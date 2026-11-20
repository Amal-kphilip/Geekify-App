@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.geekify.android.player.sabr

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSourceException
import androidx.media3.datasource.DataSpec
import com.metrolist.innertubex.InnerTube
import com.metrolist.innertubex.InnerTubeLogger
import com.metrolist.innertubex.extraction.TokenProvider
import com.metrolist.innertubex.extraction.strategy.PlaybackClientCatalog
import com.metrolist.innertubex.extraction.strategy.PoTokenProviderKind
import com.metrolist.innertubex.sabr.ExperimentalSabrApi
import com.metrolist.innertubex.sabr.SabrAudioStream
import com.metrolist.innertubex.sabr.SabrBootstrap
import com.metrolist.innertubex.sabr.SabrChunk
import com.metrolist.innertubex.sabr.SabrFailureKind
import com.metrolist.innertubex.sabr.SabrProtocolException
import com.metrolist.innertubex.sabr.SabrResponseDiagnostics
import io.ktor.client.HttpClient
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.InterruptedIOException
import java.io.RandomAccessFile
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.io.encoding.Base64
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch

private const val MAX_NO_PROGRESS_REQUESTS = 2
private const val PROTECTION_ATTESTATION_PENDING = 2

internal fun sabrStreamingTokenSupplier(
    bootstrap: SabrBootstrap,
    profileId: String,
    innerTube: InnerTube,
    provider: TokenProvider?
): (suspend () -> ByteArray?)? {
    if (bootstrap.poToken != null || provider == null) return null
    val manifest = PlaybackClientCatalog.findManifest(profileId.substringBefore("__")) ?: return null
    val kinds = manifest.poTokens.gvs.providers.ifEmpty { setOf(PoTokenProviderKind.WEBPAGE_ATTESTATION) }
    if (kinds.none { it in provider.capabilities.providers }) return null
    val session = innerTube.sessionSnapshot()
    return supplier@{
        if (innerTube.sessionSnapshot().generation != session.generation ||
            kinds.none { it in provider.capabilities.providers }
        ) {
            return@supplier null
        }
        val visitor = bootstrap.tokenVisitorData?.takeIf(String::isNotBlank)
            ?: session.visitorData?.takeIf(String::isNotBlank)
            ?: innerTube.fetchFreshVisitorData(session)
            ?: return@supplier null
        val result = provider.getPoToken(
            bootstrap.videoId,
            visitor,
            session.cookie.takeIf { manifest.request.cookies }
        )
        if (result?.visitorData != visitor) return@supplier null
        val token = result.streamingDataToken
        if (token.isBlank() || token.length > 21848) return@supplier null
        val standard = token.replace('-', '+').replace('_', '/')
        Base64.decode(standard.padEnd(standard.length + (4 - standard.length % 4) % 4, '='))
            .takeIf { it.isNotEmpty() && it.size <= 16 * 1024 }
    }
}

@OptIn(ExperimentalSabrApi::class)
fun sabrAudioPlaybackChunks(
    httpClient: HttpClient,
    bootstrap: SabrBootstrap,
    playbackPositionMs: () -> Long,
    initialPlayerTimeMs: Long = 0L,
    onResponse: ((SabrResponseDiagnostics) -> Unit)? = null,
    requestRetryDelay: suspend (Long) -> Unit = { delay(it) },
    logger: InnerTubeLogger = InnerTubeLogger.NONE,
    tokenSupplier: (suspend () -> ByteArray?)? = null,
    onAttestationPending: (() -> Unit)? = null
): Flow<SabrChunk> = flow {
    val tokenAvailable = AtomicBoolean(bootstrap.poToken?.isNotEmpty() == true)
    var tokenObserved = tokenAvailable.get()
    var pendingReported = false
    emitAll(
        SabrAudioStream(
            httpClient = httpClient,
            bootstrap = bootstrap,
            maxRequestsWithoutProgress = MAX_NO_PROGRESS_REQUESTS,
            playbackPositionMs = playbackPositionMs,
            onResponse = { response ->
                onResponse?.invoke(response)
                if (tokenObserved && response.protectionStatus == PROTECTION_ATTESTATION_PENDING) {
                    if (!pendingReported) {
                        pendingReported = true
                        onAttestationPending?.invoke()
                    }
                    if (response.requestNumber > 0) {
                        throw SabrProtocolException(
                            "SABR attestation remains pending with a PO token",
                            SabrFailureKind.ATTESTATION_REQUIRED
                        )
                    }
                }
                tokenObserved = tokenAvailable.get()
            },
            requestRetryDelay = requestRetryDelay,
            initialPlayerTimeMs = initialPlayerTimeMs,
            logger = logger,
            tokenSupplier = tokenSupplier?.let { supplier ->
                {
                    supplier()?.also {
                        if (it.isNotEmpty() && it.size <= 16 * 1024) tokenAvailable.set(true)
                    }
                }
            }
        ).chunks()
    )
}

/**
 * Sweeps orphaned `geekify_sabr_*.bin` temp files left behind by prior process kills.
 */
fun cleanupStaleSabrTempFiles(cacheDir: File) {
    runCatching {
        cacheDir.listFiles { file ->
            file.isFile && file.name.startsWith("geekify_sabr_") && file.name.endsWith(".bin")
        }?.forEach { file ->
            runCatching { file.delete() }
        }
    }
}

/**
 * Temp-file backed seekable stream that collects [Flow]<[SabrChunk]> in the background
 * on [Dispatchers.IO] and serves random-access reads to Media3's [SeekableSabrDataSource].
 *
 * Unlike a single-use network stream, a single [SabrBufferedSource] instance is shared across
 * Media3's `close()`/`open(newPos)` seek cycles for the lifetime of a track so backward and
 * forward seeks within buffered data complete immediately without restarting SABR from byte 0.
 */
class SabrBufferedSource(
    cacheDir: File,
    private val chunkFlowFactory: (initialPlayerTimeMs: Long) -> Flow<SabrChunk>,
    val contentLength: Long = C.LENGTH_UNSET.toLong()
) : Closeable {
    private val lock = Any()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val tempFile: File = File.createTempFile("geekify_sabr_", ".bin", cacheDir)
    private val raf = RandomAccessFile(tempFile, "rw")
    private val writtenEnd = AtomicLong(0L)
    private val completed = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)
    @Volatile private var failure: Throwable? = null
    private var producerJob: Job? = null

    val bufferedBytes: Long get() = writtenEnd.get()
    val isClosed: Boolean get() = closed.get()

    init {
        startProducer(0L)
    }

    private fun startProducer(startTimeMs: Long) {
        producerJob?.cancel()
        producerJob = scope.launch {
            try {
                chunkFlowFactory(startTimeMs).collect { chunk ->
                    if (closed.get()) return@collect
                    val data = chunk.data
                    if (data.isNotEmpty()) {
                        synchronized(lock) {
                            if (closed.get()) return@synchronized
                            raf.seek(writtenEnd.get())
                            raf.write(data)
                            writtenEnd.addAndGet(data.size.toLong())
                            notifyLock()
                        }
                    }
                }
                synchronized(lock) {
                    completed.set(true)
                    notifyLock()
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                synchronized(lock) {
                    failure = t
                    completed.set(true)
                    notifyLock()
                }
            }
        }
    }

    fun readAt(position: Long, buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        synchronized(lock) {
            val deadline = System.currentTimeMillis() + READ_TIMEOUT_MS
            while (!closed.get() && position >= writtenEnd.get() && !completed.get()) {
                val remaining = deadline - System.currentTimeMillis()
                if (remaining <= 0L) {
                    throw IOException("Timed out waiting for SABR audio data at offset $position")
                }
                try {
                    waitLock(minOf(remaining, 250L))
                } catch (ie: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw InterruptedIOException("Interrupted waiting for SABR audio data at offset $position")
                }
            }
            if (closed.get()) return C.RESULT_END_OF_INPUT
            val available = (writtenEnd.get() - position).coerceAtMost(length.toLong()).toInt()
            if (available <= 0) {
                failure?.let { throw IOException("SABR stream failed: ${it.message}", it) }
                return C.RESULT_END_OF_INPUT
            }
            raf.seek(position)
            return raf.read(buffer, offset, available)
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        producerJob?.cancel()
        scope.cancel()
        synchronized(lock) {
            notifyLock()
            runCatching { raf.close() }
        }
        runCatching { tempFile.delete() }
    }

    // Java monitor wait/notifyAll require casting kotlin.Any to java.lang.Object.
    @Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN")
    private fun notifyLock() = (lock as Object).notifyAll()

    @Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN")
    private fun waitLock(timeoutMs: Long) = (lock as Object).wait(timeoutMs)

    private companion object {
        const val READ_TIMEOUT_MS = 20_000L
    }
}

/**
 * Media3 [BaseDataSource] wrapping [SabrBufferedSource] so ExoPlayer plays SABR streams
 * natively through [androidx.media3.exoplayer.source.ProgressiveMediaSource].
 *
 * When [ownsSource] is `false` (default when sharing a track-scoped [SabrBufferedSource]),
 * [close] ends the current DataSpec read without terminating the background SABR producer,
 * allowing Media3 container sniffers and seeks to re-open at any offset without losing buffered bytes.
 */
@UnstableApi
class SeekableSabrDataSource(
    private val sourceFactory: () -> SabrBufferedSource,
    private val ownsSource: Boolean = false,
    private val mimeType: String? = null
) : BaseDataSource(/* isNetwork = */ true) {

    private var uri: Uri? = null
    private var source: SabrBufferedSource? = null
    private var position: Long = 0L
    private var bytesRemaining: Long = C.LENGTH_UNSET.toLong()
    private var opened = false

    override fun open(dataSpec: DataSpec): Long {
        uri = dataSpec.uri
        transferInitializing(dataSpec)
        val buffered = source?.takeUnless { it.isClosed } ?: sourceFactory().also { source = it }
        position = dataSpec.position
        val total = buffered.contentLength
        if (total != C.LENGTH_UNSET.toLong() && position > total) {
            throw DataSourceException(PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE)
        }
        bytesRemaining = when {
            dataSpec.length != C.LENGTH_UNSET.toLong() -> dataSpec.length
            total != C.LENGTH_UNSET.toLong() -> (total - position).coerceAtLeast(0L)
            else -> C.LENGTH_UNSET.toLong()
        }
        opened = true
        transferStarted(dataSpec)
        return bytesRemaining
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (bytesRemaining == 0L) return C.RESULT_END_OF_INPUT
        val toRead = if (bytesRemaining == C.LENGTH_UNSET.toLong()) {
            length
        } else {
            minOf(bytesRemaining, length.toLong()).toInt()
        }
        val activeSource = source ?: return C.RESULT_END_OF_INPUT
        val read = activeSource.readAt(position, buffer, offset, toRead)
        if (read == C.RESULT_END_OF_INPUT || read < 0) return C.RESULT_END_OF_INPUT
        position += read
        if (bytesRemaining != C.LENGTH_UNSET.toLong()) {
            bytesRemaining -= read
        }
        bytesTransferred(read)
        return read
    }

    override fun getUri(): Uri? = uri

    override fun getResponseHeaders(): MutableMap<String, MutableList<String>> {
        val headers = mutableMapOf<String, MutableList<String>>()
        mimeType?.takeIf { it.isNotBlank() }?.let {
            headers["Content-Type"] = mutableListOf(it)
        }
        return headers
    }

    override fun close() {
        if (opened) {
            opened = false
            transferEnded()
        }
        if (ownsSource) {
            source?.close()
            source = null
        }
        uri = null
    }
}
