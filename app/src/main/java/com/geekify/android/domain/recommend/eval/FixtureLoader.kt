package com.geekify.android.domain.recommend.eval

import kotlinx.serialization.json.Json

object FixtureLoader {
    private val json = Json { ignoreUnknownKeys = true }

    fun loadSyntheticListeners(): List<EvalListener> {
        val stream = javaClass.classLoader?.getResourceAsStream("synthetic_listeners.json")
            ?: Thread.currentThread().contextClassLoader?.getResourceAsStream("synthetic_listeners.json")
            ?: javaClass.getResourceAsStream("/synthetic_listeners.json")
            ?: error("Could not find synthetic_listeners.json in resources")

        val content = stream.bufferedReader().use { it.readText() }
        return json.decodeFromString<List<EvalListener>>(content)
    }
}
