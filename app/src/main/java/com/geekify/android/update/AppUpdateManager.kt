package com.geekify.android.update

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import com.geekify.android.BuildConfig
import com.geekify.android.notifications.UpdateChecker
import java.io.File
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request

data class AvailableUpdate(
    val version: String,
    val downloadUrl: String,
    val releaseNotes: String = "",
    val expectedSha256: String? = null,
    val sha256AssetUrl: String? = null
)

/**
 * Checks GitHub Releases and installs an APK supplied by the project's latest release.
 *
 * Security & reliability guarantees:
 * - Uses `If-None-Match` ETags and respects GitHub API rate-limit headers (`X-RateLimit-Reset` / `Retry-After`).
 * - Downloads into a temporary `.part` file and cleans up partial files on interruption or verification failure.
 * - Verifies published SHA-256 checksums (from asset `digest`, `.sha256` checksum asset, or release notes).
 * - Verifies the downloaded APK's package name and signing certificate against the installed application
 *   before finalizing the `.apk` file and before launching the system package installer.
 */
class AppUpdateManager(private val context: Context) {
    private val client = OkHttpClient.Builder()
        .callTimeout(30, TimeUnit.SECONDS)
        .build()
    private val json = Json { ignoreUnknownKeys = true }
    private val prefs by lazy {
        context.getSharedPreferences("geekify_update_cache", Context.MODE_PRIVATE)
    }

    companion object {
        private const val LATEST_RELEASE_URL = "https://api.github.com/repos/Amal-kphilip/Geekify-App/releases/latest"
        private const val KEY_ETAG = "latest_etag"
        private const val KEY_CACHED_BODY = "latest_body"
        private const val KEY_RATE_LIMIT_RESET_AT = "rate_limit_reset_at"
        private val SHA256_HEX_REGEX = Regex("\\b([a-fA-F0-9]{64})\\b")

        fun extractSha256Hex(raw: String?): String? {
            if (raw.isNullOrBlank()) return null
            val stripped = raw.trim().removePrefix("sha256:").removePrefix("SHA256:").trim()
            return SHA256_HEX_REGEX.find(stripped)?.groupValues?.getOrNull(1)?.lowercase(Locale.US)
        }

        fun sha256Hex(bytes: ByteArray): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
            return digest.joinToString("") { "%02x".format(it) }
        }

        fun verifyChecksum(actualSha256Hex: String, expectedSha256Hex: String?): Boolean {
            val expected = extractSha256Hex(expectedSha256Hex) ?: return true
            return actualSha256Hex.equals(expected, ignoreCase = true)
        }

        fun isNewerVersion(candidate: String, installed: String): Boolean {
            fun parts(version: String) = version.trim().removePrefix("v").substringBefore('-').split('.')
                .map { it.toIntOrNull() ?: 0 }
            val candidateParts = parts(candidate)
            val installedParts = parts(installed)
            val size = maxOf(candidateParts.size, installedParts.size)
            for (index in 0 until size) {
                val comparison = (candidateParts.getOrElse(index) { 0 })
                    .compareTo(installedParts.getOrElse(index) { 0 })
                if (comparison != 0) return comparison > 0
            }
            return false
        }
    }

    suspend fun findAvailableUpdate(): AvailableUpdate? = withContext(Dispatchers.IO) {
        val nowMs = System.currentTimeMillis()
        val rateLimitResetAt = prefs.getLong(KEY_RATE_LIMIT_RESET_AT, 0L)
        if (nowMs < rateLimitResetAt) {
            return@withContext prefs.getString(KEY_CACHED_BODY, null)?.let(::parseReleaseUpdate)
        }

        val cachedEtag = prefs.getString(KEY_ETAG, null)
        val requestBuilder = Request.Builder()
            .url(LATEST_RELEASE_URL)
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "Geekify-Android-Updater")
        if (!cachedEtag.isNullOrBlank()) {
            requestBuilder.header("If-None-Match", cachedEtag)
        }

        runCatching {
            client.newCall(requestBuilder.build()).execute().use { response ->
                val resetAt = UpdateChecker.parseRateLimitResetAtMs(response, nowMs)
                if (resetAt != null && !response.isSuccessful) {
                    prefs.edit().putLong(KEY_RATE_LIMIT_RESET_AT, resetAt).apply()
                    return@use prefs.getString(KEY_CACHED_BODY, null)?.let(::parseReleaseUpdate)
                }
                if (response.code == 304) {
                    return@use prefs.getString(KEY_CACHED_BODY, null)?.let(::parseReleaseUpdate)
                }
                if (!response.isSuccessful) return@use null

                val body = response.body.string()
                val newEtag = response.header("ETag")
                prefs.edit().apply {
                    putString(KEY_CACHED_BODY, body)
                    putLong(KEY_RATE_LIMIT_RESET_AT, 0L)
                    if (!newEtag.isNullOrBlank()) putString(KEY_ETAG, newEtag)
                }.apply()
                parseReleaseUpdate(body)
            }
        }.getOrNull()
    }

    internal fun parseReleaseUpdate(body: String): AvailableUpdate? = runCatching {
        val release: JsonObject = json.parseToJsonElement(body).jsonObject
        if (release["draft"]?.jsonPrimitive?.contentOrNull == "true" ||
            release["prerelease"]?.jsonPrimitive?.contentOrNull == "true"
        ) return null

        val version = release["tag_name"]?.jsonPrimitive?.contentOrNull
            ?.removePrefix("v")
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: return null

        val assets = release["assets"]?.jsonArray.orEmpty().map { it.jsonObject }
        val apkAsset = assets.firstOrNull {
            val name = it["name"]?.jsonPrimitive?.contentOrNull.orEmpty()
            name.endsWith(".apk", ignoreCase = true)
        } ?: return null

        val apkName = apkAsset["name"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val downloadUrl = apkAsset["browser_download_url"]?.jsonPrimitive?.contentOrNull
            ?.takeIf { it.startsWith("https://") }
            ?: return null
        val releaseNotes = release["body"]?.jsonPrimitive?.contentOrNull.orEmpty().trim()

        // 1. Prefer GitHub's native asset digest field ("sha256:...") when present
        val assetDigestSha256 = extractSha256Hex(apkAsset["digest"]?.jsonPrimitive?.contentOrNull)
        // 2. Or a companion .sha256 asset uploaded by the release workflow
        val sha256Asset = assets.firstOrNull {
            val name = it["name"]?.jsonPrimitive?.contentOrNull.orEmpty()
            name.equals("$apkName.sha256", ignoreCase = true) || name.endsWith(".sha256", ignoreCase = true)
        }
        val sha256AssetUrl = sha256Asset?.get("browser_download_url")?.jsonPrimitive?.contentOrNull
            ?.takeIf { it.startsWith("https://") }
        // 3. Or an explicit SHA-256 hex in release notes
        val notesSha256 = extractSha256Hex(releaseNotes)

        if (isNewerVersion(version, BuildConfig.VERSION_NAME)) {
            AvailableUpdate(
                version = version,
                downloadUrl = downloadUrl,
                releaseNotes = releaseNotes,
                expectedSha256 = assetDigestSha256 ?: notesSha256,
                sha256AssetUrl = sha256AssetUrl
            )
        } else {
            null
        }
    }.getOrNull()

    /**
     * [onProgress] is optional and purely informational: it receives 0f..1f as bytes arrive, or is
     * never called when the server does not report a size. It does not affect the download itself.
     */
    suspend fun download(update: AvailableUpdate, onProgress: (Float) -> Unit = {}): File = withContext(Dispatchers.IO) {
        require(update.downloadUrl.startsWith("https://")) { "Update download URL must use HTTPS." }
        val safeVersion = update.version.replace(Regex("[^A-Za-z0-9._-]"), "_")
        val updatesDir = File(context.cacheDir, "updates").apply { mkdirs() }
        val partFile = File(updatesDir, "Geekify-$safeVersion.apk.part")
        val destination = File(updatesDir, "Geekify-$safeVersion.apk")

        if (partFile.exists()) partFile.delete()

        val expectedSha256 = update.expectedSha256 ?: fetchRemoteSha256(update.sha256AssetUrl)

        try {
            val request = Request.Builder()
                .url(update.downloadUrl)
                .header("User-Agent", "Geekify-Android-Updater")
                .build()
            val actualSha256: String
            client.newCall(request).execute().use { response ->
                check(response.isSuccessful) { "Download failed (${response.code})" }
                val total = response.body.contentLength()
                val digest = MessageDigest.getInstance("SHA-256")
                response.body.byteStream().use { input ->
                    partFile.outputStream().use { output ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        var copied = 0L
                        var lastPercent = -1
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            output.write(buffer, 0, read)
                            digest.update(buffer, 0, read)
                            copied += read
                            if (total > 0L) {
                                val percent = (copied * 100 / total).toInt()
                                if (percent != lastPercent) {
                                    lastPercent = percent
                                    onProgress((copied.toFloat() / total).coerceIn(0f, 1f))
                                }
                            }
                        }
                        output.flush()
                        check(copied > 0L && (total <= 0L || copied == total)) {
                            "Update download was interrupted ($copied of $total bytes received)."
                        }
                    }
                }
                actualSha256 = digest.digest().joinToString("") { "%02x".format(it) }
            }

            if (!verifyChecksum(actualSha256, expectedSha256)) {
                throw SecurityException("Downloaded APK failed SHA-256 integrity verification.")
            }

            verifyApkArchiveSignature(partFile)

            if (destination.exists()) {
                destination.delete()
            }
            check(partFile.renameTo(destination)) { "Could not finalize downloaded update package." }
            destination
        } catch (e: CancellationException) {
            partFile.delete()
            throw e
        } catch (t: Throwable) {
            partFile.delete()
            throw t
        }
    }

    private fun fetchRemoteSha256(sha256AssetUrl: String?): String? {
        if (sha256AssetUrl.isNullOrBlank() || !sha256AssetUrl.startsWith("https://")) return null
        return runCatching {
            val req = Request.Builder()
                .url(sha256AssetUrl)
                .header("User-Agent", "Geekify-Android-Updater")
                .build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) null else extractSha256Hex(resp.body.string())
            }
        }.getOrNull()
    }

    /**
     * Verifies that [apkFile] is a valid Android archive for this application's package name and
     * is signed with the same signing certificate as the currently installed application.
     */
    internal fun verifyApkArchiveSignature(apkFile: File) {
        val pm = context.packageManager
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            @Suppress("DEPRECATION")
            PackageManager.GET_SIGNATURES
        }

        val archiveInfo: PackageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getPackageArchiveInfo(apkFile.absolutePath, PackageManager.PackageInfoFlags.of(flags.toLong()))
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageArchiveInfo(apkFile.absolutePath, flags)
        } ?: throw SecurityException("Downloaded file is not a valid APK package.")

        if (archiveInfo.packageName != context.packageName) {
            throw SecurityException("Downloaded APK package name (${archiveInfo.packageName}) does not match ${context.packageName}.")
        }

        val installedInfo: PackageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(flags.toLong()))
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(context.packageName, flags)
        }

        val archiveDigests = extractCertificateDigests(archiveInfo)
        val installedDigests = extractCertificateDigests(installedInfo)
        if (archiveDigests.isEmpty() || installedDigests.isEmpty() || archiveDigests.intersect(installedDigests).isEmpty()) {
            throw SecurityException("Downloaded APK signing certificate does not match the installed application.")
        }
    }

    private fun extractCertificateDigests(info: PackageInfo): Set<String> {
        val signatures: Array<Signature> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val signingInfo = info.signingInfo ?: return emptySet()
            if (signingInfo.hasMultipleSigners()) {
                signingInfo.apkContentsSigners
            } else {
                signingInfo.signingCertificateHistory
            }
        } else {
            @Suppress("DEPRECATION")
            info.signatures
        } ?: return emptySet()

        return signatures.map { sig -> sha256Hex(sig.toByteArray()) }.toSet()
    }

    fun install(apk: File) {
        verifyApkArchiveSignature(apk)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !context.packageManager.canRequestPackageInstalls()) {
            context.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                data = Uri.parse("package:${context.packageName}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
            return
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apk)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            context.startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            error("No package installer is available on this device.")
        }
    }
}
