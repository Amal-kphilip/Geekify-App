package com.geekify.android.ui.player

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Radio
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.ThumbDown
import androidx.compose.material.icons.rounded.ThumbDownOffAlt
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.geekify.android.audio.AudioSettings
import com.geekify.android.data.local.DownloadEntity
import com.geekify.android.data.local.FeedbackAction
import com.geekify.android.data.model.Track
import com.geekify.android.downloads.SongDownloadManager
import com.geekify.android.ui.components.CreatePlaylistDialog
import com.geekify.android.ui.components.bestArtworkUrl
import com.geekify.android.ui.components.bouncyClickable
import com.geekify.android.ui.library.LibraryViewModel
import com.geekify.android.ui.theme.*
import kotlin.math.roundToInt
import kotlinx.coroutines.launch
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject

private enum class PlayerMenuPage {
    MAIN,
    TEMPO_PITCH,
    PLAYLISTS,
    SONG_DETAILS
}

private data class MenuActionItem(
    val icon: ImageVector,
    val title: String,
    val description: String? = null,
    val tint: Color? = null,
    val badgeText: String? = null,
    val showChevron: Boolean = false,
    val enabled: Boolean = true,
    val onClick: () -> Unit
)

/**
 * Geekify-native More Options modal bottom sheet for the Now Playing screen.
 * Styled exclusively with Geekify's reactive theme tokens (InkPanel, InkElevated, InkGlassBorder,
 * user-selected accents, Sohne Breit typography) and full light/dark theme compatibility.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerMoreOptionsSheet(
    viewModel: PlayerViewModel,
    accentColor: Color,
    onDismiss: () -> Unit,
    onNavigateToArtist: (String) -> Unit,
    onNavigateToAlbum: (String) -> Unit,
    onNavigateToEqualizer: () -> Unit,
    onNavigateToListenTogether: () -> Unit,
    libraryViewModel: LibraryViewModel = koinViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val audioSettings by viewModel.audioSettings.collectAsStateWithLifecycle()
    val isDisliked by viewModel.isCurrentDisliked.collectAsStateWithLifecycle()
    val playlists by libraryViewModel.playlists.collectAsState()
    val downloadManager: SongDownloadManager = koinInject()
    val feedbackRepository: com.geekify.android.domain.recommend.RecommendationFeedbackRepository = koinInject()
    val allDownloads by downloadManager.downloads.collectAsState(initial = emptyList())

    val track = state.current ?: run {
        onDismiss()
        return
    }
    val activeTrackFeedback by remember(track.videoId) {
        feedbackRepository.observeTrackFeedbackAction(track.videoId)
    }.collectAsState(initial = null)
    var feedbackSubmissionInFlight by remember(track.videoId) { mutableStateOf(false) }
    val currentDownload = remember(allDownloads, track.videoId) {
        allDownloads.firstOrNull { it.videoId == track.videoId }
    }
    val isDownloaded = currentDownload?.status == DownloadEntity.STATUS_COMPLETED
    val isDownloading = currentDownload?.status == DownloadEntity.STATUS_RUNNING ||
        currentDownload?.status == DownloadEntity.STATUS_QUEUED

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var activePage by remember { mutableStateOf(PlayerMenuPage.MAIN) }
    var showCreatePlaylist by remember { mutableStateOf(false) }

    val onAccentColor = if (accentColor.luminance() > 0.55f) Color(0xFF14110A) else Color.White
    val trackUrl = "https://music.youtube.com/watch?v=${track.videoId}"
    val firstArtistId = track.artists.firstOrNull { !it.id.isNullOrBlank() }?.id

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = InkPanel,
        contentColor = TextPrimary,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        dragHandle = {
            Box(
                modifier = Modifier
                    .padding(top = 12.dp, bottom = 8.dp)
                    .width(42.dp)
                    .height(4.dp)
                    .clip(CircleShape)
                    .background(InkGlassBorder)
            )
        }
    ) {
        AnimatedContent(
            targetState = activePage,
            transitionSpec = {
                val forward = targetState != PlayerMenuPage.MAIN
                if (forward) {
                    (slideInHorizontally(tween(220)) { it / 3 } + fadeIn(tween(180)))
                        .togetherWith(slideOutHorizontally(tween(180)) { -it / 4 } + fadeOut(tween(140)))
                } else {
                    (slideInHorizontally(tween(220)) { -it / 4 } + fadeIn(tween(180)))
                        .togetherWith(slideOutHorizontally(tween(180)) { it / 3 } + fadeOut(tween(140)))
                }
            },
            label = "geekifyPlayerMenuPages"
        ) { page ->
            when (page) {
                PlayerMenuPage.MAIN -> {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 18.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                        contentPadding = PaddingValues(top = 4.dp, bottom = 28.dp)
                    ) {
                        // 1. Geekify Track Header Card
                        item {
                            GeekifyTrackHeaderCard(
                                track = track,
                                isDownloaded = isDownloaded,
                                accentColor = accentColor
                            )
                        }

                        // 2. Quick Actions Strip (Radio, Playlist, Share, Copy Link)
                        item {
                            GeekifyQuickActionsRow(
                                accentColor = accentColor,
                                actions = listOf(
                                    MenuActionItem(
                                        icon = Icons.Rounded.Radio,
                                        title = "Radio",
                                        onClick = {
                                            viewModel.startRadio(track)
                                            Toast.makeText(context, "Starting radio for ${track.title}", Toast.LENGTH_SHORT).show()
                                            onDismiss()
                                        }
                                    ),
                                    MenuActionItem(
                                        icon = GeekifyIcons.PlaylistAdd,
                                        title = "Playlist",
                                        onClick = { activePage = PlayerMenuPage.PLAYLISTS }
                                    ),
                                    MenuActionItem(
                                        icon = Icons.Rounded.Share,
                                        title = "Share",
                                        onClick = {
                                            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                                type = "text/plain"
                                                putExtra(Intent.EXTRA_SUBJECT, track.title)
                                                putExtra(Intent.EXTRA_TEXT, "${track.title} – ${track.artist}\n$trackUrl")
                                            }
                                            context.startActivity(Intent.createChooser(shareIntent, "Share song"))
                                            onDismiss()
                                        }
                                    ),
                                    MenuActionItem(
                                        icon = Icons.Rounded.ContentCopy,
                                        title = "Copy Link",
                                        onClick = {
                                            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                                            cm?.setPrimaryClip(ClipData.newPlainText(track.title, trackUrl))
                                            Toast.makeText(context, "Song link copied", Toast.LENGTH_SHORT).show()
                                            onDismiss()
                                        }
                                    )
                                )
                            )
                        }

                        // 3. Volume Control Panel
                        item {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(22.dp))
                                    .background(InkElevated)
                                    .border(1.dp, InkGlassBorder, RoundedCornerShape(22.dp))
                                    .padding(horizontal = 16.dp, vertical = 14.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .size(32.dp)
                                                .clip(CircleShape)
                                                .background(accentColor.copy(alpha = 0.14f)),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Icon(
                                                imageVector = GeekifyIcons.VolumeUp,
                                                contentDescription = null,
                                                tint = accentColor,
                                                modifier = Modifier.size(18.dp)
                                            )
                                        }
                                        Text(
                                            text = "Output Volume",
                                            color = TextPrimary,
                                            fontSize = 14.5.sp,
                                            fontWeight = FontWeight.SemiBold
                                        )
                                    }
                                    Box(
                                        modifier = Modifier
                                            .clip(CircleShape)
                                            .background(accentColor.copy(alpha = 0.14f))
                                            .padding(horizontal = 10.dp, vertical = 4.dp)
                                    ) {
                                        Text(
                                            text = "${(state.volume.coerceIn(0f, 1f) * 100).roundToInt()}%",
                                            color = accentColor,
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                }
                                Slider(
                                    value = state.volume.coerceIn(0f, 1f),
                                    onValueChange = { viewModel.setVolume(it) },
                                    colors = SliderDefaults.colors(
                                        thumbColor = accentColor,
                                        activeTrackColor = accentColor,
                                        inactiveTrackColor = InkGlassBorder
                                    )
                                )
                            }
                        }

                        // 4. Explore & Track Info Section
                        item {
                            GeekifySectionCard(
                                header = "EXPLORE & DETAILS",
                                accentColor = accentColor,
                                actions = buildList {
                                    if (!firstArtistId.isNullOrBlank()) {
                                        add(
                                            MenuActionItem(
                                                icon = GeekifyIcons.Person,
                                                title = "Go to Artist",
                                                description = track.artist,
                                                showChevron = true,
                                                onClick = {
                                                    onDismiss()
                                                    onNavigateToArtist(firstArtistId)
                                                }
                                            )
                                        )
                                    }
                                    if (!track.albumId.isNullOrBlank()) {
                                        add(
                                            MenuActionItem(
                                                icon = GeekifyIcons.Album,
                                                title = "Go to Album",
                                                description = track.album?.takeIf { it.isNotBlank() },
                                                showChevron = true,
                                                onClick = {
                                                    onDismiss()
                                                    onNavigateToAlbum(track.albumId)
                                                }
                                            )
                                        )
                                    }
                                    add(
                                        MenuActionItem(
                                            icon = GeekifyIcons.Info,
                                            title = "Song Details",
                                            description = listOfNotNull(
                                                track.album?.takeIf { it.isNotBlank() },
                                                track.duration?.takeIf { it.isNotBlank() }
                                            ).joinToString(" · ").takeIf { it.isNotBlank() },
                                            showChevron = true,
                                            onClick = { activePage = PlayerMenuPage.SONG_DETAILS }
                                        )
                                    )
                                }
                            )
                        }

                        // 5. Offline & Feedback Section
                        item {
                            val isMoreLikeSelected = activeTrackFeedback == com.geekify.android.data.local.FeedbackAction.MORE_LIKE_THIS
                            val isLessLikeSelected = activeTrackFeedback == com.geekify.android.data.local.FeedbackAction.LESS_LIKE_THIS
                            GeekifySectionCard(
                                header = "OFFLINE & PREFERENCES",
                                accentColor = accentColor,
                                actions = buildList {
                                    add(
                                        MenuActionItem(
                                            icon = GeekifyIcons.AutoAwesome,
                                            title = "More Like This",
                                            description = if (isMoreLikeSelected) {
                                                "Boosting similar artists & style in your recommendations"
                                            } else {
                                                "Tune recommendations toward this track's style"
                                            },
                                            tint = if (isMoreLikeSelected) accentColor else null,
                                            badgeText = if (isMoreLikeSelected) "Active" else null,
                                            onClick = {
                                                if (feedbackSubmissionInFlight) return@MenuActionItem
                                                feedbackSubmissionInFlight = true
                                                scope.launch {
                                                    try {
                                                        val updated = feedbackRepository.toggleTrackFeedback(
                                                            track = track,
                                                            action = com.geekify.android.data.local.FeedbackAction.MORE_LIKE_THIS
                                                        )
                                                        val msg = if (updated != null) {
                                                            "Got it — recommending more like \"${track.title}\""
                                                        } else {
                                                            "Removed \"More Like This\" preference"
                                                        }
                                                        Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                                                    } finally {
                                                        feedbackSubmissionInFlight = false
                                                    }
                                                }
                                            }
                                        )
                                    )
                                    add(
                                        MenuActionItem(
                                            icon = GeekifyIcons.Sliders,
                                            title = "Less Like This",
                                            description = if (isLessLikeSelected) {
                                                "Showing fewer tracks like this in your recommendations"
                                            } else {
                                                "Reduce similar recommendations without blocking this language"
                                            },
                                            tint = if (isLessLikeSelected) Color(0xFFF87171) else null,
                                            badgeText = if (isLessLikeSelected) "Active" else null,
                                            onClick = {
                                                if (feedbackSubmissionInFlight) return@MenuActionItem
                                                feedbackSubmissionInFlight = true
                                                scope.launch {
                                                    try {
                                                        val updated = feedbackRepository.toggleTrackFeedback(
                                                            track = track,
                                                            action = com.geekify.android.data.local.FeedbackAction.LESS_LIKE_THIS
                                                        )
                                                        val msg = if (updated != null) {
                                                            "Got it — showing less like \"${track.title}\""
                                                        } else {
                                                            "Removed \"Less Like This\" preference"
                                                        }
                                                        Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                                                    } finally {
                                                        feedbackSubmissionInFlight = false
                                                    }
                                                }
                                            }
                                        )
                                    )
                                    add(
                                        MenuActionItem(
                                            icon = if (isDownloaded) GeekifyIcons.CloudDone else GeekifyIcons.Download,
                                            title = when {
                                                isDownloaded -> "Remove Download"
                                                isDownloading -> "Cancel Download"
                                                else -> "Download Offline"
                                            },
                                            description = when {
                                                isDownloaded -> "Saved locally for offline playback"
                                                isDownloading -> "Downloading (${(currentDownload.progress * 100).roundToInt()}%)"
                                                else -> "Save this track for offline listening"
                                            },
                                            tint = if (isDownloaded || isDownloading) accentColor else null,
                                            badgeText = if (isDownloaded) "Saved" else null,
                                            onClick = {
                                                when {
                                                    isDownloaded -> {
                                                        downloadManager.deleteDownload(track.videoId)
                                                        Toast.makeText(context, "Removed offline download", Toast.LENGTH_SHORT).show()
                                                    }
                                                    isDownloading -> {
                                                        downloadManager.cancel(track.videoId)
                                                        Toast.makeText(context, "Download cancelled", Toast.LENGTH_SHORT).show()
                                                    }
                                                    else -> {
                                                        downloadManager.enqueue(track)
                                                        Toast.makeText(context, "Queued download for ${track.title}", Toast.LENGTH_SHORT).show()
                                                    }
                                                }
                                                onDismiss()
                                            }
                                        )
                                    )
                                    add(
                                        MenuActionItem(
                                            icon = if (isDisliked) Icons.Rounded.ThumbDown else Icons.Rounded.ThumbDownOffAlt,
                                            title = if (isDisliked) "Remove Dislike" else "Dislike Song",
                                            description = if (isDisliked) "Undo dislike feedback" else "Tune recommendations away from this track",
                                            tint = if (isDisliked) ErrorRed else null,
                                            onClick = {
                                                val nowDisliked = viewModel.toggleDislikeCurrent()
                                                if (nowDisliked) {
                                                    scope.launch {
                                                        libraryViewModel.recordFeedback(track, FeedbackAction.LESS_LIKE_THIS)
                                                    }
                                                }
                                                Toast.makeText(
                                                    context,
                                                    if (nowDisliked) "Disliked song" else "Removed dislike",
                                                    Toast.LENGTH_SHORT
                                                ).show()
                                                onDismiss()
                                            }
                                        )
                                    )
                                    if (track.artist.isNotBlank()) {
                                        add(
                                            MenuActionItem(
                                                icon = Icons.Rounded.Block,
                                                title = "Block Artist",
                                                description = "Stop recommending ${track.artist}",
                                                tint = ErrorRed,
                                                onClick = {
                                                    scope.launch {
                                                        libraryViewModel.recordArtistFeedback(track.artist, FeedbackAction.BLOCK_ARTIST)
                                                    }
                                                    Toast.makeText(context, "Blocked ${track.artist}", Toast.LENGTH_SHORT).show()
                                                    onDismiss()
                                                }
                                            )
                                        )
                                    }
                                }
                            )
                        }

                        // 6. Audio & Social Section
                        item {
                            val speedPitchSummary = "%.2fx speed · %.2fx pitch".format(audioSettings.speed, audioSettings.pitch)
                            GeekifySectionCard(
                                header = "AUDIO & SOCIAL",
                                accentColor = accentColor,
                                actions = listOf(
                                    MenuActionItem(
                                        icon = GeekifyIcons.Groups,
                                        title = "Listen Together",
                                        description = "Sync live playback with friends",
                                        showChevron = true,
                                        onClick = {
                                            onDismiss()
                                            onNavigateToListenTogether()
                                        }
                                    ),
                                    MenuActionItem(
                                        icon = GeekifyIcons.GraphicEq,
                                        title = "Equalizer",
                                        description = if (audioSettings.eqEnabled) "Active · ${audioSettings.preset}" else "Off · 5-band parametric EQ",
                                        tint = if (audioSettings.eqEnabled) accentColor else null,
                                        badgeText = if (audioSettings.eqEnabled) "ON" else null,
                                        showChevron = true,
                                        onClick = {
                                            onDismiss()
                                            onNavigateToEqualizer()
                                        }
                                    ),
                                    MenuActionItem(
                                        icon = Icons.Rounded.Speed,
                                        title = "Tempo & Pitch",
                                        description = speedPitchSummary,
                                        tint = if (audioSettings.speed != 1f || audioSettings.pitch != 1f) accentColor else null,
                                        showChevron = true,
                                        onClick = { activePage = PlayerMenuPage.TEMPO_PITCH }
                                    )
                                )
                            )
                        }
                    }
                }

                PlayerMenuPage.TEMPO_PITCH -> {
                    TempoPitchSubpage(
                        audioSettings = audioSettings,
                        accentColor = accentColor,
                        onAccentColor = onAccentColor,
                        onSpeedChange = viewModel::setPlaybackSpeed,
                        onPitchChange = viewModel::setPlaybackPitch,
                        onReset = viewModel::resetTempoPitch,
                        onBack = { activePage = PlayerMenuPage.MAIN }
                    )
                }

                PlayerMenuPage.PLAYLISTS -> {
                    AddToPlaylistSubpage(
                        track = track,
                        playlists = playlists,
                        accentColor = accentColor,
                        onSelectPlaylist = { playlistId, name ->
                            libraryViewModel.addToPlaylist(playlistId, track)
                            Toast.makeText(context, "Added to $name", Toast.LENGTH_SHORT).show()
                            onDismiss()
                        },
                        onCreateNew = { showCreatePlaylist = true },
                        onBack = { activePage = PlayerMenuPage.MAIN }
                    )
                }

                PlayerMenuPage.SONG_DETAILS -> {
                    SongDetailsSubpage(
                        track = track,
                        isDownloaded = isDownloaded,
                        accentColor = accentColor,
                        onBack = { activePage = PlayerMenuPage.MAIN }
                    )
                }
            }
        }
    }

    if (showCreatePlaylist) {
        CreatePlaylistDialog(
            onDismiss = { showCreatePlaylist = false },
            onCreate = { name ->
                libraryViewModel.createPlaylistWithTrack(name, track)
                showCreatePlaylist = false
                Toast.makeText(context, "Created playlist \"$name\"", Toast.LENGTH_SHORT).show()
                onDismiss()
            }
        )
    }
}

@Composable
private fun GeekifyTrackHeaderCard(
    track: Track,
    isDownloaded: Boolean,
    accentColor: Color
) {
    val thumbUrl = track.thumbnails.bestArtworkUrl(360)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(22.dp))
            .background(InkElevated)
            .border(1.dp, InkGlassBorder, RoundedCornerShape(22.dp))
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(56.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(InkPanel)
                .border(1.dp, InkGlassBorder, RoundedCornerShape(14.dp)),
            contentAlignment = Alignment.Center
        ) {
            if (thumbUrl != null) {
                AsyncImage(
                    model = thumbUrl,
                    contentDescription = track.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Icon(GeekifyIcons.MusicNote, contentDescription = null, tint = TextSecondary)
            }
        }
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = track.title,
                color = TextPrimary,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = track.artist,
                color = TextSecondary,
                fontSize = 13.sp,
                fontWeight = FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            val meta = listOfNotNull(
                track.album?.takeIf { it.isNotBlank() },
                track.duration?.takeIf { it.isNotBlank() }
            ).joinToString(" · ")
            if (meta.isNotBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = meta,
                    color = TextMuted,
                    fontSize = 11.5.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        if (isDownloaded) {
            Spacer(Modifier.width(8.dp))
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(accentColor.copy(alpha = 0.16f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = GeekifyIcons.CloudDone,
                    contentDescription = "Downloaded",
                    tint = accentColor,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}

@Composable
private fun GeekifyQuickActionsRow(
    accentColor: Color,
    actions: List<MenuActionItem>
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        actions.forEach { action ->
            Column(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(18.dp))
                    .background(InkElevated)
                    .border(1.dp, InkGlassBorder, RoundedCornerShape(18.dp))
                    .bouncyClickable(pressedScale = 0.94f, onClick = action.onClick)
                    .padding(horizontal = 6.dp, vertical = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(accentColor.copy(alpha = 0.14f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = action.icon,
                        contentDescription = action.title,
                        tint = accentColor,
                        modifier = Modifier.size(19.dp)
                    )
                }
                Spacer(Modifier.height(7.dp))
                Text(
                    text = action.title,
                    color = TextPrimary,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun GeekifySectionCard(
    header: String?,
    accentColor: Color,
    actions: List<MenuActionItem>
) {
    if (actions.isEmpty()) return
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(7.dp)
    ) {
        if (!header.isNullOrBlank()) {
            Text(
                text = header,
                color = TextMuted,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 1.1.sp,
                modifier = Modifier.padding(start = 6.dp)
            )
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(22.dp))
                .background(InkElevated)
                .border(1.dp, InkGlassBorder, RoundedCornerShape(22.dp))
                .animateContentSize()
        ) {
            actions.forEachIndexed { idx, action ->
                val iconTint = action.tint ?: TextPrimary
                val badgeBg = when {
                    action.tint == ErrorRed -> ErrorRed.copy(alpha = 0.14f)
                    action.tint != null -> action.tint.copy(alpha = 0.15f)
                    else -> InkGlass
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .alpha(if (action.enabled) 1f else 0.55f)
                        .clickable(enabled = action.enabled, onClick = action.onClick)
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(38.dp)
                            .clip(CircleShape)
                            .background(badgeBg),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = action.icon,
                            contentDescription = null,
                            tint = iconTint,
                            modifier = Modifier.size(19.dp)
                        )
                    }
                    Spacer(Modifier.width(13.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = action.title,
                            color = action.tint ?: TextPrimary,
                            fontSize = 14.5.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (!action.description.isNullOrBlank()) {
                            Spacer(Modifier.height(2.dp))
                            Text(
                                text = action.description,
                                color = TextSecondary,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Normal,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                    if (!action.badgeText.isNullOrBlank()) {
                        Spacer(Modifier.width(8.dp))
                        Box(
                            modifier = Modifier
                                .clip(CircleShape)
                                .background(accentColor.copy(alpha = 0.16f))
                                .padding(horizontal = 8.dp, vertical = 3.dp)
                        ) {
                            Text(
                                text = action.badgeText,
                                color = accentColor,
                                fontSize = 10.5.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                    if (action.showChevron) {
                        Spacer(Modifier.width(6.dp))
                        Icon(
                            imageVector = GeekifyIcons.ChevronRight,
                            contentDescription = null,
                            tint = TextMuted,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
                if (idx < actions.lastIndex) {
                    HorizontalDivider(
                        color = InkGlassBorder,
                        thickness = 0.75.dp,
                        modifier = Modifier.padding(start = 64.dp, end = 14.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun SubpageHeader(
    title: String,
    accentColor: Color,
    onBack: () -> Unit,
    trailingLabel: String? = null,
    onTrailingClick: (() -> Unit)? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(InkElevated)
                .border(1.dp, InkGlassBorder, CircleShape)
                .clickable(onClick = onBack),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = GeekifyIcons.ArrowBack,
                contentDescription = "Back",
                tint = TextPrimary,
                modifier = Modifier.size(18.dp)
            )
        }
        Spacer(Modifier.width(12.dp))
        Text(
            text = title,
            color = TextPrimary,
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.weight(1f)
        )
        if (trailingLabel != null && onTrailingClick != null) {
            Box(
                modifier = Modifier
                    .clip(CircleShape)
                    .background(accentColor.copy(alpha = 0.15f))
                    .clickable(onClick = onTrailingClick)
                    .padding(horizontal = 14.dp, vertical = 7.dp)
            ) {
                Text(
                    text = trailingLabel,
                    color = accentColor,
                    fontSize = 12.5.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}

@Composable
private fun TempoPitchSubpage(
    audioSettings: AudioSettings,
    accentColor: Color,
    onAccentColor: Color,
    onSpeedChange: (Float) -> Unit,
    onPitchChange: (Float) -> Unit,
    onReset: () -> Unit,
    onBack: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp)
            .padding(top = 4.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        SubpageHeader(
            title = "Tempo & Pitch",
            accentColor = accentColor,
            onBack = onBack,
            trailingLabel = "Reset",
            onTrailingClick = onReset
        )

        // Speed panel
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(22.dp))
                .background(InkElevated)
                .border(1.dp, InkGlassBorder, RoundedCornerShape(22.dp))
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("Playback Speed", color = TextPrimary, fontSize = 14.5.sp, fontWeight = FontWeight.SemiBold)
                Text("%.2fx".format(audioSettings.speed), color = accentColor, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            }
            Slider(
                value = audioSettings.speed,
                onValueChange = { onSpeedChange((it * 20f).roundToInt() / 20f) },
                valueRange = AudioSettings.MIN_RATE..AudioSettings.MAX_RATE,
                colors = SliderDefaults.colors(
                    thumbColor = accentColor,
                    activeTrackColor = accentColor,
                    inactiveTrackColor = InkGlassBorder
                )
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                listOf(0.75f, 1.0f, 1.25f, 1.5f, 1.75f).forEach { preset ->
                    val selected = kotlin.math.abs(audioSettings.speed - preset) < 0.03f
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(CircleShape)
                            .background(if (selected) accentColor else InkPanel)
                            .border(1.dp, if (selected) accentColor else InkGlassBorder, CircleShape)
                            .clickable { onSpeedChange(preset) }
                            .padding(vertical = 8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "${preset}x",
                            color = if (selected) onAccentColor else TextPrimary,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
        }

        // Pitch panel
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(22.dp))
                .background(InkElevated)
                .border(1.dp, InkGlassBorder, RoundedCornerShape(22.dp))
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("Pitch", color = TextPrimary, fontSize = 14.5.sp, fontWeight = FontWeight.SemiBold)
                Text("%.2fx".format(audioSettings.pitch), color = accentColor, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            }
            Slider(
                value = audioSettings.pitch,
                onValueChange = { onPitchChange((it * 20f).roundToInt() / 20f) },
                valueRange = AudioSettings.MIN_RATE..AudioSettings.MAX_RATE,
                colors = SliderDefaults.colors(
                    thumbColor = accentColor,
                    activeTrackColor = accentColor,
                    inactiveTrackColor = InkGlassBorder
                )
            )
        }
    }
}

@Composable
private fun AddToPlaylistSubpage(
    track: Track,
    playlists: List<com.geekify.android.data.local.LocalPlaylist>,
    accentColor: Color,
    onSelectPlaylist: (String, String) -> Unit,
    onCreateNew: () -> Unit,
    onBack: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp)
            .padding(top = 4.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        SubpageHeader(
            title = "Add to Playlist",
            accentColor = accentColor,
            onBack = onBack,
            trailingLabel = "New",
            onTrailingClick = onCreateNew
        )
        if (playlists.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(20.dp))
                    .background(InkElevated)
                    .border(1.dp, InkGlassBorder, RoundedCornerShape(20.dp))
                    .padding(24.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "No playlists yet. Tap \"New\" to create one with \"${track.title}\".",
                    color = TextSecondary,
                    fontSize = 13.5.sp,
                    textAlign = TextAlign.Center
                )
            }
        } else {
            GeekifySectionCard(
                header = "YOUR PLAYLISTS",
                accentColor = accentColor,
                actions = playlists.map { pl ->
                    MenuActionItem(
                        icon = GeekifyIcons.QueueMusic,
                        title = pl.name,
                        showChevron = true,
                        onClick = { onSelectPlaylist(pl.id, pl.name) }
                    )
                }
            )
        }
    }
}

@Composable
private fun SongDetailsSubpage(
    track: Track,
    isDownloaded: Boolean,
    accentColor: Color,
    onBack: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp)
            .padding(top = 4.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        SubpageHeader(
            title = "Song Details",
            accentColor = accentColor,
            onBack = onBack
        )
        DetailInfoRow("TITLE", track.title)
        DetailInfoRow("ARTIST", track.artist)
        track.album?.takeIf { it.isNotBlank() }?.let { DetailInfoRow("ALBUM", it) }
        track.duration?.takeIf { it.isNotBlank() }?.let { DetailInfoRow("DURATION", it) }
        DetailInfoRow("TRACK ID", track.videoId)
        DetailInfoRow("OFFLINE STATUS", if (isDownloaded) "Downloaded locally" else "Streaming")
        if (track.explicit) DetailInfoRow("CONTENT RATING", "Explicit")
    }
}

@Composable
private fun DetailInfoRow(label: String, value: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(InkElevated)
            .border(1.dp, InkGlassBorder, RoundedCornerShape(18.dp))
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Text(
            text = label,
            color = TextMuted,
            fontSize = 10.5.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 1.sp
        )
        Spacer(Modifier.height(3.dp))
        Text(
            text = value,
            color = TextPrimary,
            fontSize = 14.5.sp,
            fontWeight = FontWeight.Medium
        )
    }
}
