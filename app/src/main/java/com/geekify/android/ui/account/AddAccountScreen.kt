package com.geekify.android.ui.account

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Mail
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.graphics.vector.ImageVector
import com.geekify.android.ui.components.CircleIconButton
import com.geekify.android.ui.components.LocalBottomInset
import com.geekify.android.ui.components.bouncyClickable
import com.geekify.android.ui.theme.*

/** Sign in / create account, as a full screen with the same page layout as the other internal screens. */
@Composable
fun AddAccountScreen(viewModel: AccountViewModel, onBack: () -> Unit) {
    val state by viewModel.uiState.collectAsState()
    val activityContext = LocalContext.current.findActivity()
    var isSignUp by remember { mutableStateOf(false) }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    val startedSignedIn = remember { state.user != null }

    LaunchedEffect(Unit) { viewModel.clearFeedback() }
    // Signing in from this screen closes it; being signed in already shows the "sign out first" card instead.
    LaunchedEffect(state.user) { if (!startedSignedIn && state.user != null) onBack() }

    Box(modifier = Modifier.fillMaxSize().background(InkBackground)) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(start = 20.dp, end = 20.dp, top = 14.dp, bottom = 24.dp + LocalBottomInset.current)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircleIconButton(
                    icon = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    onClick = onBack,
                    size = 50.dp
                )
                Text(
                    "Add account",
                    color = TextPrimary,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Medium,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.size(50.dp))
            }

            Spacer(Modifier.height(32.dp))

            val signedIn = state.user
            if (signedIn != null) {
                SignedInCard(
                    name = signedIn.name,
                    email = signedIn.email,
                    onSignOut = {
                        viewModel.signOut()
                    }
                )
            } else {
                Text(
                    if (isSignUp) "Create your account" else "Welcome back",
                    color = TextPrimary,
                    fontSize = 28.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    "Sync playlists and liked music across your devices.",
                    color = TextSecondary,
                    fontSize = 14.sp,
                    modifier = Modifier.padding(top = 6.dp, bottom = 24.dp)
                )

                if (isSignUp) {
                    AuthField(name, { name = it }, "Display name", Icons.Default.Person)
                    Spacer(Modifier.height(12.dp))
                }
                AuthField(email, { email = it }, "Email", Icons.Default.Mail, keyboardType = KeyboardType.Email)
                Spacer(Modifier.height(12.dp))
                AuthField(password, { password = it }, "Password", Icons.Default.Lock, password = true)

                state.error?.let { Text(it, color = ErrorRed, fontSize = 13.sp, modifier = Modifier.padding(top = 12.dp)) }
                state.message?.let { Text(it, color = Lime, fontSize = 13.sp, modifier = Modifier.padding(top = 12.dp)) }

                Spacer(Modifier.height(22.dp))

                val canSubmit = !state.isLoading && email.isNotBlank() && password.isNotEmpty() && (!isSignUp || name.isNotBlank())
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(54.dp)
                        .clip(CircleShape)
                        .background(if (canSubmit) Lime else InkElevated)
                        .then(
                            if (canSubmit) Modifier.bouncyClickable(pressedScale = 0.97f) {
                                if (isSignUp) viewModel.signUpEmail(name, email, password) else viewModel.signInEmail(email, password)
                            } else Modifier
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    if (state.isLoading) {
                        CircularProgressIndicator(color = OnAccent, modifier = Modifier.size(22.dp), strokeWidth = 2.5.dp)
                    } else {
                        Text(
                            if (isSignUp) "Create account" else "Sign in",
                            color = if (canSubmit) OnAccent else TextMuted,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 18.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    HorizontalDivider(modifier = Modifier.weight(1f), color = InkGlassBorder)
                    Text("or", color = TextMuted, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 12.dp))
                    HorizontalDivider(modifier = Modifier.weight(1f), color = InkGlassBorder)
                }

                OutlinedButton(
                    onClick = { viewModel.signInGoogle(activityContext) },
                    enabled = !state.isLoading,
                    modifier = Modifier.fillMaxWidth().height(54.dp),
                    shape = CircleShape,
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = TextPrimary),
                    border = BorderStroke(1.dp, InkGlassBorder)
                ) {
                    Icon(Icons.Default.AccountCircle, contentDescription = null, tint = TextPrimary, modifier = Modifier.size(22.dp))
                    Spacer(Modifier.width(10.dp))
                    Text("Continue with Google", fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                }

                TextButton(
                    onClick = { isSignUp = !isSignUp; viewModel.clearFeedback() },
                    modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 6.dp)
                ) {
                    Text(if (isSignUp) "Already have an account? Sign in" else "Create an account", color = Lime)
                }
                if (!isSignUp) {
                    TextButton(
                        onClick = { if (email.isNotBlank()) viewModel.resetPassword(email) },
                        modifier = Modifier.align(Alignment.CenterHorizontally)
                    ) { Text("Forgot password?", color = TextMuted) }
                }
            }
        }
    }
}

@Composable
private fun SignedInCard(name: String, email: String?, onSignOut: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(28.dp))
            .background(InkPanel)
            .padding(24.dp)
    ) {
        Text("You're signed in", color = TextPrimary, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(6.dp))
        Text(
            listOfNotNull(name, email).joinToString("  •  "),
            color = TextSecondary,
            fontSize = 14.sp
        )
        Spacer(Modifier.height(14.dp))
        Text(
            "To use a different account, sign out first. Your library is backed up and will be waiting when you sign back in.",
            color = TextMuted,
            fontSize = 13.sp,
            lineHeight = 19.sp
        )
        Spacer(Modifier.height(20.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp)
                .clip(CircleShape)
                .background(ErrorRed.copy(alpha = 0.14f))
                .bouncyClickable(pressedScale = 0.97f, onClick = onSignOut),
            contentAlignment = Alignment.Center
        ) {
            Text("Sign out", color = ErrorRed, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun AuthField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    icon: ImageVector,
    password: Boolean = false,
    keyboardType: KeyboardType = KeyboardType.Text
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        leadingIcon = { Icon(icon, null, tint = TextSecondary) },
        singleLine = true,
        visualTransformation = if (password) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
        keyboardOptions = KeyboardOptions(keyboardType = if (password) KeyboardType.Password else keyboardType),
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
}
