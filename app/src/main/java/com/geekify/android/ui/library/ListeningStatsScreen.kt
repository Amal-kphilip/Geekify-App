package com.geekify.android.ui.library
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import com.geekify.android.ui.theme.InkElevated


import androidx.compose.foundation.background
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.hilt.navigation.compose.hiltViewModel
import com.geekify.android.data.local.ListeningStatsRepository
import com.geekify.android.domain.listening.ListeningStatsCalculator
import com.geekify.android.domain.listening.ListeningStatsSnapshot
import com.geekify.android.domain.listening.TrackListeningTotal
import com.geekify.android.domain.listening.ArtistListeningTotal
import com.geekify.android.domain.listening.LanguageListeningTotal
import com.geekify.android.domain.listening.DiscoveredArtist
import com.geekify.android.domain.listening.DailyListeningTotal
import com.geekify.android.ui.components.AuroraBackground
import com.geekify.android.ui.components.CircleIconButton
import com.geekify.android.ui.components.LocalBottomInset
import com.geekify.android.ui.theme.InkPanel
import com.geekify.android.ui.theme.Lime
import com.geekify.android.ui.theme.TextMuted
import com.geekify.android.ui.theme.TextPrimary
import com.geekify.android.ui.theme.TextSecondary
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@HiltViewModel
class ListeningStatsViewModel @Inject constructor(
    repository: ListeningStatsRepository
) : ViewModel() {
    val stats: StateFlow<ListeningStatsSnapshot> = repository.observeSessions()
        .map { ListeningStatsCalculator.calculate(it) }
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000L),
            ListeningStatsCalculator.calculate(emptyList())
        )
}

@Composable
fun ListeningStatsScreen(
    onBack: () -> Unit,
    viewModel: ListeningStatsViewModel = hiltViewModel()
) {
    val stats by viewModel.stats.collectAsState()
    val scrollState = rememberScrollState()
    val bottomInset = LocalBottomInset.current

    AuroraBackground {
        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .verticalScroll(scrollState)
                .padding(horizontal = 20.dp)
        ) {
            Row(
                Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 18.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                CircleIconButton(
                    icon = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    onClick = onBack,
                    size = 50.dp
                )
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Listening stats", color = TextPrimary, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                    Text("Your listening, over time", color = TextSecondary, fontSize = 12.sp)
                }
                Spacer(Modifier.size(50.dp))
            }

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                MetricCard("LAST 7 DAYS", ListeningStatsCalculator.formatDuration(stats.last7DaysMs), Modifier.weight(1f))
                MetricCard("LAST 30 DAYS", ListeningStatsCalculator.formatDuration(stats.last30DaysMs), Modifier.weight(1f))
            }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                MetricCard("CURRENT STREAK", "${stats.currentStreakDays} days", Modifier.weight(1f))
                MetricCard("BEST STREAK", "${stats.longestStreakDays} days", Modifier.weight(1f))
            }

            if (!stats.hasData) {
                Surface(
                    modifier = Modifier.fillMaxWidth().padding(top = 18.dp),
                    color = InkPanel,
                    shape = RoundedCornerShape(24.dp)
                ) {
                    Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Default.GraphicEq, contentDescription = null, tint = Lime, modifier = Modifier.size(34.dp))
                        Spacer(Modifier.height(10.dp))
                        Text("Your listening story starts here", color = TextPrimary, fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "Geekify counts time while music is actually playing. Listen for at least 10 seconds to start building your stats.",
                            color = TextSecondary,
                            fontSize = 13.sp,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            } else {
                Spacer(Modifier.height(24.dp))
                WeeklyListeningChart(stats.dailyListening)
                Spacer(Modifier.height(24.dp))
                SectionHeading("Top tracks", "Last 30 days · ranked by listening time")
                if (stats.topTracks.isEmpty()) EmptyLine("No meaningful listens in this period yet.")
                stats.topTracks.forEachIndexed { index, track -> TrackTotalRow(index + 1, track) }

                Spacer(Modifier.height(22.dp))
                SectionHeading("Top artists", "Last 30 days · ranked by listening time")
                if (stats.topArtists.isEmpty()) EmptyLine("Artist stats will appear as you listen.")
                stats.topArtists.forEachIndexed { index, artist -> ArtistTotalRow(index + 1, artist) }

                Spacer(Modifier.height(22.dp))
                SectionHeading("Most-played languages", "Estimated from title and artist script")
                if (stats.languages.isEmpty()) EmptyLine("Language estimates will appear as metadata becomes available.")
                val maxLanguageMs = stats.languages.maxOfOrNull { it.listenedMs } ?: 0L
                stats.languages.forEach { LanguageTotalBar(it, maxLanguageMs) }
                Text(
                    "Language detection is approximate. Latin-script and transliterated songs may be listed as unclassified rather than incorrectly assumed to be English.",
                    color = TextMuted,
                    fontSize = 11.sp,
                    lineHeight = 15.sp,
                    modifier = Modifier.padding(top = 4.dp)
                )

                Spacer(Modifier.height(22.dp))
                SectionHeading("Recently discovered artists", "First tracked meaningful listen in the last 30 days")
                if (stats.recentlyDiscoveredArtists.isEmpty()) EmptyLine("New-to-you artists will appear here after you spend time with their music.")
                stats.recentlyDiscoveredArtists.forEach { DiscoveredArtistRow(it) }

                Spacer(Modifier.height(18.dp))
                Text(
                    "A listening day counts toward a streak after 10 minutes of listening. Sessions shorter than 10 seconds are excluded from listening-time totals.",
                    color = TextMuted,
                    fontSize = 11.sp,
                    lineHeight = 15.sp
                )
            }
            Spacer(Modifier.height(bottomInset + 28.dp))
        }
    }
}

@Composable
private fun MetricCard(label: String, value: String, modifier: Modifier = Modifier) {
    Surface(modifier = modifier, color = InkPanel, shape = RoundedCornerShape(22.dp)) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 18.dp)) {
            Text(label, color = TextMuted, fontSize = 10.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(8.dp))
            Text(value, color = TextPrimary, fontSize = 23.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun SectionHeading(title: String, subtitle: String) {
    Text(title, color = TextPrimary, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
    Spacer(Modifier.height(3.dp))
    Text(subtitle, color = TextSecondary, fontSize = 12.sp)
    Spacer(Modifier.height(10.dp))
}

@Composable
private fun TrackTotalRow(rank: Int, track: TrackListeningTotal) {
    RankingRow(rank, track.title, track.artist, ListeningStatsCalculator.formatDuration(track.listenedMs), "${track.sessions} sessions")
}

@Composable
private fun ArtistTotalRow(rank: Int, artist: ArtistListeningTotal) {
    RankingRow(rank, artist.name, "${artist.sessions} meaningful sessions", ListeningStatsCalculator.formatDuration(artist.listenedMs), null)
}

@Composable
private fun LanguageTotalBar(language: LanguageListeningTotal, maximumMs: Long) {
    Column(Modifier.fillMaxWidth().padding(vertical = 7.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                language.name,
                color = TextPrimary,
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Text(ListeningStatsCalculator.formatDuration(language.listenedMs), color = TextSecondary, fontSize = 12.sp)
        }
        Spacer(Modifier.height(7.dp))
        Box(
            Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(50)).background(InkElevated)
        ) {
            val fraction = if (maximumMs > 0L) (language.listenedMs.toFloat() / maximumMs).coerceIn(0f, 1f) else 0f
            Box(
                Modifier.fillMaxWidth(fraction).fillMaxHeight().clip(RoundedCornerShape(50)).background(Lime)
            )
        }
    }
}

@Composable
private fun WeeklyListeningChart(days: List<DailyListeningTotal>) {
    val maximumMs = days.maxOfOrNull { it.listenedMs }?.coerceAtLeast(1L) ?: 1L
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = InkPanel,
        shape = RoundedCornerShape(24.dp)
    ) {
        Column(Modifier.padding(horizontal = 18.dp, vertical = 18.dp)) {
            Text("Listening over time", color = TextPrimary, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(3.dp))
            Text("Daily listening · last 7 days", color = TextSecondary, fontSize = 12.sp)
            Spacer(Modifier.height(18.dp))
            Row(
                modifier = Modifier.fillMaxWidth().height(126.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.Bottom
            ) {
                days.forEach { day ->
                    val ratio = (day.listenedMs.toFloat() / maximumMs).coerceIn(0f, 1f)
                    val barHeight = if (day.listenedMs > 0L) (72f * ratio).coerceAtLeast(4f) else 2f
                    Column(
                        modifier = Modifier.weight(1f),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Bottom
                    ) {
                        Text(
                            shortDuration(day.listenedMs),
                            color = TextSecondary,
                            fontSize = 9.sp,
                            maxLines = 1
                        )
                        Spacer(Modifier.height(7.dp))
                        Box(
                            modifier = Modifier.fillMaxWidth().height(74.dp),
                            contentAlignment = Alignment.BottomCenter
                        ) {
                            Box(
                                Modifier.width(18.dp).height(barHeight.dp).clip(CircleShape)
                                    .background(if (day.listenedMs > 0L) Lime else InkElevated)
                            )
                        }
                        Spacer(Modifier.height(7.dp))
                        Text(day.label, color = TextMuted, fontSize = 10.sp, maxLines = 1)
                    }
                }
            }
        }
    }
}

private fun shortDuration(durationMs: Long): String {
    val safeDuration = durationMs.coerceAtLeast(0L)
    val minutes = safeDuration / 60_000L
    return when {
        safeDuration == 0L -> "0m"
        minutes == 0L -> "<1m"
        minutes >= 60L -> "${minutes / 60}h"
        else -> "${minutes}m"
    }
}

@Composable
private fun DiscoveredArtistRow(artist: DiscoveredArtist) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(artist.name, color = TextPrimary, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("First heard ${ListeningStatsCalculator.formatDate(artist.firstHeardAt)}", color = TextSecondary, fontSize = 12.sp)
        }
    }
}

@Composable
private fun RankingRow(rank: Int, title: String, subtitle: String, primaryValue: String, secondaryValue: String?) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(rank.toString(), color = Lime, fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(end = 14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = TextPrimary, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(subtitle, color = TextSecondary, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(primaryValue, color = TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            if (secondaryValue != null) Text(secondaryValue, color = TextMuted, fontSize = 10.sp)
        }
    }
}

@Composable
private fun EmptyLine(text: String) {
    Text(text, color = TextMuted, fontSize = 13.sp, modifier = Modifier.padding(vertical = 8.dp))
}

