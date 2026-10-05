package com.geekify.android.ui.account

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.geekify.android.ui.theme.InkBackground

/** Full-screen Edit profile: change photo, rename, delete account. */
@Composable
fun EditProfileScreen(viewModel: AccountViewModel, onBack: () -> Unit) {
    val state by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val activityContext = context.findActivity()
    val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) viewModel.setAvatar(context, uri)
    }

    // Signing out or deleting the account leaves nothing to edit.
    LaunchedEffect(state.user) { if (state.user == null) onBack() }

    Box(modifier = Modifier.fillMaxSize().background(InkBackground)) {
        ProfileEditor(
            state = state,
            onBack = onBack,
            onPickPhoto = {
                photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            },
            onRemovePhoto = { viewModel.removeAvatar() },
            onSaveName = { viewModel.updateName(it) },
            onDeleteAccount = { password -> viewModel.deleteAccount(password, activityContext) },
            onClearFeedback = { viewModel.clearFeedback() }
        )
    }
}
