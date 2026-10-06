package com.geekify.android.player

import com.geekify.android.data.model.Thumbnail
import com.geekify.android.data.model.Track
import com.geekify.android.player.artwork.ArtworkUrls
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ArtworkUrlsTest {
    @Test fun blankAndUnknownSchemesAreRejected() {
        assertNull(ArtworkUrls.normalize(null))
        assertNull(ArtworkUrls.normalize("  "))
        assertNull(ArtworkUrls.normalize("/storage/emulated/0/cover.jpg"))
    }
    @Test fun protocolRelativeBecomesHttps() =
        assertEquals("https://lh3.googleusercontent.com/a=w60-h60", ArtworkUrls.normalize("//lh3.googleusercontent.com/a=w60-h60"))
    @Test fun googleHttpIsUpgraded() =
        assertEquals("https://i.ytimg.com/vi/x/hq.jpg", ArtworkUrls.normalize("http://i.ytimg.com/vi/x/hq.jpg"))
    @Test fun contentUriIsKept() =
        assertEquals("content://media/external/audio/albumart/5", ArtworkUrls.normalize("content://media/external/audio/albumart/5"))
    @Test fun biggestThumbnailIsResizedToNotificationSize() {
        val t = listOf(
            Thumbnail("https://lh3.googleusercontent.com/a=w60-h60", 60, 60),
            Thumbnail("https://lh3.googleusercontent.com/a=w544-h544", 544, 544)
        )
        assertEquals("https://lh3.googleusercontent.com/a=w544-h544", ArtworkUrls.best(t))
    }
    @Test fun invalidFirstThumbnailFallsThroughToNextUsableOne() {
        val t = listOf(Thumbnail("garbage", 900, 900), Thumbnail("https://x.test/a.jpg", 100, 100))
        assertEquals("https://x.test/a.jpg", ArtworkUrls.best(t))
    }
    @Test fun missingThumbnailsFallBackToVideoId() =
        assertEquals("https://i.ytimg.com/vi/dQw4w9WgXcQ/hqdefault.jpg",
            ArtworkUrls.forTrack(Track(videoId = "dQw4w9WgXcQ", title = "t", artist = "a")))
}
