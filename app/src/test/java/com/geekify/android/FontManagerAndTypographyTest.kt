package com.geekify.android

import androidx.compose.ui.text.font.FontFamily
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.mutablePreferencesOf
import com.geekify.android.customization.AppFontChoice
import com.geekify.android.customization.CustomizationPersistence
import com.geekify.android.customization.DisplayDensityScale
import com.geekify.android.customization.TypographyDensityConfig
import com.geekify.android.ui.theme.GeekifyFontManager
import com.geekify.android.ui.theme.SohneBreitFontFamily
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FontManagerAndTypographyTest {

    @Test
    fun magicBytesValidation_acceptsValidFontFormatsAndRejectsInvalidFiles() {
        val validTtf = byteArrayOf(0x00, 0x01, 0x00, 0x00, 0x00, 0x10)
        val validOtf = "OTTO\u0000\u000C".toByteArray(Charsets.US_ASCII)
        val validAppleTrue = "true\u0000\u000C".toByteArray(Charsets.US_ASCII)
        val validTyp1 = "typ1\u0000\u000C".toByteArray(Charsets.US_ASCII)
        val validTtc = "ttcf\u0000\u0001".toByteArray(Charsets.US_ASCII)

        assertTrue(GeekifyFontManager.isValidFontMagicBytes(validTtf))
        assertTrue(GeekifyFontManager.isValidFontMagicBytes(validOtf))
        assertTrue(GeekifyFontManager.isValidFontMagicBytes(validAppleTrue))
        assertTrue(GeekifyFontManager.isValidFontMagicBytes(validTyp1))
        assertTrue(GeekifyFontManager.isValidFontMagicBytes(validTtc))

        assertEquals("ttf", GeekifyFontManager.detectExtensionFromMagicBytes(validTtf))
        assertEquals("otf", GeekifyFontManager.detectExtensionFromMagicBytes(validOtf))

        // Invalid headers: PNG, HTML, ZIP, empty, too short
        val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47)
        val html = "<htm".toByteArray(Charsets.US_ASCII)
        val zip = byteArrayOf(0x50, 0x4B, 0x03, 0x04)
        assertFalse(GeekifyFontManager.isValidFontMagicBytes(png))
        assertFalse(GeekifyFontManager.isValidFontMagicBytes(html))
        assertFalse(GeekifyFontManager.isValidFontMagicBytes(zip))
        assertFalse(GeekifyFontManager.isValidFontMagicBytes(ByteArray(0)))
        assertFalse(GeekifyFontManager.isValidFontMagicBytes(byteArrayOf(0x00, 0x01)))
    }

    @Test
    fun sanitization_stripsTraversalAndUnsafeCharacters() {
        assertEquals(
            "Inter Regular",
            GeekifyFontManager.sanitizeFontDisplayName("../../Downloads/Inter_Regular.ttf")
        )
        assertEquals(
            "JetBrains Mono",
            GeekifyFontManager.sanitizeFontDisplayName("C:\\Windows\\Fonts\\JetBrains-Mono.otf")
        )
        assertEquals(
            "Custom Font",
            GeekifyFontManager.sanitizeFontDisplayName("...///....ttf")
        )

        val safeInternal = GeekifyFontManager.sanitizeInternalFileName("../../evil/font name!", "OTF")
        assertFalse(safeInternal.contains(".."))
        assertFalse(safeInternal.contains("/"))
        assertFalse(safeInternal.contains("\\"))
        assertTrue(safeInternal.endsWith(".otf"))

        val fallbackExt = GeekifyFontManager.sanitizeInternalFileName("my_font", "exe")
        assertTrue(fallbackExt.endsWith(".ttf"))
    }

    @Test
    fun pathContainment_blocksTraversalOutsideFontsDir() {
        val tempRoot = java.nio.file.Files.createTempDirectory("geekify_fonts_test_").toFile()
        try {
            val fontsDir = File(tempRoot, "fonts").apply { mkdirs() }
            val insideFile = File(fontsDir, "imported_abc123.ttf")
            val escapedFile = File(fontsDir, "../secret.ttf")

            assertTrue(GeekifyFontManager.isSafeFontFileWithinDir(fontsDir, insideFile))
            assertFalse(GeekifyFontManager.isSafeFontFileWithinDir(fontsDir, escapedFile))
            assertFalse(GeekifyFontManager.isSafeFontFileWithinDir(fontsDir, fontsDir))
        } finally {
            tempRoot.deleteRecursively()
        }
    }

    @Test
    fun quickDownloadCatalog_respectsLicensingAndUsesOfficialHttpsUrls() {
        val catalog = GeekifyFontManager.quickDownloadCatalog
        val googleSans = catalog.first { it.id == "google_sans" }
        assertTrue("Google Sans must be bundled from user-supplied SIL OFL font", googleSans.isBundled)
        assertFalse(googleSans.isDownloadable)
        assertEquals("SIL OFL 1.1", googleSans.licenseLabel)
        assertNull(googleSans.regularUrl)

        val googleSansConfig = TypographyDensityConfig(
            fontChoice = AppFontChoice.DOWNLOADED,
            activeFontId = "google_sans",
            activeFontName = "Google Sans"
        )
        assertEquals(
            com.geekify.android.ui.theme.GoogleSansFontFamily,
            GeekifyFontManager.resolveFontFamily(googleSansConfig)
        )

        val openSourceIds = listOf("lato", "roboto", "comic_neue", "atkinson_hyperlegible_next")
        for (id in openSourceIds) {
            val spec = catalog.first { it.id == id }
            assertTrue("$id must be downloadable", spec.isDownloadable)
            assertTrue(
                "$id must use HTTPS official Google Fonts repo",
                spec.regularUrl?.startsWith("https://raw.githubusercontent.com/google/fonts/") == true
            )
        }
    }

    @Test
    fun persistence_preservesSohneBreitDefaultAndRoundTripsCustomFonts() {
        val defaults = CustomizationPersistence.fromPreferences(emptyPreferences()).typography
        assertEquals(AppFontChoice.SOHNE_BREIT, defaults.fontChoice)
        assertEquals("Sohne Breit", defaults.resolvedDisplayName)
        assertEquals(DisplayDensityScale.NATIVE, defaults.displayDensity)

        val customPrefs = mutablePreferencesOf(
            CustomizationPersistence.KEY_TYPO_FONT_CHOICE to AppFontChoice.IMPORTED.name,
            CustomizationPersistence.KEY_TYPO_FONT_ID to "imported_12ab34",
            CustomizationPersistence.KEY_TYPO_FONT_NAME to "Space Grotesk",
            CustomizationPersistence.KEY_TYPO_FONT_PATH to "/data/user/0/com.geekify.android/files/fonts/imported_12ab34.ttf",
            CustomizationPersistence.KEY_TYPO_FONT_SCALE to 1.12f,
            CustomizationPersistence.KEY_TYPO_TITLE_SCALE to 1.15f,
            CustomizationPersistence.KEY_TYPO_SUBTITLE_SCALE to 0.92f,
            CustomizationPersistence.KEY_TYPO_DISPLAY_DENSITY to DisplayDensityScale.COMPACT.name,
            CustomizationPersistence.KEY_TYPO_LANDSCAPE_SCALING to false
        )
        val loaded = CustomizationPersistence.fromPreferences(customPrefs).typography
        assertEquals(AppFontChoice.IMPORTED, loaded.fontChoice)
        assertEquals("Space Grotesk", loaded.resolvedDisplayName)
        assertEquals(1.12f, loaded.fontScale, 0.001f)
        assertEquals(1.15f, loaded.titleScale, 0.001f)
        assertEquals(0.92f, loaded.subtitleScale, 0.001f)
        assertEquals(DisplayDensityScale.COMPACT, loaded.displayDensity)
        assertFalse(loaded.enableLandscapeScaling)

        // Simulate resetFontToSystem(): only font keys change, density & scales remain intact
        customPrefs[CustomizationPersistence.KEY_TYPO_FONT_CHOICE] = AppFontChoice.SYSTEM_DEFAULT.name
        customPrefs.remove(CustomizationPersistence.KEY_TYPO_FONT_ID)
        customPrefs[CustomizationPersistence.KEY_TYPO_FONT_NAME] = AppFontChoice.SYSTEM_DEFAULT.label
        customPrefs.remove(CustomizationPersistence.KEY_TYPO_FONT_PATH)

        val afterReset = CustomizationPersistence.fromPreferences(customPrefs).typography
        assertEquals(AppFontChoice.SYSTEM_DEFAULT, afterReset.fontChoice)
        assertEquals("System Default", afterReset.resolvedDisplayName)
        assertEquals("", afterReset.activeFontPath)
        assertEquals(1.12f, afterReset.fontScale, 0.001f)
        assertEquals(1.15f, afterReset.titleScale, 0.001f)
        assertEquals(0.92f, afterReset.subtitleScale, 0.001f)
        assertEquals(DisplayDensityScale.COMPACT, afterReset.displayDensity)
        assertFalse(afterReset.enableLandscapeScaling)
    }

    @Test
    fun resolveFontFamily_fallsBackSafelyWhenCustomFontMissingOrCorrupted() {
        val sohneConfig = TypographyDensityConfig(fontChoice = AppFontChoice.SOHNE_BREIT)
        assertEquals(SohneBreitFontFamily, GeekifyFontManager.resolveFontFamily(sohneConfig))

        val systemConfig = TypographyDensityConfig(fontChoice = AppFontChoice.SYSTEM_DEFAULT)
        assertEquals(FontFamily.Default, GeekifyFontManager.resolveFontFamily(systemConfig))

        val missingFileConfig = TypographyDensityConfig(
            fontChoice = AppFontChoice.IMPORTED,
            activeFontId = "imported_missing",
            activeFontName = "Missing Font",
            activeFontPath = "/nonexistent/path/missing.ttf"
        )
        assertEquals(FontFamily.Default, GeekifyFontManager.resolveFontFamily(missingFileConfig))

        val corruptedTemp = File.createTempFile("corrupted_font_", ".ttf")
        try {
            corruptedTemp.writeText("NOT_A_REAL_FONT_FILE_CONTENT_1234567890_PADDING_PADDING_PADDING_PADDING_PADDING_PADDING_PADDING_PADDING_PADDING_PADDING_PADDING_PADDING")
            val corruptedConfig = TypographyDensityConfig(
                fontChoice = AppFontChoice.DOWNLOADED,
                activeFontId = "lato",
                activeFontName = "Lato",
                activeFontPath = corruptedTemp.absolutePath
            )
            assertEquals(FontFamily.Default, GeekifyFontManager.resolveFontFamily(corruptedConfig))
        } finally {
            corruptedTemp.delete()
        }
    }
}
