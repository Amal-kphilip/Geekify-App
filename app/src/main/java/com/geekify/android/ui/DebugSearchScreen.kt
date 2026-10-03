package com.geekify.android.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel

/** Phase 1 diagnostic UI: lets the data layer be validated before player work begins. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DebugSearchScreen(viewModel: DebugSearchViewModel = viewModel()) {
    val state by viewModel.state.collectAsState()
    Scaffold(topBar = { TopAppBar(title = { Text("Geekify data-layer check") }) }) { padding ->
        Column(Modifier.padding(padding).padding(16.dp)) {
            OutlinedTextField(value = state.query, onValueChange = viewModel::setQuery, label = { Text("Search YouTube Music") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            Spacer(Modifier.height(12.dp))
            Button(onClick = { viewModel.search() }, enabled = !state.loading) { Text(if (state.loading) "Searching…" else "Search") }
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 12.dp)) }
            state.response?.let { response ->
                Text("Songs ${response.songs.size} · Albums ${response.albums.size} · Artists ${response.artists.size} · Playlists ${response.playlists.size}", modifier = Modifier.padding(vertical = 12.dp))
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(response.songs, key = { it.videoId }) { track -> ListItem(headlineContent = { Text(track.title) }, supportingContent = { Text(track.artist) }) }
                }
            }
        }
    }
}
