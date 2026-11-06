package com.geekify.android.update

import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppUpdateManagerTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `semantic version comparison handles major minor patch and prerelease tags`() {
        assertTrue(UpdateMetadataParser.isNewerVersion("2.5.2", "2.5.1"))
        assertTrue(UpdateMetadataParser.isNewerVersion("v2.6.0", "2.5.9"))
        assertTrue(UpdateMetadataParser.isNewerVersion("3.0.0", "2.99.99"))
        assertTrue(UpdateMetadataParser.isNewerVersion("2.5.1", "2.5.1-beta1"))

        assertFalse(UpdateMetadataParser.isNewerVersion("2.5.1", "2.5.1"))
        assertFalse(UpdateMetadataParser.isNewerVersion("v2.5.0", "2.5.1"))
        assertFalse(UpdateMetadataParser.isNewerVersion("2.5.1-rc1", "2.5.1"))
    }

    @Test
    fun `changelog parser categorizes sections and strips noisy links and commit hashes`() {
        val rawMarkdown = """
            ## What's New
            - Seamless shared-element transition between Mini Player and Now Playing (569848e)
            - Redesigned Morphe-style update experience by @Amal-kphilip in https://github.com/Amal-kphilip/Geekify-App/pull/42

            ## Performance & Improvements
            * Faster Listening Statistics calculation on large histories
            * Resumable HTTP Range APK downloads

            ## Bug Fixes
            - Fixed collapsed player overlay intercepting touches

            **Full Changelog**: https://github.com/Amal-kphilip/Geekify-App/compare/v2.5.0...v2.5.1
            SHA-256: a3f5c8e90123456789abcdef0123456789abcdef0123456789abcdef01234567
        """.trimIndent()

        val sections = UpdateMetadataParser.parseChangelogSections(rawMarkdown)
        assertEquals(3, sections.size)

        assertEquals(ChangelogCategory.WHATS_NEW, sections[0].category)
        assertEquals(2, sections[0].bullets.size)
        assertEquals(
            "Seamless shared-element transition between Mini Player and Now Playing",
            sections[0].bullets[0]
        )
        assertEquals(
            "Redesigned Morphe-style update experience",
            sections[0].bullets[1]
        )

        assertEquals(ChangelogCategory.IMPROVEMENTS, sections[1].category)
        assertEquals(2, sections[1].bullets.size)

        assertEquals(ChangelogCategory.BUG_FIXES, sections[2].category)
        assertEquals(listOf("Fixed collapsed player overlay intercepting touches"), sections[2].bullets)
    }

    @Test
    fun `changelog parser strips release boilerplate and supports plain-text section headers`() {
        val v251StyleMarkdown = """
            # Geekify v2.5.1 - Release Notes

            **Release Date:** 06 November 2026

            ### What's New
            - **Sub-Pixel Spring Settling**: Refined the Now Playing transition.

            ### Bug Fixes
            - Fixed collapsed hit-test touch interception.

            ---

            **Geekify Team**
            **Full Changelog**: https://github.com/Amal-kphilip/Geekify-App/compare/v2.5.0...v2.5.1
        """.trimIndent()

        val v251Sections = UpdateMetadataParser.parseChangelogSections(v251StyleMarkdown)
        assertEquals(2, v251Sections.size)
        assertEquals(2, v251Sections.sumOf { it.bullets.size })
        assertEquals(ChangelogCategory.WHATS_NEW, v251Sections[0].category)
        assertEquals(ChangelogCategory.BUG_FIXES, v251Sections[1].category)

        val v240PlainHeaderMarkdown = """
            Geekify v2.4.0 - Release Notes
            Release Date: 06 November 2026
            What's New
            Introduced a 6-stage hybrid recommendation engine.
            Improvements
            Upgraded track deduplication.
            Bug Fixes
            Fixed a race condition during Home pull-to-refresh.
        """.trimIndent()

        val v240Sections = UpdateMetadataParser.parseChangelogSections(v240PlainHeaderMarkdown)
        assertEquals(3, v240Sections.size)
        assertEquals(ChangelogCategory.WHATS_NEW, v240Sections[0].category)
        assertEquals(ChangelogCategory.IMPROVEMENTS, v240Sections[1].category)
        assertEquals(ChangelogCategory.BUG_FIXES, v240Sections[2].category)
    }

    @Test
    fun `release timeline parser filters drafts and prereleases and marks latest target`() {
        val payload = """
            [
              {
                "tag_name": "v2.6.0-beta1",
                "name": "Geekify v2.6.0 Beta",
                "draft": false,
                "prerelease": true,
                "body": "- Beta feature"
              },
              {
                "tag_name": "v2.5.2",
                "name": "Geekify v2.5.2",
                "draft": false,
                "prerelease": false,
                "published_at": "2026-11-06T10:00:00Z",
                "body": "## What's New\n- Circular wavy progress indicator\n- Expandable changelog timeline"
              },
              {
                "tag_name": "v2.5.1",
                "name": "Geekify v2.5.1",
                "draft": false,
                "prerelease": false,
                "published_at": "2026-11-05T10:00:00Z",
                "body": "## Bug Fixes\n- Fixed mini-player click handling"
              }
            ]
        """.trimIndent()

        val timeline = parseReleaseTimelineJson(json.parseToJsonElement(payload).jsonArray, "2.5.1")
        assertEquals(2, timeline.size)
        assertEquals("2.5.2", timeline[0].versionName)
        assertTrue(timeline[0].isCurrentTarget)
        assertEquals(2, timeline[0].changeCount)

        assertEquals("2.5.1", timeline[1].versionName)
        assertFalse(timeline[1].isCurrentTarget)
        assertEquals(1, timeline[1].changeCount)
    }

    @Test
    fun `parseAvailableUpdateFromJson selects signed release APK and extracts digest and size`() {
        val expectedDigest = "b4f5c8e90123456789abcdef0123456789abcdef0123456789abcdef01234567"
        val releaseJson = """
            {
              "tag_name": "v2.5.2",
              "name": "Geekify v2.5.2",
              "draft": false,
              "prerelease": false,
              "published_at": "2026-11-06T10:15:00Z",
              "html_url": "https://github.com/Amal-kphilip/Geekify-App/releases/tag/v2.5.2",
              "body": "## What's New\n- Morphe-inspired updater redesign\n## Bug Fixes\n- Improved installer verification",
              "assets": [
                {
                  "name": "app-release-unsigned.apk",
                  "size": 10485760,
                  "browser_download_url": "https://github.com/Amal-kphilip/Geekify-App/releases/download/v2.5.2/app-release-unsigned.apk"
                },
                {
                  "name": "Geekify-v2.5.2-release.apk",
                  "size": 26214400,
                  "digest": "sha256:$expectedDigest",
                  "browser_download_url": "https://github.com/Amal-kphilip/Geekify-App/releases/download/v2.5.2/Geekify-v2.5.2-release.apk"
                }
              ]
            }
        """.trimIndent()

        val parsed = parseAvailableUpdateFromJson(
            releaseObj = json.parseToJsonElement(releaseJson).jsonObject,
            currentVersion = "2.5.1",
            timeline = emptyList(),
            sha256Fetcher = { null }
        )

        assertNotNull(parsed)
        parsed!!
        assertEquals("2.5.2", parsed.versionName)
        assertEquals(20502, parsed.versionCode)
        assertEquals("Geekify-v2.5.2-release.apk", parsed.apkAssetName)
        assertEquals(26214400L, parsed.apkSizeBytes)
        assertEquals(expectedDigest, parsed.expectedSha256)
        assertEquals(2, parsed.totalChangeCount)

        // When currentVersion is already equal or newer, returns null
        val upToDate = parseAvailableUpdateFromJson(
            releaseObj = json.parseToJsonElement(releaseJson).jsonObject,
            currentVersion = "2.5.2",
            timeline = emptyList(),
            sha256Fetcher = { null }
        )
        assertNull(upToDate)
    }

    @Test
    fun `download progress snapshot and human readable formatters compute accurate values`() {
        val snapshot = DownloadProgressSnapshot(
            downloadedBytes = 15L * 1024L * 1024L,
            totalBytes = 30L * 1024L * 1024L,
            bytesPerSecond = 3L * 1024L * 1024L,
            etaSeconds = 5L,
            isResumed = true
        )
        assertEquals(0.5f, snapshot.progressFraction ?: 0f, 0.001f)
        assertEquals("15.0 MB", formatBytes(snapshot.downloadedBytes))
        assertEquals("30.0 MB", formatBytes(snapshot.totalBytes!!))
        assertEquals("3.0 MB/s", formatTransferSpeed(snapshot.bytesPerSecond))
        assertEquals("5s left", formatEtaSeconds(snapshot.etaSeconds))

        val unknownTotal = DownloadProgressSnapshot(downloadedBytes = 512L * 1024L, totalBytes = null)
        assertNull(unknownTotal.progressFraction)
        assertEquals("512 KB", formatBytes(unknownTotal.downloadedBytes))
    }

    @Test
    fun `sha256 file digest matches constant-time checksum verifier`() {
        val tempFile = File.createTempFile("geekify-update-test", ".apk")
        try {
            tempFile.writeText("Geekify signed package payload test bytes")
            val computed = sha256File(tempFile)
            assertEquals(64, computed.length)
            assertTrue(AppUpdateManager.verifyChecksum(computed, "sha256:${computed.uppercase()}"))
            assertFalse(
                AppUpdateManager.verifyChecksum(
                    computed,
                    "0000000000000000000000000000000000000000000000000000000000000000"
                )
            )
        } finally {
            tempFile.delete()
        }
    }
}
