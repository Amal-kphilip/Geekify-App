package com.geekify.android.ui.library

import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import coil3.compose.AsyncImage
import com.geekify.android.BuildConfig
import com.geekify.android.data.auth.AccountUser
import com.geekify.android.notifications.NotificationSettingsViewModel
import com.geekify.android.settings.AppSettingsState
import com.geekify.android.settings.AudioQualityPreference
import com.geekify.android.settings.SettingsViewModel
import com.geekify.android.ui.components.AuroraBackground
import com.geekify.android.ui.components.CircleIconButton
import com.geekify.android.ui.components.DialogButtonStyle
import com.geekify.android.ui.components.DialogPillButton
import com.geekify.android.ui.components.GeekifyDialog
import com.geekify.android.ui.components.LocalBottomInset
import com.geekify.android.ui.components.bouncyClickable
import com.geekify.android.ui.glass.GlassStyle
import com.geekify.android.ui.glass.LocalGlassBackdrop
import com.geekify.android.ui.glass.glassBackdropSource
import com.geekify.android.ui.glass.liquidGlass
import com.geekify.android.ui.glass.rememberGlassBackdrop
import com.geekify.android.ui.navigation.FloatingTabBarInlineBehavior
import com.geekify.android.ui.theme.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

private enum class SettingsSubPage(val title: String) {
    ROOT("Settings"),
    APPEARANCE("Appearance & Glass"),
    PLAYER("Player & Audio"),
    CONTENT("Content & Region"),
    NOTIFICATIONS("Notifications"),
    PRIVACY("Privacy & Security"),
    STORAGE("Storage & Backup"),
    UPDATES("Software Update"),
    ABOUT("About")
}

// Vibrant icon tile palette
private val TilePink = Color(0xFFFF2D55)
private val TileRed = Color(0xFFFF3B30)
private val TileOrange = Color(0xFFFF9500)
private val TileAmber = Color(0xFFFFCC00)
private val TileGreen = Color(0xFF34C759)
private val TileMint = Color(0xFF00C7BE)
private val TileTeal = Color(0xFF30B0C7)
private val TileCyan = Color(0xFF32ADE6)
private val TileBlue = Color(0xFF007AFF)
private val TileIndigo = Color(0xFF5856D6)
private val TilePurple = Color(0xFFAF52DE)
private val TileGray = Color(0xFF8E8E93)

private val COUNTRIES = listOf(
    "IN" to "India",
    "US" to "United States",
    "GB" to "United Kingdom",
    "CA" to "Canada",
    "AU" to "Australia",
    "DE" to "Germany",
    "FR" to "France",
    "JP" to "Japan",
    "KR" to "South Korea",
    "BR" to "Brazil",
    "SG" to "Singapore",
    "AE" to "United Arab Emirates"
)

private val LANGUAGES = listOf(
    "en" to "English",
    "hi" to "Hindi (हिन्दी)",
    "ml" to "Malayalam (മലയാളം)",
    "ta" to "Tamil (தமிழ்)",
    "te" to "Telugu (తెలుగు)",
    "es" to "Spanish (Español)",
    "fr" to "French (Français)",
    "de" to "German (Deutsch)",
    "ja" to "Japanese (日本語)",
    "ko" to "Korean (한국어)",
    "pt" to "Portuguese (Português)"
)

@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onAudioClick: () -> Unit = {},
    onAppIconClick: () -> Unit = {},
    onProfileClick: () -> Unit = {},
    onListenTogetherClick: () -> Unit = {},
    onListeningStatsClick: () -> Unit = {},
    onRecentsClick: () -> Unit = {},
    onHealthClick: () -> Unit = {},
    onCheckUpdatesClick: () -> Unit = {},
    currentUser: AccountUser? = null,
    notificationSettings: NotificationSettingsViewModel = hiltViewModel(),
    settingsViewModel: SettingsViewModel = hiltViewModel()
) {
    val notif by notificationSettings.prefs.collectAsState()
    val state by settingsViewModel.settings.collectAsState()
    val storageStats by settingsViewModel.storageStats.collectAsState()
    val statusMessage by settingsViewModel.statusMessage.collectAsState()
    val bottomInset = LocalBottomInset.current

    var currentPage by rememberSaveable { mutableStateOf(SettingsSubPage.ROOT) }
    var searchQuery by rememberSaveable { mutableStateOf("") }
    val rootScrollState = rememberSaveable(saver = ScrollState.Saver) { ScrollState(0) }

    BackHandler(enabled = currentPage != SettingsSubPage.ROOT) {
        currentPage = SettingsSubPage.ROOT
    }

    val createBackupLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri != null) settingsViewModel.exportBackup(uri)
    }

    val openBackupLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) settingsViewModel.importBackup(uri)
    }

    AuroraBackground {
        Box(modifier = Modifier.fillMaxSize()) {
            AnimatedContent(
                targetState = currentPage,
                transitionSpec = {
                    if (targetState != SettingsSubPage.ROOT) {
                        (slideInHorizontally(tween(260)) { it / 3 } + fadeIn(tween(220)))
                            .togetherWith(slideOutHorizontally(tween(220)) { -it / 4 } + fadeOut(tween(160)))
                    } else {
                        (slideInHorizontally(tween(260)) { -it / 4 } + fadeIn(tween(220)))
                            .togetherWith(slideOutHorizontally(tween(220)) { it / 3 } + fadeOut(tween(160)))
                    }
                },
                label = "settingsSubPage"
            ) { page ->
                val pageScroll = if (page == SettingsSubPage.ROOT) {
                    rootScrollState
                } else {
                    rememberSaveable(page.name, saver = ScrollState.Saver) { ScrollState(0) }
                }
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .statusBarsPadding()
                        .padding(horizontal = 20.dp)
                        .verticalScroll(pageScroll)
                ) {
                    // Top Bar
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 14.dp, bottom = 14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CircleIconButton(
                            icon = GeekifyIcons.ArrowBack,
                            contentDescription = "Back",
                            onClick = {
                                if (page == SettingsSubPage.ROOT) onBack()
                                else currentPage = SettingsSubPage.ROOT
                            },
                            size = 48.dp
                        )
                        Text(
                            text = page.title,
                            color = TextPrimary,
                            fontSize = 20.sp,
                            fontWeight = FontWeight.SemiBold,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.weight(1f)
                        )
                        Spacer(Modifier.size(48.dp))
                    }

                    // Toast / Status Banner
                    AnimatedVisibility(visible = statusMessage != null) {
                        statusMessage?.let { msg ->
                            Surface(
                                color = Lime.copy(alpha = 0.16f),
                                shape = RoundedCornerShape(18.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(bottom = 12.dp)
                                    .border(1.dp, Lime.copy(alpha = 0.45f), RoundedCornerShape(18.dp))
                                    .clickable { settingsViewModel.clearStatusMessage() }
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(GeekifyIcons.CheckCircle, contentDescription = null, tint = Lime, modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.width(10.dp))
                                    Text(msg, color = TextPrimary, fontSize = 13.5.sp, modifier = Modifier.weight(1f))
                                    Icon(GeekifyIcons.Close, contentDescription = "Dismiss", tint = TextSecondary, modifier = Modifier.size(16.dp))
                                }
                            }
                        }
                    }

                    when (page) {
                        SettingsSubPage.ROOT -> RootSettingsContent(
                            searchQuery = searchQuery,
                            onSearchQueryChange = { searchQuery = it },
                            currentUser = currentUser,
                            state = state,
                            storageTotalBytes = storageStats.totalBytes,
                            onProfileClick = onProfileClick,
                            onListenTogetherClick = onListenTogetherClick,
                            onListeningStatsClick = onListeningStatsClick,
                            onRecentsClick = onRecentsClick,
                            onAudioClick = onAudioClick,
                            onAppIconClick = onAppIconClick,
                            onHealthClick = onHealthClick,
                            onOpenSubPage = { currentPage = it }
                        )

                        SettingsSubPage.APPEARANCE -> AppearanceGlassSubPage(
                            state = state,
                            viewModel = settingsViewModel,
                            onAppIconClick = onAppIconClick
                        )

                        SettingsSubPage.PLAYER -> PlayerAudioSubPage(
                            state = state,
                            viewModel = settingsViewModel,
                            onAudioClick = onAudioClick
                        )

                        SettingsSubPage.CONTENT -> ContentRegionSubPage(
                            state = state,
                            viewModel = settingsViewModel
                        )

                        SettingsSubPage.NOTIFICATIONS -> NotificationsSubPage(
                            updates = notif.updates,
                            tips = notif.tips,
                            engagement = notif.engagement,
                            onUpdatesChange = notificationSettings::setUpdates,
                            onTipsChange = notificationSettings::setTips,
                            onEngagementChange = notificationSettings::setEngagement
                        )

                        SettingsSubPage.PRIVACY -> PrivacySubPage(
                            state = state,
                            viewModel = settingsViewModel
                        )

                        SettingsSubPage.STORAGE -> StorageBackupSubPage(
                            stats = storageStats,
                            viewModel = settingsViewModel,
                            onExportBackup = {
                                val dateStr = SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(Date())
                                createBackupLauncher.launch("geekify_backup_$dateStr.json")
                            },
                            onImportBackup = {
                                openBackupLauncher.launch(arrayOf("application/json", "*/*"))
                            }
                        )

                        SettingsSubPage.UPDATES -> UpdatesSubPage(
                            updateCacheBytes = storageStats.updateCacheBytes,
                            onCheckUpdatesClick = onCheckUpdatesClick,
                            onClearUpdateCache = settingsViewModel::clearUpdateCache
                        )

                        SettingsSubPage.ABOUT -> AboutSubPage()
                    }

                    Spacer(Modifier.height(bottomInset + 28.dp))
                }
            }
        }
    }
}

@Composable
private fun RootSettingsContent(
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    currentUser: AccountUser?,
    state: AppSettingsState,
    storageTotalBytes: Long,
    onProfileClick: () -> Unit,
    onListenTogetherClick: () -> Unit,
    onListeningStatsClick: () -> Unit,
    onRecentsClick: () -> Unit,
    onAudioClick: () -> Unit,
    onAppIconClick: () -> Unit,
    onHealthClick: () -> Unit,
    onOpenSubPage: (SettingsSubPage) -> Unit
) {
    // Search Bar
    SettingsSearchBar(
        query = searchQuery,
        onQueryChange = onSearchQueryChange
    )
    Spacer(Modifier.height(16.dp))

    val q = searchQuery.trim().lowercase()
    fun matches(vararg terms: String): Boolean {
        if (q.isEmpty()) return true
        return terms.any { it.lowercase().contains(q) }
    }

    // Profile Card at Top
    if (matches("profile", "account", "sync", "sign in", "cloud", currentUser?.name.orEmpty(), currentUser?.email.orEmpty())) {
        ProfileSettingsCard(user = currentUser, onClick = onProfileClick)
        Spacer(Modifier.height(18.dp))
    }

    // Group 1: Social & Activity
    val showListenTogether = matches("listen together", "room", "party", "sync", "friends")
    val showStats = matches("listening statistics", "stats", "analytics", "top songs", "minutes", "artists")
    val showHistory = matches("recents", "listening history", "recently played", "tracks")
    if (showListenTogether || showStats || showHistory) {
        SettingsSectionHeader("ACTIVITY & SOCIAL")
        IosSettingsGroup {
            val items = buildList<@Composable () -> Unit> {
                if (showListenTogether) {
                    add {
                        IosSettingsRow(
                            icon = GeekifyIcons.Groups,
                            tileColor = TilePink,
                            title = "Listen Together",
                            subtitle = "Host or join live synchronized listening rooms",
                            onClick = onListenTogetherClick
                        )
                    }
                }
                if (showStats) {
                    add {
                        IosSettingsRow(
                            icon = GeekifyIcons.Insights,
                            tileColor = TilePurple,
                            title = "Listening Statistics",
                            subtitle = "Your top tracks, artists, and listening streaks",
                            onClick = onListeningStatsClick
                        )
                    }
                }
                if (showHistory) {
                    add {
                        IosSettingsRow(
                            icon = GeekifyIcons.History,
                            tileColor = TileOrange,
                            title = "Recents & History",
                            subtitle = "Recently played songs across your sessions",
                            onClick = onRecentsClick
                        )
                    }
                }
            }
            items.forEachIndexed { idx, item ->
                item()
                if (idx < items.lastIndex) SettingsGroupDivider()
            }
        }
        Spacer(Modifier.height(18.dp))
    }

    // Group 2: Experience & Customization
    val showAppearance = matches("appearance", "liquid glass", "blur", "refraction", "lens", "saturation", "navigation bar", "adaptive", "default tab", "theme")
    val showAppIcon = matches("app icon", "launcher icon", "logo")
    val showPlayer = matches("player", "audio", "quality", "stream", "persistent queue", "autoplay", "shuffle", "repeat", "keep screen on", "error")
    val showEq = matches("equalizer", "bass", "tempo", "speed", "pitch", "skip silence", "loudness", "normalization")
    val showContent = matches("content", "region", "country", "language", "explicit", "locale")
    val showNotif = matches("notifications", "alerts", "reminders", "tips", "updates")

    if (showAppearance || showAppIcon || showPlayer || showEq || showContent || showNotif) {
        SettingsSectionHeader("EXPERIENCE & PLAYBACK")
        IosSettingsGroup {
            val items = buildList<@Composable () -> Unit> {
                if (showAppearance) {
                    add {
                        IosSettingsRow(
                            icon = GeekifyIcons.AutoAwesome,
                            tileColor = TileCyan,
                            title = "Appearance & Liquid Glass",
                            subtitle = if (state.glass.enabled) "Liquid Glass On • ${state.glass.blurDp.roundToInt()}dp blur" else "Liquid Glass Off",
                            onClick = { onOpenSubPage(SettingsSubPage.APPEARANCE) }
                        )
                    }
                }
                if (showAppIcon) {
                    add {
                        IosSettingsRow(
                            icon = GeekifyIcons.Apps,
                            tileColor = TileIndigo,
                            title = "App Icon",
                            subtitle = "Choose the Geekify launcher icon",
                            onClick = onAppIconClick
                        )
                    }
                }
                if (showPlayer) {
                    add {
                        IosSettingsRow(
                            icon = GeekifyIcons.PlayCircleFilled,
                            tileColor = TileRed,
                            title = "Player & Playback",
                            subtitle = "${state.audioQuality.label} quality • Queue & autoplay",
                            onClick = { onOpenSubPage(SettingsSubPage.PLAYER) }
                        )
                    }
                }
                if (showEq) {
                    add {
                        IosSettingsRow(
                            icon = GeekifyIcons.GraphicEq,
                            tileColor = TileOrange,
                            title = "Equalizer & Audio Effects",
                            subtitle = "5-band EQ, presets, speed, pitch, normalization",
                            onClick = onAudioClick
                        )
                    }
                }
                if (showContent) {
                    val countryName = COUNTRIES.firstOrNull { it.first == state.contentCountry }?.second ?: state.contentCountry
                    add {
                        IosSettingsRow(
                            icon = GeekifyIcons.Language,
                            tileColor = TileBlue,
                            title = "Content & Region",
                            subtitle = "$countryName • ${if (state.hideExplicit) "Explicit hidden" else "All content"}",
                            onClick = { onOpenSubPage(SettingsSubPage.CONTENT) }
                        )
                    }
                }
                if (showNotif) {
                    add {
                        IosSettingsRow(
                            icon = GeekifyIcons.Notifications,
                            tileColor = TileAmber,
                            title = "Notifications",
                            subtitle = "Updates, feature tips & listening reminders",
                            onClick = { onOpenSubPage(SettingsSubPage.NOTIFICATIONS) }
                        )
                    }
                }
            }
            items.forEachIndexed { idx, item ->
                item()
                if (idx < items.lastIndex) SettingsGroupDivider()
            }
        }
        Spacer(Modifier.height(18.dp))
    }

    // Group 3: Data, System & About
    val showPrivacy = matches("privacy", "security", "pause history", "clear history", "screenshots", "incognito")
    val showStorage = matches("storage", "cache", "backup", "restore", "export", "import", "disk", "clear cache")
    val showUpdates = matches("software update", "update", "version", "github", "apk")
    val showHealth = matches("extractor health", "diagnostics", "innertube", "stream", "status")
    val showAbout = matches("about", "version", "credits", "github", "geekify")

    if (showPrivacy || showStorage || showUpdates || showHealth || showAbout) {
        SettingsSectionHeader("DATA & SYSTEM")
        IosSettingsGroup {
            val items = buildList<@Composable () -> Unit> {
                if (showPrivacy) {
                    add {
                        IosSettingsRow(
                            icon = GeekifyIcons.Shield,
                            tileColor = TileIndigo,
                            title = "Privacy & Security",
                            subtitle = if (state.pauseListenHistory) "History paused" else "History & screenshot controls",
                            onClick = { onOpenSubPage(SettingsSubPage.PRIVACY) }
                        )
                    }
                }
                if (showStorage) {
                    add {
                        IosSettingsRow(
                            icon = GeekifyIcons.Storage,
                            tileColor = TileGreen,
                            title = "Storage, Cache & Backup",
                            subtitle = "${formatBytes(storageTotalBytes)} used • Library backup & restore",
                            onClick = { onOpenSubPage(SettingsSubPage.STORAGE) }
                        )
                    }
                }
                if (showUpdates) {
                    add {
                        IosSettingsRow(
                            icon = GeekifyIcons.SystemUpdate,
                            tileColor = TileTeal,
                            title = "Software Update",
                            subtitle = "v${BuildConfig.VERSION_NAME} installed • Check for updates",
                            onClick = { onOpenSubPage(SettingsSubPage.UPDATES) }
                        )
                    }
                }
                if (showHealth) {
                    add {
                        IosSettingsRow(
                            icon = GeekifyIcons.MonitorHeart,
                            tileColor = TileMint,
                            title = "Extractor Health",
                            subtitle = "Stream resolver & YouTube client diagnostics",
                            onClick = onHealthClick
                        )
                    }
                }
                if (showAbout) {
                    add {
                        IosSettingsRow(
                            icon = GeekifyIcons.Info,
                            tileColor = TileGray,
                            title = "About Geekify",
                            subtitle = "Version ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                            onClick = { onOpenSubPage(SettingsSubPage.ABOUT) }
                        )
                    }
                }
            }
            items.forEachIndexed { idx, item ->
                item()
                if (idx < items.lastIndex) SettingsGroupDivider()
            }
        }
    }
}

// ============================================================================
// SUB-PAGE 1: APPEARANCE & LIQUID GLASS
// ============================================================================
@Composable
private fun AppearanceGlassSubPage(
    state: AppSettingsState,
    viewModel: SettingsViewModel,
    onAppIconClick: () -> Unit
) {
    val glass = state.glass

    // Live interactive Liquid Glass preview card
    SettingsSectionHeader("LIVE GLASS PREVIEW")
    val previewBackdrop = rememberGlassBackdrop()
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(158.dp)
            .clip(RoundedCornerShape(28.dp))
            .border(1.dp, Color.White.copy(alpha = 0.10f), RoundedCornerShape(28.dp))
    ) {
        // Vibrant background scene recorded into previewBackdrop
        Box(
            modifier = Modifier
                .fillMaxSize()
                .glassBackdropSource(previewBackdrop)
                .background(
                    Brush.linearGradient(
                        colors = listOf(
                            Color(0xFF0F172A),
                            Color(0xFF4F46E5),
                            Color(0xFFEC4899),
                            Color(0xFF10B981)
                        )
                    )
                )
        ) {
            // Colorful orbs & text behind glass so refraction and blur are clearly visible
            Box(
                modifier = Modifier
                    .size(110.dp)
                    .offset(x = 24.dp, y = 16.dp)
                    .clip(CircleShape)
                    .background(Lime.copy(alpha = 0.75f))
            )
            Box(
                modifier = Modifier
                    .size(95.dp)
                    .align(Alignment.BottomEnd)
                    .offset(x = (-28).dp, y = (-12).dp)
                    .clip(CircleShape)
                    .background(Color(0xFF38BDF8).copy(alpha = 0.85f))
            )
            Text(
                text = "GEEKIFY LIQUID GLASS",
                color = Color.White.copy(alpha = 0.55f),
                fontSize = 18.sp,
                fontWeight = FontWeight.Black,
                letterSpacing = 2.sp,
                modifier = Modifier.align(Alignment.Center)
            )
        }

        // Foreground Liquid Glass capsule sampling previewBackdrop
        CompositionLocalProvider(LocalGlassBackdrop provides previewBackdrop) {
            Row(
                modifier = Modifier
                    .align(Alignment.Center)
                    .fillMaxWidth(0.86f)
                    .height(64.dp)
                    .liquidGlass(RoundedCornerShape(32.dp), GlassStyle.Preview)
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(Lime),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(GeekifyIcons.AutoAwesome, contentDescription = null, tint = OnAccent, modifier = Modifier.size(20.dp))
                }
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text("Crystal Liquid Glass", color = TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    Text(
                        text = if (glass.enabled) "${glass.blurDp.roundToInt()}dp blur • ${(glass.saturation * 100).roundToInt()}% vibrancy" else "Glass disabled",
                        color = TextSecondary,
                        fontSize = 12.sp
                    )
                }
            }
        }
    }

    Spacer(Modifier.height(18.dp))
    SettingsSectionHeader("LIQUID GLASS ENGINE")
    IosSettingsGroup {
        IosSettingsSwitchRow(
            icon = GeekifyIcons.BlurOn,
            tileColor = TileCyan,
            title = "Enable Liquid Glass",
            subtitle = "Real-time AGSL lens refraction and backdrop blur",
            checked = glass.enabled,
            onCheckedChange = viewModel::setGlassEnabled
        )
        if (glass.enabled) {
            SettingsGroupDivider()
            IosSettingsSwitchRow(
                icon = GeekifyIcons.MusicNote,
                tileColor = TilePink,
                title = "Mini Player Glass",
                subtitle = "Use clear liquid glass on the floating Now Playing bar",
                checked = glass.miniPlayerGlass,
                onCheckedChange = viewModel::setGlassMiniPlayer
            )
            SettingsGroupDivider()
            IosSettingsSwitchRow(
                icon = GeekifyIcons.Navigation,
                tileColor = TileBlue,
                title = "Navigation Bar Glass",
                subtitle = "Use clear liquid glass on the adaptive bottom bar",
                checked = glass.navBarGlass,
                onCheckedChange = viewModel::setGlassNavBar
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                SettingsGroupDivider()
                IosSettingsSwitchRow(
                    icon = GeekifyIcons.Flare,
                    tileColor = TilePurple,
                    title = "Chromatic Aberration",
                    subtitle = "Optical RGB color dispersion along curved glass edges",
                    checked = glass.chromaticAberration,
                    onCheckedChange = viewModel::setGlassChromaticAberration
                )
                SettingsGroupDivider()
                IosSettingsSwitchRow(
                    icon = GeekifyIcons.Layers,
                    tileColor = TileIndigo,
                    title = "Spherical Depth Effect",
                    subtitle = "3D perspective magnification across the surface",
                    checked = glass.depthEffect,
                    onCheckedChange = viewModel::setGlassDepthEffect
                )
            }
        }
    }

    if (glass.enabled) {
        Spacer(Modifier.height(18.dp))
        SettingsSectionHeader("OPTICAL TUNING")
        IosSettingsGroup {
            IosSettingsSliderRow(
                title = "Backdrop Blur Radius",
                valueLabel = "${glass.blurDp.roundToInt()} dp",
                value = glass.blurDp,
                valueRange = 0f..20f,
                onValueChange = viewModel::setGlassBlurDp
            )
            SettingsGroupDivider()
            IosSettingsSliderRow(
                title = "Backdrop Vibrancy (Saturation)",
                valueLabel = "${"%.1f".format(Locale.US, glass.saturation)}x",
                value = glass.saturation,
                valueRange = 0.8f..2.5f,
                onValueChange = viewModel::setGlassSaturation
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                SettingsGroupDivider()
                IosSettingsSliderRow(
                    title = "Edge Refraction Warp",
                    valueLabel = "${glass.lensAmountDp.roundToInt()} dp",
                    value = glass.lensAmountDp,
                    valueRange = 0f..28f,
                    onValueChange = viewModel::setGlassLensAmountDp
                )
                SettingsGroupDivider()
                IosSettingsSliderRow(
                    title = "Refraction Bevel Depth",
                    valueLabel = "${glass.lensHeightDp.roundToInt()} dp",
                    value = glass.lensHeightDp,
                    valueRange = 0f..28f,
                    onValueChange = viewModel::setGlassLensHeightDp
                )
            }
            SettingsGroupDivider()
            IosSettingsSliderRow(
                title = "Surface Tint Opacity",
                valueLabel = "${(glass.tintAlpha * 100).roundToInt()}%",
                value = glass.tintAlpha,
                valueRange = 0f..0.50f,
                onValueChange = viewModel::setGlassTintAlpha
            )
            SettingsGroupDivider()
            IosSettingsRow(
                icon = GeekifyIcons.RestartAlt,
                tileColor = TileGray,
                title = "Reset Liquid Glass Defaults",
                subtitle = "Restore crystal-clear 3dp optical preset",
                onClick = viewModel::resetGlassDefaults
            )
        }
    }

    Spacer(Modifier.height(18.dp))
    SettingsSectionHeader("NAVIGATION & LAUNCH")
    IosSettingsGroup {
        var showNavBehaviorDialog by remember { mutableStateOf(false) }
        var showDefaultTabDialog by remember { mutableStateOf(false) }

        val behaviorLabel = when (state.navInlineBehavior) {
            FloatingTabBarInlineBehavior.OnScrollDown -> "Collapse on scroll down"
            FloatingTabBarInlineBehavior.ExpandedAtTopOnly -> "Expanded at top only"
            FloatingTabBarInlineBehavior.Never -> "Always expanded"
            FloatingTabBarInlineBehavior.OnScrollUp -> "Collapse on scroll up"
        }
        IosSettingsRow(
            icon = GeekifyIcons.ViewDay,
            tileColor = TileTeal,
            title = "Adaptive Bottom Bar",
            subtitle = behaviorLabel,
            onClick = { showNavBehaviorDialog = true }
        )
        SettingsGroupDivider()
        val tabLabel = when (state.defaultTab) {
            "search" -> "Search"
            "library" -> "Library"
            else -> "Home"
        }
        IosSettingsRow(
            icon = GeekifyIcons.Home,
            tileColor = TileGreen,
            title = "Default Launch Tab",
            subtitle = tabLabel,
            onClick = { showDefaultTabDialog = true }
        )
        SettingsGroupDivider()
        IosSettingsRow(
            icon = GeekifyIcons.Apps,
            tileColor = TileIndigo,
            title = "App Launcher Icon",
            subtitle = "Customize Geekify's home screen icon",
            onClick = onAppIconClick
        )

        if (showNavBehaviorDialog) {
            OptionPickerDialog(
                title = "Adaptive Bottom Bar",
                options = listOf(
                    FloatingTabBarInlineBehavior.OnScrollDown to ("Collapse on scroll down" to "Collapses into compact inline pills when scrolling down"),
                    FloatingTabBarInlineBehavior.ExpandedAtTopOnly to ("Expanded at top only" to "Stays compact whenever scrolled away from the top"),
                    FloatingTabBarInlineBehavior.Never to ("Always expanded" to "Never collapses the navigation capsule")
                ),
                selected = state.navInlineBehavior,
                onSelect = {
                    viewModel.setNavInlineBehavior(it)
                    showNavBehaviorDialog = false
                },
                onDismiss = { showNavBehaviorDialog = false }
            )
        }

        if (showDefaultTabDialog) {
            OptionPickerDialog(
                title = "Default Launch Tab",
                options = listOf(
                    "home" to ("Home" to "Open your personalized music feed on startup"),
                    "search" to ("Search" to "Open Search when Geekify starts"),
                    "library" to ("Library" to "Open Your Library when Geekify starts")
                ),
                selected = state.defaultTab,
                onSelect = {
                    viewModel.setDefaultTab(it)
                    showDefaultTabDialog = false
                },
                onDismiss = { showDefaultTabDialog = false }
            )
        }
    }
}

// ============================================================================
// SUB-PAGE 2: PLAYER & AUDIO
// ============================================================================
@Composable
private fun PlayerAudioSubPage(
    state: AppSettingsState,
    viewModel: SettingsViewModel,
    onAudioClick: () -> Unit
) {
    var showQualityDialog by remember { mutableStateOf(false) }

    SettingsSectionHeader("AUDIO & STREAMING")
    IosSettingsGroup {
        IosSettingsRow(
            icon = GeekifyIcons.GraphicEq,
            tileColor = TileOrange,
            title = "Equalizer & Audio Effects",
            subtitle = "5-band equalizer, presets, tempo, pitch & loudness",
            onClick = onAudioClick
        )
        SettingsGroupDivider()
        IosSettingsRow(
            icon = GeekifyIcons.HighQuality,
            tileColor = TileRed,
            title = "Streaming Audio Quality",
            subtitle = "${state.audioQuality.label} — ${state.audioQuality.subtitle}",
            onClick = { showQualityDialog = true }
        )
    }

    Spacer(Modifier.height(18.dp))
    SettingsSectionHeader("QUEUE & PLAYBACK BEHAVIOR")
    IosSettingsGroup {
        IosSettingsSwitchRow(
            icon = GeekifyIcons.QueueMusic,
            tileColor = TileBlue,
            title = "Persistent Queue",
            subtitle = "Save and restore your queue and playback position across restarts",
            checked = state.persistentQueue,
            onCheckedChange = viewModel::setPersistentQueue
        )
        SettingsGroupDivider()
        IosSettingsSwitchRow(
            icon = GeekifyIcons.AutoMode,
            tileColor = TilePurple,
            title = "Endless Autoplay",
            subtitle = "Automatically queue related songs when your queue runs out",
            checked = state.autoplayRelated,
            onCheckedChange = viewModel::setAutoplayRelated
        )
        SettingsGroupDivider()
        IosSettingsSwitchRow(
            icon = GeekifyIcons.Shuffle,
            tileColor = TileGreen,
            title = "Remember Shuffle & Repeat",
            subtitle = "Keep shuffle and repeat modes when reopening the app",
            checked = state.rememberShuffleRepeat,
            onCheckedChange = viewModel::setRememberShuffleRepeat
        )
        SettingsGroupDivider()
        IosSettingsSwitchRow(
            icon = GeekifyIcons.FastForward,
            tileColor = TilePink,
            title = "Auto-skip on Stream Error",
            subtitle = "Automatically advance to the next song if a track cannot be played",
            checked = state.autoSkipOnError,
            onCheckedChange = viewModel::setAutoSkipOnError
        )
        SettingsGroupDivider()
        IosSettingsSwitchRow(
            icon = GeekifyIcons.LightMode,
            tileColor = TileAmber,
            title = "Keep Screen On in Player",
            subtitle = "Prevent the display from sleeping while Expanded Player is open",
            checked = state.keepScreenOn,
            onCheckedChange = viewModel::setKeepScreenOn
        )
    }

    if (showQualityDialog) {
        OptionPickerDialog(
            title = "Streaming Audio Quality",
            options = AudioQualityPreference.entries.map { it to (it.label to it.subtitle) },
            selected = state.audioQuality,
            onSelect = {
                viewModel.setAudioQuality(it)
                showQualityDialog = false
            },
            onDismiss = { showQualityDialog = false }
        )
    }
}

// ============================================================================
// SUB-PAGE 3: CONTENT & REGION
// ============================================================================
@Composable
private fun ContentRegionSubPage(
    state: AppSettingsState,
    viewModel: SettingsViewModel
) {
    var showCountryDialog by remember { mutableStateOf(false) }
    var showLanguageDialog by remember { mutableStateOf(false) }

    val countryName = COUNTRIES.firstOrNull { it.first == state.contentCountry }?.second ?: state.contentCountry
    val languageName = LANGUAGES.firstOrNull { it.first == state.contentLanguage }?.second ?: state.contentLanguage

    SettingsSectionHeader("CATALOG REGION & LANGUAGE")
    IosSettingsGroup {
        IosSettingsRow(
            icon = GeekifyIcons.Public,
            tileColor = TileBlue,
            title = "Content Country",
            subtitle = "$countryName (${state.contentCountry}) — Charts & new releases region",
            onClick = { showCountryDialog = true }
        )
        SettingsGroupDivider()
        IosSettingsRow(
            icon = GeekifyIcons.Translate,
            tileColor = TileTeal,
            title = "Content Language",
            subtitle = "$languageName — YouTube Music catalog language",
            onClick = { showLanguageDialog = true }
        )
    }

    Spacer(Modifier.height(18.dp))
    SettingsSectionHeader("CONTENT RESTRICTIONS")
    IosSettingsGroup {
        IosSettingsSwitchRow(
            icon = GeekifyIcons.Explicit,
            tileColor = TileRed,
            title = "Hide Explicit Content",
            subtitle = "Filter out explicit tracks from Home, Search, and Autoplay",
            checked = state.hideExplicit,
            onCheckedChange = viewModel::setHideExplicit
        )
    }

    if (showCountryDialog) {
        OptionPickerDialog(
            title = "Content Country",
            options = COUNTRIES.map { (code, name) -> code to (name to "Region code: $code") },
            selected = state.contentCountry,
            onSelect = {
                viewModel.setContentCountry(it)
                showCountryDialog = false
            },
            onDismiss = { showCountryDialog = false }
        )
    }

    if (showLanguageDialog) {
        OptionPickerDialog(
            title = "Content Language",
            options = LANGUAGES.map { (code, name) -> code to (name to "Language code: $code") },
            selected = state.contentLanguage,
            onSelect = {
                viewModel.setContentLanguage(it)
                showLanguageDialog = false
            },
            onDismiss = { showLanguageDialog = false }
        )
    }
}

// ============================================================================
// SUB-PAGE 4: NOTIFICATIONS
// ============================================================================
@Composable
private fun NotificationsSubPage(
    updates: Boolean,
    tips: Boolean,
    engagement: Boolean,
    onUpdatesChange: (Boolean) -> Unit,
    onTipsChange: (Boolean) -> Unit,
    onEngagementChange: (Boolean) -> Unit
) {
    SettingsSectionHeader("IN-APP & PUSH NOTIFICATIONS")
    IosSettingsGroup {
        IosSettingsSwitchRow(
            icon = GeekifyIcons.SystemUpdate,
            tileColor = TileGreen,
            title = "App Updates",
            subtitle = "Notify me when a new Geekify release is available",
            checked = updates,
            onCheckedChange = onUpdatesChange
        )
        SettingsGroupDivider()
        IosSettingsSwitchRow(
            icon = GeekifyIcons.Lightbulb,
            tileColor = TileAmber,
            title = "Tips & New Features",
            subtitle = "Occasional highlights after an app update",
            checked = tips,
            onCheckedChange = onTipsChange
        )
        SettingsGroupDivider()
        IosSettingsSwitchRow(
            icon = GeekifyIcons.LibraryMusic,
            tileColor = TilePurple,
            title = "Personalized Listening Reminders",
            subtitle = "Based on recent and frequently played songs on this device",
            checked = engagement,
            onCheckedChange = onEngagementChange
        )
    }
    Spacer(Modifier.height(10.dp))
    Text(
        text = "Media playback controls are managed automatically while music is playing.",
        color = TextMuted,
        fontSize = 12.sp,
        modifier = Modifier.padding(horizontal = 12.dp)
    )
}

// ============================================================================
// SUB-PAGE 5: PRIVACY & SECURITY
// ============================================================================
@Composable
private fun PrivacySubPage(
    state: AppSettingsState,
    viewModel: SettingsViewModel
) {
    var confirmClearHistory by remember { mutableStateOf(false) }

    SettingsSectionHeader("LISTENING HISTORY")
    IosSettingsGroup {
        IosSettingsSwitchRow(
            icon = GeekifyIcons.PauseCircleFilled,
            tileColor = TileOrange,
            title = "Pause Listening History",
            subtitle = "Do not record played songs, play counts, or listening stats",
            checked = state.pauseListenHistory,
            onCheckedChange = viewModel::setPauseListenHistory
        )
        SettingsGroupDivider()
        IosSettingsRow(
            icon = GeekifyIcons.DeleteSweep,
            tileColor = TileRed,
            title = "Clear Listening History & Stats",
            subtitle = "Permanently delete local recent tracks, play counts, and sessions",
            onClick = { confirmClearHistory = true }
        )
    }

    Spacer(Modifier.height(18.dp))
    SettingsSectionHeader("SCREEN SECURITY")
    IosSettingsGroup {
        IosSettingsSwitchRow(
            icon = GeekifyIcons.Security,
            tileColor = TileIndigo,
            title = "Disable Screenshots",
            subtitle = "Block screenshots and hide app preview in the recents switcher",
            checked = state.disableScreenshots,
            onCheckedChange = viewModel::setDisableScreenshots
        )
    }

    if (confirmClearHistory) {
        GeekifyDialog(onDismissRequest = { confirmClearHistory = false }) {
            Text("Clear Listening History?", color = TextPrimary, fontSize = 19.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Text(
                "This will clear your Recents list, top play counts, and listening statistics on this device.",
                color = TextSecondary,
                fontSize = 14.sp
            )
            Spacer(Modifier.height(20.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                DialogPillButton(
                    text = "Cancel",
                    onClick = { confirmClearHistory = false },
                    style = DialogButtonStyle.Secondary,
                    modifier = Modifier.weight(1f)
                )
                DialogPillButton(
                    text = "Clear All",
                    onClick = {
                        viewModel.clearListenHistory()
                        confirmClearHistory = false
                    },
                    style = DialogButtonStyle.Destructive,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

// ============================================================================
// SUB-PAGE 6: STORAGE, CACHE & BACKUP
// ============================================================================
@Composable
private fun StorageBackupSubPage(
    stats: com.geekify.android.settings.StorageStats,
    viewModel: SettingsViewModel,
    onExportBackup: () -> Unit,
    onImportBackup: () -> Unit
) {
    LaunchedEffect(Unit) {
        viewModel.refreshStorageStats()
    }

    SettingsSectionHeader("STORAGE USAGE (${formatBytes(stats.totalBytes)})")
    IosSettingsGroup {
        IosSettingsRow(
            icon = GeekifyIcons.Audiotrack,
            tileColor = TileGreen,
            title = "Clear Audio Stream Cache",
            subtitle = "${formatBytes(stats.audioCacheBytes)} cached (300 MB limit)",
            onClick = viewModel::clearAudioCache
        )
        SettingsGroupDivider()
        IosSettingsRow(
            icon = GeekifyIcons.Image,
            tileColor = TileCyan,
            title = "Clear Artwork & Image Cache",
            subtitle = "${formatBytes(stats.imageCacheBytes)} cached album & artist covers",
            onClick = viewModel::clearImageCache
        )
        SettingsGroupDivider()
        IosSettingsRow(
            icon = GeekifyIcons.Dataset,
            tileColor = TileOrange,
            title = "Prune Cached Feed & Metadata",
            subtitle = "${formatBytes(stats.metadataBytes)} local database & catalog",
            onClick = viewModel::clearMetadataCache
        )
    }

    Spacer(Modifier.height(18.dp))
    SettingsSectionHeader("LOCAL LIBRARY BACKUP & RESTORE")
    IosSettingsGroup {
        IosSettingsRow(
            icon = GeekifyIcons.UploadFile,
            tileColor = TileBlue,
            title = "Backup Library to JSON",
            subtitle = "Export liked songs, playlists, saved albums & history",
            onClick = onExportBackup
        )
        SettingsGroupDivider()
        IosSettingsRow(
            icon = GeekifyIcons.Download,
            tileColor = TilePurple,
            title = "Restore Library from Backup",
            subtitle = "Import and merge a Geekify .json backup file",
            onClick = onImportBackup
        )
    }
}

// ============================================================================
// SUB-PAGE 7: SOFTWARE UPDATE
// ============================================================================
@Composable
private fun UpdatesSubPage(
    updateCacheBytes: Long,
    onCheckUpdatesClick: () -> Unit,
    onClearUpdateCache: () -> Unit
) {
    val context = LocalContext.current
    SettingsSectionHeader("INSTALLED VERSION")
    IosSettingsGroup {
        IosSettingsRow(
            icon = GeekifyIcons.Verified,
            tileColor = TileGreen,
            title = "Geekify v${BuildConfig.VERSION_NAME}",
            subtitle = "Build ${BuildConfig.VERSION_CODE} • Installed on this device",
            showChevron = false,
            onClick = {}
        )
        SettingsGroupDivider()
        IosSettingsRow(
            icon = GeekifyIcons.SystemUpdate,
            tileColor = TileTeal,
            title = "Check for Updates Now",
            subtitle = "Query GitHub Releases for a newer Geekify APK",
            onClick = onCheckUpdatesClick
        )
        SettingsGroupDivider()
        IosSettingsRow(
            icon = GeekifyIcons.OpenInNew,
            tileColor = TileBlue,
            title = "View Releases on GitHub",
            subtitle = "Read changelogs and download APKs directly",
            onClick = {
                runCatching {
                    context.startActivity(
                        Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/Amal-kphilip/Geekify-App/releases"))
                    )
                }
            }
        )
        if (updateCacheBytes > 0L) {
            SettingsGroupDivider()
            IosSettingsRow(
                icon = GeekifyIcons.DeleteOutline,
                tileColor = TileRed,
                title = "Clear Downloaded Update APKs",
                subtitle = "${formatBytes(updateCacheBytes)} in update cache",
                onClick = onClearUpdateCache
            )
        }
    }
}

// ============================================================================
// SUB-PAGE 8: ABOUT GEEKIFY
// ============================================================================
@Composable
private fun AboutSubPage() {
    val context = LocalContext.current
    val installDate = remember {
        runCatching {
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            SimpleDateFormat("MMM d, yyyy", Locale.US).format(Date(info.firstInstallTime))
        }.getOrDefault("Unknown")
    }

    Surface(
        color = InkPanel,
        shape = RoundedCornerShape(28.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                modifier = Modifier
                    .size(64.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(Lime.copy(alpha = 0.16f))
                    .border(1.dp, Lime.copy(alpha = 0.4f), RoundedCornerShape(18.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(GeekifyIcons.AutoAwesome, contentDescription = null, tint = Lime, modifier = Modifier.size(32.dp))
            }
            Spacer(Modifier.height(12.dp))
            Text("Geekify", color = TextPrimary, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            Text(
                "A midnight music space built around your queue, library, and listening trail.",
                color = TextSecondary,
                fontSize = 13.5.sp,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(12.dp))
            Surface(
                color = InkElevated,
                shape = RoundedCornerShape(50),
            ) {
                Text(
                    text = "v${BuildConfig.VERSION_NAME} • Build ${BuildConfig.VERSION_CODE}",
                    color = Lime,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)
                )
            }
        }
    }

    Spacer(Modifier.height(18.dp))
    SettingsSectionHeader("BUILD & LINKS")
    IosSettingsGroup {
        IosSettingsRow(
            icon = GeekifyIcons.CalendarToday,
            tileColor = TilePurple,
            title = "First Installed",
            subtitle = installDate,
            showChevron = false,
            onClick = {}
        )
        SettingsGroupDivider()
        IosSettingsRow(
            icon = GeekifyIcons.PhoneAndroid,
            tileColor = TileCyan,
            title = "Android Runtime",
            subtitle = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
            showChevron = false,
            onClick = {}
        )
        SettingsGroupDivider()
        IosSettingsRow(
            icon = GeekifyIcons.Code,
            tileColor = TileGray,
            title = "GitHub Repository",
            subtitle = "github.com/Amal-kphilip/Geekify-App",
            onClick = {
                runCatching {
                    context.startActivity(
                        Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/Amal-kphilip/Geekify-App"))
                    )
                }
            }
        )
    }
}

// ============================================================================
// SHARED SETTINGS BUILDING BLOCKS
// ============================================================================

@Composable
private fun SettingsSearchBar(
    query: String,
    onQueryChange: (String) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(46.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(InkPanel)
            .border(1.dp, Color.White.copy(alpha = 0.06f), RoundedCornerShape(16.dp))
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = GeekifyIcons.Search,
            contentDescription = "Search settings",
            tint = TextMuted,
            modifier = Modifier.size(20.dp)
        )
        Spacer(Modifier.width(10.dp))
        Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            if (query.isEmpty()) {
                Text("Search settings...", color = TextMuted, fontSize = 14.5.sp)
            }
            BasicTextField(
                value = query,
                onValueChange = onQueryChange,
                singleLine = true,
                textStyle = TextStyle(color = TextPrimary, fontSize = 14.5.sp),
                cursorBrush = SolidColor(Lime),
                modifier = Modifier.fillMaxWidth()
            )
        }
        if (query.isNotEmpty()) {
            IconButton(onClick = { onQueryChange("") }, modifier = Modifier.size(28.dp)) {
                Icon(GeekifyIcons.Clear, contentDescription = "Clear", tint = TextSecondary, modifier = Modifier.size(18.dp))
            }
        }
    }
}

@Composable
private fun ProfileSettingsCard(
    user: AccountUser?,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(26.dp))
            .background(InkPanel)
            .border(1.dp, Color.White.copy(alpha = 0.07f), RoundedCornerShape(26.dp))
            .bouncyClickable(pressedScale = 0.98f, onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(54.dp)
                .clip(CircleShape)
                .background(InkElevated),
            contentAlignment = Alignment.Center
        ) {
            if (!user?.photoUrl.isNullOrBlank()) {
                AsyncImage(
                    model = user?.photoUrl,
                    contentDescription = user?.name,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            } else {
                Icon(
                    imageVector = GeekifyIcons.Person,
                    contentDescription = null,
                    tint = Lime,
                    modifier = Modifier.size(28.dp)
                )
            }
        }
        Spacer(Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = user?.name?.ifBlank { "Geekify Listener" } ?: "Sign in to Geekify",
                color = TextPrimary,
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = user?.email ?: "Cloud sync, profile & account",
                color = TextSecondary,
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Icon(GeekifyIcons.ChevronRight, contentDescription = null, tint = TextMuted)
    }
}

@Composable
private fun SettingsSectionHeader(title: String) {
    Text(
        text = title,
        color = TextMuted,
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.8.sp,
        modifier = Modifier.padding(start = 14.dp, bottom = 8.dp)
    )
}

@Composable
private fun IosSettingsGroup(
    content: @Composable ColumnScope.() -> Unit
) {
    Surface(
        color = InkPanel,
        shape = RoundedCornerShape(24.dp),
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, Color.White.copy(alpha = 0.06f), RoundedCornerShape(24.dp))
    ) {
        Column(modifier = Modifier.fillMaxWidth(), content = content)
    }
}

@Composable
private fun SettingsGroupDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(start = 60.dp, end = 16.dp),
        thickness = 0.6.dp,
        color = Color.White.copy(alpha = 0.07f)
    )
}

@Composable
private fun IosSettingsRow(
    icon: ImageVector,
    tileColor: Color,
    title: String,
    subtitle: String? = null,
    showChevron: Boolean = true,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = showChevron, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(32.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(tileColor),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(19.dp)
            )
        }
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                color = TextPrimary,
                fontSize = 15.5.sp,
                fontWeight = FontWeight.Medium
            )
            if (!subtitle.isNullOrBlank()) {
                Text(
                    text = subtitle,
                    color = TextSecondary,
                    fontSize = 12.5.sp,
                    modifier = Modifier.padding(top = 1.dp)
                )
            }
        }
        if (showChevron) {
            Spacer(Modifier.width(8.dp))
            Icon(
                imageVector = GeekifyIcons.ChevronRight,
                contentDescription = null,
                tint = TextMuted,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

@Composable
private fun IosSettingsSwitchRow(
    icon: ImageVector,
    tileColor: Color,
    title: String,
    subtitle: String? = null,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(horizontal = 16.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(32.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(tileColor),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(19.dp)
            )
        }
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                color = TextPrimary,
                fontSize = 15.5.sp,
                fontWeight = FontWeight.Medium
            )
            if (!subtitle.isNullOrBlank()) {
                Text(
                    text = subtitle,
                    color = TextSecondary,
                    fontSize = 12.5.sp,
                    modifier = Modifier.padding(top = 1.dp)
                )
            }
        }
        Spacer(Modifier.width(10.dp))
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = OnAccent,
                checkedTrackColor = Lime,
                uncheckedThumbColor = TextSecondary,
                uncheckedTrackColor = InkElevated
            )
        )
    }
}

@Composable
private fun IosSettingsSliderRow(
    title: String,
    valueLabel: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(title, color = TextPrimary, fontSize = 14.5.sp, fontWeight = FontWeight.Medium)
            Text(valueLabel, color = Lime, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange,
            colors = SliderDefaults.colors(
                thumbColor = Lime,
                activeTrackColor = Lime,
                inactiveTrackColor = InkElevated
            )
        )
    }
}

@Composable
private fun <T> OptionPickerDialog(
    title: String,
    options: List<Pair<T, Pair<String, String>>>,
    selected: T,
    onSelect: (T) -> Unit,
    onDismiss: () -> Unit
) {
    GeekifyDialog(onDismissRequest = onDismiss) {
        Text(title, color = TextPrimary, fontSize = 19.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(12.dp))
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 360.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            options.forEach { (value, labels) ->
                val isSelected = value == selected
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(if (isSelected) Lime.copy(alpha = 0.14f) else InkElevated)
                        .border(
                            width = 1.dp,
                            color = if (isSelected) Lime.copy(alpha = 0.55f) else Color.Transparent,
                            shape = RoundedCornerShape(16.dp)
                        )
                        .clickable { onSelect(value) }
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = labels.first,
                            color = if (isSelected) Lime else TextPrimary,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = labels.second,
                            color = TextSecondary,
                            fontSize = 12.sp
                        )
                    }
                    if (isSelected) {
                        Icon(GeekifyIcons.Check, contentDescription = null, tint = Lime, modifier = Modifier.size(20.dp))
                    }
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        DialogPillButton(
            text = "Close",
            onClick = onDismiss,
            style = DialogButtonStyle.Secondary,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

private fun formatBytes(bytes: Long): String {
    if (bytes <= 0L) return "0 MB"
    val mb = bytes.toDouble() / (1024.0 * 1024.0)
    return if (mb < 0.1) "< 0.1 MB" else "%.1f MB".format(Locale.US, mb)
}
