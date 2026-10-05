package com.geekify.android.ui.components

import android.os.Build
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.geekify.android.ui.theme.*
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider

/**
 * Dims the screen behind a dialog and, on Android 12+, blurs it.
 * Call it from inside a [Dialog]'s content, since it adjusts that dialog's window.
 */
@Composable
fun DialogBackdrop(dim: Float = 0.55f, blurRadius: Int = 28) {
    val window = (LocalView.current.parent as? DialogWindowProvider)?.window
    SideEffect {
        window?.let { w ->
            w.setDimAmount(dim)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                w.addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
                val attrs = w.attributes
                attrs.blurBehindRadius = blurRadius
                w.attributes = attrs
            }
        }
    }
}

/** Geekify's standard popup: floating dark glass sheet, 30dp corners, soft shadow, dimmed + blurred backdrop. */
@Composable
fun GeekifyDialog(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    dismissible: Boolean = true,
    content: @Composable ColumnScope.() -> Unit
) {
    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = dismissible,
            dismissOnClickOutside = dismissible
        )
    ) {
        DialogBackdrop()
        val shape = RoundedCornerShape(30.dp)
        Box(modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp)) {
            Column(
                modifier = modifier
                    .fillMaxWidth()
                    .shadow(
                        elevation = 40.dp,
                        shape = shape,
                        ambientColor = Color.Black.copy(alpha = 0.5f),
                        spotColor = Color.Black.copy(alpha = 0.5f)
                    )
                    .clip(shape)
                    .background(Color(0xEB101014))
                    .border(
                        1.dp,
                        Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.16f), Color.White.copy(alpha = 0.04f))),
                        shape
                    )
                    .padding(24.dp),
                content = content
            )
        }
    }
}

enum class DialogButtonStyle { Primary, Destructive, Secondary }

/** Pill button used at the bottom of every Geekify dialog. */
@Composable
fun DialogPillButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    style: DialogButtonStyle = DialogButtonStyle.Primary,
    enabled: Boolean = true,
    loading: Boolean = false
) {
    val container = when {
        !enabled && style != DialogButtonStyle.Secondary -> InkElevated
        style == DialogButtonStyle.Primary -> Lime
        style == DialogButtonStyle.Destructive -> ErrorRed
        else -> Color.Transparent
    }
    val content = when {
        !enabled -> TextMuted
        style == DialogButtonStyle.Primary -> OnAccent
        style == DialogButtonStyle.Destructive -> Color.White
        else -> TextSecondary
    }
    Box(
        modifier = modifier
            .height(52.dp)
            .clip(CircleShape)
            .background(container)
            .then(if (enabled && !loading) Modifier.bouncyClickable(pressedScale = 0.97f, onClick = onClick) else Modifier),
        contentAlignment = Alignment.Center
    ) {
        if (loading) {
            CircularProgressIndicator(color = content, modifier = Modifier.size(22.dp), strokeWidth = 2.5.dp)
        } else {
            Text(text, color = content, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
        }
    }
}

/** Title + message + Cancel / confirm. Use [destructive] for removals so the action reads as red. */
@Composable
fun ConfirmDialog(
    title: String,
    message: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    destructive: Boolean = false,
    icon: ImageVector? = null
) {
    GeekifyDialog(onDismissRequest = onDismiss) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) {
                Box(
                    modifier = Modifier
                        .size(46.dp)
                        .clip(CircleShape)
                        .background(if (destructive) ErrorRed.copy(alpha = 0.14f) else InkElevated),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(icon, contentDescription = null, tint = if (destructive) ErrorRed else Lime, modifier = Modifier.size(24.dp))
                }
                Spacer(Modifier.width(14.dp))
            }
            Text(title, color = TextPrimary, fontSize = 21.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        }
        Spacer(Modifier.height(14.dp))
        Text(message, color = TextSecondary, fontSize = 14.5.sp, lineHeight = 21.sp)
        Spacer(Modifier.height(24.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            DialogPillButton("Cancel", onDismiss, Modifier.weight(1f), DialogButtonStyle.Secondary)
            DialogPillButton(
                confirmLabel,
                onConfirm,
                Modifier.weight(1.4f),
                if (destructive) DialogButtonStyle.Destructive else DialogButtonStyle.Primary
            )
        }
    }
}
