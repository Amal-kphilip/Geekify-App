package com.geekify.android.ui.library

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.hilt.navigation.compose.hiltViewModel
import com.geekify.android.notifications.NotificationSettingsViewModel
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.geekify.android.BuildConfig
import com.geekify.android.ui.components.AuroraBackground
import com.geekify.android.ui.components.LocalBottomInset
import com.geekify.android.ui.components.CircleIconButton
import com.geekify.android.ui.components.bouncyClickable
import com.geekify.android.ui.theme.*

@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onAudioClick: () -> Unit = {},
    onAppIconClick: () -> Unit = {},
    notificationSettings: NotificationSettingsViewModel = hiltViewModel()
) {
    val notif by notificationSettings.prefs.collectAsState()
    val scrollState = rememberScrollState()
    val bottomInset = LocalBottomInset.current
    AuroraBackground {
        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .padding(horizontal = 20.dp)
                .verticalScroll(scrollState)
        ) {
            Row(Modifier.fillMaxWidth().padding(top = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                CircleIconButton(icon = Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", onClick = onBack, size = 50.dp)
                Text("Settings", color = TextPrimary, fontSize = 20.sp, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center, modifier = Modifier.weight(1f))
                Spacer(Modifier.size(50.dp))
            }
            Spacer(Modifier.height(20.dp))
            SettingsEntry(Icons.Default.GraphicEq, "Audio & equalizer", "Equalizer, tempo, pitch, normalization", onAudioClick)
            Spacer(Modifier.height(14.dp))
            SettingsEntry(Icons.Default.Apps, "App icon", "Choose the Geekify launcher icon", onAppIconClick)
            Spacer(Modifier.height(14.dp))
            Surface(color = InkPanel, shape = RoundedCornerShape(28.dp)) {
                Column(Modifier.padding(horizontal = 18.dp, vertical = 8.dp)) {
                    Text("Notifications", color = TextPrimary, fontSize = 17.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 10.dp))
                    NotificationSwitch("App updates", "Tell me when a new Geekify version is available", notif.updates, notificationSettings::setUpdates)
                    NotificationSwitch("Tips & new features", "Rare notes after an update", notif.tips, notificationSettings::setTips)
                    NotificationSwitch("Library reminders", "Occasionally continue your recent songs (off by default)", notif.engagement, notificationSettings::setEngagement)
                    Text("Playback controls are managed in Android's notification settings.", color = TextMuted, fontSize = 12.sp, modifier = Modifier.padding(bottom = 10.dp))
                }
            }
            Spacer(Modifier.height(14.dp))
            Surface(color = InkElevated, shape = RoundedCornerShape(28.dp)) {
                Column(Modifier.padding(18.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.AutoAwesome, null, tint = Lime)
                        Spacer(Modifier.width(10.dp))
                        Text("Geekify", color = TextPrimary, fontWeight = FontWeight.Bold)
                    }
                    Spacer(Modifier.height(10.dp))
                    Text("A midnight music space built around your queue, library, and listening trail.", color = TextSecondary, fontSize = 14.sp)
                    Spacer(Modifier.height(14.dp))
                    Text("Version ${BuildConfig.VERSION_NAME}", color = TextMuted, fontSize = 12.sp)
                }
            }
            Spacer(Modifier.height(bottomInset + 24.dp))
        }
    }
}

/** A tappable Settings row: round icon chip, title, one-line description and a chevron. */
@Composable
private fun SettingsEntry(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, subtitle: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(28.dp))
            .background(InkPanel)
            .bouncyClickable(pressedScale = 0.98f, onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier.size(46.dp).clip(CircleShape).background(InkElevated),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, null, tint = Lime, modifier = Modifier.size(22.dp))
        }
        Spacer(Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, color = TextPrimary, fontSize = 17.sp, fontWeight = FontWeight.Medium)
            Text(subtitle, color = TextSecondary, fontSize = 13.sp, modifier = Modifier.padding(top = 2.dp))
        }
        Icon(Icons.Default.ChevronRight, null, tint = TextMuted)
    }
}

@Composable
private fun NotificationSwitch(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, color = TextPrimary, fontSize = 15.sp)
            Text(subtitle, color = TextSecondary, fontSize = 12.sp)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
