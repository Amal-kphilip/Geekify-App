package com.geekify.android.ui.library

import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.PredictiveBackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.ui.graphics.graphicsLayer
import kotlin.coroutines.cancellation.CancellationException
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import com.geekify.android.ui.navigation.GeekifyNavMotion
import kotlinx.coroutines.launch
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.koin.androidx.compose.koinViewModel
import com.geekify.android.BuildConfig
import com.geekify.android.customization.CustomizationCategory
import com.geekify.android.data.auth.AccountUser
import com.geekify.android.notifications.NotificationSettingsViewModel
import com.geekify.android.settings.AppSettingsState
import com.geekify.android.settings.AudioQualityPreference
import com.geekify.android.settings.SettingsViewModel
import com.geekify.android.ui.ascii.AsciiCatalog
import com.geekify.android.ui.components.*
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private enum class SettingsSubPage(val title: String) {
    ROOT("Settings"),
    CONTENT("Content & region"),
    NOTIFICATIONS("Notifications"),
    PRIVACY("Privacy & security"),
    STORAGE("Storage & backup"),
    DISCORD("Discord Rich Presence"),
    DIAGNOSTICS("Playback diagnostics"),
    ABOUT("About Geekify")
}

private data class SettingsRootItem(
    val key: String,
    val title: String,
    val subtitle: String,
    val icon: ImageVector,
    val subPage: SettingsSubPage? = null,
    val onClick: () -> Unit
)

private data class SettingsCategoryGroup(
    val title: String,
    val items: List<SettingsRootItem>
)

private data class SearchableSettingEntry(
    val id: String,
    val title: String,
    val subtitle: String,
    val category: String,
    val keywords: List<String>,
    val icon: ImageVector,
    val tint: Color,
    val onSelect: () -> Unit
)

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
    "kn" to "Kannada (ಕನ್ನಡ)",
    "bn" to "Bengali (বাংলা)",
    "gu" to "Gujarati (ગુજરાતી)",
    "or" to "Odia (ଓଡ଼ିଆ)",
    "pa" to "Punjabi (ਪੰਜਾਬੀ)",
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
    onPersonalizationClick: (CustomizationCategory?) -> Unit = {},
    onAudioClick: () -> Unit = {},
    onAppIconClick: () -> Unit = {},
    onProfileClick: () -> Unit = {},
    @Suppress("UNUSED_PARAMETER") onListenTogetherClick: () -> Unit = {},
    onListeningStatsClick: () -> Unit = {},
    @Suppress("UNUSED_PARAMETER") onRecentsClick: () -> Unit = {},
    onHealthClick: () -> Unit = {},
    onDownloadsClick: () -> Unit = {},
    onCheckUpdatesClick: () -> Unit = {},
    currentUser: AccountUser? = null,
    notificationSettings: NotificationSettingsViewModel = koinViewModel(),
    settingsViewModel: SettingsViewModel = koinViewModel()
) {
    val notif by notificationSettings.prefs.collectAsState()
    val state by settingsViewModel.settings.collectAsState()
    val storageStats by settingsViewModel.storageStats.collectAsState()
    val statusMessage by settingsViewModel.statusMessage.collectAsState()
    val bottomInset = LocalBottomInset.current

    // Settings feedback behaves like a toast: a newer message resets the timeout,
    // and the user can still dismiss it immediately by tapping the banner.
    LaunchedEffect(statusMessage) {
        val message = statusMessage ?: return@LaunchedEffect
        delay(3_500L)
        if (settingsViewModel.statusMessage.value == message) {
            settingsViewModel.clearStatusMessage()
        }
    }

    var currentPage by rememberSaveable { mutableStateOf(SettingsSubPage.ROOT) }
    var splitActivePage by rememberSaveable { mutableStateOf(SettingsSubPage.CONTENT) }
    var searchQuery by rememberSaveable { mutableStateOf("") }
    val rootScrollState = rememberSaveable(saver = ScrollState.Saver) { ScrollState(0) }

    val backCancelScope = rememberCoroutineScope()
    val predictiveBackAnim = remember { Animatable(0f) }
    var predictiveBackEdge by remember { mutableIntStateOf(GeekifyNavMotion.SWIPE_EDGE_LEFT) }
    var lastBackWasGesture by remember { mutableStateOf(false) }

    PredictiveBackHandler(enabled = currentPage != SettingsSubPage.ROOT) { backEvents ->
        try {
            backEvents.collect { event ->
                predictiveBackEdge = event.swipeEdge
                predictiveBackAnim.snapTo(event.progress.coerceIn(0f, 1f))
            }
            lastBackWasGesture = true
            predictiveBackAnim.snapTo(0f)
            currentPage = SettingsSubPage.ROOT
        } catch (_: CancellationException) {
            lastBackWasGesture = false
            backCancelScope.launch {
                predictiveBackAnim.animateTo(
                    targetValue = 0f,
                    animationSpec = tween(
                        durationMillis = GeekifyNavMotion.GESTURE_CANCEL_RETURN_DURATION_MS,
                        easing = FastOutSlowInEasing
                    )
                )
            }
        }
    }

    LaunchedEffect(currentPage) {
        if (currentPage != SettingsSubPage.ROOT) {
            lastBackWasGesture = false
            predictiveBackAnim.snapTo(0f)
        }
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

    val countryName = COUNTRIES.firstOrNull { it.first == state.contentCountry }?.second ?: state.contentCountry

    val openSubPage: (SettingsSubPage, Boolean) -> Unit = { target, isSplit ->
        if (isSplit) {
            splitActivePage = target
        } else {
            currentPage = target
        }
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(if (state.ascii.enabled) Color.Transparent else IosPalette.systemGroupedBackground)
    ) {
        val isSplitLayout = maxWidth >= 840.dp

        // Clean, grouped Settings categories mapped directly to Geekify's existing destinations.
        val rootGroups = remember(
            state,
            storageStats.totalBytes,
            countryName,
            currentUser,
            isSplitLayout
        ) {
            listOf(
                SettingsCategoryGroup(
                    title = "Interface",
                    items = listOf(
                        SettingsRootItem(
                            key = "appearance",
                            title = "Appearance",
                            subtitle = "Theme, Liquid Glass, ASCII, player & widgets",
                            icon = GeekifyIcons.Palette,
                            onClick = { onPersonalizationClick(null) }
                        ),
                        SettingsRootItem(
                            key = "typography_density",
                            title = "Typography & density",
                            subtitle = "${state.customization.typography.resolvedDisplayName} · fonts, text scale & display density",
                            icon = GeekifyIcons.Translate,
                            onClick = { onPersonalizationClick(CustomizationCategory.TYPOGRAPHY_DENSITY) }
                        ),
                        SettingsRootItem(
                            key = "navigation_layout",
                            title = "Navigation & layout",
                            subtitle = "Navigation bar, startup tab & display options",
                            icon = GeekifyIcons.Navigation,
                            onClick = { onPersonalizationClick(CustomizationCategory.NAVIGATION) }
                        ),
                        SettingsRootItem(
                            key = "home_screen",
                            title = "Home screen",
                            subtitle = "Home sections, shelves & content arrangement",
                            icon = GeekifyIcons.Home,
                            onClick = { onPersonalizationClick(CustomizationCategory.HOME_SCREEN) }
                        ),
                        SettingsRootItem(
                            key = "content",
                            title = "Content",
                            subtitle = "$countryName · catalog language, discovery & explicit filter",
                            icon = GeekifyIcons.Language,
                            subPage = SettingsSubPage.CONTENT,
                            onClick = { openSubPage(SettingsSubPage.CONTENT, isSplitLayout) }
                        )
                    )
                ),
                SettingsCategoryGroup(
                    title = "Playback",
                    items = listOf(
                        SettingsRootItem(
                            key = "player_audio",
                            title = "Player & audio",
                            subtitle = "${state.audioQuality.label} · playback, transitions & queue",
                            icon = GeekifyIcons.PlayArrow,
                            onClick = { onPersonalizationClick(CustomizationCategory.PLAYBACK_PREFERENCES) }
                        ),
                        SettingsRootItem(
                            key = "equalizer",
                            title = "Equalizer & effects",
                            subtitle = "5-band EQ, presets, speed, pitch & loudness",
                            icon = GeekifyIcons.GraphicEq,
                            onClick = onAudioClick
                        ),
                        SettingsRootItem(
                            key = "lyrics",
                            title = "Lyrics",
                            subtitle = "Lyric fetching, translation, romanization & appearance",
                            icon = GeekifyIcons.Lyrics,
                            onClick = { onPersonalizationClick(CustomizationCategory.LYRICS_APPEARANCE) }
                        )
                    )
                ),
                SettingsCategoryGroup(
                    title = "Content & library",
                    items = listOf(
                        SettingsRootItem(
                            key = "downloads",
                            title = "Downloads",
                            subtitle = "Offline songs, download progress & playback",
                            icon = GeekifyIcons.Download,
                            onClick = onDownloadsClick
                        ),
                        SettingsRootItem(
                            key = "listening_stats",
                            title = "Listening statistics",
                            subtitle = "Listening history, top artists & activity",
                            icon = GeekifyIcons.Insights,
                            onClick = onListeningStatsClick
                        )
                    )
                ),
                SettingsCategoryGroup(
                    title = "Privacy & security",
                    items = listOf(
                        SettingsRootItem(
                            key = "account",
                            title = "Account",
                            subtitle = currentUser?.let { "Signed in as ${it.name}" } ?: "Sign in & manage your account",
                            icon = GeekifyIcons.AccountCircle,
                            onClick = onProfileClick
                        ),
                        SettingsRootItem(
                            key = "privacy",
                            title = "Privacy",
                            subtitle = if (state.pauseListenHistory) {
                                "History paused · screenshot restrictions"
                            } else {
                                "Listening history & screenshot restrictions"
                            },
                            icon = GeekifyIcons.Shield,
                            subPage = SettingsSubPage.PRIVACY,
                            onClick = { openSubPage(SettingsSubPage.PRIVACY, isSplitLayout) }
                        )
                    )
                ),
                SettingsCategoryGroup(
                    title = "Storage & data",
                    items = listOf(
                        SettingsRootItem(
                            key = "storage",
                            title = "Storage & backup",
                            subtitle = "${formatBytes(storageStats.totalBytes)} used · cache, JSON backup & restore",
                            icon = GeekifyIcons.Storage,
                            subPage = SettingsSubPage.STORAGE,
                            onClick = { openSubPage(SettingsSubPage.STORAGE, isSplitLayout) }
                        )
                    )
                ),
                SettingsCategoryGroup(
                    title = "Application & about",
                    items = listOf(
                        SettingsRootItem(
                            key = "updates",
                            title = "Updates",
                            subtitle = "Update checks & release notes · v${BuildConfig.VERSION_NAME}",
                            icon = GeekifyIcons.SystemUpdate,
                            onClick = onCheckUpdatesClick
                        ),
                        SettingsRootItem(
                            key = "notifications",
                            title = "Notifications",
                            subtitle = "Release alerts, feature tips & reminders",
                            icon = GeekifyIcons.Notifications,
                            subPage = SettingsSubPage.NOTIFICATIONS,
                            onClick = { openSubPage(SettingsSubPage.NOTIFICATIONS, isSplitLayout) }
                        ),
                        SettingsRootItem(
                            key = "discord",
                            title = "Discord Rich Presence",
                            subtitle = "Broadcast your live Now Playing activity to Discord",
                            icon = GeekifyIcons.Groups,
                            subPage = SettingsSubPage.DISCORD,
                            onClick = { openSubPage(SettingsSubPage.DISCORD, isSplitLayout) }
                        ),
                        SettingsRootItem(
                            key = "health",
                            title = "Extractor health",
                            subtitle = "Catalog search & stream resolver diagnostics",
                            icon = GeekifyIcons.MonitorHeart,
                            onClick = onHealthClick
                        ),
                        SettingsRootItem(
                            key = "diagnostics",
                            title = "Playback diagnostics",
                            subtitle = "Real-time stream resolution & reliability metrics",
                            icon = GeekifyIcons.Speed,
                            subPage = SettingsSubPage.DIAGNOSTICS,
                            onClick = { openSubPage(SettingsSubPage.DIAGNOSTICS, isSplitLayout) }
                        ),
                        SettingsRootItem(
                            key = "about",
                            title = "About Geekify",
                            subtitle = "Version v${BuildConfig.VERSION_NAME}, contributors, links & licenses",
                            icon = GeekifyIcons.Info,
                            subPage = SettingsSubPage.ABOUT,
                            onClick = { openSubPage(SettingsSubPage.ABOUT, isSplitLayout) }
                        )
                    )
                )
            )
        }

        val searchRegistry = remember(isSplitLayout) {
            buildSearchRegistry(
                openPage = { openSubPage(it, isSplitLayout) },
                onPersonalizationCategory = { cat -> onPersonalizationClick(cat) },
                onAudioClick = onAudioClick,
                onAppIconClick = onAppIconClick,
                onProfileClick = onProfileClick,
                onListeningStatsClick = onListeningStatsClick,
                onDownloadsClick = onDownloadsClick,
                onHealthClick = onHealthClick,
                onCheckUpdatesClick = onCheckUpdatesClick
            )
        }

        @Composable
        fun RenderSubPageContent(page: SettingsSubPage) {
            AnimatedVisibility(visible = statusMessage != null) {
                statusMessage?.let { msg ->
                    IosStatusBanner(
                        message = msg,
                        onDismiss = settingsViewModel::clearStatusMessage
                    )
                }
            }

            when (page) {
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

                SettingsSubPage.DISCORD -> DiscordRichPresenceSubPage()

                SettingsSubPage.DIAGNOSTICS -> PlaybackDiagnosticsSubPage()

                SettingsSubPage.ABOUT -> AboutAndUpdateSubPage(
                    updateCacheBytes = storageStats.updateCacheBytes,
                    onClearUpdateCache = settingsViewModel::clearUpdateCache
                )

                SettingsSubPage.ROOT -> Unit
            }
        }

        if (isSplitLayout) {
            Row(modifier = Modifier.fillMaxSize()) {
                Box(
                    modifier = Modifier
                        .width(380.dp)
                        .fillMaxHeight()
                ) {
                    IosSettingsPageScaffold(
                        title = "Settings",
                        scrollState = rootScrollState,
                        onBack = onBack,
                        maxContentWidth = 380.dp,
                        bottomPadding = bottomInset + 32.dp,
                        minimalStyle = true
                    ) {
                        RootListBody(
                            searchQuery = searchQuery,
                            onQueryChange = { searchQuery = it },
                            rootGroups = rootGroups,
                            searchRegistry = searchRegistry,
                            selectedSubPage = splitActivePage
                        )
                    }
                }

                Box(
                    modifier = Modifier
                        .width(0.5.dp)
                        .fillMaxHeight()
                        .background(IosPalette.separator)
                )

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                ) {
                    Crossfade(
                        targetState = splitActivePage,
                        animationSpec = tween(200),
                        label = "splitSettingsDetail"
                    ) { detailPage ->
                        val detailScroll = rememberSaveable(
                            "split_${detailPage.name}",
                            saver = ScrollState.Saver
                        ) { ScrollState(0) }
                        IosSettingsPageScaffold(
                            title = detailPage.title,
                            scrollState = detailScroll,
                            onBack = onBack,
                            showBackButton = false,
                            maxContentWidth = 680.dp,
                            bottomPadding = bottomInset + 40.dp,
                            minimalStyle = true
                        ) {
                            RenderSubPageContent(detailPage)
                        }
                    }
                }
            }
        } else {
            AnimatedContent(
                targetState = currentPage,
                transitionSpec = {
                    GeekifyNavMotion.subPageTransition(
                        isForward = targetState != SettingsSubPage.ROOT,
                        lastBackWasGesture = lastBackWasGesture
                    )
                },
                label = "settingsSubPage"
            ) { page ->
                if (page == SettingsSubPage.ROOT) {
                    IosSettingsPageScaffold(
                        title = "Settings",
                        scrollState = rootScrollState,
                        onBack = onBack,
                        bottomPadding = bottomInset + 32.dp,
                        minimalStyle = true
                    ) {
                        AnimatedVisibility(visible = statusMessage != null) {
                            statusMessage?.let { msg ->
                                IosStatusBanner(
                                    message = msg,
                                    onDismiss = settingsViewModel::clearStatusMessage
                                )
                            }
                        }

                        RootListBody(
                            searchQuery = searchQuery,
                            onQueryChange = { searchQuery = it },
                            rootGroups = rootGroups,
                            searchRegistry = searchRegistry,
                            selectedSubPage = null
                        )
                    }
                } else {
                    val subScroll = rememberSaveable(page.name, saver = ScrollState.Saver) { ScrollState(0) }
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer {
                                val tf = GeekifyNavMotion.computePredictiveBackTransform(
                                    progress = predictiveBackAnim.value,
                                    density = density,
                                    swipeEdge = predictiveBackEdge
                                )
                                translationX = tf.translationXPx
                                scaleX = tf.scale
                                scaleY = tf.scale
                                alpha = tf.alpha
                            }
                    ) {
                        IosSettingsPageScaffold(
                            title = page.title,
                            scrollState = subScroll,
                            onBack = { currentPage = SettingsSubPage.ROOT },
                            bottomPadding = bottomInset + 40.dp,
                            minimalStyle = true
                        ) {
                            RenderSubPageContent(page)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RootListBody(
    searchQuery: String,
    onQueryChange: (String) -> Unit,
    rootGroups: List<SettingsCategoryGroup>,
    searchRegistry: List<SearchableSettingEntry>,
    selectedSubPage: SettingsSubPage?
) {
    // Keep settings search in the page's normal scroll flow below the Settings heading
    // so it never competes with the floating mini-player or navigation bar.
    DockedSearchPill(
        query = searchQuery,
        onQueryChange = onQueryChange,
        modifier = Modifier.padding(bottom = 22.dp)
    )

    val trimmedQuery = searchQuery.trim()
    if (trimmedQuery.isEmpty()) {
        rootGroups.forEachIndexed { groupIdx, group ->
            MinimalSettingsGroupHeader(title = group.title)
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                group.items.forEachIndexed { idx, item ->
                    val shape = groupedCardShape(index = idx, count = group.items.size)
                    MinimalSettingsCategoryRow(
                        title = item.title,
                        subtitle = item.subtitle,
                        icon = item.icon,
                        shape = shape,
                        selected = selectedSubPage != null && item.subPage == selectedSubPage,
                        onClick = item.onClick
                    )
                }
            }
            if (groupIdx < rootGroups.lastIndex) {
                Spacer(Modifier.height(22.dp))
            }
        }
    } else {
        val results = remember(trimmedQuery, searchRegistry) {
            searchRegistry.filter { entry ->
                entry.title.contains(trimmedQuery, ignoreCase = true) ||
                    entry.subtitle.contains(trimmedQuery, ignoreCase = true) ||
                    entry.category.contains(trimmedQuery, ignoreCase = true) ||
                    entry.keywords.any { it.contains(trimmedQuery, ignoreCase = true) }
            }
        }

        if (results.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 56.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = GeekifyIcons.Search,
                        contentDescription = null,
                        tint = TextMuted,
                        modifier = Modifier.size(34.dp)
                    )
                    Text(
                        text = "No settings found",
                        color = TextPrimary,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = "No matches for \"$trimmedQuery\"",
                        color = TextSecondary,
                        fontSize = 13.5.sp,
                        textAlign = TextAlign.Center
                    )
                }
            }
        } else {
            MinimalSettingsGroupHeader(title = "Results (${results.size})")
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                results.forEachIndexed { idx, entry ->
                    val shape = groupedCardShape(index = idx, count = results.size)
                    MinimalSettingsCategoryRow(
                        title = entry.title,
                        subtitle = "${entry.category} · ${entry.subtitle}",
                        icon = entry.icon,
                        shape = shape,
                        selected = false,
                        onClick = {
                            entry.onSelect()
                        }
                    )
                }
            }
        }
    }
}

private fun groupedCardShape(index: Int, count: Int): RoundedCornerShape {
    val outer = 22.dp
    val inner = 5.dp
    return when {
        count <= 1 -> RoundedCornerShape(outer)
        index == 0 -> RoundedCornerShape(topStart = outer, topEnd = outer, bottomStart = inner, bottomEnd = inner)
        index == count - 1 -> RoundedCornerShape(topStart = inner, topEnd = inner, bottomStart = outer, bottomEnd = outer)
        else -> RoundedCornerShape(inner)
    }
}

@Composable
private fun MinimalSettingsGroupHeader(title: String) {
    Text(
        text = title,
        color = TextSecondary,
        fontSize = 13.sp,
        fontWeight = FontWeight.Medium,
        letterSpacing = 0.1.sp,
        modifier = Modifier.padding(start = 6.dp, bottom = 9.dp)
    )
}

@Composable
private fun MinimalSettingsCategoryRow(
    title: String,
    subtitle: String,
    icon: ImageVector,
    shape: RoundedCornerShape,
    selected: Boolean,
    onClick: () -> Unit
) {
    val containerColor = if (selected) {
        Lime.copy(alpha = 0.14f)
    } else {
        SettingsVisuals.card
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(containerColor)
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier.size(28.dp),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (selected) Lime else TextPrimary.copy(alpha = 0.86f),
                modifier = Modifier.size(22.dp)
            )
        }
        Spacer(Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                color = TextPrimary,
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (subtitle.isNotBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = subtitle,
                    color = TextSecondary,
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Icon(
            imageVector = GeekifyIcons.ChevronRight,
            contentDescription = null,
            tint = TextMuted,
            modifier = Modifier.size(18.dp)
        )
    }
}

@Composable
private fun DockedSearchPill(
    query: String,
    onQueryChange: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val focusManager = LocalFocusManager.current

    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(50.dp)
            .clip(CircleShape)
            .background(InkElevated)
            .padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = GeekifyIcons.Search,
            contentDescription = "Search settings",
            tint = TextSecondary,
            modifier = Modifier.size(19.dp)
        )
        Spacer(Modifier.width(12.dp))
        Box(
            modifier = Modifier.weight(1f),
            contentAlignment = Alignment.CenterStart
        ) {
            if (query.isEmpty()) {
                Text(
                    text = "Search settings",
                    color = TextSecondary,
                    fontSize = 15.5.sp,
                    maxLines = 1
                )
            }
            BasicTextField(
                value = query,
                onValueChange = onQueryChange,
                singleLine = true,
                textStyle = TextStyle(
                    color = TextPrimary,
                    fontSize = 15.5.sp,
                    fontFamily = AppFont
                ),
                cursorBrush = SolidColor(Lime),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus() }),
                modifier = Modifier.fillMaxWidth()
            )
        }
        if (query.isNotEmpty()) {
            Box(
                modifier = Modifier
                    .size(24.dp)
                    .clip(CircleShape)
                    .background(TextMuted.copy(alpha = 0.22f))
                    .clickable(
                        indication = null,
                        interactionSource = remember { MutableInteractionSource() }
                    ) {
                        onQueryChange("")
                        focusManager.clearFocus()
                    },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = GeekifyIcons.Close,
                    contentDescription = "Clear search",
                    tint = TextPrimary,
                    modifier = Modifier.size(13.dp)
                )
            }
        }
    }
}

@Composable
private fun IosStatusBanner(
    message: String,
    onDismiss: () -> Unit
) {
    Surface(
        color = SettingsVisuals.card,
        shape = RoundedCornerShape(18.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 16.dp)
            .border(0.8.dp, SettingsVisuals.green.copy(alpha = 0.38f), RoundedCornerShape(18.dp))
            .clickable(onClick = onDismiss)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = GeekifyIcons.CheckCircle,
                contentDescription = null,
                tint = IosPalette.systemGreen,
                modifier = Modifier.size(18.dp)
            )
            Spacer(Modifier.width(10.dp))
            Text(
                text = message,
                color = IosPalette.label,
                fontSize = 14.sp,
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

// ============================================================================
// SUB-PAGE 1: APPEARANCE & LIQUID GLASS
// ============================================================================
@Composable
private fun AppearanceGlassSubPage(
    state: AppSettingsState,
    viewModel: SettingsViewModel
) {
    val glass = state.glass
    val imageScroll = rememberScrollState()
    val previewBackdrop = rememberGlassBackdrop()

    IosSettingsSection(
        title = "Live preview",
        footer = "Drag the backdrop horizontally to watch the refraction lens warp colors in real time."
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(206.dp)
                .clip(IosCardShape)
        ) {
            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxSize()
                    .glassBackdropSource(previewBackdrop)
                    .background(
                        Brush.linearGradient(
                            colors = listOf(
                                Color(0xFFFF2D55),
                                Color(0xFF5856D6),
                                Color(0xFF32ADE6),
                                Color(0xFF34C759)
                            )
                        )
                    )
            ) {
                val panelWidth = maxWidth
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .horizontalScroll(imageScroll)
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxHeight()
                            .width(panelWidth * 1.75f)
                            .background(
                                Brush.linearGradient(
                                    colors = listOf(
                                        Color(0xFF0F172A),
                                        Color(0xFFFF375F),
                                        Color(0xFF5E5CE6),
                                        Color(0xFF64D2FF),
                                        Color(0xFF30D158),
                                        Color(0xFFFF9F0A)
                                    )
                                )
                            )
                    ) {
                        Box(
                            modifier = Modifier
                                .size(130.dp)
                                .offset(x = 28.dp, y = 18.dp)
                                .clip(CircleShape)
                                .background(Lime.copy(alpha = 0.78f))
                        )
                        Box(
                            modifier = Modifier
                                .size(115.dp)
                                .align(Alignment.BottomCenter)
                                .offset(x = 30.dp, y = (-12).dp)
                                .clip(CircleShape)
                                .background(Color(0xFFFF375F).copy(alpha = 0.80f))
                        )
                        Box(
                            modifier = Modifier
                                .size(110.dp)
                                .align(Alignment.TopEnd)
                                .offset(x = (-36).dp, y = 24.dp)
                                .clip(CircleShape)
                                .background(Color(0xFF64D2FF).copy(alpha = 0.85f))
                        )
                        Text(
                            text = "LIQUID GLASS",
                            color = Color.White.copy(alpha = 0.65f),
                            fontSize = 22.sp,
                            fontWeight = FontWeight.Black,
                            letterSpacing = 3.sp,
                            modifier = Modifier.align(Alignment.Center)
                        )
                    }
                }
            }

            CompositionLocalProvider(LocalGlassBackdrop provides previewBackdrop) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalArrangement = Arrangement.SpaceBetween,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    val miniModifier = if (glass.enabled && glass.miniPlayerGlass) {
                        Modifier.liquidGlass(RoundedCornerShape(24.dp), GlassStyle.Preview)
                    } else {
                        Modifier
                            .clip(RoundedCornerShape(24.dp))
                            .background(IosPalette.secondarySystemGroupedBackground.copy(alpha = 0.94f))
                            .border(0.5.dp, Color.White.copy(alpha = 0.12f), RoundedCornerShape(24.dp))
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth(0.92f)
                            .height(54.dp)
                            .then(miniModifier)
                            .padding(horizontal = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(34.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(IosPalette.systemPink),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = GeekifyIcons.MusicNote,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        Spacer(Modifier.width(10.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Midnight City",
                                color = Color.White,
                                fontSize = 13.5.sp,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1
                            )
                            Text(
                                text = if (glass.enabled && glass.miniPlayerGlass) {
                                    "${glass.blurDp.roundToInt()}dp blur · ${(glass.saturation * 100).roundToInt()}% vibrancy"
                                } else {
                                    "Solid surface"
                                },
                                color = Color.White.copy(alpha = 0.72f),
                                fontSize = 11.sp,
                                maxLines = 1
                            )
                        }
                        Icon(
                            imageVector = GeekifyIcons.PlayArrow,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Icon(
                            imageVector = GeekifyIcons.SkipNext,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    val navModifier = if (glass.enabled && glass.navBarGlass) {
                        Modifier.liquidGlass(CircleShape, GlassStyle.Preview)
                    } else {
                        Modifier
                            .clip(CircleShape)
                            .background(IosPalette.secondarySystemGroupedBackground.copy(alpha = 0.94f))
                            .border(0.5.dp, Color.White.copy(alpha = 0.12f), CircleShape)
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth(0.92f)
                            .height(56.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .then(navModifier)
                                .padding(horizontal = 8.dp),
                            horizontalArrangement = Arrangement.SpaceEvenly,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            val tabs = listOf(
                                GeekifyIcons.Home to "Home",
                                GeekifyIcons.LibraryMusic to "Library",
                                GeekifyIcons.Settings to "Settings"
                            )
                            tabs.forEachIndexed { idx, (icon, label) ->
                                val selected = idx == 0
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.Center
                                ) {
                                    Icon(
                                        imageVector = icon,
                                        contentDescription = null,
                                        tint = if (selected) Lime else Color.White.copy(alpha = 0.85f),
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Text(
                                        text = label,
                                        color = if (selected) Lime else Color.White.copy(alpha = 0.85f),
                                        fontSize = 10.sp,
                                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
                                    )
                                }
                            }
                        }

                        Box(
                            modifier = Modifier
                                .size(56.dp)
                                .then(navModifier),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = GeekifyIcons.Search,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                    }
                }
            }
        }
    }

    Spacer(Modifier.height(24.dp))
    IosSettingsSection(title = "Liquid Glass engine") {
        IosSettingsSwitchRow(
            icon = GeekifyIcons.BlurOn,
            tileColor = IosPalette.systemCyan,
            title = "Liquid Glass",
            subtitle = "Real-time AGSL lens refraction and backdrop blur",
            checked = glass.enabled,
            onCheckedChange = viewModel::setGlassEnabled
        )
        if (glass.enabled) {
            IosSettingsDivider()
            IosSettingsSwitchRow(
                icon = GeekifyIcons.MusicNote,
                tileColor = IosPalette.systemPink,
                title = "Mini Player Glass",
                subtitle = "Apply liquid glass to the floating Now Playing pill",
                checked = glass.miniPlayerGlass,
                onCheckedChange = viewModel::setGlassMiniPlayer
            )
            IosSettingsDivider()
            IosSettingsSwitchRow(
                icon = GeekifyIcons.Navigation,
                tileColor = IosPalette.systemBlue,
                title = "Navigation Bar Glass",
                subtitle = "Apply liquid glass to the bottom navigation bar",
                checked = glass.navBarGlass,
                onCheckedChange = viewModel::setGlassNavBar
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                IosSettingsDivider()
                IosSettingsSwitchRow(
                    icon = GeekifyIcons.Flare,
                    tileColor = IosPalette.systemPurple,
                    title = "Chromatic Aberration",
                    subtitle = "Optical RGB color dispersion along curved edges",
                    checked = glass.chromaticAberration,
                    onCheckedChange = viewModel::setGlassChromaticAberration
                )
                IosSettingsDivider()
                IosSettingsSwitchRow(
                    icon = GeekifyIcons.Layers,
                    tileColor = IosPalette.systemIndigo,
                    title = "Spherical Depth Effect",
                    subtitle = "3D perspective magnification across the lens",
                    checked = glass.depthEffect,
                    onCheckedChange = viewModel::setGlassDepthEffect
                )
            }
        }
    }

    if (glass.enabled) {
        Spacer(Modifier.height(24.dp))
        IosSettingsSection(title = "Optical tuning") {
            IosSettingsSliderRow(
                title = "Backdrop Blur Radius",
                valueLabel = "${glass.blurDp.roundToInt()} dp",
                value = glass.blurDp,
                valueRange = 0f..20f,
                onValueChange = viewModel::setGlassBlurDp
            )
            IosSettingsDivider(insetStart = 16.dp)
            IosSettingsSliderRow(
                title = "Backdrop Vibrancy",
                valueLabel = "${"%.1f".format(Locale.US, glass.saturation)}×",
                value = glass.saturation,
                valueRange = 0.8f..2.5f,
                onValueChange = viewModel::setGlassSaturation
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                IosSettingsDivider(insetStart = 16.dp)
                IosSettingsSliderRow(
                    title = "Edge Refraction Warp",
                    valueLabel = "${glass.lensAmountDp.roundToInt()} dp",
                    value = glass.lensAmountDp,
                    valueRange = 0f..28f,
                    onValueChange = viewModel::setGlassLensAmountDp
                )
                IosSettingsDivider(insetStart = 16.dp)
                IosSettingsSliderRow(
                    title = "Refraction Bevel Depth",
                    valueLabel = "${glass.lensHeightDp.roundToInt()} dp",
                    value = glass.lensHeightDp,
                    valueRange = 0f..28f,
                    onValueChange = viewModel::setGlassLensHeightDp
                )
            }
            IosSettingsDivider(insetStart = 16.dp)
            IosSettingsSliderRow(
                title = "Surface Tint Opacity",
                valueLabel = "${(glass.tintAlpha * 100).roundToInt()}%",
                value = glass.tintAlpha,
                valueRange = 0f..0.50f,
                onValueChange = viewModel::setGlassTintAlpha
            )
            IosSettingsDivider(insetStart = 16.dp)
            IosSettingsRow(
                icon = GeekifyIcons.RestartAlt,
                tileColor = IosPalette.systemGray,
                title = "Reset Liquid Glass Defaults",
                subtitle = "Restore crystal-clear 3 dp optical preset",
                onClick = viewModel::resetGlassDefaults
            )
        }
    }

    Spacer(Modifier.height(24.dp))
    IosSettingsSection(title = "Navigation & launch") {
        var showNavBehaviorDialog by remember { mutableStateOf(false) }
        var showDefaultTabDialog by remember { mutableStateOf(false) }

        val behaviorLabel = when (state.navInlineBehavior) {
            FloatingTabBarInlineBehavior.OnScrollDown -> "On scroll down"
            FloatingTabBarInlineBehavior.ExpandedAtTopOnly -> "At top only"
            FloatingTabBarInlineBehavior.Never -> "Always expanded"
            FloatingTabBarInlineBehavior.OnScrollUp -> "On scroll up"
        }
        IosSettingsRow(
            icon = GeekifyIcons.ViewDay,
            tileColor = IosPalette.systemTeal,
            title = "Adaptive Bottom Bar",
            subtitle = "Collapse behavior while scrolling lists",
            value = behaviorLabel,
            onClick = { showNavBehaviorDialog = true }
        )
        IosSettingsDivider()
        val tabLabel = when (state.defaultTab) {
            "search" -> "Search"
            "library" -> "Library"
            else -> "Home"
        }
        IosSettingsRow(
            icon = GeekifyIcons.Home,
            tileColor = IosPalette.systemGreen,
            title = "Default Launch Tab",
            subtitle = "Screen opened when Geekify starts",
            value = tabLabel,
            onClick = { showDefaultTabDialog = true }
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
// SUB-PAGE 2: PLAYER & PLAYBACK
// ============================================================================
@Composable
private fun PlayerAudioSubPage(
    state: AppSettingsState,
    viewModel: SettingsViewModel
) {
    var showQualityDialog by remember { mutableStateOf(false) }

    IosSettingsSection(title = "Streaming quality") {
        IosSettingsRow(
            icon = GeekifyIcons.HighQuality,
            tileColor = IosPalette.systemRed,
            title = "Audio Stream Quality",
            value = state.audioQuality.label,
            subtitle = state.audioQuality.subtitle,
            onClick = { showQualityDialog = true }
        )
    }

    Spacer(Modifier.height(24.dp))
    IosSettingsSection(title = "Queue & playback") {
        IosSettingsSwitchRow(
            icon = GeekifyIcons.QueueMusic,
            tileColor = IosPalette.systemBlue,
            title = "Persistent Queue",
            subtitle = "Restore queue and playback position across restarts",
            checked = state.persistentQueue,
            onCheckedChange = viewModel::setPersistentQueue
        )
        IosSettingsDivider()
        IosSettingsSwitchRow(
            icon = GeekifyIcons.AutoMode,
            tileColor = IosPalette.systemPurple,
            title = "Endless Autoplay",
            subtitle = "Automatically queue related songs when your queue ends",
            checked = state.autoplayRelated,
            onCheckedChange = viewModel::setAutoplayRelated
        )
        IosSettingsDivider()
        IosSettingsSwitchRow(
            icon = GeekifyIcons.Shuffle,
            tileColor = IosPalette.systemGreen,
            title = "Remember Shuffle & Repeat",
            subtitle = "Keep shuffle and repeat modes when reopening the app",
            checked = state.rememberShuffleRepeat,
            onCheckedChange = viewModel::setRememberShuffleRepeat
        )
        IosSettingsDivider()
        IosSettingsSwitchRow(
            icon = GeekifyIcons.FastForward,
            tileColor = IosPalette.systemPink,
            title = "Auto-skip on Stream Error",
            subtitle = "Advance to the next track if a stream fails to resolve",
            checked = state.autoSkipOnError,
            onCheckedChange = viewModel::setAutoSkipOnError
        )
        IosSettingsDivider()
        IosSettingsSwitchRow(
            icon = GeekifyIcons.LightMode,
            tileColor = IosPalette.systemOrange,
            title = "Keep Screen On in Player",
            subtitle = "Prevent the screen from sleeping while Now Playing is open",
            checked = state.keepScreenOn,
            onCheckedChange = viewModel::setKeepScreenOn
        )
    }

    if (showQualityDialog) {
        OptionPickerDialog(
            title = "Audio Stream Quality",
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

    IosSettingsSection(
        title = "Catalog region & language",
        footer = "Changing your country or language immediately refreshes regional charts, trending shelves, and recommendations on Home."
    ) {
        IosSettingsRow(
            icon = GeekifyIcons.Public,
            tileColor = IosPalette.systemBlue,
            title = "Content Country",
            value = countryName,
            subtitle = "Regional charts & new releases (${state.contentCountry})",
            onClick = { showCountryDialog = true }
        )
        IosSettingsDivider()
        IosSettingsRow(
            icon = GeekifyIcons.Translate,
            tileColor = IosPalette.systemTeal,
            title = "Content Language",
            value = languageName.substringBefore(" ("),
            subtitle = "Preferred music catalog language (${state.contentLanguage.uppercase(Locale.US)})",
            onClick = { showLanguageDialog = true }
        )
    }

    Spacer(Modifier.height(24.dp))
    IosSettingsSection(title = "Content restrictions") {
        IosSettingsSwitchRow(
            icon = GeekifyIcons.Explicit,
            tileColor = IosPalette.systemRed,
            title = "Hide Explicit Content",
            subtitle = "Filter explicit tracks from Home, Search, and Autoplay",
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
    IosSettingsSection(
        title = "In-app & push notifications",
        footer = "Media playback controls are managed automatically by the system while music is playing."
    ) {
        IosSettingsSwitchRow(
            icon = GeekifyIcons.SystemUpdate,
            tileColor = IosPalette.systemGreen,
            title = "App Updates",
            subtitle = "Notify when a new Geekify release is available",
            checked = updates,
            onCheckedChange = onUpdatesChange
        )
        IosSettingsDivider()
        IosSettingsSwitchRow(
            icon = GeekifyIcons.Lightbulb,
            tileColor = IosPalette.systemYellow,
            title = "Tips & New Features",
            subtitle = "Occasional highlights after an app update",
            checked = tips,
            onCheckedChange = onTipsChange
        )
        IosSettingsDivider()
        IosSettingsSwitchRow(
            icon = GeekifyIcons.LibraryMusic,
            tileColor = IosPalette.systemPurple,
            title = "Listening Reminders",
            subtitle = "Personalized picks based on your recent plays",
            checked = engagement,
            onCheckedChange = onEngagementChange
        )
    }
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

    IosSettingsSection(title = "Listening history") {
        IosSettingsSwitchRow(
            icon = GeekifyIcons.PauseCircleFilled,
            tileColor = IosPalette.systemOrange,
            title = "Pause Listening History",
            subtitle = "Do not record played songs, play counts, or listening stats",
            checked = state.pauseListenHistory,
            onCheckedChange = viewModel::setPauseListenHistory
        )
        IosSettingsDivider()
        IosSettingsRow(
            icon = GeekifyIcons.DeleteSweep,
            tileColor = IosPalette.systemRed,
            title = "Clear Listening History & Stats",
            subtitle = "Delete local recent tracks, play counts, and sessions",
            destructive = true,
            onClick = { confirmClearHistory = true }
        )
    }

    Spacer(Modifier.height(24.dp))
    IosSettingsSection(title = "Screen security") {
        IosSettingsSwitchRow(
            icon = GeekifyIcons.Security,
            tileColor = IosPalette.systemIndigo,
            title = "Disable Screenshots",
            subtitle = "Block screenshots and hide app preview in the recents switcher",
            checked = state.disableScreenshots,
            onCheckedChange = viewModel::setDisableScreenshots
        )
    }

    if (confirmClearHistory) {
        GeekifyDialog(onDismissRequest = { confirmClearHistory = false }) {
            Text("Clear Listening History?", color = IosPalette.label, fontSize = 19.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Text(
                "This will clear your Recents list, top play counts, and listening statistics on this device.",
                color = IosPalette.secondaryLabel,
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
private enum class CacheClearTarget(
    val title: String,
    val message: String
) {
    AUDIO(
        title = "Clear audio stream cache?",
        message = "Temporary audio stream data will be removed. Geekify will rebuild this cache automatically as you stream. Your library and saved music are not affected."
    ),
    IMAGES(
        title = "Clear artwork & image cache?",
        message = "Cached album and artist artwork will be removed and downloaded again when needed. Your playlists and listening data are not affected."
    ),
    METADATA(
        title = "Clear cached feed & metadata?",
        message = "Cached Home shelves and catalog metadata will be removed. Geekify will fetch fresh data the next time it loads. Your library and listening history are not affected."
    )
}

@Composable
private fun StorageBackupSubPage(
    stats: com.geekify.android.settings.StorageStats,
    viewModel: SettingsViewModel,
    onExportBackup: () -> Unit,
    onImportBackup: () -> Unit
) {
    var pendingCacheClear by remember { mutableStateOf<CacheClearTarget?>(null) }

    LaunchedEffect(Unit) {
        viewModel.refreshStorageStats()
    }

    IosSettingsSection(title = "Storage usage (${formatBytes(stats.totalBytes)})") {
        IosSettingsRow(
            icon = GeekifyIcons.Audiotrack,
            tileColor = IosPalette.systemGreen,
            title = "Clear Audio Stream Cache",
            value = formatBytes(stats.audioCacheBytes),
            subtitle = "300 MB automatic cache limit",
            onClick = { pendingCacheClear = CacheClearTarget.AUDIO }
        )
        IosSettingsDivider()
        IosSettingsRow(
            icon = GeekifyIcons.Image,
            tileColor = IosPalette.systemCyan,
            title = "Clear Artwork & Image Cache",
            value = formatBytes(stats.imageCacheBytes),
            subtitle = "Cached album and artist covers",
            onClick = { pendingCacheClear = CacheClearTarget.IMAGES }
        )
        IosSettingsDivider()
        IosSettingsRow(
            icon = GeekifyIcons.Dataset,
            tileColor = IosPalette.systemOrange,
            title = "Prune Cached Feed & Metadata",
            value = formatBytes(stats.metadataBytes),
            subtitle = "Local database and catalog cache",
            onClick = { pendingCacheClear = CacheClearTarget.METADATA }
        )
    }

    pendingCacheClear?.let { target ->
        ConfirmDialog(
            title = target.title,
            message = target.message,
            confirmLabel = "Clear cache",
            destructive = true,
            icon = GeekifyIcons.Delete,
            onDismiss = { pendingCacheClear = null },
            onConfirm = {
                when (target) {
                    CacheClearTarget.AUDIO -> viewModel.clearAudioCache()
                    CacheClearTarget.IMAGES -> viewModel.clearImageCache()
                    CacheClearTarget.METADATA -> viewModel.clearMetadataCache()
                }
                pendingCacheClear = null
            }
        )
    }

    Spacer(Modifier.height(24.dp))
    IosSettingsSection(
        title = "Library backup & restore",
        footer = "Backups include your liked songs, custom playlists, saved albums, and listening history in a portable JSON file."
    ) {
        IosSettingsRow(
            icon = GeekifyIcons.UploadFile,
            tileColor = IosPalette.systemBlue,
            title = "Backup Library to JSON",
            subtitle = "Export liked songs, playlists, albums & history",
            onClick = onExportBackup
        )
        IosSettingsDivider()
        IosSettingsRow(
            icon = GeekifyIcons.Download,
            tileColor = IosPalette.systemPurple,
            title = "Restore Library from Backup",
            subtitle = "Import and merge a .json backup file",
            onClick = onImportBackup
        )
    }
}

// ============================================================================
// SUB-PAGE 7: ABOUT & SOFTWARE UPDATE (CONSOLIDATED, ZERO DUPLICATES)
// ============================================================================
@Composable
private fun AboutAndUpdateSubPage(
    updateCacheBytes: Long,
    onClearUpdateCache: () -> Unit
) {
    AboutGeekifyContent(
        updateCacheBytes = updateCacheBytes,
        onClearUpdateCache = onClearUpdateCache
    )
}

// ============================================================================
// DEEP SEARCH REGISTRY (Single Authoritative Entry Per Setting)
// ============================================================================
private fun buildSearchRegistry(
    openPage: (SettingsSubPage) -> Unit,
    onPersonalizationCategory: (CustomizationCategory) -> Unit,
    onAudioClick: () -> Unit,
    onAppIconClick: () -> Unit,
    onProfileClick: () -> Unit,
    onListeningStatsClick: () -> Unit,
    onDownloadsClick: () -> Unit,
    onHealthClick: () -> Unit,
    onCheckUpdatesClick: () -> Unit
): List<SearchableSettingEntry> = listOf(
    SearchableSettingEntry(
        id = "cust_theme_colors",
        title = "Theme & Color Palettes",
        subtitle = "Light, Dark, AMOLED black, accent palettes & dynamic artwork colors",
        category = "Appearance",
        keywords = listOf("appearance", "personalization", "personalization studio", "theme", "light", "dark", "amoled", "black", "graphite", "color", "palette", "accent", "lime", "violet", "cyan", "material you"),
        icon = GeekifyIcons.Palette,
        tint = IosPalette.systemPurple,
        onSelect = { onPersonalizationCategory(CustomizationCategory.THEME_COLORS) }
    ),
    SearchableSettingEntry(
        id = "cust_liquid_glass",
        title = "Liquid Glass & Visual Effects",
        subtitle = "Optical refraction presets, backdrop blur, saturation, lens & depth",
        category = "Appearance",
        keywords = listOf("glass", "blur", "refraction", "lens", "vibrancy", "saturation", "chromatic", "depth"),
        icon = GeekifyIcons.AutoAwesome,
        tint = IosPalette.systemCyan,
        onSelect = { onPersonalizationCategory(CustomizationCategory.LIQUID_GLASS) }
    ),
    SearchableSettingEntry(
        id = "cust_ascii_background",
        title = "ASCII Background & Performance",
        subtitle = "Animated creatures, atmospheric scenes, density, hue & battery profile",
        category = "Appearance",
        keywords = listOf(
            "ascii", "background", "animations", "tweaks", "butterfly", "butterflies",
            "koi", "jellyfish", "frog", "crane", "crab", "whale", "rain", "snow", "waves",
            "aurora", "detail", "grid", "battery saver"
        ),
        icon = GeekifyIcons.Tune,
        tint = IosPalette.systemIndigo,
        onSelect = { onPersonalizationCategory(CustomizationCategory.ASCII_BACKGROUND) }
    ),
    SearchableSettingEntry(
        id = "cust_home_screen",
        title = "Home Screen Layout & Sections",
        subtitle = "Reorder or hide home shelves, grid columns, card sizing & corner radius",
        category = "Appearance",
        keywords = listOf("home", "shelves", "carousel", "daily picks", "grid", "card radius", "greeting"),
        icon = GeekifyIcons.Home,
        tint = IosPalette.systemBlue,
        onSelect = { onPersonalizationCategory(CustomizationCategory.HOME_SCREEN) }
    ),
    SearchableSettingEntry(
        id = "cust_navigation",
        title = "Navigation Bar & Startup Tab",
        subtitle = "Visible destinations, tab order, capsule style, labels & startup tab",
        category = "Appearance",
        keywords = listOf("navigation", "tabs", "bottom bar", "default tab", "startup tab", "labels", "collapse", "scroll"),
        icon = GeekifyIcons.Navigation,
        tint = IosPalette.systemTeal,
        onSelect = { onPersonalizationCategory(CustomizationCategory.NAVIGATION) }
    ),
    SearchableSettingEntry(
        id = "cust_player_appearance",
        title = "Music Player Appearance (6 Layouts)",
        subtitle = "Vinyl tonearm, standard, minimal, artwork-focused, fullscreen & expressive layouts",
        category = "Appearance",
        keywords = listOf("player", "now playing", "vinyl", "tonearm", "minimal", "fullscreen", "expressive", "artwork shape", "backdrop"),
        icon = GeekifyIcons.Album,
        tint = IosPalette.systemPink,
        onSelect = { onPersonalizationCategory(CustomizationCategory.PLAYER_APPEARANCE) }
    ),
    SearchableSettingEntry(
        id = "cust_mini_player",
        title = "Mini-Player Style & Controls",
        subtitle = "Floating capsule, compact bar, thumbnail shape & visible buttons",
        category = "Appearance",
        keywords = listOf("mini player", "pill", "capsule", "progress ring", "corner radius"),
        icon = GeekifyIcons.PlayCircleFilled,
        tint = IosPalette.systemGreen,
        onSelect = { onPersonalizationCategory(CustomizationCategory.MINI_PLAYER) }
    ),
    SearchableSettingEntry(
        id = "cust_gestures",
        title = "Gestures & Touch Controls",
        subtitle = "Swipe artwork to skip, pull to collapse, queue swipe & haptics",
        category = "Appearance",
        keywords = listOf("gestures", "swipe", "haptics", "sensitivity", "skip", "collapse"),
        icon = GeekifyIcons.Tune,
        tint = IosPalette.systemYellow,
        onSelect = { onPersonalizationCategory(CustomizationCategory.GESTURES) }
    ),
    SearchableSettingEntry(
        id = "cust_typography",
        title = "Fonts, Typography & Display Density",
        subtitle = "Font manager, Google Sans, custom TTF/OTF import, text scale & density",
        category = "Interface",
        keywords = listOf(
            "font", "fonts", "font manager", "typography", "sohne", "sohne breit",
            "google sans", "lato", "roboto", "comic neue", "atkinson", "ttf", "otf",
            "text scale", "font size", "density", "landscape"
        ),
        icon = GeekifyIcons.Translate,
        tint = IosPalette.systemOrange,
        onSelect = { onPersonalizationCategory(CustomizationCategory.TYPOGRAPHY_DENSITY) }
    ),
    SearchableSettingEntry(
        id = "cust_lyrics",
        title = "Lyrics Appearance & Romanization",
        subtitle = "Synchronized lyrics font size, alignment, word highlight & phonetic romanization",
        category = "Appearance",
        keywords = listOf("lyrics", "karaoke", "romanization", "alignment", "line spacing", "synced"),
        icon = GeekifyIcons.Lyrics,
        tint = IosPalette.systemMint,
        onSelect = { onPersonalizationCategory(CustomizationCategory.LYRICS_APPEARANCE) }
    ),
    SearchableSettingEntry(
        id = "cust_playback_preferences",
        title = "Playback & Audio Preferences",
        subtitle = "Streaming quality, crossfade, skip silence, normalization, bass boost & autoplay",
        category = "Appearance",
        keywords = listOf("quality", "bitrate", "crossfade", "skip silence", "normalization", "bass boost", "queue", "autoplay", "shuffle", "repeat", "keep screen on"),
        icon = GeekifyIcons.GraphicEq,
        tint = IosPalette.systemRed,
        onSelect = { onPersonalizationCategory(CustomizationCategory.PLAYBACK_PREFERENCES) }
    ),
    SearchableSettingEntry(
        id = "cust_widgets",
        title = "Home Screen Widgets",
        subtitle = "Widget surface theme, artwork visibility & transport controls",
        category = "Appearance",
        keywords = listOf("widget", "home screen widget", "remoteviews"),
        icon = GeekifyIcons.ViewDay,
        tint = IosPalette.systemCyan,
        onSelect = { onPersonalizationCategory(CustomizationCategory.WIDGETS) }
    ),
    SearchableSettingEntry(
        id = "cust_app_icon",
        title = "App Icon",
        subtitle = "Choose the home screen launcher icon",
        category = "Appearance",
        keywords = listOf("icon", "launcher", "logo", "home screen"),
        icon = GeekifyIcons.Apps,
        tint = IosPalette.systemBlue,
        onSelect = onAppIconClick
    ),
    SearchableSettingEntry(
        id = "sys_equalizer",
        title = "Equalizer & Audio Effects Studio",
        subtitle = "5-band EQ, presets, speed & pitch shifting",
        category = "Audio Effects",
        keywords = listOf("eq", "equalizer", "5-band", "tempo", "speed", "pitch"),
        icon = GeekifyIcons.GraphicEq,
        tint = IosPalette.systemGreen,
        onSelect = onAudioClick
    ),
    SearchableSettingEntry(
        id = "sys_content_region",
        title = "Content Country, Language & Explicit Filter",
        subtitle = "Regional charts, catalog language & explicit content restriction",
        category = "Content & Region",
        keywords = listOf("country", "region", "language", "explicit", "india", "united states", "hindi", "malayalam", "tamil"),
        icon = GeekifyIcons.Language,
        tint = IosPalette.systemBlue,
        onSelect = { openPage(SettingsSubPage.CONTENT) }
    ),
    SearchableSettingEntry(
        id = "sys_notifications",
        title = "Notifications",
        subtitle = "App updates, feature tips, and listening reminders",
        category = "Notifications",
        keywords = listOf("notifications", "alerts", "reminders", "tips"),
        icon = GeekifyIcons.Notifications,
        tint = IosPalette.systemYellow,
        onSelect = { openPage(SettingsSubPage.NOTIFICATIONS) }
    ),
    SearchableSettingEntry(
        id = "sys_privacy",
        title = "Privacy, History & Screenshots",
        subtitle = "Pause or clear listening history and block screenshots",
        category = "Privacy & Security",
        keywords = listOf("privacy", "security", "pause history", "clear history", "screenshots", "incognito"),
        icon = GeekifyIcons.Shield,
        tint = IosPalette.systemIndigo,
        onSelect = { openPage(SettingsSubPage.PRIVACY) }
    ),
    SearchableSettingEntry(
        id = "sys_storage_backup",
        title = "Storage, Cache & Library Backup",
        subtitle = "Clear audio/image cache, export or restore JSON backup",
        category = "Storage & Backup",
        keywords = listOf("storage", "cache", "clear", "backup", "restore", "export", "import", "json"),
        icon = GeekifyIcons.Storage,
        tint = IosPalette.systemGreen,
        onSelect = { openPage(SettingsSubPage.STORAGE) }
    ),
    SearchableSettingEntry(
        id = "sys_offline_downloads",
        title = "Offline Downloads",
        subtitle = "Manage downloaded songs, progress & offline playback",
        category = "Storage & Downloads",
        keywords = listOf("downloads", "offline", "saved songs"),
        icon = GeekifyIcons.Download,
        tint = IosPalette.systemTeal,
        onSelect = onDownloadsClick
    ),
    SearchableSettingEntry(
        id = "sys_discord_rpc",
        title = "Discord Rich Presence",
        subtitle = "Broadcast your live Now Playing activity to Discord",
        category = "Integrations",
        keywords = listOf("discord", "rich presence", "rpc", "gateway", "status"),
        icon = GeekifyIcons.Groups,
        tint = IosPalette.systemIndigo,
        onSelect = { openPage(SettingsSubPage.DISCORD) }
    ),
    SearchableSettingEntry(
        id = "sys_extractor_health",
        title = "Extractor Health",
        subtitle = "Stream resolver and catalog diagnostics",
        category = "Diagnostics",
        keywords = listOf("health", "extractor", "probe"),
        icon = GeekifyIcons.MonitorHeart,
        tint = IosPalette.systemMint,
        onSelect = onHealthClick
    ),
    SearchableSettingEntry(
        id = "sys_playback_diagnostics",
        title = "Playback Diagnostics",
        subtitle = "Real-time stream resolution & reliability metrics",
        category = "Diagnostics",
        keywords = listOf("diagnostics", "latency", "codec", "stalls"),
        icon = GeekifyIcons.Speed,
        tint = IosPalette.systemGreen,
        onSelect = { openPage(SettingsSubPage.DIAGNOSTICS) }
    ),
    SearchableSettingEntry(
        id = "sys_listening_stats",
        title = "Listening Statistics",
        subtitle = "Listening history, top artists, minutes played & activity",
        category = "Content & Library",
        keywords = listOf("statistics", "stats", "history", "top artists", "listening", "wrapped", "activity"),
        icon = GeekifyIcons.Insights,
        tint = IosPalette.systemPurple,
        onSelect = onListeningStatsClick
    ),
    SearchableSettingEntry(
        id = "sys_account",
        title = "Account & Sign-In",
        subtitle = "Manage your Geekify & YouTube Music account",
        category = "Privacy & Security",
        keywords = listOf("account", "profile", "sign in", "login", "logout", "authentication", "youtube music"),
        icon = GeekifyIcons.AccountCircle,
        tint = IosPalette.systemBlue,
        onSelect = onProfileClick
    ),
    SearchableSettingEntry(
        id = "sys_updates",
        title = "Updates & Release Notes",
        subtitle = "Check for new Geekify versions and changelogs",
        category = "Application & About",
        keywords = listOf("updates", "update", "changelog", "release notes", "version", "apk", "github"),
        icon = GeekifyIcons.SystemUpdate,
        tint = IosPalette.systemGreen,
        onSelect = onCheckUpdatesClick
    ),
    SearchableSettingEntry(
        id = "sys_about",
        title = "About Geekify",
        subtitle = "Version info, contributors, community links & licenses",
        category = "Application & About",
        keywords = listOf("about", "version", "build", "contributors", "licenses", "open source", "github"),
        icon = GeekifyIcons.Info,
        tint = IosPalette.systemGray,
        onSelect = { openPage(SettingsSubPage.ABOUT) }
    )
).distinctBy { it.id }

@Composable
private fun <T> OptionPickerDialog(
    title: String,
    options: List<Pair<T, Pair<String, String>>>,
    selected: T,
    onSelect: (T) -> Unit,
    onDismiss: () -> Unit
) {
    GeekifyDialog(onDismissRequest = onDismiss) {
        Text(title, color = IosPalette.label, fontSize = 19.sp, fontWeight = FontWeight.Bold)
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
                        .clip(RoundedCornerShape(14.dp))
                        .background(
                            if (isSelected) SettingsVisuals.blue.copy(alpha = 0.14f)
                            else SettingsVisuals.cardRaised
                        )
                        .border(
                            width = 1.dp,
                            color = if (isSelected) SettingsVisuals.blue.copy(alpha = 0.72f) else SettingsVisuals.cardBorder,
                            shape = RoundedCornerShape(14.dp)
                        )
                        .clickable { onSelect(value) }
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = labels.first,
                            color = if (isSelected) SettingsVisuals.blue else IosPalette.label,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = labels.second,
                            color = IosPalette.secondaryLabel,
                            fontSize = 12.5.sp
                        )
                    }
                    if (isSelected) {
                        Icon(
                            imageVector = GeekifyIcons.Check,
                            contentDescription = null,
                            tint = SettingsVisuals.blue,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        DialogPillButton(
            text = "Cancel",
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

@Composable
private fun PlaybackDiagnosticsSubPage() {
    val context = LocalContext.current
    val diagnosticsTracker: com.geekify.android.player.PlaybackDiagnosticsTracker = org.koin.compose.koinInject()
    val snapshot by diagnosticsTracker.snapshot.collectAsState()
    var copiedReport by remember { mutableStateOf(false) }

    IosSettingsSection(title = "Stream resolution health") {
        IosSettingsRow(
            icon = GeekifyIcons.Speed,
            tileColor = IosPalette.systemGreen,
            title = "Resolution Success Rate",
            value = "${"%.1f".format(snapshot.resolutionSuccessRate)}%",
            subtitle = "${snapshot.successfulResolutions} successful / ${snapshot.totalResolutions} total"
        )
        IosSettingsDivider()
        IosSettingsRow(
            icon = GeekifyIcons.Timer,
            tileColor = IosPalette.systemBlue,
            title = "Avg Resolution Latency",
            value = "${snapshot.avgResolutionLatencyMs} ms",
            subtitle = "Time to resolve streaming media URL"
        )
        IosSettingsDivider()
        IosSettingsRow(
            icon = GeekifyIcons.Public,
            tileColor = IosPalette.systemPurple,
            title = "Network Condition",
            value = snapshot.networkCondition
        )
    }

    Spacer(Modifier.height(24.dp))
    IosSettingsSection(title = "Audio pipeline & stability") {
        IosSettingsRow(
            icon = GeekifyIcons.Audiotrack,
            tileColor = IosPalette.systemOrange,
            title = "Active Codec & Format",
            value = snapshot.audioFormat
        )
        IosSettingsDivider()
        IosSettingsRow(
            icon = GeekifyIcons.ErrorOutline,
            tileColor = IosPalette.systemRed,
            title = "Playback Stalls",
            value = "${snapshot.stallCount}",
            subtitle = "${snapshot.totalBufferingDurationMs} ms total buffering duration"
        )
    }

    Spacer(Modifier.height(24.dp))
    IosSettingsSection(title = "Scrubbed diagnostics report") {
        IosSettingsRow(
            icon = GeekifyIcons.UploadFile,
            tileColor = IosPalette.systemTeal,
            title = if (copiedReport) "Copied to Clipboard!" else "Copy Diagnostic Report",
            subtitle = "Privacy-safe report with all URLs, tokens, and PII redacted",
            onClick = {
                val report = diagnosticsTracker.generateScrubbedReport()
                val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
                clipboard?.setPrimaryClip(android.content.ClipData.newPlainText("Geekify Diagnostics", report))
                copiedReport = true
            }
        )
    }
}

@Composable
private fun DiscordRichPresenceSubPage() {
    val discordManager: com.geekify.android.discord.DiscordRpcManager = org.koin.compose.koinInject()
    val uiState by discordManager.uiState.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var appIdInput by remember(uiState.applicationId) { mutableStateOf(uiState.applicationId) }
    var tokenInput by remember { mutableStateOf("") }

    val statusTextAndColor: Pair<String, Color> = when (val s = uiState.status) {
        is com.geekify.android.discord.DiscordConnectionStatus.Disabled ->
            "Disabled" to IosPalette.secondaryLabel
        is com.geekify.android.discord.DiscordConnectionStatus.NotConfigured ->
            "Not configured (enter Application ID & Token)" to IosPalette.systemYellow
        is com.geekify.android.discord.DiscordConnectionStatus.Idle ->
            "Ready (will connect on playback or Test)" to IosPalette.systemCyan
        is com.geekify.android.discord.DiscordConnectionStatus.Connecting ->
            "Connecting to Discord Gateway v10..." to IosPalette.systemBlue
        is com.geekify.android.discord.DiscordConnectionStatus.Connected ->
            ("Connected" + (s.username?.let { " as @$it" } ?: "")) to IosPalette.systemGreen
        is com.geekify.android.discord.DiscordConnectionStatus.Error ->
            s.message to IosPalette.systemRed
    }

    IosSettingsSection(
        title = "Discord Rich Presence",
        footer = "Broadcasts your current song title, artist, and elapsed/remaining time as a Listening activity on Discord while music is playing."
    ) {
        IosSettingsSwitchRow(
            icon = GeekifyIcons.Groups,
            tileColor = IosPalette.systemIndigo,
            title = "Enable Discord Rich Presence",
            subtitle = "Connect to Discord Gateway when playback starts",
            checked = uiState.enabled,
            onCheckedChange = { enabled ->
                scope.launch {
                    discordManager.setConfig(
                        enabled = enabled,
                        applicationId = appIdInput,
                        accessToken = tokenInput.takeIf { it.isNotBlank() }
                    )
                }
            }
        )
        IosSettingsDivider()
        IosSettingsRow(
            icon = GeekifyIcons.MonitorHeart,
            tileColor = IosPalette.systemGreen,
            title = "Connection Status",
            subtitle = statusTextAndColor.first,
            value = when (uiState.status) {
                is com.geekify.android.discord.DiscordConnectionStatus.Connected -> "Online"
                is com.geekify.android.discord.DiscordConnectionStatus.Connecting -> "..."
                is com.geekify.android.discord.DiscordConnectionStatus.Error -> "Error"
                else -> "Offline"
            }
        )
        uiState.lastSyncedTrack?.let { synced ->
            IosSettingsDivider()
            IosSettingsRow(
                icon = GeekifyIcons.Audiotrack,
                tileColor = IosPalette.systemPurple,
                title = "Active Presence",
                subtitle = synced
            )
        }
    }

    Spacer(Modifier.height(20.dp))

    IosSettingsSection(
        title = "Authentication & Credentials",
        footer = "Android Connection Requirements:\n1. Create or select a Discord Application at discord.com/developers/applications and copy its Application ID.\n2. Provide an OAuth2 access token with presence scope (or authorize via OAuth2 PKCE in your browser).\n3. Keep an active internet connection while playing music."
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(
                value = appIdInput,
                onValueChange = { appIdInput = it },
                label = { Text("Discord Application ID") },
                placeholder = { Text("e.g. 123456789012345678") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = tokenInput,
                onValueChange = { tokenInput = it },
                label = {
                    Text(
                        if (uiState.hasToken) "Access Token (Saved: ${uiState.maskedToken})"
                        else "Discord Access Token"
                    )
                },
                placeholder = { Text("Paste token to set or update") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                Button(
                    onClick = {
                        scope.launch {
                            discordManager.setConfig(
                                enabled = true,
                                applicationId = appIdInput,
                                accessToken = tokenInput.takeIf { it.isNotBlank() }
                            )
                            tokenInput = ""
                        }
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Save & Connect")
                }
                OutlinedButton(
                    onClick = {
                        val id = appIdInput.trim()
                        if (id.isNotBlank()) {
                            val (url, _) = discordManager.buildOAuthPkceUrl(id)
                            runCatching {
                                context.startActivity(
                                    android.content.Intent(
                                        android.content.Intent.ACTION_VIEW,
                                        android.net.Uri.parse(url)
                                    )
                                )
                            }
                        }
                    },
                    enabled = appIdInput.isNotBlank()
                ) {
                    Text("OAuth2 Browser")
                }
            }
        }
    }
}
