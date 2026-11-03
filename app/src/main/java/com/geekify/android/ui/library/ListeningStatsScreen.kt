package com.geekify.android.ui.library

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.geekify.android.data.local.ListeningStatsRepository
import com.geekify.android.domain.listening.DailyListeningTotal
import com.geekify.android.domain.listening.DiscoveredArtist
import com.geekify.android.domain.listening.ListeningStatsCalculator
import com.geekify.android.domain.listening.ListeningStatsSnapshot
import com.geekify.android.ui.components.LocalBottomInset
import com.geekify.android.ui.components.IosSettingsPageScaffold
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

private val statsChartColors = listOf(
    Color(0xFF3985FF),
    Color(0xFF36C5D8),
    Color(0xFF53D69A),
    Color(0xFF9B83F6),
    Color(0xFFFFB454),
    Color(0xFFFF6F91)
)

@Composable
fun ListeningStatsScreen(
    onBack: () -> Unit,
    viewModel: ListeningStatsViewModel = hiltViewModel()
) {
    val stats by viewModel.stats.collectAsState()
    val scrollState = androidx.compose.foundation.rememberScrollState()
    val bottomInset = LocalBottomInset.current
    var selectedPeriod by rememberSaveable { mutableStateOf(StatsPeriod.WEEK) }
    val primaryDuration = if (selectedPeriod == StatsPeriod.WEEK) stats.last7DaysMs else stats.last30DaysMs
    val periodTitle = if (selectedPeriod == StatsPeriod.WEEK) "Last 7 days" else "Last 30 days"
    val activeDays = stats.dailyListening.count { it.listenedMs > 0L }

    IosSettingsPageScaffold(
        title = "Listening Stats",
        scrollState = scrollState,
        onBack = onBack,
        bottomPadding = bottomInset + 36.dp
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
            ListeningOverviewCard(
                duration = primaryDuration,
                periodTitle = periodTitle,
                activeDays = activeDays,
                selectedPeriod = selectedPeriod,
                onPeriodSelected = { selectedPeriod = it }
            )

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                StreakMetricCard(
                    title = "Current streak",
                    value = "${stats.currentStreakDays} ${if (stats.currentStreakDays == 1) "day" else "days"}",
                    supporting = "10 min daily goal",
                    accent = Color(0xFF53D69A),
                    modifier = Modifier.weight(1f)
                )
                StreakMetricCard(
                    title = "Best streak",
                    value = "${stats.longestStreakDays} ${if (stats.longestStreakDays == 1) "day" else "days"}",
                    supporting = "Personal record",
                    accent = Color(0xFF9B83F6),
                    modifier = Modifier.weight(1f)
                )
            }

            StatsSection(
                title = "Listening activity",
                subtitle = "Daily playback · Last 7 days"
            ) {
                WeeklyActivityChart(stats.dailyListening)
            }

            if (stats.topTracks.isNotEmpty()) {
                StatsSection(
                    title = "Top tracks",
                    subtitle = "Most listened · Last 30 days"
                ) {
                    Column {
                        stats.topTracks.forEachIndexed { index, track ->
                            StatsRankingRow(
                                rank = index + 1,
                                title = track.title,
                                subtitle = track.artist,
                                duration = ListeningStatsCalculator.formatDuration(track.listenedMs),
                                detail = "${track.sessions} ${if (track.sessions == 1) "play" else "plays"}"
                            )
                            if (index < stats.topTracks.lastIndex) StatsHairline()
                        }
                    }
                }
            }

            if (stats.topArtists.isNotEmpty()) {
                StatsSection(
                    title = "Top artists",
                    subtitle = "By listening time · Last 30 days"
                ) {
                    Column {
                        stats.topArtists.forEachIndexed { index, artist ->
                            StatsRankingRow(
                                rank = index + 1,
                                title = artist.name,
                                subtitle = "${artist.sessions} ${if (artist.sessions == 1) "session" else "sessions"}",
                                duration = ListeningStatsCalculator.formatDuration(artist.listenedMs)
                            )
                            if (index < stats.topArtists.lastIndex) StatsHairline()
                        }
                    }
                }
            }

            if (stats.recentlyDiscoveredArtists.isNotEmpty()) {
                StatsSection(
                    title = "Recently discovered",
                    subtitle = "Artists you started listening to in the last 30 days"
                ) {
                    Column {
                        stats.recentlyDiscoveredArtists.forEachIndexed { index, artist ->
                            DiscoveredArtistItem(artist)
                            if (index < stats.recentlyDiscoveredArtists.lastIndex) StatsHairline()
                        }
                    }
                }
            }

            if (!stats.hasData) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = Color(0xFF17191D),
                    shape = RoundedCornerShape(24.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 22.dp, vertical = 26.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text("Your listening story starts here", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Listen to music for at least 10 seconds to start building your stats. Daily streaks count after 10 minutes of playback.",
                            color = Color(0xFF9B9DA5),
                            fontSize = 13.sp,
                            lineHeight = 19.sp
                        )
                    }
                }
            }
        }
    }
}

private enum class StatsPeriod { WEEK, MONTH }

@Composable
private fun ListeningOverviewCard(
    duration: Long,
    periodTitle: String,
    activeDays: Int,
    selectedPeriod: StatsPeriod,
    onPeriodSelected: (StatsPeriod) -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = Color.Transparent,
        shape = RoundedCornerShape(26.dp)
    ) {
        Box(
            modifier = Modifier
                .background(
                    Brush.linearGradient(
                        listOf(Color(0xFF202B3C), Color(0xFF191B22), Color(0xFF17191D))
                    ),
                    RoundedCornerShape(26.dp)
                )
                .padding(20.dp)
        ) {
            Column {
                Text(
                    "LISTENING OVERVIEW",
                    color = Color(0xFF69C9E7),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.2.sp
                )
                Spacer(Modifier.height(14.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            ListeningStatsCalculator.formatDuration(duration),
                            color = Color.White,
                            fontSize = 34.sp,
                            lineHeight = 40.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = (-0.8).sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(periodTitle, color = Color(0xFFB1B4BE), fontSize = 13.sp)
                        Spacer(Modifier.height(16.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            PeriodPill("7 days", selectedPeriod == StatsPeriod.WEEK) { onPeriodSelected(StatsPeriod.WEEK) }
                            PeriodPill("30 days", selectedPeriod == StatsPeriod.MONTH) { onPeriodSelected(StatsPeriod.MONTH) }
                        }
                    }
                    Spacer(Modifier.width(8.dp))
                    ActiveDaysRing(activeDays = activeDays)
                }
            }
        }
    }
}

@Composable
private fun PeriodPill(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(CircleShape)
            .background(if (selected) Color(0xFF2E78E8) else Color.White.copy(alpha = 0.08f))
            .clickable(onClick = onClick)
            .padding(horizontal = 13.dp, vertical = 8.dp)
    ) {
        Text(label, color = Color.White, fontSize = 12.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium)
    }
}

@Composable
private fun ActiveDaysRing(activeDays: Int) {
    Box(modifier = Modifier.size(112.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val ringWidth = 12.dp.toPx()
            val inset = ringWidth / 2f
            val arcSize = androidx.compose.ui.geometry.Size(size.width - ringWidth, size.height - ringWidth)
            val topLeft = androidx.compose.ui.geometry.Offset(inset, inset)
            drawArc(
                color = Color.White.copy(alpha = 0.13f),
                startAngle = -90f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = ringWidth, cap = StrokeCap.Round)
            )
            if (activeDays > 0) {
                drawArc(
                    brush = Brush.sweepGradient(listOf(Color(0xFF3985FF), Color(0xFF36C5D8), Color(0xFF53D69A), Color(0xFF3985FF))),
                    startAngle = -90f,
                    sweepAngle = 360f * activeDays.coerceIn(0, 7) / 7f,
                    useCenter = false,
                    topLeft = topLeft,
                    size = arcSize,
                    style = Stroke(width = ringWidth, cap = StrokeCap.Round)
                )
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("$activeDays/7", color = Color.White, fontSize = 21.sp, fontWeight = FontWeight.Bold)
            Text("active days", color = Color(0xFF9B9DA5), fontSize = 10.sp)
        }
    }
}

@Composable
private fun StreakMetricCard(
    title: String,
    value: String,
    supporting: String,
    accent: Color,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        color = Color(0xFF1A1B1F),
        shape = RoundedCornerShape(22.dp)
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 15.dp)) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(accent))
            Spacer(Modifier.height(12.dp))
            Text(value, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold, maxLines = 1)
            Spacer(Modifier.height(3.dp))
            Text(title, color = Color(0xFFD7D8DE), fontSize = 13.sp, fontWeight = FontWeight.Medium)
            Text(supporting, color = Color(0xFF858891), fontSize = 11.sp)
        }
    }
}

@Composable
private fun StatsSection(
    title: String,
    subtitle: String,
    content: @Composable ColumnScope.() -> Unit
) {
    Column {
        Column(Modifier.padding(start = 3.dp, bottom = 9.dp)) {
            Text(title, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Text(subtitle, color = Color(0xFF858891), fontSize = 12.sp)
        }
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = Color(0xFF191A1E),
            shape = RoundedCornerShape(22.dp)
        ) {
            Column(content = content)
        }
    }
}

@Composable
private fun WeeklyActivityChart(days: List<DailyListeningTotal>) {
    val maxMs = days.maxOfOrNull { it.listenedMs }?.coerceAtLeast(1L) ?: 1L
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 17.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
            Text("Daily listening time", color = Color(0xFFE8E9ED), fontSize = 13.sp, fontWeight = FontWeight.Medium)
            Text("${days.count { it.listenedMs > 0L }} active", color = Color(0xFF858891), fontSize = 11.sp)
        }
        Spacer(Modifier.height(18.dp))
        Row(
            modifier = Modifier.fillMaxWidth().height(142.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.Bottom
        ) {
            days.forEachIndexed { index, day ->
                val ratio = (day.listenedMs.toFloat() / maxMs.toFloat()).coerceIn(0f, 1f)
                Column(
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Bottom
                ) {
                    Text(
                        shortStatsDuration(day.listenedMs),
                        color = if (day.listenedMs > 0L) Color(0xFFDDE1EA) else Color(0xFF6E7179),
                        fontSize = 9.sp,
                        maxLines = 1,
                        softWrap = false
                    )
                    Spacer(Modifier.height(7.dp))
                    Box(
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        contentAlignment = Alignment.BottomCenter
                    ) {
                        Box(
                            modifier = Modifier
                                .widthIn(max = 25.dp)
                                .fillMaxWidth()
                                .fillMaxHeight(if (day.listenedMs > 0L) ratio.coerceAtLeast(0.06f) else 0.025f)
                                .clip(RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp, bottomStart = 3.dp, bottomEnd = 3.dp))
                                .background(
                                    if (day.listenedMs > 0L) Brush.verticalGradient(listOf(Color(0xFF55C9E7), Color(0xFF347CE8)))
                                    else Brush.verticalGradient(listOf(Color(0xFF30333A), Color(0xFF30333A)))
                                )
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(day.label, color = Color(0xFF8A8D96), fontSize = 10.sp, fontWeight = FontWeight.Medium)
                }
            }
        }
    }
}

@Composable
private fun StatsRankingRow(
    rank: Int,
    title: String,
    subtitle: String,
    duration: String,
    detail: String? = null
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier.size(34.dp).clip(RoundedCornerShape(11.dp))
                .background(statsChartColors[(rank - 1).mod(statsChartColors.size)].copy(alpha = 0.18f)),
            contentAlignment = Alignment.Center
        ) {
            Text(rank.toString(), color = statsChartColors[(rank - 1).mod(statsChartColors.size)], fontSize = 13.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, color = Color(0xFFF1F2F5), fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(subtitle, color = Color(0xFF858891), fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.width(10.dp))
        Column(horizontalAlignment = Alignment.End) {
            Text(duration, color = Color(0xFFE6E8ED), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            if (detail != null) Text(detail, color = Color(0xFF858891), fontSize = 10.sp)
        }
    }
}

@Composable
private fun DiscoveredArtistItem(artist: DiscoveredArtist) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(36.dp).clip(CircleShape).background(Color(0xFF3D78CE).copy(alpha = 0.2f)), contentAlignment = Alignment.Center) {
            Text(artist.name.firstOrNull()?.uppercase() ?: "?", color = Color(0xFF71BDEB), fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.width(12.dp))
        Column {
            Text(artist.name, color = Color(0xFFF1F2F5), fontSize = 14.sp, fontWeight = FontWeight.Medium)
            Text("First heard ${ListeningStatsCalculator.formatDate(artist.firstHeardAt)}", color = Color(0xFF858891), fontSize = 11.sp)
        }
    }
}

@Composable
private fun StatsHairline() {
    Box(Modifier.fillMaxWidth().padding(start = 62.dp, end = 16.dp).height(0.5.dp).background(Color(0xFF34363C)))
}

private fun shortStatsDuration(durationMs: Long): String {
    val minutes = durationMs.coerceAtLeast(0L) / 60_000L
    return when {
        durationMs <= 0L -> "0m"
        minutes == 0L -> "<1m"
        minutes >= 60L -> "${minutes / 60}h ${minutes % 60}m"
        else -> "${minutes}m"
    }
}
