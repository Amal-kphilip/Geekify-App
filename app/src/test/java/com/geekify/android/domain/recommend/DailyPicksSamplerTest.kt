package com.geekify.android.domain.recommend

import com.geekify.android.data.model.Card
import com.geekify.android.data.model.ShelfCard
import com.geekify.android.data.model.ShelfItem
import com.geekify.android.data.model.ShelfTrack
import com.geekify.android.data.model.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class DailyPicksSamplerTest {

    private fun track(id: String, artist: String = "Artist"): ShelfTrack =
        ShelfTrack(Track(videoId = id, title = "Title $id", artist = artist))

    private fun card(id: String, type: String = "album"): ShelfCard =
        ShelfCard(Card(id = id, title = "Card $id", type = type))

    @Test
    fun `sampling is deterministic with a fixed seed`() {
        val candidates = (1..10).map { track("id_$it") }
        val seed = 424242L

        val result1 = DailyPicksSampler.sample(
            candidates = candidates,
            weights = { 1.0 },
            recentlyShownTopIds = emptySet(),
            random = Random(seed)
        )

        val result2 = DailyPicksSampler.sample(
            candidates = candidates,
            weights = { 1.0 },
            recentlyShownTopIds = emptySet(),
            random = Random(seed)
        )

        assertEquals(result1.map { it.id }, result2.map { it.id })
    }

    @Test
    fun `previously shown top item is disqualified from position 0 on refresh`() {
        val candidates = (1..5).map { track("id_$it") }
        val recentTopIds = setOf("id_1")

        // Heavy weight on id_1 so it would normally win
        val weights = { item: ShelfItem -> if (item.id == "id_1") 100.0 else 1.0 }

        val sampled = DailyPicksSampler.sample(
            candidates = candidates,
            weights = weights,
            recentlyShownTopIds = recentTopIds,
            random = Random(12345L)
        )

        assertNotEquals("id_1", sampled.first().id)
        assertTrue(sampled.any { it.id == "id_1" }) // Still present in the list, just not first
    }

    @Test
    fun `five consecutive refreshes produce five different first items when enough candidates exist`() {
        val candidates = (1..20).map { track("track_$it") }
        val recentTopIds = mutableSetOf<String>()
        val firstItems = mutableListOf<String>()

        var seed = 100L
        repeat(5) {
            val sampled = DailyPicksSampler.sample(
                candidates = candidates,
                weights = { 1.0 },
                recentlyShownTopIds = recentTopIds,
                random = Random(seed++)
            )
            val topId = sampled.first().id
            firstItems.add(topId)
            recentTopIds.add(topId)
        }

        // All 5 first items must be distinct
        assertEquals(5, firstItems.distinct().size)
    }

    @Test
    fun `single candidate returns itself without crash`() {
        val single = listOf(track("single"))
        val sampled = DailyPicksSampler.sample(
            candidates = single,
            weights = { 1.0 },
            recentlyShownTopIds = setOf("single"),
            random = Random(1L)
        )
        assertEquals(1, sampled.size)
        assertEquals("single", sampled.first().id)
    }
}
