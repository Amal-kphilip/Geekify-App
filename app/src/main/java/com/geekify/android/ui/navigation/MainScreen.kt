package com.geekify.android.ui.navigation

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.LibraryMusic
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.animation.animateColorAsState
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.geekify.android.data.model.Card
import com.geekify.android.data.model.CollectionKind
import com.geekify.android.data.model.Track
import com.geekify.android.ui.account.AccountSheet
import com.geekify.android.audio.AudioSettingsScreen
import com.geekify.android.listentogether.ListenTogetherScreen
import com.geekify.android.ui.account.AccountViewModel
import com.geekify.android.ui.account.AddAccountScreen
import com.geekify.android.ui.account.EditProfileScreen
import com.geekify.android.ui.components.CreatePlaylistDialog
import com.geekify.android.ui.components.LocalBottomInset
import com.geekify.android.ui.components.LocalNowPlayingId
import com.geekify.android.ui.components.bouncyClickable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.graphics.graphicsLayer
import com.geekify.android.ui.glass.GlassOverlayHost
import com.geekify.android.ui.glass.GlassOverlays
import com.geekify.android.ui.glass.GlassStyle
import com.geekify.android.ui.glass.LocalGlassBackdrop
import com.geekify.android.ui.glass.LocalGlassOverlayHost
import com.geekify.android.ui.glass.consumeTaps
import com.geekify.android.ui.glass.glassBackdropSource
import com.geekify.android.ui.glass.liquidGlass
import com.geekify.android.ui.glass.rememberGlassBackdrop
import com.geekify.android.ui.glass.rememberReducedMotion
import com.geekify.android.ui.glass.trackPress
import com.geekify.android.ui.components.TrackActionsDialog
import com.geekify.android.ui.details.ArtistScreen
import com.geekify.android.ui.details.CollectionScreen
import com.geekify.android.ui.details.DetailsViewModel
import com.geekify.android.ui.health.HealthScreen
import com.geekify.android.ui.health.HealthViewModel
import com.geekify.android.ui.home.HomeScreen
import com.geekify.android.ui.home.HomeViewModel
import com.geekify.android.ui.library.LibraryScreen
import com.geekify.android.ui.library.LibraryViewModel
import com.geekify.android.ui.library.LikedScreen
import com.geekify.android.ui.library.PlaylistScreen
import com.geekify.android.ui.library.RecentsScreen
import com.geekify.android.ui.library.RecentsViewModel
import com.geekify.android.ui.library.ListeningStatsScreen
import com.geekify.android.ui.library.SettingsScreen
import com.geekify.android.ui.library.AppIconSettingsScreen
import com.geekify.android.settings.LocalAppSettings
import com.geekify.android.settings.LocalGlassConfig
import com.geekify.android.settings.SettingsViewModel
import com.geekify.android.ui.player.ExpandedPlayerScreen
import com.geekify.android.ui.player.NowPlayingBar
import com.geekify.android.ui.player.PlayerViewModel
import com.geekify.android.ui.player.QueueScreen
import com.geekify.android.ui.search.SearchScreen
import com.geekify.android.ui.search.SearchViewModel
import com.geekify.android.ui.theme.*
import com.geekify.android.BuildConfig
import com.geekify.android.ui.update.UpdateDialog
import com.geekify.android.ui.update.UpdateStage
import com.geekify.android.update.AppUpdateManager
import com.geekify.android.update.AvailableUpdate
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private data class NavTab(
    val route: String,
    val label: String,
    val selectedIcon: ImageVector,
    val icon: ImageVector
)

@Composable
fun MainScreen(
    navController: NavHostController = rememberNavController(),
    playerViewModel: PlayerViewModel = hiltViewModel(),
    accountViewModel: AccountViewModel = hiltViewModel(),
    libraryViewModel: LibraryViewModel = hiltViewModel(),
    settingsViewModel: SettingsViewModel = hiltViewModel(),
    startRoute: String? = null,
    onStartRouteConsumed: () -> Unit = {},
    triggerUpdateCheck: Boolean = false,
    onUpdateCheckConsumed: () -> Unit = {}
) {
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route

    var showExpandedPlayer by remember { mutableStateOf(false) }
    var showQueue by remember { mutableStateOf(false) }
    var showAccountSheet by remember { mutableStateOf(false) }
    var showCreatePlaylist by remember { mutableStateOf(false) }
    var miniPlayerDismissed by remember { mutableStateOf(false) }
    var actionTrack by remember { mutableStateOf<Track?>(null) }

    val accountState by accountViewModel.uiState.collectAsState()
    val appSettingsState by settingsViewModel.settings.collectAsState()
    val playerState by playerViewModel.state.collectAsState()
    val likedTracks by libraryViewModel.liked.collectAsState()
    val playlists by libraryViewModel.playlists.collectAsState()
    val savedCollections by libraryViewModel.savedCollections.collectAsState()
    val avatarUrl = accountState.user?.photoUrl
    val avatarName = accountState.user?.name
    val context = LocalContext.current
    val updateManager = remember { AppUpdateManager(context.applicationContext) }
    val updateScope = rememberCoroutineScope()
    var availableUpdate by remember { mutableStateOf<AvailableUpdate?>(null) }
    var updateStage by remember { mutableStateOf(UpdateStage.Available) }
    var downloadedApk by remember { mutableStateOf<java.io.File?>(null) }
    // null = size unknown (indeterminate ring); otherwise the real 0f..1f download fraction.
    var updateProgress by remember { mutableStateOf<Float?>(null) }
    var updateError by remember { mutableStateOf<String?>(null) }

    var hasCheckedUpdates by remember { mutableStateOf(false) }
    LaunchedEffect(triggerUpdateCheck) {
        // Check once on normal launch, and again whenever the user taps an update notification.
        if (!hasCheckedUpdates || triggerUpdateCheck) {
            hasCheckedUpdates = true
            availableUpdate = updateManager.findAvailableUpdate()
            if (triggerUpdateCheck) {
                updateStage = UpdateStage.Available
                downloadedApk = null
                updateProgress = null
                updateError = null
                onUpdateCheckConsumed()
            }
        }
    }

    // Deep link from a Geekify notification (e.g. "recents"). Only known routes are accepted.
    LaunchedEffect(startRoute) {
        when (startRoute) {
            Screen.Recents.route -> navController.navigate(Screen.Recents.route) { launchSingleTop = true }
            Screen.Library.route -> navController.navigate(Screen.Library.route) { launchSingleTop = true }
            Screen.Search.route -> navController.navigate(Screen.Search.route) { launchSingleTop = true }
            Screen.Home.route -> navController.navigate(Screen.Home.route) { launchSingleTop = true }
            Screen.ListeningStats.route -> navController.navigate(Screen.ListeningStats.route) { launchSingleTop = true }
            Screen.ListenTogether.route -> navController.navigate(Screen.ListenTogether.route) { launchSingleTop = true }
            Screen.AudioSettings.route -> navController.navigate(Screen.AudioSettings.route) { launchSingleTop = true }
            Screen.Settings.route -> navController.navigate(Screen.Settings.route) { launchSingleTop = true }
            else -> Unit
        }
        if (startRoute != null) onStartRouteConsumed()
        run {

        }
        run {
        }
    }

    // Back handling lives inside each overlay (ExpandedPlayerScreen, QueueScreen) so the topmost
    // overlay always wins over the NavHost: Player -> Queue -> Back returns to the Player.

    val onTrackClick: (Track, List<Track>) -> Unit = { track, list ->
        playerViewModel.play(track, list)
    }
    val onTrackActions: (Track) -> Unit = { track -> actionTrack = track }

    val onCardClick: (Card) -> Unit = { card ->
        when (card.type) {
            "artist" -> {
                val id = card.browseId ?: card.id
                navController.navigate(Screen.Artist.createRoute(id))
            }
            "album" -> {
                val id = card.browseId ?: card.id
                navController.navigate(Screen.Collection.createRoute(id, CollectionKind.ALBUM.name))
            }
            "playlist" -> {
                val id = card.playlistId ?: card.id
                navController.navigate(Screen.Collection.createRoute(id, CollectionKind.PLAYLIST.name))
            }
            "song" -> {
                val vid = card.videoId ?: card.id
                val track = Track(videoId = vid, title = card.title, artist = card.subtitle ?: "Unknown", thumbnails = card.thumbnails)
                playerViewModel.play(track)
            }
        }
    }

    // A paused mini player can be dismissed with a downward swipe. Starting playback or
    // switching to another track makes the mini player available again.
    LaunchedEffect(playerState.current?.videoId) {
        miniPlayerDismissed = false
    }
    LaunchedEffect(playerState.isPlaying) {
        if (playerState.isPlaying) miniPlayerDismissed = false
    }

    // Height of the floating mini player + navigation pill, so scrolling lists can leave room for them.
    var floatingBarsHeightPx by remember { mutableIntStateOf(0) }
    val floatingBarsHeight = with(LocalDensity.current) { floatingBarsHeightPx.toDp() }
    val scrollConnection = rememberFloatingTabBarScrollConnection(inlineBehavior = appSettingsState.navInlineBehavior)

    var appliedDefaultTab by remember { mutableStateOf(false) }
    LaunchedEffect(appSettingsState.defaultTab) {
        if (!appliedDefaultTab) {
            appliedDefaultTab = true
            if (startRoute == null) {
                when (appSettingsState.defaultTab) {
                    "search" -> navController.navigate(Screen.Search.route) { launchSingleTop = true }
                    "library" -> navController.navigate(Screen.Library.route) { launchSingleTop = true }
                }
            }
        }
    }

    val navigateToTab: (String) -> Unit = { route ->
        scrollConnection.expand()
        when (route) {
            Screen.Home.route -> {
                // Return directly to the existing Home root instead of restoring
                // a previously saved nested destination/state.
                val returnedHome = navController.popBackStack(Screen.Home.route, inclusive = false)
                if (!returnedHome && currentRoute != Screen.Home.route) {
                    navController.navigate(Screen.Home.route) {
                        launchSingleTop = true
                        restoreState = false
                    }
                }
            }

            Screen.Library.route -> {
                // Library can have Playlist/Collection/Artist destinations stacked above it.
                // Pop directly to the existing Library root so the tab always becomes active.
                val returnedLibrary = navController.popBackStack(Screen.Library.route, inclusive = false)
                if (!returnedLibrary && currentRoute != Screen.Library.route) {
                    navController.navigate(Screen.Library.route) {
                        launchSingleTop = true
                        restoreState = false
                    }
                }
            }

            Screen.Search.route -> {
                // Return to the existing Search root when coming from another destination.
                // When Search is already selected, recreate just that root destination so a
                // previously entered query/results state is cleared and the Search tab responds.
                if (currentRoute == Screen.Search.route) {
                    navController.navigate(Screen.Search.route) {
                        popUpTo(Screen.Search.route) { inclusive = true }
                        launchSingleTop = true
                        restoreState = false
                    }
                } else {
                    val returnedSearch = navController.popBackStack(Screen.Search.route, inclusive = false)
                    if (!returnedSearch) {
                        navController.navigate(Screen.Search.route) {
                            launchSingleTop = true
                            restoreState = false
                        }
                    }
                }
            }

            else -> {
                if (currentRoute != route) {
                    navController.navigate(route) {
                        launchSingleTop = true
                        restoreState = false
                    }
                }
            }
        }
    }

    // Two backdrops: the CONTENT (what the floating nav / mini player float over) and the whole SCENE
    // (what popups, dialogs, panels and the queue float over). Glass never samples itself.
    val contentBackdrop = rememberGlassBackdrop()
    val sceneBackdrop = rememberGlassBackdrop()
    val overlayHost = remember { GlassOverlayHost() }

    val animatedFloatingBarsHeight by animateDpAsState(
        targetValue = with(LocalDensity.current) { floatingBarsHeightPx.toDp() },
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "floatingBarsHeight"
    )

    CompositionLocalProvider(
        LocalNowPlayingId provides playerState.current?.videoId,
        LocalBottomInset provides animatedFloatingBarsHeight,
        LocalGlassOverlayHost provides overlayHost,
        LocalGlassBackdrop provides contentBackdrop,
        LocalAppSettings provides appSettingsState,
        LocalGlassConfig provides appSettingsState.glass
    ) {
    Box(modifier = Modifier.fillMaxSize().background(InkBackground)) {
      // ---- SCENE: everything that sits underneath popups / dialogs / panels ----
      Box(modifier = Modifier.fillMaxSize().glassBackdropSource(sceneBackdrop, enabled = overlayHost.entries.isNotEmpty() || showQueue).background(InkBackground)) {
        // Main content stays clean, sharp and opaque. It is only RECORDED so the floating bars can sample it.
        Box(modifier = Modifier.fillMaxSize().glassBackdropSource(contentBackdrop).nestedScroll(scrollConnection).background(InkBackground)) {
                NavHost(
                    modifier = Modifier.fillMaxSize(),
                    navController = navController,
                    startDestination = Screen.Home.route,
                    enterTransition = { fadeIn(tween(240)) },
                    exitTransition = { fadeOut(tween(160)) },
                    popEnterTransition = { fadeIn(tween(240)) },
                    popExitTransition = { fadeOut(tween(160)) }
                ) {
                    composable(Screen.Home.route) {
                        val homeVm: HomeViewModel = hiltViewModel()
                        HomeScreen(
                            viewModel = homeVm,
                            photoUrl = avatarUrl,
                            userName = avatarName,
                            onLikedClick = { navController.navigate(Screen.Liked.route) },
                            onTrackClick = onTrackClick,
                            onTrackActions = onTrackActions,
                            onCardClick = onCardClick,
                            onAccountClick = { showAccountSheet = true },
                            onSearchClick = { navigateToTab(Screen.Search.route) },
                            likedIds = likedTracks.map { it.videoId }.toSet(),
                            onToggleLike = { libraryViewModel.toggleLike(it) },
                            onAddToQueue = { playerViewModel.addToQueue(it) }
                        )
                    }

                    composable(Screen.Search.route) {
                        val searchVm: SearchViewModel = hiltViewModel()
                        SearchScreen(
                            viewModel = searchVm,
                            photoUrl = avatarUrl,
                            userName = avatarName,
                            onAvatarClick = { showAccountSheet = true },
                            onTrackClick = onTrackClick,
                            onTrackActions = onTrackActions,
                            onCardClick = onCardClick
                        )
                    }

                    composable(Screen.Library.route) {
                        val libraryVm: LibraryViewModel = hiltViewModel()
                        LibraryScreen(
                            viewModel = libraryVm,
                            photoUrl = avatarUrl,
                            userName = avatarName,
                            onAvatarClick = { showAccountSheet = true },
                            onLikedClick = { navController.navigate(Screen.Liked.route) },
                            onPlaylistClick = { id, name -> navController.navigate(Screen.Playlist.createRoute(id, name)) },
                            onCollectionClick = { id, kind -> navController.navigate(Screen.Collection.createRoute(id, kind)) },
                            onBack = { navigateToTab(Screen.Home.route) },
                            onPlayTracks = { list -> list.firstOrNull()?.let { first -> playerViewModel.play(first, list) } }
                        )
                    }

                    composable(Screen.Liked.route) {
                        val libraryVm: LibraryViewModel = hiltViewModel()
                        LikedScreen(
                            viewModel = libraryVm,
                            onBack = { navController.popBackStack() },
                            onPlayTrack = onTrackClick,
                            onShufflePlay = { playerViewModel.playShuffled(it) }
                        )
                    }

                    composable(
                        route = Screen.Playlist.route,
                        arguments = listOf(
                            navArgument("id") { type = NavType.StringType },
                            navArgument("name") { type = NavType.StringType }
                        )
                    ) { entry ->
                        val id = entry.arguments?.getString("id") ?: ""
                        val name = entry.arguments?.getString("name") ?: "Playlist"
                        val libraryVm: LibraryViewModel = hiltViewModel()
                        PlaylistScreen(
                            playlistId = id,
                            playlistName = name,
                            viewModel = libraryVm,
                            onBack = { navController.popBackStack() },
                            onPlayTrack = onTrackClick,
                            onShufflePlay = { playerViewModel.playShuffled(it) }
                        )
                    }

                    composable(
                        route = Screen.Artist.route,
                        arguments = listOf(navArgument("id") { type = NavType.StringType })
                    ) { entry ->
                        val id = entry.arguments?.getString("id") ?: ""
                        val detailsVm: DetailsViewModel = hiltViewModel()
                        ArtistScreen(
                            artistId = id,
                            viewModel = detailsVm,
                            onBack = { navController.popBackStack() },
                            onTrackClick = onTrackClick,
                            onTrackActions = onTrackActions,
                            onCardClick = onCardClick
                        )
                    }

                    composable(
                        route = Screen.Collection.route,
                        arguments = listOf(
                            navArgument("id") { type = NavType.StringType },
                            navArgument("kind") { type = NavType.StringType }
                        )
                    ) { entry ->
                        val id = entry.arguments?.getString("id") ?: ""
                        val kindStr = entry.arguments?.getString("kind") ?: CollectionKind.PLAYLIST.name
                        val kind = runCatching { CollectionKind.valueOf(kindStr) }.getOrDefault(CollectionKind.PLAYLIST)
                        val detailsVm: DetailsViewModel = hiltViewModel()
                        CollectionScreen(
                            id = id,
                            kind = kind,
                            viewModel = detailsVm,
                            onBack = { navController.popBackStack() },
                            onPlayTrack = onTrackClick,
                            onTrackActions = onTrackActions,
                            onShufflePlay = { playerViewModel.playShuffled(it) },
                            isSaved = savedCollections.any { it.id == id },
                            onToggleSaved = { libraryViewModel.toggleSavedCollection(it) }
                        )
                    }

                    composable(Screen.Health.route) {
                        val healthVm: HealthViewModel = hiltViewModel()
                        HealthScreen(
                            viewModel = healthVm,
                            onBack = { navController.popBackStack() }
                        )
                    }

                    composable(Screen.Recents.route) {
                        val recentsVm: RecentsViewModel = hiltViewModel()
                        RecentsScreen(recentsVm, onBack = { navController.popBackStack() }, onPlay = onTrackClick)
                    }

                    composable(Screen.ListeningStats.route) {
                        ListeningStatsScreen(onBack = { navController.popBackStack() })
                    }

                    composable(Screen.Settings.route) {
                        SettingsScreen(
                            onBack = { navController.popBackStack() },
                            onAudioClick = { navController.navigate(Screen.AudioSettings.route) },
                            onAppIconClick = { navController.navigate(Screen.AppIconSettings.route) },
                            onProfileClick = { showAccountSheet = true },
                            onListenTogetherClick = { navController.navigate(Screen.ListenTogether.route) },
                            onListeningStatsClick = { navController.navigate(Screen.ListeningStats.route) },
                            onRecentsClick = { navController.navigate(Screen.Recents.route) },
                            onHealthClick = { navController.navigate(Screen.Health.route) },
                            onCheckUpdatesClick = {
                                updateScope.launch {
                                    val found = updateManager.findAvailableUpdate()
                                    if (found != null) {
                                        availableUpdate = found
                                        updateStage = UpdateStage.Available
                                        downloadedApk = null
                                        updateProgress = null
                                        updateError = null
                                    } else {
                                        android.widget.Toast.makeText(context, "Geekify is up to date (v${BuildConfig.VERSION_NAME})", android.widget.Toast.LENGTH_SHORT).show()
                                    }
                                }
                            },
                            currentUser = accountState.user
                        )
                    }

                    composable(Screen.ListenTogether.route) {
                        ListenTogetherScreen(onBack = { navController.popBackStack() })
                    }

                    composable(Screen.AudioSettings.route) {
                        AudioSettingsScreen(onBack = { navController.popBackStack() })
                    }

                    composable(Screen.AppIconSettings.route) {
                        AppIconSettingsScreen(onBack = { navController.popBackStack() })
                    }

                    composable(Screen.EditProfile.route) {
                        EditProfileScreen(viewModel = accountViewModel, onBack = { navController.popBackStack() })
                    }

                    composable(Screen.AddAccount.route) {
                        AddAccountScreen(viewModel = accountViewModel, onBack = { navController.popBackStack() })
                    }
                }
        }

        // ---- Floating adaptive navigation bar + mini player (content scrolls underneath) ----
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .onSizeChanged { floatingBarsHeightPx = it.height }
                .consumeTaps()
        ) {
            Box(
                modifier = Modifier
                    .navigationBarsPadding()
                    .padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 12.dp)
            ) {
                AdaptiveBottomBar(
                    scrollConnection = scrollConnection,
                    playerViewModel = playerViewModel,
                    currentRoute = currentRoute,
                    libraryDetailActive = navBackStackEntry?.destination?.route in setOf(Screen.Liked.route, Screen.Playlist.route, Screen.Collection.route) &&
                        navController.previousBackStackEntry?.destination?.route == Screen.Library.route,
                    miniPlayerDismissed = miniPlayerDismissed,
                    onDismissMiniPlayer = {
                        if (!playerState.isPlaying) miniPlayerDismissed = true
                    },
                    onOpenExpandedPlayer = { showExpandedPlayer = true },
                    onHome = { navigateToTab(Screen.Home.route) },
                    onSearch = { navigateToTab(Screen.Search.route) },
                    onLibrary = { navigateToTab(Screen.Library.route) },
                    onCreate = { showCreatePlaylist = true }
                )
            }
        }

      }

        // Fullscreen Expanded Player Overlay
        AnimatedVisibility(
            visible = showExpandedPlayer,
            enter = slideInVertically(tween(380, easing = FastOutSlowInEasing), initialOffsetY = { it }) + fadeIn(tween(250)),
            // Do not slide the touch layer away while it is fading. That left exposed areas
            // where a second tap could activate cards on the screen behind the player.
            exit = fadeOut(tween(180)),
            modifier = Modifier.fillMaxSize()
        ) {
            ExpandedPlayerScreen(
                viewModel = playerViewModel,
                onDismiss = { showExpandedPlayer = false },
                onQueueClick = {
                    showExpandedPlayer = false
                    showQueue = true
                }
            )
        }

      } // end SCENE

      CompositionLocalProvider(LocalGlassBackdrop provides sceneBackdrop) {
        // Queue Overlay (a glass container over the dimmed scene)
        AnimatedVisibility(
            visible = showQueue,
            enter = slideInVertically(tween(380, easing = FastOutSlowInEasing), initialOffsetY = { it }) + fadeIn(tween(250)),
            exit = slideOutVertically(tween(320, easing = FastOutSlowInEasing), targetOffsetY = { it }) + fadeOut(tween(250)),
            modifier = Modifier.fillMaxSize()
        ) {
            QueueScreen(
                viewModel = playerViewModel,
                onBack = {
                    showQueue = false
                    showExpandedPlayer = true
                }
            )
        }

        // Create playlist (from the "Create" tab)
        if (showCreatePlaylist) {
            val createVm: LibraryViewModel = hiltViewModel()
            CreatePlaylistDialog(
                onDismiss = { showCreatePlaylist = false },
                onCreate = {
                    createVm.createPlaylist(it)
                    showCreatePlaylist = false
                }
            )
        }

        actionTrack?.let { track ->
            TrackActionsDialog(
                track = track,
                liked = likedTracks.any { it.videoId == track.videoId },
                playlists = playlists,
                onDismiss = { actionTrack = null },
                onToggleLike = { libraryViewModel.toggleLike(track) },
                onAddToQueue = { playerViewModel.addToQueue(track) },
                onAddToPlaylist = { playlistId -> libraryViewModel.addToPlaylist(playlistId, track) },
                onCreatePlaylist = { name -> libraryViewModel.createPlaylistWithTrack(name, track) }
            )
        }

        // Account / Auth Bottom Sheet
        if (showAccountSheet) {
            AccountSheet(
                viewModel = accountViewModel,
                onDismiss = { showAccountSheet = false },
                onRecentsClick = { navController.navigate(Screen.Recents.route) },
                onSettingsClick = { navController.navigate(Screen.Settings.route) },
                onEditProfileClick = { navController.navigate(Screen.EditProfile.route) },
                onAddAccountClick = { navController.navigate(Screen.AddAccount.route) },
                onListenTogetherClick = { navController.navigate(Screen.ListenTogether.route) },
                onListeningStatsClick = { navController.navigate(Screen.ListeningStats.route) },
                onHealthClick = { navController.navigate(Screen.Health.route) }
            )
        }

        availableUpdate?.let { update ->
            UpdateDialog(
                stage = updateStage,
                currentVersion = BuildConfig.VERSION_NAME,
                newVersion = update.version.trimStart('v', 'V'),
                releaseNotes = update.releaseNotes,
                progress = updateProgress,
                error = updateError,
                onUpdate = {
                    updateStage = UpdateStage.Downloading
                    updateError = null
                    updateProgress = null
                    updateScope.launch {
                        runCatching {
                            updateManager.download(update) { updateProgress = it }
                        }.onSuccess { apk ->
                            downloadedApk = apk
                            updateProgress = 1f
                            updateStage = UpdateStage.Ready
                        }.onFailure {
                            updateStage = UpdateStage.Available
                            updateError = "Couldn’t download the update. Please try again."
                        }
                    }
                },
                onInstall = {
                    downloadedApk?.let { apk ->
                        updateStage = UpdateStage.Installing
                        updateManager.install(apk)
                        updateScope.launch {
                            // If the system installer was cancelled, let the person try again.
                            delay(3000)
                            if (updateStage == UpdateStage.Installing) updateStage = UpdateStage.Ready
                        }
                    }
                },
                onLater = { availableUpdate = null }
            )
        }

        // Popups, dialogs and the profile panel register here so they can be glass over the scene.
        GlassOverlays(overlayHost)
      }
    }
}
