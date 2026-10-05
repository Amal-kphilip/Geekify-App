package com.geekify.android.ui.health

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.geekify.android.ui.components.CircleIconButton
import com.geekify.android.ui.components.LocalBottomInset
import com.geekify.android.ui.components.bouncyClickable
import com.geekify.android.ui.theme.*

/** Extractor health: runs two live checks (search, audio stream) and reports each one clearly. */
@Composable
fun HealthScreen(
    viewModel: HealthViewModel,
    onBack: () -> Unit
) {
    val state by viewModel.uiState.collectAsState()

    LaunchedEffect(Unit) {
        if (state.innertubeSearchOk == null && !state.isRunning) {
            viewModel.runDiagnostics()
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(InkBackground)) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(start = 20.dp, end = 20.dp, top = 14.dp, bottom = 24.dp + LocalBottomInset.current)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircleIconButton(
                    icon = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    onClick = onBack,
                    size = 50.dp
                )
                Text(
                    "Extractor health",
                    color = TextPrimary,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Medium,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.size(50.dp))
            }

            Spacer(Modifier.height(24.dp))

            Text(
                text = when {
                    state.isRunning -> "Checking…"
                    state.innertubeSearchOk == true && state.streamResolveOk == true -> "Everything is working"
                    state.innertubeSearchOk == null && state.streamResolveOk == null -> "Ready to check"
                    else -> "Something needs attention"
                },
                color = TextPrimary,
                fontSize = 28.sp,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                "Live tests of the services Geekify uses to find and play music.",
                color = TextSecondary,
                fontSize = 14.sp,
                modifier = Modifier.padding(top = 6.dp, bottom = 24.dp)
            )

            CheckCard(
                title = "Search",
                subtitle = "Finds songs, albums and artists",
                ok = state.innertubeSearchOk,
                running = state.isRunning
            )
            Spacer(Modifier.height(14.dp))
            CheckCard(
                title = "Audio stream",
                subtitle = "Resolves a playable audio URL",
                ok = state.streamResolveOk,
                running = state.isRunning
            ) {
                state.mimeType?.let { Text("Format  $it", color = Lime, fontSize = 12.sp) }
                state.resolvedUrl?.let {
                    Text(
                        "Stream  $it",
                        color = TextMuted,
                        fontSize = 11.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }

            state.error?.let { message ->
                Spacer(Modifier.height(14.dp))
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(24.dp))
                        .background(ErrorRed.copy(alpha = 0.12f))
                        .padding(18.dp)
                ) {
                    Text("What went wrong", color = ErrorRed, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    Text(message, color = TextSecondary, fontSize = 13.sp, lineHeight = 19.sp, modifier = Modifier.padding(top = 6.dp))
                }
            }

            Spacer(Modifier.height(28.dp))

            val canRun = !state.isRunning
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(54.dp)
                    .clip(CircleShape)
                    .background(if (canRun) Lime else InkElevated)
                    .then(if (canRun) Modifier.bouncyClickable(pressedScale = 0.97f) { viewModel.runDiagnostics() } else Modifier),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (state.isRunning) {
                    CircularProgressIndicator(color = Lime, modifier = Modifier.size(22.dp), strokeWidth = 2.5.dp)
                } else {
                    Icon(Icons.Default.Refresh, contentDescription = null, tint = OnAccent, modifier = Modifier.size(22.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Run again", color = OnAccent, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

/** One diagnostic: title, a status badge (checking / working / failed) and optional details underneath. */
@Composable
private fun CheckCard(
    title: String,
    subtitle: String,
    ok: Boolean?,
    running: Boolean,
    details: @Composable ColumnScope.() -> Unit = {}
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(InkPanel)
            .padding(18.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(title, color = TextPrimary, fontSize = 17.sp, fontWeight = FontWeight.Medium)
                Text(subtitle, color = TextSecondary, fontSize = 13.sp, modifier = Modifier.padding(top = 2.dp))
            }
            Spacer(Modifier.width(12.dp))
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(
                        when (ok) {
                            true -> Lime.copy(alpha = 0.16f)
                            false -> ErrorRed.copy(alpha = 0.16f)
                            null -> InkElevated
                        }
                    ),
                contentAlignment = Alignment.Center
            ) {
                when (ok) {
                    true -> Icon(Icons.Default.CheckCircle, contentDescription = "Working", tint = Lime, modifier = Modifier.size(24.dp))
                    false -> Icon(Icons.Default.Error, contentDescription = "Failed", tint = ErrorRed, modifier = Modifier.size(24.dp))
                    null -> if (running) {
                        CircularProgressIndicator(color = Lime, modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    }
                }
            }
        }
        if (ok != null) {
            Spacer(Modifier.height(10.dp))
            details()
        }
    }
}
