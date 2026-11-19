package com.geekify.android.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.geekify.android.customization.LyricsCustomizationConfig
import com.geekify.android.customization.LyricsTextAlignment
import com.geekify.android.data.model.Track
import com.geekify.android.lyrics.LyricsLookupOutcome
import com.geekify.android.lyrics.LyricsRepository
import com.geekify.android.lyrics.LyricsSyncType
import com.geekify.android.lyrics.OfflineRomanizer
import com.geekify.android.lyrics.ParsedLyrics
import com.geekify.android.player.PlaybackPosition
import com.geekify.android.ui.theme.InkElevated
import com.geekify.android.ui.theme.InkPanel
import com.geekify.android.ui.theme.Lime
import com.geekify.android.ui.theme.OnAccent
import com.geekify.android.ui.theme.TextPrimary
import com.geekify.android.ui.theme.TextSecondary
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

@Composable
fun SynchronizedLyricsView(
    track: Track,
    positionFlow: StateFlow<PlaybackPosition>,
    config: LyricsCustomizationConfig,
    onSeekTo: (Long) -> Unit,
    modifier: Modifier = Modifier
) {
    val lyricsRepository: LyricsRepository = koinInject()
    val position by positionFlow.collectAsState()
    val scope = rememberCoroutineScope()

    var isLoading by remember(track.videoId) { mutableStateOf(true) }
    var outcome by remember(track.videoId) { mutableStateOf<LyricsLookupOutcome?>(null) }
    var activeLyrics by remember(track.videoId) { mutableStateOf<ParsedLyrics?>(null) }
    var alternatives by remember(track.videoId) { mutableStateOf<List<ParsedLyrics>>(emptyList()) }
    var showSourcePicker by remember(track.videoId) { mutableStateOf(false) }
    var offsetMs by remember(track.videoId) { mutableLongStateOf(0L) }
    var retryToken by remember(track.videoId) { mutableIntStateOf(0) }
    var queriedWithKnownDuration by remember(track.videoId) { mutableStateOf(false) }

    val resolvedPlayerDurationSec = (position.durationMs / 1000L).toInt().takeIf { it > 0 }

    LaunchedEffect(track.videoId, retryToken) {
        val requestVideoId = track.videoId
        isLoading = true
        val forceRefresh = retryToken > 0
        val collectAlts = showSourcePicker || retryToken > 0
        if (track.durationSeconds != null || resolvedPlayerDurationSec != null) {
            queriedWithKnownDuration = true
        }
        try {
            val result = lyricsRepository.getOrFetchOutcome(
                videoId = requestVideoId,
                title = track.title,
                artist = track.artist,
                durationSeconds = track.durationSeconds ?: resolvedPlayerDurationSec,
                durationMs = position.durationMs,
                album = track.album,
                forceRefresh = forceRefresh,
                collectAlternatives = collectAlts
            )
            if (track.videoId == requestVideoId) {
                outcome = result
                if (result is LyricsLookupOutcome.Success) {
                    activeLyrics = result.lyrics
                    alternatives = result.alternatives
                    offsetMs = result.lyrics.offsetMs
                } else {
                    activeLyrics = null
                    alternatives = emptyList()
                }
                isLoading = false
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
            if (track.videoId == requestVideoId) {
                outcome = LyricsLookupOutcome.NetworkFailure(e.message ?: "Unexpected lyrics lookup error")
                activeLyrics = null
                isLoading = false
            }
        }
    }

    // If the initial query ran before ExoPlayer reported durationMs and found no synced match,
    // automatically re-run once with the accurate duration.
    LaunchedEffect(track.videoId, resolvedPlayerDurationSec) {
        if (!queriedWithKnownDuration && resolvedPlayerDurationSec != null && !isLoading) {
            val current = activeLyrics
            if (current == null || !current.isSynced) {
                queriedWithKnownDuration = true
                retryToken++
            }
        }
    }

    val currentParsed = activeLyrics?.copy(offsetMs = offsetMs)
    val lines = currentParsed?.lines.orEmpty()
    val isSynced = currentParsed?.isSynced == true
    val activeIndex = remember(currentParsed, position.positionMs, offsetMs) {
        currentParsed?.activeLineIndex(position.positionMs) ?: -1
    }

    val listState = rememberLazyListState()
    LaunchedEffect(track.videoId, isSynced, activeIndex) {
        if (isSynced && activeIndex >= 0 && lines.size > 1) {
            runCatching { listState.animateScrollToItem((activeIndex - 1).coerceAtLeast(0)) }
        }
    }

    val textAlign = when (config.alignment) {
        LyricsTextAlignment.START -> TextAlign.Start
        LyricsTextAlignment.CENTER -> TextAlign.Center
        LyricsTextAlignment.END -> TextAlign.End
    }
    val activeColor = if (config.activeColorFollowsAccent) Lime else TextPrimary

    Box(
        modifier = modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(24.dp))
            .background(InkPanel.copy(alpha = 0.84f))
            .border(1.dp, Color.White.copy(alpha = 0.10f), RoundedCornerShape(24.dp))
            .padding(12.dp)
    ) {
        when {
            isLoading -> {
                Column(
                    modifier = Modifier.align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    CircularProgressIndicator(
                        color = Lime,
                        modifier = Modifier.size(32.dp)
                    )
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = "Searching synchronized lyrics…",
                        color = TextSecondary,
                        fontSize = 13.sp
                    )
                }
            }

            currentParsed == null || lines.isEmpty() -> {
                val (headline, detail) = when (val o = outcome) {
                    is LyricsLookupOutcome.NetworkFailure ->
                        "Lyrics Provider Connection Failed" to o.message
                    is LyricsLookupOutcome.InvalidResponse ->
                        "Invalid Provider Response" to o.message
                    is LyricsLookupOutcome.NoMatch ->
                        "No Matching Lyrics Found" to "Could not find lyrics for \"${track.title}\" by ${track.artist}."
                    else ->
                        "Lyrics Unavailable" to "No lyrics were returned for this track."
                }
                Column(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(horizontal = 18.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = headline,
                        color = TextPrimary,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                        textAlign = TextAlign.Center
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = detail,
                        color = TextSecondary,
                        fontSize = 13.sp,
                        textAlign = TextAlign.Center
                    )
                    Spacer(Modifier.height(14.dp))
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .background(Lime)
                            .clickable { retryToken++ }
                            .padding(horizontal = 18.dp, vertical = 8.dp)
                    ) {
                        Text(
                            text = "Retry Lookup",
                            color = OnAccent,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            else -> {
                Column(modifier = Modifier.fillMaxSize()) {
                    // Header row: Provider + Sync Type badge + Offset / Sources / Retry controls
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 4.dp, vertical = 2.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        val badgeLabel = when (currentParsed.syncType) {
                            LyricsSyncType.WORD_SYNCED -> "${currentParsed.provider.removePrefix("User:")} · Word Sync"
                            LyricsSyncType.LINE_SYNCED -> "${currentParsed.provider.removePrefix("User:")} · Synced"
                            LyricsSyncType.UNSYNCED -> "${currentParsed.provider.removePrefix("User:")} · Plain Lyrics"
                        }
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(50))
                                .background(
                                    if (isSynced) Lime.copy(alpha = 0.16f)
                                    else Color(0xFFFCD34D).copy(alpha = 0.16f)
                                )
                                .padding(horizontal = 10.dp, vertical = 4.dp)
                        ) {
                            Text(
                                text = badgeLabel,
                                color = if (isSynced) Lime else Color(0xFFFCD34D),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }

                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (isSynced) {
                                TextButton(
                                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                                    onClick = {
                                        val next = offsetMs - 250L
                                        offsetMs = next
                                        scope.launch { lyricsRepository.updateOffset(track.videoId, next) }
                                    }
                                ) { Text("-0.25s", color = Lime, fontSize = 11.sp) }

                                TextButton(
                                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                                    onClick = {
                                        val next = offsetMs + 250L
                                        offsetMs = next
                                        scope.launch { lyricsRepository.updateOffset(track.videoId, next) }
                                    }
                                ) { Text("+0.25s", color = Lime, fontSize = 11.sp) }
                            }

                            TextButton(
                                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                                onClick = {
                                    showSourcePicker = !showSourcePicker
                                    if (showSourcePicker && alternatives.size <= 1) {
                                        retryToken++
                                    }
                                }
                            ) {
                                Text(
                                    text = if (isSynced) "Sources" else "Find Synced",
                                    color = TextPrimary,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }
                    }

                    if (!isSynced) {
                        Text(
                            text = "Only unsynchronized lyrics are available from ${currentParsed.provider.removePrefix("User:")}. Tap \"Find Synced\" to query all providers.",
                            color = Color(0xFFFCD34D),
                            fontSize = 11.sp,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }

                    if (showSourcePicker && alternatives.isNotEmpty()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState())
                                .padding(horizontal = 4.dp, vertical = 6.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            alternatives.forEach { alt ->
                                val isCurrent = alt.provider == currentParsed.provider && alt.syncType == currentParsed.syncType
                                val label = when (alt.syncType) {
                                    LyricsSyncType.WORD_SYNCED -> "${alt.provider} (Word)"
                                    LyricsSyncType.LINE_SYNCED -> "${alt.provider} (Synced)"
                                    LyricsSyncType.UNSYNCED -> "${alt.provider} (Plain)"
                                }
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(50))
                                        .background(if (isCurrent) Lime else InkElevated)
                                        .clickable {
                                            scope.launch {
                                                val saved = lyricsRepository.selectAlternative(track.videoId, alt)
                                                activeLyrics = saved
                                            }
                                        }
                                        .padding(horizontal = 12.dp, vertical = 5.dp)
                                ) {
                                    Text(
                                        text = label,
                                        color = if (isCurrent) OnAccent else TextPrimary,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                }
                            }
                        }
                    }

                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(vertical = 20.dp, horizontal = 8.dp),
                        verticalArrangement = Arrangement.spacedBy((8 * config.lineSpacingMultiplier).dp)
                    ) {
                        itemsIndexed(lines, key = { idx, line -> "$idx:${line.timeMs}" }) { index, line ->
                            val isActive = isSynced && index == activeIndex
                            val activeWordIdx = if (isActive && config.wordLevelHighlight && line.words.isNotEmpty()) {
                                currentParsed.activeWordIndex(index, position.positionMs)
                            } else -1

                            val romanized = remember(line.text, config.showRomanization) {
                                if (config.showRomanization && OfflineRomanizer.hasNonLatinScript(line.text)) {
                                    OfflineRomanizer.romanize(line.text)
                                } else null
                            }

                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(10.dp))
                                    .clickable(enabled = isSynced && line.timeMs >= 0L) {
                                        if (line.timeMs >= 0L) onSeekTo(line.timeMs)
                                    }
                                    .padding(vertical = 4.dp, horizontal = 6.dp)
                            ) {
                                val lineFontSize = (if (isActive || !isSynced) config.fontSizeSp else (config.fontSizeSp - 2f).coerceAtLeast(13f)).sp
                                val lineHeightSp = (config.fontSizeSp * config.lineSpacingMultiplier).sp

                                if (isActive && config.wordLevelHighlight && line.words.isNotEmpty()) {
                                    val annotated = buildAnnotatedString {
                                        line.words.forEachIndexed { wIdx, word ->
                                            val sung = wIdx <= activeWordIdx
                                            withStyle(
                                                SpanStyle(
                                                    color = if (sung) activeColor else TextPrimary.copy(alpha = 0.55f),
                                                    fontWeight = if (wIdx == activeWordIdx) FontWeight.ExtraBold else FontWeight.Bold
                                                )
                                            ) {
                                                append(word.text)
                                                if (wIdx < line.words.lastIndex) append(" ")
                                            }
                                        }
                                    }
                                    Text(
                                        text = annotated,
                                        fontSize = lineFontSize,
                                        lineHeight = lineHeightSp,
                                        textAlign = textAlign,
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                } else {
                                    Text(
                                        text = line.text,
                                        color = when {
                                            !isSynced -> TextPrimary.copy(alpha = 0.90f)
                                            isActive -> activeColor
                                            else -> TextSecondary.copy(alpha = 0.55f)
                                        },
                                        fontSize = lineFontSize,
                                        lineHeight = lineHeightSp,
                                        fontWeight = if (isActive) FontWeight.Bold else FontWeight.Medium,
                                        textAlign = textAlign,
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                }

                                if (!romanized.isNullOrBlank()) {
                                    Spacer(Modifier.height(2.dp))
                                    Text(
                                        text = romanized,
                                        color = if (isActive) activeColor.copy(alpha = 0.82f) else TextSecondary.copy(alpha = 0.42f),
                                        fontSize = (config.fontSizeSp * 0.62f).coerceAtLeast(11f).sp,
                                        textAlign = textAlign,
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
