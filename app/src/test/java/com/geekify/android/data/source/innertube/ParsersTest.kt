package com.geekify.android.data.source.innertube

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class ParsersTest {
    @Test fun `responsive list item becomes a track`() {
        val raw = Json.parseToJsonElement(
            """{"musicResponsiveListItemRenderer":{"flexColumns":[
              {"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"Song"}]}}},
              {"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"Artist","navigationEndpoint":{"browseEndpoint":{"browseId":"UCartist","browseEndpointContextSupportedConfigs":{"browseEndpointContextMusicConfig":{"pageType":"MUSIC_PAGE_TYPE_ARTIST"}}}}},{"text":" • "},{"text":"3:45"}]}}}
            ],"playlistItemData":{"videoId":"abcdefghijk"},"thumbnail":{"thumbnails":[{"url":"https://i.ytimg.com/a.jpg","width":120}]}}}"""
        )
        val track = Parsers.tracks(raw).single()
        assertEquals("abcdefghijk", track.videoId)
        assertEquals("Song", track.title)
        assertEquals("Artist", track.artist)
        assertEquals(225, track.durationSeconds)
        assertNotNull(track.thumbnails.firstOrNull())
    }
    @Test fun `two row song card with playlist and artist context stays distinct song`() {
        val raw = Json.parseToJsonElement(
            """{"musicTwoRowItemRenderer":{"title":{"simpleText":"Song"},"subtitle":{"runs":[{"text":"Artist","navigationEndpoint":{"browseEndpoint":{"browseId":"UCartist","browseEndpointContextSupportedConfigs":{"browseEndpointContextMusicConfig":{"pageType":"MUSIC_PAGE_TYPE_ARTIST"}}}}}]},"thumbnail":{"thumbnails":[{"url":"https://i.ytimg.com/a.jpg"}]},"navigationEndpoint":{"watchEndpoint":{"videoId":"song1234567","playlistId":"PLcontext"}}}}"""
        )
        val card = Parsers.cards(raw).single()
        assertEquals("song", card.type)
        assertEquals("song1234567", card.videoId)
        assertEquals("song1234567", card.id)
    }

}
