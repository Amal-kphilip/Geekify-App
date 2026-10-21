package com.geekify.android.ui.library

import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
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

private enum class SettingsSubPage(val title: String) {
    ROOT("Settings"),
    APPEARANCE("Appearance"),
    PLAYER("Player & audio"),
    CONTENT("Content & region"),
    NOTIFICATIONS("Notifications"),
    PRIVACY("Privacy & security"),
    STORAGE("Storage & backup"),
    UPDATES("Software update"),
    ABOUT("About")
}

private data class SettingsRootItem(
    val key: String,
    val title: String,
    val subtitle: String? = null,
    val value: String? = null,
    val icon: ImageVector,
    val tint: Color,
    val subPage: SettingsSubPage? = null,
    val onClick: () -> Unit
)

private data class SearchableSettingEntry(
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
    var splitActivePage by rememberSaveable { mutableStateOf(SettingsSubPage.APPEARANCE) }
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
            .background(IosPalette.systemGroupedBackground)
    ) {
        val isSplitLayout = maxWidth >= 840.dp

        val rootGroups = remember(
            state,
            storageStats.totalBytes,
            countryName,
            isSplitLayout
        ) {
            listOf(
                listOf(
                    SettingsRootItem(
                        key = "listen_together",
                        title = "Listen Together",
                        subtitle = "Host or join synchronized listening rooms",
                        icon = GeekifyIcons.Groups,
                        tint = IosPalette.systemCyan,
                        onClick = onListenTogetherClick
                    ),
                    SettingsRootItem(
                        key = "listening_stats",
                        title = "Listening Statistics",
                        subtitle = "Top songs, artists, and listening streaks",
                        icon = GeekifyIcons.Insights,
                        tint = IosPalette.systemPurple,
                        onClick = onListeningStatsClick
                    ),
                    SettingsRootItem(
                        key = "recents",
                        title = "Recents & History",
                        subtitle = "Recently played tracks across sessions",
                        icon = GeekifyIcons.History,
                        tint = IosPalette.systemOrange,
                        onClick = onRecentsClick
                    )
                ),
                listOf(
                    SettingsRootItem(
                        key = "appearance",
                        title = "Appearance & Liquid Glass",
                        value = if (state.glass.enabled) "${state.glass.blurDp.roundToInt()} dp" else "Off",
                        icon = GeekifyIcons.AutoAwesome,
                        tint = IosPalette.systemCyan,
                        subPage = SettingsSubPage.APPEARANCE,
                        onClick = { openSubPage(SettingsSubPage.APPEARANCE, isSplitLayout) }
                    ),
                    SettingsRootItem(
                        key = "app_icon",
                        title = "App Icon",
                        icon = GeekifyIcons.Apps,
                        tint = IosPalette.systemBlue,
                        onClick = onAppIconClick
                    ),
                    SettingsRootItem(
                        key = "player",
                        title = "Player & Audio",
                        value = state.audioQuality.label,
                        icon = GeekifyIcons.PlayCircleFilled,
                        tint = IosPalette.systemRed,
                        subPage = SettingsSubPage.PLAYER,
                        onClick = { openSubPage(SettingsSubPage.PLAYER, isSplitLayout) }
                    ),
                    SettingsRootItem(
                        key = "equalizer",
                        title = "Equalizer & Effects",
                        icon = GeekifyIcons.GraphicEq,
                        tint = IosPalette.systemGreen,
                        onClick = onAudioClick
                    )
                ),
                listOf(
                    SettingsRootItem(
                        key = "content",
                        title = "Content & Region",
                        value = countryName,
                        icon = GeekifyIcons.Language,
                        tint = IosPalette.systemBlue,
                        subPage = SettingsSubPage.CONTENT,
                        onClick = { openSubPage(SettingsSubPage.CONTENT, isSplitLayout) }
                    ),
                    SettingsRootItem(
                        key = "notifications",
                        title = "Notifications",
                        icon = GeekifyIcons.Notifications,
                        tint = IosPalette.systemYellow,
                        subPage = SettingsSubPage.NOTIFICATIONS,
                        onClick = { openSubPage(SettingsSubPage.NOTIFICATIONS, isSplitLayout) }
                    )
                ),
                listOf(
                    SettingsRootItem(
                        key = "privacy",
                        title = "Privacy & Security",
                        value = if (state.pauseListenHistory) "Paused" else null,
                        icon = GeekifyIcons.Shield,
                        tint = IosPalette.systemIndigo,
                        subPage = SettingsSubPage.PRIVACY,
                        onClick = { openSubPage(SettingsSubPage.PRIVACY, isSplitLayout) }
                    ),
                    SettingsRootItem(
                        key = "storage",
                        title = "Storage & Backup",
                        value = formatBytes(storageStats.totalBytes),
                        icon = GeekifyIcons.Storage,
                        tint = IosPalette.systemGreen,
                        subPage = SettingsSubPage.STORAGE,
                        onClick = { openSubPage(SettingsSubPage.STORAGE, isSplitLayout) }
                    )
                ),
                listOf(
                    SettingsRootItem(
                        key = "updates",
                        title = "Software Update",
                        value = "v${BuildConfig.VERSION_NAME}",
                        icon = GeekifyIcons.SystemUpdate,
                        tint = IosPalette.systemTeal,
                        subPage = SettingsSubPage.UPDATES,
                        onClick = { openSubPage(SettingsSubPage.UPDATES, isSplitLayout) }
                    ),
                    SettingsRootItem(
                        key = "health",
                        title = "Extractor Health",
                        icon = GeekifyIcons.MonitorHeart,
                        tint = IosPalette.systemMint,
                        onClick = onHealthClick
                    ),
                    SettingsRootItem(
                        key = "about",
                        title = "About",
                        icon = GeekifyIcons.Info,
                        tint = IosPalette.systemGray,
                        subPage = SettingsSubPage.ABOUT,
                        onClick = { openSubPage(SettingsSubPage.ABOUT, isSplitLayout) }
                    )
                )
            )
        }

        val searchRegistry = remember(isSplitLayout) {
            buildSearchRegistry(
                openPage = { openSubPage(it, isSplitLayout) },
                onProfileClick = onProfileClick,
                onListenTogetherClick = onListenTogetherClick,
                onListeningStatsClick = onListeningStatsClick,
                onRecentsClick = onRecentsClick,
                onAudioClick = onAudioClick,
                onAppIconClick = onAppIconClick,
                onHealthClick = onHealthClick
            )
        }

        @Composable
        fun RenderSubPageContent(page: SettingsSubPage) {
            // Optional status toast banner
            AnimatedVisibility(visible = statusMessage != null) {
                statusMessage?.let { msg ->
                    IosStatusBanner(
                        message = msg,
                        onDismiss = settingsViewModel::clearStatusMessage
                    )
                }
            }

            when (page) {
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
                SettingsSubPage.ROOT -> Unit
            }
        }

        if (isSplitLayout) {
            // Cupertino Split-View Master-Detail Layout for Tablets / Wide Screens
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
                        bottomPadding = bottomInset + 104.dp,
                        floatingBottomBar = {
                            DockedSearchPill(
                                query = searchQuery,
                                onQueryChange = { searchQuery = it },
                                modifier = Modifier
                                    .align(Alignment.BottomCenter)
                                    .padding(bottom = bottomInset + 16.dp)
                            )
                        }
                    ) {
                        RootListBody(
                            searchQuery = searchQuery,
                            currentUser = currentUser,
                            rootGroups = rootGroups,
                            searchRegistry = searchRegistry,
                            selectedSubPage = splitActivePage,
                            onProfileClick = onProfileClick,
                            onClearSearch = { searchQuery = "" }
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
                            bottomPadding = bottomInset + 40.dp
                        ) {
                            RenderSubPageContent(detailPage)
                        }
                    }
                }
            }
        } else {
            // Single-pane push/pop navigation with preserved scroll state
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
                if (page == SettingsSubPage.ROOT) {
                    IosSettingsPageScaffold(
                        title = "Settings",
                        scrollState = rootScrollState,
                        onBack = onBack,
                        bottomPadding = bottomInset + 108.dp,
                        floatingBottomBar = {
                            DockedSearchPill(
                                query = searchQuery,
                                onQueryChange = { searchQuery = it },
                                modifier = Modifier
                                    .align(Alignment.BottomCenter)
                                    .padding(bottom = bottomInset + 16.dp)
                            )
                        }
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
                            currentUser = currentUser,
                            rootGroups = rootGroups,
                            searchRegistry = searchRegistry,
                            selectedSubPage = null,
                            onProfileClick = onProfileClick,
                            onClearSearch = { searchQuery = "" }
                        )
                    }
                } else {
                    val subScroll = rememberSaveable(page.name, saver = ScrollState.Saver) { ScrollState(0) }
                    IosSettingsPageScaffold(
                        title = page.title,
                        scrollState = subScroll,
                        onBack = { currentPage = SettingsSubPage.ROOT },
                        bottomPadding = bottomInset + 40.dp
                    ) {
                        RenderSubPageContent(page)
                    }
                }
            }
        }
    }
}

@Composable
private fun RootListBody(
    searchQuery: String,
    currentUser: AccountUser?,
    rootGroups: List<List<SettingsRootItem>>,
    searchRegistry: List<SearchableSettingEntry>,
    selectedSubPage: SettingsSubPage?,
    onProfileClick: () -> Unit,
    onClearSearch: () -> Unit
) {
    val trimmedQuery = searchQuery.trim()
    if (trimmedQuery.isEmpty()) {
        ProfileCard(
            user = currentUser,
            onClick = onProfileClick
        )
        Spacer(Modifier.height(24.dp))

        rootGroups.forEachIndexed { groupIdx, group ->
            IosSettingsSection {
                group.forEachIndexed { idx, item ->
                    IosSettingsRow(
                        title = item.title,
                        subtitle = item.subtitle,
                        value = item.value,
                        icon = item.icon,
                        tileColor = item.tint,
                        selected = selectedSubPage != null && item.subPage == selectedSubPage,
                        onClick = item.onClick
                    )
                    if (idx < group.lastIndex) {
                        IosSettingsDivider()
                    }
                }
            }
            if (groupIdx < rootGroups.lastIndex) {
                Spacer(Modifier.height(24.dp))
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
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = GeekifyIcons.Search,
                        contentDescription = null,
                        tint = IosPalette.tertiaryLabel,
                        modifier = Modifier.size(36.dp)
                    )
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = "No results for \"$trimmedQuery\"",
                        color = IosPalette.secondaryLabel,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        } else {
            IosSettingsSection(title = "Results (${results.size})") {
                results.forEachIndexed { idx, entry ->
                    IosSettingsRow(
                        title = entry.title,
                        subtitle = "${entry.category} · ${entry.subtitle}",
                        icon = entry.icon,
                        tileColor = entry.tint,
                        onClick = {
                            onClearSearch()
                            entry.onSelect()
                        }
                    )
                    if (idx < results.lastIndex) {
                        IosSettingsDivider()
                    }
                }
            }
        }
    }
}

@Composable
private fun ProfileCard(
    user: AccountUser?,
    onClick: () -> Unit
) {
    Surface(
        color = IosPalette.secondarySystemGroupedBackground,
        shape = IosCardShape,
        modifier = Modifier
            .fillMaxWidth()
            .bouncyClickable(pressedScale = 0.98f, onClick = onClick)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(60.dp)
                    .clip(CircleShape)
                    .background(IosPalette.tertiarySystemGroupedBackground),
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
                        tint = IosPalette.secondaryLabel,
                        modifier = Modifier.size(28.dp)
                    )
                }
            }
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = user?.name?.ifBlank { "Geekify Listener" } ?: "Sign in",
                    color = IosPalette.label,
                    fontSize = 20.sp,
                    lineHeight = 25.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = (-0.4).sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = user?.email ?: "Cloud sync, playlists and backup",
                    color = IosPalette.secondaryLabel,
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(Modifier.width(8.dp))
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
private fun DockedSearchPill(
    query: String,
    onQueryChange: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val focusManager = LocalFocusManager.current
    var focused by remember { mutableStateOf(false) }
    val showCancel = focused || query.isNotEmpty()

    Box(
        modifier = modifier.fillMaxWidth(),
        contentAlignment = Alignment.Center
    ) {
        Row(
            modifier = Modifier
                .widthIn(max = 720.dp)
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .height(48.dp)
                    .shadow(elevation = 10.dp, shape = CircleShape, clip = false)
                    .clip(CircleShape)
                    .background(IosPalette.secondarySystemGroupedBackground)
                    .border(0.5.dp, Color.White.copy(alpha = 0.12f), CircleShape)
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = GeekifyIcons.Search,
                    contentDescription = "Search",
                    tint = IosPalette.secondaryLabel,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(10.dp))
                Box(
                    modifier = Modifier.weight(1f),
                    contentAlignment = Alignment.CenterStart
                ) {
                    if (query.isEmpty()) {
                        Text(
                            text = "Search settings",
                            color = IosPalette.secondaryLabel,
                            fontSize = 16.sp,
                            maxLines = 1
                        )
                    }
                    BasicTextField(
                        value = query,
                        onValueChange = onQueryChange,
                        singleLine = true,
                        textStyle = TextStyle(
                            color = IosPalette.label,
                            fontSize = 16.sp
                        ),
                        cursorBrush = SolidColor(IosPalette.systemBlue),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus() }),
                        modifier = Modifier
                            .fillMaxWidth()
                            .onFocusChanged { focused = it.isFocused }
                    )
                }
                if (query.isNotEmpty()) {
                    Box(
                        modifier = Modifier
                            .size(20.dp)
                            .clip(CircleShape)
                            .background(IosPalette.tertiaryLabel)
                            .clickable(
                                indication = null,
                                interactionSource = remember { MutableInteractionSource() }
                            ) { onQueryChange("") },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = GeekifyIcons.Close,
                            contentDescription = "Clear",
                            tint = IosPalette.secondarySystemGroupedBackground,
                            modifier = Modifier.size(12.dp)
                        )
                    }
                }
            }

            AnimatedVisibility(
                visible = showCancel,
                enter = fadeIn() + expandHorizontally(expandFrom = Alignment.Start),
                exit = fadeOut() + shrinkHorizontally(shrinkTowards = Alignment.Start)
            ) {
                Text(
                    text = "Cancel",
                    color = IosPalette.systemBlue,
                    fontSize = 17.sp,
                    modifier = Modifier
                        .padding(start = 12.dp)
                        .clickable(
                            indication = null,
                            interactionSource = remember { MutableInteractionSource() }
                        ) {
                            onQueryChange("")
                            focusManager.clearFocus()
                        }
                        .padding(vertical = 8.dp)
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
        color = IosPalette.secondarySystemGroupedBackground,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 16.dp)
            .border(0.5.dp, IosPalette.systemGreen.copy(alpha = 0.55f), RoundedCornerShape(16.dp))
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
    viewModel: SettingsViewModel,
    onAppIconClick: () -> Unit
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
            // Horizontally pannable vibrant backdrop recorded into previewBackdrop
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

            // Live Mini Player + Floating Nav Bar sampling previewBackdrop
            CompositionLocalProvider(LocalGlassBackdrop provides previewBackdrop) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalArrangement = Arrangement.SpaceBetween,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    // Mock Mini Player Pill
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

                    // Mock Floating Nav Bar + Search Orb
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
            value = tabLabel,
            onClick = { showDefaultTabDialog = true }
        )
        IosSettingsDivider()
        IosSettingsRow(
            icon = GeekifyIcons.Apps,
            tileColor = IosPalette.systemBlue,
            title = "App Icon",
            subtitle = "Choose the home screen icon",
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

    IosSettingsSection(title = "Audio & streaming") {
        IosSettingsRow(
            icon = GeekifyIcons.GraphicEq,
            tileColor = IosPalette.systemGreen,
            title = "Equalizer & Audio Effects",
            subtitle = "5-band EQ, presets, speed, pitch & loudness",
            onClick = onAudioClick
        )
        IosSettingsDivider()
        IosSettingsRow(
            icon = GeekifyIcons.HighQuality,
            tileColor = IosPalette.systemRed,
            title = "Streaming Quality",
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

    IosSettingsSection(
        title = "Catalog region & language",
        footer = "Changing your country or language immediately refreshes regional charts, trending shelves, and recommendations on Home."
    ) {
        IosSettingsRow(
            icon = GeekifyIcons.Public,
            tileColor = IosPalette.systemBlue,
            title = "Content Country",
            value = countryName,
            subtitle = "Charts & new releases region (${state.contentCountry})",
            onClick = { showCountryDialog = true }
        )
        IosSettingsDivider()
        IosSettingsRow(
            icon = GeekifyIcons.Translate,
            tileColor = IosPalette.systemTeal,
            title = "Content Language",
            value = languageName.substringBefore(" ("),
            subtitle = languageName,
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

    IosSettingsSection(title = "Storage usage (${formatBytes(stats.totalBytes)})") {
        IosSettingsRow(
            icon = GeekifyIcons.Audiotrack,
            tileColor = IosPalette.systemGreen,
            title = "Clear Audio Stream Cache",
            value = formatBytes(stats.audioCacheBytes),
            subtitle = "300 MB automatic cache limit",
            onClick = viewModel::clearAudioCache
        )
        IosSettingsDivider()
        IosSettingsRow(
            icon = GeekifyIcons.Image,
            tileColor = IosPalette.systemCyan,
            title = "Clear Artwork & Image Cache",
            value = formatBytes(stats.imageCacheBytes),
            subtitle = "Cached album and artist covers",
            onClick = viewModel::clearImageCache
        )
        IosSettingsDivider()
        IosSettingsRow(
            icon = GeekifyIcons.Dataset,
            tileColor = IosPalette.systemOrange,
            title = "Prune Cached Feed & Metadata",
            value = formatBytes(stats.metadataBytes),
            subtitle = "Local database and catalog cache",
            onClick = viewModel::clearMetadataCache
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
// SUB-PAGE 7: SOFTWARE UPDATE
// ============================================================================
@Composable
private fun UpdatesSubPage(
    updateCacheBytes: Long,
    onCheckUpdatesClick: () -> Unit,
    onClearUpdateCache: () -> Unit
) {
    val context = LocalContext.current
    IosSettingsSection(title = "Installed version") {
        IosSettingsRow(
            icon = GeekifyIcons.Verified,
            tileColor = IosPalette.systemGreen,
            title = "Geekify v${BuildConfig.VERSION_NAME}",
            value = "Build ${BuildConfig.VERSION_CODE}",
            subtitle = "Installed on this device",
            showChevron = false,
            onClick = null
        )
        IosSettingsDivider()
        IosSettingsRow(
            icon = GeekifyIcons.SystemUpdate,
            tileColor = IosPalette.systemTeal,
            title = "Check for Updates Now",
            subtitle = "Query GitHub Releases for a newer APK",
            onClick = onCheckUpdatesClick
        )
        IosSettingsDivider()
        IosSettingsRow(
            icon = GeekifyIcons.OpenInNew,
            tileColor = IosPalette.systemBlue,
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
            IosSettingsDivider()
            IosSettingsRow(
                icon = GeekifyIcons.DeleteOutline,
                tileColor = IosPalette.systemRed,
                title = "Clear Downloaded Update APKs",
                value = formatBytes(updateCacheBytes),
                destructive = true,
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
        color = IosPalette.secondarySystemGroupedBackground,
        shape = IosCardShape,
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
                    .clip(RoundedCornerShape(16.dp))
                    .background(IosPalette.systemCyan.copy(alpha = 0.16f))
                    .border(0.5.dp, IosPalette.systemCyan.copy(alpha = 0.45f), RoundedCornerShape(16.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = GeekifyIcons.AutoAwesome,
                    contentDescription = null,
                    tint = IosPalette.systemCyan,
                    modifier = Modifier.size(32.dp)
                )
            }
            Spacer(Modifier.height(12.dp))
            Text(
                text = "Geekify",
                color = IosPalette.label,
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = (-0.4).sp
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "A midnight music space built around your queue, library, and listening trail.",
                color = IosPalette.secondaryLabel,
                fontSize = 13.5.sp,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(12.dp))
            Surface(
                color = IosPalette.tertiarySystemGroupedBackground,
                shape = CircleShape
            ) {
                Text(
                    text = "v${BuildConfig.VERSION_NAME} · Build ${BuildConfig.VERSION_CODE}",
                    color = IosPalette.label,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)
                )
            }
        }
    }

    Spacer(Modifier.height(24.dp))
    IosSettingsSection(title = "Build & links") {
        IosSettingsRow(
            icon = GeekifyIcons.CalendarToday,
            tileColor = IosPalette.systemPurple,
            title = "First Installed",
            value = installDate,
            showChevron = false,
            onClick = null
        )
        IosSettingsDivider()
        IosSettingsRow(
            icon = GeekifyIcons.PhoneAndroid,
            tileColor = IosPalette.systemCyan,
            title = "Android Runtime",
            value = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
            showChevron = false,
            onClick = null
        )
        IosSettingsDivider()
        IosSettingsRow(
            icon = GeekifyIcons.Code,
            tileColor = IosPalette.systemGray,
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
// DEEP SEARCH REGISTRY
// ============================================================================
private fun buildSearchRegistry(
    openPage: (SettingsSubPage) -> Unit,
    onProfileClick: () -> Unit,
    onListenTogetherClick: () -> Unit,
    onListeningStatsClick: () -> Unit,
    onRecentsClick: () -> Unit,
    onAudioClick: () -> Unit,
    onAppIconClick: () -> Unit,
    onHealthClick: () -> Unit
): List<SearchableSettingEntry> = listOf(
    SearchableSettingEntry(
        title = "Account & Cloud Sync",
        subtitle = "Sign in, profile, and cloud playlist sync",
        category = "Account",
        keywords = listOf("profile", "login", "sign in", "google", "sync", "account"),
        icon = GeekifyIcons.Person,
        tint = IosPalette.systemBlue,
        onSelect = onProfileClick
    ),
    SearchableSettingEntry(
        title = "Listen Together",
        subtitle = "Host or join synchronized listening rooms",
        category = "Activity & Social",
        keywords = listOf("room", "party", "friends", "live", "together"),
        icon = GeekifyIcons.Groups,
        tint = IosPalette.systemCyan,
        onSelect = onListenTogetherClick
    ),
    SearchableSettingEntry(
        title = "Listening Statistics",
        subtitle = "Top songs, artists, and listening streaks",
        category = "Activity & Social",
        keywords = listOf("stats", "analytics", "minutes", "top tracks", "wrapped"),
        icon = GeekifyIcons.Insights,
        tint = IosPalette.systemPurple,
        onSelect = onListeningStatsClick
    ),
    SearchableSettingEntry(
        title = "Recents & History",
        subtitle = "Recently played songs across your sessions",
        category = "Activity & Social",
        keywords = listOf("history", "recent", "played", "log"),
        icon = GeekifyIcons.History,
        tint = IosPalette.systemOrange,
        onSelect = onRecentsClick
    ),
    SearchableSettingEntry(
        title = "Liquid Glass",
        subtitle = "Real-time AGSL lens refraction and backdrop blur",
        category = "Appearance",
        keywords = listOf("glass", "blur", "refraction", "lens", "vibrancy", "saturation", "shader"),
        icon = GeekifyIcons.AutoAwesome,
        tint = IosPalette.systemCyan,
        onSelect = { openPage(SettingsSubPage.APPEARANCE) }
    ),
    SearchableSettingEntry(
        title = "Mini Player & Navigation Bar Glass",
        subtitle = "Toggle liquid glass on the Mini Player or bottom bar",
        category = "Appearance",
        keywords = listOf("mini player", "nav bar", "navigation bar", "floating bar", "pill"),
        icon = GeekifyIcons.Navigation,
        tint = IosPalette.systemBlue,
        onSelect = { openPage(SettingsSubPage.APPEARANCE) }
    ),
    SearchableSettingEntry(
        title = "Chromatic Aberration & Depth",
        subtitle = "Optical RGB dispersion and spherical lens depth",
        category = "Appearance",
        keywords = listOf("chromatic", "aberration", "depth", "spherical", "bevel", "tint"),
        icon = GeekifyIcons.Flare,
        tint = IosPalette.systemPurple,
        onSelect = { openPage(SettingsSubPage.APPEARANCE) }
    ),
    SearchableSettingEntry(
        title = "Adaptive Bottom Bar & Default Tab",
        subtitle = "Scroll collapse behavior and startup tab",
        category = "Appearance",
        keywords = listOf("default tab", "home", "search", "library", "collapse", "scroll"),
        icon = GeekifyIcons.ViewDay,
        tint = IosPalette.systemTeal,
        onSelect = { openPage(SettingsSubPage.APPEARANCE) }
    ),
    SearchableSettingEntry(
        title = "App Icon",
        subtitle = "Choose the home screen launcher icon",
        category = "Appearance",
        keywords = listOf("icon", "launcher", "logo", "home screen"),
        icon = GeekifyIcons.Apps,
        tint = IosPalette.systemBlue,
        onSelect = onAppIconClick
    ),
    SearchableSettingEntry(
        title = "Equalizer & Audio Effects",
        subtitle = "5-band EQ, presets, speed, pitch & volume normalization",
        category = "Player & Audio",
        keywords = listOf("eq", "equalizer", "bass", "tempo", "speed", "pitch", "skip silence", "normalize"),
        icon = GeekifyIcons.GraphicEq,
        tint = IosPalette.systemGreen,
        onSelect = onAudioClick
    ),
    SearchableSettingEntry(
        title = "Streaming Audio Quality",
        subtitle = "High, Normal, or Data Saver audio bitrate",
        category = "Player & Audio",
        keywords = listOf("quality", "bitrate", "kbps", "stream", "data saver", "high quality"),
        icon = GeekifyIcons.HighQuality,
        tint = IosPalette.systemRed,
        onSelect = { openPage(SettingsSubPage.PLAYER) }
    ),
    SearchableSettingEntry(
        title = "Persistent Queue & Endless Autoplay",
        subtitle = "Save queue across restarts and auto-queue related tracks",
        category = "Player & Audio",
        keywords = listOf("queue", "autoplay", "shuffle", "repeat", "keep screen on", "skip error"),
        icon = GeekifyIcons.QueueMusic,
        tint = IosPalette.systemPurple,
        onSelect = { openPage(SettingsSubPage.PLAYER) }
    ),
    SearchableSettingEntry(
        title = "Content Country & Language",
        subtitle = "Regional charts, new releases, and catalog language",
        category = "Content & Region",
        keywords = listOf("country", "region", "language", "india", "united states", "hindi", "malayalam", "tamil"),
        icon = GeekifyIcons.Language,
        tint = IosPalette.systemBlue,
        onSelect = { openPage(SettingsSubPage.CONTENT) }
    ),
    SearchableSettingEntry(
        title = "Hide Explicit Content",
        subtitle = "Filter explicit songs from Home, Search, and Autoplay",
        category = "Content & Region",
        keywords = listOf("explicit", "clean", "filter", "restrict"),
        icon = GeekifyIcons.Explicit,
        tint = IosPalette.systemRed,
        onSelect = { openPage(SettingsSubPage.CONTENT) }
    ),
    SearchableSettingEntry(
        title = "Notifications",
        subtitle = "App updates, feature tips, and listening reminders",
        category = "Notifications",
        keywords = listOf("notifications", "alerts", "reminders", "tips"),
        icon = GeekifyIcons.Notifications,
        tint = IosPalette.systemYellow,
        onSelect = { openPage(SettingsSubPage.NOTIFICATIONS) }
    ),
    SearchableSettingEntry(
        title = "Privacy, History & Screenshots",
        subtitle = "Pause or clear listening history and block screenshots",
        category = "Privacy & Security",
        keywords = listOf("privacy", "security", "pause history", "clear history", "screenshots", "incognito"),
        icon = GeekifyIcons.Shield,
        tint = IosPalette.systemIndigo,
        onSelect = { openPage(SettingsSubPage.PRIVACY) }
    ),
    SearchableSettingEntry(
        title = "Storage, Cache & Library Backup",
        subtitle = "Clear audio/image cache, export or restore JSON backup",
        category = "Storage & Backup",
        keywords = listOf("storage", "cache", "clear", "backup", "restore", "export", "import", "json"),
        icon = GeekifyIcons.Storage,
        tint = IosPalette.systemGreen,
        onSelect = { openPage(SettingsSubPage.STORAGE) }
    ),
    SearchableSettingEntry(
        title = "Software Update",
        subtitle = "Check GitHub Releases for a newer version",
        category = "Software Update",
        keywords = listOf("update", "version", "apk", "release", "github"),
        icon = GeekifyIcons.SystemUpdate,
        tint = IosPalette.systemTeal,
        onSelect = { openPage(SettingsSubPage.UPDATES) }
    ),
    SearchableSettingEntry(
        title = "Extractor Health",
        subtitle = "Stream resolver and catalog diagnostics",
        category = "Diagnostics",
        keywords = listOf("health", "extractor", "diagnostics", "stream"),
        icon = GeekifyIcons.MonitorHeart,
        tint = IosPalette.systemMint,
        onSelect = onHealthClick
    ),
    SearchableSettingEntry(
        title = "About Geekify",
        subtitle = "Version info, runtime details, and repository link",
        category = "About",
        keywords = listOf("about", "version", "build", "credits", "github"),
        icon = GeekifyIcons.Info,
        tint = IosPalette.systemGray,
        onSelect = { openPage(SettingsSubPage.ABOUT) }
    )
)

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
                            if (isSelected) IosPalette.systemBlue.copy(alpha = 0.18f)
                            else IosPalette.tertiarySystemGroupedBackground
                        )
                        .border(
                            width = 1.dp,
                            color = if (isSelected) IosPalette.systemBlue.copy(alpha = 0.65f) else Color.Transparent,
                            shape = RoundedCornerShape(14.dp)
                        )
                        .clickable { onSelect(value) }
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = labels.first,
                            color = if (isSelected) IosPalette.systemBlue else IosPalette.label,
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
                            tint = IosPalette.systemBlue,
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
