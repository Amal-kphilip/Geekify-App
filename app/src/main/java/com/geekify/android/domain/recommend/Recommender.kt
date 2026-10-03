package com.geekify.android.domain.recommend

/**
 * Pure Kotlin port of backend/app/services/recommender.py.
 * No Android imports. Testable with plain JUnit.
 */
object Recommender {
    const val LIKE_WEIGHT = 1.6
    const val LIKE_DECAY = 0.96
    const val RECENT_WEIGHT = 1.0
    const val RECENT_DECAY = 0.90
    const val RANK_DECAY = 0.12
    const val CO_OCCURRENCE_BOOST = 0.35
    const val MAX_SEEDS = 6
    const val MAX_SEEDS_PER_ARTIST = 2
    const val MAX_TRACK_SECONDS = 15 * 60

    data class Signal(val videoId: String, val artist: String = "", val title: String = "")

    data class Candidate(
        val videoId: String,
        val title: String,
        val artist: String,
        val artistDisplay: String,
        val thumbnails: List<Any>,
        val duration: String?,
        val durationSeconds: Int?,
        val explicit: Boolean,
        val type: String,
        var score: Double = 0.0,
        val sources: MutableSet<String> = mutableSetOf()
    )

    data class MixResult(val id: String, val title: String, val subtitle: String, val videoIds: List<String>)

    private fun akey(name: String?) = (name ?: "").trim().lowercase()

    data class WeightResult(
        val weights: Map<String, Double>,
        val meta: Map<String, Signal>,
        val affinity: Map<String, Double>
    )

    fun seedWeights(liked: List<Signal>, recent: List<Signal>): WeightResult {
        val weights = mutableMapOf<String, Double>()
        val meta = mutableMapOf<String, Signal>()
        val affinity = mutableMapOf<String, Double>()
        liked.forEachIndexed { i, s ->
            val w = LIKE_WEIGHT * Math.pow(LIKE_DECAY, i.toDouble())
            weights[s.videoId] = (weights[s.videoId] ?: 0.0) + w
            meta.putIfAbsent(s.videoId, s)
            val ak = akey(s.artist); if (ak.isNotEmpty()) affinity[ak] = (affinity[ak] ?: 0.0) + w
        }
        recent.forEachIndexed { i, s ->
            val w = RECENT_WEIGHT * Math.pow(RECENT_DECAY, i.toDouble())
            weights[s.videoId] = (weights[s.videoId] ?: 0.0) + w
            meta.putIfAbsent(s.videoId, s)
            val ak = akey(s.artist); if (ak.isNotEmpty()) affinity[ak] = (affinity[ak] ?: 0.0) + w
        }
        return WeightResult(weights, meta, affinity)
    }

    fun pickSeeds(weights: Map<String, Double>, meta: Map<String, Signal>, limit: Int = MAX_SEEDS): List<String> {
        val seeds = mutableListOf<String>()
        val perArtist = mutableMapOf<String, Int>()
        for (vid in weights.entries.sortedByDescending { it.value }.map { it.key }) {
            val a = akey(meta[vid]?.artist)
            if (a.isNotEmpty() && (perArtist[a] ?: 0) >= MAX_SEEDS_PER_ARTIST) continue
            seeds.add(vid)
            perArtist[a] = (perArtist[a] ?: 0) + 1
            if (seeds.size >= limit) break
        }
        return seeds
    }

    fun diversify(cands: List<Candidate>, n: Int, maxPerArtist: Int = 3, decay: Double = 0.65): List<Candidate> {
        val pool = cands.toMutableList()
        val out = mutableListOf<Candidate>()
        val count = mutableMapOf<String, Int>()
        while (pool.isNotEmpty() && out.size < n) {
            var best: Candidate? = null
            var bestEff = -1.0
            for (c in pool) {
                val cnt = count[c.artist] ?: 0
                if (c.artist.isNotEmpty() && cnt >= maxPerArtist) continue
                val eff = c.score * Math.pow(decay, cnt.toDouble())
                if (eff > bestEff) { best = c; bestEff = eff }
            }
            best ?: break
            out.add(best); pool.remove(best)
            count[best.artist] = (count[best.artist] ?: 0) + 1
        }
        return out
    }

    /**
     * Build mixes from pre-fetched radio tracks.
     * @param liked up to 40 liked signals (newest first)
     * @param recent up to 30 recent signals (newest first)
     * @param related map of seedVideoId -> list of related Track-like objects (must have videoId, artist, durationSeconds)
     */
    fun <T> buildMixes(
        liked: List<Signal>,
        recent: List<Signal>,
        related: Map<String, List<T>>,
        videoId: (T) -> String,
        artist: (T) -> String,
        artistDisplay: (T) -> String,
        title: (T) -> String,
        thumbs: (T) -> List<Any>,
        duration: (T) -> String?,
        durationSeconds: (T) -> Int?,
        explicit: (T) -> Boolean,
        type: (T) -> String
    ): Pair<List<MixResult>, List<String>> {
        val (weights, meta, affinity) = seedWeights(liked, recent)
        if (weights.isEmpty()) return Pair(emptyList(), emptyList())
        val seeds = pickSeeds(weights, meta)
        val topW = seeds.maxOfOrNull { weights[it] ?: 0.0 } ?: 1.0
        val known = weights.keys.toSet()
        val knownArtists = affinity.keys.toSet()
        val totalAff = affinity.values.sum().let { if (it == 0.0) 1.0 else it }

        val cands = mutableMapOf<String, Candidate>()
        for (seed in seeds) {
            val w = (weights[seed] ?: 0.0) / topW
            val tracks = related[seed] ?: continue
            tracks.take(40).forEachIndexed { rank, t ->
                val vid = videoId(t)
                if (vid.isEmpty() || vid in known) return@forEachIndexed
                val dur = durationSeconds(t)
                if (dur != null && dur > MAX_TRACK_SECONDS) return@forEachIndexed
                val c = cands.getOrPut(vid) {
                    Candidate(vid, title(t), akey(artist(t)), artistDisplay(t), thumbs(t), duration(t), dur, explicit(t), type(t))
                }
                c.score += w / (1 + RANK_DECAY * rank)
                c.sources.add(seed)
            }
        }

        for (c in cands.values) {
            c.score *= 1 + CO_OCCURRENCE_BOOST * (c.sources.size - 1)
            val share = (affinity[c.artist] ?: 0.0) / totalAff
            c.score *= 1 + minOf(0.6, share * 3)
        }

        val everything = cands.values.toList()
        val mixes = mutableListOf<MixResult>()

        val main = diversify(everything, 30)
        if (main.size >= 8) mixes.add(MixResult("for-you", "Made for you", "Built from what you play and save", main.map { it.videoId }))

        val fresh = diversify(everything.filter { it.artist.isNotEmpty() && it.artist !in knownArtists }, 25)
        if (fresh.size >= 8) mixes.add(MixResult("fresh", "Fresh finds", "Artists you haven't played yet", fresh.map { it.videoId }))

        // More like <artist> for top 2 seeding artists
        val seedByArtist = mutableMapOf<String, MutableList<String>>()
        for (s in seeds) { val a = akey(meta[s]?.artist ?: ""); if (a.isNotEmpty()) seedByArtist.getOrPut(a) { mutableListOf() }.add(s) }
        val names = meta.values.filter { it.artist.isNotEmpty() }.associate { akey(it.artist) to it.artist }
        var shown = 0
        for ((artist, _) in affinity.entries.sortedByDescending { it.value }) {
            if (shown >= 2) break
            val aSeeds = seedByArtist[artist]?.toSet() ?: continue
            val pool = everything.filter { it.sources.any { s -> s in aSeeds } && it.artist != artist }
            val picked = diversify(pool, 20)
            if (picked.size >= 8) {
                shown++
                val display = names[artist] ?: artist.replaceFirstChar { it.uppercase() }
                mixes.add(MixResult("artist-$artist", "More like $display", "Similar sound, new tracks", picked.map { it.videoId }))
            }
        }

        val seedTitles = seeds.mapNotNull { meta[it]?.title?.takeIf { t -> t.isNotEmpty() } }
        return Pair(mixes, seedTitles)
    }
}
