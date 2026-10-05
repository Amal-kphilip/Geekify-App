package com.geekify.android.listentogether

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.geekify.android.core.UiState
import com.geekify.android.ui.components.CircleIconButton
import com.geekify.android.ui.components.LocalBottomInset
import com.geekify.android.ui.components.bouncyClickable
import com.geekify.android.ui.theme.*

private val Amber = Color(0xFFFCD34D)

/** Create or join a listening room, see the connection status, and leave. */
@Composable
fun ListenTogetherScreen(
    onBack: () -> Unit,
    viewModel: ListenTogetherViewModel = hiltViewModel()
) {
    val ui by viewModel.state.collectAsState()
    val nowPlaying by viewModel.nowPlaying.collectAsState()

    Box(modifier = Modifier.fillMaxSize().background(InkBackground)) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(start = 20.dp, end = 20.dp, top = 14.dp, bottom = 24.dp + LocalBottomInset.current)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircleIconButton(Icons.AutoMirrored.Filled.ArrowBack, "Back", onBack, size = 50.dp)
                Text(
                    "Listen together",
                    color = TextPrimary,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Medium,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.size(50.dp))
            }
            Spacer(Modifier.height(24.dp))

            when (val state = ui) {
                UiState.Loading -> Box(Modifier.fillMaxWidth().height(160.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = Lime)
                }
                is UiState.Error -> Text(state.message, color = ErrorRed, fontSize = 14.sp)
                is UiState.Success -> {
                    val room = state.data
                    StatusChip(room.status)
                    Spacer(Modifier.height(18.dp))
                    if (room.room == null) {
                        JoinForm(room.serverUrl, viewModel)
                    } else {
                        InRoom(room, nowPlaying, viewModel)
                    }
                    room.error?.let {
                        Text(it, color = ErrorRed, fontSize = 13.sp, modifier = Modifier.padding(top = 16.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusChip(status: ConnectionStatus) {
    val (color, text) = when (status) {
        ConnectionStatus.Connected -> Lime to "Connected"
        ConnectionStatus.Connecting -> Amber to "Connecting…"
        is ConnectionStatus.Reconnecting -> Amber to "Reconnecting (attempt ${status.attempt}) in ${(status.inMs + 999) / 1000}s"
        ConnectionStatus.Disconnected -> TextMuted to "Not in a room"
    }
    Row(
        modifier = Modifier
            .clip(CircleShape)
            .background(InkElevated)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(10.dp))
        Text(text, color = TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun JoinForm(savedServer: String, vm: ListenTogetherViewModel) {
    var server by remember(savedServer) { mutableStateOf(savedServer) }
    var code by remember { mutableStateOf("") }

    Text("Listen in sync with friends", color = TextPrimary, fontSize = 26.sp, fontWeight = FontWeight.SemiBold)
    Text(
        "Everyone in a room hears the same song at the same moment. You need a Geekify relay server address.",
        color = TextSecondary,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        modifier = Modifier.padding(top = 6.dp, bottom = 22.dp)
    )

    FormField(server, { server = it; vm.clearError() }, "Server address", "wss://your-server/ws", KeyboardType.Uri, KeyboardCapitalization.None)
    Spacer(Modifier.height(12.dp))
    FormField(code, { code = it.uppercase().take(ListenTogetherManager.MAX_ROOM_CHARS); vm.clearError() }, "Room code", "ABC123", KeyboardType.Text, KeyboardCapitalization.Characters)

    Spacer(Modifier.height(22.dp))
    val canJoin = server.isNotBlank() && code.length >= ListenTogetherManager.MIN_ROOM_CHARS
    PillButton("Join room", enabled = canJoin, primary = true) { vm.join(server, code) }
    Spacer(Modifier.height(12.dp))
    PillButton("Create a new room", enabled = server.isNotBlank(), primary = false) {
        val fresh = vm.newRoomCode()
        code = fresh
        vm.join(server, fresh)
    }
}

@Composable
private fun InRoom(room: RoomUiState, nowPlaying: String?, vm: ListenTogetherViewModel) {
    val context = LocalContext.current
    val code = room.room.orEmpty()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(28.dp))
            .background(InkPanel)
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("ROOM CODE", color = TextMuted, fontSize = 12.sp, letterSpacing = 1.sp, fontWeight = FontWeight.Medium)
        Text(code, color = Lime, fontSize = 40.sp, fontWeight = FontWeight.Bold, letterSpacing = 4.sp, modifier = Modifier.padding(vertical = 8.dp))
        Text(
            if (room.peers <= 1) "Waiting for friends to join" else "${room.peers} people listening",
            color = TextSecondary,
            fontSize = 14.sp
        )
        if (nowPlaying != null) {
            Text(
                "Now playing: $nowPlaying",
                color = TextMuted,
                fontSize = 13.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 10.dp)
            )
        }
    }

    Spacer(Modifier.height(20.dp))
    PillButton("Share room code", enabled = true, primary = true) {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, "Listen with me on Geekify. Room code: $code")
        }
        runCatching { context.startActivity(Intent.createChooser(send, "Share room code")) }
    }
    Spacer(Modifier.height(12.dp))
    PillButton("Leave room", enabled = true, primary = false, destructive = true) { vm.leave() }
}

@Composable
private fun FormField(
    value: String,
    onChange: (String) -> Unit,
    label: String,
    placeholder: String,
    keyboardType: KeyboardType,
    capitalization: KeyboardCapitalization
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        placeholder = { Text(placeholder, color = TextMuted) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType, capitalization = capitalization),
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

@Composable
private fun PillButton(
    text: String,
    enabled: Boolean,
    primary: Boolean,
    destructive: Boolean = false,
    onClick: () -> Unit
) {
    val container = when {
        destructive -> ErrorRed.copy(alpha = 0.14f)
        !enabled -> InkElevated
        primary -> Lime
        else -> InkElevated
    }
    val content = when {
        destructive -> ErrorRed
        !enabled -> TextMuted
        primary -> OnAccent
        else -> TextPrimary
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(54.dp)
            .clip(CircleShape)
            .background(container)
            .then(if (enabled) Modifier.bouncyClickable(pressedScale = 0.97f, onClick = onClick) else Modifier),
        contentAlignment = Alignment.Center
    ) {
        Text(text, color = content, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
    }
}
