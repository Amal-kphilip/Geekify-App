package com.geekify.android.ui.update

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.automirrored.rounded.TrendingUp
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.InstallMobile
import androidx.compose.material.icons.rounded.NetworkCell
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.SystemUpdateAlt
import androidx.compose.material.icons.rounded.Verified
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.geekify.android.R
import com.geekify.android.ui.glass.rememberReducedMotion
import com.geekify.android.ui.theme.InkBackground
import com.geekify.android.ui.theme.InkElevated
import com.geekify.android.ui.theme.InkPanel
import com.geekify.android.ui.theme.Lime
import com.geekify.android.ui.theme.TextPrimary
import com.geekify.android.ui.theme.TextSecondary
import com.geekify.android.update.AvailableUpdate
import com.geekify.android.update.ChangelogCategory
import com.geekify.android.update.ChangelogSection
import com.geekify.android.update.DownloadProgressSnapshot
import com.geekify.android.update.ReleaseChangelogEntry
import com.geekify.android.update.UpdateState
import com.geekify.android.update.UpdateUiState
import com.geekify.android.update.formatBytes
import com.geekify.android.update.formatEtaSeconds
import com.geekify.android.update.formatTransferSpeed
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

private val DarkBackground = InkBackground
private val DarkSurface = InkPanel
private val DarkCard = InkElevated

/**
 * Legacy stage enum preserved for binary/source compatibility with any existing callers.
 */
enum class UpdateStage { Available, Downloading, Ready, Installing }

/**
 * Morphe Manager-inspired single-dialog application update experience tailored to Geekify's
 * dark surface and electric lime design system.
 *
 * The header and footer remain anchored while the central stage transitions smoothly across:
 * CHECKING -> UP_TO_DATE / UPDATE_AVAILABLE -> METERED_WARNING -> DOWNLOADING -> VERIFYING ->
 * READY_TO_INSTALL -> INSTALLING -> COMPLETED (plus DOWNLOAD_FAILED / VERIFY_FAILED /
 * INSTALL_FAILED / INSTALL_CANCELLED).
 */
@Composable
fun UpdateDialog(
    uiState: UpdateUiState,
    onStartDownload: () -> Unit,
    onConfirmMeteredDownload: () -> Unit,
    onCancelDownload: () -> Unit,
    onInstall: () -> Unit,
    onRetryCheck: () -> Unit,
    onToggleChangelogVersion: (String) -> Unit,
    onDismiss: (rememberSkip: Boolean) -> Unit
) {
    val context = LocalContext.current
    val canDismissOnBackOrOutside = uiState.state != UpdateState.DOWNLOADING &&
        uiState.state != UpdateState.VERIFYING &&
        uiState.state != UpdateState.INSTALLING

    val openReleasePage: () -> Unit = {
        val url = uiState.availableUpdate?.releasePageUrl
            ?: "https://github.com/Amal-kphilip/Geekify-App/releases"
        runCatching {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    Dialog(
        onDismissRequest = {
            if (canDismissOnBackOrOutside) {
                onDismiss(false)
            }
        },
        properties = DialogProperties(
            dismissOnBackPress = canDismissOnBackOrOutside,
            dismissOnClickOutside = canDismissOnBackOrOutside,
            usePlatformDefaultWidth = false
        )
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 20.dp),
            shape = RoundedCornerShape(30.dp),
            color = DarkSurface,
            tonalElevation = 12.dp,
            shadowElevation = 28.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                Lime.copy(alpha = 0.13f),
                                DarkSurface,
                                DarkBackground
                            )
                        )
                    )
                    .border(
                        width = 1.dp,
                        brush = Brush.verticalGradient(
                            colors = listOf(
                                Lime.copy(alpha = 0.36f),
                                Color.White.copy(alpha = 0.08f)
                            )
                        ),
                        shape = RoundedCornerShape(30.dp)
                    )
                    .padding(horizontal = 22.dp, vertical = 20.dp)
            ) {
                // 1.ISTENT COORDINATED HEADER
                UpdateDialogHeader(
                    uiState = uiState,
                    canClose = canDismissOnBackOrOutside,
                    onClose = { onDismiss(false) }
                )

                Spacer(Modifier.height(16.dp))

                // 2. STAGE-DRIVEN CENTRAL CONTENT
                AnimatedContent(
                    targetState = uiState.state,
                    contentKey = { it },
                    transitionSpec = {
                        (fadeIn(animationSpec = tween(220, easing = FastOutSlowInEasing)) +
                            scaleIn(
                                initialScale = 0.94f,
                                animationSpec = tween(220, easing = FastOutSlowInEasing)
                            )) togetherWith
                            (fadeOut(animationSpec = tween(160, easing = FastOutSlowInEasing)) +
                                scaleOut(
                                    targetScale = 0.96f,
                                    animationSpec = tween(160, easing = FastOutSlowInEasing)
                                )) using SizeTransform(clip = false)
                    },
                    label = "UpdateCentralStageTransition",
                    modifier = Modifier.fillMaxWidth()
                ) { stage ->
                    when (stage) {
                        UpdateState.CHECKING -> CheckingStageContent()
                        UpdateState.UP_TO_DATE -> UpToDateStageContent(
                            uiState = uiState,
                            onToggleChangelogVersion = onToggleChangelogVersion
                        )
                        UpdateState.UPDATE_AVAILABLE -> UpdateAvailableStageContent(
                            uiState = uiState,
                            onToggleChangelogVersion = onToggleChangelogVersion
                        )
                        UpdateState.METERED_WARNING -> MeteredWarningStageContent(uiState = uiState)
                        UpdateState.DOWNLOADING -> DownloadingOrVerifyingStageContent(
                            uiState = uiState,
                            isVerifying = false
                        )
                        UpdateState.VERIFYING -> DownloadingOrVerifyingStageContent(
                            uiState = uiState,
                            isVerifying = true
                        )
                        UpdateState.READY_TO_INSTALL -> ReadyToInstallStageContent(uiState = uiState)
                        UpdateState.INSTALLING -> InstallingStageContent(uiState = uiState)
                        UpdateState.COMPLETED -> CompletedStageContent(
                            uiState = uiState,
                            onToggleChangelogVersion = onToggleChangelogVersion
                        )
                        UpdateState.DOWNLOAD_FAILED,
                        UpdateState.VERIFY_FAILED,
                        UpdateState.INSTALL_FAILED,
                        UpdateState.INSTALL_CANCELLED -> ErrorOrCancelledStageContent(uiState = uiState)
                    }
                }

                Spacer(Modifier.height(18.dp))

                // 3. COORDINATED DYNAMIC FOOTER
                UpdateDialogFooter(
                    uiState = uiState,
                    onStartDownload = onStartDownload,
                    onConfirmMeteredDownload = onConfirmMeteredDownload,
                    onCancelDownload = onCancelDownload,
                    onInstall = onInstall,
                    onRetryCheck = onRetryCheck,
                    onOpenReleasePage = openReleasePage,
                    onDismiss = onDismiss
                )
            }
        }
    }
}

@Composable
private fun UpdateDialogHeader(
    uiState: UpdateUiState,
    canClose: Boolean,
    onClose: () -> Unit
) {
    val stateTitle = when (uiState.state) {
        UpdateState.CHECKING -> stringResource(R.string.update_checking_title)
        UpdateState.UP_TO_DATE -> stringResource(R.string.update_up_to_date_title)
        UpdateState.UPDATE_AVAILABLE -> stringResource(R.string.update_available_title)
        UpdateState.METERED_WARNING -> stringResource(R.string.update_metered_warning_title)
        UpdateState.DOWNLOADING -> stringResource(R.string.update_downloading_title)
        UpdateState.VERIFYING -> stringResource(R.string.update_verifying_title)
        UpdateState.READY_TO_INSTALL -> stringResource(R.string.update_ready_title)
        UpdateState.INSTALLING -> stringResource(R.string.update_installing_title)
        UpdateState.COMPLETED -> stringResource(R.string.update_completed_title)
        UpdateState.DOWNLOAD_FAILED -> stringResource(R.string.update_download_failed_title)
        UpdateState.VERIFY_FAILED -> stringResource(R.string.update_verify_failed_title)
        UpdateState.INSTALL_FAILED -> stringResource(R.string.update_install_failed_title)
        UpdateState.INSTALL_CANCELLED -> stringResource(R.string.update_install_cancelled_title)
    }

    val versionBadgeText = remember(
        uiState.state,
        uiState.installedVersionName,
        uiState.availableUpdate?.versionName,
        uiState.completedUpdateInfo
    ) {
        val completed = uiState.completedUpdateInfo
        val available = uiState.availableUpdate
        when {
            completed != null && !completed.previousVersionName.isNullOrBlank() ->
                "v${completed.previousVersionName} → v${completed.newVersionName}"
            available != null ->
                "v${uiState.installedVersionName} → v${available.versionName}"
            else ->
                "v${uiState.installedVersionName}"
        }
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Circular Geekify Brand Icon Container
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(
                        Brush.radialGradient(
                            colors = listOf(
                                Lime.copy(alpha = 0.28f),
                                Lime.copy(alpha = 0.10f)
                            )
                        )
                    )
                    .border(1.dp, Lime.copy(alpha = 0.45f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                GeekifyBrandGlyph(modifier = Modifier.size(26.dp))
            }

            Spacer(Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = stringResource(R.string.update_app_name),
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontWeight = FontWeight.ExtraBold,
                            letterSpacing = 1.6.sp
                        ),
                        color = Lime
                    )
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(999.dp))
                            .background(Color.White.copy(alpha = 0.07f))
                            .border(1.dp, Color.White.copy(alpha = 0.10f), RoundedCornerShape(999.dp))
                            .padding(horizontal = 8.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = versionBadgeText,
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                            color = TextPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                Spacer(Modifier.height(2.dp))

                AnimatedContent(
                    targetState = stateTitle,
                    transitionSpec = {
                        fadeIn(tween(180)) togetherWith fadeOut(tween(120))
                    },
                    label = "HeaderStateTitleTransition"
                ) { title ->
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.ExtraBold),
                        color = TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            if (canClose) {
                IconButton(
                    onClick = onClose,
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = 0.06f))
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Close,
                        contentDescription = stringResource(R.string.update_action_close),
                        tint = TextSecondary,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun CheckingStageContent() {
    val reducedMotion = rememberReducedMotion()
    val infinite = rememberInfiniteTransition(label = "CheckingShimmer")
    val pulseAlpha by infinite.animateFloat(
        initialValue = if (reducedMotion) 0.14f else 0.08f,
        targetValue = if (reducedMotion) 0.14f else 0.20f,
        animationSpec = infiniteRepeatable(
            animation = tween(850, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "SkeletonAlpha"
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .semantics { liveRegion = LiveRegionMode.Polite },
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Version comparison skeleton
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(18.dp))
                .background(DarkCard)
                .border(1.dp, Color.White.copy(alpha = 0.06f), RoundedCornerShape(18.dp))
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(44.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color.White.copy(alpha = pulseAlpha))
            )
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(44.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Lime.copy(alpha = pulseAlpha * 0.85f))
            )
        }

        // Changelog skeleton card
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(DarkCard)
                .border(1.dp, Color.White.copy(alpha = 0.06f), RoundedCornerShape(20.dp))
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.45f)
                    .height(14.dp)
                    .clip(RoundedCornerShape(999.dp))
                    .background(Lime.copy(alpha = pulseAlpha))
            )
            repeat(4) { idx ->
                Box(
                    modifier = Modifier
                        .fillMaxWidth(if (idx == 3) 0.68f else 0.92f)
                        .height(12.dp)
                        .clip(RoundedCornerShape(999.dp))
                        .background(Color.White.copy(alpha = pulseAlpha))
                )
            }
        }
    }
}

@Composable
private fun UpToDateStageContent(
    uiState: UpdateUiState,
    onToggleChangelogVersion: (String) -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(18.dp))
                .background(Lime.copy(alpha = 0.10f))
                .border(1.dp, Lime.copy(alpha = 0.32f), RoundedCornerShape(18.dp))
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(Lime.copy(alpha = 0.20f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Rounded.CheckCircle,
                    contentDescription = null,
                    tint = Lime,
                    modifier = Modifier.size(22.dp)
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Geekify v${uiState.installedVersionName}",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.ExtraBold),
                    color = TextPrimary
                )
                Text(
                    text = stringResource(R.string.update_up_to_date_subtitle),
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary
                )
            }
        }

        if (uiState.recentChangelog.isNotEmpty()) {
            ChangelogTimelinePanel(
                entries = uiState.recentChangelog,
                expandedVersions = uiState.expandedChangelogVersions,
                onToggleVersion = onToggleChangelogVersion,
                maxHeightDp = 240
            )
        }
    }
}

@Composable
private fun UpdateAvailableStageContent(
    uiState: UpdateUiState,
    onToggleChangelogVersion: (String) -> Unit
) {
    val update = uiState.availableUpdate ?: return
    val timeline = if (uiState.recentChangelog.isNotEmpty()) {
        uiState.recentChangelog
    } else {
        update.changelogTimeline
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // Version transition summary row
        VersionTransitionSummaryCard(
            installedVersion = uiState.installedVersionName,
            availableVersion = update.versionName,
            releaseDate = update.formattedDate,
            apkSizeBytes = update.apkSizeBytes,
            changeCount = update.totalChangeCount
        )

        // Expandable multi-release changelog timeline
        ChangelogTimelinePanel(
            entries = timeline,
            expandedVersions = uiState.expandedChangelogVersions,
            onToggleVersion = onToggleChangelogVersion,
            maxHeightDp = 260
        )
    }
}

@Composable
private fun VersionTransitionSummaryCard(
    installedVersion: String,
    availableVersion: String,
    releaseDate: String?,
    apkSizeBytes: Long?,
    changeCount: Int
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(DarkCard)
            .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(18.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            VersionPillCard(
                label = "Installed",
                version = "v$installedVersion",
                highlighted = false,
                modifier = Modifier.weight(1f)
            )
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(Lime.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Rounded.ArrowForward,
                    contentDescription = null,
                    tint = Lime,
                    modifier = Modifier.size(16.dp)
                )
            }
            VersionPillCard(
                label = "Available",
                version = "v$availableVersion",
                highlighted = true,
                modifier = Modifier.weight(1f)
            )
        }

        // Metadata chips (Release Date, Package Size, Change Count)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (!releaseDate.isNullOrBlank()) {
                MetadataTagChip(text = releaseDate)
            }
            if (apkSizeBytes != null && apkSizeBytes > 0L) {
                MetadataTagChip(text = formatBytes(apkSizeBytes))
            }
            if (changeCount > 0) {
                MetadataTagChip(
                    text = stringResource(R.string.update_changes_count, changeCount),
                    highlighted = true
                )
            }
        }
    }
}

@Composable
private fun VersionPillCard(
    label: String,
    version: String,
    highlighted: Boolean,
    modifier: Modifier = Modifier
) {
    val borderColor = if (highlighted) Lime.copy(alpha = 0.45f) else Color.White.copy(alpha = 0.07f)
    val bgColor = if (highlighted) Lime.copy(alpha = 0.11f) else Color.White.copy(alpha = 0.03f)

    Column(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(bgColor)
            .border(1.dp, borderColor, RoundedCornerShape(14.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = if (highlighted) Lime else TextSecondary
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = version,
            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.ExtraBold),
            color = TextPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun MetadataTagChip(
    text: String,
    highlighted: Boolean = false
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(
                if (highlighted) Lime.copy(alpha = 0.14f) else Color.White.copy(alpha = 0.05f)
            )
            .padding(horizontal = 9.dp, vertical = 3.dp)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
            color = if (highlighted) Lime else TextSecondary
        )
    }
}

@Composable
private fun ChangelogTimelinePanel(
    entries: List<ReleaseChangelogEntry>,
    expandedVersions: Set<String>,
    onToggleVersion: (String) -> Unit,
    maxHeightDp: Int
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(DarkCard)
            .border(1.dp, Color.White.copy(alpha = 0.07f), RoundedCornerShape(20.dp))
            .padding(14.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Icon(
                    imageVector = Icons.Rounded.AutoAwesome,
                    contentDescription = null,
                    tint = Lime,
                    modifier = Modifier.size(16.dp)
                )
                Text(
                    text = stringResource(R.string.update_whats_new_header),
                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                    color = Lime
                )
            }
            if (entries.size > 1) {
                Text(
                    text = "${entries.size} releases",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextSecondary
                )
            }
        }

        Spacer(Modifier.height(10.dp))

        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 96.dp, max = maxHeightDp.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(bottom = 2.dp)
        ) {
            itemsIndexed(
                items = entries,
                key = { _, item -> item.versionName }
            ) { index, entry ->
                val isExpanded = expandedVersions.contains(entry.versionName) ||
                    (expandedVersions.isEmpty() && index == 0)

                ReleaseTimelineCard(
                    entry = entry,
                    isExpanded = isExpanded,
                    isLatest = index == 0,
                    onToggle = { onToggleVersion(entry.versionName) }
                )
            }
        }
    }
}

@Composable
private fun ReleaseTimelineCard(
    entry: ReleaseChangelogEntry,
    isExpanded: Boolean,
    isLatest: Boolean,
    onToggle: () -> Unit
) {
    val chevronRotation by animateFloatAsState(
        targetValue = if (isExpanded) 180f else 0f,
        animationSpec = tween(220, easing = FastOutSlowInEasing),
        label = "ChangelogChevronRotation"
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(
                if (isLatest) Lime.copy(alpha = 0.06f) else Color.White.copy(alpha = 0.03f)
            )
            .border(
                width = 1.dp,
                color = if (isExpanded) Lime.copy(alpha = 0.28f) else Color.White.copy(alpha = 0.06f),
                shape = RoundedCornerShape(14.dp)
            )
            .clickable(onClick = onToggle)
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Timeline indicator dot
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(if (isLatest) Lime else TextSecondary.copy(alpha = 0.6f))
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = "v${entry.versionName}",
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.ExtraBold),
                color = if (isLatest) Lime else TextPrimary
            )
            if (!entry.formattedDate.isNullOrBlank()) {
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "• ${entry.formattedDate}",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextSecondary
                )
            }
            Spacer(Modifier.weight(1f))
            if (entry.changeCount > 0) {
                Text(
                    text = "${entry.changeCount}",
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                    color = TextSecondary,
                    modifier = Modifier
                        .clip(RoundedCornerShape(999.dp))
                        .background(Color.White.copy(alpha = 0.06f))
                        .padding(horizontal = 7.dp, vertical = 2.dp)
                )
                Spacer(Modifier.width(4.dp))
            }
            Icon(
                imageVector = Icons.Rounded.ExpandMore,
                contentDescription = if (isExpanded) "Collapse release notes" else "Expand release notes",
                tint = TextSecondary,
                modifier = Modifier
                    .size(18.dp)
                    .rotate(chevronRotation)
            )
        }

        AnimatedVisibility(
            visible = isExpanded,
            enter = expandVertically(animationSpec = tween(220, easing = FastOutSlowInEasing)) +
                fadeIn(animationSpec = tween(180)),
            exit = shrinkVertically(animationSpec = tween(180, easing = FastOutSlowInEasing)) +
                fadeOut(animationSpec = tween(130))
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                entry.sections.forEach { section ->
                    ChangelogCategoryBlock(section = section)
                }
            }
        }
    }
}

@Composable
private fun ChangelogCategoryBlock(section: ChangelogSection) {
    val categoryIcon: ImageVector = when (section.category) {
        ChangelogCategory.WHATS_NEW -> Icons.Rounded.AutoAwesome
        ChangelogCategory.IMPROVEMENTS -> Icons.AutoMirrored.Rounded.TrendingUp
        ChangelogCategory.BUG_FIXES -> Icons.Rounded.BugReport
        ChangelogCategory.NOTES -> Icons.Rounded.Description
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Icon(
                imageVector = categoryIcon,
                contentDescription = null,
                tint = Lime.copy(alpha = 0.9f),
                modifier = Modifier.size(14.dp)
            )
            Text(
                text = section.title,
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                color = TextPrimary
            )
        }

        section.bullets.forEach { bullet ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 4.dp),
                verticalAlignment = Alignment.Top
            ) {
                Box(
                    modifier = Modifier
                        .padding(top = 7.dp, end = 8.dp)
                        .size(5.dp)
                        .clip(CircleShape)
                        .background(Lime.copy(alpha = 0.85f))
                )
                Text(
                    text = bullet,
                    style = MaterialTheme.typography.bodySmall.copy(lineHeight = 18.sp),
                    color = TextSecondary
                )
            }
        }
    }
}

@Composable
private fun MeteredWarningStageContent(uiState: UpdateUiState) {
    val sizeLabel = uiState.availableUpdate?.apkSizeBytes?.let(::formatBytes) ?: "~25 MB"

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(22.dp))
            .background(DarkCard)
            .border(1.dp, Lime.copy(alpha = 0.30f), RoundedCornerShape(22.dp))
            .padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Box(
            modifier = Modifier
                .size(56.dp)
                .clip(CircleShape)
                .background(Lime.copy(alpha = 0.16f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Rounded.NetworkCell,
                contentDescription = null,
                tint = Lime,
                modifier = Modifier.size(28.dp)
            )
        }

        Text(
            text = stringResource(R.string.update_metered_warning_title),
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.ExtraBold),
            color = TextPrimary,
            textAlign = TextAlign.Center
        )

        Text(
            text = stringResource(R.string.update_metered_warning_body, sizeLabel),
            style = MaterialTheme.typography.bodyMedium,
            color = TextSecondary,
            textAlign = TextAlign.Center
        )
    }
}

/**
 * Prominent Morphe-style Circular Wavy Download & Verification Indicator (`220.dp` diameter).
 */
@Composable
private fun DownloadingOrVerifyingStageContent(
    uiState: UpdateUiState,
    isVerifying: Boolean
) {
    val reducedMotion = rememberReducedMotion()
    val snapshot: DownloadProgressSnapshot = uiState.downloadProgress
    val rawFraction = if (isVerifying) 1f else (snapshot.progressFraction ?: 0f)
    val animatedProgress by animateFloatAsState(
        targetValue = rawFraction.coerceIn(0f, 1f),
        animationSpec = tween(durationMillis = 240, easing = FastOutSlowInEasing),
        label = "CircularDownloadProgress"
    )

    var entered by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { entered = true }
    val ringScale by animateFloatAsState(
        targetValue = if (entered) 1f else 0.85f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessLow
        ),
        label = "CircularRingScaleIn"
    )

    val infiniteTransition = rememberInfiniteTransition(label = "CircularWaveAnimation")
    val wavePhase by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = (2f * PI).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1350, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "WavePhaseRad"
    )

    val indeterminateRotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1800, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "IndeterminateRingRotation"
    )

    val percentageInt = (animatedProgress * 100f).roundToInt().coerceIn(0, 100)
    val bytesText = remember(snapshot.downloadedBytes, snapshot.totalBytes) {
        val dl = formatBytes(snapshot.downloadedBytes)
        val total = snapshot.totalBytes?.takeIf { it > 0L }?.let(::formatBytes)
        if (total != null) "$dl / $total" else dl
    }
    val speedText = remember(snapshot.bytesPerSecond) {
        formatTransferSpeed(snapshot.bytesPerSecond)
    }
    val etaText = remember(snapshot.etaSeconds) {
        formatEtaSeconds(snapshot.etaSeconds)
    }

    val accessibilityDesc = if (isVerifying) {
        stringResource(R.string.update_verifying_title)
    } else if (uiState.isIndeterminateDownload) {
        "Downloading update, $bytesText downloaded"
    } else {
        "Downloading update, $percentageInt percent, $bytesText"
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
            .semantics {
                contentDescription = accessibilityDesc
                liveRegion = LiveRegionMode.Polite
            },
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(220.dp)
                .graphicsLayer {
                    scaleX = ringScale
                    scaleY = ringScale
                },
            contentAlignment = Alignment.Center
        ) {
            CircularWavyProgressRing(
                progress = if (uiState.isIndeterminateDownload && !isVerifying) 0.34f else animatedProgress,
                wavePhase = if (reducedMotion) 0f else wavePhase,
                rotationOffsetDeg = if (uiState.isIndeterminateDownload && !isVerifying && !reducedMotion) {
                    indeterminateRotation
                } else {
                    0f
                },
                isVerifying = isVerifying,
                modifier = Modifier.fillMaxSize()
            )

            // Center readout inside the 220.dp ring
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
                modifier = Modifier.padding(horizontal = 24.dp)
            ) {
                if (isVerifying) {
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .clip(CircleShape)
                            .background(Lime.copy(alpha = 0.18f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Security,
                            contentDescription = null,
                            tint = Lime,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "100%",
                        style = MaterialTheme.typography.headlineMedium.copy(
                            fontWeight = FontWeight.ExtraBold
                        ),
                        color = Lime
                    )
                } else {
                    Text(
                        text = if (uiState.isIndeterminateDownload) "•••" else "$percentageInt%",
                        style = MaterialTheme.typography.displaySmall.copy(
                            fontWeight = FontWeight.ExtraBold,
                            letterSpacing = (-1).sp
                        ),
                        color = TextPrimary
                    )
                }

                Spacer(Modifier.height(4.dp))

                Text(
                    text = bytesText,
                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                    color = TextSecondary,
                    textAlign = TextAlign.Center
                )

                if (!isVerifying && (speedText.isNotEmpty() || etaText.isNotEmpty())) {
                    Spacer(Modifier.height(6.dp))
                    val metaLine = listOf(speedText, etaText).filter { it.isNotEmpty() }.joinToString(" • ")
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(999.dp))
                            .background(Lime.copy(alpha = 0.12f))
                            .padding(horizontal = 10.dp, vertical = 3.dp)
                    ) {
                        Text(
                            text = metaLine,
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                            color = Lime,
                            maxLines = 1
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(14.dp))

        Text(
            text = if (isVerifying) {
                stringResource(R.string.update_verifying_title)
            } else {
                stringResource(R.string.update_downloading_title)
            },
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
            color = TextPrimary
        )

        Spacer(Modifier.height(4.dp))

        Text(
            text = if (isVerifying) {
                stringResource(R.string.update_verifying_subtitle)
            } else {
                stringResource(R.string.update_downloading_subtitle)
            },
            style = MaterialTheme.typography.bodySmall,
            color = TextSecondary,
            textAlign = TextAlign.Center
        )
    }
}

/**
 * Custom Canvas drawing the Morphe-inspired circular wavy progress indicator in Geekify Lime.
 */
@Composable
private fun CircularWavyProgressRing(
    progress: Float,
    wavePhase: Float,
    rotationOffsetDeg: Float,
    isVerifying: Boolean,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier) {
        val strokeWidthPx = 10.dp.toPx()
        val waveAmplitudePx = if (isVerifying) 2.2.dp.toPx() else 3.2.dp.toPx()
        val paddingPx = strokeWidthPx + waveAmplitudePx + 4.dp.toPx()
        val baseRadius = (size.minDimension / 2f) - paddingPx
        val centerOffset = Offset(size.width / 2f, size.height / 2f)

        // 1. Subtle background track circle
        drawCircle(
            color = Color.White.copy(alpha = 0.08f),
            radius = baseRadius,
            center = centerOffset,
            style = Stroke(width = strokeWidthPx, cap = StrokeCap.Round)
        )

        // 2. Soft ambient glow behind active arc
        val clampedProgress = progress.coerceIn(0f, 1f)
        if (clampedProgress > 0.005f) {
            val startAngleDeg = -90f + rotationOffsetDeg
            val sweepAngleDeg = 360f * clampedProgress
            val steps = (sweepAngleDeg * 1.6f).roundToInt().coerceIn(24, 360)
            val waveFrequency = 12f

            val wavyPath = Path()
            for (i in 0..steps) {
                val fraction = i.toFloat() / steps.toFloat()
                val angleDeg = startAngleDeg + sweepAngleDeg * fraction
                val angleRad = angleDeg * (PI.toFloat() / 180f)

                // Taper wave amplitude smoothly at both ends of the arc so caps stay rounded and clean
                val edgeEnvelope = sin(fraction * PI.toFloat()).coerceIn(0f, 1f)
                val waveOffset = sin(angleRad * waveFrequency + wavePhase) * waveAmplitudePx * edgeEnvelope
                val r = baseRadius + waveOffset

                val x = centerOffset.x + r * cos(angleRad)
                val y = centerOffset.y + r * sin(angleRad)
                if (i == 0) {
                    wavyPath.moveTo(x, y)
                } else {
                    wavyPath.lineTo(x, y)
                }
            }

            // Outer glow pass
            drawPath(
                path = wavyPath,
                color = Lime.copy(alpha = 0.22f),
                style = Stroke(width = strokeWidthPx * 1.75f, cap = StrokeCap.Round)
            )

            // Crisp primary Lime wave stroke
            drawPath(
                path = wavyPath,
                color = Lime,
                style = Stroke(width = strokeWidthPx, cap = StrokeCap.Round)
            )
        }
    }
}

@Composable
private fun ReadyToInstallStageContent(uiState: UpdateUiState) {
    val fileSize = uiState.verifiedApkFile?.length()?.let(::formatBytes)
        ?: uiState.availableUpdate?.apkSizeBytes?.let(::formatBytes)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(22.dp))
            .background(DarkCard)
            .border(1.dp, Lime.copy(alpha = 0.35f), RoundedCornerShape(22.dp))
            .padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Box(
            modifier = Modifier
                .size(64.dp)
                .clip(CircleShape)
                .background(Lime.copy(alpha = 0.16f))
                .border(1.dp, Lime.copy(alpha = 0.45f), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Rounded.Verified,
                contentDescription = null,
                tint = Lime,
                modifier = Modifier.size(32.dp)
            )
        }

        Text(
            text = "Geekify v${uiState.targetVersionName}",
            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.ExtraBold),
            color = TextPrimary
        )

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier
                .clip(RoundedCornerShape(999.dp))
                .background(Lime.copy(alpha = 0.14f))
                .padding(horizontal = 12.dp, vertical = 5.dp)
        ) {
            Icon(
                imageVector = Icons.Rounded.Security,
                contentDescription = null,
                tint = Lime,
                modifier = Modifier.size(15.dp)
            )
            Text(
                text = stringResource(R.string.update_verified_badge),
                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                color = Lime
            )
        }

        if (fileSize != null) {
            Text(
                text = "Package size: $fileSize",
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary
            )
        }
    }
}

/**
 * Dedicated Installation Screen (`UpdateState.INSTALLING`) with pulsing Geekify logo animation
 * (`scale` `0.82f..1.0f`, `alpha` `0.45f..1.0f`, `900ms` reverse-repeat).
 */
@Composable
private fun InstallingStageContent(uiState: UpdateUiState) {
    val reducedMotion = rememberReducedMotion()
    val infinite = rememberInfiniteTransition(label = "InstallingLogoPulse")
    val pulseScale by infinite.animateFloat(
        initialValue = if (reducedMotion) 1f else 0.82f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 900, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "InstallingPulseScale"
    )
    val pulseAlpha by infinite.animateFloat(
        initialValue = if (reducedMotion) 0.95f else 0.45f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 900, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "InstallingPulseAlpha"
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 18.dp)
            .semantics { liveRegion = LiveRegionMode.Polite },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Box(
            modifier = Modifier.size(124.dp),
            contentAlignment = Alignment.Center
        ) {
            // Outer pulsing halo
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        scaleX = pulseScale * 1.08f
                        scaleY = pulseScale * 1.08f
                        alpha = pulseAlpha * 0.35f
                    }
                    .clip(CircleShape)
                    .background(Lime.copy(alpha = 0.22f))
            )

            // Core pulsing Geekify emblem
            Box(
                modifier = Modifier
                    .size(92.dp)
                    .graphicsLayer {
                        scaleX = pulseScale
                        scaleY = pulseScale
                        alpha = pulseAlpha
                    }
                    .clip(CircleShape)
                    .background(
                        Brush.radialGradient(
                            colors = listOf(
                                Lime.copy(alpha = 0.32f),
                                DarkCard
                            )
                        )
                    )
                    .border(2.dp, Lime.copy(alpha = 0.65f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                GeekifyBrandGlyph(modifier = Modifier.size(46.dp))
            }
        }

        Text(
            text = stringResource(R.string.update_installing_title),
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.ExtraBold),
            color = TextPrimary,
            textAlign = TextAlign.Center
        )

        Text(
            text = if (uiState.awaitingUnknownSourcesPermission) {
                stringResource(R.string.update_installing_permission_subtitle)
            } else {
                stringResource(R.string.update_installing_subtitle)
            },
            style = MaterialTheme.typography.bodySmall,
            color = TextSecondary,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 12.dp)
        )
    }
}

/**
 * Post-update completion screen (`UpdateState.COMPLETED`) shown only when verified against
 * the installed application version.
 */
@Composable
private fun CompletedStageContent(
    uiState: UpdateUiState,
    onToggleChangelogVersion: (String) -> Unit
) {
    var entered by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { entered = true }
    val badgeScale by animateFloatAsState(
        targetValue = if (entered) 1.0f else 0.6f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMediumLow
        ),
        label = "CompletedCheckScale"
    )

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(22.dp))
                .background(Lime.copy(alpha = 0.10f))
                .border(1.dp, Lime.copy(alpha = 0.36f), RoundedCornerShape(22.dp))
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(64.dp)
                    .graphicsLayer {
                        scaleX = badgeScale
                        scaleY = badgeScale
                    }
                    .clip(CircleShape)
                    .background(Lime.copy(alpha = 0.22f))
                    .border(1.5.dp, Lime, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Rounded.CheckCircle,
                    contentDescription = null,
                    tint = Lime,
                    modifier = Modifier.size(36.dp)
                )
            }

            Text(
                text = stringResource(R.string.update_completed_title),
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.ExtraBold),
                color = TextPrimary,
                textAlign = TextAlign.Center
            )

            Text(
                text = "Version v${uiState.targetVersionName}",
                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                color = Lime
            )

            Text(
                text = stringResource(R.string.update_completed_subtitle),
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary,
                textAlign = TextAlign.Center
            )
        }

        if (uiState.recentChangelog.isNotEmpty()) {
            ChangelogTimelinePanel(
                entries = uiState.recentChangelog,
                expandedVersions = uiState.expandedChangelogVersions,
                onToggleVersion = onToggleChangelogVersion,
                maxHeightDp = 200
            )
        }
    }
}

@Composable
private fun ErrorOrCancelledStageContent(uiState: UpdateUiState) {
    val isCancelled = uiState.state == UpdateState.INSTALL_CANCELLED
    val accentColor = if (isCancelled) Lime else Color(0xFFFF6B6B)
    val hasSavedApk = uiState.verifiedApkFile?.exists() == true

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(22.dp))
            .background(DarkCard)
            .border(1.dp, accentColor.copy(alpha = 0.35f), RoundedCornerShape(22.dp))
            .padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Box(
            modifier = Modifier
                .size(56.dp)
                .clip(CircleShape)
                .background(accentColor.copy(alpha = 0.14f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = if (isCancelled) Icons.Rounded.InstallMobile else Icons.Rounded.ErrorOutline,
                contentDescription = null,
                tint = accentColor,
                modifier = Modifier.size(28.dp)
            )
        }

        Text(
            text = uiState.errorTitle ?: when (uiState.state) {
                UpdateState.DOWNLOAD_FAILED -> stringResource(R.string.update_download_failed_title)
                UpdateState.VERIFY_FAILED -> stringResource(R.string.update_verify_failed_title)
                UpdateState.INSTALL_FAILED -> stringResource(R.string.update_install_failed_title)
                else -> stringResource(R.string.update_install_cancelled_title)
            },
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.ExtraBold),
            color = TextPrimary,
            textAlign = TextAlign.Center
        )

        Text(
            text = uiState.errorMessage
                ?: "Something went wrong while updating Geekify. Please try again.",
            style = MaterialTheme.typography.bodySmall.copy(lineHeight = 18.sp),
            color = TextSecondary,
            textAlign = TextAlign.Center
        )

        if (hasSavedApk) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier
                    .clip(RoundedCornerShape(999.dp))
                    .background(Lime.copy(alpha = 0.12f))
                    .padding(horizontal = 10.dp, vertical = 4.dp)
            ) {
                Icon(
                    imageVector = Icons.Rounded.Verified,
                    contentDescription = null,
                    tint = Lime,
                    modifier = Modifier.size(14.dp)
                )
                Text(
                    text = "Verified APK is cached — no re-download needed",
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                    color = Lime
                )
            }
        }
    }
}

@Composable
private fun UpdateDialogFooter(
    uiState: UpdateUiState,
    onStartDownload: () -> Unit,
    onConfirmMeteredDownload: () -> Unit,
    onCancelDownload: () -> Unit,
    onInstall: () -> Unit,
    onRetryCheck: () -> Unit,
    onOpenReleasePage: () -> Unit,
    onDismiss: (rememberSkip: Boolean) -> Unit
) {
    AnimatedContent(
        targetState = uiState.state,
        contentKey = { it },
        transitionSpec = {
            fadeIn(tween(180)) togetherWith fadeOut(tween(130))
        },
        label = "UpdateFooterTransition",
        modifier = Modifier.fillMaxWidth()
    ) { stage ->
        when (stage) {
            UpdateState.CHECKING -> {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(
                        onClick = { onDismiss(false) },
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.update_action_cancel),
                            color = TextSecondary,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }

            UpdateState.UP_TO_DATE -> {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedButton(
                        onClick = onRetryCheck,
                        shape = RoundedCornerShape(16.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.14f)),
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Refresh,
                            contentDescription = null,
                            tint = TextPrimary,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = stringResource(R.string.update_action_check_again),
                            color = TextPrimary,
                            fontWeight = FontWeight.SemiBold
                        )
                    }

                    Button(
                        onClick = { onDismiss(false) },
                        shape = RoundedCornerShape(16.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Lime,
                            contentColor = DarkBackground
                        ),
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.update_action_done),
                            fontWeight = FontWeight.ExtraBold
                        )
                    }
                }
            }

            UpdateState.UPDATE_AVAILABLE -> {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(
                        onClick = { onDismiss(true) },
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.height(50.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.update_action_later),
                            color = TextSecondary,
                            fontWeight = FontWeight.SemiBold
                        )
                    }

                    Button(
                        onClick = onStartDownload,
                        shape = RoundedCornerShape(16.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Lime,
                            contentColor = DarkBackground
                        ),
                        contentPadding = PaddingValues(horizontal = 20.dp),
                        modifier = Modifier
                            .weight(1f)
                            .height(50.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.CloudDownload,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = stringResource(R.string.update_action_download),
                            fontWeight = FontWeight.ExtraBold,
                            fontSize = 15.sp
                        )
                    }
                }
            }

            UpdateState.METERED_WARNING -> {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(
                        onClick = onCancelDownload,
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.height(50.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.update_action_cancel),
                            color = TextSecondary,
                            fontWeight = FontWeight.SemiBold
                        )
                    }

                    Button(
                        onClick = onConfirmMeteredDownload,
                        shape = RoundedCornerShape(16.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Lime,
                            contentColor = DarkBackground
                        ),
                        modifier = Modifier
                            .weight(1f)
                            .height(50.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.CloudDownload,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = stringResource(R.string.update_action_download_anyway),
                            fontWeight = FontWeight.ExtraBold
                        )
                    }
                }
            }

            UpdateState.DOWNLOADING -> {
                OutlinedButton(
                    onClick = onCancelDownload,
                    shape = RoundedCornerShape(16.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.14f)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                ) {
                    Text(
                        text = stringResource(R.string.update_action_cancel),
                        color = TextPrimary,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

            UpdateState.VERIFYING -> {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "Please wait while Geekify verifies the package…",
                        style = MaterialTheme.typography.labelMedium,
                        color = TextSecondary
                    )
                }
            }

            UpdateState.READY_TO_INSTALL -> {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(
                        onClick = { onDismiss(false) },
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.height(50.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.update_action_later),
                            color = TextSecondary,
                            fontWeight = FontWeight.SemiBold
                        )
                    }

                    Button(
                        onClick = onInstall,
                        shape = RoundedCornerShape(16.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Lime,
                            contentColor = DarkBackground
                        ),
                        modifier = Modifier
                            .weight(1f)
                            .height(50.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.SystemUpdateAlt,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = stringResource(R.string.update_action_install),
                            fontWeight = FontWeight.ExtraBold,
                            fontSize = 15.sp
                        )
                    }
                }
            }

            UpdateState.INSTALLING -> {
                if (uiState.awaitingUnknownSourcesPermission) {
                    Button(
                        onClick = onInstall,
                        shape = RoundedCornerShape(16.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Lime,
                            contentColor = DarkBackground
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(50.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.update_action_grant_permission),
                            fontWeight = FontWeight.ExtraBold
                        )
                    }
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "Confirm installation in the Android system prompt",
                            style = MaterialTheme.typography.labelMedium,
                            color = Lime
                        )
                    }
                }
            }

            UpdateState.COMPLETED -> {
                Button(
                    onClick = { onDismiss(false) },
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Lime,
                        contentColor = DarkBackground
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp)
                ) {
                    Text(
                        text = stringResource(R.string.update_action_done),
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = 15.sp
                    )
                }
            }

            UpdateState.DOWNLOAD_FAILED,
            UpdateState.VERIFY_FAILED,
            UpdateState.INSTALL_FAILED,
            UpdateState.INSTALL_CANCELLED -> {
                val hasVerifiedApk = uiState.verifiedApkFile?.exists() == true &&
                    (stage == UpdateState.INSTALL_CANCELLED || stage == UpdateState.INSTALL_FAILED)

                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TextButton(
                            onClick = { onDismiss(false) },
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier.height(48.dp)
                        ) {
                            Text(
                                text = stringResource(R.string.update_action_close),
                                color = TextSecondary,
                                fontWeight = FontWeight.SemiBold
                            )
                        }

                        Button(
                            onClick = {
                                if (hasVerifiedApk) {
                                    onInstall()
                                } else if (uiState.availableUpdate != null) {
                                    onStartDownload()
                                } else {
                                    onRetryCheck()
                                }
                            },
                            shape = RoundedCornerShape(16.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Lime,
                                contentColor = DarkBackground
                            ),
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp)
                        ) {
                            Icon(
                                imageVector = if (hasVerifiedApk) {
                                    Icons.Rounded.SystemUpdateAlt
                                } else {
                                    Icons.Rounded.Refresh
                                },
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = if (hasVerifiedApk) {
                                    stringResource(R.string.update_action_install)
                                } else {
                                    stringResource(R.string.update_action_retry)
                                },
                                fontWeight = FontWeight.ExtraBold
                            )
                        }
                    }

                    TextButton(
                        onClick = onOpenReleasePage,
                        modifier = Modifier.align(Alignment.CenterHorizontally)
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.OpenInNew,
                            contentDescription = null,
                            tint = TextSecondary,
                            modifier = Modifier.size(15.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = stringResource(R.string.update_action_open_release_page),
                            style = MaterialTheme.typography.labelMedium,
                            color = TextSecondary
                        )
                    }
                }
            }
        }
    }
}

/**
 * Custom vector glyph representing Geekify's signature audio/vinyl emblem in Lime.
 */
@Composable
private fun GeekifyBrandGlyph(modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val r = size.minDimension / 2f
        val center = Offset(size.width / 2f, size.height / 2f)

        // Outer vinyl groove arc
        drawCircle(
            color = Lime,
            radius = r * 0.88f,
            center = center,
            style = Stroke(width = r * 0.16f, cap = StrokeCap.Round)
        )
        // Inner accent groove
        drawArc(
            color = Lime.copy(alpha = 0.65f),
            startAngle = -45f,
            sweepAngle = 210f,
            useCenter = false,
            style = Stroke(width = r * 0.11f, cap = StrokeCap.Round)
        )
        // Center spindle dot
        drawCircle(
            color = Lime,
            radius = r * 0.24f,
            center = center
        )
    }
}
