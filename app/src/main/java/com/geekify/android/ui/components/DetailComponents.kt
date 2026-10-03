package com.geekify.android.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.geekify.android.ui.theme.*

/** Spotify-style header for album / playlist / liked pages: back arrow, big cover, title, green play button. */
@Composable
fun DetailHeader(
    onBack: () -> Unit,
    title: String,
    subtitle: String?,
    meta: String?,
    onPlay: (() -> Unit)?,
    modifier: Modifier = Modifier,
    trailing: @Composable RowScope.() -> Unit = {},
    art: @Composable BoxScope.() -> Unit
) {
    Column(modifier = modifier.fillMaxWidth().statusBarsPadding()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = TextPrimary)
            }
            Spacer(Modifier.weight(1f))
            trailing()
        }

        Box(
            modifier = Modifier
                .align(Alignment.CenterHorizontally)
                .size(220.dp)
                .shadow(16.dp, RoundedCornerShape(4.dp))
                .clip(RoundedCornerShape(4.dp))
                .background(InkElevated),
            contentAlignment = Alignment.Center,
            content = art
        )

        Spacer(Modifier.height(20.dp))

        Column(modifier = Modifier.padding(horizontal = 16.dp)) {
            Text(
                text = title,
                color = TextPrimary,
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            if (!subtitle.isNullOrBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(subtitle, color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            }
            if (!meta.isNullOrBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(meta, color = TextSecondary, fontSize = 13.sp)
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (onPlay != null) {
                Box(
                    modifier = Modifier
                        .size(56.dp)
                        .clip(CircleShape)
                        .background(SpotifyGreen)
                        .bouncyClickable(pressedScale = 0.92f, onClick = onPlay),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Default.PlayArrow, contentDescription = "Play", tint = OnAccent, modifier = Modifier.size(34.dp))
                }
            }
        }
    }
}
