package com.geekify.android.ui.account

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Mail
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.geekify.android.data.auth.SyncState
import com.geekify.android.ui.components.GlassSurface
import com.geekify.android.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountSheet(
    viewModel: AccountViewModel,
    onDismiss: () -> Unit
) {
    val state by viewModel.uiState.collectAsState()
    var isSignUp by remember { mutableStateOf(false) }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = InkPanel,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            val user = state.user
            if (user != null) {
                // Logged In Profile
                Box(
                    modifier = Modifier
                        .size(72.dp)
                        .clip(CircleShape)
                        .background(BrandGradient),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.AccountCircle,
                        contentDescription = null,
                        tint = InkBackground,
                        modifier = Modifier.size(48.dp)
                    )
                }

                Spacer(Modifier.height(12.dp))

                Text(
                    text = user.name,
                    color = TextPrimary,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold
                )

                user.email?.let {
                    Text(
                        text = it,
                        color = TextSecondary,
                        fontSize = 13.sp
                    )
                }

                Spacer(Modifier.height(16.dp))

                // Sync Status Pill
                val (syncIcon, syncText, syncColor) = when (state.syncState) {
                    SyncState.SYNCING -> Triple(Icons.Default.CloudSync, "Syncing library…", BrandMint)
                    SyncState.SAVED -> Triple(Icons.Default.CloudDone, "Library synced to cloud", SuccessGreen)
                    SyncState.ERROR -> Triple(Icons.Default.ErrorOutline, "Sync paused, will retry", ErrorRed)
                    SyncState.IDLE -> Triple(Icons.Default.CloudDone, "Library saved", TextSecondary)
                }

                GlassSurface(
                    modifier = Modifier.fillMaxWidth(),
                    backgroundColor = InkElevated
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(syncIcon, contentDescription = null, tint = syncColor, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(10.dp))
                        Text(syncText, color = TextPrimary, fontSize = 13.sp)
                    }
                }

                Spacer(Modifier.height(24.dp))

                Button(
                    onClick = {
                        viewModel.signOut()
                        onDismiss()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = ErrorRed.copy(alpha = 0.2f)),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Sign out & clear local data", color = ErrorRed, fontWeight = FontWeight.SemiBold)
                }
            } else {
                // Sign In / Sign Up Form
                Text(
                    text = if (isSignUp) "Create Geekify Account" else "Sign In to Geekify",
                    color = TextPrimary,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "Sync your liked tracks and playlists across devices",
                    color = TextSecondary,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(top = 4.dp, bottom = 16.dp)
                )

                if (isSignUp) {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text("Display Name") },
                        leadingIcon = { Icon(Icons.Default.Person, contentDescription = null, tint = TextSecondary) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = BrandViolet,
                            unfocusedBorderColor = InkGlassBorder,
                            focusedTextColor = TextPrimary,
                            unfocusedTextColor = TextPrimary
                        )
                    )
                    Spacer(Modifier.height(10.dp))
                }

                OutlinedTextField(
                    value = email,
                    onValueChange = { email = it },
                    label = { Text("Email") },
                    leadingIcon = { Icon(Icons.Default.Mail, contentDescription = null, tint = TextSecondary) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = BrandViolet,
                        unfocusedBorderColor = InkGlassBorder,
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary
                    )
                )

                Spacer(Modifier.height(10.dp))

                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("Password") },
                    leadingIcon = { Icon(Icons.Default.Lock, contentDescription = null, tint = TextSecondary) },
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = BrandViolet,
                        unfocusedBorderColor = InkGlassBorder,
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary
                    )
                )

                if (state.error != null) {
                    Text(
                        text = state.error ?: "",
                        color = ErrorRed,
                        fontSize = 13.sp,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }

                if (state.message != null) {
                    Text(
                        text = state.message ?: "",
                        color = SuccessGreen,
                        fontSize = 13.sp,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }

                Spacer(Modifier.height(16.dp))

                Button(
                    onClick = {
                        if (isSignUp) viewModel.signUpEmail(name, email, password)
                        else viewModel.signInEmail(email, password)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = BrandViolet),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !state.isLoading
                ) {
                    if (state.isLoading) {
                        CircularProgressIndicator(color = InkBackground, modifier = Modifier.size(20.dp))
                    } else {
                        Text(if (isSignUp) "Sign Up" else "Sign In", color = InkBackground, fontWeight = FontWeight.Bold)
                    }
                }

                Spacer(Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    TextButton(onClick = { isSignUp = !isSignUp }) {
                        Text(
                            text = if (isSignUp) "Already have an account? Sign In" else "Need an account? Sign Up",
                            color = BrandMint,
                            fontSize = 13.sp
                        )
                    }

                    if (!isSignUp) {
                        TextButton(onClick = { if (email.isNotBlank()) viewModel.resetPassword(email) }) {
                            Text("Forgot password?", color = TextMuted, fontSize = 13.sp)
                        }
                    }
                }
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}
