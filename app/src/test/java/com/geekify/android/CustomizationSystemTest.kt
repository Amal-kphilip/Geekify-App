package com.geekify.android

import androidx.compose.ui.text.font.FontFamily
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import com.geekify.android.customization.*
import com.geekify.android.data.local.LyricsDao
import com.geekify.android.data.local.LyricsEntity
import com.geekify.android.lyrics.*
import com.geekify.android.ui.theme.ActiveTypographyState
import com.geekify.android.ui.theme.AppFont
import com.geekify.android.ui.theme.SohneBreitFontFamily
import com.geekify.android.ui.theme.buildGeekifyTypography
import com.geekify.android.ui.theme.resolveFontFamily
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test

class CustomizationSystemTest {

    // ========================================================================================
    // 1. CUSTOMIZATION PERSISTENCE, RANGES & RESET TESTS
    // ========================================================================================

    @Test
    fun `default CustomizationState initializes with expected Geekify defaults`() {
        val prefs = mutablePreferencesOf()
        val state = CustomizationPersistence.fromPreferences(prefs)

        assertEquals(DarkThemeVariant.STANDARD_DARK, state.theme.themeVariant)
        assertEquals(AccentPalettePreset.ELECTRIC_LIME, state.theme.palettePreset)
        assertEquals(GlassPreset.BALANCED, state.glassPreset)
        assertEquals(AsciiPerformanceMode.AUTO, state.asciiPerformanceMode)
        assertEquals(HomeSectionId.DEFAULT_ORDER, state.home.sectionOrder)
        assertTrue(state.home.hiddenSections.isEmpty())
        assertEquals(PlayerLayoutStyle.VINYL_TONEARM, state.player.layoutStyle)
        assertEquals(MiniPlayerStyle.FLOATING_PILL, state.miniPlayer.style)
        assertEquals(AppFontChoice.SOHNE_BREIT, state.typography.fontChoice)
        assertEquals(LyricsTextAlignment.CENTER, state.lyrics.alignment)
        assertEquals(WidgetBackgroundStyle.DARK_GLASS, state.widgets.backgroundStyle)
    }

    @Test
    fun `fromPreferences clamps out-of-range numeric customization values safely`() {
        val prefs = mutablePreferencesOf(
            CustomizationPersistence.KEY_HOME_GRID_COLUMNS to 99,
            CustomizationPersistence.KEY_HOME_ARTWORK_SIZE_DP to 10,
            CustomizationPersistence.KEY_HOME_CORNER_RADIUS_DP to 200,
            CustomizationPersistence.KEY_PLAYER_ARTWORK_SCALE to 5.0f,
            CustomizationPersistence.KEY_MINI_CORNER_RADIUS_DP to 2,
            CustomizationPersistence.KEY_MINI_HEIGHT_DP to 500,
            CustomizationPersistence.KEY_GESTURE_SENSITIVITY to 0.1f,
            CustomizationPersistence.KEY_TYPO_FONT_SCALE to 3.0f,
            CustomizationPersistence.KEY_LYRICS_FONT_SIZE_SP to 90f
        )

        val state = CustomizationPersistence.fromPreferences(prefs)

        assertEquals(3, state.home.gridColumns)
        assertEquals(52, state.home.artworkSizeDp)
        assertEquals(36, state.home.cardCornerRadiusDp)
        assertEquals(1.20f, state.player.artworkScale, 0.001f)
        assertEquals(12, state.miniPlayer.cornerRadiusDp)
        assertEquals(76, state.miniPlayer.heightDp)
        assertEquals(0.6f, state.gestures.gestureSensitivity, 0.001f)
        assertEquals(1.25f, state.typography.fontScale, 0.001f)
        assertEquals(34f, state.lyrics.fontSizeSp, 0.001f)
    }

    @Test
    fun `NavigationCustomizationConfig preserves order and handles all enabled destination combinations`() {
        // 1. All destinations enabled
        val allConfig = NavigationCustomizationConfig(visibleTabs = NavTabId.DEFAULT_TABS)
        assertEquals(listOf(NavTabId.HOME, NavTabId.LIBRARY, NavTabId.CREATE, NavTabId.SEARCH), allConfig.safeVisibleTabs)

        // 2. Only one destination enabled (HOME)
        val oneHome = NavigationCustomizationConfig(visibleTabs = listOf(NavTabId.HOME))
        assertEquals(listOf(NavTabId.HOME), oneHome.safeVisibleTabs)

        // 3. Only one primary destination enabled (LIBRARY)
        val oneLib = NavigationCustomizationConfig(visibleTabs = listOf(NavTabId.LIBRARY))
        assertEquals(listOf(NavTabId.LIBRARY), oneLib.safeVisibleTabs)

        // 4. Two destinations enabled (HOME + LIBRARY, Search hidden)
        val noSearch = NavigationCustomizationConfig(visibleTabs = listOf(NavTabId.HOME, NavTabId.LIBRARY))
        assertEquals(listOf(NavTabId.HOME, NavTabId.LIBRARY), noSearch.safeVisibleTabs)
        assertFalse(NavTabId.SEARCH in noSearch.safeVisibleTabs)

        // 5. Non-adjacent destinations disabled (HOME + SEARCH)
        val nonAdjacent = NavigationCustomizationConfig(visibleTabs = listOf(NavTabId.HOME, NavTabId.SEARCH))
        assertEquals(listOf(NavTabId.HOME, NavTabId.SEARCH), nonAdjacent.safeVisibleTabs)

        // 6. Reordered destinations while some are disabled (SEARCH -> LIBRARY)
        val reordered = NavigationCustomizationConfig(visibleTabs = listOf(NavTabId.SEARCH, NavTabId.LIBRARY))
        assertEquals(listOf(NavTabId.SEARCH, NavTabId.LIBRARY), reordered.safeVisibleTabs)

        // 7. Empty or non-primary only (CREATE + SEARCH) guarantees HOME is restored
        val emptyConfig = NavigationCustomizationConfig(visibleTabs = emptyList())
        assertTrue(emptyConfig.safeVisibleTabs.contains(NavTabId.HOME))

        val onlyCreateAndSearch = NavigationCustomizationConfig(
            visibleTabs = listOf(NavTabId.CREATE, NavTabId.SEARCH)
        )
        assertEquals(listOf(NavTabId.HOME, NavTabId.CREATE, NavTabId.SEARCH), onlyCreateAndSearch.safeVisibleTabs)
    }

    @Test
    fun `all 6 PlayerLayoutStyle options persist and restore accurately`() {
        for (layout in PlayerLayoutStyle.entries) {
            val prefs = mutablePreferencesOf(
                CustomizationPersistence.KEY_PLAYER_LAYOUT_STYLE to layout.name
            )
            val restored = CustomizationPersistence.fromPreferences(prefs)
            assertEquals(layout, restored.player.layoutStyle)
        }
    }

    @Test
    fun `resetCategory resets only target category while preserving other categories`() {
        val prefs = mutablePreferencesOf(
            CustomizationPersistence.KEY_THEME_VARIANT to DarkThemeVariant.AMOLED_BLACK.name,
            CustomizationPersistence.KEY_PALETTE_PRESET to AccentPalettePreset.CYBER_VIOLET.name,
            CustomizationPersistence.KEY_PLAYER_LAYOUT_STYLE to PlayerLayoutStyle.FULLSCREEN_ARTWORK.name,
            CustomizationPersistence.KEY_LYRICS_FONT_SIZE_SP to 28f
        )

        CustomizationPersistence.resetCategory(prefs, CustomizationCategory.THEME_COLORS)
        val afterThemeReset = CustomizationPersistence.fromPreferences(prefs)

        assertEquals(DarkThemeVariant.STANDARD_DARK, afterThemeReset.theme.themeVariant)
        assertEquals(AccentPalettePreset.ELECTRIC_LIME, afterThemeReset.theme.palettePreset)
        assertEquals(PlayerLayoutStyle.FULLSCREEN_ARTWORK, afterThemeReset.player.layoutStyle)
        assertEquals(28f, afterThemeReset.lyrics.fontSizeSp, 0.001f)
    }

    @Test
    fun `resetAll clears all customization keys while preserving unrelated preferences`() {
        val unrelatedKey = stringPreferencesKey("content_country")
        val prefs = mutablePreferencesOf(
            unrelatedKey to "IN",
            CustomizationPersistence.KEY_THEME_VARIANT to DarkThemeVariant.GRAPHITE.name,
            CustomizationPersistence.KEY_PLAYER_LAYOUT_STYLE to PlayerLayoutStyle.EXPRESSIVE.name,
            CustomizationPersistence.KEY_MINI_STYLE to MiniPlayerStyle.COMPACT_SLIM.name,
            CustomizationPersistence.KEY_TYPO_FONT_CHOICE to "LEGACY_MONOSPACE_TECH"
        )

        CustomizationPersistence.resetAll(prefs)
        val restored = CustomizationPersistence.fromPreferences(prefs)

        assertEquals("IN", prefs[unrelatedKey])
        assertEquals(DarkThemeVariant.STANDARD_DARK, restored.theme.themeVariant)
        assertEquals(PlayerLayoutStyle.VINYL_TONEARM, restored.player.layoutStyle)
        assertEquals(MiniPlayerStyle.FLOATING_PILL, restored.miniPlayer.style)
        assertEquals(AppFontChoice.SOHNE_BREIT, restored.typography.fontChoice)
    }

    // ========================================================================================
    // 2. GLOBAL TYPOGRAPHY PROPAGATION TESTS
    // ========================================================================================

    @Test
    fun `Sohne Breit is the default global font family and font choices propagate to MaterialTypography`() {
        assertEquals(
            listOf(
                AppFontChoice.SOHNE_BREIT,
                AppFontChoice.SYSTEM_DEFAULT,
                AppFontChoice.DOWNLOADED,
                AppFontChoice.IMPORTED
            ),
            AppFontChoice.entries
        )
        assertEquals(SohneBreitFontFamily, AppFontChoice.SOHNE_BREIT.resolveFontFamily())

        // Legacy saved font choices migrate cleanly to SOHNE_BREIT
        val legacyRestored = CustomizationPersistence.fromPreferences(
            mutablePreferencesOf(CustomizationPersistence.KEY_TYPO_FONT_CHOICE to "MONTSERRAT")
        )
        assertEquals(AppFontChoice.SOHNE_BREIT, legacyRestored.typography.fontChoice)

        for (choice in AppFontChoice.entries) {
            val config = TypographyDensityConfig(
                fontChoice = choice,
                fontScale = 1.15f,
                titleScale = 1.10f,
                subtitleScale = 0.95f
            )
            ActiveTypographyState.apply(config)
            val expectedFamily = com.geekify.android.ui.theme.GeekifyFontManager.resolveFontFamily(config)
            assertEquals(expectedFamily, AppFont)
            assertEquals(1.15f, ActiveTypographyState.fontScale, 0.001f)

            val typo = buildGeekifyTypography(
                fontFamily = expectedFamily,
                titleScale = 1.10f,
                subtitleScale = 0.95f
            )
            assertEquals(expectedFamily, typo.displayLarge.fontFamily)
            assertEquals(expectedFamily, typo.displayMedium.fontFamily)
            assertEquals(expectedFamily, typo.displaySmall.fontFamily)
            assertEquals(expectedFamily, typo.headlineLarge.fontFamily)
            assertEquals(expectedFamily, typo.headlineMedium.fontFamily)
            assertEquals(expectedFamily, typo.headlineSmall.fontFamily)
            assertEquals(expectedFamily, typo.titleLarge.fontFamily)
            assertEquals(expectedFamily, typo.titleMedium.fontFamily)
            assertEquals(expectedFamily, typo.titleSmall.fontFamily)
            assertEquals(expectedFamily, typo.bodyLarge.fontFamily)
            assertEquals(expectedFamily, typo.bodyMedium.fontFamily)
            assertEquals(expectedFamily, typo.bodySmall.fontFamily)
            assertEquals(expectedFamily, typo.labelLarge.fontFamily)
            assertEquals(expectedFamily, typo.labelMedium.fontFamily)
            assertEquals(expectedFamily, typo.labelSmall.fontFamily)
        }
    }

    // ========================================================================================
    // 3. SYNCHRONIZED LYRICS NORMALIZATION, PARSING, FALLBACK & CACHING TESTS
    // ========================================================================================

    @Test
    fun `LyricsMetadataNormalizer converts duration units safely without mixing ms and seconds`() {
        // Already in seconds
        assertEquals(215, LyricsMetadataNormalizer.resolveDurationSeconds(durationSeconds = 215, durationMs = null))
        // Accidentally passed milliseconds in durationSeconds (> 18000) -> converted to seconds
        assertEquals(215, LyricsMetadataNormalizer.resolveDurationSeconds(durationSeconds = 215_000, durationMs = null))
        // Missing durationSeconds falls back to durationMs / 1000
        assertEquals(198, LyricsMetadataNormalizer.resolveDurationSeconds(durationSeconds = null, durationMs = 198_450L))
        assertEquals(198, LyricsMetadataNormalizer.resolveDurationSeconds(durationSeconds = 0, durationMs = 198_000L))
    }

    @Test
    fun `LyricsMetadataNormalizer cleans video labels and feat decorators while preserving song titles`() {
        assertEquals(
            "Blinding Lights",
            LyricsMetadataNormalizer.cleanSearchTitle("Blinding Lights (Official Music Video)")
        )
        assertEquals(
            "Starboy",
            LyricsMetadataNormalizer.cleanCoreTitle("Starboy (feat. Daft Punk) [Official Video]")
        )
        assertEquals(
            "Leave With Me",
            LyricsMetadataNormalizer.cleanCoreTitle("Leave With Me (Featured in Always Lalisa) (Official Music Video)")
        )
        assertEquals(
            "Unakke Nizhalaanen",
            LyricsMetadataNormalizer.cleanCoreTitle("Unakke Nizhalaanen - Lyric Video | Scene | Suriya | Nazriya| Naslen | Sushin Shyam | Jithu Madhavan")
        )
        assertEquals(
            "Bohemian Rhapsody",
            LyricsMetadataNormalizer.cleanCoreTitle("Bohemian Rhapsody - Remastered 2011")
        )
        assertEquals(
            "The Weeknd",
            LyricsMetadataNormalizer.primaryArtist("The Weeknd, Daft Punk - Topic")
        )
        assertEquals(
            listOf("The Weeknd", "Daft Punk"),
            LyricsMetadataNormalizer.extractArtistTokens("The Weeknd feat. Daft Punk")
        )
    }

    @Test
    fun `LyricsMetadataNormalizer rejects mismatched song candidates`() {
        // Matching title, artist, and duration within tolerance -> true
        assertTrue(
            LyricsMetadataNormalizer.matchesCandidate(
                targetTitle = "Starboy (Official Video)",
                targetArtist = "The Weeknd, Daft Punk",
                targetDurationSec = 230,
                candidateTitle = "Starboy",
                candidateArtist = "The Weeknd",
                candidateDurationSec = 230.5
            )
        )

        // Wrong artist -> false
        assertFalse(
            LyricsMetadataNormalizer.matchesCandidate(
                targetTitle = "Starboy",
                targetArtist = "The Weeknd",
                targetDurationSec = 230,
                candidateTitle = "Starboy",
                candidateArtist = "Completely Different Artist",
                candidateDurationSec = 230.0
            )
        )

        // Duration off by 45 seconds -> false
        assertFalse(
            LyricsMetadataNormalizer.matchesCandidate(
                targetTitle = "Starboy",
                targetArtist = "The Weeknd",
                targetDurationSec = 230,
                candidateTitle = "Starboy",
                candidateArtist = "The Weeknd",
                candidateDurationSec = 285.0
            )
        )

        // Unrequested karaoke/instrumental candidate -> false
        assertFalse(
            LyricsMetadataNormalizer.matchesCandidate(
                targetTitle = "Starboy",
                targetArtist = "The Weeknd",
                targetDurationSec = 230,
                candidateTitle = "Starboy (Karaoke Version)",
                candidateArtist = "The Weeknd",
                candidateDurationSec = 230.0
            )
        )
    }

    @Test
    fun `LrcParser handles multiple timestamps per line, word-level sync, offsets and plain fallback`() {
        val multiLrc = """
            [ti:Test Song]
            [offset:500]
            [00:10.00][00:30.00]Repeated chorus line
            [00:20.50]Verse line in between
        """.trimIndent()

        val parsed = LrcParser.parse(multiLrc, provider = "LrcLib", offsetMs = 250L)
        assertTrue(parsed.isSynced)
        assertEquals(LyricsSyncType.LINE_SYNCED, parsed.syncType)
        assertEquals(3, parsed.lines.size)
        // [offset:500] subtracts 500ms from raw timestamps; sorted chronologically: 9500ms, 20000ms, 29500ms
        assertEquals(9_500L, parsed.lines[0].timeMs)
        assertEquals("Repeated chorus line", parsed.lines[0].text)
        assertEquals(20_000L, parsed.lines[1].timeMs)
        assertEquals("Verse line in between", parsed.lines[1].text)
        assertEquals(29_500L, parsed.lines[2].timeMs)

        // Active line selection with offsetMs = +250L
        assertEquals(0, parsed.activeLineIndex(9_300L)) // 9300 + 250 = 9550 >= 9500
        assertEquals(1, parsed.activeLineIndex(19_800L)) // 19800 + 250 = 20050 >= 20000

        // Word-level LRC
        val wordLrc = """
            [00:05.00]<00:05.00>Hello <00:05.50>neon <00:06.20>world
            [00:08.00]<00:08.00>Second <00:08.60>line
        """.trimIndent()
        val wordParsed = LrcParser.parse(wordLrc, provider = "SimpMusic")
        assertTrue(wordParsed.isSynced)
        assertTrue(wordParsed.hasWordSync)
        assertEquals(LyricsSyncType.WORD_SYNCED, wordParsed.syncType)
        assertEquals(3, wordParsed.lines[0].words.size)
        assertEquals(1, wordParsed.activeWordIndex(0, 5_600L))

        // Plain lyrics are never marked as synchronized
        val plain = LrcParser.parse("First plain line\nSecond plain line", provider = "LrcLib")
        assertFalse(plain.isSynced)
        assertEquals(LyricsSyncType.UNSYNCED, plain.syncType)
        assertEquals(-1, plain.activeLineIndex(10_000L))
    }

    @Test
    fun `TtmlParser parses word-synchronized and line-synchronized TTML XML accurately`() {
        val ttml = """
            <tt xmlns="http://www.w3.org/ns/ttml">
              <body>
                <div>
                  <p begin="00:12.000" end="00:15.500">
                    <span begin="00:12.000" end="00:12.800">Electric</span>
                    <span begin="00:12.800" end="00:14.000">dreams</span>
                    <span begin="00:14.000" end="00:15.500">tonight</span>
                  </p>
                  <p begin="00:16.000" end="00:19.000">
                    <span begin="00:16.000" end="00:17.200">Shining</span>
                    <span begin="00:17.200" end="00:19.000">bright</span>
                  </p>
                </div>
              </body>
            </tt>
        """.trimIndent()

        val parsed = TtmlParser.parse(ttml, provider = "BetterLyrics")
        assertNotNull(parsed)
        assertTrue(parsed!!.isSynced)
        assertTrue(parsed.hasWordSync)
        assertEquals(LyricsSyncType.WORD_SYNCED, parsed.syncType)
        assertEquals(2, parsed.lines.size)
        assertEquals(12_000L, parsed.lines[0].timeMs)
        assertEquals("Electric dreams tonight", parsed.lines[0].text)
        assertEquals(3, parsed.lines[0].words.size)
        assertEquals(1, parsed.activeWordIndex(0, 13_000L))
    }

    @Test
    fun `LyricsClient falls back across providers after HTTP error or plain lyrics to find synced lyrics`() = runBlocking {
        val mockHttp = OkHttpClient.Builder()
            .addInterceptor(Interceptor { chain ->
                val url = chain.request().url.toString()
                when {
                    // 1. SimpMusic fails with HTTP 503
                    url.contains("simpmusic.org") -> Response.Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(503)
                        .message("Service Unavailable")
                        .body("".toResponseBody("application/json".toMediaType()))
                        .build()

                    // 2. BetterLyrics returns 404 empty
                    url.contains("boidu.dev") -> Response.Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(404)
                        .message("Not Found")
                        .body("".toResponseBody("application/json".toMediaType()))
                        .build()

                    // 3. LrcLib returns only unsynchronized plain lyrics
                    url.contains("lrclib.net") -> {
                        val json = """
                            [
                              {
                                "id": 101,
                                "trackName": "Neon Horizon",
                                "artistName": "Geekify Artist",
                                "duration": 200.0,
                                "instrumental": false,
                                "plainLyrics": "Plain line one\nPlain line two",
                                "syncedLyrics": null
                              }
                            ]
                        """.trimIndent()
                        Response.Builder()
                            .request(chain.request())
                            .protocol(Protocol.HTTP_1_1)
                            .code(200)
                            .message("OK")
                            .body(json.toResponseBody("application/json".toMediaType()))
                            .build()
                    }

                    // 4. KuGou returns synchronized LRC!
                    url.contains("lyrics.kugou.com/search") -> {
                        val json = """
                            {
                              "candidates": [
                                {
                                  "id": 999,
                                  "accesskey": "abc123key",
                                  "duration": 200000,
                                  "song": "Neon Horizon",
                                  "singer": "Geekify Artist"
                                }
                              ]
                            }
                        """.trimIndent()
                        Response.Builder()
                            .request(chain.request())
                            .protocol(Protocol.HTTP_1_1)
                            .code(200)
                            .message("OK")
                            .body(json.toResponseBody("application/json".toMediaType()))
                            .build()
                    }

                    url.contains("lyrics.kugou.com/download") -> {
                        val rawLrc = "[00:04.00]Synced line one\n[00:08.50]Synced line two"
                        val b64 = java.util.Base64.getEncoder().encodeToString(rawLrc.toByteArray())
                        val json = """{"content":"$b64"}"""
                        Response.Builder()
                            .request(chain.request())
                            .protocol(Protocol.HTTP_1_1)
                            .code(200)
                            .message("OK")
                            .body(json.toResponseBody("application/json".toMediaType()))
                            .build()
                    }

                    else -> throw IOException("Unexpected URL: $url")
                }
            })
            .build()

        val client = LyricsClient(mockHttp)
        val outcome = client.fetchOutcome(
            title = "Neon Horizon (Official Video)",
            artist = "Geekify Artist - Topic",
            durationSeconds = 200,
            videoId = "dQw4w9WgXcQ"
        )

        assertTrue(outcome is LyricsLookupOutcome.Success)
        val success = outcome as LyricsLookupOutcome.Success
        // Must not stop at LrcLib's plain lyrics; must pick KuGou's synchronized LRC!
        assertTrue(success.lyrics.isSynced)
        assertEquals("KuGou", success.lyrics.provider)
        assertEquals(2, success.lyrics.lines.size)
        assertEquals(4_000L, success.lyrics.lines[0].timeMs)
    }

    @Test
    fun `LyricsRepository negative cache expires or bypasses on forceRefresh retry`() = runBlocking {
        var shouldSucceed = false
        val mockHttp = OkHttpClient.Builder()
            .addInterceptor(Interceptor { chain ->
                val url = chain.request().url.toString()
                if (shouldSucceed && url.contains("lrclib.net/api/search")) {
                    val json = """
                        [
                          {
                            "id": 55,
                            "trackName": "Retry Song",
                            "artistName": "Retry Artist",
                            "duration": 180.0,
                            "instrumental": false,
                            "syncedLyrics": "[00:02.00]Line A\n[00:06.00]Line B"
                          }
                        ]
                    """.trimIndent()
                    Response.Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body(json.toResponseBody("application/json".toMediaType()))
                        .build()
                } else {
                    Response.Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(404)
                        .message("Not Found")
                        .body("".toResponseBody("application/json".toMediaType()))
                        .build()
                }
            })
            .build()

        val fakeDao = object : LyricsDao {
            private val map = mutableMapOf<String, LyricsEntity>()
            override suspend fun find(videoId: String): LyricsEntity? = map[videoId]
            override fun observe(videoId: String): Flow<LyricsEntity?> = MutableStateFlow(map[videoId])
            override suspend fun upsert(entity: LyricsEntity) { map[entity.videoId] = entity }
            override suspend fun updateOffset(videoId: String, offsetMs: Long, now: Long) {
                map[videoId]?.let { map[videoId] = it.copy(offsetMs = offsetMs, updatedAt = now) }
            }
            override suspend fun delete(videoId: String) { map.remove(videoId) }
        }

        val repo = LyricsRepository(fakeDao, LyricsClient(mockHttp))

        // First call returns NoMatch
        val first = repo.getOrFetchOutcome("local_track_1", "Retry Song", "Retry Artist", 180)
        assertTrue(first is LyricsLookupOutcome.NoMatch)

        // Enable provider response and retry with forceRefresh = true -> bypasses negative cache
        shouldSucceed = true
        val retried = repo.getOrFetchOutcome("local_track_1", "Retry Song", "Retry Artist", 180, forceRefresh = true)
        assertTrue(retried is LyricsLookupOutcome.Success)
        assertTrue((retried as LyricsLookupOutcome.Success).lyrics.isSynced)
    }

    // ========================================================================================
    // 4. METROLIST-INSPIRED PLAYER, LYRICS, MINI-PLAYER & APPEARANCE PERSISTENCE TESTS
    // ========================================================================================

    @Test
    fun `all PlayerBackgroundStyle, PlayerProgressStyle, MiniPlayerStyle and LyricsAnimationStyle persist cleanly`() {
        for (bg in PlayerBackgroundStyle.entries) {
            val restored = CustomizationPersistence.fromPreferences(
                mutablePreferencesOf(CustomizationPersistence.KEY_PLAYER_BG_STYLE to bg.name)
            )
            assertEquals(bg, restored.player.backgroundStyle)
        }
        for (ps in PlayerProgressStyle.entries) {
            val restored = CustomizationPersistence.fromPreferences(
                mutablePreferencesOf(
                    CustomizationPersistence.KEY_PLAYER_PROGRESS_STYLE to ps.name,
                    CustomizationPersistence.KEY_PLAYER_PROGRESS_HEIGHT to 8f
                )
            )
            assertEquals(ps, restored.player.progressStyle)
            assertEquals(8f, restored.player.progressHeightDp, 0.001f)
        }
        for (ms in MiniPlayerStyle.entries) {
            val restored = CustomizationPersistence.fromPreferences(
                mutablePreferencesOf(CustomizationPersistence.KEY_MINI_STYLE to ms.name)
            )
            assertEquals(ms, restored.miniPlayer.style)
        }
        for (anim in LyricsAnimationStyle.entries) {
            val restored = CustomizationPersistence.fromPreferences(
                mutablePreferencesOf(CustomizationPersistence.KEY_LYRICS_ANIMATION_STYLE to anim.name)
            )
            assertEquals(anim, restored.lyrics.animationStyle)
        }
    }

    @Test
    fun `legacy preferences migrate cleanly to ThemeMode, DisplayDensityScale and activeAlignment`() {
        val legacyPrefs = mutablePreferencesOf(
            CustomizationPersistence.KEY_THEME_VARIANT to DarkThemeVariant.AMOLED_BLACK.name,
            CustomizationPersistence.KEY_TYPO_DENSITY_MODE to UiDensityMode.SPACIOUS.name,
            CustomizationPersistence.KEY_LYRICS_ALIGNMENT to LyricsTextAlignment.START.name
        )
        val migrated = CustomizationPersistence.fromPreferences(legacyPrefs)
        assertEquals(ThemeMode.PURE_BLACK, migrated.theme.themeMode)
        assertEquals(DisplayDensityScale.LARGE, migrated.typography.displayDensity)
        assertEquals(LyricsTextAlignment.START, migrated.lyrics.alignment)
        assertEquals(LyricsTextAlignment.START, migrated.lyrics.activeAlignment)
    }

    @Test
    fun `new numeric settings clamp safely and resetCategory restores Metrolist-inspired defaults`() {
        val prefs = mutablePreferencesOf(
            CustomizationPersistence.KEY_THEME_MODE to ThemeMode.LIGHT.name,
            CustomizationPersistence.KEY_PALETTE_STYLE to ThemePaletteStyle.EXPRESSIVE.name,
            CustomizationPersistence.KEY_PLAYER_PROGRESS_HEIGHT to 42f,
            CustomizationPersistence.KEY_MINI_ARTWORK_SIZE_DP to 99,
            CustomizationPersistence.KEY_MINI_SURFACE_ALPHA to 0.05f,
            CustomizationPersistence.KEY_LYRICS_GLOBAL_OFFSET_MS to 99_000L,
            CustomizationPersistence.KEY_LYRICS_SUBTITLE_FONT_SIZE_SP to 60f,
            CustomizationPersistence.KEY_TYPO_DISPLAY_DENSITY to DisplayDensityScale.ULTRA_COMPACT.name
        )
        val clamped = CustomizationPersistence.fromPreferences(prefs)
        assertEquals(ThemeMode.LIGHT, clamped.theme.themeMode)
        assertEquals(ThemePaletteStyle.EXPRESSIVE, clamped.theme.paletteStyle)
        assertEquals(12f, clamped.player.progressHeightDp, 0.001f)
        assertEquals(56, clamped.miniPlayer.artworkSizeDp)
        assertEquals(0.30f, clamped.miniPlayer.surfaceAlpha, 0.001f)
        assertEquals(5_000L, clamped.lyrics.globalOffsetMs)
        assertEquals(28f, clamped.lyrics.subtitleFontSizeSp, 0.001f)
        assertEquals(DisplayDensityScale.ULTRA_COMPACT, clamped.typography.displayDensity)

        CustomizationPersistence.resetAll(prefs)
        val afterReset = CustomizationPersistence.fromPreferences(prefs)
        assertEquals(ThemeMode.DARK, afterReset.theme.themeMode)
        assertEquals(ThemePaletteStyle.TONAL_SPOT, afterReset.theme.paletteStyle)
        assertEquals(4f, afterReset.player.progressHeightDp, 0.001f)
        assertEquals(0L, afterReset.lyrics.globalOffsetMs)
        assertEquals(DisplayDensityScale.NATIVE, afterReset.typography.displayDensity)
    }

    @Test
    fun `seekbar showRemainingTime and player showRemainingTime persist independently`() {
        val prefs = mutablePreferencesOf(
            CustomizationPersistence.KEY_SEEKBAR_SHOW_REMAINING to false,
            CustomizationPersistence.KEY_PLAYER_SHOW_REMAINING to true
        )
        val stateA = CustomizationPersistence.fromPreferences(prefs)
        assertFalse(stateA.seekbar.showRemainingTime)
        assertTrue(stateA.player.showRemainingTime)

        prefs[CustomizationPersistence.KEY_SEEKBAR_SHOW_REMAINING] = true
        prefs[CustomizationPersistence.KEY_PLAYER_SHOW_REMAINING] = false
        val stateB = CustomizationPersistence.fromPreferences(prefs)
        assertTrue(stateB.seekbar.showRemainingTime)
        assertFalse(stateB.player.showRemainingTime)
    }
}
