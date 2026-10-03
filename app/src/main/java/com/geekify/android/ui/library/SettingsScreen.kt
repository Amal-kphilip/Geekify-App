package com.geekify.android.ui.library

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.geekify.android.BuildConfig
import com.geekify.android.ui.components.AuroraBackground
import com.geekify.android.ui.theme.*

@Composable
fun SettingsScreen(onBack: () -> Unit) {
    AuroraBackground {
        Column(Modifier.fillMaxSize().statusBarsPadding().padding(horizontal = 20.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Back", tint = TextPrimary) }
                Text("Settings", color = TextPrimary, fontSize = 24.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(20.dp))
            Surface(color = InkElevated, shape = MaterialTheme.shapes.large) {
                Column(Modifier.padding(18.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.AutoAwesome, null, tint = BrandMint)
                        Spacer(Modifier.width(10.dp))
                        Text("Geekify", color = TextPrimary, fontWeight = FontWeight.Bold)
                    }
                    Spacer(Modifier.height(10.dp))
                    Text("A midnight music space built around your queue, library, and listening trail.", color = TextSecondary, fontSize = 14.sp)
                    Spacer(Modifier.height(14.dp))
                    Text("Version ${BuildConfig.VERSION_NAME}", color = TextMuted, fontSize = 12.sp)
                }
            }
        }
    }
}
