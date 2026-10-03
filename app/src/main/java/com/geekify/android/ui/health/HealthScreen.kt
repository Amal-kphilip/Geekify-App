package com.geekify.android.ui.health

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.geekify.android.ui.components.AuroraBackground
import com.geekify.android.ui.components.GlassSurface
import com.geekify.android.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HealthScreen(
    viewModel: HealthViewModel,
    onBack: () -> Unit
) {
    val state by viewModel.uiState.collectAsState()

    LaunchedEffect(Unit) {
        if (state.innertubeSearchOk == null) {
            viewModel.runDiagnostics()
        }
    }

    AuroraBackground {
        Scaffold(
            containerColor = Color.Transparent,
            topBar = {
                TopAppBar(
                    title = { Text("Extractor Health Check", fontWeight = FontWeight.Bold, color = TextPrimary) },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = TextPrimary)
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)
                )
            }
        ) { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(
                    text = "Runs diagnostic tests against YouTube Music endpoints to ensure metadata queries and audio stream resolution are operational.",
                    color = TextSecondary,
                    fontSize = 14.sp
                )

                // Test 1: InnerTube Search
                GlassSurface(
                    modifier = Modifier.fillMaxWidth(),
                    backgroundColor = InkPanel
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column {
                            Text("1. InnerTube Metadata API", color = TextPrimary, fontWeight = FontWeight.SemiBold)
                            Text("Searches catalog & parses browse shelves", color = TextSecondary, fontSize = 12.sp)
                        }

                        when (state.innertubeSearchOk) {
                            true -> Icon(Icons.Default.CheckCircle, contentDescription = "OK", tint = SuccessGreen)
                            false -> Icon(Icons.Default.Error, contentDescription = "Failed", tint = ErrorRed)
                            null -> if (state.isRunning) CircularProgressIndicator(modifier = Modifier.size(20.dp), color = BrandViolet)
                        }
                    }
                }

                // Test 2: Stream Resolution
                GlassSurface(
                    modifier = Modifier.fillMaxWidth(),
                    backgroundColor = InkPanel
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column {
                                Text("2. Audio Stream Resolution", color = TextPrimary, fontWeight = FontWeight.SemiBold)
                                Text("Extracts highest bitrate direct stream URL", color = TextSecondary, fontSize = 12.sp)
                            }

                            when (state.streamResolveOk) {
                                true -> Icon(Icons.Default.CheckCircle, contentDescription = "OK", tint = SuccessGreen)
                                false -> Icon(Icons.Default.Error, contentDescription = "Failed", tint = ErrorRed)
                                null -> if (state.isRunning) CircularProgressIndicator(modifier = Modifier.size(20.dp), color = BrandViolet)
                            }
                        }

                        state.mimeType?.let {
                            Spacer(Modifier.height(8.dp))
                            Text("MIME Format: $it", color = BrandMint, fontSize = 12.sp)
                        }

                        state.resolvedUrl?.let {
                            Spacer(Modifier.height(4.dp))
                            Text("Stream: $it", color = TextMuted, fontSize = 11.sp, maxLines = 2)
                        }
                    }
                }

                if (state.error != null) {
                    Text(
                        text = "Error: ${state.error}",
                        color = ErrorRed,
                        fontSize = 13.sp
                    )
                }

                Spacer(Modifier.weight(1f))

                Button(
                    onClick = { viewModel.runDiagnostics() },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !state.isRunning,
                    colors = ButtonDefaults.buttonColors(containerColor = BrandViolet),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    if (state.isRunning) {
                        CircularProgressIndicator(color = InkBackground, modifier = Modifier.size(20.dp))
                    } else {
                        Text("Re-run Diagnostics", color = InkBackground, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}
