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
import com.geekify.android.ui.library.SettingsScreen
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
    startRoute: String? = null,
    onStartRouteConsumed: () -> Unit = {}
) {
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route

    var showExpandedPlayer by remember { mutableStateOf(false) }
    var showQueue by remember { mutableStateOf(false) }
    var showAccountSheet by remember { mutableStateOf(false) }
    var showCreatePlaylist by remember { mutableStateOf(false) }
    var actionTrack by remember { mutableStateOf<Track?>(null) }

    val accountState by accountViewModel.uiState.collectAsState()
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

    LaunchedEffect(Unit) {
        availableUpdate = updateManager.findAvailableUpdate()
    }

    // Deep link from a Geekify notification (e.g. "recents"). Only known routes are accepted.
    LaunchedEffect(startRoute) {
        if (startRoute == Screen.Recents.route) {
            navController.navigate(Screen.Recents.route) { launchSingleTop = true }
        }
        if (startRoute != null) onStartRouteConsumed()
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

    // Height of the floating mini player + navigation pill, so scrolling lists can leave room for them.
    var floatingBarsHeightPx by remember { mutableIntStateOf(0) }
    val floatingBarsHeight = with(LocalDensity.current) { floatingBarsHeightPx.toDp() }

    val navigateToTab: (String) -> Unit = { route ->
        if (route == Screen.Home.route) {
            // A playlist/detail screen is stacked above Home. Pop back to the
            // existing Home instance instead of trying to restore a nested state.
            val returnedHome = navController.popBackStack(Screen.Home.route, inclusive = false)
            if (!returnedHome) {
                navController.navigate(Screen.Home.route) { launchSingleTop = true }
            }
        } else if (currentRoute != route) {
            navController.navigate(route) {
                popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                launchSingleTop = true
                restoreState = true
            }
        }
    }

    // Two backdrops: the CONTENT (what the floating nav / mini player float over) and the whole SCENE
    // (what popups, dialogs, panels and the queue float over). Glass never samples itself.
    val contentBackdrop = rememberGlassBackdrop()
    val sceneBackdrop = rememberGlassBackdrop()
    val overlayHost = remember { GlassOverlayHost() }

    CompositionLocalProvider(
        LocalNowPlayingId provides playerState.current?.videoId,
        LocalBottomInset provides floatingBarsHeight,
        LocalGlassOverlayHost provides overlayHost
    ) {
    Box(modifier = Modifier.fillMaxSize().background(InkBackground)) {
      // ---- SCENE: everything that sits underneath popups / dialogs / panels ----
      Box(modifier = Modifier.fillMaxSize().glassBackdropSource(sceneBackdrop, enabled = overlayHost.entries.isNotEmpty() || showQueue).background(InkBackground)) {
      CompositionLocalProvider(LocalGlassBackdrop provides contentBackdrop) {
        // Main content stays clean, sharp and opaque. It is only RECORDED so the floating bars can sample it.
        Box(modifier = Modifier.fillMaxSize().glassBackdropSource(contentBackdrop).background(InkBackground)) {
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

                    composable(Screen.Settings.route) {
                        SettingsScreen(
                            onBack = { navController.popBackStack() },
                            onAudioClick = { navController.navigate(Screen.AudioSettings.route) }
                        )
                    }

                    composable(Screen.ListenTogether.route) {
                        ListenTogetherScreen(onBack = { navController.popBackStack() })
                    }

                    composable(Screen.AudioSettings.route) {
                        AudioSettingsScreen(onBack = { navController.popBackStack() })
                    }

                    composable(Screen.EditProfile.route) {
                        EditProfileScreen(viewModel = accountViewModel, onBack = { navController.popBackStack() })
                    }

                    composable(Screen.AddAccount.route) {
                        AddAccountScreen(viewModel = accountViewModel, onBack = { navController.popBackStack() })
                    }
                }

        }

        // ---- Floating mini player + navigation pill (content scrolls underneath) ----
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .onSizeChanged { floatingBarsHeightPx = it.height }
                // Topmost pointer target for the whole bottom strip (pills AND the gaps between/around them):
                // a tap here can never reach a song, album or card underneath.
                .consumeTaps()
        ) {
            // Fades the list into the background behind the pills.
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .background(
                        Brush.verticalGradient(
                            0f to Color.Transparent,
                            0.5f to InkBackground.copy(alpha = 0.55f),
                            1f to InkBackground.copy(alpha = 0.85f)
                        )
                    )
            )
            Column(
                modifier = Modifier
                    .navigationBarsPadding()
                    .padding(start = 16.dp, end = 16.dp, top = 30.dp, bottom = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                NowPlayingBar(
                    viewModel = playerViewModel,
                    onClick = { showExpandedPlayer = true }
                )
                FloatingNavBar(
                    currentRoute = currentRoute,
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
                onHealthClick = { navController.navigate(Screen.Health.route) }
            )
        }

        availableUpdate?.let { update ->
            UpdateDialog(
                stage = updateStage,
                currentVersion = BuildConfig.VERSION_NAME,
                newVersion = update.version.trimStart('v', 'V'),
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
}

/**
 * Floating Liquid Glass pill with the primary destinations. The lime selection circle slides between items on a spring.
 * Settings lives in the profile menu. Every item is a 56dp target with a content description.
 */
@Composable
private fun FloatingNavBar(
    currentRoute: String?,
    onHome: () -> Unit,
    onSearch: () -> Unit,
    onLibrary: () -> Unit,
    onCreate: () -> Unit
) {
    val selectedIndex = when (currentRoute) {
        Screen.Home.route -> 0
        Screen.Search.route -> 1
        Screen.Library.route -> 2
        else -> -1
    }
    val reduced = rememberReducedMotion()
    val pressed = remember { mutableStateOf(false) }
    val pressAnim by animateFloatAsState(if (pressed.value) 1f else 0f, label = "navPress")

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .liquidGlass(CircleShape, GlassStyle.Navigation, pressProgress = { pressAnim })
            .trackPress(pressed)
            .consumeTaps() // the bar itself is a pointer target; item clicks still win because children are handled first
    ) {
        val itemSize = 56.dp
        val inner = maxWidth - 20.dp
        val gap = (inner - itemSize * 4) / 3
        val targetX = (itemSize + gap) * selectedIndex.coerceAtLeast(0)
        val indicatorX by animateDpAsState(
            targetValue = targetX,
            animationSpec = if (reduced) snap() else spring(dampingRatio = 0.72f, stiffness = Spring.StiffnessMedium),
            label = "navIndicatorX"
        )
        val indicatorAlpha by animateFloatAsState(if (selectedIndex >= 0) 1f else 0f, label = "navIndicatorAlpha")

        Box(modifier = Modifier.padding(horizontal = 10.dp, vertical = 9.dp)) {
            Box(
                modifier = Modifier
                    .offset(x = indicatorX)
                    .size(itemSize)
                    .graphicsLayer { alpha = indicatorAlpha }
                    .clip(CircleShape)
                    .background(Lime)
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                NavPillItem(selectedIndex == 0, Icons.Filled.Home, Icons.Outlined.Home, "Home", onHome)
                NavPillItem(selectedIndex == 1, Icons.Filled.Search, Icons.Outlined.Search, "Search", onSearch)
                NavPillItem(selectedIndex == 2, Icons.Filled.LibraryMusic, Icons.Outlined.LibraryMusic, "My Music", onLibrary)
                // "Create" is an action, never a destination.
                NavPillItem(false, Icons.Filled.Add, Icons.Outlined.Add, "Create playlist", onCreate)
            }
        }
    }
}

@Composable
private fun NavPillItem(
    selected: Boolean,
    selectedIcon: ImageVector,
    icon: ImageVector,
    label: String,
    onClick: () -> Unit
) {
    val fg by animateColorAsState(if (selected) OnAccent else Color.White.copy(alpha = 0.94f), tween(220), label = "navFg")
    Box(
        modifier = Modifier
            .size(56.dp)
            .clip(CircleShape)
            .bouncyClickable(pressedScale = 0.9f, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = if (selected) selectedIcon else icon,
            contentDescription = label,
            tint = fg,
            modifier = Modifier.size(26.dp)
        )
    }
}
