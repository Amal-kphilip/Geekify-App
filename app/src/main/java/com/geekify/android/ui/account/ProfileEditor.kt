package com.geekify.android.ui.account

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.geekify.android.ui.components.*
import com.geekify.android.ui.theme.*

/** Edit profile page: photo and display name updates remain backed by the existing AccountViewModel. */
@Composable
internal fun ProfileEditor(
    state: AccountUiState,
    onBack: () -> Unit,
    onPickPhoto: () -> Unit,
    onRemovePhoto: () -> Unit,
    onSaveName: (String) -> Unit,
    onDeleteAccount: (String?) -> Unit,
    onClearFeedback: () -> Unit
) {
    val user = state.user ?: return
    var name by remember(user.uid, user.name) { mutableStateOf(user.name) }
    var showDelete by remember { mutableStateOf(false) }
    val nameChanged = name.trim().isNotEmpty() && name.trim() != user.name
    val saveEnabled = nameChanged && !state.isLoading
    val scrollState = rememberScrollState()
    val bottomInset = LocalBottomInset.current

    LaunchedEffect(Unit) { onClearFeedback() }

    IosSettingsPageScaffold(
        title = "Edit Profile",
        scrollState = scrollState,
        onBack = onBack,
        bottomPadding = bottomInset + 36.dp
    ) {
        val bannerError = state.error?.takeIf { !showDelete }
        val bannerMessage = state.message
        AnimatedVisibility(visible = bannerError != null || bannerMessage != null) {
            val isError = bannerError != null
            val text = bannerError ?: bannerMessage.orEmpty()
            val accentColor = if (isError) IosPalette.systemRed else SettingsVisuals.green
            Surface(
                color = SettingsVisuals.card,
                shape = RoundedCornerShape(18.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp)
                    .border(0.8.dp, accentColor.copy(alpha = 0.42f), RoundedCornerShape(18.dp))
                    .clickable(onClick = onClearFeedback)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = if (isError) GeekifyIcons.ErrorOutline else GeekifyIcons.CheckCircle,
                        contentDescription = null,
                        tint = accentColor,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = text,
                        color = IosPalette.label,
                        fontSize = 14.sp,
                        lineHeight = 19.sp,
                        modifier = Modifier.weight(1f)
                    )
                    Icon(
                        imageVector = GeekifyIcons.Close,
                        contentDescription = "Dismiss",
                        tint = IosPalette.secondaryLabel,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }

        // Listener Profile Hero Card (Settings & Listening Stats surface language)
        Surface(
            color = SettingsVisuals.card,
            shape = IosCardShape,
            modifier = Modifier
                .fillMaxWidth()
                .border(0.8.dp, SettingsVisuals.cardBorder, IosCardShape)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        Brush.linearGradient(
                            listOf(
                                SettingsVisuals.heroStart,
                                SettingsVisuals.heroMiddle,
                                SettingsVisuals.card
                            )
                        )
                    )
                    .padding(horizontal = 24.dp, vertical = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(modifier = Modifier.size(104.dp)) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .clip(CircleShape)
                            .background(SettingsVisuals.blue.copy(alpha = 0.18f))
                            .border(1.dp, SettingsVisuals.cyan.copy(alpha = 0.38f), CircleShape)
                            .bouncyClickable(pressedScale = 0.97f, onClick = onPickPhoto),
                        contentAlignment = Alignment.Center
                    ) {
                        if (user.photoUrl != null) {
                            AsyncImage(
                                model = user.photoUrl,
                                contentDescription = "Profile picture",
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize()
                            )
                        } else {
                            Text(
                                text = user.name.take(1).uppercase(),
                                color = SettingsVisuals.cyan,
                                fontSize = 42.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .size(34.dp)
                            .clip(CircleShape)
                            .background(SettingsVisuals.cardRaised)
                            .border(0.8.dp, SettingsVisuals.cardBorder, CircleShape)
                            .bouncyClickable(pressedScale = 0.92f, onClick = onPickPhoto),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = GeekifyIcons.CameraAlt,
                            contentDescription = "Change profile picture",
                            tint = SettingsVisuals.cyan,
                            modifier = Modifier.size(17.dp)
                        )
                    }
                }

                Spacer(Modifier.height(14.dp))
                Text(
                    text = user.name,
                    color = IosPalette.label,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = (-0.4).sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                user.email?.let { email ->
                    Text(
                        text = email,
                        color = IosPalette.secondaryLabel,
                        fontSize = 13.5.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 3.dp)
                    )
                }

                Spacer(Modifier.height(16.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedButton(
                        onClick = onPickPhoto,
                        shape = CircleShape,
                        border = BorderStroke(0.8.dp, SettingsVisuals.cyan.copy(alpha = 0.55f)),
                        colors = ButtonDefaults.outlinedButtonColors(
                            containerColor = SettingsVisuals.cyan.copy(alpha = 0.10f),
                            contentColor = IosPalette.systemCyan
                        ),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
                    ) {
                        Icon(GeekifyIcons.CameraAlt, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(7.dp))
                        Text("Change photo", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    }
                    if (user.hasCustomPhoto) {
                        OutlinedButton(
                            onClick = onRemovePhoto,
                            shape = CircleShape,
                            border = BorderStroke(0.8.dp, SettingsVisuals.cardBorder),
                            colors = ButtonDefaults.outlinedButtonColors(
                                containerColor = SettingsVisuals.cardRaised,
                                contentColor = IosPalette.secondaryLabel
                            ),
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp)
                        ) {
                            Text("Remove", fontSize = 13.sp, fontWeight = FontWeight.Medium)
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(24.dp))
        IosSettingsSection(
            title = "Profile details",
            footer = "Your display name appears on your profile, library backups, and Listen Together sessions."
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 14.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IosIconBadge(icon = GeekifyIcons.Person, tint = IosPalette.systemBlue)
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Display Name",
                            color = IosPalette.label,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Medium,
                            letterSpacing = (-0.2).sp
                        )
                        Text(
                            text = "Up to 40 characters",
                            color = IosPalette.secondaryLabel,
                            fontSize = 12.5.sp
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = name,
                    onValueChange = { if (it.length <= 40) name = it },
                    placeholder = { Text("Enter your name", color = SettingsVisuals.muted) },
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = SettingsVisuals.cardRaised,
                        unfocusedContainerColor = SettingsVisuals.cardRaised,
                        focusedBorderColor = SettingsVisuals.blue,
                        unfocusedBorderColor = SettingsVisuals.cardBorder,
                        cursorColor = SettingsVisuals.blue,
                        focusedTextColor = IosPalette.label,
                        unfocusedTextColor = IosPalette.label
                    )
                )
                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = { onSaveName(name) },
                    enabled = saveEnabled,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = SettingsVisuals.blue,
                        contentColor = Color.White,
                        disabledContainerColor = SettingsVisuals.cardRaised,
                        disabledContentColor = SettingsVisuals.muted
                    )
                ) {
                    if (state.isLoading && !showDelete) {
                        CircularProgressIndicator(
                            color = Color.White,
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp
                        )
                    } else {
                        Text("Save changes", fontSize = 14.5.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }

            IosSettingsDivider()

            IosSettingsRow(
                icon = GeekifyIcons.Mail,
                tileColor = IosPalette.systemTeal,
                title = "Email Address",
                subtitle = user.email ?: "Not available",
                showChevron = false,
                onClick = null
            )
        }

        Spacer(Modifier.height(24.dp))
        IosSettingsSection(
            title = "Danger zone",
            footer = "Permanently deletes your Geekify account and removes your backed-up liked songs, playlists, and listening history."
        ) {
            IosSettingsRow(
                icon = GeekifyIcons.DeleteForever,
                tileColor = IosPalette.systemRed,
                title = "Delete Account",
                subtitle = "Permanently remove your account and cloud library",
                destructive = true,
                onClick = {
                    onClearFeedback()
                    showDelete = true
                }
            )
        }
    }

    if (showDelete) {
        var password by remember { mutableStateOf("") }
        GeekifyDialog(onDismissRequest = { if (!state.isLoading) showDelete = false }, dismissible = !state.isLoading) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(46.dp)
                        .clip(CircleShape)
                        .background(IosPalette.systemRed.copy(alpha = 0.14f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = GeekifyIcons.DeleteForever,
                        contentDescription = null,
                        tint = IosPalette.systemRed,
                        modifier = Modifier.size(24.dp)
                    )
                }
                Spacer(Modifier.width(14.dp))
                Text(
                    text = "Delete account?",
                    color = IosPalette.label,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold
                )
            }
            Spacer(Modifier.height(12.dp))
            Text(
                text = "This permanently deletes your account and your backed-up liked songs, playlists and history. This can't be undone.",
                color = IosPalette.secondaryLabel,
                fontSize = 14.sp,
                lineHeight = 20.sp
            )
            Spacer(Modifier.height(16.dp))
            if (user.hasPassword) {
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("Confirm your password") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = SettingsVisuals.cardRaised,
                        unfocusedContainerColor = SettingsVisuals.cardRaised,
                        focusedBorderColor = IosPalette.systemRed,
                        unfocusedBorderColor = SettingsVisuals.cardBorder,
                        focusedLabelColor = IosPalette.systemRed,
                        unfocusedLabelColor = IosPalette.secondaryLabel,
                        cursorColor = IosPalette.systemRed,
                        focusedTextColor = IosPalette.label,
                        unfocusedTextColor = IosPalette.label
                    )
                )
            } else {
                Text(
                    text = "You'll be asked to confirm with your Google account.",
                    color = IosPalette.secondaryLabel,
                    fontSize = 13.sp
                )
            }
            state.error?.let {
                Text(
                    text = it,
                    color = IosPalette.systemRed,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(top = 12.dp)
                )
            }
            Spacer(Modifier.height(22.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                DialogPillButton(
                    text = "Cancel",
                    onClick = { showDelete = false },
                    modifier = Modifier.weight(1f),
                    style = DialogButtonStyle.Secondary,
                    enabled = !state.isLoading
                )
                DialogPillButton(
                    text = "Delete",
                    onClick = { onDeleteAccount(if (user.hasPassword) password else null) },
                    modifier = Modifier.weight(1.4f),
                    style = DialogButtonStyle.Destructive,
                    enabled = !user.hasPassword || password.isNotEmpty(),
                    loading = state.isLoading
                )
            }
        }
    }
}
