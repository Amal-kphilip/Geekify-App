package com.geekify.android.customization

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.geekify.android.settings.AppSettingsState
import com.geekify.android.ui.components.IosPalette
import com.geekify.android.ui.components.IosSettingsDivider
import com.geekify.android.ui.components.IosSettingsPageScaffold
import com.geekify.android.ui.components.IosSettingsSection
import com.geekify.android.ui.components.SettingsVisuals
import com.geekify.android.ui.theme.AppFont
import com.geekify.android.ui.theme.GeekifyFontManager
import com.geekify.android.ui.theme.GeekifyIcons
import com.geekify.android.ui.theme.Lime
import com.geekify.android.ui.theme.QuickDownloadFontSpec
import com.geekify.android.ui.theme.TextMuted
import com.geekify.android.ui.theme.TextPrimary
import com.geekify.android.ui.theme.TextSecondary
import kotlin.math.roundToInt

/**
 * Dedicated Fonts management screen under Personalization Studio -> Typography & Density.
 * Supports importing TTF/OTF files via Android SAF, resetting to Android system default font,
 * switching back to Geekify's built-in Sohne Breit font, and downloading legally redistributable
 * open-source fonts on demand.
 */
@Composable
fun FontsManagerScreen(
    state: AppSettingsState,
    viewModel: CustomizationViewModel,
    onBack: () -> Unit,
    bottomPadding: Dp = 40.dp
) {
    val typo = state.customization.typography
    val fontsUi by viewModel.fontsUiState.collectAsState()
    val toastMessage by viewModel.toastMessage.collectAsState()
    val scrollState = rememberSaveable("fonts_manager_scroll", saver = ScrollState.Saver) { ScrollState(0) }

    LaunchedEffect(Unit) {
        viewModel.refreshQuickFontStates()
    }

    val fontPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            viewModel.importFontFile(uri)
        }
    }

    IosSettingsPageScaffold(
        title = "Fonts",
        scrollState = scrollState,
        onBack = onBack,
        bottomPadding = bottomPadding,
        minimalStyle = true
    ) {
        AnimatedVisibility(visible = toastMessage != null) {
            toastMessage?.let { msg ->
                FontFeedbackBanner(
                    message = msg,
                    accentColor = SettingsVisuals.green,
                    icon = GeekifyIcons.CheckCircle,
                    onDismiss = viewModel::clearToast
                )
            }
        }

        AnimatedVisibility(visible = fontsUi.errorMessage != null) {
            fontsUi.errorMessage?.let { err ->
                FontFeedbackBanner(
                    message = err,
                    accentColor = IosPalette.systemRed,
                    icon = GeekifyIcons.ErrorOutline,
                    onDismiss = viewModel::clearFontError
                )
            }
        }

        // ---- Live Font Preview ----
        IosSettingsSection(title = "Live Preview") {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = typo.resolvedDisplayName,
                        color = Lime,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = AppFont,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(Modifier.width(8.dp))
                    val badgeText = when (typo.fontChoice) {
                        AppFontChoice.SOHNE_BREIT -> "Built-in"
                        AppFontChoice.SYSTEM_DEFAULT -> "System"
                        AppFontChoice.DOWNLOADED -> "Downloaded"
                        AppFontChoice.IMPORTED -> "Imported"
                    }
                    Surface(
                        color = Lime.copy(alpha = 0.14f),
                        shape = RoundedCornerShape(999.dp)
                    ) {
                        Text(
                            text = badgeText,
                            color = Lime,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp)
                        )
                    }
                }

                Text(
                    text = "Aa Bb Cc",
                    color = TextPrimary,
                    fontSize = 26.sp,
                    fontWeight = FontWeight.ExtraBold,
                    fontFamily = AppFont
                )
                Text(
                    text = "0123456789",
                    color = TextPrimary,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium,
                    fontFamily = AppFont
                )
                Text(
                    text = "The quick brown fox jumps over the lazy dog.",
                    color = TextSecondary,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Normal,
                    fontFamily = AppFont,
                    lineHeight = 20.sp
                )
            }
        }

        Spacer(Modifier.height(22.dp))

        // ---- Section A: Fonts ----
        IosSettingsSection(title = "Fonts") {
            FontActionRow(
                icon = GeekifyIcons.UploadFile,
                iconTint = TextPrimary,
                title = "Choose font file",
                subtitle = if (fontsUi.isImporting) "Validating and importing font…" else "Import a TTF or OTF file.",
                trailing = if (fontsUi.isImporting) {
                    {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = Lime
                        )
                    }
                } else null,
                enabled = !fontsUi.isImporting,
                onClick = {
                    fontPickerLauncher.launch(
                        arrayOf(
                            "font/ttf",
                            "font/otf",
                            "application/x-font-ttf",
                            "application/x-font-opentype",
                            "application/octet-stream",
                            "*/*"
                        )
                    )
                }
            )

            if (typo.fontChoice == AppFontChoice.IMPORTED) {
                IosSettingsDivider(insetStart = 56.dp)
                FontActionRow(
                    icon = GeekifyIcons.CheckCircle,
                    iconTint = Lime,
                    title = typo.resolvedDisplayName,
                    subtitle = "Imported font · Active",
                    subtitleColor = Lime,
                    enabled = true,
                    onClick = {}
                )
            }

            IosSettingsDivider(insetStart = 56.dp)

            val isSohneActive = typo.fontChoice == AppFontChoice.SOHNE_BREIT
            FontActionRow(
                icon = GeekifyIcons.AutoAwesome,
                iconTint = if (isSohneActive) Lime else TextPrimary,
                title = "Sohne Breit",
                subtitle = if (isSohneActive) "Active · Geekify built-in font" else "Geekify's signature built-in font.",
                subtitleColor = if (isSohneActive) Lime else TextSecondary,
                trailing = if (isSohneActive) {
                    { ActiveFontPill() }
                } else null,
                enabled = true,
                onClick = { viewModel.selectBuiltInFont(AppFontChoice.SOHNE_BREIT) }
            )

            IosSettingsDivider(insetStart = 56.dp)

            val isSystemActive = typo.fontChoice == AppFontChoice.SYSTEM_DEFAULT
            FontActionRow(
                icon = GeekifyIcons.RestartAlt,
                iconTint = if (isSystemActive) Lime else TextPrimary,
                title = "Reset font",
                subtitle = if (isSystemActive) {
                    "Active · Using the system font."
                } else {
                    "Use the system font and remove the imported font."
                },
                subtitleColor = if (isSystemActive) Lime else TextSecondary,
                trailing = if (isSystemActive) {
                    { ActiveFontPill() }
                } else null,
                enabled = true,
                onClick = { viewModel.resetFontToSystem() }
            )
        }

        Spacer(Modifier.height(22.dp))

        // ---- Section C: Quick font downloads ----
        IosSettingsSection(
            title = "Quick font downloads",
            footer = "Open-source fonts are downloaded on demand over HTTPS from official Google Fonts repositories and stored privately inside Geekify."
        ) {
            GeekifyFontManager.quickDownloadCatalog.forEachIndexed { index, spec ->
                if (index > 0) {
                    IosSettingsDivider(insetStart = 56.dp)
                }
                val installState = fontsUi.quickFontStates[spec.id] ?: when {
                    spec.isBundled -> QuickFontInstallState.Installed
                    spec.isDownloadable -> QuickFontInstallState.Available
                    else -> QuickFontInstallState.Unavailable(spec.unavailableReason ?: "Unavailable")
                }
                val isActive = typo.fontChoice == AppFontChoice.DOWNLOADED && typo.activeFontId == spec.id
                QuickDownloadFontRow(
                    spec = spec,
                    installState = installState,
                    isActive = isActive,
                    onClick = { viewModel.onQuickFontClicked(spec) }
                )
            }
        }
    }
}

@Composable
private fun QuickDownloadFontRow(
    spec: QuickDownloadFontSpec,
    installState: QuickFontInstallState,
    isActive: Boolean,
    onClick: () -> Unit
) {
    val (icon, iconTint, statusText, statusColor, clickable) = when (installState) {
        is QuickFontInstallState.Unavailable -> RowVisuals(
            icon = GeekifyIcons.Info,
            iconTint = TextMuted,
            subtitle = installState.reason,
            subtitleColor = TextMuted,
            enabled = false
        )
        is QuickFontInstallState.Downloading -> {
            val pct = (installState.progress * 100f).roundToInt().coerceIn(0, 100)
            RowVisuals(
                icon = GeekifyIcons.Download,
                iconTint = Lime,
                subtitle = if (pct > 0) "Downloading… $pct%" else "Downloading…",
                subtitleColor = Lime,
                enabled = false
            )
        }
        QuickFontInstallState.Installed -> if (isActive) {
            RowVisuals(
                icon = GeekifyIcons.CheckCircle,
                iconTint = Lime,
                subtitle = "Active · ${spec.licenseLabel}",
                subtitleColor = Lime,
                enabled = true
            )
        } else {
            RowVisuals(
                icon = GeekifyIcons.CheckCircle,
                iconTint = SettingsVisuals.green,
                subtitle = "Installed · Tap to use",
                subtitleColor = TextSecondary,
                enabled = true
            )
        }
        is QuickFontInstallState.Failed -> RowVisuals(
            icon = GeekifyIcons.Refresh,
            iconTint = IosPalette.systemRed,
            subtitle = "Download failed · Tap to retry",
            subtitleColor = IosPalette.systemRed,
            enabled = true
        )
        QuickFontInstallState.Available -> RowVisuals(
            icon = GeekifyIcons.Download,
            iconTint = TextPrimary,
            subtitle = "Available · Tap to install",
            subtitleColor = TextSecondary,
            enabled = true
        )
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (clickable) Modifier.clickable(onClick = onClick) else Modifier)
            .alpha(if (installState is QuickFontInstallState.Unavailable) 0.58f else 1f)
            .padding(horizontal = 16.dp, vertical = 13.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier.size(32.dp),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = iconTint,
                    modifier = Modifier.size(21.dp)
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = spec.displayName,
                    color = TextPrimary,
                    fontSize = 15.5.sp,
                    fontWeight = if (isActive) FontWeight.SemiBold else FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = statusText,
                    color = statusColor,
                    fontSize = 12.5.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (isActive) {
                Spacer(Modifier.width(8.dp))
                ActiveFontPill()
            }
        }

        if (installState is QuickFontInstallState.Downloading) {
            Spacer(Modifier.height(8.dp))
            if (installState.progress > 0f) {
                LinearProgressIndicator(
                    progress = { installState.progress.coerceIn(0f, 1f) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 44.dp)
                        .height(3.dp)
                        .clip(RoundedCornerShape(999.dp)),
                    color = Lime,
                    trackColor = TextMuted.copy(alpha = 0.2f)
                )
            } else {
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 44.dp)
                        .height(3.dp)
                        .clip(RoundedCornerShape(999.dp)),
                    color = Lime,
                    trackColor = TextMuted.copy(alpha = 0.2f)
                )
            }
        }
    }
}

private data class RowVisuals(
    val icon: ImageVector,
    val iconTint: Color,
    val subtitle: String,
    val subtitleColor: Color,
    val enabled: Boolean
)

@Composable
private fun FontActionRow(
    icon: ImageVector,
    iconTint: Color,
    title: String,
    subtitle: String,
    subtitleColor: Color = TextSecondary,
    trailing: (@Composable () -> Unit)? = null,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 16.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier.size(32.dp),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = iconTint,
                modifier = Modifier.size(21.dp)
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                color = TextPrimary,
                fontSize = 15.5.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = subtitle,
                color = subtitleColor,
                fontSize = 12.5.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (trailing != null) {
            Spacer(Modifier.width(8.dp))
            trailing()
        }
    }
}

@Composable
private fun ActiveFontPill() {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(Lime.copy(alpha = 0.16f))
            .padding(horizontal = 10.dp, vertical = 4.dp)
    ) {
        Text(
            text = "Active",
            color = Lime,
            fontSize = 11.5.sp,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
private fun FontFeedbackBanner(
    message: String,
    accentColor: Color,
    icon: ImageVector,
    onDismiss: () -> Unit
) {
    Surface(
        color = SettingsVisuals.card,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 14.dp)
            .border(0.8.dp, accentColor.copy(alpha = 0.42f), RoundedCornerShape(16.dp))
            .clickable(onClick = onDismiss)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = accentColor,
                modifier = Modifier.size(18.dp)
            )
            Text(
                text = message,
                color = TextPrimary,
                fontSize = 13.sp,
                modifier = Modifier.weight(1f)
            )
        }
    }
}
