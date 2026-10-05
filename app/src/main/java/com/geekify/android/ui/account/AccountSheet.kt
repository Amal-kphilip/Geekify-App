package com.geekify.android.ui.account

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil3.compose.AsyncImage
import com.geekify.android.data.auth.SyncState
import com.geekify.android.ui.components.DialogBackdrop
import com.geekify.android.ui.components.bouncyClickable
import com.geekify.android.ui.theme.*

/**
 * The profile menu that slides in from the start edge. It is only a menu: Edit profile and Add account
 * open full screens (like Settings, Recents and Extractor health) so they share the app's page layout.
 */
@Composable
fun AccountSheet(
    viewModel: AccountViewModel,
    onDismiss: () -> Unit,
    onRecentsClick: () -> Unit,
    onSettingsClick: () -> Unit,
    onHealthClick: () -> Unit,
    onEditProfileClick: () -> Unit,
    onAddAccountClick: () -> Unit,
    onListenTogetherClick: () -> Unit
) {
    val state by viewModel.uiState.collectAsState()

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
    ) {
        DialogBackdrop(dim = 0.6f, blurRadius = 24)
        var panelVisible by remember { mutableStateOf(false) }
        LaunchedEffect(Unit) { panelVisible = true }

        Box(modifier = Modifier.fillMaxSize().clickable(onClick = onDismiss)) {
            AnimatedVisibility(
                visible = panelVisible,
                enter = slideInHorizontally(tween(300, easing = FastOutSlowInEasing)) { -it } + fadeIn(tween(200)),
                modifier = Modifier.fillMaxHeight().widthIn(max = 352.dp).fillMaxWidth(0.88f)
            ) {
                Surface(
                    modifier = Modifier.fillMaxSize().clickable(onClick = {}),
                    color = InkPanel,
                    shape = RoundedCornerShape(topEnd = 36.dp, bottomEnd = 36.dp),
                    shadowElevation = 24.dp
                ) {
                    ProfileHub(
                        state, onDismiss,
                        onAccountClick = { onAddAccountClick(); onDismiss() },
                        onProfileClick = {
                            if (state.user != null) onEditProfileClick() else onAddAccountClick()
                            onDismiss()
                        },
                        onListenTogetherClick = { onListenTogetherClick(); onDismiss() },
                        onRecentsClick = { onRecentsClick(); onDismiss() },
                        onSettingsClick = { onSettingsClick(); onDismiss() },
                        onHealthClick = { onHealthClick(); onDismiss() },
                        onSignOut = { viewModel.signOut(); onDismiss() }
                    )
                }
            }
        }
    }
}

@Composable
private fun ProfileHub(
    state: AccountUiState, onDismiss: () -> Unit, onAccountClick: () -> Unit, onProfileClick: () -> Unit,
    onListenTogetherClick: () -> Unit,
    onRecentsClick: () -> Unit, onSettingsClick: () -> Unit, onHealthClick: () -> Unit, onSignOut: () -> Unit
) {
    val user = state.user
    Column(modifier = Modifier.fillMaxSize()) {
        // ---- Header: soft glow, avatar, name, email and an edit / sign-in pill ----
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .drawBehind { drawProfileGlow() }
                .statusBarsPadding()
                .padding(horizontal = 24.dp, vertical = 28.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(76.dp)
                    .clip(CircleShape)
                    .background(Lime)
                    .bouncyClickable(pressedScale = 0.96f, onClick = onProfileClick),
                contentAlignment = Alignment.Center
            ) {
                when {
                    user?.photoUrl != null -> AsyncImage(
                        model = user.photoUrl,
                        contentDescription = "Profile picture",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                    user != null -> Text(user.name.take(1).uppercase(), color = OnAccent, fontSize = 32.sp, fontWeight = FontWeight.SemiBold)
                    else -> Icon(Icons.Default.Person, null, tint = OnAccent, modifier = Modifier.size(40.dp))
                }
            }
            Spacer(Modifier.height(18.dp))
            Text(
                user?.name ?: "Guest listener",
                color = TextPrimary,
                fontWeight = FontWeight.SemiBold,
                fontSize = 26.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(4.dp))
            Text(
                user?.email ?: "Sign in to back up your library",
                color = TextSecondary,
                fontSize = 14.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(16.dp))
            Box(
                modifier = Modifier
                    .clip(CircleShape)
                    .background(if (user == null) Lime else Color.White.copy(alpha = 0.14f))
                    .bouncyClickable(pressedScale = 0.96f, onClick = onProfileClick)
                    .padding(horizontal = 18.dp, vertical = 10.dp)
            ) {
                Text(
                    if (user == null) "Sign in" else "Edit profile",
                    color = if (user == null) OnAccent else TextPrimary,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(vertical = 8.dp)
        ) {
            if (user != null) DrawerRow(Icons.Default.Edit, "Edit profile", onProfileClick)
            DrawerRow(Icons.Default.PersonAdd, if (user == null) "Sign in or sign up" else "Add account", onAccountClick)
            DrawerRow(Icons.Default.Groups, "Listen together", onListenTogetherClick)
            DrawerRow(Icons.Default.History, "Recents", onRecentsClick)
            DrawerRow(Icons.Default.HealthAndSafety, "Extractor health", onHealthClick)
            DrawerRow(Icons.Default.Settings, "Settings and privacy", onSettingsClick)
            if (user != null) {
                Spacer(Modifier.height(8.dp))
                DrawerRow(Icons.AutoMirrored.Filled.Logout, "Sign out", onSignOut, destructive = true)
            }
        }

        val syncText = when (state.syncState) {
            SyncState.SYNCING -> "Syncing your library…"
            SyncState.SAVED -> "Your library is backed up"
            SyncState.ERROR -> "Cloud sync needs attention"
            SyncState.IDLE -> if (user == null) "Local library only" else "Library ready to sync"
        }
        val syncTint = when (state.syncState) {
            SyncState.ERROR -> ErrorRed
            SyncState.SAVED -> Lime
            else -> TextMuted
        }
        Row(
            modifier = Modifier
                .navigationBarsPadding()
                .padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 20.dp)
                .clip(CircleShape)
                .background(InkElevated)
                .padding(horizontal = 16.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                if (state.syncState == SyncState.ERROR) Icons.Default.ErrorOutline else Icons.Default.CloudDone,
                null,
                tint = syncTint,
                modifier = Modifier.size(18.dp)
            )
            Spacer(Modifier.width(10.dp))
            Text(syncText, color = TextSecondary, fontSize = 13.sp)
        }
    }
}

/** Soft violet / pink bloom behind the profile header, fading into the panel colour. */
private fun DrawScope.drawProfileGlow() {
    val w = size.width
    val h = size.height
    drawRect(
        Brush.radialGradient(
            listOf(Color(0xFF8A4DFF).copy(alpha = 0.50f), Color.Transparent),
            center = Offset(w * 0.15f, h * 0.05f),
            radius = w * 1.0f
        )
    )
    drawRect(
        Brush.radialGradient(
            listOf(Color(0xFFFF8FC4).copy(alpha = 0.55f), Color.Transparent),
            center = Offset(w * 0.02f, 0f),
            radius = w * 0.55f
        )
    )
    drawRect(Brush.verticalGradient(0.5f to Color.Transparent, 1f to InkPanel))
}

/** Menu row: round icon chip, label, chevron. [destructive] switches it to the red variant. */
@Composable
private fun DrawerRow(icon: ImageVector, title: String, onClick: () -> Unit, destructive: Boolean = false) {
    val tint = if (destructive) ErrorRed else TextPrimary
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .bouncyClickable(pressedScale = 0.98f, onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(46.dp)
                .clip(CircleShape)
                .background(if (destructive) ErrorRed.copy(alpha = 0.14f) else InkElevated),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, null, tint = tint, modifier = Modifier.size(22.dp))
        }
        Spacer(Modifier.width(16.dp))
        Text(title, color = tint, fontSize = 17.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
        if (!destructive) {
            Icon(Icons.Default.ChevronRight, null, tint = TextMuted, modifier = Modifier.size(22.dp))
        }
    }
}


/** Credential Manager needs a real Activity to show Google's account sheet. */
internal tailrec fun Context.findActivity(): Context = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> this
}
