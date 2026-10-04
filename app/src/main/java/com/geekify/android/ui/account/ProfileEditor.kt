package com.geekify.android.ui.account

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.geekify.android.ui.components.CircleIconButton
import com.geekify.android.ui.components.bouncyClickable
import com.geekify.android.ui.theme.*

/** Edit profile page shown inside the account sheet: change photo, rename, delete account. */
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
    var name by remember(user.name) { mutableStateOf(user.name) }
    var showDelete by remember { mutableStateOf(false) }
    val nameChanged = name.trim().isNotEmpty() && name.trim() != user.name

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
                icon = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "Back to profile menu",
                onClick = onBack,
                size = 46.dp
            )
            Text(
                "Edit profile",
                color = TextPrimary,
                fontSize = 19.sp,
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.size(46.dp))
        }

        Spacer(Modifier.height(28.dp))

        // ---- Photo ----
        Box(modifier = Modifier.align(Alignment.CenterHorizontally).size(124.dp)) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(CircleShape)
                    .background(Lime)
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
                        color = OnAccent,
                        fontSize = 48.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(InkElevated)
                    .bouncyClickable(pressedScale = 0.92f, onClick = onPickPhoto),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Default.CameraAlt, contentDescription = "Change profile picture", tint = Lime, modifier = Modifier.size(20.dp))
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(
            modifier = Modifier.align(Alignment.CenterHorizontally),
            horizontalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            Text(
                "Change photo",
                color = Lime,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.bouncyClickable(onClick = onPickPhoto)
            )
            if (user.hasCustomPhoto) {
                Text(
                    "Remove",
                    color = TextSecondary,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.bouncyClickable(onClick = onRemovePhoto)
                )
            }
        }

        Spacer(Modifier.height(28.dp))

        // ---- Name ----
        OutlinedTextField(
            value = name,
            onValueChange = { if (it.length <= 40) name = it },
            label = { Text("Display name") },
            singleLine = true,
            shape = RoundedCornerShape(18.dp),
            modifier = Modifier.fillMaxWidth(),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Lime,
                unfocusedBorderColor = InkGlassBorder,
                focusedLabelColor = Lime,
                cursorColor = Lime,
                focusedTextColor = TextPrimary,
                unfocusedTextColor = TextPrimary
            )
        )
        user.email?.let {
            Text("Signed in as $it", color = TextMuted, fontSize = 13.sp, modifier = Modifier.padding(start = 4.dp, top = 8.dp))
        }

        Spacer(Modifier.height(16.dp))
        val saveEnabled = nameChanged && !state.isLoading
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp)
                .clip(CircleShape)
                .background(if (saveEnabled) Lime else InkElevated)
                .then(if (saveEnabled) Modifier.bouncyClickable(pressedScale = 0.97f) { onSaveName(name) } else Modifier),
            contentAlignment = Alignment.Center
        ) {
            if (state.isLoading && !showDelete) {
                CircularProgressIndicator(color = OnAccent, modifier = Modifier.size(22.dp), strokeWidth = 2.5.dp)
            } else {
                Text(
                    "Save name",
                    color = if (saveEnabled) OnAccent else TextMuted,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }

        if (!showDelete) {
            state.error?.let { Text(it, color = ErrorRed, fontSize = 13.sp, modifier = Modifier.padding(top = 12.dp)) }
            state.message?.let { Text(it, color = Lime, fontSize = 13.sp, modifier = Modifier.padding(top = 12.dp)) }
        }

        Spacer(Modifier.height(36.dp))
        HorizontalDivider(color = InkGlassBorder)
        Spacer(Modifier.height(20.dp))

        // ---- Delete account ----
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp)
                .clip(CircleShape)
                .background(ErrorRed.copy(alpha = 0.14f))
                .bouncyClickable(pressedScale = 0.97f) {
                    onClearFeedback()
                    showDelete = true
                },
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Default.DeleteForever, contentDescription = null, tint = ErrorRed, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(8.dp))
            Text("Delete account", color = ErrorRed, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        }
        Text(
            "Permanently removes your account and backed-up library.",
            color = TextMuted,
            fontSize = 12.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(top = 10.dp)
        )
        Spacer(Modifier.height(24.dp))
    }

    if (showDelete) {
        var password by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { if (!state.isLoading) showDelete = false },
            containerColor = InkElevated,
            shape = RoundedCornerShape(28.dp),
            title = { Text("Delete account?", color = TextPrimary, fontWeight = FontWeight.SemiBold) },
            text = {
                Column {
                    Text(
                        "This permanently deletes your account and your backed-up liked songs, playlists and history. This can't be undone.",
                        color = TextSecondary,
                        fontSize = 14.sp,
                        lineHeight = 20.sp
                    )
                    Spacer(Modifier.height(14.dp))
                    if (user.hasPassword) {
                        OutlinedTextField(
                            value = password,
                            onValueChange = { password = it },
                            label = { Text("Confirm your password") },
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                            shape = RoundedCornerShape(16.dp),
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
                    state.error?.let { Text(it, color = ErrorRed, fontSize = 13.sp, modifier = Modifier.padding(top = 10.dp)) }
                }
            },
            confirmButton = {
                Button(
                    onClick = { onDeleteAccount(if (user.hasPassword) password else null) },
                    enabled = !state.isLoading && (!user.hasPassword || password.isNotEmpty()),
                    shape = CircleShape,
                    colors = ButtonDefaults.buttonColors(containerColor = ErrorRed, contentColor = Color.White)
                ) {
                    if (state.isLoading) {
                        CircularProgressIndicator(color = Color.White, modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    } else {
                        Text("Delete", fontWeight = FontWeight.SemiBold)
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = { showDelete = false }, enabled = !state.isLoading) {
                    Text("Cancel", color = TextSecondary)
                }
            }
        )
    }
}
