package com.geekify.android.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AddCircleOutline
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.geekify.android.data.model.Track
import com.geekify.android.ui.theme.*

/** "3h 20min" / "42 min" from the tracks' durations; null when most durations are unknown. */
fun totalDurationText(tracks: List<Track>): String? {
    val known = tracks.mapNotNull { it.durationSeconds }
    if (known.isEmpty() || known.size * 2 < tracks.size) return null
    val minutes = known.sum() / 60
    return if (minutes >= 60) "${minutes / 60}h ${minutes % 60}min" else "$minutes min"
}

/**
 * Spotify-style header for album / playlist / liked pages: back arrow, large cover, description,
 * creator, "tracks • duration" line, then shuffle + the big green play button.
 */
@Composable
fun DetailHeader(
    onBack: () -> Unit,
    title: String,
    subtitle: String?,
    meta: String?,
    onPlay: (() -> Unit)?,
    modifier: Modifier = Modifier,
    description: String? = null,
    onShuffle: (() -> Unit)? = null,
    isSaved: Boolean = false,
    onToggleSaved: (() -> Unit)? = null,
    trailing: @Composable RowScope.() -> Unit = {},
    art: @Composable BoxScope.() -> Unit
) {
    Column(modifier = modifier.fillMaxWidth().statusBarsPadding()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            CircleIconButton(
                icon = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "Back",
                onClick = onBack,
                container = Color.White.copy(alpha = 0.16f)
            )
            Spacer(Modifier.weight(1f))
            trailing()
        }

        Spacer(Modifier.height(8.dp))

        Box(
            modifier = Modifier
                .align(Alignment.CenterHorizontally)
                .fillMaxWidth(0.68f)
                .aspectRatio(1f)
                .shadow(24.dp, RoundedCornerShape(32.dp))
                .clip(RoundedCornerShape(32.dp))
                .background(InkElevated),
            contentAlignment = Alignment.Center,
            content = art
        )

        Spacer(Modifier.height(24.dp))

        Column(modifier = Modifier.padding(horizontal = 20.dp)) {
            Text(
                text = title,
                color = TextPrimary,
                fontSize = 26.sp,
                lineHeight = 32.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            if (!description.isNullOrBlank()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = description,
                    color = TextSecondary,
                    fontSize = 16.sp,
                    lineHeight = 22.sp,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (!subtitle.isNullOrBlank()) {
                Spacer(Modifier.height(14.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier.size(28.dp).clip(CircleShape).background(BrandGradient),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(subtitle.first().uppercase(), color = OnAccent, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    }
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = subtitle,
                        color = TextPrimary,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            if (!meta.isNullOrBlank()) {
                Spacer(Modifier.height(10.dp))
                Text(meta, color = TextSecondary, fontSize = 15.sp)
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 18.dp),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (onToggleSaved != null) {
                // Add to / remove from Your Library: outlined plus when not saved, green check when saved.
                IconButton(onClick = onToggleSaved, modifier = Modifier.size(48.dp)) {
                    Icon(
                        imageVector = if (isSaved) Icons.Default.CheckCircle else Icons.Default.AddCircleOutline,
                        contentDescription = if (isSaved) "Remove from Your Library" else "Add to Your Library",
                        tint = if (isSaved) Lime else TextSecondary,
                        modifier = Modifier.size(32.dp)
                    )
                }
                Spacer(Modifier.weight(1f))
            }
            if (onShuffle != null) {
                IconButton(onClick = onShuffle, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.Default.Shuffle, contentDescription = "Shuffle play", tint = TextSecondary, modifier = Modifier.size(28.dp))
                }
                Spacer(Modifier.width(12.dp))
            }
            if (onPlay != null) {
                Box(
                    modifier = Modifier
                        .size(60.dp)
                        .clip(CircleShape)
                        .background(Lime)
                        .bouncyClickable(pressedScale = 0.92f, onClick = onPlay),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Default.PlayArrow, contentDescription = "Play", tint = OnAccent, modifier = Modifier.size(36.dp))
                }
            }
        }
    }
}
