package com.geekify.android.ui.search

import com.geekify.android.data.local.*
import com.geekify.android.data.model.*
import com.geekify.android.data.source.MusicResult
import com.geekify.android.data.source.MusicSource
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SearchViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `rapid query changes cancel earlier debounced requests and only execute latest query`() = runTest(testDispatcher) {
        val fakeSource = ControlledMusicSource()
        val likedDao = FakeLikedDao()
        val vm = createViewModel(fakeSource, likedDao = likedDao)

        vm.onQueryChange("m")
        advanceTimeBy(100)
        vm.onQueryChange("ma")
        advanceTimeBy(100)
        vm.onQueryChange("mal")
        advanceTimeBy(100)
        vm.onQueryChange("malare")

        // Before 300ms debounce expires for "malare", no search call should have been executed
        assertTrue(fakeSource.executedSearches.isEmpty())

        advanceTimeBy(SearchViewModel.SEARCH_DEBOUNCE_MS + 50)
        advanceUntilIdle()

        assertEquals(listOf("malare"), fakeSource.executedSearches.map { it.first })
        assertEquals("malare", vm.uiState.value.query)
        assertNotNull(vm.uiState.value.response)
        assertEquals("malare_1", vm.uiState.value.response?.songs?.firstOrNull()?.videoId)
        // Verify full-table allOnce() was never called during search
        assertEquals(0, likedDao.allOnceCalls)
    }

    @Test
    fun `old query response arriving late never overwrites active query results`() = runTest(testDispatcher) {
        val slowGate = CompletableDeferred<SearchResponse>()
        val fakeSource = ControlledMusicSource(
            customSearchHandler = { query, _ ->
                if (query == "old query") {
                    MusicResult.Success(slowGate.await())
                } else {
                    MusicResult.Success(
                        SearchResponse(
                            query = query,
                            songs = listOf(Track("new_id_1", "New Query Song", "New Artist")),
                            continuation = "token_new"
                        )
                    )
                }
            }
        )
        val vm = createViewModel(fakeSource)

        // Start "old query" and let it pass debounce so the network call is in-flight
        vm.onQueryChange("old query")
        advanceTimeBy(SearchViewModel.SEARCH_DEBOUNCE_MS + 20)
        runCurrent()

        // User immediately changes query to "new query"
        vm.onQueryChange("new query")
        advanceTimeBy(SearchViewModel.SEARCH_DEBOUNCE_MS + 20)
        advanceUntilIdle()

        // Now complete the old query's deferred response late
        slowGate.complete(
            SearchResponse(
                query = "old query",
                songs = listOf(Track("stale_id_1", "Old Query Song", "Old Artist")),
                continuation = "token_old"
            )
        )
        advanceUntilIdle()

        val state = vm.uiState.value
        assertEquals("new query", state.query)
        assertFalse(state.isLoading)
        assertEquals(listOf("new_id_1"), state.response?.songs?.map { it.videoId })
    }

    @Test
    fun `stale pagination response after changing query cannot corrupt new search results`() = runTest(testDispatcher) {
        val paginationGate = CompletableDeferred<SearchResponse>()
        val fakeSource = ControlledMusicSource(
            customSearchHandler = { query, _ ->
                MusicResult.Success(
                    SearchResponse(
                        query = query,
                        songs = listOf(Track("${query}_song_1", "Song for $query", "Artist")),
                        continuation = "cont_$query"
                    )
                )
            },
            customContinuationHandler = { token ->
                if (token == "cont_queryA") {
                    MusicResult.Success(paginationGate.await())
                } else {
                    MusicResult.Success(
                        SearchResponse(
                            query = "queryB",
                            songs = listOf(Track("page2_B", "Song B Page 2", "Artist")),
                            continuation = null
                        )
                    )
                }
            }
        )
        val vm = createViewModel(fakeSource)

        // 1. Submit queryA
        vm.onSubmit("queryA")
        advanceUntilIdle()
        assertEquals(listOf("queryA_song_1"), vm.uiState.value.response?.songs?.map { it.videoId })

        // 2. Trigger pagination for queryA (stays suspended on paginationGate)
        vm.loadMore()
        runCurrent()
        assertTrue(vm.uiState.value.isLoadingMore)

        // 3. Switch to queryB while queryA's pagination is still in flight
        vm.onSubmit("queryB")
        advanceUntilIdle()
        assertFalse(vm.uiState.value.isLoadingMore)
        assertEquals(listOf("queryB_song_1"), vm.uiState.value.response?.songs?.map { it.videoId })

        // 4. Complete queryA's stale pagination response
        paginationGate.complete(
            SearchResponse(
                query = "queryA",
                songs = listOf(Track("stale_page2_A", "Stale Song A Page 2", "Artist")),
                continuation = "cont_queryA_2"
            )
        )
        advanceUntilIdle()

        // Active queryB results must remain completely uncorrupted
        val finalSongs = vm.uiState.value.response?.songs?.map { it.videoId }.orEmpty()
        assertEquals(listOf("queryB_song_1"), finalSongs)
        assertFalse(finalSongs.contains("stale_page2_A"))
    }

    @Test
    fun `duplicate results across initial search and pagination are deduplicated`() = runTest(testDispatcher) {
        val fakeSource = ControlledMusicSource(
            customSearchHandler = { query, _ ->
                MusicResult.Success(
                    SearchResponse(
                        query = query,
                        songs = listOf(
                            Track("dup_1", "Starboy", "The Weeknd", album = "Starboy", durationSeconds = 230),
                            Track("dup_1", "Starboy", "The Weeknd", album = "Starboy", durationSeconds = 230),
                            Track("ver_dup", "Starboy (Official Video)", "The Weeknd", durationSeconds = 230)
                        ),
                        albums = listOf(
                            Card("alb_1", "Starboy", "The Weeknd", type = "album"),
                            Card("alb_1", "Starboy", "The Weeknd", type = "album")
                        ),
                        continuation = "cont_starboy"
                    )
                )
            },
            customContinuationHandler = { _ ->
                MusicResult.Success(
                    SearchResponse(
                        query = "Starboy",
                        songs = listOf(
                            Track("dup_1", "Starboy", "The Weeknd", album = "Starboy", durationSeconds = 230),
                            Track("ver_dup_2", "Starboy (Official Audio)", "The Weeknd", durationSeconds = 230),
                            Track("unique_2", "Party Monster", "The Weeknd", album = "Starboy", durationSeconds = 249)
                        ),
                        albums = listOf(
                            Card("alb_1", "Starboy", "The Weeknd", type = "album"),
                            Card("alb_2", "Starboy (Deluxe)", "The Weeknd", type = "album")
                        ),
                        continuation = null
                    )
                )
            }
        )
        val vm = createViewModel(fakeSource)

        vm.onSubmit("Starboy")
        advanceUntilIdle()

        assertEquals(listOf("dup_1"), vm.uiState.value.response?.songs?.map { it.videoId })
        assertEquals(listOf("alb_1"), vm.uiState.value.response?.albums?.map { it.id })

        vm.loadMore()
        advanceUntilIdle()

        assertEquals(listOf("dup_1", "unique_2"), vm.uiState.value.response?.songs?.map { it.videoId })
        assertEquals(listOf("alb_1", "alb_2"), vm.uiState.value.response?.albums?.map { it.id })
    }

    @Test
    fun `obsolete suggestion requests are cancelled on submit or query change`() = runTest(testDispatcher) {
        val suggestionGate = CompletableDeferred<List<String>>()
        val fakeSource = ControlledMusicSource(
            customSuggestionsHandler = { query ->
                if (query == "star") {
                    suggestionGate.await()
                } else {
                    listOf("$query suggestion")
                }
            }
        )
        val vm = createViewModel(fakeSource)

        // Start typing "star" and advance past suggestion debounce (150ms) so request is in-flight
        vm.onQueryChange("star")
        advanceTimeBy(SearchViewModel.SUGGESTIONS_DEBOUNCE_MS + 20)
        runCurrent()

        // User submits "starboy" before "star" suggestions return
        vm.onSubmit("starboy")
        advanceUntilIdle()
        assertTrue(vm.uiState.value.suggestions.isEmpty())

        // Now complete the obsolete "star" suggestions late
        suggestionGate.complete(listOf("star wars", "starlight"))
        advanceUntilIdle()

        // Suggestions must stay empty after submission
        assertTrue(vm.uiState.value.suggestions.isEmpty())
    }

    @Test
    fun `error state exposes message and retry recovers cleanly`() = runTest(testDispatcher) {
        var shouldFail = true
        val fakeSource = ControlledMusicSource(
            customSearchHandler = { query, _ ->
                if (shouldFail) {
                    MusicResult.Failure("Network timeout")
                } else {
                    MusicResult.Success(
                        SearchResponse(
                            query = query,
                            songs = listOf(Track("rec_1", "Recovered $query", "Artist"))
                        )
                    )
                }
            }
        )
        val vm = createViewModel(fakeSource)

        vm.onSubmit("Believer")
        advanceUntilIdle()

        assertEquals("Network timeout", vm.uiState.value.error)
        assertFalse(vm.uiState.value.isLoading)
        assertNull(vm.uiState.value.response)

        shouldFail = false
        vm.retry()
        advanceUntilIdle()

        assertNull(vm.uiState.value.error)
        assertFalse(vm.uiState.value.isLoading)
        assertEquals(listOf("rec_1"), vm.uiState.value.response?.songs?.map { it.videoId })
    }

    @Test
    fun `tab change during pagination and repeated loadMore calls prevent duplicate or stale page merges`() = runTest(testDispatcher) {
        var continuationCallCount = 0
        val paginationGate = CompletableDeferred<SearchResponse>()
        val fakeSource = ControlledMusicSource(
            customSearchHandler = { query, type ->
                MusicResult.Success(
                    SearchResponse(
                        query = query,
                        songs = listOf(Track("${query}_${type?.name ?: "ALL"}_1", "$query Song", "Artist")),
                        continuation = "cont_${type?.name ?: "ALL"}"
                    )
                )
            },
            customContinuationHandler = { token ->
                continuationCallCount++
                if (token == "cont_ALL") {
                    MusicResult.Success(paginationGate.await())
                } else {
                    MusicResult.Success(
                        SearchResponse(
                            query = "Numb",
                            songs = listOf(Track("numb_songs_page2", "Numb Song Page 2", "Artist")),
                            continuation = null
                        )
                    )
                }
            }
        )
        val vm = createViewModel(fakeSource)

        vm.onSubmit("Numb")
        advanceUntilIdle()
        assertEquals(listOf("Numb_ALL_1"), vm.uiState.value.response?.songs?.map { it.videoId })

        // Trigger loadMore three times rapidly -> only 1 continuation call should be made
        vm.loadMore()
        vm.loadMore()
        vm.loadMore()
        runCurrent()
        assertEquals(1, continuationCallCount)
        assertTrue(vm.uiState.value.isLoadingMore)

        // Switch tab to "Songs" while "All" pagination is still in-flight
        vm.onTabSelected("Songs")
        advanceUntilIdle()
        assertEquals("Songs", vm.uiState.value.selectedTab)
        assertFalse(vm.uiState.value.isLoadingMore)
        assertEquals(listOf("Numb_SONG_1"), vm.uiState.value.response?.songs?.map { it.videoId })

        // Complete stale "All" tab pagination -> must not merge into "Songs" tab results
        paginationGate.complete(
            SearchResponse(
                query = "Numb",
                songs = listOf(Track("stale_all_page2", "Numb Stale Page 2", "Artist")),
                continuation = null
            )
        )
        advanceUntilIdle()
        assertEquals(listOf("Numb_SONG_1"), vm.uiState.value.response?.songs?.map { it.videoId })
    }

    @Test
    fun `search deduplication preserves live remix acoustic instrumental and alternate edits while collapsing duplicate official versions`() {
        val tracks = listOf(
            Track("v_studio", "Starboy", "The Weeknd", album = "Starboy", durationSeconds = 230),
            Track("v_official_vid", "Starboy (Official Video)", "The Weeknd", durationSeconds = 230),
            Track("v_lyric_vid", "Starboy (Lyric Video)", "The Weeknd", durationSeconds = 230),
            Track("v_live", "Starboy (Live at VEVO)", "The Weeknd", durationSeconds = 240),
            Track("v_remix", "Starboy (Kygo Remix)", "The Weeknd", durationSeconds = 245),
            Track("v_acoustic", "Starboy (Acoustic Version)", "The Weeknd", durationSeconds = 215),
            Track("v_instrumental", "Starboy (Instrumental)", "The Weeknd", durationSeconds = 230),
            Track("v_edit", "Starboy (Radio Edit)", "The Weeknd", durationSeconds = 200)
        )

        val ranked = SearchRelevance.rankTracks("Starboy", tracks)
        val ids = ranked.map { it.videoId }.toSet()

        // Studio + Official Video + Lyric Video collapse to the highest-scored studio version
        assertTrue(ids.contains("v_studio"))
        assertFalse(ids.contains("v_official_vid"))
        assertFalse(ids.contains("v_lyric_vid"))

        // Genuinely distinct recording variants are all preserved
        assertTrue(ids.contains("v_live"))
        assertTrue(ids.contains("v_remix"))
        assertTrue(ids.contains("v_acoustic"))
        assertTrue(ids.contains("v_instrumental"))
        assertTrue(ids.contains("v_edit"))
    }

    private fun createViewModel(
        musicSource: MusicSource,
        likedDao: FakeLikedDao = FakeLikedDao()
    ): SearchViewModel = SearchViewModel(
        musicSource = musicSource,
        recentSearchDao = FakeRecentSearchDao(),
        likedDao = likedDao,
        historyDao = FakeHistoryDao(),
        playlistDao = FakePlaylistDao(),
        playStatDao = FakePlayStatDao(),
        defaultDispatcher = testDispatcher,
        ioDispatcher = testDispatcher
    )

    private class ControlledMusicSource(
        private val customSearchHandler: (suspend (String, SearchType?) -> MusicResult<SearchResponse>)? = null,
        private val customContinuationHandler: (suspend (String) -> MusicResult<SearchResponse>)? = null,
        private val customSuggestionsHandler: (suspend (String) -> List<String>)? = null
    ) : MusicSource {
        val executedSearches = mutableListOf<Pair<String, SearchType?>>()

        override suspend fun search(query: String, type: SearchType?): MusicResult<SearchResponse> {
            executedSearches.add(query to type)
            customSearchHandler?.let { return it(query, type) }
            delay(10)
            return MusicResult.Success(
                SearchResponse(
                    query = query,
                    songs = listOf(Track("${query}_1", query, "Artist"))
                )
            )
        }

        override suspend fun searchContinuation(continuation: String): MusicResult<SearchResponse> {
            customContinuationHandler?.let { return it(continuation) }
            return MusicResult.Success(SearchResponse(query = ""))
        }

        override suspend fun searchSuggestions(query: String): List<String> {
            customSuggestionsHandler?.let { return it(query) }
            return listOf("$query 1", "$query 2")
        }

        override suspend fun home(): MusicResult<HomeResponse> = MusicResult.Success(HomeResponse(emptyList()))
        override suspend fun track(videoId: String): MusicResult<Track> = MusicResult.Failure("Not used")
        override suspend fun artist(channelId: String): MusicResult<ArtistPage> = MusicResult.Failure("Not used")
        override suspend fun collection(id: String, kind: CollectionKind): MusicResult<CollectionPage> = MusicResult.Failure("Not used")
        override suspend fun related(videoId: String): MusicResult<List<Track>> = MusicResult.Success(emptyList())
    }

    private class FakeRecentSearchDao : RecentSearchDao {
        private val items = MutableStateFlow<List<RecentSearchEntity>>(emptyList())
        override fun observeRecent(limit: Int): Flow<List<RecentSearchEntity>> = items
        override suspend fun getRecentOnce(limit: Int): List<RecentSearchEntity> = items.value
        override suspend fun insert(entity: RecentSearchEntity) {
            items.value = (listOf(entity) + items.value.filterNot { it.query == entity.query }).take(30)
        }
        override suspend fun delete(query: String) {
            items.value = items.value.filterNot { it.query == query }
        }
        override suspend fun clearAll() {
            items.value = emptyList()
        }
        override suspend fun pruneOld() {}
    }

    private class FakeLikedDao : LikedDao {
        var allOnceCalls = 0
        private val itemsFlow = MutableStateFlow<List<LikedTrackEntity>>(emptyList())
        private val artistsFlow = MutableStateFlow(listOf("Vijay Yesudas", "The Weeknd"))

        override fun all(): Flow<List<LikedTrackEntity>> = itemsFlow
        override suspend fun isLiked(id: String): Boolean = false
        override suspend fun insert(e: LikedTrackEntity) {}
        override suspend fun delete(e: LikedTrackEntity) {}
        override suspend fun clear() {}
        override suspend fun allOnce(): List<LikedTrackEntity> {
            allOnceCalls++
            return emptyList()
        }
        override suspend fun allVideoIdsOnce(): List<String> = emptyList()
        override fun observeVideoIds(): Flow<List<String>> = MutableStateFlow(emptyList())
        override suspend fun artistProjectionsOnce(limit: Int): List<LikedArtistProjection> =
            listOf(
                LikedArtistProjection(
                    artist = "Vijay Yesudas, The Weeknd",
                    artists = listOf(ArtistRef("Vijay Yesudas"), ArtistRef("The Weeknd"))
                )
            )
        override fun observeArtistProjections(limit: Int): Flow<List<LikedArtistProjection>> =
            MutableStateFlow(
                listOf(
                    LikedArtistProjection(
                        artist = "Vijay Yesudas, The Weeknd",
                        artists = listOf(ArtistRef("Vijay Yesudas"), ArtistRef("The Weeknd"))
                    )
                )
            )
        override suspend fun distinctArtistsOnce(limit: Int): List<String> = artistsFlow.value
        override fun observeDistinctArtists(limit: Int): Flow<List<String>> = artistsFlow
        override suspend fun searchLiked(query: String, limit: Int): List<LikedTrackEntity> = emptyList()
        override suspend fun insertAll(list: List<LikedTrackEntity>) {}
    }

    private class FakeHistoryDao : HistoryDao {
        override fun recent(): Flow<List<HistoryTrackEntity>> = MutableStateFlow(emptyList())
        override suspend fun insert(e: HistoryTrackEntity) {}
        override suspend fun delete(id: String) {}
        override suspend fun trim() {}
        override suspend fun clear() {}
        override suspend fun allOnce(): List<HistoryTrackEntity> = emptyList()
        override suspend fun searchHistory(query: String, limit: Int): List<HistoryTrackEntity> = emptyList()
        override suspend fun insertAll(list: List<HistoryTrackEntity>) {}
    }

    private class FakePlaylistDao : PlaylistDao {
        override fun allPlaylists(): Flow<List<PlaylistEntity>> = MutableStateFlow(emptyList())
        override suspend fun insertPlaylist(p: PlaylistEntity) {}
        override suspend fun deletePlaylist(id: String) {}
        override suspend fun clearPlaylists() {}
        override suspend fun rename(id: String, name: String) {}
        override fun tracksFor(id: String): Flow<List<PlaylistTrackEntity>> = MutableStateFlow(emptyList())
        override fun allTracks(): Flow<List<PlaylistTrackEntity>> = MutableStateFlow(emptyList())
        override suspend fun allTracksOnce(): List<PlaylistTrackEntity> = emptyList()
        override suspend fun insertTrack(t: PlaylistTrackEntity) {}
        override suspend fun removeTrack(pid: String, vid: String) {}
        override suspend fun updateTrackPosition(pid: String, vid: String, newPosition: Int) {}
        override suspend fun clearTracks(id: String) {}
        override suspend fun clearAllTracks() {}
        override suspend fun allPlaylistsOnce(): List<PlaylistEntity> = emptyList()
        override suspend fun searchPlaylists(query: String, limit: Int): List<PlaylistEntity> = emptyList()
        override suspend fun tracksOnce(id: String): List<PlaylistTrackEntity> = emptyList()
        override suspend fun insertAll(list: List<PlaylistEntity>) {}
        override suspend fun insertAllTracks(list: List<PlaylistTrackEntity>) {}
    }

    private class FakePlayStatDao : PlayStatDao {
        override suspend fun bump(id: String, title: String, artist: String, now: Long): Int = 1
        override suspend fun insert(e: PlayStatEntity): Long = 1L
        override suspend fun top(limit: Int): List<PlayStatEntity> = emptyList()
        override fun topFlow(limit: Int): Flow<List<PlayStatEntity>> = MutableStateFlow(emptyList())
        override suspend fun topArtistsOnce(limit: Int): List<String> = listOf("Arijit Singh")
        override fun observeTopArtists(limit: Int): Flow<List<String>> = MutableStateFlow(listOf("Arijit Singh"))
        override suspend fun clear() {}
    }
}
