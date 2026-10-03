package com.geekify.android.ui.library

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.History
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.lifecycle.ViewModel
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.geekify.android.data.local.HistoryRepository
import com.geekify.android.data.model.Track
import com.geekify.android.ui.components.AuroraBackground
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
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Back", tint = TextPrimary) }
                Text("Recents", color = TextPrimary, fontSize = 24.sp, fontWeight = FontWeight.Bold)
            }
            if (tracks.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Default.History, null, tint = BrandMint, modifier = Modifier.size(40.dp))
                        Spacer(Modifier.height(12.dp))
                        Text("Your listening trail starts here", color = TextSecondary)
                    }
                }
            } else LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
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
