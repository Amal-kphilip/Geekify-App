package com.geekify.android.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ReleaseNotesParserTest {

    @Test
    fun parseGeneratedReleaseNotes_correctlyParsesSectionsAndStripsBoilerplate() {
        val markdown = """
            # Geekify v2.8.0 - Release Notes

            **Release Date:** 16 November 2026

            ### What's New
            - Pinned Home header

            ### Improvements
            - Offload simulation to background thread
            - Random un-prefixed commit

            ### Bug Fixes
            - Clear recent searches on logout

            ---

            **Geekify Team**
            **Full Changelog**: https://github.com/Amal-kphilip/Geekify-App/compare/v2.7.7...v2.8.0
        """.trimIndent()

        val sections = UpdateMetadataParser.parseChangelogSections(markdown)

        // 1. Sections are recognized
        assertEquals(3, sections.size)
        assertEquals(ChangelogCategory.WHATS_NEW, sections[0].category)
        assertEquals("What's New", sections[0].title)
        assertEquals(listOf("Pinned Home header"), sections[0].bullets)

        assertEquals(ChangelogCategory.IMPROVEMENTS, sections[1].category)
        assertEquals("Improvements", sections[1].title)
        assertEquals(listOf("Offload simulation to background thread", "Random un-prefixed commit"), sections[1].bullets)

        assertEquals(ChangelogCategory.BUG_FIXES, sections[2].category)
        assertEquals("Bug Fixes", sections[2].title)
        assertEquals(listOf("Clear recent searches on logout"), sections[2].bullets)

        // 2. All bullets are clean (no hashes, no links)
        val allBullets = sections.flatMap { it.bullets }
        for (bullet in allBullets) {
            assertFalse("Bullet contains http link: $bullet", bullet.contains("http://") || bullet.contains("https://"))
            assertFalse("Bullet contains commit hash: $bullet", bullet.contains(Regex("""\b[0-9a-f]{7,40}\b""")))
            assertFalse("Bullet contains PR number: $bullet", bullet.contains(Regex("""#\d+""")))
        }

        // 3. Boilerplate lines (Title, Release Date, footer, divider) are completely stripped
        for (bullet in allBullets) {
            assertFalse("Contains title: $bullet", bullet.contains("Release Notes", ignoreCase = true))
            assertFalse("Contains Release Date: $bullet", bullet.contains("Release Date", ignoreCase = true))
            assertFalse("Contains Geekify Team: $bullet", bullet.contains("Geekify Team", ignoreCase = true))
            assertFalse("Contains Full Changelog: $bullet", bullet.contains("Full Changelog", ignoreCase = true))
            assertFalse("Contains divider: $bullet", bullet.startsWith("---"))
        }
    }
}
