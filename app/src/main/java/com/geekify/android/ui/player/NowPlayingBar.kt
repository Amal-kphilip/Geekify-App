package com.geekify.android.ui.player

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Shuffle
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
import com.geekify.android.ui.components.bestArtworkUrl
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import com.geekify.android.ui.glass.GlassStyle
import com.geekify.android.ui.glass.LocalGlassBackdrop
import com.geekify.android.ui.glass.liquidGlass
import com.geekify.android.ui.glass.trackPress
import com.geekify.android.ui.theme.*

/**
 * Floating mini player: a frosted capsule with the cover, title / artist, a lime play button,
 * next, a hairline divider and shuffle (same grammar as the compact pill in the design).
 */
@Composable
fun NowPlayingBar(
    viewModel: PlayerViewModel,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val state by viewModel.state.collectAsState()
    val track = state.current ?: return

    val thumbUrl = track.thumbnails.bestArtworkUrl(480)
    val pressed = remember { mutableStateOf(false) }
    val pressAnim by animateFloatAsState(if (pressed.value) 1f else 0f, label = "miniPress")

    Box(
        modifier = modifier
            .fillMaxWidth()
            // Floating glass surface only: the cover, title, artist and controls inside it stay sharp and opaque.
            .liquidGlass(RoundedCornerShape(40.dp), GlassStyle.Floating, pressProgress = { pressAnim })
            .trackPress(pressed)
            .bouncyClickable(pressedScale = 0.985f, onClick = onClick)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 10.dp, end = 6.dp, top = 10.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
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
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = state.error ?: track.artist,
                    color = if (state.error != null) Color(0xFFFCD34D) else TextSecondary,
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(Modifier.width(8.dp))

            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(Lime)
                    .bouncyClickable(pressedScale = 0.92f) { viewModel.togglePlay() },
                contentAlignment = Alignment.Center
            ) {
                if (state.isBuffering) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        color = OnAccent,
                        strokeWidth = 2.dp
                    )
                } else {
                    Crossfade(targetState = state.isPlaying, animationSpec = tween(150), label = "miniPlay") { playing ->
                        Icon(
                            imageVector = if (playing) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = if (playing) "Pause" else "Play",
                            tint = OnAccent,
                            modifier = Modifier.size(26.dp)
                        )
                    }
                }
            }

            Spacer(Modifier.width(2.dp))

            IconButton(onClick = { viewModel.next() }, modifier = Modifier.size(40.dp)) {
                Icon(Icons.Default.SkipNext, contentDescription = "Next", tint = Color.White, modifier = Modifier.size(26.dp))
            }

            Box(
                modifier = Modifier
                    .padding(horizontal = 4.dp)
                    .width(1.dp)
                    .height(22.dp)
                    .background(Color.White.copy(alpha = 0.22f))
            )

            IconButton(onClick = { viewModel.toggleShuffle() }, modifier = Modifier.size(40.dp)) {
                Icon(
                    Icons.Default.Shuffle,
                    contentDescription = "Shuffle",
                    tint = if (state.shuffle) Lime else Color.White,
                    modifier = Modifier.size(22.dp)
                )
            }
        }

        // Hairline progress, eased so it glides rather than ticking.
        val target = if (state.durationMs > 0L) {
            (state.progressMs.toFloat() / state.durationMs.toFloat()).coerceIn(0f, 1f)
        } else 0f
        val progress by animateFloatAsState(target, tween(900, easing = LinearEasing), label = "miniProgress")
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(horizontal = 30.dp, vertical = 5.dp)
                .fillMaxWidth()
                .height(2.dp)
                .clip(RoundedCornerShape(1.dp))
                .background(Color.White.copy(alpha = 0.16f))
        ) {
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(progress)
                    .background(Lime)
            )
        }
    }
}

/** Heart that pops with a spring when toggled; lime when liked. */
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
                tint = if (isLiked) Lime else Color.White.copy(alpha = 0.85f),
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
