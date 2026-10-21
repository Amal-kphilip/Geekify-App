package com.geekify.android.ui.library

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.geekify.android.appicon.AppIconManager
import com.geekify.android.ui.components.*
import com.geekify.android.ui.theme.GeekifyIcons

@Composable
fun AppIconSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var selectedId by remember { mutableStateOf(AppIconManager.currentIcon(context)) }
    val bottomInset = LocalBottomInset.current
    val scrollState = rememberScrollState()

    IosSettingsPageScaffold(
        title = "App Icon",
        scrollState = scrollState,
        onBack = onBack,
        bottomPadding = bottomInset + 36.dp
    ) {
        IosSettingsSection(
            title = "Home screen icon",
            footer = "The selected icon is applied to Geekify on your device launcher."
        ) {
            AppIconManager.options.forEachIndexed { idx, option ->
                val selected = option.id == selectedId
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            AppIconManager.setIcon(context, option.id)
                            selectedId = option.id
                        }
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Image(
                        painter = painterResource(option.previewRes),
                        contentDescription = option.label,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .size(56.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .border(
                                width = 0.5.dp,
                                color = Color.White.copy(alpha = 0.14f),
                                shape = RoundedCornerShape(14.dp)
                            )
                    )
                    Spacer(Modifier.width(14.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = option.label,
                            color = IosPalette.label,
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            text = if (selected) "Active icon" else "Tap to apply",
                            color = if (selected) IosPalette.systemBlue else IosPalette.secondaryLabel,
                            fontSize = 13.sp,
                            modifier = Modifier.padding(top = 2.dp)
                        )
                    }
                    if (selected) {
                        Box(
                            modifier = Modifier
                                .size(26.dp)
                                .clip(CircleShape)
                                .background(IosPalette.systemBlue),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = GeekifyIcons.Check,
                                contentDescription = "Selected",
                                tint = Color.White,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }
                if (idx < AppIconManager.options.lastIndex) {
                    IosSettingsDivider(insetStart = 86.dp)
                }
            }
        }
    }
}
