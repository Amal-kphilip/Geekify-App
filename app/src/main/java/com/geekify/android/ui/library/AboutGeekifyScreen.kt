/*
 * Geekify Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */
package com.geekify.android.ui.library

import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import coil3.compose.AsyncImage
import com.geekify.android.BuildConfig
import com.geekify.android.ui.components.IosPalette
import com.geekify.android.ui.components.IosSettingsDivider
import com.geekify.android.ui.components.IosSettingsRow
import com.geekify.android.ui.components.IosSettingsSection
import com.geekify.android.ui.components.SettingsVisuals
import com.geekify.android.ui.components.bouncyClickable
import com.geekify.android.ui.theme.GeekifyIcons
import com.geekify.android.ui.theme.InkPanel
import com.geekify.android.ui.theme.Lime
import com.geekify.android.ui.theme.TextPrimary
import com.geekify.android.ui.theme.TextSecondary
import com.geekify.android.update.formatBytes
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val CREATOR_GITHUB_URL = "https://github.com/Amal-kphilip"
private const val CREATOR_AVATAR_URL = "https://github.com/Amal-kphilip.png"
private const val REPO_URL = "https://github.com/Amal-kphilip/Geekify-App"
private const val REPO_RELEASES_URL = "https://github.com/Amal-kphilip/Geekify-App/releases"

private enum class AboutInfoDialog(val title: String, val body: String) {
    LICENSES(
        title = "Open-Source Licenses",
        body = "Geekify is licensed under the GNU General Public License v3.0 (GPL-3.0).\n\n" +
            "Core open-source libraries:\n" +
            "• Jetpack Compose & Material 3 (Apache 2.0)\n" +
            "• AndroidX Media3 / ExoPlayer (Apache 2.0)\n" +
            "• Room Persistence Library & DataStore (Apache 2.0)\n" +
            "• Koin Dependency Injection (Apache 2.0)\n" +
            "• OkHttp & Kotlinx Serialization / Coroutines (Apache 2.0)\n" +
            "• Coil 3 Image Loading (Apache 2.0)"
    ),
    ACKNOWLEDGEMENTS(
        title = "Third-Party Acknowledgements",
        body = "Geekify integrates with open and community-driven services:\n\n" +
            "• LrcLib, SimpMusic & KuGou for synchronized lyrics lookup\n" +
            "• Shazam audio fingerprinting for song recognition\n" +
            "• ListenBrainz & Last.fm for optional scrobbling\n" +
            "• Discord Rich Presence for optional playback status\n" +
            "• GitHub Releases for signed APK updates and release history"
    ),
    PRIVACY(
        title = "Privacy Policy",
        body = "Geekify is local-first and privacy-respecting:\n\n" +
            "• Your listening history, likes, playlists, recommendation feedback, and customization preferences are stored locally on your device.\n" +
            "• Geekify includes no third-party ad SDKs or behavioral analytics trackers.\n" +
            "• Optional external features (lyrics lookup, song recognition, scrobbling, Discord RPC, and update checks) only contact their respective services when enabled or invoked by you."
    ),
    TERMS(
        title = "Terms of Service",
        body = "Geekify is free, open-source software distributed under GPL-3.0 without warranty of any kind.\n\n" +
            "• You are responsible for complying with the terms of any third-party services or accounts you choose to connect.\n" +
            "• Source code, issue tracking, and signed releases are published at github.com/Amal-kphilip/Geekify-App."
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun AboutGeekifyContent(
    updateCacheBytes: Long,
    onClearUpdateCache: () -> Unit
) {
    val context = LocalContext.current
    var activeDialog by remember { mutableStateOf<AboutInfoDialog?>(null) }

    val openUrl: (String) -> Unit = remember(context) {
        { url ->
            runCatching {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
            }
        }
    }

    val appIconBitmap = remember(context) {
        runCatching {
            context.packageManager.getApplicationIcon(context.applicationInfo)
                .toBitmap(width = 192, height = 192)
                .asImageBitmap()
        }.getOrNull()
    }

    val installDate = remember(context) {
        runCatching {
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            SimpleDateFormat("MMM d, yyyy", Locale.US).format(Date(info.firstInstallTime))
        }.getOrDefault("Unknown")
    }

    val deviceAbi = remember {
        Build.SUPPORTED_ABIS.firstOrNull()?.uppercase(Locale.US) ?: "UNIVERSAL"
    }
    val buildTypeLabel = if (BuildConfig.DEBUG) "Debug" else "Release"

    var entered by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { entered = true }
    val heroAlpha by animateFloatAsState(
        targetValue = if (entered) 1f else 0f,
        animationSpec = tween(260, easing = FastOutSlowInEasing),
        label = "aboutHeroAlpha"
    )
    val heroScale by animateFloatAsState(
        targetValue = if (entered) 1f else 0.96f,
        animationSpec = tween(280, easing = FastOutSlowInEasing),
        label = "aboutHeroScale"
    )

    // 1. Main App Identity Hero Card (Metrolist-inspired structure, 100% Geekify branding)
    val heroShape = RoundedCornerShape(28.dp)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer {
                alpha = heroAlpha
                scaleX = heroScale
                scaleY = heroScale
            }
            .clip(heroShape)
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        SettingsVisuals.cardRaised,
                        SettingsVisuals.card
                    )
                )
            )
            .border(0.9.dp, SettingsVisuals.cardBorder, heroShape)
    ) {
        // Low-contrast repeating Geekify motif pattern background
        val patternColor = Lime.copy(alpha = 0.055f)
        Canvas(modifier = Modifier.matchParentSize()) {
            val step = 54.dp.toPx()
            val tileSize = 18.dp.toPx()
            var row = 0
            var y = -step * 0.25f
            while (y < size.height + step) {
                val xOffset = if (row % 2 == 0) 0f else step * 0.5f
                var x = -step * 0.25f + xOffset
                while (x < size.width + step) {
                    drawRoundRect(
                        color = patternColor,
                        topLeft = Offset(x, y),
                        size = Size(tileSize, tileSize),
                        cornerRadius = CornerRadius(5.dp.toPx(), 5.dp.toPx()),
                        style = Stroke(width = 1.2.dp.toPx())
                    )
                    drawCircle(
                        color = patternColor,
                        radius = 2.6.dp.toPx(),
                        center = Offset(x + tileSize * 0.5f, y + tileSize * 0.5f)
                    )
                    x += step
                }
                y += step
                row++
            }
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                modifier = Modifier
                    .size(84.dp)
                    .clip(RoundedCornerShape(22.dp))
                    .background(SettingsVisuals.cardRaised)
                    .border(1.dp, Lime.copy(alpha = 0.32f), RoundedCornerShape(22.dp)),
                contentAlignment = Alignment.Center
            ) {
                if (appIconBitmap != null) {
                    Image(
                        bitmap = appIconBitmap,
                        contentDescription = "Geekify app icon",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Icon(
                        imageVector = GeekifyIcons.MusicNote,
                        contentDescription = "Geekify",
                        tint = Lime,
                        modifier = Modifier.size(38.dp)
                    )
                }
            }

            Spacer(Modifier.height(16.dp))

            Text(
                text = "Geekify",
                color = IosPalette.label,
                fontSize = 26.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = (-0.5).sp
            )

            Spacer(Modifier.height(6.dp))

            Text(
                text = "Midnight music player crafted for high-res playback, synchronized lyrics, and deep personalization.",
                color = IosPalette.secondaryLabel,
                fontSize = 13.5.sp,
                lineHeight = 19.sp,
                textAlign = TextAlign.Center
            )

            Spacer(Modifier.height(18.dp))

            // Separate rounded technical metadata chips
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                AboutMetaChip(label = "v${BuildConfig.VERSION_NAME}", accent = true)
                AboutMetaChip(label = "Build ${BuildConfig.VERSION_CODE}")
                AboutMetaChip(label = deviceAbi)
                AboutMetaChip(label = buildTypeLabel)
            }
        }
    }

    Spacer(Modifier.height(16.dp))

    // 2. Social & Project Quick-Link Tiles (GitHub & Releases with balanced widths)
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        AboutActionTile(
            icon = GeekifyIcons.Code,
            label = "GitHub",
            accentColor = IosPalette.label,
            onClick = { openUrl(CREATOR_GITHUB_URL) },
            modifier = Modifier.weight(1f)
        )
        AboutActionTile(
            icon = GeekifyIcons.AutoAwesome,
            label = "Releases",
            accentColor = Lime,
            onClick = { openUrl(REPO_RELEASES_URL) },
            modifier = Modifier.weight(1f)
        )
    }

    Spacer(Modifier.height(26.dp))

    // 3. Creator Section
    Text(
        text = "Creator",
        color = IosPalette.secondaryLabel,
        fontSize = 13.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.2.sp,
        modifier = Modifier.padding(start = 6.dp, bottom = 10.dp)
    )

    val creatorCardShape = RoundedCornerShape(24.dp)
    Surface(
        color = SettingsVisuals.card,
        shape = creatorCardShape,
        modifier = Modifier
            .fillMaxWidth()
            .border(0.85.dp, SettingsVisuals.cardBorder, creatorCardShape)
            .clip(creatorCardShape)
            .bouncyClickable(pressedScale = 0.97f) { openUrl(CREATOR_GITHUB_URL) }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(54.dp)
                    .clip(CircleShape)
                    .background(Lime.copy(alpha = 0.16f))
                    .border(1.dp, Lime.copy(alpha = 0.45f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "AK",
                    color = Lime,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold
                )
                AsyncImage(
                    model = CREATOR_AVATAR_URL,
                    contentDescription = "Amal K. Philip",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(CircleShape)
                )
            }
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Amal K. Philip",
                    color = IosPalette.label,
                    fontSize = 16.5.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = "Creator of Geekify",
                    color = IosPalette.secondaryLabel,
                    fontSize = 13.5.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Icon(
                imageVector = GeekifyIcons.ChevronRight,
                contentDescription = "Open GitHub profile",
                tint = IosPalette.tertiaryLabel,
                modifier = Modifier.size(20.dp)
            )
        }
    }

    // 4. Downloaded Update APK Cache Cleanup (shown only when cached APKs exist)
    if (updateCacheBytes > 0L) {
        Spacer(Modifier.height(26.dp))

        IosSettingsSection(title = "Software Update") {
            IosSettingsRow(
                icon = GeekifyIcons.DeleteOutline,
                tileColor = IosPalette.systemRed,
                title = "Clear Downloaded Update APKs",
                value = formatBytes(updateCacheBytes),
                destructive = true,
                onClick = onClearUpdateCache
            )
        }
    }

    Spacer(Modifier.height(22.dp))

    // 5. Legal & Project Information
    IosSettingsSection(title = "Legal & Acknowledgements") {
        IosSettingsRow(
            icon = GeekifyIcons.Library,
            tileColor = IosPalette.systemIndigo,
            title = "Open-Source Licenses",
            subtitle = "GPL-3.0 and core Android libraries",
            onClick = { activeDialog = AboutInfoDialog.LICENSES }
        )
        IosSettingsDivider()
        IosSettingsRow(
            icon = GeekifyIcons.FavoriteBorder,
            tileColor = IosPalette.systemPink,
            title = "Third-Party Acknowledgements",
            subtitle = "Lyrics, recognition, and community integrations",
            onClick = { activeDialog = AboutInfoDialog.ACKNOWLEDGEMENTS }
        )
        IosSettingsDivider()
        IosSettingsRow(
            icon = GeekifyIcons.Security,
            tileColor = IosPalette.systemBlue,
            title = "Privacy Policy",
            subtitle = "Local-first storage, zero ad trackers",
            onClick = { activeDialog = AboutInfoDialog.PRIVACY }
        )
        IosSettingsDivider()
        IosSettingsRow(
            icon = GeekifyIcons.Info,
            tileColor = IosPalette.systemOrange,
            title = "Terms of Service",
            subtitle = "Open-source usage terms",
            onClick = { activeDialog = AboutInfoDialog.TERMS }
        )
    }

    Spacer(Modifier.height(22.dp))

    IosSettingsSection(title = "Project Information") {
        IosSettingsRow(
            icon = GeekifyIcons.CalendarToday,
            tileColor = IosPalette.systemPurple,
            title = "First Installed",
            value = installDate,
            showChevron = false,
            onClick = null
        )
        IosSettingsDivider()
        IosSettingsRow(
            icon = GeekifyIcons.PhoneAndroid,
            tileColor = IosPalette.systemCyan,
            title = "Android Runtime",
            value = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
            showChevron = false,
            onClick = null
        )
        IosSettingsDivider()
        IosSettingsRow(
            icon = GeekifyIcons.Code,
            tileColor = IosPalette.systemGray,
            title = "Source Repository",
            subtitle = "github.com/Amal-kphilip/Geekify-App",
            onClick = { openUrl(REPO_URL) }
        )
    }

    activeDialog?.let { dlg ->
        AlertDialog(
            onDismissRequest = { activeDialog = null },
            containerColor = InkPanel,
            titleContentColor = TextPrimary,
            textContentColor = TextSecondary,
            title = {
                Text(
                    text = dlg.title,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Text(
                    text = dlg.body,
                    fontSize = 13.5.sp,
                    lineHeight = 20.sp,
                    modifier = Modifier.verticalScroll(rememberScrollState())
                )
            },
            confirmButton = {
                TextButton(onClick = { activeDialog = null }) {
                    Text("Close", color = Lime, fontWeight = FontWeight.SemiBold)
                }
            }
        )
    }
}

@Composable
private fun AboutMetaChip(
    label: String,
    accent: Boolean = false
) {
    val bg = if (accent) Lime.copy(alpha = 0.16f) else SettingsVisuals.cardRaised
    val border = if (accent) Lime.copy(alpha = 0.42f) else SettingsVisuals.cardBorder
    val textColor = if (accent) Lime else IosPalette.label
    Box(
        modifier = Modifier
            .clip(CircleShape)
            .background(bg)
            .border(0.8.dp, border, CircleShape)
            .padding(horizontal = 12.dp, vertical = 5.dp)
    ) {
        Text(
            text = label,
            color = textColor,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1
        )
    }
}

@Composable
private fun AboutActionTile(
    icon: ImageVector,
    label: String,
    accentColor: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val tileShape = RoundedCornerShape(20.dp)
    Surface(
        color = SettingsVisuals.card,
        shape = tileShape,
        modifier = modifier
            .border(0.8.dp, SettingsVisuals.cardBorder, tileShape)
            .clip(tileShape)
            .bouncyClickable(pressedScale = 0.95f, onClick = onClick)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 16.dp, horizontal = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = accentColor,
                modifier = Modifier.size(22.dp)
            )
            Text(
                text = label,
                color = IosPalette.label,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
