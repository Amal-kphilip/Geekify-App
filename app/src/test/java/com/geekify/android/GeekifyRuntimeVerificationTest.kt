package com.geekify.android

import android.database.sqlite.SQLiteDatabase
import androidx.media3.common.PlaybackException
import androidx.media3.datasource.DataSourceException
import androidx.media3.datasource.DataSpec
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.sqlite.db.SupportSQLiteOpenHelper
import com.geekify.android.data.local.GeekifyDatabase
import com.geekify.android.discord.DiscordRpcManager
import com.geekify.android.lyrics.OfflineRomanizer
import com.geekify.android.player.sabr.SabrBufferedSource
import com.geekify.android.player.sabr.SeekableSabrDataSource
import com.geekify.android.player.sabr.cleanupStaleSabrTempFiles
import com.geekify.android.recognition.ShazamRecognitionClient
import com.geekify.android.recognition.ShazamSignatureGenerator
import com.geekify.android.scrobble.ScrobbleManager
import com.metrolist.innertubex.sabr.SabrChunk
import kotlinx.coroutines.flow.flowOf
import okhttp3.OkHttpClient
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64
import kotlin.math.PI
import kotlin.math.sin

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class GeekifyRuntimeVerificationTest {

    @Test
    fun shazamSignatureHasValidHeaderAndTlvLength() {
        val sampleRate = 16000
        val numSamples = sampleRate * 4
        val pcm = ByteArray(numSamples * 2)
        for (i in 0 until numSamples) {
            val t = i.toDouble() / sampleRate
            val env = 0.5 + 0.5 * sin(2.0 * PI * 3.0 * t)
            val s = (sin(2.0 * PI * 520.0 * t) + sin(2.0 * PI * 1350.0 * t) + sin(2.0 * PI * 2800.0 * t)) * env * 9000.0
            val v = s.toInt().coerceIn(-32768, 32767)
            pcm[i * 2] = (v and 0xff).toByte()
            pcm[i * 2 + 1] = ((v ushr 8) and 0xff).toByte()
        }
        val uri = ShazamSignatureGenerator.fromI16(pcm)
        assertTrue(uri.startsWith("data:audio/vnd.shazam.sig;base64,"))
        val raw = Base64.getDecoder().decode(uri.substringAfter("base64,"))
        assertTrue("Signature payload should contain header + TLV frame", raw.size >= 56)

        val bb = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(0xcafe2580.toInt(), bb.getInt(0))
        val sizeMinusHeader = bb.getInt(8)
        val outerChunkTag = bb.getInt(48)
        val outerChunkLen = bb.getInt(52)
        val actualContentsLen = raw.size - 56

        assertEquals(0x40000000, outerChunkTag)
        assertEquals("Outer TLV length must equal contents.size + 8", actualContentsLen + 8, outerChunkLen)
        assertEquals("Header size_minus_header must equal contents.size + 8", actualContentsLen + 8, sizeMinusHeader)
    }

    @Test
    fun shazamParserHandlesMalformedJsonSafely() {
        val client = ShazamRecognitionClient(OkHttpClient())
        assertNull(client.parseResult("not-json"))
        assertNull(client.parseResult("""{"track":{"hub":"not-an-object"}}"""))
        assertNull(client.parseResult("""{}"""))
    }

    @Test
    fun sabrSeekableDataSourceRetainsBufferedDataAcrossCloseAndSeek() {
        val tempDir = File(RuntimeEnvironment.getApplication().cacheDir, "sabr_test_${System.nanoTime()}").apply { mkdirs() }
        val payload = ByteArray(64) { it.toByte() }
        val buffered = SabrBufferedSource(
            cacheDir = tempDir,
            chunkFlowFactory = {
                flowOf(
                    SabrChunk(
                        data = payload,
                        startRange = 0L,
                        isInitialization = true,
                        sequenceNumber = 0,
                        startMs = 0L,
                        durationMs = 1000L
                    )
                )
            },
            contentLength = payload.size.toLong()
        )
        try {
            val ds = SeekableSabrDataSource(sourceFactory = { buffered }, ownsSource = false, mimeType = "audio/mp4")
            // Initial read at offset 0
            assertEquals(64L, ds.open(DataSpec.Builder().setUri("geekify://sabr/test").setPosition(0L).build()))
            val head = ByteArray(8)
            assertEquals(8, ds.read(head, 0, 8))
            assertArrayEquals(payload.copyOfRange(0, 8), head)
            ds.close() // Must NOT destroy shared SabrBufferedSource!
            assertFalse(buffered.isClosed)

            // Forward seek to offset 32
            assertEquals(32L, ds.open(DataSpec.Builder().setUri("geekify://sabr/test").setPosition(32L).build()))
            val mid = ByteArray(8)
            assertEquals(8, ds.read(mid, 0, 8))
            assertArrayEquals(payload.copyOfRange(32, 40), mid)
            ds.close()

            // Backward seek to offset 4
            assertEquals(60L, ds.open(DataSpec.Builder().setUri("geekify://sabr/test").setPosition(4L).build()))
            val back = ByteArray(4)
            assertEquals(4, ds.read(back, 0, 4))
            assertArrayEquals(payload.copyOfRange(4, 8), back)
            ds.close()

            // Out-of-range seek must throw ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE
            var caughtCode: Int? = null
            try {
                ds.open(DataSpec.Builder().setUri("geekify://sabr/test").setPosition(999L).build())
            } catch (e: DataSourceException) {
                caughtCode = e.reason
            }
            assertEquals(PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE, caughtCode)
        } finally {
            buffered.close()
            cleanupStaleSabrTempFiles(tempDir)
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun offlineRomanizerTransliteratesHangulKanaAndCyrillic() {
        assertTrue(OfflineRomanizer.hasNonLatinScript("사랑해"))
        assertEquals("saranghae", OfflineRomanizer.romanize("사랑해"))
        assertEquals("arigatou", OfflineRomanizer.romanize("ありがとう"))
        assertEquals("Privet", OfflineRomanizer.romanize("Привет"))
        assertFalse(OfflineRomanizer.hasNonLatinScript("Hello World"))
    }

    @Test
    fun lastFmMd5SignatureMatchesSpecification() {
        val sig = ScrobbleManager.computeLastFmSignature(
            params = mapOf("method" to "track.scrobble", "api_key" to "KEY123", "artist" to "Daft Punk"),
            secret = "SECRET456"
        )
        assertEquals(32, sig.length)
        assertTrue(sig.all { it in '0'..'9' || it in 'a'..'f' })
        // Determinism check regardless of map insertion order
        val sig2 = ScrobbleManager.computeLastFmSignature(
            params = mapOf("artist" to "Daft Punk", "method" to "track.scrobble", "api_key" to "KEY123"),
            secret = "SECRET456"
        )
        assertEquals(sig, sig2)
    }

    @Test
    fun roomMigration10To11PreservesExistingDataAndCreatesNewTables() {
        val context = RuntimeEnvironment.getApplication()
        val dbName = "migration_test_${System.nanoTime()}.db"
        val factory = FrameworkSQLiteOpenHelperFactory()
        val helper = factory.create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(dbName)
                .callback(object : SupportSQLiteOpenHelper.Callback(10) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        db.execSQL("CREATE TABLE IF NOT EXISTS playlists (id TEXT PRIMARY KEY NOT NULL, name TEXT NOT NULL, createdAt INTEGER NOT NULL)")
                        db.execSQL("CREATE TABLE IF NOT EXISTS liked_songs (videoId TEXT PRIMARY KEY NOT NULL, title TEXT NOT NULL, artist TEXT NOT NULL, likedAt INTEGER NOT NULL)")
                        db.execSQL("INSERT INTO playlists (id, name, createdAt) VALUES ('pl1', 'Road Trip', 1000)")
                        db.execSQL("INSERT INTO liked_songs (videoId, title, artist, likedAt) VALUES ('vid1', 'Track One', 'Artist One', 2000)")
                    }
                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {}
                })
                .build()
        )
        try {
            val db = helper.writableDatabase
            com.geekify.android.data.local.MIGRATION_10_11.migrate(db)

            // Verify existing v10 data is untouched
            db.query("SELECT name FROM playlists WHERE id = 'pl1'").use { c ->
                assertTrue(c.moveToFirst())
                assertEquals("Road Trip", c.getString(0))
            }
            db.query("SELECT title FROM liked_songs WHERE videoId = 'vid1'").use { c ->
                assertTrue(c.moveToFirst())
                assertEquals("Track One", c.getString(0))
            }

            // Verify all 4 v11 tables exist and accept writes
            db.execSQL(
                "INSERT INTO cached_audio (videoId, kind, filePath, mimeType, contentLength, durationMs, title, artist, createdAt, lastAccessedAt) " +
                    "VALUES ('v1', 'DOWNLOAD', '/tmp/v1.m4a', 'audio/mp4', 1024, 180000, 'Song', 'Artist', 1, 1)"
            )
            db.execSQL(
                "INSERT INTO downloads (videoId, title, artist, status, progress, bytesDownloaded, totalBytes, createdAt, updatedAt) " +
                    "VALUES ('v1', 'Song', 'Artist', 'COMPLETED', 1.0, 1024, 1024, 1, 1)"
            )
            db.execSQL(
                "INSERT INTO recognition_history (trackId, title, artist, recognizedAt) VALUES ('t1', 'Hit', 'Band', 1)"
            )
            db.execSQL(
                "INSERT INTO lyrics (videoId, rawLyrics, provider, offsetMs, updatedAt) VALUES ('v1', 'La la la', 'lrclib', 0, 1)"
            )
            db.query("SELECT COUNT(*) FROM downloads").use { c ->
                assertTrue(c.moveToFirst())
                assertEquals(1, c.getInt(0))
            }
        } finally {
            helper.close()
            context.deleteDatabase(dbName)
        }
    }
}
