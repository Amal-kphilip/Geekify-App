package com.geekify.android.update

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import com.geekify.android.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

data class AvailableUpdate(
    val version: String,
    val downloadUrl: String
)

/** Checks GitHub Releases and installs an APK supplied by the project's latest release. */
class AppUpdateManager(private val context: Context) {
    private val client = OkHttpClient.Builder()
        .callTimeout(30, TimeUnit.SECONDS)
        .build()
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun findAvailableUpdate(): AvailableUpdate? = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("https://api.github.com/repos/Amal-kphilip/Geekify-App/releases/latest")
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "Geekify-Android-Updater")
            .build()

        runCatching {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use null
                val release = json.parseToJsonElement(response.body.string()).jsonObject
                if (release["draft"]?.jsonPrimitive?.content == "true" ||
                    release["prerelease"]?.jsonPrimitive?.content == "true"
                ) return@use null

                val version = release["tag_name"]?.jsonPrimitive?.content
                    ?.removePrefix("v")
                    ?.trim()
                    ?: return@use null
                val asset = release["assets"]?.jsonArray
                    ?.firstOrNull { it.jsonObject["name"]?.jsonPrimitive?.content?.endsWith(".apk", true) == true }
                    ?.jsonObject
                    ?: return@use null
                val downloadUrl = asset["browser_download_url"]?.jsonPrimitive?.content ?: return@use null

                if (isNewer(version, BuildConfig.VERSION_NAME)) AvailableUpdate(version, downloadUrl) else null
            }
        }.getOrNull()
    }

    /**
     * [onProgress] is optional and purely informational: it receives 0f..1f as bytes arrive, or is
     * never called when the server does not report a size. It does not affect the download itself.
     */
    suspend fun download(update: AvailableUpdate, onProgress: (Float) -> Unit = {}): File = withContext(Dispatchers.IO) {
        val updatesDir = File(context.cacheDir, "updates").apply { mkdirs() }
        val destination = File(updatesDir, "Geekify-${update.version}.apk")
        val request = Request.Builder().url(update.downloadUrl).build()
        client.newCall(request).execute().use { response ->
            check(response.isSuccessful) { "Download failed (${response.code})" }
            val total = response.body.contentLength()
            response.body.byteStream().use { input ->
                destination.outputStream().use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var copied = 0L
                    var lastPercent = -1
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        copied += read
                        if (total > 0L) {
                            val percent = (copied * 100 / total).toInt()
                            if (percent != lastPercent) {
                                lastPercent = percent
                                onProgress((copied.toFloat() / total).coerceIn(0f, 1f))
                            }
                        }
                    }
                }
            }
        }
        destination
    }

    fun install(apk: File) {
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

    private fun isNewer(candidate: String, installed: String): Boolean {
        fun parts(version: String) = version.substringBefore('-').split('.')
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
