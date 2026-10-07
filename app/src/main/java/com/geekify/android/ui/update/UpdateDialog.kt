package com.geekify.android.ui.update

import android.os.Build
import android.view.WindowManager
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import com.geekify.android.R
import com.geekify.android.ui.components.bouncyClickable
import com.geekify.android.ui.theme.*

enum class UpdateStage { Available, Downloading, Ready, Installing }

private val DialogSurface = Color(0xEB101014)
private val VersionCardColor = Color(0xFF1A1A20)

/**
 * Geekify's own "Update available" sheet. Dark frosted surface, lime accent, pill buttons and a thin
 * horizontal progress bar. The screen behind is dimmed and (Android 12+) blurred.
 *
 * [progress] is 0f..1f while downloading, or null when the size is unknown.
 */
@Composable
fun UpdateDialog(
    stage: UpdateStage,
    currentVersion: String,
    newVersion: String,
    progress: Float?,
    error: String?,
    onUpdate: () -> Unit,
    onInstall: () -> Unit,
    onLater: () -> Unit
) {
    Dialog(
        onDismissRequest = { if (stage != UpdateStage.Downloading && stage != UpdateStage.Installing) onLater() },
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = stage != UpdateStage.Downloading && stage != UpdateStage.Installing,
            dismissOnClickOutside = stage != UpdateStage.Downloading && stage != UpdateStage.Installing
        )
    ) {
        // Dim + blur whatever is behind the dialog window.
        val window = (LocalView.current.parent as? DialogWindowProvider)?.window
        SideEffect {
            window?.let { w ->
                w.setDimAmount(0.55f)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    w.addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
                    val attrs = w.attributes
                    attrs.blurBehindRadius = 28
                    w.attributes = attrs
                }
            }
        }

        val shape = RoundedCornerShape(30.dp)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .shadow(
                        elevation = 40.dp,
                        shape = shape,
                        ambientColor = Color.Black.copy(alpha = 0.5f),
                        spotColor = Color.Black.copy(alpha = 0.5f)
                    )
                    .clip(shape)
                    .background(DialogSurface)
                    .border(
                        1.dp,
                        Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.16f), Color.White.copy(alpha = 0.04f))),
                        shape
                    )
                    .padding(24.dp)
            ) {
                // ---- Header ----
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(46.dp)
                            .clip(CircleShape)
                            .background(colorResource(R.color.ic_launcher_background)),
                        contentAlignment = Alignment.Center
                    ) {
                        Image(
                            painter = painterResource(R.drawable.ic_launcher_foreground),
                            contentDescription = "Geekify",
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop
                        )
                    }
                    Spacer(Modifier.width(14.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Update available", color = TextPrimary, fontSize = 21.sp, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(2.dp))
                        Text("Geekify $newVersion", color = TextSecondary, fontSize = 13.5.sp, fontWeight = FontWeight.Medium)
                    }
                    Icon(
                        Icons.Outlined.FileDownload,
                        contentDescription = null,
                        tint = Lime,
                        modifier = Modifier.size(24.dp)
                    )
                }

                Spacer(Modifier.height(16.dp))

                // ---- Description ----
                Text(
                    "A new version of Geekify is ready.",
                    color = TextPrimary.copy(alpha = 0.92f),
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    error ?: "Download the latest version for improved performance, fixes and new features.",
                    color = if (error != null) ErrorRed else TextSecondary,
                    fontSize = 14.sp,
                    lineHeight = 20.sp
                )

                Spacer(Modifier.height(24.dp))

                // ---- Versions ----
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    VersionCard("Current version", currentVersion, TextSecondary, highlighted = false, modifier = Modifier.weight(1f))
                    VersionCard("New version", newVersion, Lime, highlighted = true, modifier = Modifier.weight(1f))
                }

                Spacer(Modifier.height(24.dp))

                // ---- Progress / actions ----
                AnimatedContent(
                    targetState = stage,
                    transitionSpec = {
                        (fadeIn(animationSpec = tween(180)) +
                            slideInVertically(
                                animationSpec = tween(240, easing = FastOutSlowInEasing),
                                initialOffsetY = { 10 }
                            )) togetherWith
                            (fadeOut(animationSpec = tween(120)) +
                                slideOutVertically(
                                    animationSpec = tween(160, easing = FastOutSlowInEasing),
                                    targetOffsetY = { -6 }
                                )) using
                            SizeTransform(
                                clip = false,
                                sizeAnimationSpec = { _, _ ->
                                    tween(280, easing = FastOutSlowInEasing)
                                }
                            )
                    },
                    label = "updateStage"
                ) { current ->
                    when (current) {
                        UpdateStage.Available -> Row(
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            PillButton("Later", onLater, primary = false, modifier = Modifier.weight(1f))
                            PillButton("Update", onUpdate, primary = true, modifier = Modifier.weight(1.4f))
                        }
                        UpdateStage.Downloading -> ProgressBlock(
                            label = "Downloading update…",
                            progress = progress
                        )
                        UpdateStage.Installing -> ProgressBlock(
                            label = "Installing update…",
                            progress = null,
                            showPercent = false
                        )
                        UpdateStage.Ready -> Column {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = Lime, modifier = Modifier.size(20.dp))
                                Spacer(Modifier.width(8.dp))
                                Text("Update ready", color = TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                            }
                            Spacer(Modifier.height(16.dp))
                            PillButton("Restart & Install", onInstall, primary = true, modifier = Modifier.fillMaxWidth())
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun VersionCard(
    label: String,
    value: String,
    valueColor: Color,
    highlighted: Boolean,
    modifier: Modifier = Modifier
) {
    val shape = RoundedCornerShape(18.dp)
    Column(
        modifier = modifier
            .clip(shape)
            .background(VersionCardColor)
            .border(1.dp, if (highlighted) Lime.copy(alpha = 0.28f) else Color.White.copy(alpha = 0.06f), shape)
            .padding(horizontal = 16.dp, vertical = 14.dp)
    ) {
        Text(
            text = label.uppercase(),
            color = TextMuted,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            letterSpacing = 1.sp,
            maxLines = 1
        )
        Spacer(Modifier.height(6.dp))
        Text(value, color = valueColor, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

@Composable
private fun PillButton(
    text: String,
    onClick: () -> Unit,
    primary: Boolean,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .height(52.dp)
            .clip(CircleShape)
            .background(if (primary) Lime else Color.Transparent)
            .bouncyClickable(pressedScale = 0.97f, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            color = if (primary) OnAccent else TextSecondary,
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1
        )
    }
}

@Composable
private fun ProgressBlock(label: String, progress: Float?, showPercent: Boolean = true) {
    val animated by animateFloatAsState(
        targetValue = progress ?: 0f,
        animationSpec = tween(300, easing = LinearEasing),
        label = "updateFraction"
    )
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
            if (showPercent && progress != null) {
                Text("${(animated * 100).toInt()}%", color = Lime, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            }
        }
        Spacer(Modifier.height(12.dp))
        LineProgress(progress = if (progress == null) null else animated)
    }
}

/** Thin rounded lime bar. Determinate for 0f..1f, otherwise a segment that glides across the track. */
@Composable
private fun LineProgress(progress: Float?, height: Dp = 6.dp) {
    val trackShape = RoundedCornerShape(50)
    val transition = rememberInfiniteTransition(label = "indeterminate")
    val slide by transition.animateFloat(
        initialValue = -0.4f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1200, easing = LinearEasing), RepeatMode.Restart),
        label = "slide"
    )
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(height)
            .clip(trackShape)
            .background(Color.White.copy(alpha = 0.12f))
    ) {
        if (progress != null) {
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(progress.coerceIn(0f, 1f))
                    .clip(trackShape)
                    .background(Lime)
            )
        } else {
            BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                Box(
                    modifier = Modifier
                        .offset(x = maxWidth * slide)
                        .fillMaxHeight()
                        .width(maxWidth * 0.4f)
                        .clip(trackShape)
                        .background(Lime)
                )
            }
        }
    }
}
