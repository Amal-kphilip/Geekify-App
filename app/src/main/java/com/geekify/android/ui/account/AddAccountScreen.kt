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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.geekify.android.R
import com.geekify.android.data.auth.KnownAccount
import com.geekify.android.ui.components.*
import com.geekify.android.ui.theme.*

/** Account manager. Firebase maintains a separate SDK-managed session for each saved identity. */
@Composable
fun AddAccountScreen(viewModel: AccountViewModel, onBack: () -> Unit) {
    val state by viewModel.uiState.collectAsState()
    val activityContext = LocalContext.current.findActivity()
    val hadActiveAccountOnEntry = remember { viewModel.uiState.value.user != null }
    var showAuthForm by remember {
        mutableStateOf(!state.isRestoringAccounts && state.user == null && state.knownAccounts.isEmpty())
    }
    var selectedAccount by remember { mutableStateOf<KnownAccount?>(null) }
    var isSignUp by remember { mutableStateOf(false) }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var showSignOutConfirmation by remember { mutableStateOf(false) }
    var signInWasRequested by remember { mutableStateOf(false) }
    var accountPendingRemoval by remember { mutableStateOf<KnownAccount?>(null) }
    val scrollState = rememberScrollState()
    val bottomInset = LocalBottomInset.current

    LaunchedEffect(Unit) { viewModel.clearFeedback() }
    LaunchedEffect(state.isRestoringAccounts, state.user?.uid, state.knownAccounts.size) {
        if (!state.isRestoringAccounts && state.user == null && state.knownAccounts.isEmpty()) {
            showAuthForm = true
        }
    }
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

    val otherAccounts = state.knownAccounts.filterNot { it.uid == state.user?.uid }

    IosSettingsPageScaffold(
        title = stringResource(R.string.accounts_title),
        scrollState = scrollState,
        onBack = onBack,
        bottomPadding = bottomInset + 36.dp
    ) {
        val bannerError = (state.syncError ?: state.error)?.takeIf { !showAuthForm }
        val bannerMessage = state.message?.takeIf { !showAuthForm }
        AnimatedVisibility(visible = bannerError != null || bannerMessage != null) {
            val isError = bannerError != null
            val bannerText = bannerError ?: bannerMessage.orEmpty()
            val accentColor = if (isError) IosPalette.systemRed else SettingsVisuals.green
            Surface(
                color = SettingsVisuals.card,
                shape = RoundedCornerShape(18.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp)
                    .border(0.8.dp, accentColor.copy(alpha = 0.42f), RoundedCornerShape(18.dp))
                    .clickable(onClick = viewModel::clearFeedback)
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
                        text = bannerText,
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

        if (state.isRestoringAccounts) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 20.dp)
                    .border(0.6.dp, SettingsVisuals.cardBorder, IosCardShape),
                color = SettingsVisuals.card,
                shape = IosCardShape
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        color = SettingsVisuals.cyan,
                        strokeWidth = 2.2.dp
                    )
                    Spacer(Modifier.width(14.dp))
                    Column {
                        Text(
                            text = "Restoring saved accounts",
                            color = IosPalette.label,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = "Checking secure sign-in sessions before showing switch actions…",
                            color = IosPalette.secondaryLabel,
                            fontSize = 12.sp,
                            modifier = Modifier.padding(top = 2.dp)
                        )
                    }
                }
            }
        }

        if (state.user != null) {
            IosSettingsSection(
                title = "Current account",
                footer = "This account is active on this device."
            ) {
                KnownAccountCard(
                    account = KnownAccount(
                        uid = state.user!!.uid,
                        email = state.user!!.email,
                        name = state.user!!.name,
                        photoUrl = state.user!!.photoUrl,
                        hasPassword = state.user!!.hasPassword,
                        lastUsedAt = System.currentTimeMillis(),
                        hasCachedSession = true
                    ),
                    active = true,
                    onClick = {}
                )
            }
            Spacer(Modifier.height(24.dp))
        }

        if (otherAccounts.isNotEmpty()) {
            IosSettingsSection(
                title = "Saved accounts",
                footer = "Choose an account to switch, or long-press an account to remove it from this device."
            ) {
                otherAccounts.forEachIndexed { index, account ->
                    KnownAccountCard(
                        account = account,
                        active = false,
                        compact = true,
                        enabled = !state.isRestoringAccounts && !state.isLoading,
                        onClick = {
                            viewModel.clearFeedback()
                            signInWasRequested = !hadActiveAccountOnEntry
                            // Always ask the repository to inspect the Firebase SDK session first.
                            // The visible cached-session flag is only presentation state and can be
                            // stale during app/process restoration. Re-authentication is shown only
                            // after the repository confirms that no reusable session exists.
                            viewModel.switchToKnownAccount(account.uid) { needsAuth ->
                                selectedAccount = needsAuth
                                email = needsAuth.email.orEmpty()
                                password = ""
                                isSignUp = false
                                showAuthForm = true
                            }
                        },
                        onLongClick = {
                            accountPendingRemoval = account
                        }
                    )
                    if (index < otherAccounts.lastIndex) {
                        IosSettingsDivider(insetStart = 76.dp)
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }

        if (!showAuthForm) {
            IosSettingsSection(title = "Add account") {
                IosSettingsRow(
                    icon = GeekifyIcons.PersonAdd,
                    tileColor = IosPalette.systemBlue,
                    title = "Add another account",
                    subtitle = "Sign in with Google or email and password",
                    onClick = if (!state.isRestoringAccounts && !state.isLoading) {
                        {
                            selectedAccount = null
                            email = ""
                            password = ""
                            name = ""
                            isSignUp = false
                            showAuthForm = true
                            viewModel.clearFeedback()
                        }
                    } else null
                )
            }
        } else {
            val sectionTitle = when {
                selectedAccount != null -> "Switch account"
                isSignUp -> "Create account"
                else -> "Add account"
            }
            IosSettingsSection(title = sectionTitle) {
                AccountAuthForm(
                    isSignUp = isSignUp,
                    name = name,
                    email = email,
                    password = password,
                    isLoading = state.isLoading || state.isRestoringAccounts,
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
        }

        if (state.user != null) {
            Spacer(Modifier.height(24.dp))
            IosSettingsSection(
                title = "Account security",
                footer = "Geekify keeps separate Firebase sign-in sessions for saved accounts on this device. Passwords are never stored by Geekify, and each account's library and recommendations stay separate."
            ) {
                IosSettingsRow(
                    icon = GeekifyIcons.Logout,
                    tileColor = IosPalette.systemRed,
                    title = "Sign out of current account",
                    subtitle = "Save library, recents & listening sessions before signing out",
                    destructive = true,
                    onClick = if (!state.isLoading) {
                        { showSignOutConfirmation = true }
                    } else null
                )
            }
        }
    }

    if (showSignOutConfirmation) {
        GeekifyDialog(
            onDismissRequest = { if (!state.isLoading) showSignOutConfirmation = false },
            dismissible = !state.isLoading
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(46.dp)
                        .clip(CircleShape)
                        .background(IosPalette.systemRed.copy(alpha = 0.14f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = GeekifyIcons.Logout,
                        contentDescription = null,
                        tint = IosPalette.systemRed,
                        modifier = Modifier.size(22.dp)
                    )
                }
                Spacer(Modifier.width(14.dp))
                Text(
                    text = "Sign out of Geekify?",
                    color = IosPalette.label,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold
                )
            }
            Spacer(Modifier.height(12.dp))
            Text(
                text = "Geekify will save this account's library, recents and listening sessions first. If another saved account is ready on this device, Geekify will switch to it automatically. Otherwise you'll be signed out. If backup fails, your current account and data stay intact.",
                color = IosPalette.secondaryLabel,
                fontSize = 14.sp,
                lineHeight = 20.sp
            )
            Spacer(Modifier.height(22.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                DialogPillButton(
                    text = "Cancel",
                    onClick = { showSignOutConfirmation = false },
                    modifier = Modifier.weight(1f),
                    style = DialogButtonStyle.Secondary,
                    enabled = !state.isLoading
                )
                DialogPillButton(
                    text = "Sign out",
                    onClick = { viewModel.signOut { showSignOutConfirmation = false } },
                    modifier = Modifier.weight(1.3f),
                    style = DialogButtonStyle.Destructive,
                    enabled = !state.isLoading,
                    loading = state.isLoading
                )
            }
        }
    }

    accountPendingRemoval?.let { account ->
        GeekifyDialog(onDismissRequest = { accountPendingRemoval = null }) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(46.dp)
                        .clip(CircleShape)
                        .background(IosPalette.systemRed.copy(alpha = 0.14f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = GeekifyIcons.DeleteOutline,
                        contentDescription = null,
                        tint = IosPalette.systemRed,
                        modifier = Modifier.size(22.dp)
                    )
                }
                Spacer(Modifier.width(14.dp))
                Text(
                    text = stringResource(R.string.remove_account_title, account.name),
                    color = IosPalette.label,
                    fontSize = 19.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )
            }
            Spacer(Modifier.height(12.dp))
            Text(
                text = stringResource(R.string.remove_account_message, account.name),
                color = IosPalette.secondaryLabel,
                fontSize = 14.sp,
                lineHeight = 20.sp
            )
            Spacer(Modifier.height(22.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                DialogPillButton(
                    text = stringResource(R.string.action_cancel),
                    onClick = { accountPendingRemoval = null },
                    modifier = Modifier.weight(1f),
                    style = DialogButtonStyle.Secondary
                )
                DialogPillButton(
                    text = stringResource(R.string.action_remove),
                    onClick = {
                        val uid = account.uid
                        accountPendingRemoval = null
                        viewModel.forgetAccount(uid)
                    },
                    modifier = Modifier.weight(1.3f),
                    style = DialogButtonStyle.Destructive
                )
            }
        }
    }
}

@Composable
private fun KnownAccountCard(
    account: KnownAccount,
    active: Boolean,
    compact: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null
) {
    val haptics = LocalHapticFeedback.current
    val removeLabel = stringResource(R.string.accessibility_remove_account)
    val cardClickModifier = if (!active && enabled) {
        Modifier
            .bouncyCombinedClickable(
                pressedScale = 0.98f,
                onLongClick = onLongClick?.let { action ->
                    {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        action()
                    }
                },
                onClick = onClick
            )
            .then(
                if (onLongClick != null) {
                    Modifier.semantics {
                        customActions = listOf(
                            CustomAccessibilityAction(removeLabel) {
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                onLongClick()
                                true
                            }
                        )
                    }
                } else Modifier
            )
    } else Modifier

    val backgroundModifier = if (active) {
        Modifier.background(
            Brush.linearGradient(
                listOf(
                    SettingsVisuals.heroStart,
                    SettingsVisuals.heroMiddle,
                    SettingsVisuals.card
                )
            )
        )
    } else {
        Modifier.background(Color.Transparent)
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(backgroundModifier)
            .then(cardClickModifier)
            .padding(horizontal = 16.dp, vertical = if (compact) 13.dp else 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(if (compact) 48.dp else 54.dp)
                .clip(CircleShape)
                .background(
                    if (active) SettingsVisuals.blue.copy(alpha = 0.20f)
                    else SettingsVisuals.cardRaised
                )
                .border(
                    width = 0.7.dp,
                    color = if (active) SettingsVisuals.cyan.copy(alpha = 0.42f) else SettingsVisuals.cardBorder,
                    shape = CircleShape
                ),
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
                Text(
                    text = account.name.take(1).uppercase(),
                    color = SettingsVisuals.cyan,
                    fontSize = if (compact) 19.sp else 22.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
        Spacer(Modifier.width(13.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = account.name,
                color = IosPalette.label,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = (-0.2).sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = account.email ?: "${if (account.hasPassword) "Email account" else "Google account"}",
                color = IosPalette.secondaryLabel,
                fontSize = 12.5.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 1.dp)
            )
            Text(
                text = when {
                    active -> if (account.hasPassword) "Email & password" else "Google"
                    !enabled -> "Checking saved sign-in session…"
                    account.hasCachedSession -> "Ready for quick switching"
                    account.hasPassword -> "One-time password check required"
                    else -> "One-time Google sign-in required"
                },
                color = SettingsVisuals.muted,
                fontSize = 11.5.sp,
                modifier = Modifier.padding(top = 2.dp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(Modifier.width(8.dp))
        if (active) {
            Surface(
                color = SettingsVisuals.green.copy(alpha = 0.16f),
                shape = CircleShape,
                border = BorderStroke(0.6.dp, SettingsVisuals.green.copy(alpha = 0.42f))
            ) {
                Text(
                    text = "ACTIVE",
                    color = SettingsVisuals.green,
                    fontSize = 10.5.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.6.sp,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                )
            }
        } else if (!enabled) {
            CircularProgressIndicator(
                modifier = Modifier.size(16.dp),
                color = SettingsVisuals.cyan,
                strokeWidth = 2.dp
            )
        } else {
            val badgeColor = if (account.hasCachedSession) SettingsVisuals.green else SettingsVisuals.cyan
            Surface(
                color = badgeColor.copy(alpha = 0.14f),
                shape = CircleShape,
                border = BorderStroke(0.5.dp, badgeColor.copy(alpha = 0.38f))
            ) {
                Text(
                    text = if (account.hasCachedSession) "Switch" else "Verify",
                    color = badgeColor,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                )
            }
            Spacer(Modifier.width(6.dp))
            Icon(
                imageVector = GeekifyIcons.ChevronRight,
                contentDescription = null,
                tint = IosPalette.tertiaryLabel,
                modifier = Modifier.size(16.dp)
            )
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
    val selectedGoogleAccount = selectedAccount != null && !selectedAccount.hasPassword

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 18.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IosIconBadge(
                icon = if (selectedAccount != null) GeekifyIcons.SwitchAccount else GeekifyIcons.PersonAdd,
                tint = if (selectedAccount != null) IosPalette.systemCyan else IosPalette.systemBlue
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = when {
                        selectedAccount != null -> "Switch to ${selectedAccount.name}"
                        isSignUp -> "Create an account"
                        else -> "Add another account"
                    },
                    color = IosPalette.label,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = (-0.2).sp
                )
                Text(
                    text = when {
                        selectedGoogleAccount -> "Continue with the Google identity linked to this saved account."
                        selectedAccount != null -> "Re-enter your password to verify this account. Geekify never saves it."
                        else -> "Your current account is backed up before a different account becomes active."
                    },
                    color = IosPalette.secondaryLabel,
                    fontSize = 12.5.sp,
                    lineHeight = 17.sp,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
        }

        if (!selectedGoogleAccount) {
            Spacer(Modifier.height(16.dp))
            if (isSignUp) {
                AccountTextField(
                    value = name,
                    onValueChange = onNameChange,
                    label = "Display name",
                    password = false,
                    keyboardType = KeyboardType.Text
                )
                Spacer(Modifier.height(10.dp))
            }
            AccountTextField(
                value = email,
                onValueChange = onEmailChange,
                label = "Email address",
                password = false,
                keyboardType = KeyboardType.Email,
                enabled = selectedAccount == null
            )
            Spacer(Modifier.height(10.dp))
            AccountTextField(
                value = password,
                onValueChange = onPasswordChange,
                label = "Password",
                password = true,
                keyboardType = KeyboardType.Password
            )
        }

        error?.let {
            Text(
                text = it,
                color = IosPalette.systemRed,
                fontSize = 12.5.sp,
                lineHeight = 17.sp,
                modifier = Modifier.padding(top = 10.dp)
            )
        }
        message?.let {
            Text(
                text = it,
                color = SettingsVisuals.green,
                fontSize = 12.5.sp,
                lineHeight = 17.sp,
                modifier = Modifier.padding(top = 10.dp)
            )
        }

        val canSubmit = !selectedGoogleAccount && !isLoading && email.isNotBlank() && password.isNotEmpty() && (!isSignUp || name.isNotBlank())
        if (!selectedGoogleAccount) {
            Spacer(Modifier.height(16.dp))
            Button(
                onClick = onSubmit,
                enabled = canSubmit,
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
                if (isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = Color.White
                    )
                } else {
                    Text(
                        text = if (isSignUp) "Create account" else if (selectedAccount != null) "Verify and switch" else "Add account",
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 14.5.sp
                    )
                }
            }
        }

        if (!isSignUp && (selectedAccount == null || selectedGoogleAccount)) {
            Spacer(Modifier.height(10.dp))
            OutlinedButton(
                onClick = onGoogle,
                enabled = !isLoading,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
                shape = RoundedCornerShape(14.dp),
                border = BorderStroke(0.8.dp, SettingsVisuals.cardBorder),
                colors = ButtonDefaults.outlinedButtonColors(
                    containerColor = SettingsVisuals.cardRaised,
                    contentColor = IosPalette.label
                )
            ) {
                Text(
                    text = "G",
                    color = SettingsVisuals.cyan,
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 16.sp
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    text = "Continue with Google",
                    color = IosPalette.label,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 14.sp
                )
            }
        }

        Spacer(Modifier.height(8.dp))
        if (selectedAccount == null) {
            TextButton(
                onClick = onToggleSignUp,
                enabled = !isLoading,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = if (isSignUp) "Already have an account? Sign in" else "Create a new account",
                    color = SettingsVisuals.blue,
                    fontSize = 13.5.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        }
        TextButton(
            onClick = onCancel,
            enabled = !isLoading,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = "Cancel",
                color = SettingsVisuals.muted,
                fontSize = 13.5.sp
            )
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
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth(),
        colors = OutlinedTextFieldDefaults.colors(
            focusedContainerColor = SettingsVisuals.cardRaised,
            unfocusedContainerColor = SettingsVisuals.cardRaised,
            disabledContainerColor = SettingsVisuals.cardRaised.copy(alpha = 0.6f),
            focusedBorderColor = SettingsVisuals.blue,
            unfocusedBorderColor = SettingsVisuals.cardBorder,
            disabledBorderColor = SettingsVisuals.cardBorder,
            focusedLabelColor = SettingsVisuals.blue,
            unfocusedLabelColor = IosPalette.secondaryLabel,
            disabledLabelColor = SettingsVisuals.muted,
            cursorColor = SettingsVisuals.blue,
            focusedTextColor = IosPalette.label,
            unfocusedTextColor = IosPalette.label,
            disabledTextColor = IosPalette.secondaryLabel
        )
    )
}
