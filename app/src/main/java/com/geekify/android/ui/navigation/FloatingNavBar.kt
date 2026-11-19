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
import androidx.compose.foundation.shape.RoundedCornerShape
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
import com.geekify.android.customization.LocalCustomizationState
import com.geekify.android.customization.NavBarStyle
import com.geekify.android.customization.NavTabId
import com.geekify.android.ui.components.bouncyClickable
import com.geekify.android.ui.glass.GlassStyle
import com.geekify.android.ui.glass.consumeTaps
import com.geekify.android.ui.glass.liquidGlass
import com.geekify.android.ui.glass.rememberReducedMotion
import com.geekify.android.ui.glass.trackPress
import com.geekify.android.ui.player.NowPlayingBar
import com.geekify.android.ui.player.PlayerTransitionState
import com.geekify.android.ui.player.PlayerViewModel
import com.geekify.android.ui.theme.GeekifyIcons
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
    private var inlineLocked = false

    /** Keeps the dock compact on screens that need more unobstructed content space. */
    fun setInlineLocked(locked: Boolean) {
        inlineLocked = locked
        if (locked) inline() else expand()
    }

    fun expand() {
        isInline = false
        accumulatedScroll = 0f
    }

    fun inline() {
        isInline = true
        accumulatedScroll = 0f
    }

    override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
        if (inlineLocked || inlineBehavior == FloatingTabBarInlineBehavior.Never) return Offset.Zero

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
        if (!inlineLocked && inlineBehavior == FloatingTabBarInlineBehavior.ExpandedAtTopOnly) {
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
 * Adaptive Liquid Glass Bottom Navigation Bar dynamically built from the user's ordered,
 * enabled destinations ([NavTabId]).
 *
 * - Renders ONLY the enabled destinations in their configured order.
 * - Leaves zero empty slots, invisible composables, or unexplained gaps when destinations are hidden.
 * - Distributes all remaining destinations evenly across the capsule width.
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
    transitionState: PlayerTransitionState? = null,
    modifier: Modifier = Modifier
) {
    val playerState by playerViewModel.state.collectAsState()
    val hasPlayer = playerState.current != null && !miniPlayerDismissed
    val navCust = LocalCustomizationState.current.navigation

    // Single authoritative ordered collection of enabled destinations
    val orderedTabs: List<NavTabId> = remember(navCust.visibleTabs) {
        navCust.safeVisibleTabs
    }

    val barHeight = if (navCust.style == NavBarStyle.SLIM_PILL) 54.dp else 64.dp
    val isInline = scrollConnection.isInline
    val density = LocalDensity.current
    val navSlidePx = with(density) { 24.dp.toPx() }

    val activeTabId: NavTabId = remember(currentRoute, libraryDetailActive, orderedTabs) {
        val matched = when {
            currentRoute == Screen.Search.route -> NavTabId.SEARCH
            currentRoute == Screen.Library.route || libraryDetailActive -> NavTabId.LIBRARY
            currentRoute == Screen.Home.route -> NavTabId.HOME
            else -> null
        }
        if (matched != null && matched in orderedTabs) {
            matched
        } else {
            orderedTabs.firstOrNull { it != NavTabId.CREATE } ?: NavTabId.HOME
        }
    }

    val activeTabAction: () -> Unit = when (activeTabId) {
        NavTabId.SEARCH -> onSearch
        NavTabId.LIBRARY -> onLibrary
        NavTabId.CREATE -> onCreate
        NavTabId.HOME -> onHome
    }

    val showSeparateInlineSearchAction = activeTabId != NavTabId.SEARCH &&
        NavTabId.SEARCH in orderedTabs &&
        orderedTabs.size > 1

    Box(
        modifier = modifier.fillMaxWidth(),
        contentAlignment = Alignment.BottomCenter
    ) {
        Box(
            modifier = Modifier
                .widthIn(max = 420.dp)
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
                    // INLINE MODE WITH MUSIC: single row [Tab Circle] + [Compact Mini Player] + optional [Search Circle]
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier.graphicsLayer {
                                val p = transitionState?.progress ?: 1f
                                val navAlpha = ((p - 0.35f) / 0.65f).coerceIn(0f, 1f)
                                alpha = navAlpha
                                translationY = (1f - navAlpha) * navSlidePx
                            }
                        ) {
                            ActiveTabCircleButton(
                                activeTab = activeTabId,
                                size = 56.dp,
                                onClick = {
                                    scrollConnection.expand()
                                    activeTabAction()
                                }
                            )
                        }
                        NowPlayingBar(
                            viewModel = playerViewModel,
                            isInline = true,
                            transitionState = transitionState,
                            onClick = onOpenExpandedPlayer,
                            onDismiss = onDismissMiniPlayer,
                            modifier = Modifier.weight(1f)
                        )
                        if (showSeparateInlineSearchAction) {
                            Box(
                                modifier = Modifier.graphicsLayer {
                                    val p = transitionState?.progress ?: 1f
                                    val navAlpha = ((p - 0.35f) / 0.65f).coerceIn(0f, 1f)
                                    alpha = navAlpha
                                    translationY = (1f - navAlpha) * navSlidePx
                                }
                            ) {
                                SearchCircleButton(
                                    isSelected = false,
                                    size = 56.dp,
                                    onClick = onSearch
                                )
                            }
                        }
                    }
                } else if (inline && !playing) {
                    // INLINE MODE WITHOUT MUSIC: centered Active Tab Circle + optional Search Circle
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        ActiveTabCircleButton(
                            activeTab = activeTabId,
                            size = 56.dp,
                            onClick = {
                                scrollConnection.expand()
                                activeTabAction()
                            }
                        )
                        if (showSeparateInlineSearchAction) {
                            Spacer(Modifier.width(14.dp))
                            SearchCircleButton(
                                isSelected = false,
                                size = 56.dp,
                                onClick = onSearch
                            )
                        }
                    }
                } else {
                    // EXPANDED MODE: Mini-player above + Unified Dynamic Navigation Capsule below
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        if (playing) {
                            NowPlayingBar(
                                viewModel = playerViewModel,
                                isInline = false,
                                transitionState = transitionState,
                                onClick = onOpenExpandedPlayer,
                                onDismiss = onDismissMiniPlayer
                            )
                        }
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(barHeight)
                                .graphicsLayer {
                                    val p = transitionState?.progress ?: 1f
                                    val navAlpha = ((p - 0.35f) / 0.65f).coerceIn(0f, 1f)
                                    alpha = navAlpha
                                    translationY = (1f - navAlpha) * navSlidePx
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            MainTabCapsule(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .fillMaxHeight(),
                                orderedTabs = orderedTabs,
                                currentRoute = currentRoute,
                                libraryDetailActive = libraryDetailActive,
                                onHome = onHome,
                                onLibrary = onLibrary,
                                onCreate = onCreate,
                                onSearch = onSearch,
                                showLabels = navCust.showLabels,
                                barHeight = barHeight
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Dynamic Navigation Capsule rendering ONLY the enabled destinations in [orderedTabs]
 * in their configured sequence, distributing them evenly across the bar width with zero gaps.
 */
@Composable
private fun MainTabCapsule(
    orderedTabs: List<NavTabId>,
    currentRoute: String?,
    libraryDetailActive: Boolean,
    onHome: () -> Unit,
    onLibrary: () -> Unit,
    onCreate: () -> Unit,
    onSearch: () -> Unit,
    modifier: Modifier = Modifier,
    showLabels: Boolean = false,
    barHeight: Dp = 64.dp
) {
    val activeTabs = orderedTabs.ifEmpty { listOf(NavTabId.HOME) }

    val selectedIndex = activeTabs.indexOfFirst { tab ->
        when (tab) {
            NavTabId.HOME -> currentRoute == Screen.Home.route
            NavTabId.LIBRARY -> currentRoute == Screen.Library.route || libraryDetailActive
            NavTabId.SEARCH -> currentRoute == Screen.Search.route
            NavTabId.CREATE -> false
        }
    }
    val reduced = rememberReducedMotion()
    val pressed = remember { mutableStateOf(false) }
    val pressAnim by animateFloatAsState(if (pressed.value) 1f else 0f, label = "tabPress")

    BoxWithConstraints(
        modifier = modifier
            .height(barHeight)
            .liquidGlass(CircleShape, GlassStyle.Navigation, pressProgress = { pressAnim })
            .trackPress(pressed)
            .consumeTaps()
    ) {
        val tabCount = activeTabs.size.coerceAtLeast(1)
        val innerWidth = (maxWidth - 8.dp).coerceAtLeast(0.dp)
        val tabWidth = innerWidth / tabCount
        val targetX = if (selectedIndex in 0 until tabCount) tabWidth * selectedIndex else 0.dp
        val indicatorX by animateDpAsState(
            targetValue = targetX,
            animationSpec = if (reduced) snap() else spring(dampingRatio = 0.78f, stiffness = Spring.StiffnessMedium),
            label = "tabIndicatorX"
        )
        val indicatorAlpha by animateFloatAsState(
            targetValue = if (selectedIndex in 0 until tabCount) 1f else 0f,
            label = "tabIndicatorAlpha"
        )

        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(4.dp)
        ) {
            // Sliding glass indicator puck sized dynamically to 1 / tabCount of the bar
            Box(
                modifier = Modifier
                    .offset(x = indicatorX)
                    .width(tabWidth)
                    .fillMaxHeight()
                    .graphicsLayer { alpha = indicatorAlpha }
                    .clip(RoundedCornerShape(28.dp))
                    .background(Color(0xFF2E2E2E).copy(alpha = 0.75f))
                    .border(1.dp, Color.White.copy(alpha = 0.15f), RoundedCornerShape(28.dp))
            )

            Row(
                modifier = Modifier.fillMaxSize(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                activeTabs.forEachIndexed { idx, tab ->
                    key(tab.name) {
                        when (tab) {
                            NavTabId.HOME -> TabItem(
                                selected = selectedIndex == idx,
                                selectedIcon = GeekifyIcons.Home,
                                icon = GeekifyIcons.Home,
                                label = tab.label,
                                showLabel = showLabels,
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxHeight(),
                                onClick = onHome
                            )
                            NavTabId.LIBRARY -> TabItem(
                                selected = selectedIndex == idx,
                                selectedIcon = GeekifyIcons.Library,
                                icon = GeekifyIcons.Library,
                                label = tab.label,
                                showLabel = showLabels,
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxHeight(),
                                onClick = onLibrary
                            )
                            NavTabId.CREATE -> TabItem(
                                selected = false,
                                selectedIcon = GeekifyIcons.Add,
                                icon = GeekifyIcons.Add,
                                label = tab.label,
                                showLabel = showLabels,
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxHeight(),
                                onClick = onCreate
                            )
                            NavTabId.SEARCH -> TabItem(
                                selected = selectedIndex == idx,
                                selectedIcon = GeekifyIcons.Search,
                                icon = GeekifyIcons.Search,
                                label = tab.label,
                                showLabel = showLabels,
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxHeight(),
                                onClick = onSearch
                            )
                        }
                    }
                }
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
    showLabel: Boolean = true,
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
            modifier = Modifier.size(22.dp)
        )
        if (showLabel) {
            Spacer(Modifier.height(3.dp))
            Text(
                text = label,
                color = fg,
                fontSize = 11.sp,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1
            )
        }
    }
}

/** Standalone circular glass Search button used in inline collapsed mode. */
@Composable
private fun SearchCircleButton(
    isSelected: Boolean,
    size: Dp = 64.dp,
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
                    .padding(3.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF2E2E2E).copy(alpha = 0.75f))
                    .border(1.dp, Color.White.copy(alpha = 0.15f), CircleShape)
            )
        }
        Icon(
            imageVector = GeekifyIcons.Search,
            contentDescription = "Search",
            tint = if (isSelected) Lime else Color.White.copy(alpha = 0.90f),
            modifier = Modifier.size(if (size >= 60.dp) 24.dp else 22.dp)
        )
    }
}

/** Active Tab Circle shown on the left in inline mode; tapping activates the displayed tab. */
@Composable
private fun ActiveTabCircleButton(
    activeTab: NavTabId,
    size: Dp = 56.dp,
    onClick: () -> Unit
) {
    val pressed = remember { mutableStateOf(false) }
    val pressAnim by animateFloatAsState(if (pressed.value) 1f else 0f, label = "activePress")

    val (icon, desc) = when (activeTab) {
        NavTabId.LIBRARY -> GeekifyIcons.Library to "Library"
        NavTabId.SEARCH -> GeekifyIcons.Search to "Search"
        NavTabId.CREATE -> GeekifyIcons.Add to "Create"
        NavTabId.HOME -> GeekifyIcons.Home to "Home"
    }

    Box(
        modifier = Modifier
            .size(size)
            .liquidGlass(CircleShape, GlassStyle.Navigation, pressProgress = { pressAnim })
            .trackPress(pressed)
            .bouncyClickable(pressedScale = 0.92f, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(3.dp)
                .clip(CircleShape)
                .background(Color(0xFF2E2E2E).copy(alpha = 0.75f))
                .border(1.dp, Color.White.copy(alpha = 0.15f), CircleShape)
        )
        Icon(
            imageVector = icon,
            contentDescription = desc,
            tint = Lime,
            modifier = Modifier.size(24.dp)
        )
    }
}
