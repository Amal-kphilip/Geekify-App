package com.geekify.android.recognition

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.geekify.android.data.local.RecognitionHistoryDao
import com.geekify.android.data.local.RecognitionHistoryEntity
import com.geekify.android.data.model.SearchType
import com.geekify.android.data.model.Thumbnail
import com.geekify.android.data.model.Track
import com.geekify.android.data.source.MusicResult
import com.geekify.android.data.source.MusicSource
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * Captures live 44.1 kHz mono PCM16 microphone audio, performs progressive Shazam fingerprint
 * matching at 4s, 8s, and 12s checkpoints, persists matches to [RecognitionHistoryDao], and
 * resolves the playable YouTube Music videoId.
 */
class MusicRecognitionService(
    private val context: Context,
    private val shazamClient: ShazamRecognitionClient,
    private val historyDao: RecognitionHistoryDao,
    private val musicSource: MusicSource,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {
    private val scope = CoroutineScope(SupervisorJob() + ioDispatcher)
    private val sessionMutex = Mutex()
    private var activeJob: Job? = null
    @Volatile private var stopCaptureEarly = false
    @Volatile private var cancelRequested = false

    private val _status = MutableStateFlow<RecognitionStatus>(RecognitionStatus.Ready)
    val status: StateFlow<RecognitionStatus> = _status.asStateFlow()

    private val _audioLevel = MutableStateFlow(0f)
    val audioLevel: StateFlow<Float> = _audioLevel.asStateFlow()

    val history: Flow<List<RecognitionHistoryEntity>> = historyDao.observeAll()

    fun searchHistory(query: String): Flow<List<RecognitionHistoryEntity>> =
        if (query.isBlank()) historyDao.observeAll() else historyDao.search(query.trim())

    fun hasMicrophonePermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    fun startRecognition() {
        if (!hasMicrophonePermission()) {
            _status.value = RecognitionStatus.Error("Microphone permission is required to identify songs.")
            return
        }
        val current = _status.value
        if (current is RecognitionStatus.Listening || current is RecognitionStatus.Processing) {
            return
        }
        cancelRequested = false
        stopCaptureEarly = false
        activeJob?.cancel()
        activeJob = scope.launch {
            runRecognitionSession()
        }
    }

    /**
     * Stops microphone capture. If at least 3 seconds of audio were already captured and [abort]
     * is false, proceeds to match the captured audio; otherwise resets to [RecognitionStatus.Ready].
     */
    fun stopRecognition(abort: Boolean = true) {
        if (abort) {
            cancelRequested = true
            activeJob?.cancel()
            activeJob = null
            _audioLevel.value = 0f
            if (_status.value is RecognitionStatus.Listening || _status.value is RecognitionStatus.Processing) {
                _status.value = RecognitionStatus.Ready
            }
        } else {
            stopCaptureEarly = true
        }
    }

    fun reset() {
        stopRecognition(abort = true)
        _status.value = RecognitionStatus.Ready
    }

    fun deleteHistoryItem(id: Long) {
        scope.launch { historyDao.deleteById(id) }
    }

    fun clearHistory() {
        scope.launch { historyDao.clearAll() }
    }

    suspend fun resolveTrackForPlayback(
        title: String,
        artist: String,
        album: String?,
        coverArtUrl: String?,
        existingVideoId: String?,
        historyId: Long? = null
    ): Track? = withContext(ioDispatcher) {
        val videoId = existingVideoId?.takeIf { it.isNotBlank() }
            ?: resolveYoutubeVideoId("$title $artist".trim())?.also { resolved ->
                if (historyId != null && historyId > 0L) {
                    runCatching { historyDao.updateYoutubeVideoId(historyId, resolved) }
                }
            }
            ?: return@withContext null
        Track(
            videoId = videoId,
            title = title,
            artist = artist,
            album = album,
            thumbnails = listOfNotNull(coverArtUrl?.let { Thumbnail(it) })
        )
    }

    @SuppressLint("MissingPermission")
    private suspend fun runRecognitionSession() = withContext(ioDispatcher) {
        if (!sessionMutex.tryLock()) return@withContext
        try {
            _status.value = RecognitionStatus.Listening
            _audioLevel.value = 0f

            val captureSampleRate = 44_100
            val targetSampleRate = 16_000
            val channelConfig = AudioFormat.CHANNEL_IN_MONO
            val encoding = AudioFormat.ENCODING_PCM_16BIT
            val minBuf = AudioRecord.getMinBufferSize(captureSampleRate, channelConfig, encoding)
            if (minBuf <= 0) {
                _status.value = RecognitionStatus.Error("Microphone hardware does not support 44.1 kHz PCM recording.")
                return@withContext
            }
            val bufferSize = minBuf.coerceAtLeast(captureSampleRate / 2)

            val recorder = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                captureSampleRate,
                channelConfig,
                encoding,
                bufferSize
            )

            if (recorder.state != AudioRecord.STATE_INITIALIZED) {
                recorder.release()
                _status.value = RecognitionStatus.Error("Could not initialize microphone audio recorder.")
                return@withContext
            }

            val capturedPcm = ByteArrayOutputStream(captureSampleRate * 2 * 12)
            val maxDurationMs = 12_000L
            val checkpointsMs = longArrayOf(4_200L, 8_000L)
            var nextCheckpointIdx = 0
            val startElapsed = SystemClock.elapsedRealtime()
            var earlyMatched: RecognitionResult? = null

            try {
                recorder.startRecording()
                if (recorder.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                    _status.value = RecognitionStatus.Error("Microphone is in use by another application.")
                    return@withContext
                }
                val buf = ByteArray(bufferSize)
                var consecutiveZeroReads = 0
                while (isActive && !cancelRequested && !stopCaptureEarly) {
                    val elapsed = SystemClock.elapsedRealtime() - startElapsed
                    if (elapsed >= maxDurationMs) break

                    val read = recorder.read(buf, 0, buf.size)
                    if (read < 0) {
                        _status.value = RecognitionStatus.Error("Microphone read error ($read).")
                        return@withContext
                    }
                    if (read == 0) {
                        if (++consecutiveZeroReads > 50) {
                            _status.value = RecognitionStatus.Error("Microphone stopped delivering audio.")
                            return@withContext
                        }
                        continue
                    }
                    consecutiveZeroReads = 0
                    capturedPcm.write(buf, 0, read)
                    _audioLevel.value = computeAudioLevel(buf, read)

                    // Progressive checkpoint recognition at ~4.2s and ~8s
                    if (nextCheckpointIdx < checkpointsMs.size && elapsed >= checkpointsMs[nextCheckpointIdx]) {
                        nextCheckpointIdx++
                        val snapshot = capturedPcm.toByteArray()
                        if (snapshot.size >= captureSampleRate * 2 * 3) {
                            val resampled = resample16BitMono(snapshot, captureSampleRate, targetSampleRate)
                            val sampleDurationMs = (resampled.size / 2) * 1000L / targetSampleRate
                            val sig = ShazamSignatureGenerator.fromI16(resampled)
                            val tryMatch = shazamClient.recognize(sig, sampleDurationMs).getOrNull()
                            if (tryMatch != null) {
                                earlyMatched = tryMatch
                                break
                            }
                        }
                    }
                }
            } catch (ce: CancellationException) {
                _status.value = RecognitionStatus.Ready
                throw ce
            } catch (e: Exception) {
                Timber.tag(LOG_TAG).e(e, "Audio capture failed")
                _status.value = RecognitionStatus.Error(e.message ?: "Audio capture failed.")
                return@withContext
            } finally {
                _audioLevel.value = 0f
                runCatching { recorder.stop() }
                runCatching { recorder.release() }
            }

            if (cancelRequested) {
                _status.value = RecognitionStatus.Ready
                return@withContext
            }

            if (earlyMatched != null) {
                handleMatchSuccess(earlyMatched)
                return@withContext
            }

            val rawBytes = capturedPcm.toByteArray()
            if (rawBytes.size < captureSampleRate * 2 * 2) {
                _status.value = RecognitionStatus.Error("Not enough audio captured. Hold your phone closer to the music.")
                return@withContext
            }

            _status.value = RecognitionStatus.Processing
            val resampled16k = resample16BitMono(rawBytes, captureSampleRate, targetSampleRate)
            val sampleDurationMs = (resampled16k.size / 2) * 1000L / targetSampleRate
            val signature = ShazamSignatureGenerator.fromI16(resampled16k)

            shazamClient.recognize(signature, sampleDurationMs).fold(
                onSuccess = { match ->
                    if (!cancelRequested) {
                        handleMatchSuccess(match)
                    }
                },
                onFailure = { err ->
                    if (cancelRequested) {
                        _status.value = RecognitionStatus.Ready
                    } else {
                        Timber.tag(LOG_TAG).w(err, "Shazam recognition did not match")
                        _status.value = when (err) {
                            is NoRecognitionMatchException -> RecognitionStatus.NoMatch(
                                "No matching song found. Try again during a clearer section of the track."
                            )
                            is RecognitionUserException -> RecognitionStatus.Error(err.message ?: "Recognition service busy.")
                            else -> RecognitionStatus.Error("Network error while contacting recognition service.")
                        }
                    }
                }
            )
        } finally {
            sessionMutex.unlock()
        }
    }

    private suspend fun handleMatchSuccess(match: RecognitionResult) {
        val insertedId = historyDao.insert(
            RecognitionHistoryEntity(
                trackId = match.trackId.ifBlank { "${match.title}:${match.artist}" },
                title = match.title,
                artist = match.artist,
                album = match.album,
                coverArtUrl = match.coverArtHqUrl ?: match.coverArtUrl,
                genre = match.genre,
                releaseDate = match.releaseDate,
                label = match.label,
                shazamUrl = match.shazamUrl,
                spotifyUrl = match.spotifyUrl,
                appleMusicUrl = match.appleMusicUrl,
                isrc = match.isrc,
                youtubeVideoId = match.youtubeVideoId
            )
        )
        _status.value = RecognitionStatus.Success(match)

        if (match.youtubeVideoId.isNullOrBlank()) {
            val query = "${match.title} ${match.artist}".trim()
            val resolvedId = resolveYoutubeVideoId(query)
            if (!resolvedId.isNullOrBlank() && !cancelRequested) {
                historyDao.updateYoutubeVideoId(insertedId, resolvedId)
                _status.value = RecognitionStatus.Success(match.copy(youtubeVideoId = resolvedId))
            }
        }
    }

    private fun computeAudioLevel(buffer: ByteArray, read: Int): Float {
        val sampleCount = read / 2
        if (sampleCount == 0) return 0f
        val samples = ByteBuffer.wrap(buffer, 0, read).order(ByteOrder.LITTLE_ENDIAN)
        var sumSquares = 0.0
        repeat(sampleCount) {
            val sample = samples.short.toDouble()
            sumSquares += sample * sample
        }
        val rms = sqrt(sumSquares / sampleCount) / 32768.0
        return (sqrt(rms) * 1.6).coerceIn(0.0, 1.0).toFloat()
    }

    private suspend fun resolveYoutubeVideoId(query: String): String? {
        if (query.isBlank()) return null
        for (filter in listOf(SearchType.SONG, SearchType.VIDEO, null)) {
            val res = runCatching { musicSource.search(query, filter) }.getOrNull()
            val hit = (res as? MusicResult.Success)?.value?.songs?.firstOrNull()?.videoId
            if (!hit.isNullOrBlank()) return hit
        }
        return null
    }

    companion object {
        private const val LOG_TAG = "MusicRecognition"

        fun resample16BitMono(
            input: ByteArray,
            inputRate: Int,
            outputRate: Int
        ): ByteArray {
            if (inputRate == outputRate) return input
            val inputSamples = ShortArray(input.size / 2)
            ByteBuffer.wrap(input)
                .order(ByteOrder.LITTLE_ENDIAN)
                .asShortBuffer()
                .get(inputSamples)
            val ratio = outputRate.toDouble() / inputRate.toDouble()
            val outputSamples = ShortArray((inputSamples.size * ratio).toInt())
            for (index in outputSamples.indices) {
                val source = index / ratio
                val base = source.toInt().coerceIn(0, inputSamples.lastIndex)
                val next = (base + 1).coerceAtMost(inputSamples.lastIndex)
                val fraction = source - base
                outputSamples[index] =
                    (inputSamples[base] * (1.0 - fraction) + inputSamples[next] * fraction).toInt().toShort()
            }
            return ByteArray(outputSamples.size * 2).also {
                ByteBuffer.wrap(it)
                    .order(ByteOrder.LITTLE_ENDIAN)
                    .asShortBuffer()
                    .put(outputSamples)
            }
        }
    }
}
