package com.geekify.android.ui.library

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.History
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.lifecycle.ViewModel
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.geekify.android.data.local.HistoryRepository
import com.geekify.android.data.model.Track
import com.geekify.android.ui.components.AuroraBackground
import com.geekify.android.ui.components.CircleIconButton
import com.geekify.android.ui.components.LocalBottomInset
import com.geekify.android.ui.components.TrackRow
import com.geekify.android.ui.theme.*
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import dagger.hilt.android.lifecycle.HiltViewModel

@Composable
fun RecentsScreen(viewModel: RecentsViewModel, onBack: () -> Unit, onPlay: (Track, List<Track>) -> Unit) {
    val tracks by viewModel.recent.collectAsState(initial = emptyList())
    AuroraBackground {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 14.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                CircleIconButton(icon = Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", onClick = onBack, size = 50.dp)
                Text("Recents", color = TextPrimary, fontSize = 20.sp, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center, modifier = Modifier.weight(1f))
                Spacer(Modifier.size(50.dp))
            }
            if (tracks.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Default.History, null, tint = BrandMint, modifier = Modifier.size(40.dp))
                        Spacer(Modifier.height(12.dp))
                        Text("Your listening trail starts here", color = TextSecondary)
                    }
                }
            } else LazyColumn(contentPadding = PaddingValues(bottom = 24.dp + LocalBottomInset.current)) {
                items(tracks, key = { it.videoId }) { track ->
                    TrackRow(track = track, onClick = { onPlay(track, tracks) })
                }
            }
        }
    }
}

@HiltViewModel
class RecentsViewModel @Inject constructor(repository: HistoryRepository) : ViewModel() {
    val recent: Flow<List<Track>> = repository.recent
}
