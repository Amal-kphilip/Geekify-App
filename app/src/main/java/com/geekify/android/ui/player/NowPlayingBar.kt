package com.geekify.android.ui.player

import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.geekify.android.ui.components.bouncyClickable
import com.geekify.android.ui.components.rememberArtColor
import com.geekify.android.ui.theme.*

@Composable
fun NowPlayingBar(
    viewModel: PlayerViewModel,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val state by viewModel.state.collectAsState()
    val isLiked by viewModel.isCurrentLiked.collectAsState()
    val track = state.current ?: return

    val thumbUrl = track.thumbnails.lastOrNull()?.url
    val artColor by rememberArtColor(thumbUrl, fallback = InkElevated)
    val barColor by animateColorAsState(artColor, tween(500), label = "barColor")

    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(barColor)
            .bouncyClickable(pressedScale = 0.985f, onClick = onClick)
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 8.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(42.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(InkElevated),
                    contentAlignment = Alignment.Center
                ) {
                    Crossfade(targetState = thumbUrl, animationSpec = tween(300), label = "miniArt") { url ->
                        if (url != null) {
                            AsyncImage(
                                model = url,
                                contentDescription = track.title,
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Crop
                            )
                        } else {
                            Icon(Icons.Default.MusicNote, contentDescription = null, tint = TextSecondary)
                        }
                    }
                }

                Spacer(Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = track.title,
                        color = TextPrimary,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = state.error ?: track.artist,
                        color = if (state.error != null) Color(0xFFFCD34D) else Color.White.copy(alpha = 0.7f),
                        fontSize = 13.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                LikeButton(liked = isLiked, size = 24.dp, onClick = { viewModel.toggleLikeCurrent() })

                IconButton(onClick = { viewModel.togglePlay() }) {
                    if (state.isBuffering) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(22.dp),
                            color = Color.White,
                            strokeWidth = 2.dp
                        )
                    } else {
                        Crossfade(targetState = state.isPlaying, animationSpec = tween(150), label = "miniPlay") { playing ->
                            Icon(
                                imageVector = if (playing) Icons.Default.Pause else Icons.Default.PlayArrow,
                                contentDescription = if (playing) "Pause" else "Play",
                                tint = Color.White,
                                modifier = Modifier.size(30.dp)
                            )
                        }
                    }
                }

                IconButton(onClick = { viewModel.next() }) {
                    Icon(Icons.Default.SkipNext, contentDescription = "Next", tint = Color.White, modifier = Modifier.size(26.dp))
                }
            }

            // Thin progress line, eased so it glides rather than ticking.
            val target = if (state.durationMs > 0L) {
                (state.progressMs.toFloat() / state.durationMs.toFloat()).coerceIn(0f, 1f)
            } else 0f
            val progress by animateFloatAsState(target, tween(900, easing = LinearEasing), label = "miniProgress")
            Box(
                modifier = Modifier
                    .padding(horizontal = 8.dp)
                    .fillMaxWidth()
                    .height(2.dp)
                    .clip(RoundedCornerShape(1.dp))
                    .background(Color.White.copy(alpha = 0.25f))
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .fillMaxWidth(progress)
                        .background(Color.White)
                )
            }
            Spacer(Modifier.height(6.dp))
        }
    }
}

/** Heart that pops with a spring when toggled; green when liked, like Spotify's now-playing screen. */
@Composable
fun LikeButton(
    liked: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: androidx.compose.ui.unit.Dp = 28.dp
) {
    val scale by animateFloatAsState(
        targetValue = if (liked) 1f else 0.92f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioHighBouncy, stiffness = Spring.StiffnessMedium),
        label = "likeScale"
    )
    IconButton(onClick = onClick, modifier = modifier) {
        Crossfade(targetState = liked, animationSpec = tween(150), label = "likeIcon") { isLiked ->
            Icon(
                imageVector = if (isLiked) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                contentDescription = "Like",
                tint = if (isLiked) SpotifyGreen else Color.White.copy(alpha = 0.85f),
                modifier = Modifier
                    .size(size)
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                    }
            )
        }
    }
}
