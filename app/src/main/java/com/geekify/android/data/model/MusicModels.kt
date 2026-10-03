package com.geekify.android.data.model

import kotlinx.serialization.Serializable

@Serializable data class Thumbnail(val url: String, val width: Int? = null, val height: Int? = null)
@Serializable data class ArtistRef(val name: String, val id: String? = null)
@Serializable data class Track(
    val videoId: String,
    val title: String,
    val artist: String,
    val artists: List<ArtistRef> = emptyList(),
    val album: String? = null,
    val albumId: String? = null,
    val thumbnails: List<Thumbnail> = emptyList(),
    val duration: String? = null,
    val durationSeconds: Int? = null,
    val explicit: Boolean = false,
    val type: String = "song"
)
@Serializable data class Card(
    val id: String,
    val title: String,
    val subtitle: String? = null,
    val thumbnails: List<Thumbnail> = emptyList(),
    val type: String = "album",
    val videoId: String? = null,
    val playlistId: String? = null,
    val browseId: String? = null
)
@Serializable data class Shelf(val title: String, val items: List<ShelfItem> = emptyList())
@Serializable sealed interface ShelfItem { val id: String; val title: String }
@Serializable data class ShelfTrack(val value: Track) : ShelfItem { override val id get() = value.videoId; override val title get() = value.title }
@Serializable data class ShelfCard(val value: Card) : ShelfItem { override val id get() = value.id; override val title get() = value.title }
@Serializable data class SearchResponse(val query: String, val songs: List<Track> = emptyList(), val albums: List<Card> = emptyList(), val artists: List<Card> = emptyList(), val playlists: List<Card> = emptyList(), val shelves: List<Shelf> = emptyList())
@Serializable data class HomeResponse(val shelves: List<Shelf>)
@Serializable data class ArtistPage(val id: String, val name: String, val description: String? = null, val thumbnails: List<Thumbnail> = emptyList(), val songs: List<Track> = emptyList(), val albums: List<Card> = emptyList(), val singles: List<Card> = emptyList(), val related: List<Card> = emptyList())
@Serializable data class CollectionPage(val id: String, val title: String, val subtitle: String? = null, val description: String? = null, val type: String = "playlist", val year: String? = null, val thumbnails: List<Thumbnail> = emptyList(), val tracks: List<Track> = emptyList(), val artist: String? = null, val artistId: String? = null)
@Serializable data class Mix(val id: String, val title: String, val subtitle: String, val tracks: List<Track> = emptyList())
@Serializable data class MixResponse(val mixes: List<Mix>, val seeds: List<String>)

enum class SearchType { SONG, ALBUM, ARTIST, PLAYLIST }
enum class CollectionKind { ALBUM, PLAYLIST }
