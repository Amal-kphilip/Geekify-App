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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.text.KeyboardOptions
import coil3.compose.AsyncImage
import com.geekify.android.data.auth.KnownAccount
import com.geekify.android.ui.components.CircleIconButton
import com.geekify.android.ui.components.LocalBottomInset
import com.geekify.android.ui.components.bouncyClickable
import com.geekify.android.ui.theme.*

/** Account manager. Credentials are never saved; switching asks the provider to authenticate again. */
@Composable
fun AddAccountScreen(viewModel: AccountViewModel, onBack: () -> Unit) {
    val state by viewModel.uiState.collectAsState()
    val activityContext = LocalContext.current.findActivity()
    val hadActiveAccountOnEntry = remember { viewModel.uiState.value.user != null }
    var showAuthForm by remember { mutableStateOf(state.user == null && state.knownAccounts.isEmpty()) }
    var selectedAccount by remember { mutableStateOf<KnownAccount?>(null) }
    var isSignUp by remember { mutableStateOf(false) }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var showSignOutConfirmation by remember { mutableStateOf(false) }
    var signInWasRequested by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { viewModel.clearFeedback() }
    LaunchedEffect(state.user?.uid) {
        val currentUid = state.user?.uid
        if (currentUid != null && signInWasRequested) {
            signInWasRequested = false
            password = ""
            showAuthForm = false
            selectedAccount = null
            isSignUp = false
            if (!hadActiveAccountOnEntry) onBack()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(InkBackground)
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 28.dp + LocalBottomInset.current)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CircleIconButton(
                icon = GeekifyIcons.ArrowBack,
                contentDescription = "Back",
                onClick = onBack,
                size = 48.dp
            )
            Text(
                "Accounts",
                color = TextPrimary,
                fontSize = 22.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.size(48.dp))
        }

        Spacer(Modifier.height(22.dp))
        AccountManagerHero(
            activeName = state.user?.name,
            activeEmail = state.user?.email,
            accountCount = state.knownAccounts.size
        )

        if (state.user != null) {
            Spacer(Modifier.height(18.dp))
            AccountSectionTitle("CURRENT ACCOUNT", "This account is active on this device")
            KnownAccountCard(
                account = KnownAccount(
                    uid = state.user!!.uid,
                    email = state.user!!.email,
                    name = state.user!!.name,
                    photoUrl = state.user!!.photoUrl,
                    hasPassword = state.user!!.hasPassword,
                    lastUsedAt = System.currentTimeMillis()
                ),
                active = true,
                onClick = {}
            )
        }

        val otherAccounts = state.knownAccounts.filterNot { it.uid == state.user?.uid }
        if (otherAccounts.isNotEmpty()) {
            Spacer(Modifier.height(22.dp))
            AccountSectionTitle("SAVED ACCOUNTS", "Choose an account to switch")
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(26.dp))
                    .background(InkPanel)
                    .padding(horizontal = 14.dp, vertical = 6.dp)
            ) {
                otherAccounts.forEachIndexed { index, account ->
                    KnownAccountCard(
                        account = account,
                        active = false,
                        compact = true,
                        onClick = {
                            viewModel.clearFeedback()
                            if (account.hasPassword) {
                                selectedAccount = account
                                email = account.email.orEmpty()
                                password = ""
                                isSignUp = false
                                showAuthForm = true
                            } else {
                                selectedAccount = account
                                signInWasRequested = !hadActiveAccountOnEntry
                                viewModel.signInGoogle(activityContext, expectedUid = account.uid)
                            }
                        }
                    )
                    if (index < otherAccounts.lastIndex) {
                        HorizontalDivider(color = InkGlassBorder.copy(alpha = 0.65f), modifier = Modifier.padding(start = 66.dp))
                    }
                }
            }
        }

        Spacer(Modifier.height(22.dp))
        if (!showAuthForm) {
            PrimaryAccountAction(
                title = "Add another account",
                subtitle = "Sign in with Google or email and password",
                onClick = {
                    selectedAccount = null
                    email = ""
                    password = ""
                    name = ""
                    isSignUp = false
                    showAuthForm = true
                    viewModel.clearFeedback()
                }
            )
        } else {
            AccountAuthForm(
                isSignUp = isSignUp,
                name = name,
                email = email,
                password = password,
                isLoading = state.isLoading,
                selectedAccount = selectedAccount,
                error = state.error,
                message = state.message,
                onNameChange = { name = it.take(40) },
                onEmailChange = { email = it },
                onPasswordChange = { password = it },
                onSubmit = {
                    if (isSignUp) viewModel.signUpEmail(name, email, password)
                    else {
                        signInWasRequested = !hadActiveAccountOnEntry
                        viewModel.signInEmail(email, password, expectedUid = selectedAccount?.uid)
                    }
                },
                onGoogle = {
                    signInWasRequested = !hadActiveAccountOnEntry
                    viewModel.signInGoogle(activityContext, expectedUid = selectedAccount?.uid)
                },
                onToggleSignUp = {
                    isSignUp = !isSignUp
                    selectedAccount = null
                    viewModel.clearFeedback()
                },
                onCancel = {
                    showAuthForm = false
                    selectedAccount = null
                    password = ""
                    viewModel.clearFeedback()
                }
            )
        }

        if (state.user != null) {
            Spacer(Modifier.height(22.dp))
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = InkPanel,
                shape = RoundedCornerShape(24.dp)
            ) {
                Column(Modifier.padding(18.dp)) {
                    Text("Account security", color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Geekify backs up the active library before switching accounts. Passwords and sign-in tokens are never stored by the account switcher.",
                        color = TextSecondary,
                        fontSize = 13.sp,
                        lineHeight = 19.sp
                    )
                    Spacer(Modifier.height(14.dp))
                    OutlinedButton(
                        onClick = { showSignOutConfirmation = true },
                        enabled = !state.isLoading,
                        modifier = Modifier.fillMaxWidth().height(48.dp),
                        shape = CircleShape,
                        border = BorderStroke(1.dp, ErrorRed.copy(alpha = 0.5f)),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = ErrorRed)
                    ) { Text("Sign out of current account", fontWeight = FontWeight.SemiBold) }
                }
            }
        }

        (state.syncError ?: state.error)?.takeIf { state.user != null && state.error == null }?.let {
            Text(it, color = ErrorRed, fontSize = 12.sp, modifier = Modifier.padding(top = 12.dp))
        }
    }

    if (showSignOutConfirmation) {
        AlertDialog(
            onDismissRequest = { if (!state.isLoading) showSignOutConfirmation = false },
            title = { Text("Sign out of Geekify?") },
            text = {
                Text(
                    "Geekify will back up this account's library, recents and listening sessions first. " +
                        "If backup fails, you'll remain signed in and your data will stay on this device."
                )
            },
            confirmButton = {
                TextButton(
                    enabled = !state.isLoading,
                    onClick = { viewModel.signOut { showSignOutConfirmation = false } }
                ) {
                    if (state.isLoading) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    else Text("Sign out", color = ErrorRed)
                }
            },
            dismissButton = {
                TextButton(enabled = !state.isLoading, onClick = { showSignOutConfirmation = false }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun AccountManagerHero(activeName: String?, activeEmail: String?, accountCount: Int) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(28.dp))
            .background(
                Brush.linearGradient(listOf(Color(0xFF1E3442), Color(0xFF1C1D28), Color(0xFF191A1E)))
            )
            .padding(22.dp)
    ) {
        Text("YOUR GEEKIFY ACCOUNTS", color = Color(0xFF69C9E7), fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.1.sp)
        Spacer(Modifier.height(10.dp))
        Text(
            if (activeName != null) "Switch without mixing libraries" else "Keep your music lives separate",
            color = TextPrimary,
            fontSize = 23.sp,
            lineHeight = 28.sp,
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.height(6.dp))
        Text(
            if (activeName != null) "Active: ${activeEmail ?: activeName}. Each account loads its own synced library and listening history."
            else "Add an account or sign back in to restore its library, history and recommendations.",
            color = TextSecondary,
            fontSize = 13.sp,
            lineHeight = 19.sp
        )
        Spacer(Modifier.height(12.dp))
        Text("$accountCount account${if (accountCount == 1) "" else "s"} saved on this device", color = Color(0xFF9DDCF0), fontSize = 12.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun AccountSectionTitle(title: String, subtitle: String) {
    Column(Modifier.padding(start = 4.dp, bottom = 10.dp)) {
        Text(title, color = Color(0xFF69C9E7), fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.1.sp)
        Text(subtitle, color = TextMuted, fontSize = 12.sp, modifier = Modifier.padding(top = 3.dp))
    }
}

@Composable
private fun KnownAccountCard(
    account: KnownAccount,
    active: Boolean,
    compact: Boolean = false,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(if (compact) 18.dp else 24.dp))
            .background(if (compact) Color.Transparent else InkPanel)
            .then(if (!active) Modifier.bouncyClickable(pressedScale = 0.98f, onClick = onClick) else Modifier)
            .padding(horizontal = if (compact) 4.dp else 16.dp, vertical = if (compact) 12.dp else 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier.size(if (compact) 46.dp else 54.dp).clip(CircleShape).background(Lime.copy(alpha = 0.16f)),
            contentAlignment = Alignment.Center
        ) {
            if (!account.photoUrl.isNullOrBlank()) {
                AsyncImage(
                    model = account.photoUrl,
                    contentDescription = "${account.name} profile picture",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize().clip(CircleShape)
                )
            } else {
                Text(account.name.take(1).uppercase(), color = Lime, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            }
        }
        Spacer(Modifier.width(13.dp))
        Column(Modifier.weight(1f)) {
            Text(account.name, color = TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(account.email ?: "${if (account.hasPassword) "Email account" else "Google account"}", color = TextSecondary, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (!compact) Text(if (account.hasPassword) "Email & password" else "Google", color = TextMuted, fontSize = 11.sp, modifier = Modifier.padding(top = 3.dp))
        }
        Spacer(Modifier.width(8.dp))
        if (active) {
            Text("ACTIVE", color = Color(0xFF53D69A), fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.8.sp)
        } else {
            Text("Switch", color = Color(0xFF69C9E7), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun PrimaryAccountAction(title: String, subtitle: String, onClick: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth().bouncyClickable(pressedScale = 0.98f, onClick = onClick),
        color = InkPanel,
        shape = RoundedCornerShape(24.dp)
    ) {
        Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(46.dp).clip(CircleShape).background(Color(0xFF69C9E7).copy(alpha = 0.14f)), contentAlignment = Alignment.Center) {
                Text("+", color = Color(0xFF69C9E7), fontSize = 30.sp, fontWeight = FontWeight.Light)
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(title, color = TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Text(subtitle, color = TextSecondary, fontSize = 12.sp, modifier = Modifier.padding(top = 3.dp))
            }
        }
    }
}

@Composable
private fun AccountAuthForm(
    isSignUp: Boolean,
    name: String,
    email: String,
    password: String,
    isLoading: Boolean,
    selectedAccount: KnownAccount?,
    error: String?,
    message: String?,
    onNameChange: (String) -> Unit,
    onEmailChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onGoogle: () -> Unit,
    onToggleSignUp: () -> Unit,
    onCancel: () -> Unit
) {
    Surface(modifier = Modifier.fillMaxWidth(), color = InkPanel, shape = RoundedCornerShape(26.dp)) {
        Column(Modifier.padding(20.dp)) {
            Text(
                when {
                    selectedAccount != null -> "Switch to ${selectedAccount.name}"
                    isSignUp -> "Create an account"
                    else -> "Add another account"
                },
                color = TextPrimary,
                fontSize = 21.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                if (selectedAccount != null) "Re-enter your password to verify this account. Geekify never saves it."
                else "Your current account is backed up before a different account becomes active.",
                color = TextSecondary,
                fontSize = 13.sp,
                lineHeight = 19.sp,
                modifier = Modifier.padding(top = 5.dp, bottom = 18.dp)
            )

            if (isSignUp) {
                AccountTextField(name, onNameChange, "Display name", password = false, keyboardType = KeyboardType.Text)
                Spacer(Modifier.height(11.dp))
            }
            AccountTextField(email, onEmailChange, "Email address", password = false, keyboardType = KeyboardType.Email, enabled = selectedAccount == null)
            Spacer(Modifier.height(11.dp))
            AccountTextField(password, onPasswordChange, "Password", password = true, keyboardType = KeyboardType.Password)

            if (!isSignUp && selectedAccount == null) {
                TextButton(onClick = onGoogle, enabled = !isLoading, modifier = Modifier.fillMaxWidth()) {
                    Text("Continue with Google", color = Color(0xFF69C9E7), fontWeight = FontWeight.SemiBold)
                }
            }
            error?.let { Text(it, color = ErrorRed, fontSize = 12.sp, lineHeight = 17.sp, modifier = Modifier.padding(top = 8.dp)) }
            message?.let { Text(it, color = Lime, fontSize = 12.sp, lineHeight = 17.sp, modifier = Modifier.padding(top = 8.dp)) }

            Spacer(Modifier.height(17.dp))
            val canSubmit = !isLoading && email.isNotBlank() && password.isNotEmpty() && (!isSignUp || name.isNotBlank())
            Button(
                onClick = onSubmit,
                enabled = canSubmit,
                modifier = Modifier.fillMaxWidth().height(50.dp),
                shape = CircleShape,
                colors = ButtonDefaults.buttonColors(containerColor = Lime, contentColor = OnAccent, disabledContainerColor = InkElevated, disabledContentColor = TextMuted)
            ) {
                if (isLoading) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = OnAccent)
                else Text(if (isSignUp) "Create account" else if (selectedAccount != null) "Verify and switch" else "Add account", fontWeight = FontWeight.SemiBold)
            }

            if (selectedAccount == null) {
                TextButton(onClick = onToggleSignUp, enabled = !isLoading, modifier = Modifier.fillMaxWidth()) {
                    Text(if (isSignUp) "Already have an account? Sign in" else "Create a new account", color = TextSecondary)
                }
            }
            TextButton(onClick = onCancel, enabled = !isLoading, modifier = Modifier.fillMaxWidth()) {
                Text("Cancel", color = TextMuted)
            }
        }
    }
}

@Composable
private fun AccountTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    password: Boolean,
    keyboardType: KeyboardType,
    enabled: Boolean = true
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        enabled = enabled,
        label = { Text(label) },
        singleLine = true,
        visualTransformation = if (password) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        shape = RoundedCornerShape(17.dp),
        modifier = Modifier.fillMaxWidth(),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = Color(0xFF69C9E7),
            unfocusedBorderColor = InkGlassBorder,
            focusedLabelColor = Color(0xFF69C9E7),
            cursorColor = Color(0xFF69C9E7),
            focusedTextColor = TextPrimary,
            unfocusedTextColor = TextPrimary,
            disabledTextColor = TextSecondary,
            disabledBorderColor = InkGlassBorder
        )
    )
}
