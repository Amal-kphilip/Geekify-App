package com.geekify.android.ui.components

import android.os.Build
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
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
