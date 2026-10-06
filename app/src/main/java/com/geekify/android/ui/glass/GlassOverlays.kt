package com.geekify.android.ui.glass

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.geekify.android.ui.components.bouncyClickable
import com.geekify.android.ui.theme.TextPrimary

/*
 * Dialogs, popups and the profile panel used to be separate Android windows. A separate window cannot sample the
 * app's backdrop layer, so it could only ever be "dim + blur the whole screen". These overlays are rendered in the
 * SAME composition (a portal into one host at the root of MainScreen), which is what lets them be real glass that
 * samples the scene underneath, and lets the scrim stay subtle.
 */

enum class OverlayEnter { Scale, None }

internal class OverlayEntry(
    val alignment: Alignment,
    val dismissible: Boolean,
    val scrim: Float,
    val enter: OverlayEnter,
    val onDismiss: () -> Unit,
    val content: @Composable BoxScope.() -> Unit
)

@Stable
class GlassOverlayHost {
    internal val entries = mutableStateListOf<OverlayEntry>()
}

val LocalGlassOverlayHost = staticCompositionLocalOf<GlassOverlayHost?> { null }

/** Registers [content] with the host. It is shown while this composable is in the composition and removed when it leaves. */
@Composable
fun GlassOverlayPortal(
    onDismiss: () -> Unit,
    dismissible: Boolean = true,
    scrim: Float = 0.38f,
    alignment: Alignment = Alignment.Center,
    enter: OverlayEnter = OverlayEnter.Scale,
    content: @Composable BoxScope.() -> Unit
) {
    val host = LocalGlassOverlayHost.current ?: return
    val latestContent by rememberUpdatedState(content)
    val latestDismiss by rememberUpdatedState(onDismiss)
    val entry = remember(host, dismissible, scrim, alignment, enter) {
        OverlayEntry(alignment, dismissible, scrim, enter, { latestDismiss() }, { latestContent() })
    }
    DisposableEffect(entry) {
        host.entries.add(entry)
        onDispose { host.entries.remove(entry) }
    }
}

/** Draws every registered overlay, in registration order, above the scene. Place it last in the root Box. */
@Composable
fun GlassOverlays(host: GlassOverlayHost) {
    for (entry in host.entries) key(entry) { OverlayLayer(entry) }
}

@Composable
private fun OverlayLayer(entry: OverlayEntry) {
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { shown = true }
    val reduced = rememberReducedMotion()
    val progress by animateFloatAsState(
        targetValue = if (shown) 1f else 0f,
        animationSpec = if (reduced) snap() else spring(dampingRatio = 0.82f, stiffness = Spring.StiffnessMediumLow),
        label = "overlayEnter"
    )

    // Registered inside the overlay, so Back closes it before anything underneath.
    BackHandler(enabled = true) { if (entry.dismissible) entry.onDismiss() }

    CompositionLocalProvider(LocalGlassDim provides entry.scrim) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = entry.scrim * progress))
                // The scrim is the topmost pointer target: nothing below can be tapped while an overlay is open.
                .pointerInput(entry) { detectTapGestures { if (entry.dismissible) entry.onDismiss() } },
            contentAlignment = entry.alignment
        ) {
            Box(
                modifier = Modifier
                    .imePadding()
                    .graphicsLayer {
                        alpha = progress
                        if (entry.enter == OverlayEnter.Scale) {
                            val s = 0.94f + 0.06f * progress
                            scaleX = s
                            scaleY = s
                        }
                    }
            ) { entry.content(this) }
        }
    }
}

/** Contextual popup menu (replaces DropdownMenu, which lives in its own window and cannot be glass). */
@Composable
fun GlassMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    if (!expanded) return
    GlassOverlayPortal(onDismiss = onDismissRequest, scrim = 0f, alignment = Alignment.TopEnd) {
        Column(
            modifier = modifier
                .statusBarsPadding()
                .padding(top = 76.dp, end = 20.dp)
                .width(224.dp)
                .liquidGlass(RoundedCornerShape(24.dp), GlassStyle.Overlay)
                .consumeTaps()
                .padding(vertical = 8.dp),
            content = content
        )
    }
}

@Composable
fun GlassMenuItem(text: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp) // 48dp touch target
            .bouncyClickable(pressedScale = 0.98f, onClick = onClick)
            .semantics { role = Role.Button }
            .padding(horizontal = 20.dp),
        contentAlignment = Alignment.CenterStart
    ) {
        Text(text, color = TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.Medium)
    }
}
