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

    @Test fun `iterative walk handles deeply nested structures without stack overflow`() {
        // Construct deeply nested JSON object with 150 levels
        var jsonStr = """{"musicResponsiveListItemRenderer":{"playlistItemData":{"videoId":"deep123"},"flexColumns":[{"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"Deep Song"}]}}}]}}"""
        repeat(150) {
            jsonStr = """{"nested":$jsonStr}"""
        }
        val raw = Json.parseToJsonElement(jsonStr)
        val tracks = Parsers.tracks(raw)
        assertEquals(1, tracks.size)
        assertEquals("deep123", tracks.first().videoId)
        assertEquals("Deep Song", tracks.first().title)
    }

    @Test fun `handles malformed and empty elements gracefully`() {
        val emptyObj = Json.parseToJsonElement("{}")
        assertEquals(0, Parsers.tracks(emptyObj).size)
        assertEquals(0, Parsers.cards(emptyObj).size)

        val malformedRenderer = Json.parseToJsonElement(
            """{"musicResponsiveListItemRenderer":{}}"""
        )
        val parsed = Parsers.tracks(malformedRenderer)
        assertEquals(0, parsed.size)
    }

    @Test fun `iterative walk preserves source result order`() {
        val raw = Json.parseToJsonElement(
            """{"contents":[
              {"musicResponsiveListItemRenderer":{"playlistItemData":{"videoId":"first123456"},"flexColumns":[{"musicResponsiveListItemFlexColumnRenderer":{"text":{"simpleText":"First result"}}}]}},
              {"musicResponsiveListItemRenderer":{"playlistItemData":{"videoId":"second12345"},"flexColumns":[{"musicResponsiveListItemFlexColumnRenderer":{"text":{"simpleText":"Second result"}}}]}}
            ]}"""
        )
        assertEquals(listOf("first123456", "second12345"), Parsers.tracks(raw).map { it.videoId })
    }

    @Test fun `parseSearchResponseSinglePass extracts topResult didYouMean and continuation`() {
        val raw = Json.parseToJsonElement(
            """{
              "contents": {
                "tabbedSearchResultsRenderer": {
                  "tabs": [{
                    "tabRenderer": {
                      "content": {
                        "sectionListRenderer": {
                          "contents": [
                            {
                              "musicCardShelfRenderer": {
                                "title": { "runs": [{ "text": "Blinding Lights" }] },
                                "subtitle": { "runs": [{ "text": "The Weeknd • After Hours • 2020" }] },
                                "header": { "musicCardShelfHeaderBasicRenderer": { "title": { "runs": [{ "text": "Top result" }] } } },
                                "onTap": { "watchEndpoint": { "videoId": "4NRXx6U8ABQ" } },
                                "thumbnail": { "musicThumbnailRenderer": { "thumbnail": { "thumbnails": [{ "url": "https://img.yt.com/top.jpg", "width": 500 }] } } }
                              }
                            },
                            {
                              "showingResultsForRenderer": {
                                "correctedQuery": { "runs": [{ "text": "the weeknd blinding lights" }] }
                              }
                            },
                            {
                              "musicShelfRenderer": {
                                "title": { "runs": [{ "text": "Songs" }] },
                                "contents": [
                                  {
                                    "musicResponsiveListItemRenderer": {
                                      "playlistItemData": { "videoId": "4NRXx6U8ABQ" },
                                      "flexColumns": [
                                        { "musicResponsiveListItemFlexColumnRenderer": { "text": { "runs": [{ "text": "Blinding Lights" }] } } },
                                        { "musicResponsiveListItemFlexColumnRenderer": { "text": { "runs": [{ "text": "The Weeknd" }, { "text": " • " }, { "text": "3:20" }] } } }
                                      ]
                                    }
                                  }
                                ]
                              }
                            },
                            {
                              "continuationItemRenderer": {
                                "continuationEndpoint": {
                                  "continuation": "4wINGA..."
                                },
                                "nextContinuationData": {
                                  "continuation": "token_abc_123"
                                }
                              }
                            }
                          ]
                        }
                      }
                    }
                  }]
                }
              }
            }"""
        )

        val response = Parsers.parseSearchResponseSinglePass(raw, "blnding lights", null)
        assertEquals("blnding lights", response.query)
        assertNotNull(response.topResult)
        assertEquals("4NRXx6U8ABQ", response.topResult?.videoId)
        assertEquals("Blinding Lights", response.topResult?.title)
        assertEquals("the weeknd blinding lights", response.didYouMean)
        assertEquals("token_abc_123", response.continuation)
        assertEquals(1, response.songs.size)
        assertEquals("4NRXx6U8ABQ", response.songs.first().videoId)
    }

    @Test fun `top result song in musicCardShelfRenderer is placed first in songs and ignores secondary card contents`() {
        val raw = Json.parseToJsonElement(
            """{
              "contents": {
                "sectionListRenderer": {
                  "contents": [
                    {
                      "musicCardShelfRenderer": {
                        "title": {
                          "runs": [{
                            "text": "Espresso",
                            "navigationEndpoint": { "watchEndpoint": { "videoId": "topSong1111" } }
                          }]
                        },
                        "subtitle": { "runs": [{ "text": "Song • Sabrina Carpenter • 2:55" }] },
                        "contents": [
                          {
                            "musicResponsiveListItemRenderer": {
                              "playlistItemData": { "videoId": "nextSong222" },
                              "flexColumns": [
                                { "musicResponsiveListItemFlexColumnRenderer": { "text": { "runs": [{ "text": "Please Please Please" }] } } },
                                { "musicResponsiveListItemFlexColumnRenderer": { "text": { "runs": [{ "text": "Sabrina Carpenter" }, { "text": " • " }, { "text": "3:06" }] } } }
                              ]
                            }
                          }
                        ]
                      }
                    }
                  ]
                }
              }
            }"""
        )

        val response = Parsers.parseSearchResponseSinglePass(raw, "espresso", null)
        assertNotNull(response.topResult)
        assertEquals("topSong1111", response.topResult?.videoId)
        assertEquals(listOf("topSong1111", "nextSong222"), response.songs.map { it.videoId })
        assertEquals("Sabrina Carpenter", response.songs.first().artist)
        assertEquals(175, response.songs.first().durationSeconds)
    }
}
