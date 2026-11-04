package com.geekify.android.data.source.innertube

import com.geekify.android.data.model.*
import kotlinx.serialization.json.*

/** Kotlin port of backend/app/services/parsers.py. */
object Parsers {
    private val duration = Regex("^(\\d+:)?\\d{1,2}:\\d{2}$")
    /** Iterative DFS without recursive sequence/coroutine allocation. */
    fun walk(e: JsonElement): Sequence<JsonObject> = sequence {
        val stack = ArrayDeque<JsonElement>()
        stack.add(e)
        while (stack.isNotEmpty()) {
            when (val curr = stack.removeLast()) {
                is JsonObject -> {
                    yield(curr)
                    // ArrayDeque is a stack. Push children in reverse so the resulting DFS
                    // preserves the response's original left-to-right / top-to-bottom order.
                    // Search uses renderer order as YouTube Music's relevance order; reversing
                    // it made the least relevant result appear first in Geekify.
                    for (v in curr.values.toList().asReversed()) {
                        if (v is JsonObject || v is JsonArray) stack.add(v)
                    }
                }
                is JsonArray -> {
                    for (item in curr.asReversed()) {
                        if (item is JsonObject || item is JsonArray) stack.add(item)
                    }
                }
                else -> Unit
            }
        }
    }
    fun text(e: JsonElement?): String = when (e) {
        is JsonPrimitive -> e.contentOrNull.orEmpty()
        is JsonObject -> e["simpleText"]?.jsonPrimitive?.contentOrNull
            ?: e["text"]?.jsonPrimitive?.contentOrNull
            ?: (e["runs"] as? JsonArray)?.joinToString("") { text(it.jsonObject["text"]) }.orEmpty()
        is JsonArray -> e.joinToString("") { text(it) }
        else -> ""
    }
    private fun objectOrNull(e: JsonElement?) = e as? JsonObject
    private fun JsonObject.str(name: String) = this[name]?.jsonPrimitive?.contentOrNull
    fun thumbnails(e: JsonElement?): List<Thumbnail> {
        val values = when (e) {
            is JsonArray -> e
            is JsonObject -> (e["thumbnails"] ?: e["sources"] ?: e["musicThumbnailRenderer"] ?: e["croppedSquareThumbnailRenderer"] ?: e["thumbnail"]) as? JsonArray
                ?: when (val inner = e["musicThumbnailRenderer"] ?: e["croppedSquareThumbnailRenderer"] ?: e["thumbnail"]) {
                    is JsonObject -> (inner["thumbnails"] ?: inner["sources"]) as? JsonArray
                    else -> null
                }
            else -> null
        } ?: return emptyList()
        return values.mapNotNull { objectOrNull(it) }.mapNotNull { t ->
            t.str("url")?.let { Thumbnail(if (it.startsWith("//")) "https:$it" else it, t["width"]?.jsonPrimitive?.intOrNull, t["height"]?.jsonPrimitive?.intOrNull) }
        }
    }
    fun findThumbnails(e: JsonElement): List<Thumbnail> {
        val direct = thumbnails(e)
        if (direct.isNotEmpty()) return direct
        if (e is JsonObject) {
            val fromChild = thumbnails(e["thumbnail"] ?: e["musicThumbnailRenderer"] ?: e["croppedSquareThumbnailRenderer"])
            if (fromChild.isNotEmpty()) return fromChild
        }
        return walk(e).firstNotNullOfOrNull { node ->
            thumbnails(node["thumbnails"] ?: node["musicThumbnailRenderer"] ?: node["croppedSquareThumbnailRenderer"] ?: node["thumbnail"]).takeIf { it.isNotEmpty() }
        }.orEmpty()
    }
    private fun browse(node: JsonElement): Pair<String?, String?> {
        if (node is JsonObject) {
            val endpoint = objectOrNull(node["browseEndpoint"])
                ?: objectOrNull(objectOrNull(node["navigationEndpoint"])?.get("browseEndpoint"))
            if (endpoint != null) {
                val id = endpoint.str("browseId")
                val page = objectOrNull(objectOrNull(endpoint["browseEndpointContextSupportedConfigs"])?.get("browseEndpointContextMusicConfig"))?.str("pageType")
                if (id != null) return id to page
            }
        }
        for (o in walk(node)) {
            val endpoint = objectOrNull(o["browseEndpoint"]) ?: continue
            val id = endpoint.str("browseId")
            val page = objectOrNull(objectOrNull(endpoint["browseEndpointContextSupportedConfigs"])?.get("browseEndpointContextMusicConfig"))?.str("pageType")
            if (id != null) return id to page
        }; return null to null
    }
    private fun videoId(node: JsonElement): String? {
        if (node is JsonObject) {
            objectOrNull(node["playlistItemData"])?.str("videoId")?.let { return it }
            objectOrNull(node["watchEndpoint"])?.str("videoId")?.let { return it }
            objectOrNull(node["watchPlaylistEndpoint"])?.str("videoId")?.let { return it }
            objectOrNull(node["navigationEndpoint"])?.let { nav ->
                objectOrNull(nav["watchEndpoint"])?.str("videoId")?.let { return it }
                objectOrNull(nav["watchPlaylistEndpoint"])?.str("videoId")?.let { return it }
            }
        }
        return walk(node).firstNotNullOfOrNull { o ->
            objectOrNull(o["watchEndpoint"])?.str("videoId") ?: objectOrNull(o["watchPlaylistEndpoint"])?.str("videoId") ?: objectOrNull(o["playlistItemData"])?.str("videoId")
        }
    }
    private fun artistRefs(runs: JsonArray?): List<ArtistRef> {
        val found = runs.orEmpty().mapNotNull { r ->
            val o = objectOrNull(r) ?: return@mapNotNull null; val name = o.str("text").orEmpty()
            val (id, page) = browse(o); if (name.isBlank() || name.trim() in setOf("•", ",", "&") || duration.matches(name.trim())) null
            else if (page == "MUSIC_PAGE_TYPE_ARTIST" || id?.startsWith("UC") == true) ArtistRef(name, id) else null
        }
        return found
    }
    private fun responsive(o: JsonObject): Track? {
        val id = objectOrNull(o["playlistItemData"])?.str("videoId") ?: videoId(o) ?: return null
        val columns = o["flexColumns"] as? JsonArray ?: return null
        val values = columns.map { c -> objectOrNull(c)?.values?.firstOrNull()?.let(::objectOrNull) ?: JsonObject(emptyMap()) }
        val title = text(values.firstOrNull()?.get("text")); if (title.isBlank()) return null
        var album: String? = null; var albumId: String? = null; var length: String? = null; val artists = mutableListOf<ArtistRef>()
        values.drop(1).forEach { column ->
            val t = text(column["text"])
            if (duration.matches(t.trim())) length = t.trim()
            val runs = objectOrNull(column["text"])?.get("runs") as? JsonArray
            runs?.forEach { r ->
                val runObj = objectOrNull(r) ?: return@forEach
                val runText = runObj.str("text")?.trim().orEmpty()
                if (duration.matches(runText)) length = runText
                val (bid, page) = browse(r)
                if (page == "MUSIC_PAGE_TYPE_ALBUM") { album = runText; albumId = bid }
            }
            artists += artistRefs(runs)
        }
        if (artists.isEmpty() && values.size > 1) text(values[1]["text"]).split("•").firstOrNull()?.trim()?.takeIf { it.isNotBlank() && !duration.matches(it) }?.let { artists += ArtistRef(it) }
        return Track(id, title, artists.joinToString(", ") { it.name }.ifBlank { "Unknown" }, artists, album, albumId, findThumbnails(o), length, seconds(length), o["badges"].toString().contains("EXPLICIT"))
    }
    private fun panel(o: JsonObject): Track? {
        val id = o.str("videoId") ?: videoId(o) ?: return null; val title = text(o["title"]); if (title.isBlank()) return null
        val byline = objectOrNull(o["longBylineText"] ?: o["shortBylineText"]); val artists = artistRefs(byline?.get("runs") as? JsonArray).ifEmpty { text(byline).split("•").firstOrNull()?.trim()?.takeIf { it.isNotBlank() }?.let { listOf(ArtistRef(it)) }.orEmpty() }
        val length = text(o["lengthText"]).ifBlank { null }
        return Track(
            videoId = id,
            title = title,
            artist = artists.joinToString(", ") { it.name }.ifBlank { "Unknown" },
            artists = artists,
            thumbnails = thumbnails(o),
            duration = length,
            durationSeconds = seconds(length)
        )
    }
    fun tracks(data: JsonElement, limit: Int = 200): List<Track> = buildList {
        walk(data).forEach { n ->
            val parsed = objectOrNull(n["musicResponsiveListItemRenderer"])?.let(::responsive) ?: objectOrNull(n["playlistPanelVideoRenderer"])?.let(::panel)
            if (parsed != null && none { it.videoId == parsed.videoId } && size < limit) add(parsed)
        }
    }
    fun cards(data: JsonElement, preferred: String? = null, limit: Int = 100): List<Card> = buildList {
        walk(data).forEach { n ->
            val o = objectOrNull(n["musicTwoRowItemRenderer"]) ?: return@forEach
            val title = text(o["title"]); if (title.isBlank()) return@forEach
            val navigation = objectOrNull(o["navigationEndpoint"])
            val directBrowse = objectOrNull(navigation?.get("browseEndpoint"))
            val fallbackBrowse = if (navigation == null) browse(o) else Pair(null, null)
            val bid = directBrowse?.str("browseId") ?: fallbackBrowse.first
            val page = objectOrNull(objectOrNull(directBrowse?.get("browseEndpointContextSupportedConfigs"))?.get("browseEndpointContextMusicConfig"))
                ?.str("pageType") ?: fallbackBrowse.second
            val vid = videoId(o)
            val playlist = walk(o).firstNotNullOfOrNull { x -> objectOrNull(x["watchEndpoint"])?.str("playlistId") }
            val kind = when {
                page == "MUSIC_PAGE_TYPE_ARTIST" || bid?.startsWith("UC") == true -> "artist"
                page == "MUSIC_PAGE_TYPE_ALBUM" || bid?.startsWith("MPRE") == true -> "album"
                page == "MUSIC_PAGE_TYPE_PLAYLIST" -> "playlist"
                vid != null -> "song"
                playlist != null -> "playlist"
                else -> "album"
            }
            val id = when (kind) {
                "song" -> vid ?: bid ?: playlist ?: title
                else -> bid ?: playlist ?: vid ?: title
            }
            val card = Card(id, title, text(o["subtitle"]).ifBlank { null }, findThumbnails(o), kind, vid, playlist, bid)
            if ((preferred == null || preferred == kind) && none { it.id == card.id } && size < limit) add(card)
        }
    }
    /**
     * Album / artist / playlist rows of a *search* result. YouTube Music lists these as
     * `musicResponsiveListItemRenderer` rows (the same renderer songs use), not as the
     * `musicTwoRowItemRenderer` cards that [cards] reads, so the filtered tabs came back empty.
     * The row's own `navigationEndpoint` says what it is; the artist links inside its subtitle are ignored.
     */
    fun searchEntities(data: JsonElement, preferred: String? = null, limit: Int = 100): List<Card> = buildList {
        walk(data).forEach { n ->
            val o = objectOrNull(n["musicResponsiveListItemRenderer"]) ?: return@forEach
            val endpoint = objectOrNull(objectOrNull(o["navigationEndpoint"])?.get("browseEndpoint")) ?: return@forEach
            val bid = endpoint.str("browseId") ?: return@forEach
            val page = objectOrNull(objectOrNull(endpoint["browseEndpointContextSupportedConfigs"])?.get("browseEndpointContextMusicConfig"))?.str("pageType")
            val kind = when {
                page == "MUSIC_PAGE_TYPE_ARTIST" || bid.startsWith("UC") -> "artist"
                page == "MUSIC_PAGE_TYPE_PLAYLIST" || bid.startsWith("VL") -> "playlist"
                page == "MUSIC_PAGE_TYPE_ALBUM" || bid.startsWith("MPRE") -> "album"
                else -> return@forEach
            }
            if (preferred != null && preferred != kind) return@forEach
            val columns = (o["flexColumns"] as? JsonArray).orEmpty().map { c ->
                val col = objectOrNull(c)
                objectOrNull(col?.get("musicResponsiveListItemFlexColumnRenderer")) ?: objectOrNull(col?.values?.firstOrNull())
            }
            val title = text(columns.firstOrNull()?.get("text")).trim(); if (title.isBlank()) return@forEach
            val subtitle = columns.drop(1).joinToString(" • ") { text(it?.get("text")).trim() }.trim().ifBlank { null }
            val card = Card(bid, title, subtitle, findThumbnails(o), kind, null, if (kind == "playlist") bid.removePrefix("VL") else null, bid)
            if (none { it.id == card.id } && size < limit) add(card)
        }
    }
    fun shelves(data: JsonElement): List<Pair<String, JsonObject>> = buildList {
        walk(data).forEach { n -> listOf("musicShelfRenderer", "musicCarouselShelfRenderer", "musicPlaylistShelfRenderer").forEach { key ->
            objectOrNull(n[key])?.let { block ->
                val header = objectOrNull(block["header"]); val title = text(block["title"]).ifBlank { text(objectOrNull(header?.get("musicCarouselShelfBasicHeaderRenderer"))?.get("title")) }.ifBlank { "More" }
                add(title to block)
            }
        } }
    }
    data class CollectionHeader(val title: String?, val creator: String?, val description: String?, val year: String?)

    /** Best-effort read of an album/playlist page header. Never throws; missing parts are null. */
    fun collectionHeader(raw: JsonElement): CollectionHeader = runCatching {
        val h = walk(raw).firstNotNullOfOrNull { n ->
            objectOrNull(n["musicResponsiveHeaderRenderer"]) ?: objectOrNull(n["musicDetailHeaderRenderer"])
        } ?: return@runCatching CollectionHeader(null, null, null, null)
        val subtitleParts = ((objectOrNull(h["subtitle"])?.get("runs")) as? JsonArray).orEmpty()
            .map { text(objectOrNull(it)?.get("text")).trim() }
            .filter { it.isNotBlank() && it != "•" }
        val year = subtitleParts.firstOrNull { it.matches(Regex("^\\d{4}$")) }
        val kindWords = setOf("album", "playlist", "single", "ep", "mix", "podcast")
        val creator = text(h["straplineTextOne"]).trim().ifBlank { null }
            ?: subtitleParts.firstOrNull { it != year && it.lowercase() !in kindWords }
        val description = (objectOrNull(h["description"])?.get("musicDescriptionShelfRenderer")
            ?.let { objectOrNull(it)?.get("description") } ?: h["description"])
            ?.let { text(it).trim() }?.ifBlank { null }
        CollectionHeader(text(h["title"]).trim().ifBlank { null }, creator, description, year)
    }.getOrDefault(CollectionHeader(null, null, null, null))

    fun cleanTopResultArtist(subtitle: String?): String {
        if (subtitle.isNullOrBlank()) return "Unknown"
        val parts = subtitle.split("•", "·").map { it.trim() }.filter { it.isNotBlank() }
        if (parts.isEmpty()) return subtitle.trim()
        val typeLabels = setOf("song", "video", "single", "ep", "track", "album", "artist", "playlist")
        val filtered = parts
            .dropWhile { it.lowercase() in typeLabels }
            .filterNot { part ->
                duration.matches(part) ||
                    Regex("^\\d{4}$").matches(part) ||
                    Regex("(?i)^[\\d.,]+\\s*[kmb]?\\s*(views|plays|listeners|subscribers)$").matches(part)
            }
        return filtered.firstOrNull() ?: parts.first()
    }

    fun parseTopResult(o: JsonObject): SearchTopResult? {
        val renderer = objectOrNull(o["musicCardShelfRenderer"]) ?: return null
        val title = text(renderer["title"]).ifBlank { return null }
        val subtitle = text(renderer["subtitle"]).ifBlank { null }
        // Exclude secondary list items ("contents") and overflow "menu" so we never extract
        // the videoId or browseId of the next/related song inside the card shelf.
        val cardHeader = JsonObject(renderer.filterKeys { it != "contents" && it != "menu" })
        val onTap = objectOrNull(renderer["onTap"])
        val watchEndpoint = objectOrNull(onTap?.get("watchEndpoint"))
        val browseEndpoint = objectOrNull(onTap?.get("browseEndpoint"))
        val fallbackBrowse = if (browseEndpoint == null && watchEndpoint == null) browse(cardHeader) else (null to null)
        val vid = watchEndpoint?.str("videoId") ?: videoId(cardHeader)
        val bid = browseEndpoint?.str("browseId") ?: fallbackBrowse.first
        val page = objectOrNull(objectOrNull(browseEndpoint?.get("browseEndpointContextSupportedConfigs"))?.get("browseEndpointContextMusicConfig"))?.str("pageType")
            ?: fallbackBrowse.second

        val kind = when {
            page == "MUSIC_PAGE_TYPE_ARTIST" || bid?.startsWith("UC") == true -> "artist"
            page == "MUSIC_PAGE_TYPE_ALBUM" || bid?.startsWith("MPRE") == true -> "album"
            page == "MUSIC_PAGE_TYPE_PLAYLIST" || bid?.startsWith("VL") == true -> "playlist"
            vid != null -> {
                if (subtitle?.startsWith("Video", ignoreCase = true) == true) "video" else "song"
            }
            subtitle?.contains("Artist", ignoreCase = true) == true -> "artist"
            subtitle?.contains("Album", ignoreCase = true) == true -> "album"
            subtitle?.contains("Playlist", ignoreCase = true) == true -> "playlist"
            else -> "song"
        }

        val id = when (kind) {
            "artist" -> bid ?: title
            "album" -> bid ?: title
            "playlist" -> bid?.removePrefix("VL") ?: title
            else -> vid ?: bid ?: title
        }

        val explicit = cardHeader["subtitleBadges"]?.toString()?.contains("EXPLICIT") == true ||
            cardHeader["badges"]?.toString()?.contains("EXPLICIT") == true

        return SearchTopResult(
            id = id,
            title = title,
            subtitle = subtitle,
            kind = kind,
            thumbnails = findThumbnails(cardHeader),
            videoId = vid,
            playlistId = if (kind == "playlist") bid?.removePrefix("VL") else null,
            browseId = bid,
            explicit = explicit
        )
    }

    fun parseDidYouMean(o: JsonObject): String? {
        val dym = objectOrNull(o["didYouMeanRenderer"])
            ?: objectOrNull(o["showingResultsForRenderer"])
            ?: return null
        return text(dym["correctedQuery"]).takeIf { it.isNotBlank() }
    }

    fun parseContinuation(o: JsonObject): String? {
        val contItem = objectOrNull(o["continuationItemRenderer"]) ?: return null
        val nextContData = objectOrNull(contItem["nextContinuationData"])
            ?: objectOrNull(contItem["nextRadioContinuationData"])
        return nextContData?.str("continuation")
    }

    /**
     * Single-pass DFS over a YouTube Music search response JSON.
     * Extracts shelves, tracks, cards, top result, did-you-mean, and continuation in one iteration,
     * avoiding repeated full-tree allocations and traversals.
     */
    fun parseSearchResponseSinglePass(data: JsonElement, query: String, type: String?): SearchResponse {
        var topResult: SearchTopResult? = null
        var didYouMean: String? = null
        var continuation: String? = null
        val rawShelves = mutableListOf<Pair<String, JsonObject>>()
        val allTracks = mutableListOf<Track>()
        val seenTrackIds = mutableSetOf<String>()
        val allCards = mutableListOf<Card>()
        val seenCardIds = mutableSetOf<String>()

        val entityFilter = type?.takeIf { it == "album" || it == "artist" || it == "playlist" || it == "video" }

        val stack = ArrayDeque<JsonElement>()
        stack.add(data)
        while (stack.isNotEmpty()) {
            when (val curr = stack.removeLast()) {
                is JsonObject -> {
                    if (topResult == null && curr.containsKey("musicCardShelfRenderer")) {
                        topResult = parseTopResult(curr)
                    }
                    if (didYouMean == null && (curr.containsKey("didYouMeanRenderer") || curr.containsKey("showingResultsForRenderer"))) {
                        didYouMean = parseDidYouMean(curr)
                    }
                    if (continuation == null && curr.containsKey("continuationItemRenderer")) {
                        continuation = parseContinuation(curr)
                    }
                    listOf("musicShelfRenderer", "musicCarouselShelfRenderer", "musicPlaylistShelfRenderer").forEach { key ->
                        objectOrNull(curr[key])?.let { block ->
                            val header = objectOrNull(block["header"])
                            val title = text(block["title"]).ifBlank {
                                text(objectOrNull(header?.get("musicCarouselShelfBasicHeaderRenderer"))?.get("title"))
                            }.ifBlank { "More" }
                            rawShelves.add(title to block)
                        }
                    }
                    objectOrNull(curr["musicResponsiveListItemRenderer"])?.let { resp ->
                        val tr = responsive(resp)
                        if (tr != null && seenTrackIds.add(tr.videoId) && allTracks.size < 300) {
                            allTracks.add(tr)
                        }
                        val endpoint = objectOrNull(objectOrNull(resp["navigationEndpoint"])?.get("browseEndpoint"))
                        val bid = endpoint?.str("browseId")
                        if (bid != null) {
                            val page = objectOrNull(objectOrNull(endpoint["browseEndpointContextSupportedConfigs"])?.get("browseEndpointContextMusicConfig"))?.str("pageType")
                            val kind = when {
                                page == "MUSIC_PAGE_TYPE_ARTIST" || bid.startsWith("UC") -> "artist"
                                page == "MUSIC_PAGE_TYPE_PLAYLIST" || bid.startsWith("VL") -> "playlist"
                                page == "MUSIC_PAGE_TYPE_ALBUM" || bid.startsWith("MPRE") -> "album"
                                else -> null
                            }
                            if (kind != null && (entityFilter == null || entityFilter == kind)) {
                                val columns = (resp["flexColumns"] as? JsonArray).orEmpty().map { c ->
                                    val col = objectOrNull(c)
                                    objectOrNull(col?.get("musicResponsiveListItemFlexColumnRenderer")) ?: objectOrNull(col?.values?.firstOrNull())
                                }
                                val title = text(columns.firstOrNull()?.get("text")).trim()
                                if (title.isNotBlank()) {
                                    val subtitle = columns.drop(1).joinToString(" • ") { text(it?.get("text")).trim() }.trim().ifBlank { null }
                                    val card = Card(bid, title, subtitle, findThumbnails(resp), kind, null, if (kind == "playlist") bid.removePrefix("VL") else null, bid)
                                    if (seenCardIds.add(card.id) && allCards.size < 300) {
                                        allCards.add(card)
                                    }
                                }
                            }
                        }
                    }
                    objectOrNull(curr["musicTwoRowItemRenderer"])?.let { twoRow ->
                        val title = text(twoRow["title"])
                        if (title.isNotBlank()) {
                            val navigation = objectOrNull(twoRow["navigationEndpoint"])
                            val directBrowse = objectOrNull(navigation?.get("browseEndpoint"))
                            val fallbackBrowse = if (navigation == null) browse(twoRow) else Pair(null, null)
                            val bid = directBrowse?.str("browseId") ?: fallbackBrowse.first
                            val page = objectOrNull(objectOrNull(directBrowse?.get("browseEndpointContextSupportedConfigs"))?.get("browseEndpointContextMusicConfig"))
                                ?.str("pageType") ?: fallbackBrowse.second
                            val vid = videoId(twoRow)
                            val playlist = walk(twoRow).firstNotNullOfOrNull { x -> objectOrNull(x["watchEndpoint"])?.str("playlistId") }
                            val kind = when {
                                page == "MUSIC_PAGE_TYPE_ARTIST" || bid?.startsWith("UC") == true -> "artist"
                                page == "MUSIC_PAGE_TYPE_ALBUM" || bid?.startsWith("MPRE") == true -> "album"
                                page == "MUSIC_PAGE_TYPE_PLAYLIST" -> "playlist"
                                vid != null -> "song"
                                playlist != null -> "playlist"
                                else -> "album"
                            }
                            val id = when (kind) {
                                "song" -> vid ?: bid ?: playlist ?: title
                                else -> bid ?: playlist ?: vid ?: title
                            }
                            val card = Card(id, title, text(twoRow["subtitle"]).ifBlank { null }, findThumbnails(twoRow), kind, vid, playlist, bid)
                            if ((entityFilter == null || entityFilter == kind) && seenCardIds.add(card.id) && allCards.size < 300) {
                                allCards.add(card)
                            }
                        }
                    }

                    for (v in curr.values.toList().asReversed()) {
                        if (v is JsonObject || v is JsonArray) stack.add(v)
                    }
                }
                is JsonArray -> {
                    for (item in curr.asReversed()) {
                        if (item is JsonObject || item is JsonArray) stack.add(item)
                    }
                }
                else -> Unit
            }
        }

        // Ensure a playable Top Result (from musicCardShelfRenderer) is always present at the
        // front of songs even when YouTube Music only lists secondary tracks in the shelf rows.
        val top = topResult
        val topVid = top?.videoId
        if (top != null && topVid != null && (top.kind == "song" || top.kind == "video") && (entityFilter == null || entityFilter == top.kind)) {
            val existingIdx = allTracks.indexOfFirst { it.videoId == topVid }
            if (existingIdx > 0) {
                val existing = allTracks.removeAt(existingIdx)
                allTracks.add(0, existing)
            } else if (existingIdx < 0 && seenTrackIds.add(topVid)) {
                val durationStr = top.subtitle
                    ?.split("•", "·")
                    ?.map { it.trim() }
                    ?.firstOrNull { duration.matches(it) }
                allTracks.add(
                    0,
                    Track(
                        videoId = topVid,
                        title = top.title,
                        artist = cleanTopResultArtist(top.subtitle),
                        duration = durationStr,
                        durationSeconds = seconds(durationStr),
                        thumbnails = top.thumbnails,
                        explicit = top.explicit
                    )
                )
            }
        }

        val shelves = rawShelves.mapNotNull { (title, block) ->
            val tracks = tracks(block)
            val cards = cards(block)
            val items = (tracks.map(::ShelfTrack) + cards.map(::ShelfCard)).distinctBy { it.id }
            items.takeIf { it.isNotEmpty() }?.let { Shelf(title, it) }
        }

        val byKind = allCards.groupBy { it.type }

        return SearchResponse(
            query = query,
            songs = allTracks,
            albums = byKind["album"].orEmpty(),
            artists = byKind["artist"].orEmpty(),
            playlists = byKind["playlist"].orEmpty(),
            shelves = shelves,
            topResult = topResult,
            didYouMean = didYouMean,
            continuation = continuation
        )
    }

    fun seconds(value: String?): Int? { val parts = value?.trim()?.split(":") ?: return null; if (parts.size !in 2..3 || parts.any { it.toIntOrNull() == null }) return null; return parts.map(String::toInt).let { if (it.size == 2) it[0] * 60 + it[1] else it[0] * 3600 + it[1] * 60 + it[2] } }
}
