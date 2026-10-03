package com.geekify.android.ui.navigation

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.LibraryMusic
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
import com.geekify.android.ui.account.AccountViewModel
import com.geekify.android.ui.components.CreatePlaylistDialog
import com.geekify.android.ui.components.LocalNowPlayingId
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
import com.geekify.android.ui.player.ExpandedPlayerScreen
import com.geekify.android.ui.player.NowPlayingBar
import com.geekify.android.ui.player.PlayerViewModel
import com.geekify.android.ui.player.QueueScreen
import com.geekify.android.ui.search.SearchScreen
import com.geekify.android.ui.search.SearchViewModel
import com.geekify.android.ui.theme.*
import com.geekify.android.update.AppUpdateManager
import com.geekify.android.update.AvailableUpdate
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
    accountViewModel: AccountViewModel = hiltViewModel()
) {
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route

    var showExpandedPlayer by remember { mutableStateOf(false) }
    var showQueue by remember { mutableStateOf(false) }
    var showAccountSheet by remember { mutableStateOf(false) }
    var showCreatePlaylist by remember { mutableStateOf(false) }

    val accountState by accountViewModel.uiState.collectAsState()
    val playerState by playerViewModel.state.collectAsState()
    val avatarUrl = accountState.user?.photoUrl
    val avatarName = accountState.user?.name
    val context = LocalContext.current
    val updateManager = remember { AppUpdateManager(context.applicationContext) }
    val updateScope = rememberCoroutineScope()
    var availableUpdate by remember { mutableStateOf<AvailableUpdate?>(null) }
    var isDownloadingUpdate by remember { mutableStateOf(false) }
    var updateError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        availableUpdate = updateManager.findAvailableUpdate()
    }

    // System back closes the player / queue overlays first.
    BackHandler(enabled = showQueue) { showQueue = false }
    BackHandler(enabled = showExpandedPlayer && !showQueue) { showExpandedPlayer = false }

    val onTrackClick: (Track, List<Track>) -> Unit = { track, list ->
        playerViewModel.play(track, list)
    }

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

    CompositionLocalProvider(LocalNowPlayingId provides playerState.current?.videoId) {
    Box(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            containerColor = InkBackground,
            bottomBar = {
                Column(modifier = Modifier.background(Color.Transparent)) {
                    // Persistent Mini Player - only visible when not expanded
                    if (!showExpandedPlayer && !showQueue) {
                        NowPlayingBar(
                            viewModel = playerViewModel,
                            onClick = { showExpandedPlayer = true }
                        )
                    }

                    // Bottom Navigation Bar (Home / Search / Your Library / Create)
                    NavigationBar(
                        containerColor = InkBackground,
                        tonalElevation = 0.dp
                    ) {
                        val items = listOf(
                            NavTab(Screen.Home.route, "Home", Icons.Filled.Home, Icons.Outlined.Home),
                            NavTab(Screen.Search.route, "Search", Icons.Filled.Search, Icons.Outlined.Search),
                            NavTab(Screen.Library.route, "Your Library", Icons.Filled.LibraryMusic, Icons.Outlined.LibraryMusic)
                        )

                        items.forEach { tab ->
                            val selected = currentRoute == tab.route
                            NavigationBarItem(
                                icon = { Icon(if (selected) tab.selectedIcon else tab.icon, contentDescription = tab.label) },
                                label = {
                                    Text(
                                        tab.label,
                                        fontSize = 11.sp,
                                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                                        maxLines = 1
                                    )
                                },
                                selected = selected,
                                onClick = {
                                    if (currentRoute != tab.route) {
                                        navController.navigate(tab.route) {
                                            popUpTo(navController.graph.findStartDestination().id) {
                                                saveState = true
                                            }
                                            launchSingleTop = true
                                            restoreState = true
                                        }
                                    }
                                },
                                colors = NavigationBarItemDefaults.colors(
                                    selectedIconColor = TextPrimary,
                                    selectedTextColor = TextPrimary,
                                    unselectedIconColor = TextSecondary,
                                    unselectedTextColor = TextSecondary,
                                    indicatorColor = Color.Transparent
                                )
                            )
                        }

                        // "Create" is an action, never a destination.
                        NavigationBarItem(
                            icon = { Icon(Icons.Filled.Add, contentDescription = "Create") },
                            label = { Text("Create", fontSize = 11.sp, maxLines = 1) },
                            selected = false,
                            onClick = { showCreatePlaylist = true },
                            colors = NavigationBarItemDefaults.colors(
                                unselectedIconColor = TextSecondary,
                                unselectedTextColor = TextSecondary,
                                indicatorColor = Color.Transparent
                            )
                        )
                    }
                }
            }
        ) { padding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(bottom = padding.calculateBottomPadding())
            ) {
                NavHost(
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
                            onCardClick = onCardClick,
                            onAccountClick = { showAccountSheet = true },
                            onHealthClick = { navController.navigate(Screen.Health.route) }
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
                            onPlaylistClick = { id, name -> navController.navigate(Screen.Playlist.createRoute(id, name)) }
                        )
                    }

                    composable(Screen.Liked.route) {
                        val libraryVm: LibraryViewModel = hiltViewModel()
                        LikedScreen(
                            viewModel = libraryVm,
                            onBack = { navController.popBackStack() },
                            onPlayTrack = onTrackClick
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
                            onPlayTrack = onTrackClick
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
                            onPlayTrack = onTrackClick
                        )
                    }

                    composable(Screen.Health.route) {
                        val healthVm: HealthViewModel = hiltViewModel()
                        HealthScreen(
                            viewModel = healthVm,
                            onBack = { navController.popBackStack() }
                        )
                    }
                }
            }
        }

        // Fullscreen Expanded Player Overlay
        AnimatedVisibility(
            visible = showExpandedPlayer,
            enter = slideInVertically(tween(380, easing = FastOutSlowInEasing), initialOffsetY = { it }) + fadeIn(tween(250)),
            exit = slideOutVertically(tween(320, easing = FastOutSlowInEasing), targetOffsetY = { it }) + fadeOut(tween(250)),
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

        // Queue Overlay
        AnimatedVisibility(
            visible = showQueue,
            enter = slideInVertically(tween(380, easing = FastOutSlowInEasing), initialOffsetY = { it }) + fadeIn(tween(250)),
            exit = slideOutVertically(tween(320, easing = FastOutSlowInEasing), targetOffsetY = { it }) + fadeOut(tween(250)),
            modifier = Modifier.fillMaxSize()
        ) {
            QueueScreen(
                viewModel = playerViewModel,
                onBack = { showQueue = false }
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

        // Account / Auth Bottom Sheet
        if (showAccountSheet) {
            AccountSheet(
                viewModel = accountViewModel,
                onDismiss = { showAccountSheet = false }
            )
        }

        availableUpdate?.let { update ->
            AlertDialog(
                onDismissRequest = { if (!isDownloadingUpdate) availableUpdate = null },
                title = { Text("Update available") },
                text = {
                    Text(
                        updateError ?: "Geekify ${update.version} is ready to download and install."
                    )
                },
                confirmButton = {
                    TextButton(
                        enabled = !isDownloadingUpdate,
                        onClick = {
                            isDownloadingUpdate = true
                            updateError = null
                            updateScope.launch {
                                runCatching {
                                    updateManager.download(update)
                                }.onSuccess { apk ->
                                    isDownloadingUpdate = false
                                    updateManager.install(apk)
                                }.onFailure { error ->
                                    isDownloadingUpdate = false
                                    updateError = "Couldn’t download the update. Please try again."
                                }
                            }
                        }
                    ) { Text(if (isDownloadingUpdate) "Downloading…" else "Update") }
                },
                dismissButton = {
                    TextButton(
                        enabled = !isDownloadingUpdate,
                        onClick = { availableUpdate = null }
                    ) { Text("Later") }
                }
            )
        }
    }
    }
}
