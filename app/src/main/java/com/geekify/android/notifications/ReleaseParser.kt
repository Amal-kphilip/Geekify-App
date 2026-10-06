package com.geekify.android.notifications

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

data class ReleaseInfo(val versionName: String, val versionCode: Int, val pageUrl: String)

/** Parses the GitHub "latest release" JSON. Pure, so it is unit-tested without a network. */
object ReleaseParser {
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Mirrors .github/workflows/release.yml, which builds versionCode from the tag with
     * printf "%d%02d%02d" major minor patch (v1.1.5 -> 10105). Returns null for non-numeric tags.
     */
    fun versionCodeFor(versionName: String): Int? {
        val parts = versionName.trim().removePrefix("v").substringBefore('-').split('.')
        val nums = parts.map { it.toIntOrNull() ?: return null }
        if (nums.isEmpty() || nums.size > 3) return null
        val (major, minor, patch) = Triple(nums[0], nums.getOrElse(1) { 0 }, nums.getOrElse(2) { 0 })
        if (minor !in 0..99 || patch !in 0..99) return null
        return major * 10_000 + minor * 100 + patch
    }

    fun parse(body: String): ReleaseInfo? = runCatching {
        val o = json.parseToJsonElement(body).jsonObject
        if (o["draft"]?.jsonPrimitive?.content == "true" || o["prerelease"]?.jsonPrimitive?.content == "true") return null
        val name = o["tag_name"]?.jsonPrimitive?.content?.removePrefix("v")?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val code = versionCodeFor(name) ?: return null
        val page = o["html_url"]?.jsonPrimitive?.content?.takeIf { it.startsWith("https://") } ?: return null
        ReleaseInfo(name, code, page)
    }.getOrNull()
}
