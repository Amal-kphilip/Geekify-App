package com.geekify.android.ui.health

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.geekify.android.ui.components.*
import com.geekify.android.ui.theme.GeekifyIcons

/** Extractor health: runs two live checks (search, audio stream) and reports each one clearly. */
@Composable
fun HealthScreen(
    viewModel: HealthViewModel,
    onBack: () -> Unit
) {
    val state by viewModel.uiState.collectAsState()
    val scrollState = rememberScrollState()
    val bottomInset = LocalBottomInset.current

    LaunchedEffect(Unit) {
        if (state.innertubeSearchOk == null && !state.isRunning) {
            viewModel.runDiagnostics()
        }
    }

    val allHealthy = state.innertubeSearchOk == true && state.streamResolveOk == true
    val anyFailed = state.innertubeSearchOk == false || state.streamResolveOk == false

    val heroTint = when {
        state.isRunning -> IosPalette.systemCyan
        allHealthy -> IosPalette.systemGreen
        anyFailed -> IosPalette.systemRed
        else -> IosPalette.systemBlue
    }
    val heroHeadline = when {
        state.isRunning -> "Running Live Diagnostics…"
        allHealthy -> "All Systems Operational"
        anyFailed -> "Service Attention Needed"
        else -> "Ready to Check"
    }
    val heroSubtitle = when {
        state.isRunning -> "Verifying catalog search and audio stream extraction."
        allHealthy -> "Catalog search and audio stream extraction are responding normally."
        anyFailed -> "One or more upstream extractors reported an issue during testing."
        else -> "Run live probes against the catalog and stream resolver."
    }

    IosSettingsPageScaffold(
        title = "Extractor Health",
        scrollState = scrollState,
        onBack = onBack,
        bottomPadding = bottomInset + 36.dp
    ) {
        // Hero Status Card
        Surface(
            color = IosPalette.secondarySystemGroupedBackground,
            shape = IosCardShape,
            modifier = Modifier
                .fillMaxWidth()
                .border(0.5.dp, heroTint.copy(alpha = 0.35f), IosCardShape)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 18.dp, vertical = 18.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(52.dp)
                        .clip(RoundedCornerShape(15.dp))
                        .background(heroTint.copy(alpha = 0.16f)),
                    contentAlignment = Alignment.Center
                ) {
                    when {
                        state.isRunning -> CircularProgressIndicator(
                            color = heroTint,
                            modifier = Modifier.size(24.dp),
                            strokeWidth = 2.5.dp
                        )
                        allHealthy -> Icon(
                            imageVector = GeekifyIcons.CheckCircle,
                            contentDescription = null,
                            tint = heroTint,
                            modifier = Modifier.size(28.dp)
                        )
                        anyFailed -> Icon(
                            imageVector = GeekifyIcons.Error,
                            contentDescription = null,
                            tint = heroTint,
                            modifier = Modifier.size(28.dp)
                        )
                        else -> Icon(
                            imageVector = GeekifyIcons.MonitorHeart,
                            contentDescription = null,
                            tint = heroTint,
                            modifier = Modifier.size(28.dp)
                        )
                    }
                }
                Spacer(Modifier.width(16.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = heroHeadline,
                        color = IosPalette.label,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = (-0.3).sp
                    )
                    Spacer(Modifier.height(3.dp))
                    Text(
                        text = heroSubtitle,
                        color = IosPalette.secondaryLabel,
                        fontSize = 13.sp,
                        lineHeight = 18.sp
                    )
                }
            }
        }

        Spacer(Modifier.height(24.dp))

        // Live Service Checks
        IosSettingsSection(
            title = "Live service probes",
            footer = "Probes execute a real catalog query and resolve a fresh playable stream URL bypassing local cache."
        ) {
            DiagnosticProbeRow(
                icon = GeekifyIcons.Search,
                tileColor = IosPalette.systemBlue,
                title = "Catalog & Search Engine",
                subtitle = "Queries songs, albums, artists, and playlists",
                ok = state.innertubeSearchOk,
                running = state.isRunning && state.innertubeSearchOk == null
            )
            IosSettingsDivider()
            DiagnosticProbeRow(
                icon = GeekifyIcons.Audiotrack,
                tileColor = IosPalette.systemGreen,
                title = "Audio Stream Resolver",
                subtitle = "Extracts a signed, playable audio stream URL",
                ok = state.streamResolveOk,
                running = state.isRunning && state.streamResolveOk == null
            )
        }

        // Stream Telemetry Section
        if (state.mimeType != null || state.resolvedUrl != null) {
            Spacer(Modifier.height(24.dp))
            IosSettingsSection(title = "Stream telemetry") {
                state.mimeType?.let { mime ->
                    IosSettingsRow(
                        icon = GeekifyIcons.GraphicEq,
                        tileColor = IosPalette.systemPurple,
                        title = "Container Format",
                        value = mime,
                        showChevron = false,
                        onClick = null
                    )
                }
                if (state.mimeType != null && state.resolvedUrl != null) {
                    IosSettingsDivider()
                }
                state.resolvedUrl?.let { url ->
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IosIconBadge(
                                icon = GeekifyIcons.Public,
                                tint = IosPalette.systemTeal
                            )
                            Spacer(Modifier.width(12.dp))
                            Text(
                                text = "Resolved Stream Endpoint",
                                color = IosPalette.label,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Normal
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(IosPalette.tertiarySystemGroupedBackground)
                                .padding(horizontal = 12.dp, vertical = 10.dp)
                        ) {
                            Text(
                                text = url,
                                color = IosPalette.secondaryLabel,
                                fontSize = 11.5.sp,
                                fontFamily = FontFamily.Monospace,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        }

        // Error Details
        state.error?.let { message ->
            Spacer(Modifier.height(24.dp))
            IosSettingsSection(title = "Diagnostic report") {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    IosIconBadge(
                        icon = GeekifyIcons.Error,
                        tint = IosPalette.systemRed
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Error Details",
                            color = IosPalette.systemRed,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = message,
                            color = IosPalette.secondaryLabel,
                            fontSize = 13.sp,
                            lineHeight = 18.sp
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(28.dp))

        val canRun = !state.isRunning
        Surface(
            color = if (canRun) IosPalette.systemBlue else IosPalette.secondarySystemGroupedBackground,
            shape = CircleShape,
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp)
                .then(
                    if (canRun) Modifier.bouncyClickable(pressedScale = 0.97f, onClick = viewModel::runDiagnostics)
                    else Modifier
                )
        ) {
            Row(
                modifier = Modifier.fillMaxSize(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (state.isRunning) {
                    CircularProgressIndicator(
                        color = IosPalette.systemBlue,
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.2.dp
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = "Running Diagnostics…",
                        color = IosPalette.secondaryLabel,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                } else {
                    Icon(
                        imageVector = GeekifyIcons.Refresh,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "Run Diagnostics Again",
                        color = Color.White,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
    }
}

@Composable
private fun DiagnosticProbeRow(
    icon: ImageVector,
    tileColor: Color,
    title: String,
    subtitle: String,
    ok: Boolean?,
    running: Boolean
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IosIconBadge(icon = icon, tint = tileColor)
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                color = IosPalette.label,
                fontSize = 17.sp,
                fontWeight = FontWeight.Normal,
                letterSpacing = (-0.35).sp
            )
            Text(
                text = subtitle,
                color = IosPalette.secondaryLabel,
                fontSize = 13.sp,
                modifier = Modifier.padding(top = 1.dp)
            )
        }
        Spacer(Modifier.width(10.dp))

        when {
            running -> CircularProgressIndicator(
                color = IosPalette.systemBlue,
                modifier = Modifier.size(20.dp),
                strokeWidth = 2.dp
            )
            ok == true -> StatusPill(
                text = "Healthy",
                color = IosPalette.systemGreen
            )
            ok == false -> StatusPill(
                text = "Failed",
                color = IosPalette.systemRed
            )
            else -> StatusPill(
                text = "Pending",
                color = IosPalette.systemGray
            )
        }
    }
}

@Composable
private fun StatusPill(
    text: String,
    color: Color
) {
    Box(
        modifier = Modifier
            .clip(CircleShape)
            .background(color.copy(alpha = 0.16f))
            .border(0.5.dp, color.copy(alpha = 0.45f), CircleShape)
            .padding(horizontal = 10.dp, vertical = 4.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            color = color,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold
        )
    }
}
