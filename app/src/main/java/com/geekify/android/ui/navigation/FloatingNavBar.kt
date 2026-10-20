package com.geekify.android.ui.navigation

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.LibraryMusic
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.geekify.android.ui.components.bouncyClickable
import com.geekify.android.ui.glass.GlassStyle
import com.geekify.android.ui.glass.consumeTaps
import com.geekify.android.ui.glass.liquidGlass
import com.geekify.android.ui.glass.rememberReducedMotion
import com.geekify.android.ui.glass.trackPress
import com.geekify.android.ui.player.NowPlayingBar
import com.geekify.android.ui.player.PlayerViewModel
import com.geekify.android.ui.theme.Lime
import kotlin.math.abs

/** Controls when the bottom bar transitions to inline state. */
enum class FloatingTabBarInlineBehavior {
    Never,
    OnScrollDown,
    OnScrollUp,
    ExpandedAtTopOnly
}

@Composable
fun rememberFloatingTabBarScrollConnection(
    initialIsInline: Boolean = false,
    scrollThreshold: Dp = 45.dp,
    expandThreshold: Dp = 12.dp,
    inlineBehavior: FloatingTabBarInlineBehavior = FloatingTabBarInlineBehavior.OnScrollDown
): FloatingTabBarScrollConnection = with(LocalDensity.current) {
    val scrollThresholdPx = scrollThreshold.toPx()
    val expandThresholdPx = expandThreshold.toPx()
    remember(scrollThresholdPx, expandThresholdPx, inlineBehavior, initialIsInline) {
        FloatingTabBarScrollConnection(
            initialIsInline = initialIsInline,
            scrollThresholdPx = scrollThresholdPx,
            expandThresholdPx = expandThresholdPx,
            inlineBehavior = inlineBehavior,
        )
    }
}

class FloatingTabBarScrollConnection(
    initialIsInline: Boolean = false,
    private val scrollThresholdPx: Float,
    private val expandThresholdPx: Float = scrollThresholdPx,
    private val inlineBehavior: FloatingTabBarInlineBehavior = FloatingTabBarInlineBehavior.OnScrollDown
) : NestedScrollConnection {
    var isInline by mutableStateOf(initialIsInline)
        private set

    private var accumulatedScroll = 0f

    fun expand() {
        isInline = false
        accumulatedScroll = 0f
    }

    fun inline() {
        isInline = true
        accumulatedScroll = 0f
    }

    override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
        if (inlineBehavior == FloatingTabBarInlineBehavior.Never) return Offset.Zero

        val scrollDelta = available.y
        if ((accumulatedScroll > 0 && scrollDelta < 0) || (accumulatedScroll < 0 && scrollDelta > 0)) {
            accumulatedScroll = 0f
        }
        accumulatedScroll += scrollDelta

        when (inlineBehavior) {
            FloatingTabBarInlineBehavior.OnScrollDown -> {
                if (accumulatedScroll <= -scrollThresholdPx && !isInline) {
                    isInline = true
                    accumulatedScroll = 0f
                } else if (accumulatedScroll >= expandThresholdPx && isInline) {
                    isInline = false
                    accumulatedScroll = 0f
                }
            }
            FloatingTabBarInlineBehavior.OnScrollUp -> {
                if (accumulatedScroll >= scrollThresholdPx && !isInline) {
                    isInline = true
                    accumulatedScroll = 0f
                } else if (accumulatedScroll <= -scrollThresholdPx && isInline) {
                    isInline = false
                    accumulatedScroll = 0f
                }
            }
            FloatingTabBarInlineBehavior.ExpandedAtTopOnly -> {}
            FloatingTabBarInlineBehavior.Never -> {}
        }
        return Offset.Zero
    }

    override fun onPostScroll(
        consumed: Offset,
        available: Offset,
        source: NestedScrollSource
    ): Offset {
        if (inlineBehavior == FloatingTabBarInlineBehavior.ExpandedAtTopOnly) {
            if (available.y > 0.5f) {
                if (isInline) {
                    isInline = false
                    accumulatedScroll = 0f
                }
            } else if (abs(consumed.y) > 0.5f && !isInline) {
                isInline = true
                accumulatedScroll = 0f
            }
        }
        return Offset.Zero
    }
}

/**
 * Adaptive Liquid Glass Bottom Navigation Bar adapted from Convx:
 *
 * - EXPANDED (at top of page / initial):
 *   - Mini player capsule on top (if song active)
 *   - Row below: Main Tab Capsule (Home, Library, Create) + standalone circular Search button
 *
 * - INLINE (when scrolling down):
 *   - Single row: [Active Tab Circle] + [Compact Mini Player] + [Search Circle]
 *   - If no song active: [Active Tab Circle] + [Search Circle] centered
 */
@Composable
fun AdaptiveBottomBar(
    scrollConnection: FloatingTabBarScrollConnection,
    playerViewModel: PlayerViewModel,
    currentRoute: String?,
    libraryDetailActive: Boolean,
    miniPlayerDismissed: Boolean,
    onDismissMiniPlayer: () -> Unit,
    onOpenExpandedPlayer: () -> Unit,
    onHome: () -> Unit,
    onSearch: () -> Unit,
    onLibrary: () -> Unit,
    onCreate: () -> Unit,
    modifier: Modifier = Modifier
) {
    val playerState by playerViewModel.state.collectAsState()
    val hasPlayer = playerState.current != null && !miniPlayerDismissed
    val isInline = scrollConnection.isInline

    Box(
        modifier = modifier
            .fillMaxWidth()
            .consumeTaps()
    ) {
        AnimatedContent(
            targetState = isInline to hasPlayer,
            transitionSpec = {
                (fadeIn(spring(stiffness = Spring.StiffnessMediumLow)) +
                 scaleIn(initialScale = 0.94f, animationSpec = spring(stiffness = Spring.StiffnessMediumLow)))
                    .togetherWith(
                        fadeOut(spring(stiffness = Spring.StiffnessMediumLow)) +
                        scaleOut(targetScale = 0.94f, animationSpec = spring(stiffness = Spring.StiffnessMediumLow))
                    )
            },
            label = "adaptiveNavBarAnim"
        ) { (inline, playing) ->
            if (inline && playing) {
                // INLINE MODE WITH MUSIC: single row [Tab Circle] + [Compact Mini Player] + [Search Circle]
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    ActiveTabCircleButton(
                        currentRoute = currentRoute,
                        libraryDetailActive = libraryDetailActive,
                        onClick = { scrollConnection.expand() }
                    )
                    NowPlayingBar(
                        viewModel = playerViewModel,
                        isInline = true,
                        onClick = onOpenExpandedPlayer,
                        onDismiss = onDismissMiniPlayer,
                        modifier = Modifier.weight(1f)
                    )
                    SearchCircleButton(
                        isSelected = currentRoute == Screen.Search.route,
                        size = 52.dp,
                        onClick = onSearch
                    )
                }
            } else if (inline && !playing) {
                // INLINE MODE WITHOUT MUSIC: centered Active Tab Circle + Search Circle
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    ActiveTabCircleButton(
                        currentRoute = currentRoute,
                        libraryDetailActive = libraryDetailActive,
                        onClick = { scrollConnection.expand() }
                    )
                    Spacer(Modifier.width(14.dp))
                    SearchCircleButton(
                        isSelected = currentRoute == Screen.Search.route,
                        size = 52.dp,
                        onClick = onSearch
                    )
                }
            } else {
                // EXPANDED MODE: full controls
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    if (playing) {
                        NowPlayingBar(
                            viewModel = playerViewModel,
                            isInline = false,
                            onClick = onOpenExpandedPlayer,
                            onDismiss = onDismissMiniPlayer
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        MainTabCapsule(
                            modifier = Modifier.weight(1f),
                            currentRoute = currentRoute,
                            libraryDetailActive = libraryDetailActive,
                            onHome = onHome,
                            onLibrary = onLibrary,
                            onCreate = onCreate
                        )
                        SearchCircleButton(
                            isSelected = currentRoute == Screen.Search.route,
                            size = 54.dp,
                            onClick = onSearch
                        )
                    }
                }
            }
        }
    }
}

/** Main Tab Capsule containing Home, Library, Create with sliding glass indicator puck. */
@Composable
private fun MainTabCapsule(
    currentRoute: String?,
    libraryDetailActive: Boolean,
    onHome: () -> Unit,
    onLibrary: () -> Unit,
    onCreate: () -> Unit,
    modifier: Modifier = Modifier
) {
    val selectedIndex = when {
        currentRoute == Screen.Home.route -> 0
        currentRoute == Screen.Library.route || libraryDetailActive -> 1
        else -> -1
    }
    val reduced = rememberReducedMotion()
    val pressed = remember { mutableStateOf(false) }
    val pressAnim by animateFloatAsState(if (pressed.value) 1f else 0f, label = "tabPress")

    BoxWithConstraints(
        modifier = modifier
            .height(54.dp)
            .liquidGlass(CircleShape, GlassStyle.Navigation, pressProgress = { pressAnim })
            .trackPress(pressed)
            .consumeTaps()
    ) {
        val tabCount = 3
        val tabWidth = (maxWidth - 8.dp) / tabCount
        val targetX = if (selectedIndex >= 0) tabWidth * selectedIndex else 0.dp
        val indicatorX by animateDpAsState(
            targetValue = targetX,
            animationSpec = if (reduced) snap() else spring(dampingRatio = 0.72f, stiffness = Spring.StiffnessMedium),
            label = "tabIndicatorX"
        )
        val indicatorAlpha by animateFloatAsState(
            targetValue = if (selectedIndex >= 0) 1f else 0f,
            label = "tabIndicatorAlpha"
        )

        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(4.dp)
        ) {
            // Sliding glass indicator puck
            Box(
                modifier = Modifier
                    .offset(x = indicatorX)
                    .width(tabWidth)
                    .fillMaxHeight()
                    .graphicsLayer { alpha = indicatorAlpha }
                    .clip(CircleShape)
                    .background(Color(0xFF2E2E2E).copy(alpha = 0.70f))
                    .border(1.dp, Color.White.copy(alpha = 0.15f), CircleShape)
            )

            Row(
                modifier = Modifier.fillMaxSize(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TabItem(
                    selected = selectedIndex == 0,
                    selectedIcon = Icons.Filled.Home,
                    icon = Icons.Outlined.Home,
                    label = "Home",
                    modifier = Modifier.weight(1f),
                    onClick = onHome
                )
                TabItem(
                    selected = selectedIndex == 1,
                    selectedIcon = Icons.Filled.LibraryMusic,
                    icon = Icons.Outlined.LibraryMusic,
                    label = "Library",
                    modifier = Modifier.weight(1f),
                    onClick = onLibrary
                )
                TabItem(
                    selected = false,
                    selectedIcon = Icons.Filled.Add,
                    icon = Icons.Outlined.Add,
                    label = "Create",
                    modifier = Modifier.weight(1f),
                    onClick = onCreate
                )
            }
        }
    }
}

@Composable
private fun TabItem(
    selected: Boolean,
    selectedIcon: ImageVector,
    icon: ImageVector,
    label: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val fg by animateColorAsState(
        targetValue = if (selected) Lime else Color.White.copy(alpha = 0.85f),
        animationSpec = tween(220),
        label = "tabFg"
    )

    Column(
        modifier = modifier
            .fillMaxHeight()
            .clip(CircleShape)
            .bouncyClickable(pressedScale = 0.92f, onClick = onClick),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            imageVector = if (selected) selectedIcon else icon,
            contentDescription = label,
            tint = fg,
            modifier = Modifier.size(20.dp)
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = label,
            color = fg,
            fontSize = 11.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1
        )
    }
}

/** Standalone circular glass Search button. */
@Composable
private fun SearchCircleButton(
    isSelected: Boolean,
    size: Dp = 54.dp,
    onClick: () -> Unit
) {
    val pressed = remember { mutableStateOf(false) }
    val pressAnim by animateFloatAsState(if (pressed.value) 1f else 0f, label = "searchPress")

    Box(
        modifier = Modifier
            .size(size)
            .liquidGlass(CircleShape, GlassStyle.Navigation, pressProgress = { pressAnim })
            .trackPress(pressed)
            .bouncyClickable(pressedScale = 0.92f, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        if (isSelected) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(4.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF2E2E2E).copy(alpha = 0.70f))
                    .border(1.dp, Color.White.copy(alpha = 0.15f), CircleShape)
            )
        }
        Icon(
            imageVector = if (isSelected) Icons.Filled.Search else Icons.Outlined.Search,
            contentDescription = "Search",
            tint = if (isSelected) Lime else Color.White.copy(alpha = 0.90f),
            modifier = Modifier.size(22.dp)
        )
    }
}

/** Active Tab Circle shown on the left in inline mode; tapping re-expands the bar. */
@Composable
private fun ActiveTabCircleButton(
    currentRoute: String?,
    libraryDetailActive: Boolean,
    onClick: () -> Unit
) {
    val pressed = remember { mutableStateOf(false) }
    val pressAnim by animateFloatAsState(if (pressed.value) 1f else 0f, label = "activePress")

    val (icon, desc) = when {
        currentRoute == Screen.Library.route || libraryDetailActive -> Icons.Filled.LibraryMusic to "Library"
        currentRoute == Screen.Search.route -> Icons.Filled.Search to "Search"
        else -> Icons.Filled.Home to "Home"
    }

    Box(
        modifier = Modifier
            .size(52.dp)
            .liquidGlass(CircleShape, GlassStyle.Navigation, pressProgress = { pressAnim })
            .trackPress(pressed)
            .bouncyClickable(pressedScale = 0.92f, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(4.dp)
                .clip(CircleShape)
                .background(Color(0xFF2E2E2E).copy(alpha = 0.70f))
                .border(1.dp, Color.White.copy(alpha = 0.15f), CircleShape)
        )
        Icon(
            imageVector = icon,
            contentDescription = desc,
            tint = Lime,
            modifier = Modifier.size(22.dp)
        )
    }
}
