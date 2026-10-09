package com.geekify.android.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.geekify.android.data.model.*

@Composable
fun ShelfRow(
    shelf: Shelf,
    modifier: Modifier = Modifier,
    onTrackClick: (Track, List<Track>) -> Unit,
    onCardClick: (Card) -> Unit,
    onTrackLongClick: (Track) -> Unit = {}
) {
    if (shelf.items.isEmpty()) return

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 20.dp)
    ) {
        SectionTitle(shelf.title)

        Spacer(Modifier.height(4.dp))

        // The same song/card can appear twice in one shelf; LazyRow crashes on duplicate keys, so keep the first of each.
        val uniqueItems = remember(shelf.items) { shelf.items.distinctBy { it.id } }
        val allTracksInShelf = uniqueItems.filterIsInstance<ShelfTrack>().map { it.value }

        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            items(uniqueItems, key = { it.id }) { item ->
                when (item) {
                    is ShelfTrack -> {
                        CardItem(
                            card = Card(
                                id = item.value.videoId,
                                title = item.value.title,
                                subtitle = item.value.artist,
                                thumbnails = item.value.thumbnails,
                                type = "song",
                                videoId = item.value.videoId
                            ),
                            onClick = { onTrackClick(item.value, allTracksInShelf) },
                            onLongClick = { onTrackLongClick(item.value) }
                        )
                    }
                    is ShelfCard -> {
                        CardItem(
                            card = item.value,
                            onClick = { onCardClick(item.value) }
                        )
                    }
                }
            }
        }
    }
}
