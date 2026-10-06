package com.geekify.android.data.source.innertube

import com.geekify.android.data.model.*
import kotlinx.serialization.json.*

/** Kotlin port of backend/app/services/parsers.py. */
object Parsers {
    private val duration = Regex("^(\\d+:)?\\d{1,2}:\\d{2}$")
    fun walk(e: JsonElement): Sequence<JsonObject> = sequence {
        when (e) {
            is JsonObject -> { yield(e); e.values.forEach { yieldAll(walk(it)) } }
            is JsonArray -> e.forEach { yieldAll(walk(it)) }
            else -> Unit
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
        return walk(e).firstNotNullOfOrNull { node ->
            thumbnails(node["thumbnails"] ?: node["musicThumbnailRenderer"] ?: node["croppedSquareThumbnailRenderer"] ?: node["thumbnail"]).takeIf { it.isNotEmpty() }
        }.orEmpty()
    }
    private fun browse(node: JsonElement): Pair<String?, String?> {
        for (o in walk(node)) {
            val endpoint = objectOrNull(o["browseEndpoint"]) ?: continue
            val id = endpoint.str("browseId")
            val page = objectOrNull(objectOrNull(endpoint["browseEndpointContextSupportedConfigs"])?.get("browseEndpointContextMusicConfig"))?.str("pageType")
            if (id != null) return id to page
        }; return null to null
    }
    private fun videoId(node: JsonElement): String? = walk(node).firstNotNullOfOrNull { o ->
        objectOrNull(o["watchEndpoint"])?.str("videoId") ?: objectOrNull(o["watchPlaylistEndpoint"])?.str("videoId") ?: objectOrNull(o["playlistItemData"])?.str("videoId")
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
            val (bid, page) = browse(o); val vid = videoId(o); val playlist = walk(o).firstNotNullOfOrNull { x -> objectOrNull(x["watchEndpoint"])?.str("playlistId") }
            val kind = when { page == "MUSIC_PAGE_TYPE_ARTIST" || bid?.startsWith("UC") == true -> "artist"; page == "MUSIC_PAGE_TYPE_PLAYLIST" || playlist != null -> "playlist"; page == "MUSIC_PAGE_TYPE_ALBUM" || bid?.startsWith("MPRE") == true -> "album"; vid != null -> "song"; else -> "album" }
            val card = Card(bid ?: playlist ?: vid ?: title, title, text(o["subtitle"]).ifBlank { null }, findThumbnails(o), kind, vid, playlist, bid)
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

    fun seconds(value: String?): Int? { val parts = value?.trim()?.split(":") ?: return null; if (parts.size !in 2..3 || parts.any { it.toIntOrNull() == null }) return null; return parts.map(String::toInt).let { if (it.size == 2) it[0] * 60 + it[1] else it[0] * 3600 + it[1] * 60 + it[2] } }
}
