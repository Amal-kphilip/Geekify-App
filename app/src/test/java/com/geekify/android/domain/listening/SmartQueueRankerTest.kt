package com.geekify.android.domain.listening

import com.geekify.android.data.local.ListeningSessionEntity
import com.geekify.android.data.local.PlayStat
import com.geekify.android.data.model.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SmartQueueRankerTest {
    @Test
    fun `liked candidate gets preference and every item has a reason`() {
        val current = track("current", "Current", "Current Artist")
        val liked = track("liked", "Liked song", "Favorite Artist")
        val familiar = track("familiar", "Familiar song", "Another Artist")
        val skipped = track("skipped", "Often skipped", "Skip Artist")
        val sessions = listOf(
            session("c1", skipped, listened = 15_000L, skipped = true),
            session("c2", skipped, listened = 20_000L, skipped = true),
            session("f1", familiar, listened = 180_000L, completed = true),
            session("f2", familiar, listened = 175_000L, completed = true)
        )

        val ranked = SmartQueueRanker.rank(
            candidates = listOf(familiar, skipped, liked, current),
            current = current,
            sessions = sessions,
            playCounts = listOf(PlayStat("familiar", "Familiar song", "Another Artist", 4, 10L)),
            likedTracks = listOf(liked),
            upcoming = emptyList(),
            now = 100L
        )

        assertEquals("liked", ranked.first().track.videoId)
        assertFalse(ranked.any { it.track.videoId == "current" })
        assertTrue(ranked.all { it.reason.isNotBlank() })
        assertEquals("You liked this track", ranked.first().reason)
    }

    @Test
    fun `high skip rate loses to a candidate without negative listening signals`() {
        val current = track("current", "Current", "Current Artist")
        val skipped = track("skipped", "Frequently skipped", "Skip Artist")
        val neutral = track("neutral", "Neutral", "Neutral Artist")
        val result = SmartQueueRanker.rank(
            candidates = listOf(skipped, neutral),
            current = current,
            sessions = listOf(
                session("skip-1", skipped, listened = 15_000L, skipped = true),
                session("skip-2", skipped, listened = 12_000L, skipped = true)
            ),
            playCounts = emptyList(),
            likedTracks = emptyList(),
            upcoming = emptyList(),
            now = 100L
        )

        assertEquals("neutral", result.first().track.videoId)
    }

    @Test
    fun `related queue excludes tracks already queued and diversifies artists`() {
        val current = track("current", "Current", "Artist A")
        val sameArtist = track("same", "Same artist", "Artist A")
        val newArtist = track("fresh", "Fresh artist", "Artist B")
        val rank = SmartQueueRanker.rank(
            candidates = listOf(sameArtist, newArtist, current),
            current = current,
            sessions = emptyList(),
            playCounts = emptyList(),
            likedTracks = emptyList(),
            upcoming = listOf(track("already", "Already queued", "Artist C")),
            now = 100L
        )

        assertEquals(listOf("fresh", "same"), rank.map { it.track.videoId })
        assertFalse(rank.any { it.track.videoId == "current" })
    }

    @Test
    fun `rankAsync executes on background dispatcher and produces identical ranking to pre-aggregated signals`() = kotlinx.coroutines.runBlocking {
        val current = track("current", "Current", "Artist A")
        val liked = track("liked", "Liked song", "Artist B")
        val familiar = track("familiar", "Familiar song", "Artist C")
        val sessions = listOf(
            session("f1", familiar, listened = 180_000L, completed = true)
        )
        val playCounts = listOf(PlayStat("familiar", "Familiar song", "Artist C", 4, 10L))
        val signals = SmartQueueRanker.buildSignals(
            sessions = sessions,
            playCounts = playCounts,
            likedTracks = listOf(liked),
            now = 100L
        )

        var executedThreadName: String? = null
        val customDispatcher = object : kotlinx.coroutines.CoroutineDispatcher() {
            override fun dispatch(context: kotlin.coroutines.CoroutineContext, block: Runnable) {
                executedThreadName = "CustomQueueRankerWorker"
                block.run()
            }
        }

        val asyncRanked = SmartQueueRanker.rankAsync(
            candidates = listOf(familiar, liked, current),
            current = current,
            signals = signals,
            upcoming = emptyList(),
            dispatcher = customDispatcher
        )
        val syncRanked = SmartQueueRanker.rank(
            candidates = listOf(familiar, liked, current),
            current = current,
            sessions = sessions,
            playCounts = playCounts,
            likedTracks = listOf(liked),
            upcoming = emptyList(),
            now = 100L
        )

        assertEquals("CustomQueueRankerWorker", executedThreadName)
        assertEquals(syncRanked.map { it.track.videoId }, asyncRanked.map { it.track.videoId })
        assertEquals(syncRanked.map { it.reason }, asyncRanked.map { it.reason })
    }

    private fun track(id: String, title: String, artist: String) =
        Track(videoId = id, title = title, artist = artist)

    private fun session(
        id: String,
        track: Track,
        listened: Long,
        completed: Boolean = false,
        skipped: Boolean = false
    ) = ListeningSessionEntity(
        sessionId = id,
        videoId = track.videoId,
        title = track.title,
        artist = track.artist,
        listenedMs = listened,
        durationMs = 200_000L,
        startedAt = 10L,
        updatedAt = 10L + listened,
        completed = completed,
        skipped = skipped
    )
}
