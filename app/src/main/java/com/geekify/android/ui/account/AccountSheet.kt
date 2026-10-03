package com.geekify.android.ui.account

import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import coil3.compose.AsyncImage
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.geekify.android.data.auth.SyncState
import com.geekify.android.ui.components.GlassSurface
import com.geekify.android.ui.components.bouncyClickable
import com.geekify.android.ui.theme.*

/** A start-side profile hub, deliberately distinct from the main content sheet. */
@Composable
fun AccountSheet(
    viewModel: AccountViewModel,
    onDismiss: () -> Unit,
    onRecentsClick: () -> Unit,
    onSettingsClick: () -> Unit,
    onHealthClick: () -> Unit
) {
    val state by viewModel.uiState.collectAsState()
    var showAuthForm by remember { mutableStateOf(false) }
    var isSignUp by remember { mutableStateOf(false) }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }

    LaunchedEffect(state.user) { if (state.user != null) showAuthForm = false }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
    ) {
        Box(
            modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.62f)).clickable(onClick = onDismiss)
        ) {
            Surface(
                modifier = Modifier.fillMaxHeight().widthIn(max = 352.dp).fillMaxWidth(0.88f).clickable(onClick = {}),
                color = InkPanel,
                shape = RectangleShape,
                tonalElevation = 12.dp
            ) {
                if (showAuthForm) {
                    AccountAuthForm(
                        state, isSignUp, email, password, name,
                        onBack = { showAuthForm = false }, onSignUpChange = { isSignUp = it },
                        onEmailChange = { email = it }, onPasswordChange = { password = it }, onNameChange = { name = it },
                        onSubmit = { if (isSignUp) viewModel.signUpEmail(name, email, password) else viewModel.signInEmail(email, password) },
                        onGoogleSignIn = { viewModel.signInGoogle() },
                        onResetPassword = { if (email.isNotBlank()) viewModel.resetPassword(email) }
                    )
                } else {
                    ProfileHub(
                        state, onDismiss, onAccountClick = { showAuthForm = true },
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
    state: AccountUiState, onDismiss: () -> Unit, onAccountClick: () -> Unit,
    onRecentsClick: () -> Unit, onSettingsClick: () -> Unit, onHealthClick: () -> Unit, onSignOut: () -> Unit
) {
    val user = state.user
    Column(modifier = Modifier.fillMaxSize().statusBarsPadding()) {
        // Header: avatar, bold name, "View profile" — tapping opens the existing account screen.
        Row(
            modifier = Modifier.fillMaxWidth().clickable(onClick = onAccountClick).padding(horizontal = 16.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(Modifier.size(52.dp).clip(CircleShape).background(BrandGradient), contentAlignment = Alignment.Center) {
                if (user?.photoUrl != null) {
                    AsyncImage(model = user.photoUrl, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                } else {
                    Icon(Icons.Default.AccountCircle, null, tint = InkBackground, modifier = Modifier.size(38.dp))
                }
            }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(user?.name ?: "Guest listener", color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 22.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(if (user == null) "Sign in to back up your library" else "View profile", color = TextSecondary, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        HorizontalDivider(color = InkGlassBorder)

        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            DrawerRow(Icons.Default.Add, "Add account", onAccountClick)
            DrawerRow(Icons.Default.History, "Recents", onRecentsClick)
            DrawerRow(Icons.Default.HealthAndSafety, "Extractor health", onHealthClick)
            DrawerRow(Icons.Default.Settings, "Settings and privacy", onSettingsClick)
            if (user != null) DrawerRow(Icons.AutoMirrored.Filled.Logout, "Sign out", onSignOut, tint = ErrorRed)
        }

        val syncText = when (state.syncState) {
            SyncState.SYNCING -> "Syncing your library…"
            SyncState.SAVED -> "Your library is backed up"
            SyncState.ERROR -> "Cloud sync needs attention"
            SyncState.IDLE -> if (user == null) "Local library only" else "Library ready to sync"
        }
        Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(if (state.syncState == SyncState.ERROR) Icons.Default.ErrorOutline else Icons.Default.CloudDone, null, tint = if (state.syncState == SyncState.ERROR) ErrorRed else TextMuted, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(syncText, color = TextMuted, fontSize = 13.sp)
        }
    }
}

/** Large, airy drawer row: outlined-weight white icon on the left, 20sp label, no chevron. */
@Composable
private fun DrawerRow(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, onClick: () -> Unit, tint: Color = TextPrimary) {
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 72.dp).bouncyClickable(pressedScale = 0.98f, onClick = onClick).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(28.dp))
        Spacer(Modifier.width(24.dp))
        Text(title, color = tint, fontSize = 20.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun AccountAuthForm(
    state: AccountUiState, isSignUp: Boolean, email: String, password: String, name: String,
    onBack: () -> Unit, onSignUpChange: (Boolean) -> Unit, onEmailChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit, onNameChange: (String) -> Unit, onSubmit: () -> Unit,
    onGoogleSignIn: () -> Unit, onResetPassword: () -> Unit
) {
    Column(Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState()).padding(20.dp)) {
        IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Back to profile menu", tint = TextPrimary) }
        Spacer(Modifier.height(8.dp))
        Text(if (isSignUp) "Create your account" else "Welcome back", color = TextPrimary, fontSize = 23.sp, fontWeight = FontWeight.Bold)
        Text("Sync playlists and liked music across your devices.", color = TextSecondary, fontSize = 13.sp, modifier = Modifier.padding(top = 5.dp, bottom = 20.dp))
        if (isSignUp) { AccountTextField(name, onNameChange, "Display name", Icons.Default.Person); Spacer(Modifier.height(10.dp)) }
        AccountTextField(email, onEmailChange, "Email", Icons.Default.Mail)
        Spacer(Modifier.height(10.dp))
        OutlinedTextField(value = password, onValueChange = onPasswordChange, label = { Text("Password") }, leadingIcon = { Icon(Icons.Default.Lock, null, tint = TextSecondary) }, visualTransformation = PasswordVisualTransformation(), singleLine = true, modifier = Modifier.fillMaxWidth(), colors = accountTextFieldColors())
        state.error?.let { Text(it, color = ErrorRed, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp)) }
        state.message?.let { Text(it, color = SuccessGreen, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp)) }
        Spacer(Modifier.height(18.dp))
        Button(onClick = onSubmit, enabled = !state.isLoading, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp), colors = ButtonDefaults.buttonColors(containerColor = BrandViolet)) {
            if (state.isLoading) CircularProgressIndicator(color = InkBackground, modifier = Modifier.size(20.dp)) else Text(if (isSignUp) "Create account" else "Sign in", color = InkBackground, fontWeight = FontWeight.Bold)
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            HorizontalDivider(modifier = Modifier.weight(1f), color = InkGlassBorder)
            Text("or", color = TextMuted, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 12.dp))
            HorizontalDivider(modifier = Modifier.weight(1f), color = InkGlassBorder)
        }
        OutlinedButton(
            onClick = onGoogleSignIn,
            enabled = !state.isLoading,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = TextPrimary),
            border = BorderStroke(1.dp, InkGlassBorder)
        ) {
            Icon(Icons.Default.AccountCircle, contentDescription = null, tint = TextPrimary, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
            Text("Continue with Google", fontWeight = FontWeight.SemiBold)
        }
        TextButton(onClick = { onSignUpChange(!isSignUp) }, modifier = Modifier.align(Alignment.CenterHorizontally)) { Text(if (isSignUp) "Already have an account? Sign in" else "Create an account", color = BrandMint) }
        if (!isSignUp) TextButton(onClick = onResetPassword, modifier = Modifier.align(Alignment.CenterHorizontally)) { Text("Forgot password?", color = TextMuted) }
    }
}

@Composable
private fun AccountTextField(value: String, onValueChange: (String) -> Unit, label: String, icon: androidx.compose.ui.graphics.vector.ImageVector) {
    OutlinedTextField(value = value, onValueChange = onValueChange, label = { Text(label) }, leadingIcon = { Icon(icon, null, tint = TextSecondary) }, singleLine = true, modifier = Modifier.fillMaxWidth(), colors = accountTextFieldColors())
}

@Composable
private fun accountTextFieldColors() = OutlinedTextFieldDefaults.colors(focusedBorderColor = BrandViolet, unfocusedBorderColor = InkGlassBorder, focusedTextColor = TextPrimary, unfocusedTextColor = TextPrimary)
