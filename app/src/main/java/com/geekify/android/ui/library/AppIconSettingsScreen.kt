package com.geekify.android.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.Image
import androidx.compose.ui.res.painterResource
import com.geekify.android.appicon.AppIconManager
import com.geekify.android.ui.components.AuroraBackground
import com.geekify.android.ui.components.CircleIconButton
import com.geekify.android.ui.components.LocalBottomInset
import com.geekify.android.ui.components.bouncyClickable
import com.geekify.android.ui.theme.InkElevated
import com.geekify.android.ui.theme.InkPanel
import com.geekify.android.ui.theme.Lime
import com.geekify.android.ui.theme.TextMuted
import com.geekify.android.ui.theme.TextPrimary
import com.geekify.android.ui.theme.TextSecondary

@Composable
fun AppIconSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var selectedId by remember { mutableStateOf(AppIconManager.currentIcon(context)) }
    val bottomInset = LocalBottomInset.current

    AuroraBackground {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(top = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                CircleIconButton(
                    icon = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    onClick = onBack,
                    size = 50.dp
                )
                Text(
                    text = "App icon",
                    color = TextPrimary,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.size(50.dp))
            }

            Spacer(Modifier.height(18.dp))
            Text(
                "Choose your Geekify launcher icon",
                color = TextPrimary,
                fontSize = 17.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(horizontal = 4.dp)
            )
            Text(
                "The selected icon is used for Geekify on your launcher.",
                color = TextSecondary,
                fontSize = 13.sp,
                modifier = Modifier.padding(start = 4.dp, top = 4.dp)
            )
            Spacer(Modifier.height(16.dp))

            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(bottom = bottomInset + 24.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                items(AppIconManager.options, key = { it.id }) { option ->
                    val selected = option.id == selectedId
                    Surface(
                        color = if (selected) InkElevated else InkPanel,
                        shape = RoundedCornerShape(24.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .bouncyClickable(pressedScale = 0.97f) {
                                AppIconManager.setIcon(context, option.id)
                                selectedId = option.id
                            }
                    ) {
                        Column(
                            modifier = Modifier.padding(10.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(18.dp))
                            ) {
                                Image(
                                    painter = painterResource(option.previewRes),
                                    contentDescription = option.label,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .aspectRatio(1f)
                                )
                                if (selected) {
                                    Box(
                                        modifier = Modifier
                                            .align(Alignment.TopEnd)
                                            .padding(8.dp)
                                            .size(30.dp)
                                            .clip(CircleShape)
                                            .background(Lime),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            Icons.Default.Check,
                                            contentDescription = "Selected",
                                            tint = InkPanel,
                                            modifier = Modifier.size(19.dp)
                                        )
                                    }
                                }
                            }
                            Text(
                                option.label,
                                color = TextPrimary,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Medium,
                                modifier = Modifier.padding(top = 10.dp)
                            )
                            Text(
                                if (selected) "Selected" else "Tap to use",
                                color = if (selected) Lime else TextMuted,
                                fontSize = 11.sp,
                                modifier = Modifier.padding(top = 2.dp, bottom = 4.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}
