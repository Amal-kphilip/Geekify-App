package com.geekify.android.update

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.runtime.Immutable
import androidx.core.content.FileProvider
import com.geekify.android.notifications.ReleaseParser
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max

enum class ChangelogCategory(val defaultTitle: String) {
    WHATS_NEW("What's New"),
    IMPROVEMENTS("Improvements"),
    BUG_FIXES("Bug Fixes"),
    NOTES("Release Notes")
}

@Immutable
data class ChangelogSection(
    val category: ChangelogCategory,
    val title: String,
    val bullets: List<String>
)

@Immutable
data class ReleaseChangelogEntry(
    val versionName: String,
    val versionCode: Int,
    val title: String,
    val publishedAt: String?,
    val formattedDate: String?,
    val rawNotes: String,
    val sections: List<ChangelogSection>,
    val changeCount: Int,
    val isCurrentTarget: Boolean = false
)

@Immutable
data class AvailableUpdate(
    val versionName: String,
    val versionCode: Int = ReleaseParser.versionCodeFor(versionName) ?: 0,
    val title: String,
    val notes: String,
    val apkUrl: String,
    val expectedSha256: String? = null,
    val apkAssetName: String = "Geekify-$versionName.apk",
    val apkSizeBytes: Long? = null,
    val publishedAt: String? = null,
    val formattedDate: String? = null,
    val releasePageUrl: String = "https://github.com/Amal-kphilip/Geekify-App/releases/tag/v$versionName",
    val changelogTimeline: List<ReleaseChangelogEntry> = emptyList(),
    val totalChangeCount: Int = 0
)

@Immutable
data class DownloadProgressSnapshot(
    val downloadedBytes: Long,
    val totalBytes: Long?,
    val bytesPerSecond: Long = 0L,
    val etaSeconds: Long? = null,
    val isResumed: Boolean = false
) {
    val progressFraction: Float?
        get() = totalBytes?.takeIf { it > 0L }?.let { total ->
            (downloadedBytes.toDouble() / total.toDouble()).toFloat().coerceIn(0f, 1f)
        }
}

sealed interface ApkVerificationResult {
    data class Verified(
        val apkFile: File,
        val sha256Hex: String,
        val versionName: String?,
        val versionCode: Long
    ) : ApkVerificationResult

    data class Failed(
        val reason: String,
        val shouldDeleteApk: Boolean = true
    ) : ApkVerificationResult
}

sealed interface InstallLaunchResult {
    data object LaunchedInstaller : InstallLaunchResult
    data object NeedsUnknownSourcesPermission : InstallLaunchResult
    data class Failed(val reason: String) : InstallLaunchResult
}

@Immutable
data class CompletedUpdateInfo(
    val previousVersionName: String?,
    val newVersionName: String,
    val newVersionCode: Int
)

/**
 * Self-contained GitHub Releases updater with resumable byte-level downloads,
 * multi-release changelog timeline parsing, SHA-256 + signing-certificate verification,
 * and PackageInstaller / unknown-sources lifecycle coordination.
 */
@Singleton
class AppUpdateManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    private val json = Json { ignoreUnknownKeys = true }

    private val prefs by lazy {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    companion object {
        private const val OWNER = "Amal-kphilip"
        private const val REPO = "Geekify-App"
        private const val LATEST_RELEASE_URL = "https://api.github.com/repos/$OWNER/$REPO/releases/latest"
        private const val RELEASES_LIST_URL = "https://api.github.com/repos/$OWNER/$REPO/releases?per_page=8"
        private const val RELEASES_HTML_BASE = "https://github.com/$OWNER/$REPO/releases"
        private const val PREFS_NAME = "geekify_app_updater_prefs"
        private const val KEY_DISMISSED_VERSION = "dismissed_version_name"
        private const val KEY_PENDING_INSTALL_VERSION_NAME = "pending_install_version_name"
        private const val KEY_PENDING_INSTALL_VERSION_CODE = "pending_install_version_code"
        private const val KEY_PENDING_FROM_VERSION_NAME = "pending_from_version_name"
        private const val KEY_PENDING_INSTALL_TIMESTAMP = "pending_install_timestamp"
        private const val KEY_PART_URL = "part_download_url"
        private const val KEY_PART_VERSION = "part_download_version"
        private const val KEY_CACHED_RELEASES_ETAG = "cached_releases_etag"
        private const val KEY_CACHED_RELEASES_JSON = "cached_releases_json"

        fun extractSha256Hex(raw: String?): String? {
            if (raw.isNullOrBlank()) return null
            return UpdateMetadataParser.extractSha256Hex(raw)
        }

        fun verifyChecksum(actualHex: String, expectedRaw: String?): Boolean {
            val expectedHex = extractSha256Hex(expectedRaw) ?: return false
            val actualClean = extractSha256Hex(actualHex) ?: return false
            return MessageDigest.isEqual(
                actualClean.toByteArray(Charsets.US_ASCII),
                expectedHex.toByteArray(Charsets.US_ASCII)
            )
        }
    }

    /**
     * Checks GitHub Releases for a newer stable release than [currentVersion].
     * Also fetches recent releases so the user can browse the expandable changelog timeline.
     */
    suspend fun checkForUpdate(
        currentVersion: String,
        ignoreDismissed: Boolean = false
    ): AvailableUpdate? = withContext(Dispatchers.IO) {
        val timeline = fetchReleaseTimeline(currentVersion)
        val latestReleaseObj = fetchLatestReleaseJson() ?: return@withContext null

        val parsedUpdate = parseAvailableUpdateFromJson(
            releaseObj = latestReleaseObj,
            currentVersion = currentVersion,
            timeline = timeline,
            sha256Fetcher = { url -> fetchSha256Text(url) }
        ) ?: return@withContext null

        if (!ignoreDismissed && isVersionDismissed(parsedUpdate.versionName)) {
            return@withContext null
        }

        parsedUpdate
    }

    /**
     * Fetches the recent release changelog timeline even when the user is already up to date.
     */
    suspend fun fetchRecentChangelog(currentVersion: String): List<ReleaseChangelogEntry> =
        withContext(Dispatchers.IO) {
            fetchReleaseTimeline(currentVersion)
        }

    private fun fetchLatestReleaseJson(): JsonObject? = runCatching {
        val req = Request.Builder()
            .url(LATEST_RELEASE_URL)
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "Geekify-Android-Updater")
            .build()
        http.newCall(req).execute().use { res ->
            if (!res.isSuccessful) return null
            val body = res.body.string()
            if (body.isBlank()) return null
            json.parseToJsonElement(body).jsonObject
        }
    }.getOrNull()

    private fun fetchReleaseTimeline(currentVersion: String): List<ReleaseChangelogEntry> = runCatching {
        val cachedEtag = prefs.getString(KEY_CACHED_RELEASES_ETAG, null)
        val reqBuilder = Request.Builder()
            .url(RELEASES_LIST_URL)
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "Geekify-Android-Updater")
        if (!cachedEtag.isNullOrBlank()) {
            reqBuilder.header("If-None-Match", cachedEtag)
        }

        val rawJson = http.newCall(reqBuilder.build()).execute().use { res ->
            when {
                res.code == 304 -> prefs.getString(KEY_CACHED_RELEASES_JSON, null)
                res.isSuccessful -> {
                    val body = res.body.string()
                    if (body.isNotBlank()) {
                        prefs.edit()
                            .putString(KEY_CACHED_RELEASES_ETAG, res.header("ETag"))
                            .putString(KEY_CACHED_RELEASES_JSON, body)
                            .apply()
                    }
                    body
                }
                else -> prefs.getString(KEY_CACHED_RELEASES_JSON, null)
            }
        } ?: return emptyList()

        val array = json.parseToJsonElement(rawJson).jsonArray
        parseReleaseTimelineJson(array, currentVersion)
    }.getOrDefault(emptyList())

    /**
     * Returns an existing verified APK on disk for [update] if one was already downloaded
     * and passes full verification, avoiding redundant network downloads.
     */
    suspend fun getExistingVerifiedApk(update: AvailableUpdate): File? = withContext(Dispatchers.IO) {
        val target = targetApkFile(update.versionName)
        if (!target.exists() || target.length() <= 0L) return@withContext null
        when (verifyDownloadedApk(target, update.expectedSha256, update.apkSizeBytes)) {
            is ApkVerificationResult.Verified -> target
            is ApkVerificationResult.Failed -> null
        }
    }

    /**
     * Streams the APK to cache/updates with HTTP Range resume support and byte-level progress,
     * speed, and ETA calculation. Does NOT silently verify inside download so the caller can
     * transition explicitly to the `VERIFYING` UI state.
     */
    suspend fun downloadApk(
        update: AvailableUpdate,
        onProgress: (DownloadProgressSnapshot) -> Unit
    ): File = withContext(Dispatchers.IO) {
        val dir = updatesDir()
        val safeVersion = sanitizeVersionForFile(update.versionName)
        val target = File(dir, "Geekify-$safeVersion.apk")
        val temp = File(dir, "Geekify-$safeVersion.apk.part")

        // Clean up older versions' files while preserving the active version's .part file if resumable
        cleanupStaleApks(keepVersionName = update.versionName)

        val previousUrl = prefs.getString(KEY_PART_URL, null)
        val previousVersion = prefs.getString(KEY_PART_VERSION, null)
        if (temp.exists() && (previousUrl != update.apkUrl || previousVersion != update.versionName)) {
            temp.delete()
        }

        val existingBytes = if (temp.exists()) temp.length().coerceAtLeast(0L) else 0L
        prefs.edit()
            .putString(KEY_PART_URL, update.apkUrl)
            .putString(KEY_PART_VERSION, update.versionName)
            .apply()

        val reqBuilder = Request.Builder()
            .url(update.apkUrl)
            .header("User-Agent", "Geekify-Android-Updater")
        if (existingBytes > 0L) {
            reqBuilder.header("Range", "bytes=$existingBytes-")
        }

        try {
            http.newCall(reqBuilder.build()).execute().use { res ->
                // If 416 Range Not Satisfiable, delete temp and retry from scratch once
                if (res.code == 416 && existingBytes > 0L) {
                    temp.delete()
                    return@withContext downloadFreshApk(update, temp, target, onProgress)
                }

                if (!res.isSuccessful && res.code != 206) {
                    throw IOException("Download failed with HTTP ${res.code}")
                }

                val isResumed = res.code == 206 && existingBytes > 0L
                val body = res.body
                val contentLength = body.contentLength().takeIf { it > 0L }
                val totalBytes = when {
                    isResumed && contentLength != null -> existingBytes + contentLength
                    contentLength != null -> contentLength
                    update.apkSizeBytes != null && update.apkSizeBytes > 0L -> update.apkSizeBytes
                    else -> null
                }

                val startOffset = if (isResumed) existingBytes else 0L
                if (!isResumed && temp.exists()) {
                    temp.delete()
                }

                var downloaded = startOffset
                var maxReportedDownloaded = startOffset
                val startTimeNs = System.nanoTime()
                var lastEmitNs = startTimeNs
                var smoothedSpeedBps = 0.0
                var lastSampleBytes = startOffset
                var lastSampleTimeNs = startTimeNs

                onProgress(
                    DownloadProgressSnapshot(
                        downloadedBytes = downloaded,
                        totalBytes = totalBytes,
                        bytesPerSecond = 0L,
                        etaSeconds = null,
                        isResumed = isResumed
                    )
                )

                body.byteStream().use { input ->
                    RandomAccessFile(temp, "rw").use { raf ->
                        if (isResumed) {
                            raf.seek(startOffset)
                        } else {
                            raf.setLength(0L)
                        }
                        val buf = ByteArray(32 * 1024)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val read = input.read(buf)
                            if (read <= 0) break
                            raf.write(buf, 0, read)
                            downloaded += read
                            maxReportedDownloaded = max(maxReportedDownloaded, downloaded)

                            val nowNs = System.nanoTime()
                            val elapsedSinceEmitMs = (nowNs - lastEmitNs) / 1_000_000L
                            if (elapsedSinceEmitMs >= 80L || (totalBytes != null && downloaded >= totalBytes)) {
                                val sampleDeltaSec = ((nowNs - lastSampleTimeNs) / 1_000_000_000.0).coerceAtLeast(0.05)
                                val instantSpeed = (downloaded - lastSampleBytes) / sampleDeltaSec
                                smoothedSpeedBps = if (smoothedSpeedBps <= 1.0) {
                                    instantSpeed
                                } else {
                                    smoothedSpeedBps * 0.72 + instantSpeed * 0.28
                                }
                                lastSampleBytes = downloaded
                                lastSampleTimeNs = nowNs
                                lastEmitNs = nowNs

                                val speedLong = smoothedSpeedBps.toLong().coerceAtLeast(0L)
                                val etaSec = if (totalBytes != null && totalBytes > maxReportedDownloaded && speedLong > 1024L) {
                                    ((totalBytes - maxReportedDownloaded) / speedLong).coerceAtLeast(1L)
                                } else if (totalBytes != null && maxReportedDownloaded >= totalBytes) {
                                    0L
                                } else {
                                    null
                                }

                                onProgress(
                                    DownloadProgressSnapshot(
                                        downloadedBytes = maxReportedDownloaded,
                                        totalBytes = totalBytes,
                                        bytesPerSecond = speedLong,
                                        etaSeconds = etaSec,
                                        isResumed = isResumed
                                    )
                                )
                            }
                        }
                    }
                }

                if (totalBytes != null && downloaded < totalBytes) {
                    throw IOException("Incomplete APK download ($downloaded of $totalBytes bytes)")
                }

                onProgress(
                    DownloadProgressSnapshot(
                        downloadedBytes = max(maxReportedDownloaded, downloaded),
                        totalBytes = totalBytes ?: downloaded,
                        bytesPerSecond = smoothedSpeedBps.toLong().coerceAtLeast(0L),
                        etaSeconds = 0L,
                        isResumed = isResumed
                    )
                )
            }

            if (target.exists() && !target.delete()) {
                throw IOException("Could not replace previous staged APK")
            }
            if (!temp.renameTo(target)) {
                temp.copyTo(target, overwrite = true)
                temp.delete()
            }
            prefs.edit().remove(KEY_PART_URL).remove(KEY_PART_VERSION).apply()
            target
        } catch (cancelled: CancellationException) {
            // Keep .part file intact on cancellation or network drop so HTTP Range can resume!
            throw cancelled
        }
    }

    private suspend fun downloadFreshApk(
        update: AvailableUpdate,
        temp: File,
        target: File,
        onProgress: (DownloadProgressSnapshot) -> Unit
    ): File {
        val req = Request.Builder()
            .url(update.apkUrl)
            .header("User-Agent", "Geekify-Android-Updater")
            .build()
        http.newCall(req).execute().use { res ->
            if (!res.isSuccessful) throw IOException("Download failed with HTTP ${res.code}")
            val body = res.body
            val totalBytes = body.contentLength().takeIf { it > 0L } ?: update.apkSizeBytes
            var downloaded = 0L
            val startNs = System.nanoTime()
            var lastEmitNs = startNs

            body.byteStream().use { input ->
                FileOutputStream(temp).use { output ->
                    val buf = ByteArray(32 * 1024)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val read = input.read(buf)
                        if (read <= 0) break
                        output.write(buf, 0, read)
                        downloaded += read
                        val nowNs = System.nanoTime()
                        if ((nowNs - lastEmitNs) / 1_000_000L >= 80L) {
                            val elapsedSec = ((nowNs - startNs) / 1_000_000_000.0).coerceAtLeast(0.05)
                            val speed = (downloaded / elapsedSec).toLong().coerceAtLeast(0L)
                            val eta = if (totalBytes != null && totalBytes > downloaded && speed > 1024L) {
                                (totalBytes - downloaded) / speed
                            } else null
                            lastEmitNs = nowNs
                            onProgress(
                                DownloadProgressSnapshot(
                                    downloadedBytes = downloaded,
                                    totalBytes = totalBytes,
                                    bytesPerSecond = speed,
                                    etaSeconds = eta,
                                    isResumed = false
                                )
                            )
                        }
                    }
                    output.fd.sync()
                }
            }
        }
        if (target.exists()) target.delete()
        if (!temp.renameTo(target)) {
            temp.copyTo(target, overwrite = true)
            temp.delete()
        }
        return target
    }

    /**
     * Explicit verification stage:
     * 1. Verifies file existence and non-zero size
     * 2. Verifies SHA-256 digest when provided
     * 3. Parses APK archive info via PackageManager
     * 4. Confirms packageName matches Geekify
     * 5. Confirms archive versionCode is >= installed versionCode (prevents downgrade attacks)
     * 6. Confirms APK signing certificate SHA-256 matches installed Geekify signature
     */
    suspend fun verifyDownloadedApk(
        apkFile: File,
        expectedSha256: String?,
        expectedSizeBytes: Long? = null
    ): ApkVerificationResult = withContext(Dispatchers.IO) {
        if (!apkFile.exists() || apkFile.length() <= 0L) {
            return@withContext ApkVerificationResult.Failed(
                reason = "Downloaded APK file is missing or empty.",
                shouldDeleteApk = true
            )
        }

        if (expectedSizeBytes != null && expectedSizeBytes > 0L && apkFile.length() != expectedSizeBytes) {
            apkFile.delete()
            return@withContext ApkVerificationResult.Failed(
                reason = "Downloaded file size (${formatBytes(apkFile.length())}) does not match expected release size (${formatBytes(expectedSizeBytes)}).",
                shouldDeleteApk = true
            )
        }

        val actualSha256 = runCatching { sha256File(apkFile) }.getOrElse {
            apkFile.delete()
            return@withContext ApkVerificationResult.Failed(
                reason = "Could not compute SHA-256 checksum for downloaded package.",
                shouldDeleteApk = true
            )
        }

        if (!expectedSha256.isNullOrBlank() && !actualSha256.equals(expectedSha256.trim(), ignoreCase = true)) {
            apkFile.delete()
            return@withContext ApkVerificationResult.Failed(
                reason = "SHA-256 checksum mismatch. The downloaded file may be corrupted or tampered with.",
                shouldDeleteApk = true
            )
        }

        val pm = context.packageManager
        val archiveFlags = PackageManager.GET_SIGNING_CERTIFICATES
        val archiveInfo: PackageInfo? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getPackageArchiveInfo(apkFile.absolutePath, PackageManager.PackageInfoFlags.of(archiveFlags.toLong()))
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageArchiveInfo(apkFile.absolutePath, archiveFlags)
        }

        if (archiveInfo == null) {
            apkFile.delete()
            return@withContext ApkVerificationResult.Failed(
                reason = "The downloaded file is not a valid Android package archive.",
                shouldDeleteApk = true
            )
        }

        if (archiveInfo.packageName != context.packageName) {
            apkFile.delete()
            return@withContext ApkVerificationResult.Failed(
                reason = "Package identity mismatch (${archiveInfo.packageName}). Expected ${context.packageName}.",
                shouldDeleteApk = true
            )
        }

        val installedInfo: PackageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(archiveFlags.toLong()))
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(context.packageName, archiveFlags)
        }

        val archiveVersionCode = archiveInfo.longVersionCode
        val installedVersionCode = installedInfo.longVersionCode
        if (archiveVersionCode < installedVersionCode) {
            apkFile.delete()
            return@withContext ApkVerificationResult.Failed(
                reason = "Update package version ($archiveVersionCode) is older than the installed version ($installedVersionCode).",
                shouldDeleteApk = true
            )
        }

        val archiveSigners = signingDigests(archiveInfo)
        val installedSigners = signingDigests(installedInfo)
        if (archiveSigners.isEmpty() || installedSigners.isEmpty() || archiveSigners != installedSigners) {
            apkFile.delete()
            return@withContext ApkVerificationResult.Failed(
                reason = "Package signature verification failed. The update is not signed with Geekify's release key.",
                shouldDeleteApk = true
            )
        }

        ApkVerificationResult.Verified(
            apkFile = apkFile,
            sha256Hex = actualSha256,
            versionName = archiveInfo.versionName,
            versionCode = archiveVersionCode
        )
    }

    fun canRequestPackageInstalls(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.packageManager.canRequestPackageInstalls()
        } else {
            true
        }
    }

    fun openUnknownSourcesSettings(): Boolean = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val intent = Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${context.packageName}")
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            true
        } else {
            false
        }
    }.getOrDefault(false)

    /**
     * Launches the system package installer for a verified APK file.
     * Records pending install metadata so Geekify can confirm post-update completion on next launch.
     */
    fun launchInstaller(
        apk: File,
        targetVersionName: String,
        targetVersionCode: Int,
        currentVersionName: String
    ): InstallLaunchResult {
        if (!apk.exists() || apk.length() <= 0L) {
            return InstallLaunchResult.Failed("Downloaded update package is no longer available.")
        }

        if (!canRequestPackageInstalls()) {
            openUnknownSourcesSettings()
            return InstallLaunchResult.NeedsUnknownSourcesPermission
        }

        return runCatching {
            recordPendingInstall(
                targetVersionName = targetVersionName,
                targetVersionCode = targetVersionCode,
                fromVersionName = currentVersionName
            )
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.provider",
                apk
            )
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            InstallLaunchResult.LaunchedInstaller
        }.getOrElse { error ->
            InstallLaunchResult.Failed(error.message ?: "Unable to launch Android package installer.")
        }
    }

    /**
     * Backwards-compatible convenience method for launching the installer.
     */
    fun install(apk: File) {
        if (!canRequestPackageInstalls()) {
            openUnknownSourcesSettings()
            return
        }
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.provider",
            apk
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    fun isNetworkAvailable(): Boolean = runCatching {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return true
        val net = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(net) ?: return false
        caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }.getOrDefault(true)

    fun isActiveNetworkMetered(): Boolean = runCatching {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
        cm.isActiveNetworkMetered
    }.getOrDefault(false)

    fun dismissVersion(versionName: String) {
        prefs.edit().putString(KEY_DISMISSED_VERSION, versionName.trim().removePrefix("v")).apply()
    }

    fun clearDismissedVersion() {
        prefs.edit().remove(KEY_DISMISSED_VERSION).apply()
    }

    fun isVersionDismissed(versionName: String): Boolean {
        val dismissed = prefs.getString(KEY_DISMISSED_VERSION, null) ?: return false
        return dismissed.equals(versionName.trim().removePrefix("v"), ignoreCase = true)
    }

    fun recordPendingInstall(
        targetVersionName: String,
        targetVersionCode: Int,
        fromVersionName: String
    ) {
        prefs.edit()
            .putString(KEY_PENDING_INSTALL_VERSION_NAME, targetVersionName.trim().removePrefix("v"))
            .putInt(KEY_PENDING_INSTALL_VERSION_CODE, targetVersionCode)
            .putString(KEY_PENDING_FROM_VERSION_NAME, fromVersionName.trim().removePrefix("v"))
            .putLong(KEY_PENDING_INSTALL_TIMESTAMP, System.currentTimeMillis())
            .apply()
    }

    fun clearPendingInstall() {
        prefs.edit()
            .remove(KEY_PENDING_INSTALL_VERSION_NAME)
            .remove(KEY_PENDING_INSTALL_VERSION_CODE)
            .remove(KEY_PENDING_FROM_VERSION_NAME)
            .remove(KEY_PENDING_INSTALL_TIMESTAMP)
            .apply()
    }

    fun hasPendingInstallFor(versionName: String): Boolean {
        val pending = prefs.getString(KEY_PENDING_INSTALL_VERSION_NAME, null) ?: return false
        return pending.equals(versionName.trim().removePrefix("v"), ignoreCase = true)
    }

    /**
     * Checks if the app was just updated to the version that was previously staged for installation.
     * If matched, clears the pending install record, cleans up staged APKs, and returns [CompletedUpdateInfo].
     */
    fun consumeCompletedUpdateIfMatched(
        installedVersionName: String,
        installedVersionCode: Int
    ): CompletedUpdateInfo? {
        val pendingName = prefs.getString(KEY_PENDING_INSTALL_VERSION_NAME, null) ?: return null
        val pendingCode = prefs.getInt(KEY_PENDING_INSTALL_VERSION_CODE, 0)
        val fromName = prefs.getString(KEY_PENDING_FROM_VERSION_NAME, null)
        val normalizedInstalled = installedVersionName.trim().removePrefix("v")

        val isMatched = normalizedInstalled.equals(pendingName, ignoreCase = true) ||
            (pendingCode > 0 && installedVersionCode >= pendingCode)

        if (!isMatched) return null

        clearPendingInstall()
        cleanupStaleApks(keepVersionName = null)
        return CompletedUpdateInfo(
            previousVersionName = fromName,
            newVersionName = normalizedInstalled,
            newVersionCode = installedVersionCode
        )
    }

    fun cleanupStaleApks(keepVersionName: String? = null) {
        runCatching {
            val dir = updatesDir()
            val keepPrefix = keepVersionName?.let { "Geekify-${sanitizeVersionForFile(it)}" }
            dir.listFiles()?.forEach { file ->
                if (keepPrefix == null || !file.name.startsWith(keepPrefix)) {
                    file.delete()
                }
            }
        }
    }

    fun deleteStagedApkForVersion(versionName: String) {
        runCatching {
            val safe = sanitizeVersionForFile(versionName)
            val dir = updatesDir()
            File(dir, "Geekify-$safe.apk").delete()
            File(dir, "Geekify-$safe.apk.part").delete()
            prefs.edit().remove(KEY_PART_URL).remove(KEY_PART_VERSION).apply()
        }
    }

    private fun updatesDir(): File = File(context.cacheDir, "updates").apply { mkdirs() }

    private fun targetApkFile(versionName: String): File {
        val safe = sanitizeVersionForFile(versionName)
        return File(updatesDir(), "Geekify-$safe.apk")
    }

    private fun fetchSha256Text(url: String): String? = runCatching {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", "Geekify-Android-Updater")
            .build()
        http.newCall(req).execute().use { res ->
            if (!res.isSuccessful) return null
            val text = res.body.string()
            if (text.isBlank()) return null
            extractSha256Hex(text)
        }
    }.getOrNull()

    private fun signingDigests(info: PackageInfo): Set<String> {
        val signingInfo = info.signingInfo ?: return emptySet()
        val signatures = if (signingInfo.hasMultipleSigners()) {
            signingInfo.apkContentsSigners
        } else {
            signingInfo.signingCertificateHistory
        }
        return signatures.orEmpty().map { sig ->
            MessageDigest.getInstance("SHA-256")
                .digest(sig.toByteArray())
                .joinToString("") { "%02x".format(it) }
        }.toSet()
    }
}

/**
 * Pure helper functions for version comparison, markdown changelog categorization,
 * SHA-256 extraction, and human-readable byte/speed formatting.
 * Exposed at package level so JVM unit tests can exercise all edge cases directly.
 */
internal object UpdateMetadataParser {

    private val sha256Regex = Regex("""\b([a-fA-F0-9]{64})\b""")
    private val displayDateFormatter = DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.US)

    fun isNewerVersion(remote: String, current: String): Boolean {
        val r = parseSemanticVersion(remote)
        val c = parseSemanticVersion(current)
        for (i in 0 until maxOf(r.numbers.size, c.numbers.size)) {
            val rv = r.numbers.getOrElse(i) { 0 }
            val cv = c.numbers.getOrElse(i) { 0 }
            if (rv != cv) return rv > cv
        }
        // Stable release is newer than a pre-release of the same numeric version (e.g., 2.5.0 > 2.5.0-beta1)
        if (r.prerelease == null && c.prerelease != null) return true
        if (r.prerelease != null && c.prerelease == null) return false
        return false
    }

    private data class ParsedSemVer(
        val numbers: List<Int>,
        val prerelease: String?
    )

    private fun parseSemanticVersion(version: String): ParsedSemVer {
        val cleaned = version.trim().removePrefix("v").removePrefix("V")
        val numberPart = cleaned.substringBefore('-').substringBefore('+')
        val prerelease = if ('-' in cleaned) cleaned.substringAfter('-').substringBefore('+') else null
        val nums = numberPart.split('.').map { it.toIntOrNull() ?: 0 }
        return ParsedSemVer(nums, prerelease)
    }

    fun extractSha256Hex(text: String): String? {
        return sha256Regex.find(text)?.groupValues?.get(1)?.lowercase(Locale.US)
    }

    fun formatIsoDate(isoString: String?): String? {
        if (isoString.isNullOrBlank()) return null
        return runCatching {
            val instant = Instant.parse(isoString.trim())
            displayDateFormatter.format(instant.atZone(ZoneId.systemDefault()))
        }.getOrElse {
            isoString.take(10).takeIf { it.length == 10 }
        }
    }

    fun categorizeHeading(heading: String): ChangelogCategory {
        val lower = heading.lowercase(Locale.US)
        return when {
            lower.contains("fix") || lower.contains("bug") || lower.contains("stability") ||
                lower.contains("crash") || lower.contains("resolved") -> ChangelogCategory.BUG_FIXES
            lower.contains("improve") || lower.contains("enhance") || lower.contains("perf") ||
                lower.contains("polish") || lower.contains("change") || lower.contains("update") ||
                lower.contains("refin") || lower.contains("optimiz") -> ChangelogCategory.IMPROVEMENTS
            lower.contains("new") || lower.contains("feature") || lower.contains("add") ||
                lower.contains("highlight") || lower.contains("introduc") -> ChangelogCategory.WHATS_NEW
            else -> ChangelogCategory.NOTES
        }
    }

    /**
     * Parses raw GitHub release markdown into structured, categorized [ChangelogSection]s.
     * Filters out noisy commit hashes, full-changelog compare links, and SHA-256 lines.
     */
    fun parseChangelogSections(markdown: String): List<ChangelogSection> {
        val sections = mutableListOf<ChangelogSection>()
        var currentTitle = "What's New"
        var currentCategory = ChangelogCategory.WHATS_NEW
        var currentBullets = mutableListOf<String>()

        fun flush() {
            if (currentBullets.isNotEmpty()) {
                sections += ChangelogSection(
                    category = currentCategory,
                    title = currentTitle,
                    bullets = currentBullets.toList()
                )
                currentBullets = mutableListOf()
            }
        }

        markdown.lines().forEach { rawLine ->
            val line = rawLine.trim()
            when {
                line.isEmpty() || line.startsWith("---") -> Unit
                line.startsWith("http://") || line.startsWith("https://") -> Unit
                line.contains("Full Changelog", ignoreCase = true) && line.contains("compare/") -> Unit
                line.startsWith("SHA-256", ignoreCase = true) || line.startsWith("SHA256", ignoreCase = true) -> Unit
                line.startsWith("#") -> {
                    flush()
                    val headingText = line.trimStart('#').trim().let(::cleanInlineMarkdown)
                    if (headingText.isNotEmpty()) {
                        currentTitle = headingText
                        currentCategory = categorizeHeading(headingText)
                    }
                }
                line.startsWith("- ") || line.startsWith("* ") || line.startsWith("• ") -> {
                    val cleaned = cleanInlineMarkdown(line.drop(2).trim())
                    if (cleaned.isNotEmpty()) {
                        currentBullets += cleaned
                    }
                }
                line.matches(Regex("""^\d+\.\s+.*""")) -> {
                    val cleaned = cleanInlineMarkdown(line.substringAfter('.').trim())
                    if (cleaned.isNotEmpty()) {
                        currentBullets += cleaned
                    }
                }
                else -> {
                    val cleaned = cleanInlineMarkdown(line)
                    if (cleaned.isNotEmpty() && !sha256Regex.matches(cleaned)) {
                        currentBullets += cleaned
                    }
                }
            }
        }
        flush()

        if (sections.isEmpty()) {
            return listOf(
                ChangelogSection(
                    category = ChangelogCategory.WHATS_NEW,
                    title = "What's New",
                    bullets = listOf(
                        "Performance and playback reliability improvements.",
                        "UI polish and stability fixes across Geekify."
                    )
                )
            )
        }
        return sections
    }

    fun cleanInlineMarkdown(text: String): String {
        return text
            .replace(Regex("""\[([^\]]+)]\([^)]+\)"""), "$1")
            .replace("**", "")
            .replace("__", "")
            .replace("`", "")
            .replace(Regex("""\s+by\s+@[a-zA-Z0-9_-]+\s+in\s+https?://\S+"""), "")
            .replace(Regex("""\s*\([0-9a-f]{7,40}\)$"""), "")
            .trim()
    }
}

internal fun parseReleaseTimelineJson(
    releasesArray: JsonArray,
    currentVersion: String
): List<ReleaseChangelogEntry> {
    val entries = mutableListOf<ReleaseChangelogEntry>()
    for (element in releasesArray) {
        val obj = runCatching { element.jsonObject }.getOrNull() ?: continue
        val isDraft = obj["draft"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: false
        val isPrerelease = obj["prerelease"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: false
        if (isDraft || isPrerelease) continue

        val tag = obj["tag_name"]?.jsonPrimitive?.content?.removePrefix("v")?.trim().orEmpty()
        if (tag.isBlank()) continue

        val title = obj["name"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() } ?: "Geekify v$tag"
        val body = obj["body"]?.jsonPrimitive?.content?.trim().orEmpty()
        val publishedAt = obj["published_at"]?.jsonPrimitive?.content
        val sections = UpdateMetadataParser.parseChangelogSections(body)
        val changeCount = sections.sumOf { it.bullets.size }

        entries += ReleaseChangelogEntry(
            versionName = tag,
            versionCode = ReleaseParser.versionCodeFor(tag) ?: 0,
            title = title,
            publishedAt = publishedAt,
            formattedDate = UpdateMetadataParser.formatIsoDate(publishedAt),
            rawNotes = body,
            sections = sections,
            changeCount = changeCount,
            isCurrentTarget = entries.isEmpty() && UpdateMetadataParser.isNewerVersion(tag, currentVersion)
        )
    }
    return entries
}

internal fun parseAvailableUpdateFromJson(
    releaseObj: JsonObject,
    currentVersion: String,
    timeline: List<ReleaseChangelogEntry>,
    sha256Fetcher: (String) -> String?
): AvailableUpdate? {
    val isDraft = releaseObj["draft"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: false
    val isPrerelease = releaseObj["prerelease"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: false
    if (isDraft || isPrerelease) return null

    val tag = releaseObj["tag_name"]?.jsonPrimitive?.content?.removePrefix("v")?.trim() ?: return null
    if (tag.isBlank() || !UpdateMetadataParser.isNewerVersion(tag, currentVersion)) return null

    val assets = releaseObj["assets"]?.jsonArray ?: return null
    val apkAssets = assets.map { it.jsonObject }.filter { asset ->
        val name = asset["name"]?.jsonPrimitive?.content.orEmpty()
        name.endsWith(".apk", ignoreCase = true) && !name.contains("unsigned", ignoreCase = true)
    }
    val apkAsset = apkAssets.firstOrNull { asset ->
        val name = asset["name"]?.jsonPrimitive?.content.orEmpty()
        name.contains("release", ignoreCase = true)
    } ?: apkAssets.firstOrNull() ?: return null

    val apkUrl = apkAsset["browser_download_url"]?.jsonPrimitive?.content
        ?.takeIf { it.startsWith("https://") } ?: return null
    val apkAssetName = apkAsset["name"]?.jsonPrimitive?.content ?: "Geekify-$tag.apk"
    val apkSizeBytes = apkAsset["size"]?.jsonPrimitive?.content?.toLongOrNull()?.takeIf { it > 0L }

    val digestFromGitHubAsset = apkAsset["digest"]?.jsonPrimitive?.content
        ?.removePrefix("sha256:")
        ?.trim()
        ?.takeIf { it.matches(Regex("^[a-fA-F0-9]{64}$")) }

    val sha256AssetUrl = assets.map { it.jsonObject }.firstOrNull { asset ->
        val name = asset["name"]?.jsonPrimitive?.content.orEmpty()
        name.equals("$apkAssetName.sha256", ignoreCase = true) ||
            name.endsWith(".sha256", ignoreCase = true) ||
            name.equals("checksums.txt", ignoreCase = true) ||
            name.equals("SHA256SUMS", ignoreCase = true)
    }?.get("browser_download_url")?.jsonPrimitive?.content

    val bodyText = releaseObj["body"]?.jsonPrimitive?.content?.trim().orEmpty()
    val expectedSha256 = digestFromGitHubAsset
        ?: sha256AssetUrl?.let(sha256Fetcher)
        ?: UpdateMetadataParser.extractSha256Hex(bodyText)

    val publishedAt = releaseObj["published_at"]?.jsonPrimitive?.content
    val formattedDate = UpdateMetadataParser.formatIsoDate(publishedAt)
    val releaseTitle = releaseObj["name"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() } ?: "Geekify v$tag"
    val htmlUrl = releaseObj["html_url"]?.jsonPrimitive?.content?.takeIf { it.startsWith("https://") }
        ?: "https://github.com/Amal-kphilip/Geekify-App/releases/tag/v$tag"

    val sections = UpdateMetadataParser.parseChangelogSections(bodyText)
    val primaryEntry = ReleaseChangelogEntry(
        versionName = tag,
        versionCode = ReleaseParser.versionCodeFor(tag) ?: 0,
        title = releaseTitle,
        publishedAt = publishedAt,
        formattedDate = formattedDate,
        rawNotes = bodyText,
        sections = sections,
        changeCount = sections.sumOf { it.bullets.size },
        isCurrentTarget = true
    )

    val mergedTimeline = buildList {
        add(primaryEntry)
        timeline.forEach { item ->
            if (!item.versionName.equals(tag, ignoreCase = true)) {
                add(item.copy(isCurrentTarget = false))
            }
        }
    }

    return AvailableUpdate(
        versionName = tag,
        versionCode = ReleaseParser.versionCodeFor(tag) ?: 0,
        title = releaseTitle,
        notes = bodyText.ifBlank { "Bug fixes and performance improvements." },
        apkUrl = apkUrl,
        expectedSha256 = expectedSha256,
        apkAssetName = apkAssetName,
        apkSizeBytes = apkSizeBytes,
        publishedAt = publishedAt,
        formattedDate = formattedDate,
        releasePageUrl = htmlUrl,
        changelogTimeline = mergedTimeline,
        totalChangeCount = primaryEntry.changeCount
    )
}

internal fun sanitizeVersionForFile(version: String): String =
    version.trim().removePrefix("v").replace(Regex("[^a-zA-Z0-9._-]"), "_")

internal fun sha256File(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().buffered().use { input ->
        val buf = ByteArray(16 * 1024)
        while (true) {
            val read = input.read(buf)
            if (read <= 0) break
            digest.update(buf, 0, read)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

fun formatBytes(bytes: Long): String {
    val safe = bytes.coerceAtLeast(0L)
    return when {
        safe >= 1024L * 1024L -> String.format(Locale.US, "%.1f MB", safe / (1024.0 * 1024.0))
        safe >= 1024L -> String.format(Locale.US, "%.0f KB", safe / 1024.0)
        else -> "$safe B"
    }
}

fun formatTransferSpeed(bytesPerSecond: Long): String {
    if (bytesPerSecond <= 0L) return ""
    return when {
        bytesPerSecond >= 1024L * 1024L ->
            String.format(Locale.US, "%.1f MB/s", bytesPerSecond / (1024.0 * 1024.0))
        bytesPerSecond >= 1024L ->
            String.format(Locale.US, "%.0f KB/s", bytesPerSecond / 1024.0)
        else -> "$bytesPerSecond B/s"
    }
}

fun formatEtaSeconds(etaSeconds: Long?): String {
    val sec = etaSeconds ?: return ""
    if (sec <= 0L) return "Almost done"
    return when {
        sec >= 3600L -> "${sec / 3600L}h ${(sec % 3600L) / 60L}m left"
        sec >= 60L -> "${sec / 60L}m ${sec % 60L}s left"
        else -> "${sec}s left"
    }
}
