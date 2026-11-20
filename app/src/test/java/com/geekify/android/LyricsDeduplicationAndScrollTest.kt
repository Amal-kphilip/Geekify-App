package com.geekify.android

import androidx.datastore.preferences.core.mutablePreferencesOf
import com.geekify.android.customization.CustomizationPersistence
import com.geekify.android.lyrics.LrcParser
import com.geekify.android.lyrics.LyricsLine
import com.geekify.android.lyrics.LyricsSubtitleAligner
import com.geekify.android.lyrics.TtmlParser
import com.geekify.android.ui.player.LyricsSyncCalculator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LyricsDeduplicationAndScrollTest {

    // ========================================================================================
    // A. TTML PARSING & DUPLICATE LYRICS PREVENTION
    // ========================================================================================

    @Test
    fun `TTML excludes paragraph-level x-translation and x-roman from original lines and attaches them separately`() {
        val ttml = """
            <tt xmlns="http://www.w3.org/ns/ttml" xmlns:ttm="http://www.w3.org/ns/ttml#metadata">
              <body>
                <div>
                  <p begin="00:05.000" end="00:08.000">밤하늘 별빛</p>
                  <p begin="00:05.000" end="00:08.000" ttm:role="x-translation">Starlight in the night sky</p>
                  <p begin="00:05.000" end="00:08.000" ttm:role="x-roman">Bamhaneul byeolbit</p>
                  <p begin="00:10.000" end="00:14.000">
                    <span begin="00:10.000" end="00:11.500">Electric</span>
                    <span begin="00:11.600" end="00:13.800">dreams</span>
                    <span ttm:role="x-translation">Sognando elettrico</span>
                    <span role="x-roman">Elek-trik</span>
                  </p>
                </div>
              </body>
            </tt>
        """.trimIndent()

        val parsed = TtmlParser.parse(ttml, provider = "BetterLyrics")
        assertNotNull(parsed)
        // Strictly 2 original lines — translation and romanization paragraphs MUST NOT become extra lines
        assertEquals(2, parsed!!.lines.size)

        val first = parsed.lines[0]
        assertEquals("밤하늘 별빛", first.text)
        assertEquals(5_000L, first.timeMs)
        assertEquals("Starlight in the night sky", first.embeddedTranslation)
        assertEquals("Bamhaneul byeolbit", first.embeddedRomanization)

        val second = parsed.lines[1]
        assertEquals("Electric dreams", second.text)
        assertEquals(2, second.words.size)
        assertEquals(10_000L, second.words[0].startMs)
        assertEquals("Electric", second.words[0].text)
        assertEquals("dreams", second.words[1].text)
        assertEquals("Sognando elettrico", second.embeddedTranslation)
        assertEquals("Elek-trik", second.embeddedRomanization)
    }

    @Test
    fun `TTML line-synced paragraph without timed spans strips translation and romanization spans cleanly`() {
        val ttml = """
            <tt>
              <body>
                <p begin="00:02.000" end="00:05.000">
                  Original line text
                  <span ttm:role="x-translation">Translated line text</span>
                  <span ttm:role="x-roman">Romanized text</span>
                </p>
                <p begin="00:06.000" end="00:09.000">Second original line</p>
              </body>
            </tt>
        """.trimIndent()

        val parsed = TtmlParser.parse(ttml, provider = "AppleMusic")!!
        assertEquals(2, parsed.lines.size)
        assertEquals("Original line text", parsed.lines[0].text)
        assertEquals("Translated line text", parsed.lines[0].embeddedTranslation)
        assertEquals("Romanized text", parsed.lines[0].embeddedRomanization)
    }

    @Test
    fun `LRC deduplicates identical same-timestamp entries while preserving repeated chorus lines and multi-timestamps`() {
        val lrc = """
            [00:10.00]Verse one
            [00:10.00]Verse   one
            [00:20.00][01:00.00]Repeated chorus
            [00:35.00]Bridge line
            [01:20.00]Repeated chorus
        """.trimIndent()

        val parsed = LrcParser.parse(lrc, provider = "LrcLib")
        assertTrue(parsed.isSynced)
        // 5 valid occurrences: duplicate [00:10.00] removed, but all 3 chorus timestamps kept!
        assertEquals(5, parsed.lines.size)
        assertEquals(listOf(10_000L, 20_000L, 35_000L, 60_000L, 80_000L), parsed.lines.map { it.timeMs })
        // endMs is computed from the sorted list
        assertEquals(20_000L, parsed.lines[0].endMs)
        assertEquals(35_000L, parsed.lines[1].endMs)
        assertEquals(60_000L, parsed.lines[2].endMs)
        assertEquals(80_000L, parsed.lines[3].endMs)
    }

    @Test
    fun `LyricsSubtitleAligner aligns 1-to-1 with blank placeholders and strips redundant subtitles`() {
        val originals = listOf("Hello world", "밤하늘 별빛", "Repeat line")
        val rawProvider = listOf("Hello world", "Starlight in the night sky") // missing 3rd line

        val aligned = LyricsSubtitleAligner.alignTranslations(originals, rawProvider)
        assertEquals(3, aligned.size)
        assertEquals("", aligned[0]) // redundant copy of original cleared
        assertEquals("Starlight in the night sky", aligned[1])
        assertEquals("", aligned[2]) // missing entry preserved as blank placeholder (no shift!)

        assertNull(LyricsSubtitleAligner.sanitizeSubtitle("Same Text", "  same   text "))
        assertEquals("Different", LyricsSubtitleAligner.sanitizeSubtitle("Original", "Different"))
    }

    // ========================================================================================
    // B. TRANSLATION CACHE KEYING & PERSISTENCE
    // ========================================================================================

    @Test
    fun `translation cache key invalidates on track, provider, content, or language change and persists showTranslation`() {
        val base = LyricsSubtitleAligner.buildTranslationKey("vid1", "LrcLib", listOf("Line 1"), "en")
        val diffTrack = LyricsSubtitleAligner.buildTranslationKey("vid2", "LrcLib", listOf("Line 1"), "en")
        val diffProvider = LyricsSubtitleAligner.buildTranslationKey("vid1", "BetterLyrics", listOf("Line 1"), "en")
        val diffContent = LyricsSubtitleAligner.buildTranslationKey("vid1", "LrcLib", listOf("Line 2"), "en")
        val diffLang = LyricsSubtitleAligner.buildTranslationKey("vid1", "LrcLib", listOf("Line 1"), "es")

        assertNotEquals(base, diffTrack)
        assertNotEquals(base, diffProvider)
        assertNotEquals(base, diffContent)
        assertNotEquals(base, diffLang)

        val prefs = mutablePreferencesOf(CustomizationPersistence.KEY_LYRICS_SHOW_TRANSLATION to false)
        assertFalse(CustomizationPersistence.fromPreferences(prefs).lyrics.showTranslation)

        prefs[CustomizationPersistence.KEY_LYRICS_SHOW_TRANSLATION] = true
        assertTrue(CustomizationPersistence.fromPreferences(prefs).lyrics.showTranslation)
    }

    // ========================================================================================
    // C. MID-SONG INITIAL ACTIVE-LINE SYNC
    // ========================================================================================

    @Test
    fun `opening lyrics mid-song immediately resolves exact active line for scroll positioning`() {
        val lines = (0..30).map { i ->
            LyricsLine(timeMs = i * 5_000L, text = "Line $i")
        }
        // Opening lyrics at 1:17 (77,000 ms) -> immediately targets line 15 (75,000 ms)
        val effMidSong = LyricsSyncCalculator.effectiveLyricsTimeMs(77_000L, 0L, 0L)
        assertEquals(15, LyricsSyncCalculator.findActiveLineIndex(lines, effMidSong))

        // Seeking backward to 0:12 -> immediately targets line 2 (10,000 ms)
        val effSeekBack = LyricsSyncCalculator.effectiveLyricsTimeMs(12_000L, 0L, 0L)
        assertEquals(2, LyricsSyncCalculator.findActiveLineIndex(lines, effSeekBack))
    }
}
