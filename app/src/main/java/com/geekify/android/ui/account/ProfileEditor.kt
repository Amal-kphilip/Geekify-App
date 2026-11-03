package com.geekify.android.ui.account

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.geekify.android.ui.components.CircleIconButton
import com.geekify.android.ui.components.DialogButtonStyle
import com.geekify.android.ui.components.DialogPillButton
import com.geekify.android.ui.components.GeekifyDialog
import com.geekify.android.ui.components.LocalBottomInset
import com.geekify.android.ui.components.bouncyClickable
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

    LaunchedEffect(Unit) { onClearFeedback() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CircleIconButton(
                icon = GeekifyIcons.ArrowBack,
                contentDescription = "Back",
                onClick = onBack,
                size = 46.dp
            )
            Text(
                "Edit profile",
                color = TextPrimary,
                fontSize = 21.sp,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.size(46.dp))
        }

        Spacer(Modifier.height(22.dp))
        // Profile overview card matches the rounded analytics cards used across Geekify.
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = Color.Transparent,
            shape = RoundedCornerShape(28.dp)
        ) {
            Column(
                modifier = Modifier
                    .background(
                        Brush.linearGradient(
                            listOf(Color(0xFF21384A), Color(0xFF1C202B), Color(0xFF191A1E))
                        ),
                        RoundedCornerShape(28.dp)
                    )
                    .padding(horizontal = 22.dp, vertical = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    "YOUR LISTENER PROFILE",
                    color = Color(0xFF69C9E7),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.2.sp
                )
                Spacer(Modifier.height(18.dp))
                Box(modifier = Modifier.size(112.dp)) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .clip(CircleShape)
                            .background(Lime.copy(alpha = 0.18f))
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
                                user.name.take(1).uppercase(),
                                color = Lime,
                                fontSize = 46.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .size(38.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF252932))
                            .bouncyClickable(pressedScale = 0.92f, onClick = onPickPhoto),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(GeekifyIcons.CameraAlt, contentDescription = "Change profile picture", tint = Color(0xFF69C9E7), modifier = Modifier.size(19.dp))
                    }
                }
                Spacer(Modifier.height(13.dp))
                Text(
                    user.name,
                    color = TextPrimary,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                user.email?.let {
                    Text(it, color = TextSecondary, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Spacer(Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(
                        onClick = onPickPhoto,
                        shape = CircleShape,
                        border = BorderStroke(1.dp, Color(0xFF69C9E7).copy(alpha = 0.55f)),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF8DDCF0))
                    ) {
                        Icon(GeekifyIcons.CameraAlt, null, modifier = Modifier.size(17.dp))
                        Spacer(Modifier.width(7.dp))
                        Text("Change photo", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    }
                    if (user.hasCustomPhoto) {
                        TextButton(onClick = onRemovePhoto) {
                            Text("Remove", color = TextSecondary, fontSize = 13.sp)
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(20.dp))
        Text(
            "PROFILE DETAILS",
            color = Color(0xFF69C9E7),
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.15.sp,
            modifier = Modifier.padding(start = 5.dp, bottom = 9.dp)
        )
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = Color(0xFF191A1E),
            shape = RoundedCornerShape(24.dp)
        ) {
            Column(Modifier.padding(18.dp)) {
                Text("Display name", color = TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = name,
                    onValueChange = { if (it.length <= 40) name = it },
                    placeholder = { Text("Enter your name", color = TextMuted) },
                    singleLine = true,
                    shape = RoundedCornerShape(17.dp),
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color(0xFF69C9E7),
                        unfocusedBorderColor = InkGlassBorder,
                        cursorColor = Color(0xFF69C9E7),
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary
                    )
                )
                Spacer(Modifier.height(14.dp))
                Text("Email address", color = TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(5.dp))
                Text(user.email ?: "Not available", color = TextSecondary, fontSize = 13.sp)
                Spacer(Modifier.height(17.dp))
                Button(
                    onClick = { onSaveName(name) },
                    enabled = saveEnabled,
                    modifier = Modifier.fillMaxWidth().height(50.dp),
                    shape = CircleShape,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Lime,
                        contentColor = OnAccent,
                        disabledContainerColor = Color(0xFF2B2D33),
                        disabledContentColor = TextMuted
                    )
                ) {
                    if (state.isLoading && !showDelete) {
                        CircularProgressIndicator(color = OnAccent, modifier = Modifier.size(19.dp), strokeWidth = 2.dp)
                    } else {
                        Text("Save changes", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
                state.error?.let { Text(it, color = ErrorRed, fontSize = 12.sp, lineHeight = 17.sp, modifier = Modifier.padding(top = 10.dp)) }
                state.message?.let { Text(it, color = Lime, fontSize = 12.sp, lineHeight = 17.sp, modifier = Modifier.padding(top = 10.dp)) }
            }
        }

        Spacer(Modifier.height(22.dp))
        Text(
            "DANGER ZONE",
            color = ErrorRed,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.15.sp,
            modifier = Modifier.padding(start = 5.dp, bottom = 9.dp)
        )
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = Color(0xFF191A1E),
            shape = RoundedCornerShape(24.dp)
        ) {
            Column(Modifier.padding(18.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(42.dp).clip(CircleShape).background(ErrorRed.copy(alpha = 0.13f)), contentAlignment = Alignment.Center) {
                        Icon(GeekifyIcons.DeleteForever, contentDescription = null, tint = ErrorRed, modifier = Modifier.size(21.dp))
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Delete account", color = TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                        Text("Permanently remove your account and cloud library", color = TextMuted, fontSize = 11.sp, lineHeight = 16.sp)
                    }
                }
                Spacer(Modifier.height(15.dp))
                OutlinedButton(
                    onClick = {
                        onClearFeedback()
                        showDelete = true
                    },
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    shape = CircleShape,
                    border = BorderStroke(1.dp, ErrorRed.copy(alpha = 0.55f)),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = ErrorRed)
                ) { Text("Delete account", fontWeight = FontWeight.SemiBold) }
            }
        }
        Spacer(Modifier.height(24.dp + LocalBottomInset.current))
    }

    if (showDelete) {
        var password by remember { mutableStateOf("") }
        GeekifyDialog(onDismissRequest = { if (!state.isLoading) showDelete = false }, dismissible = !state.isLoading) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier.size(46.dp).clip(CircleShape).background(ErrorRed.copy(alpha = 0.14f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(GeekifyIcons.DeleteForever, contentDescription = null, tint = ErrorRed, modifier = Modifier.size(24.dp))
                }
                Spacer(Modifier.width(14.dp))
                Text("Delete account?", color = TextPrimary, fontSize = 21.sp, fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.height(14.dp))
            Text(
                "This permanently deletes your account and your backed-up liked songs, playlists and history. This can't be undone.",
                color = TextSecondary,
                fontSize = 14.5.sp,
                lineHeight = 21.sp
            )
            Spacer(Modifier.height(16.dp))
            if (user.hasPassword) {
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("Confirm your password") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    shape = RoundedCornerShape(18.dp),
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = ErrorRed,
                        unfocusedBorderColor = InkGlassBorder,
                        focusedLabelColor = ErrorRed,
                        cursorColor = ErrorRed,
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary
                    )
                )
            } else {
                Text("You'll be asked to confirm with your Google account.", color = TextMuted, fontSize = 13.sp)
            }
            state.error?.let { Text(it, color = ErrorRed, fontSize = 13.sp, modifier = Modifier.padding(top = 12.dp)) }
            Spacer(Modifier.height(24.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                DialogPillButton(
                    "Cancel",
                    { showDelete = false },
                    Modifier.weight(1f),
                    DialogButtonStyle.Secondary,
                    enabled = !state.isLoading
                )
                DialogPillButton(
                    "Delete",
                    { onDeleteAccount(if (user.hasPassword) password else null) },
                    Modifier.weight(1.4f),
                    DialogButtonStyle.Destructive,
                    enabled = !user.hasPassword || password.isNotEmpty(),
                    loading = state.isLoading
                )
            }
        }
    }
}
