package com.geekify.android.ui.theme

import android.content.Context
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import com.geekify.android.customization.AppFontChoice
import com.geekify.android.customization.TypographyDensityConfig
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Specification for a quick-downloadable font entry shown in the Fonts Manager.
 * Only legally redistributable open-source fonts (SIL OFL / Apache 2.0 from official
 * Google Fonts repositories) are downloadable. Proprietary fonts such as Google Sans
 * are explicitly marked [isDownloadable] = false to respect licensing constraints.
 */
data class QuickDownloadFontSpec(
    val id: String,
    val displayName: String,
    val regularUrl: String?,
    val boldUrl: String? = null,
    val isVariableWeight: Boolean = false,
    val licenseLabel: String,
    val isBundled: Boolean = false,
    val isDownloadable: Boolean = true,
    val unavailableReason: String? = null
)

sealed interface FontImportResult {
    data class Success(
        val fontId: String,
        val displayName: String,
        val filePath: String
    ) : FontImportResult

    data class Error(val message: String) : FontImportResult
}

sealed interface FontDownloadResult {
    data class Success(
        val fontId: String,
        val displayName: String,
        val filePath: String
    ) : FontDownloadResult

    data class Error(val message: String) : FontDownloadResult
}

/**
 * Centralized manager for importing, downloading, validating, caching, and resolving
 * custom TTF/OTF fonts in Geekify's app-private persistent storage (`filesDir/fonts`).
 */
object GeekifyFontManager {

    const val FONTS_DIR_NAME = "fonts"
    const val MAX_FONT_FILE_BYTES: Long = 20L * 1024L * 1024L // 20 MB
    const val MIN_FONT_FILE_BYTES: Long = 128L

    /**
     * Quick font catalog.
     * - Google Sans is proprietary and cannot legally be redistributed without license;
     *   it is explicitly marked unavailable rather than scraping unauthorized sources.
     * - Lato, Roboto, Comic Neue, and Atkinson Hyperlegible Next are fetched on demand
     *   over HTTPS directly from Google's official `google/fonts` repository under
     *   SIL Open Font License 1.1 / Apache License 2.0.
     */
    val quickDownloadCatalog: List<QuickDownloadFontSpec> = listOf(
        QuickDownloadFontSpec(
            id = "google_sans",
            displayName = "Google Sans",
            regularUrl = null,
            isVariableWeight = true,
            licenseLabel = "SIL OFL 1.1",
            isBundled = true,
            isDownloadable = false,
            unavailableReason = null
        ),
        QuickDownloadFontSpec(
            id = "lato",
            displayName = "Lato",
            regularUrl = "https://raw.githubusercontent.com/google/fonts/main/ofl/lato/Lato-Regular.ttf",
            boldUrl = "https://raw.githubusercontent.com/google/fonts/main/ofl/lato/Lato-Bold.ttf",
            isVariableWeight = false,
            licenseLabel = "SIL OFL 1.1"
        ),
        QuickDownloadFontSpec(
            id = "roboto",
            displayName = "Roboto",
            regularUrl = "https://raw.githubusercontent.com/google/fonts/main/ofl/roboto/Roboto%5Bwdth%2Cwght%5D.ttf",
            boldUrl = null,
            isVariableWeight = true,
            licenseLabel = "Apache 2.0"
        ),
        QuickDownloadFontSpec(
            id = "comic_neue",
            displayName = "Comic Neue",
            regularUrl = "https://raw.githubusercontent.com/google/fonts/main/ofl/comicneue/ComicNeue-Regular.ttf",
            boldUrl = "https://raw.githubusercontent.com/google/fonts/main/ofl/comicneue/ComicNeue-Bold.ttf",
            isVariableWeight = false,
            licenseLabel = "SIL OFL 1.1"
        ),
        QuickDownloadFontSpec(
            id = "atkinson_hyperlegible_next",
            displayName = "Atkinson Hyperlegible Next",
            regularUrl = "https://raw.githubusercontent.com/google/fonts/main/ofl/atkinsonhyperlegiblenext/AtkinsonHyperlegibleNext%5Bwght%5D.ttf",
            boldUrl = null,
            isVariableWeight = true,
            licenseLabel = "SIL OFL 1.1"
        )
    )

    private val fontFamilyCache = ConcurrentHashMap<String, FontFamily>()
    private val inFlightDownloads = ConcurrentHashMap<String, Boolean>()

    fun fontsDirectory(context: Context): File {
        val dir = File(context.filesDir, FONTS_DIR_NAME)
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    fun findQuickSpec(fontId: String): QuickDownloadFontSpec? =
        quickDownloadCatalog.firstOrNull { it.id == fontId }

    fun getQuickFontRegularFile(context: Context, spec: QuickDownloadFontSpec): File {
        val safeName = sanitizeInternalFileName("quick_${spec.id}_regular", "ttf")
        return File(fontsDirectory(context), safeName)
    }

    fun getQuickFontBoldFile(context: Context, spec: QuickDownloadFontSpec): File {
        val safeName = sanitizeInternalFileName("quick_${spec.id}_bold", "ttf")
        return File(fontsDirectory(context), safeName)
    }

    fun isQuickFontInstalled(context: Context, spec: QuickDownloadFontSpec): Boolean {
        if (spec.isBundled) return true
        if (!spec.isDownloadable) return false
        val file = getQuickFontRegularFile(context, spec)
        return file.exists() && file.isFile && file.length() >= MIN_FONT_FILE_BYTES && hasValidFontHeader(file)
    }

    fun isDownloadInProgress(fontId: String): Boolean = inFlightDownloads.containsKey(fontId)

    /**
     * Validates SFNT / OpenType / TrueType magic bytes (first 4 bytes of the file):
     * - `00 01 00 00`: TrueType outline font (.ttf)
     * - `4F 54 54 4F` ("OTTO"): OpenType CFF font (.otf)
     * - `74 72 75 65` ("true"): Apple TrueType font (.ttf)
     * - `74 79 70 31` ("typ1"): Type 1 SFNT font
     * - `74 74 63 66` ("ttcf"): TrueType Collection
     */
    fun isValidFontMagicBytes(header: ByteArray): Boolean {
        if (header.size < 4) return false
        val b0 = header[0].toInt() and 0xFF
        val b1 = header[1].toInt() and 0xFF
        val b2 = header[2].toInt() and 0xFF
        val b3 = header[3].toInt() and 0xFF

        val isTrueType = b0 == 0x00 && b1 == 0x01 && b2 == 0x00 && b3 == 0x00
        val isOpenType = b0 == 0x4F && b1 == 0x54 && b2 == 0x54 && b3 == 0x4F // OTTO
        val isAppleTrue = b0 == 0x74 && b1 == 0x72 && b2 == 0x75 && b3 == 0x65 // true
        val isTyp1 = b0 == 0x74 && b1 == 0x79 && b2 == 0x70 && b3 == 0x31 // typ1
        val isTtc = b0 == 0x74 && b1 == 0x74 && b2 == 0x63 && b3 == 0x66 // ttcf
        return isTrueType || isOpenType || isAppleTrue || isTyp1 || isTtc
    }

    fun isSupportedFontExtension(fileName: String): Boolean {
        val ext = fileName.substringAfterLast('.', "").lowercase(Locale.ROOT)
        return ext == "ttf" || ext == "otf"
    }

    fun detectExtensionFromMagicBytes(header: ByteArray): String {
        if (header.size >= 4 &&
            header[0] == 0x4F.toByte() &&
            header[1] == 0x54.toByte() &&
            header[2] == 0x54.toByte() &&
            header[3] == 0x4F.toByte()
        ) {
            return "otf"
        }
        return "ttf"
    }

    /**
     * Produces a safe, readable display name from a raw filename or document label.
     * Strips directory separators, extensions, path-traversal sequences, and control characters.
     */
    fun sanitizeFontDisplayName(rawName: String): String {
        val fileNameOnly = rawName
            .replace('\\', '/')
            .substringAfterLast('/')
        val withoutExt = if (isSupportedFontExtension(fileNameOnly)) {
            fileNameOnly.substringBeforeLast('.')
        } else {
            fileNameOnly
        }
        val base = withoutExt
            .replace("..", "")
            .replace(Regex("[_\\-]+"), " ")
            .replace(Regex("[^\\p{L}\\p{N} .()]"), "")
            .replace(Regex("\\s+"), " ")
            .trim('.', ' ')
            .take(48)
        return base.ifBlank { "Custom Font" }
    }

    /**
     * Produces a safe internal filename with no path traversal, restricted to `[a-z0-9_-]`.
     */
    fun sanitizeInternalFileName(baseId: String, extension: String): String {
        val cleanBase = baseId
            .lowercase(Locale.ROOT)
            .replace(Regex("[^a-z0-9_-]"), "_")
            .replace(Regex("_+"), "_")
            .trim('_')
            .take(64)
            .ifBlank { "custom_font" }
        val cleanExt = when (extension.lowercase(Locale.ROOT)) {
            "otf" -> "otf"
            else -> "ttf"
        }
        return "$cleanBase.$cleanExt"
    }

    /**
     * Ensures [candidate] resolves strictly inside [fontsDir] (prevents path traversal).
     */
    fun isSafeFontFileWithinDir(fontsDir: File, candidate: File): Boolean = runCatching {
        val dirPath = fontsDir.canonicalFile.toPath()
        val filePath = candidate.canonicalFile.toPath()
        filePath.startsWith(dirPath) && filePath != dirPath
    }.getOrDefault(false)

    fun hasValidFontHeader(file: File): Boolean {
        if (!file.exists() || !file.isFile || file.length() < MIN_FONT_FILE_BYTES) return false
        val header = ByteArray(4)
        return runCatching {
            FileInputStream(file).use { input ->
                input.read(header) == 4 && isValidFontMagicBytes(header)
            }
        }.getOrDefault(false)
    }

    fun invalidateCache(filePath: String? = null) {
        if (filePath.isNullOrBlank()) {
            fontFamilyCache.clear()
        } else {
            fontFamilyCache.remove(filePath)
        }
    }

    /**
     * Imports a user-selected TTF or OTF font from a SAF [Uri] into app-private storage.
     * Validates size, magic bytes, and Android Typeface parsing before accepting.
     */
    suspend fun importFontFromUri(context: Context, uri: Uri): FontImportResult = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        var rawFileName = "Custom Font.ttf"
        var declaredSize: Long = -1L

        runCatching {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (nameIdx >= 0) {
                        cursor.getString(nameIdx)?.takeIf { it.isNotBlank() }?.let { rawFileName = it }
                    }
                    val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (sizeIdx >= 0 && !cursor.isNull(sizeIdx)) {
                        declaredSize = cursor.getLong(sizeIdx)
                    }
                }
            }
        }

        if (declaredSize > MAX_FONT_FILE_BYTES) {
            return@withContext FontImportResult.Error("Font file is too large (max 20 MB).")
        }

        val fontsDir = fontsDirectory(context)
        val tempFile = File(fontsDir, "import_tmp_${System.nanoTime()}.tmp")

        try {
            val digest = MessageDigest.getInstance("SHA-256")
            var totalBytes = 0L
            val header = ByteArray(4)
            var headerBytesRead = 0

            val opened = resolver.openInputStream(uri)
                ?: return@withContext FontImportResult.Error("Could not read the selected font file.")

            opened.use { input ->
                FileOutputStream(tempFile).use { output ->
                    val buffer = ByteArray(16 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        totalBytes += read
                        if (totalBytes > MAX_FONT_FILE_BYTES) {
                            return@withContext FontImportResult.Error("Font file exceeds the 20 MB size limit.")
                        }
                        if (headerBytesRead < 4) {
                            val toCopy = minOf(4 - headerBytesRead, read)
                            System.arraycopy(buffer, 0, header, headerBytesRead, toCopy)
                            headerBytesRead += toCopy
                        }
                        digest.update(buffer, 0, read)
                        output.write(buffer, 0, read)
                    }
                }
            }

            if (totalBytes < MIN_FONT_FILE_BYTES) {
                return@withContext FontImportResult.Error("Selected file is empty or not a valid font.")
            }

            if (headerBytesRead < 4 || !isValidFontMagicBytes(header)) {
                return@withContext FontImportResult.Error("Unsupported file format. Please choose a valid .ttf or .otf font.")
            }

            // Verify Android's native font parser accepts the file
            val parsedTypeface = runCatching { Typeface.createFromFile(tempFile) }.getOrNull()
            if (parsedTypeface == null) {
                return@withContext FontImportResult.Error("This font file appears corrupted and could not be loaded.")
            }

            val ext = if (isSupportedFontExtension(rawFileName)) {
                rawFileName.substringAfterLast('.', "ttf").lowercase(Locale.ROOT)
            } else {
                detectExtensionFromMagicBytes(header)
            }
            val displayName = sanitizeFontDisplayName(rawFileName)
            val hashPrefix = digest.digest().take(6).joinToString("") { "%02x".format(it) }
            val fontId = "imported_$hashPrefix"
            val finalName = sanitizeInternalFileName("${fontId}_${displayName.take(24)}", ext)
            val finalFile = File(fontsDir, finalName)

            if (!isSafeFontFileWithinDir(fontsDir, finalFile)) {
                return@withContext FontImportResult.Error("Invalid font filename.")
            }

            if (finalFile.exists()) {
                // Identical font already imported; reuse existing file to avoid duplicates
                tempFile.delete()
            } else if (!tempFile.renameTo(finalFile)) {
                tempFile.copyTo(finalFile, overwrite = true)
                tempFile.delete()
            }

            invalidateCache(finalFile.absolutePath)
            FontImportResult.Success(
                fontId = fontId,
                displayName = displayName,
                filePath = finalFile.absolutePath
            )
        } catch (e: Exception) {
            FontImportResult.Error("Failed to import font: ${e.localizedMessage ?: "Unknown error"}")
        } finally {
            if (tempFile.exists()) {
                tempFile.delete()
            }
        }
    }

    /**
     * Downloads a legally redistributable quick font on demand over HTTPS into app-private storage.
     */
    suspend fun downloadQuickFont(
        context: Context,
        spec: QuickDownloadFontSpec,
        httpClient: OkHttpClient,
        onProgress: (Float) -> Unit
    ): FontDownloadResult = withContext(Dispatchers.IO) {
        if (!spec.isDownloadable || spec.regularUrl.isNullOrBlank()) {
            return@withContext FontDownloadResult.Error(
                spec.unavailableReason ?: "${spec.displayName} is not available for download."
            )
        }
        if (inFlightDownloads.putIfAbsent(spec.id, true) != null) {
            return@withContext FontDownloadResult.Error("${spec.displayName} is already downloading.")
        }

        val fontsDir = fontsDirectory(context)
        val regularDest = getQuickFontRegularFile(context, spec)
        val boldDest = getQuickFontBoldFile(context, spec)
        val hasBold = !spec.boldUrl.isNullOrBlank()

        try {
            val regularOk = downloadSingleFontFile(
                httpClient = httpClient,
                url = spec.regularUrl,
                destFile = regularDest,
                fontsDir = fontsDir,
                onProgress = { p -> onProgress(if (hasBold) p * 0.65f else p) }
            )
            if (regularOk != null) {
                return@withContext FontDownloadResult.Error(regularOk)
            }

            val boldUrl = spec.boldUrl
            if (!boldUrl.isNullOrBlank()) {
                // Bold companion is best-effort; if it fails, regular still works cleanly
                downloadSingleFontFile(
                    httpClient = httpClient,
                    url = boldUrl,
                    destFile = boldDest,
                    fontsDir = fontsDir,
                    onProgress = { p -> onProgress(0.65f + p * 0.35f) }
                )
            }

            invalidateCache(regularDest.absolutePath)
            onProgress(1f)
            FontDownloadResult.Success(
                fontId = spec.id,
                displayName = spec.displayName,
                filePath = regularDest.absolutePath
            )
        } finally {
            inFlightDownloads.remove(spec.id)
        }
    }

    private fun downloadSingleFontFile(
        httpClient: OkHttpClient,
        url: String,
        destFile: File,
        fontsDir: File,
        onProgress: (Float) -> Unit
    ): String? {
        if (!url.startsWith("https://")) {
            return "Insecure font URL rejected."
        }
        if (!isSafeFontFileWithinDir(fontsDir, destFile)) {
            return "Invalid target font path."
        }

        val tempFile = File(fontsDir, "${destFile.name}.${System.nanoTime()}.part")
        try {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "Geekify-Android")
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return "Download failed (HTTP ${response.code})."
                }
                val body = response.body
                val contentLength = body.contentLength()
                if (contentLength > MAX_FONT_FILE_BYTES) {
                    return "Downloaded font exceeds size limit."
                }

                val header = ByteArray(4)
                var headerRead = 0
                var downloaded = 0L

                body.byteStream().use { input ->
                    FileOutputStream(tempFile).use { output ->
                        val buffer = ByteArray(16 * 1024)
                        while (true) {
                            val read = input.read(buffer)
                            if (read <= 0) break
                            downloaded += read
                            if (downloaded > MAX_FONT_FILE_BYTES) {
                                return "Downloaded font exceeds size limit."
                            }
                            if (headerRead < 4) {
                                val copyLen = minOf(4 - headerRead, read)
                                System.arraycopy(buffer, 0, header, headerRead, copyLen)
                                headerRead += copyLen
                            }
                            output.write(buffer, 0, read)
                            if (contentLength > 0L) {
                                onProgress((downloaded.toFloat() / contentLength.toFloat()).coerceIn(0f, 1f))
                            }
                        }
                    }
                }

                if (downloaded < MIN_FONT_FILE_BYTES || headerRead < 4 || !isValidFontMagicBytes(header)) {
                    return "Downloaded file is not a valid TTF/OTF font."
                }

                val parsed = runCatching { Typeface.createFromFile(tempFile) }.getOrNull()
                    ?: return "Downloaded font failed validation."
                @Suppress("UNUSED_VARIABLE")
                val unused = parsed

                if (destFile.exists()) destFile.delete()
                if (!tempFile.renameTo(destFile)) {
                    tempFile.copyTo(destFile, overwrite = true)
                    tempFile.delete()
                }
                return null
            }
        } catch (e: Exception) {
            return e.localizedMessage?.takeIf { it.isNotBlank() } ?: "Network error while downloading font."
        } finally {
            if (tempFile.exists()) {
                tempFile.delete()
            }
        }
    }

    /**
     * Safely deletes an imported font file inside `filesDir/fonts` and clears its cache entry.
     */
    fun deleteImportedFontSafely(context: Context, filePath: String) {
        if (filePath.isBlank()) return
        invalidateCache(filePath)
        runCatching {
            val fontsDir = fontsDirectory(context)
            val target = File(filePath)
            if (target.exists() && target.name.startsWith("imported_") && isSafeFontFileWithinDir(fontsDir, target)) {
                target.delete()
            }
        }
    }

    /**
     * Resolves the active Compose [FontFamily] for [config] with caching and safe fallback.
     * - [AppFontChoice.SOHNE_BREIT] -> built-in [SohneBreitFontFamily]
     * - [AppFontChoice.SYSTEM_DEFAULT] -> [FontFamily.Default]
     * - [AppFontChoice.DOWNLOADED] / [AppFontChoice.IMPORTED] -> cached file-backed [FontFamily],
     *   falling back gracefully to [FontFamily.Default] if the file is missing or corrupted.
     */
    fun resolveFontFamily(config: TypographyDensityConfig, context: Context? = null): FontFamily {
        return when (config.fontChoice) {
            AppFontChoice.SOHNE_BREIT -> SohneBreitFontFamily
            AppFontChoice.SYSTEM_DEFAULT -> FontFamily.Default
            AppFontChoice.DOWNLOADED, AppFontChoice.IMPORTED -> {
                if (config.fontChoice == AppFontChoice.DOWNLOADED && config.activeFontId == "google_sans") {
                    return GoogleSansFontFamily
                }
                val path = config.activeFontPath
                if (path.isBlank()) return FontFamily.Default
                fontFamilyCache[path]?.let { return it }

                val file = File(path)
                if (context != null && !isSafeFontFileWithinDir(fontsDirectory(context), file)) {
                    return FontFamily.Default
                }
                if (!hasValidFontHeader(file)) {
                    return FontFamily.Default
                }

                val built = buildFontFamilyFromFile(
                    regularFile = file,
                    spec = if (config.fontChoice == AppFontChoice.DOWNLOADED) findQuickSpec(config.activeFontId) else null,
                    context = context
                ) ?: return FontFamily.Default

                fontFamilyCache[path] = built
                built
            }
        }
    }

    @OptIn(ExperimentalTextApi::class)
    private fun buildFontFamilyFromFile(
        regularFile: File,
        spec: QuickDownloadFontSpec?,
        context: Context?
    ): FontFamily? = runCatching {
        // Validate via Android Typeface before constructing Compose FontFamily
        Typeface.createFromFile(regularFile) ?: return null

        val boldCompanion = if (spec != null && context != null && !spec.boldUrl.isNullOrBlank()) {
            getQuickFontBoldFile(context, spec).takeIf { hasValidFontHeader(it) }
        } else {
            null
        }

        if (spec?.isVariableWeight == true && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val weights = listOf(
                FontWeight.ExtraLight to 200,
                FontWeight.Light to 300,
                FontWeight.Normal to 400,
                FontWeight.Medium to 500,
                FontWeight.SemiBold to 600,
                FontWeight.Bold to 700,
                FontWeight.ExtraBold to 800,
                FontWeight.Black to 900
            )
            val fonts = weights.map { (weight, axisValue) ->
                Font(
                    file = regularFile,
                    weight = weight,
                    style = FontStyle.Normal,
                    variationSettings = FontVariation.Settings(FontVariation.weight(axisValue))
                )
            }
            FontFamily(fonts)
        } else if (boldCompanion != null) {
            FontFamily(
                Font(regularFile, FontWeight.ExtraLight, FontStyle.Normal),
                Font(regularFile, FontWeight.Light, FontStyle.Normal),
                Font(regularFile, FontWeight.Normal, FontStyle.Normal),
                Font(regularFile, FontWeight.Medium, FontStyle.Normal),
                Font(boldCompanion, FontWeight.SemiBold, FontStyle.Normal),
                Font(boldCompanion, FontWeight.Bold, FontStyle.Normal),
                Font(boldCompanion, FontWeight.ExtraBold, FontStyle.Normal),
                Font(boldCompanion, FontWeight.Black, FontStyle.Normal)
            )
        } else {
            FontFamily(
                Font(regularFile, FontWeight.ExtraLight, FontStyle.Normal),
                Font(regularFile, FontWeight.Light, FontStyle.Normal),
                Font(regularFile, FontWeight.Normal, FontStyle.Normal),
                Font(regularFile, FontWeight.Medium, FontStyle.Normal),
                Font(regularFile, FontWeight.SemiBold, FontStyle.Normal),
                Font(regularFile, FontWeight.Bold, FontStyle.Normal),
                Font(regularFile, FontWeight.ExtraBold, FontStyle.Normal),
                Font(regularFile, FontWeight.Black, FontStyle.Normal)
            )
        }
    }.getOrNull()
}
