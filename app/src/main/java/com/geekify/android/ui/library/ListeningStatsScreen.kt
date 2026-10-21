package com.geekify.android.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.geekify.android.data.local.ListeningStatsRepository
import com.geekify.android.domain.listening.ArtistListeningTotal
import com.geekify.android.domain.listening.DailyListeningTotal
import com.geekify.android.domain.listening.DiscoveredArtist
import com.geekify.android.domain.listening.ListeningStatsCalculator
import com.geekify.android.domain.listening.ListeningStatsSnapshot
import com.geekify.android.domain.listening.TrackListeningTotal
import com.geekify.android.ui.components.*
import com.geekify.android.ui.theme.GeekifyIcons
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
        .map { rows -> ListeningStatsCalculator.calculate(rows) }
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

    IosSettingsPageScaffold(
        title = "Listening Stats",
        scrollState = scrollState,
        onBack = onBack,
        bottomPadding = bottomInset + 36.dp
    ) {
        // 2x2 Summary Bento Grid
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            StatsSummaryTile(
                icon = GeekifyIcons.Insights,
                tileColor = IosPalette.systemCyan,
                label = "Last 7 Days",
                value = ListeningStatsCalculator.formatDuration(stats.last7DaysMs),
                caption = "Weekly time",
                modifier = Modifier.weight(1f)
            )
            StatsSummaryTile(
                icon = GeekifyIcons.CalendarToday,
                tileColor = IosPalette.systemPurple,
                label = "Last 30 Days",
                value = ListeningStatsCalculator.formatDuration(stats.last30DaysMs),
                caption = "Monthly time",
                modifier = Modifier.weight(1f)
            )
        }
        Spacer(Modifier.height(12.dp))
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            StatsSummaryTile(
                icon = GeekifyIcons.Whatshot,
                tileColor = IosPalette.systemOrange,
                label = "Current Streak",
                value = "${stats.currentStreakDays} ${if (stats.currentStreakDays == 1) "day" else "days"}",
                caption = "10m+ daily goal",
                modifier = Modifier.weight(1f)
            )
            StatsSummaryTile(
                icon = GeekifyIcons.Verified,
                tileColor = IosPalette.systemGreen,
                label = "Best Streak",
                value = "${stats.longestStreakDays} ${if (stats.longestStreakDays == 1) "day" else "days"}",
                caption = "All-time record",
                modifier = Modifier.weight(1f)
            )
        }

        if (!stats.hasData) {
            Spacer(Modifier.height(24.dp))
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = IosPalette.secondarySystemGroupedBackground,
                shape = IosCardShape
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Box(
                        modifier = Modifier
                            .size(56.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .background(IosPalette.systemPurple.copy(alpha = 0.16f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = GeekifyIcons.GraphicEq,
                            contentDescription = null,
                            tint = IosPalette.systemPurple,
                            modifier = Modifier.size(28.dp)
                        )
                    }
                    Spacer(Modifier.height(14.dp))
                    Text(
                        text = "Your listening story starts here",
                        color = IosPalette.label,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 18.sp
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "Geekify tracks time while music is actively playing. Listen for at least 10 seconds to start building your insights.",
                        color = IosPalette.secondaryLabel,
                        fontSize = 13.5.sp,
                        lineHeight = 19.sp,
                        textAlign = TextAlign.Center
                    )
                }
            }
        } else {
            Spacer(Modifier.height(24.dp))
            IosSettingsSection(title = "Daily activity · Last 7 days") {
                WeeklyListeningChart(stats.dailyListening)
            }

            Spacer(Modifier.height(24.dp))
            IosSettingsSection(title = "Top tracks · Last 30 days") {
                if (stats.topTracks.isEmpty()) {
                    EmptySectionRow("No meaningful track listens recorded in this period yet.")
                } else {
                    stats.topTracks.forEachIndexed { index, track ->
                        TrackTotalRow(rank = index + 1, track = track)
                        if (index < stats.topTracks.lastIndex) {
                            IosSettingsDivider(insetStart = 56.dp)
                        }
                    }
                }
            }

            Spacer(Modifier.height(24.dp))
            IosSettingsSection(title = "Top artists · Last 30 days") {
                if (stats.topArtists.isEmpty()) {
                    EmptySectionRow("Artist rankings will appear as you listen.")
                } else {
                    stats.topArtists.forEachIndexed { index, artist ->
                        ArtistTotalRow(rank = index + 1, artist = artist)
                        if (index < stats.topArtists.lastIndex) {
                            IosSettingsDivider(insetStart = 56.dp)
                        }
                    }
                }
            }

            Spacer(Modifier.height(24.dp))
            IosSettingsSection(
                title = "Recently discovered artists",
                footer = "A listening day counts toward your streak after 10 minutes of playback. Sessions shorter than 10 seconds are excluded."
            ) {
                if (stats.recentlyDiscoveredArtists.isEmpty()) {
                    EmptySectionRow("New-to-you artists from the last 30 days will appear here.")
                } else {
                    stats.recentlyDiscoveredArtists.forEachIndexed { index, artist ->
                        DiscoveredArtistRow(artist)
                        if (index < stats.recentlyDiscoveredArtists.lastIndex) {
                            IosSettingsDivider(insetStart = 58.dp)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StatsSummaryTile(
    icon: ImageVector,
    tileColor: Color,
    label: String,
    value: String,
    caption: String,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        color = IosPalette.secondarySystemGroupedBackground,
        shape = IosCardShape
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                IosIconBadge(icon = icon, tint = tileColor)
                Text(
                    text = caption,
                    color = IosPalette.tertiaryLabel,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium
                )
            }
            Spacer(Modifier.height(12.dp))
            Text(
                text = value,
                color = IosPalette.label,
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = (-0.4).sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = label,
                color = IosPalette.secondaryLabel,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun WeeklyListeningChart(days: List<DailyListeningTotal>) {
    val maximumMs = days.maxOfOrNull { it.listenedMs }?.coerceAtLeast(1L) ?: 1L
    val activeDaysCount = days.count { it.listenedMs > 0L }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = "Daily breakdown",
                color = IosPalette.label,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = "$activeDaysCount of 7 days active",
                color = IosPalette.secondaryLabel,
                fontSize = 12.5.sp
            )
        }

        Spacer(Modifier.height(16.dp))

        // Duration labels above bars
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            days.forEach { day ->
                Text(
                    text = shortDuration(day.listenedMs),
                    modifier = Modifier.weight(1f),
                    color = if (day.listenedMs > 0L) IosPalette.label else IosPalette.tertiaryLabel,
                    fontSize = 10.sp,
                    fontWeight = if (day.listenedMs > 0L) FontWeight.SemiBold else FontWeight.Normal,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Clip,
                    textAlign = TextAlign.Center
                )
            }
        }

        Spacer(Modifier.height(8.dp))

        // Capsule bars with full-height background slots
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(104.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.Bottom
        ) {
            days.forEach { day ->
                val ratio = (day.listenedMs.toFloat() / maximumMs).coerceIn(0f, 1f)
                val fillFraction = if (day.listenedMs > 0L) ratio.coerceAtLeast(0.08f) else 0f
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight(),
                    contentAlignment = Alignment.BottomCenter
                ) {
                    Box(
                        modifier = Modifier
                            .width(22.dp)
                            .fillMaxHeight()
                            .clip(CircleShape)
                            .background(IosPalette.tertiarySystemGroupedBackground),
                        contentAlignment = Alignment.BottomCenter
                    ) {
                        if (fillFraction > 0f) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .fillMaxHeight(fillFraction)
                                    .clip(CircleShape)
                                    .background(
                                        Brush.verticalGradient(
                                            colors = listOf(
                                                IosPalette.systemCyan,
                                                IosPalette.systemBlue
                                            )
                                        )
                                    )
                            )
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(8.dp))

        // Weekday labels
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            days.forEach { day ->
                Text(
                    text = day.label,
                    modifier = Modifier.weight(1f),
                    color = IosPalette.secondaryLabel,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Clip,
                    textAlign = TextAlign.Center
                )
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
private fun TrackTotalRow(rank: Int, track: TrackListeningTotal) {
    RankingRow(
        rank = rank,
        title = track.title,
        subtitle = track.artist,
        primaryValue = ListeningStatsCalculator.formatDuration(track.listenedMs),
        secondaryValue = "${track.sessions} ${if (track.sessions == 1) "play" else "plays"}"
    )
}

@Composable
private fun ArtistTotalRow(rank: Int, artist: ArtistListeningTotal) {
    RankingRow(
        rank = rank,
        title = artist.name,
        subtitle = "${artist.sessions} meaningful ${if (artist.sessions == 1) "session" else "sessions"}",
        primaryValue = ListeningStatsCalculator.formatDuration(artist.listenedMs),
        secondaryValue = null
    )
}

@Composable
private fun DiscoveredArtistRow(artist: DiscoveredArtist) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IosIconBadge(
            icon = GeekifyIcons.AutoAwesome,
            tint = IosPalette.systemPink
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = artist.name,
                color = IosPalette.label,
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = "First heard ${ListeningStatsCalculator.formatDate(artist.firstHeardAt)}",
                color = IosPalette.secondaryLabel,
                fontSize = 12.5.sp
            )
        }
    }
}

@Composable
private fun RankingRow(
    rank: Int,
    title: String,
    subtitle: String,
    primaryValue: String,
    secondaryValue: String?
) {
    val badgeBg = when (rank) {
        1 -> IosPalette.systemYellow.copy(alpha = 0.20f)
        2 -> IosPalette.systemCyan.copy(alpha = 0.18f)
        3 -> IosPalette.systemOrange.copy(alpha = 0.18f)
        else -> IosPalette.tertiarySystemGroupedBackground
    }
    val badgeFg = when (rank) {
        1 -> IosPalette.systemYellow
        2 -> IosPalette.systemCyan
        3 -> IosPalette.systemOrange
        else -> IosPalette.secondaryLabel
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(28.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(badgeBg),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = rank.toString(),
                color = badgeFg,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                color = IosPalette.label,
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = subtitle,
                color = IosPalette.secondaryLabel,
                fontSize = 12.5.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 1.dp)
            )
        }
        Spacer(Modifier.width(10.dp))
        Column(horizontalAlignment = Alignment.End) {
            Text(
                text = primaryValue,
                color = IosPalette.label,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold
            )
            if (secondaryValue != null) {
                Text(
                    text = secondaryValue,
                    color = IosPalette.tertiaryLabel,
                    fontSize = 11.sp
                )
            }
        }
    }
}

@Composable
private fun EmptySectionRow(text: String) {
    Text(
        text = text,
        color = IosPalette.secondaryLabel,
        fontSize = 13.5.sp,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp)
    )
}
