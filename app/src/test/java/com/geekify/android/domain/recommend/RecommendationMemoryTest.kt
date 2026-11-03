package com.geekify.android.domain.recommend

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecommendationMemoryTest {
    @Test fun `impressions are isolated by account`() {
        val memory = RecommendationMemory()
        memory.remember("account-a", listOf("track-a"))
        memory.remember("account-b", listOf("track-b"))

        assertEquals(setOf("track-a"), memory.snapshot("account-a"))
        assertEquals(setOf("track-b"), memory.snapshot("account-b"))
    }

    @Test fun `guest impressions do not cross into a signed-in account`() {
        val memory = RecommendationMemory()
        memory.remember(null, listOf("guest-track"))

        assertTrue(memory.snapshot("account-a").isEmpty())
        assertTrue(memory.snapshot(null).contains("guest-track"))
    }

    @Test fun `account memory can be cleared independently`() {
        val memory = RecommendationMemory()
        memory.remember("account-a", listOf("track-a"))
        memory.remember("account-b", listOf("track-b"))
        memory.clear("account-a")

        assertTrue(memory.snapshot("account-a").isEmpty())
        assertFalse(memory.snapshot("account-b").isEmpty())
    }
}
