package com.geekify.android.ui.downloads

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.DownloadDone
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.geekify.android.data.local.DownloadEntity
import com.geekify.android.data.model.Thumbnail
import com.geekify.android.data.model.Track
import org.koin.androidx.compose.koinViewModel
import java.util.Locale

@Composable
fun DownloadsScreen(
    onBack: () -> Unit,
    onPlayTrack: (Track) -> Unit,
    onPlayAllOffline: (List<Track>) -> Unit,
    bottomPadding: androidx.compose.ui.unit.Dp = 96.dp,
    viewModel: DownloadsViewModel = koinViewModel()
) {
    val downloads by viewModel.downloads.collectAsState()
    val stats by viewModel.stats.collectAsState()
    val speedBytesPerSec by viewModel.transferSpeedBytesPerSec.collectAsState()

    val active = downloads.filter {
        it.status == DownloadEntity.STATUS_RUNNING ||
            it.status == DownloadEntity.STATUS_QUEUED ||
            it.status == DownloadEntity.STATUS_PAUSED
    }
    val failed = downloads.filter { it.status == DownloadEntity.STATUS_FAILED }
    val completed = downloads.filter { it.status == DownloadEntity.STATUS_COMPLETED }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
    ) {
        // Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                    contentDescription = "Back",
                    tint = Color.White
                )
            }
            Text(
                text = "Offline Downloads",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                modifier = Modifier.weight(1f)
            )
            if (completed.isNotEmpty()) {
                OutlinedButton(
                    onClick = { onPlayAllOffline(completed.map { it.toTrack() }) },
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White)
                ) {
                    Icon(Icons.Rounded.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Play All")
                }
            }
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = bottomPadding),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Storage Summary Card (Permanent Downloads vs Streaming Cache)
            item(key = "storage_card") {
                Surface(
                    shape = RoundedCornerShape(18.dp),
                    color = Color.White.copy(alpha = 0.08f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Rounded.Storage,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = "Storage Separation",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = Color.White
                            )
                        }
                        Spacer(Modifier.height(10.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column {
                                Text(
                                    text = "Offline Downloads (${stats.downloadCount})",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Color.White.copy(alpha = 0.7f)
                                )
                                Text(
                                    text = formatBytes(stats.downloadBytes),
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White
                                )
                            }
                            Column(horizontalAlignment = Alignment.End) {
                                Text(
                                    text = "Streaming Cache (${stats.cacheCount})",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Color.White.copy(alpha = 0.7f)
                                )
                                Text(
                                    text = formatBytes(stats.cacheBytes),
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White
                                )
                            }
                        }
                        if (stats.cacheBytes > 0L) {
                            Spacer(Modifier.height(10.dp))
                            OutlinedButton(
                                onClick = { viewModel.clearStreamingCache() },
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("Clear Streaming Cache (Keeps Downloads)")
                            }
                        }
                    }
                }
            }

            if (active.isNotEmpty()) {
                item(key = "header_active") {
                    SectionLabel("Active (${active.size})")
                }
                items(active, key = { "active_${it.videoId}" }) { item ->
                    DownloadItemRow(
                        item = item,
                        speedBytesPerSec = if (item.status == DownloadEntity.STATUS_RUNNING) speedBytesPerSec else 0L,
                        onPlay = null,
                        onPause = { viewModel.pause(item.videoId) },
                        onResume = { viewModel.resume(item.videoId) },
                        onRetry = null,
                        onDelete = { viewModel.cancel(item.videoId) }
                    )
                }
            }

            if (failed.isNotEmpty()) {
                item(key = "header_failed") {
                    SectionLabel("Failed (${failed.size})")
                }
                items(failed, key = { "failed_${it.videoId}" }) { item ->
                    DownloadItemRow(
                        item = item,
                        onPlay = null,
                        onPause = null,
                        onResume = null,
                        onRetry = { viewModel.retry(item.videoId) },
                        onDelete = { viewModel.delete(item.videoId) }
                    )
                }
            }

            if (completed.isNotEmpty()) {
                item(key = "header_completed") {
                    SectionLabel("Offline Library (${completed.size})")
                }
                items(completed, key = { "done_${it.videoId}" }) { item ->
                    DownloadItemRow(
                        item = item,
                        onPlay = { onPlayTrack(item.toTrack()) },
                        onPause = null,
                        onResume = null,
                        onRetry = null,
                        onDelete = { viewModel.delete(item.videoId) }
                    )
                }
            }

            if (downloads.isEmpty()) {
                item(key = "empty") {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 48.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                imageVector = Icons.Rounded.DownloadDone,
                                contentDescription = null,
                                tint = Color.White.copy(alpha = 0.45f),
                                modifier = Modifier.size(48.dp)
                            )
                            Spacer(Modifier.height(12.dp))
                            Text(
                                text = "No offline downloads yet",
                                style = MaterialTheme.typography.titleMedium,
                                color = Color.White
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = "Tap the menu on any track to save it for offline playback.",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color.White.copy(alpha = 0.65f)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.SemiBold,
        color = Color.White.copy(alpha = 0.78f),
        modifier = Modifier.padding(top = 6.dp)
    )
}

@Composable
private fun DownloadItemRow(
    item: DownloadEntity,
    onPlay: (() -> Unit)?,
    onPause: (() -> Unit)?,
    onResume: (() -> Unit)?,
    onRetry: (() -> Unit)?,
    onDelete: () -> Unit,
    speedBytesPerSec: Long = 0L
) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = Color.White.copy(alpha = 0.07f),
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onPlay != null) Modifier.clickable(onClick = onPlay) else Modifier)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AsyncImage(
                    model = item.thumbnailUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(48.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color.White.copy(alpha = 0.1f))
                )
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = item.title,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = buildString {
                            append(item.artist)
                            if (item.status == DownloadEntity.STATUS_COMPLETED && item.totalBytes > 0L) {
                                append(" · ")
                                append(formatBytes(item.totalBytes))
                            } else if (item.status == DownloadEntity.STATUS_RUNNING) {
                                append(" · ${(item.progress * 100).toInt()}%")
                                if (speedBytesPerSec > 0L) {
                                    append(" (${formatBytes(speedBytesPerSec)}/s)")
                                }
                            } else if (item.status == DownloadEntity.STATUS_QUEUED) {
                                append(" · Queued")
                            } else if (item.status == DownloadEntity.STATUS_PAUSED) {
                                append(" · Paused")
                            }
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.7f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (item.status == DownloadEntity.STATUS_FAILED && !item.errorMessage.isNullOrBlank()) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Rounded.ErrorOutline,
                                contentDescription = null,
                                tint = Color(0xFFFF6B6B),
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(
                                text = item.errorMessage,
                                style = MaterialTheme.typography.labelSmall,
                                color = Color(0xFFFF6B6B),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
                if (item.status == DownloadEntity.STATUS_RUNNING && onPause != null) {
                    IconButton(onClick = onPause) {
                        Icon(Icons.Rounded.Pause, contentDescription = "Pause", tint = Color.White)
                    }
                }
                if (item.status == DownloadEntity.STATUS_PAUSED && onResume != null) {
                    IconButton(onClick = onResume) {
                        Icon(Icons.Rounded.PlayArrow, contentDescription = "Resume", tint = Color.White)
                    }
                }
                if (item.status == DownloadEntity.STATUS_FAILED && onRetry != null) {
                    IconButton(onClick = onRetry) {
                        Icon(Icons.Rounded.Refresh, contentDescription = "Retry", tint = Color.White)
                    }
                }
                IconButton(onClick = onDelete) {
                    Icon(
                        imageVector = if (item.status == DownloadEntity.STATUS_COMPLETED) {
                            Icons.Rounded.DeleteOutline
                        } else {
                            Icons.Rounded.Close
                        },
                        contentDescription = "Delete",
                        tint = Color.White.copy(alpha = 0.75f)
                    )
                }
            }
            if (item.status == DownloadEntity.STATUS_RUNNING || item.status == DownloadEntity.STATUS_QUEUED) {
                Spacer(Modifier.height(8.dp))
                if (item.status == DownloadEntity.STATUS_RUNNING && item.progress > 0f) {
                    LinearProgressIndicator(
                        progress = { item.progress.coerceIn(0f, 1f) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(4.dp)
                            .clip(RoundedCornerShape(2.dp))
                    )
                } else {
                    LinearProgressIndicator(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(4.dp)
                            .clip(RoundedCornerShape(2.dp))
                    )
                }
            }
        }
    }
}

private fun DownloadEntity.toTrack(): Track = Track(
    videoId = videoId,
    title = title,
    artist = artist,
    albumId = albumId,
    thumbnails = thumbnailUrl?.let { listOf(Thumbnail(it)) }.orEmpty()
)

private fun formatBytes(bytes: Long): String {
    if (bytes <= 0L) return "0 MB"
    val mb = bytes / (1024.0 * 1024.0)
    return if (mb >= 1024.0) {
        String.format(Locale.US, "%.2f GB", mb / 1024.0)
    } else {
        String.format(Locale.US, "%.1f MB", mb)
    }
}
