package com.geekify.android.customization

import androidx.activity.compose.BackHandler
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.ui.graphics.graphicsLayer
import com.geekify.android.ui.navigation.GeekifyNavMotion
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.launch
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.geekify.android.audio.AudioSettings
import com.geekify.android.settings.AppSettingsState
import com.geekify.android.settings.AudioQualityPreference
import com.geekify.android.ui.ascii.AsciiCatalog
import com.geekify.android.ui.components.*
import com.geekify.android.ui.glass.GlassStyle
import com.geekify.android.ui.glass.glassBackdropSource
import com.geekify.android.ui.glass.liquidGlass
import com.geekify.android.ui.glass.rememberGlassBackdrop
import com.geekify.android.ui.navigation.FloatingTabBarInlineBehavior
import com.geekify.android.ui.theme.*
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import org.koin.androidx.compose.koinViewModel

/**
 * Centralized Geekify Personalization & Customization Hub.
 * Provides live previews, category navigation across all 13 personalization domains,
 * per-category reset, and a global reset confirmation dialog.
 */
@Composable
fun CustomizationSettingsScreen(
    onBack: () -> Unit,
    onOpenAppIconPicker: () -> Unit = {},
    @Suppress("UNUSED_PARAMETER") onOpenEqualizer: () -> Unit = {},
    initialCategory: CustomizationCategory? = null,
    viewModel: CustomizationViewModel = koinViewModel()
) {
    val state by viewModel.appSettings.collectAsState()
    val audioState by viewModel.audioSettings.collectAsState()
    val toastMessage by viewModel.toastMessage.collectAsState()
    val bottomInset = LocalBottomInset.current

    val isDirectCategoryLaunch = initialCategory != null
    var activeCategory by rememberSaveable(initialCategory) { mutableStateOf(initialCategory) }
    var showFontsManager by rememberSaveable { mutableStateOf(false) }
    var showResetAllDialog by rememberSaveable { mutableStateOf(false) }
    val rootScroll = rememberSaveable(saver = ScrollState.Saver) { ScrollState(0) }

    val hasInScreenBack = showFontsManager || (!isDirectCategoryLaunch && activeCategory != null)
    val handleCategoryBack: () -> Unit = {
        if (isDirectCategoryLaunch) {
            onBack()
        } else {
            activeCategory = null
        }
    }

    // Responsive gesture-driven predictive back for nested in-screen subpages, aligned with GeekifyNavMotion:
    // - Direct category shortcuts launched from Settings let NavController handle Back directly -> Settings.
    // - Nested categories opened from the Appearance hub or Fonts Manager pop cleanly within this screen.
    val backCancelScope = rememberCoroutineScope()
    val predictiveBackAnim = remember { Animatable(0f) }
    var predictiveBackEdge by remember { mutableIntStateOf(GeekifyNavMotion.SWIPE_EDGE_LEFT) }
    var lastBackWasGesture by remember { mutableStateOf(false) }

    PredictiveBackHandler(enabled = hasInScreenBack && !showResetAllDialog) { backEvents ->
        try {
            backEvents.collect { event ->
                predictiveBackEdge = event.swipeEdge
                predictiveBackAnim.snapTo(event.progress.coerceIn(0f, 1f))
            }
            lastBackWasGesture = true
            predictiveBackAnim.snapTo(0f)
            if (showFontsManager) {
                showFontsManager = false
            } else if (!isDirectCategoryLaunch) {
                activeCategory = null
            }
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

    LaunchedEffect(activeCategory, showFontsManager) {
        if (activeCategory != CustomizationCategory.TYPOGRAPHY_DENSITY && showFontsManager) {
            showFontsManager = false
        }
        if (activeCategory != null || showFontsManager) {
            lastBackWasGesture = false
            predictiveBackAnim.snapTo(0f)
        }
    }

    LaunchedEffect(toastMessage) {
        if (toastMessage != null) {
            delay(3_000L)
            viewModel.clearToast()
        }
    }

    if (showResetAllDialog) {
        AlertDialog(
            onDismissRequest = { showResetAllDialog = false },
            containerColor = InkPanel,
            titleContentColor = TextPrimary,
            textContentColor = TextSecondary,
            title = {
                Text(
                    "Reset All Appearance Settings?",
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp
                )
            },
            text = {
                Text(
                    "This restores all themes, Liquid Glass parameters, ASCII animations, Home layout, Player layout, Mini-Player, Typography, Lyrics, and Widget styles to Geekify defaults. Your library, playlists, downloads, and account will not be affected."
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.resetAllCustomization()
                        showResetAllDialog = false
                    }
                ) {
                    Text("Reset All", color = IosPalette.systemRed, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showResetAllDialog = false }) {
                    Text("Cancel", color = Lime)
                }
            }
        )
    }

    AnimatedContent(
        targetState = Pair(activeCategory, showFontsManager),
        transitionSpec = {
            val initialDepth = (if (initialState.first != null) 1 else 0) + (if (initialState.second) 1 else 0)
            val targetDepth = (if (targetState.first != null) 1 else 0) + (if (targetState.second) 1 else 0)
            GeekifyNavMotion.subPageTransition(
                isForward = targetDepth >= initialDepth,
                lastBackWasGesture = lastBackWasGesture
            )
        },
        label = "customizationCategoryNav"
    ) { (category, fontsManagerOpen) ->
        if (category == null) {
            IosSettingsPageScaffold(
                title = "Appearance",
                scrollState = rootScroll,
                onBack = onBack,
                bottomPadding = bottomInset + 40.dp,
                minimalStyle = true
            ) {
                AnimatedVisibility(visible = toastMessage != null) {
                    toastMessage?.let { msg ->
                        CustomizationToastBanner(message = msg, onDismiss = viewModel::clearToast)
                    }
                }

                IosSettingsSection(
                    title = "Visual Identity & Atmosphere",
                    footer = "Customize surface darkness, accent palettes, Liquid Glass refraction, and procedural ASCII backgrounds."
                ) {
                    IosSettingsRow(
                        title = CustomizationCategory.THEME_COLORS.title,
                        subtitle = CustomizationCategory.THEME_COLORS.subtitle,
                        value = "${state.customization.theme.themeVariant.label} · ${state.customization.theme.palettePreset.label}",
                        icon = GeekifyIcons.Palette,
                        tileColor = IosPalette.systemPurple,
                        onClick = { activeCategory = CustomizationCategory.THEME_COLORS }
                    )
                    IosSettingsDivider()
                    IosSettingsRow(
                        title = CustomizationCategory.LIQUID_GLASS.title,
                        subtitle = CustomizationCategory.LIQUID_GLASS.subtitle,
                        value = if (state.glass.enabled) state.customization.glassPreset.label else "Off",
                        icon = GeekifyIcons.AutoAwesome,
                        tileColor = IosPalette.systemCyan,
                        onClick = { activeCategory = CustomizationCategory.LIQUID_GLASS }
                    )
                    IosSettingsDivider()
                    IosSettingsRow(
                        title = CustomizationCategory.ASCII_BACKGROUND.title,
                        subtitle = CustomizationCategory.ASCII_BACKGROUND.subtitle,
                        value = if (state.ascii.enabled) AsciiCatalog.byId(state.ascii.animalId).name else "Off",
                        icon = GeekifyIcons.Tune,
                        tileColor = IosPalette.systemIndigo,
                        onClick = { activeCategory = CustomizationCategory.ASCII_BACKGROUND }
                    )
                }

                Spacer(Modifier.height(22.dp))

                IosSettingsSection(
                    title = "Player & Interaction",
                    footer = "Switch between 6 player layouts, configure the mini-player pill, and customize gestures."
                ) {
                    IosSettingsRow(
                        title = CustomizationCategory.PLAYER_APPEARANCE.title,
                        subtitle = CustomizationCategory.PLAYER_APPEARANCE.subtitle,
                        value = state.customization.player.layoutStyle.label,
                        icon = GeekifyIcons.Album,
                        tileColor = IosPalette.systemPink,
                        onClick = { activeCategory = CustomizationCategory.PLAYER_APPEARANCE }
                    )
                    IosSettingsDivider()
                    IosSettingsRow(
                        title = CustomizationCategory.MINI_PLAYER.title,
                        subtitle = CustomizationCategory.MINI_PLAYER.subtitle,
                        value = state.customization.miniPlayer.style.label,
                        icon = GeekifyIcons.PlayCircleFilled,
                        tileColor = IosPalette.systemGreen,
                        onClick = { activeCategory = CustomizationCategory.MINI_PLAYER }
                    )
                    IosSettingsDivider()
                    IosSettingsRow(
                        title = CustomizationCategory.GESTURES.title,
                        subtitle = CustomizationCategory.GESTURES.subtitle,
                        value = if (state.customization.gestures.swipeArtworkToSkip) "Active" else "Custom",
                        icon = GeekifyIcons.Tune,
                        tileColor = IosPalette.systemYellow,
                        onClick = { activeCategory = CustomizationCategory.GESTURES }
                    )
                }

                Spacer(Modifier.height(22.dp))

                IosSettingsSection(
                    title = "Widgets & Launcher",
                    footer = "Configure home screen widgets and launcher icon."
                ) {
                    IosSettingsRow(
                        title = CustomizationCategory.WIDGETS.title,
                        subtitle = CustomizationCategory.WIDGETS.subtitle,
                        value = state.customization.widgets.backgroundStyle.label,
                        icon = GeekifyIcons.ViewDay,
                        tileColor = IosPalette.systemCyan,
                        onClick = { activeCategory = CustomizationCategory.WIDGETS }
                    )
                    IosSettingsDivider()
                    IosSettingsRow(
                        title = CustomizationCategory.APP_ICONS.title,
                        subtitle = CustomizationCategory.APP_ICONS.subtitle,
                        icon = GeekifyIcons.Apps,
                        tileColor = IosPalette.systemBlue,
                        onClick = onOpenAppIconPicker
                    )
                }

                Spacer(Modifier.height(22.dp))

                IosSettingsSection(
                    title = "Reset & Defaults",
                    footer = "Restore all visual, layout, and player appearance settings to their original Geekify defaults."
                ) {
                    IosSettingsRow(
                        title = "Reset All Appearance Settings",
                        subtitle = "Restore default theme, glass, layout, player, and typography",
                        icon = GeekifyIcons.RestartAlt,
                        tileColor = IosPalette.systemRed,
                        destructive = true,
                        showChevron = false,
                        onClick = { showResetAllDialog = true }
                    )
                }
            }
        } else if (category == CustomizationCategory.TYPOGRAPHY_DENSITY && fontsManagerOpen) {
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
                FontsManagerScreen(
                    state = state,
                    viewModel = viewModel,
                    onBack = { showFontsManager = false },
                    bottomPadding = bottomInset + 40.dp
                )
            }
        } else {
            val detailScroll = rememberSaveable(
                "cust_cat_${category.name}",
                saver = ScrollState.Saver
            ) { ScrollState(0) }

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
                title = category.title,
                scrollState = detailScroll,
                onBack = handleCategoryBack,
                bottomPadding = bottomInset + 40.dp,
                minimalStyle = true
            ) {
                AnimatedVisibility(visible = toastMessage != null) {
                    toastMessage?.let { msg ->
                        CustomizationToastBanner(message = msg, onDismiss = viewModel::clearToast)
                    }
                }

                when (category) {
                    CustomizationCategory.THEME_COLORS -> ThemeColorsCategoryPage(state, viewModel)
                    CustomizationCategory.LIQUID_GLASS -> LiquidGlassCategoryPage(state, viewModel)
                    CustomizationCategory.ASCII_BACKGROUND -> AsciiBackgroundCategoryPage(state, viewModel)
                    CustomizationCategory.HOME_SCREEN -> HomeScreenCategoryPage(state, viewModel)
                    CustomizationCategory.NAVIGATION -> NavigationCategoryPage(state, viewModel)
                    CustomizationCategory.PLAYER_APPEARANCE -> PlayerAppearanceCategoryPage(state, viewModel)
                    CustomizationCategory.MINI_PLAYER -> MiniPlayerCategoryPage(state, viewModel)
                    CustomizationCategory.GESTURES -> GesturesCategoryPage(state, viewModel)
                    CustomizationCategory.TYPOGRAPHY_DENSITY -> TypographyCategoryPage(
                        state = state,
                        viewModel = viewModel,
                        onOpenFontsManager = { showFontsManager = true }
                    )
                    CustomizationCategory.LYRICS_APPEARANCE -> LyricsCategoryPage(state, viewModel)
                    CustomizationCategory.PLAYBACK_PREFERENCES -> PlaybackPreferencesCategoryPage(
                        state = state,
                        audio = audioState,
                        viewModel = viewModel
                    )
                    CustomizationCategory.WIDGETS -> WidgetsCategoryPage(state, viewModel)
                    CustomizationCategory.APP_ICONS -> {
                        LaunchedEffect(Unit) {
                            activeCategory = null
                            onOpenAppIconPicker()
                        }
                    }
                }

                Spacer(Modifier.height(24.dp))

                IosSettingsSection {
                    IosSettingsRow(
                        title = "Reset ${category.title} to Defaults",
                        icon = GeekifyIcons.RestartAlt,
                        tileColor = IosPalette.systemRed,
                        destructive = true,
                        showChevron = false,
                        onClick = { viewModel.resetCategory(category) }
                    )
                }
            }
            }
        }
    }
}

@Composable
private fun CustomizationToastBanner(
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

@Composable
private fun LiveStudioHeroCard(state: AppSettingsState) {
    val theme = state.customization.theme
    val player = state.customization.player
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(
                Brush.linearGradient(
                    colors = listOf(
                        theme.resolvedPrimary.copy(alpha = 0.22f),
                        theme.resolvedPanel,
                        theme.resolvedSecondary.copy(alpha = 0.18f)
                    )
                )
            )
            .border(1.dp, theme.resolvedPrimary.copy(alpha = 0.35f), RoundedCornerShape(24.dp))
            .padding(20.dp)
    ) {
        Column {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "LIVE APPEARANCE PREVIEW",
                        color = Lime,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.2.sp
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "${theme.themeVariant.label} · ${theme.palettePreset.label}",
                        color = TextPrimary,
                        fontSize = 19.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "${player.layoutStyle.label} · ${state.customization.glassPreset.label} Glass",
                        color = TextSecondary,
                        fontSize = 13.sp
                    )
                }
                Box(
                    modifier = Modifier
                        .size(46.dp)
                        .clip(CircleShape)
                        .background(Lime),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = GeekifyIcons.AutoAwesome,
                        contentDescription = null,
                        tint = OnAccent,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }

            Spacer(Modifier.height(14.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StudioChip(label = state.customization.typography.fontChoice.label)
                StudioChip(label = state.customization.miniPlayer.style.label)
                StudioChip(label = "${state.customization.navigation.safeVisibleTabs.size} Tabs")
            }
        }
    }
}

@Composable
private fun StudioChip(label: String) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(Color.White.copy(alpha = 0.08f))
            .border(0.5.dp, Color.White.copy(alpha = 0.14f), RoundedCornerShape(50))
            .padding(horizontal = 10.dp, vertical = 5.dp)
    ) {
        Text(
            text = label,
            color = TextPrimary,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

// ============================================================================================
// 1. THEME & COLORS PAGE
// ============================================================================================
@Composable
private fun ThemeColorsCategoryPage(
    state: AppSettingsState,
    viewModel: CustomizationViewModel
) {
    val theme = state.customization.theme

    // Live Theme Preview Card
    IosSettingsSection(title = "Live Theme & Palette Preview") {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(theme.resolvedBackground)
                .padding(18.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(18.dp))
                    .background(theme.resolvedPanel)
                    .border(1.dp, theme.resolvedPrimary.copy(alpha = 0.4f), RoundedCornerShape(18.dp))
                    .padding(16.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(Brush.linearGradient(listOf(theme.resolvedPrimary, theme.resolvedSecondary))),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(GeekifyIcons.MusicNote, contentDescription = null, tint = theme.resolvedOnAccent)
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Midnight Synthwave", color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                        Text("Geekify Studio Session", color = TextSecondary, fontSize = 12.sp)
                    }
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .background(theme.resolvedPrimary)
                            .padding(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Text("PLAYING", color = theme.resolvedOnAccent, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }

    Spacer(Modifier.height(22.dp))

    IosSettingsSection(
        title = "Theme Mode",
        footer = "Automatic follows your Android system dark/light theme. Pure Black turns off OLED pixels."
    ) {
        ThemeMode.entries.forEachIndexed { idx, mode ->
            if (idx > 0) IosSettingsDivider(insetStart = 16.dp)
            IosSettingsRow(
                title = mode.label,
                subtitle = mode.subtitle,
                value = if (theme.themeMode == mode) "Active" else null,
                selected = theme.themeMode == mode,
                showChevron = false,
                onClick = { viewModel.setThemeMode(mode) }
            )
        }
    }

    if (theme.themeMode == ThemeMode.DARK || theme.themeMode == ThemeMode.AUTO) {
        Spacer(Modifier.height(22.dp))

        IosSettingsSection(
            title = "Dark Surface Variant",
            footer = "Choose the dark background tone used in Dark mode."
        ) {
            DarkThemeVariant.entries.forEachIndexed { idx, variant ->
                if (idx > 0) IosSettingsDivider(insetStart = 16.dp)
                IosSettingsRow(
                    title = variant.label,
                    subtitle = variant.subtitle,
                    value = if (theme.themeVariant == variant) "Active" else null,
                    selected = theme.themeVariant == variant,
                    showChevron = false,
                    onClick = { viewModel.setThemeVariant(variant) }
                )
            }
        }
    }

    Spacer(Modifier.height(22.dp))

    IosSettingsSection(
        title = "Palette Style",
        footer = "Shapes how primary and secondary colors are harmonized across Geekify."
    ) {
        ThemePaletteStyle.entries.forEachIndexed { idx, style ->
            if (idx > 0) IosSettingsDivider(insetStart = 16.dp)
            IosSettingsRow(
                title = style.label,
                subtitle = style.subtitle,
                value = if (theme.paletteStyle == style) "Active" else null,
                selected = theme.paletteStyle == style,
                showChevron = false,
                onClick = { viewModel.setPaletteStyle(style) }
            )
        }
    }

    Spacer(Modifier.height(22.dp))

    IosSettingsSection(
        title = "Accent Color Palette",
        footer = "Updates buttons, active sliders, badges, and glowing indicators across Geekify immediately."
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            AccentPalettePreset.entries.filter { it != AccentPalettePreset.CUSTOM }.forEach { preset ->
                val isSelected = theme.palettePreset == preset
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .width(76.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(if (isSelected) Color.White.copy(alpha = 0.10f) else Color.Transparent)
                        .border(
                            width = if (isSelected) 1.5.dp else 0.5.dp,
                            color = if (isSelected) Color(preset.primaryArgb) else Color.White.copy(alpha = 0.12f),
                            shape = RoundedCornerShape(16.dp)
                        )
                        .clickable { viewModel.setPalettePreset(preset) }
                        .padding(vertical = 10.dp, horizontal = 6.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(
                                Brush.linearGradient(
                                    listOf(Color(preset.primaryArgb), Color(preset.secondaryArgb))
                                )
                            )
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = preset.label,
                        color = TextPrimary,
                        fontSize = 11.sp,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }

    Spacer(Modifier.height(22.dp))

    val customColorChoices = remember {
        listOf(
            "Electric Lime" to 0xFFC8E65AL,
            "Hyper Magenta" to 0xFFFF44A8L,
            "Laser Blue" to 0xFF2979FFL,
            "Solar Orange" to 0xFFFF6D00L,
            "Acid Green" to 0xFF00E676L,
            "Ice Cyan" to 0xFF00E5FFL,
            "Crimson Pulse" to 0xFFFF1744L,
            "Lavender Mist" to 0xFFD5A6FFL
        )
    }

    IosSettingsSection(
        title = "Custom Primary Accent Swatch",
        footer = "Tap any custom swatch to switch to a user-defined primary accent color."
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            customColorChoices.forEach { (name, argb) ->
                val selected = theme.palettePreset == AccentPalettePreset.CUSTOM && theme.customPrimaryArgb == argb
                Box(
                    modifier = Modifier
                        .size(42.dp)
                        .clip(CircleShape)
                        .background(Color(argb))
                        .border(
                            width = if (selected) 3.dp else 1.dp,
                            color = if (selected) Color.White else Color.Black.copy(alpha = 0.3f),
                            shape = CircleShape
                        )
                        .clickable { viewModel.setCustomPrimaryColor(argb) }
                )
            }
        }
    }

    Spacer(Modifier.height(22.dp))

    IosSettingsSection(title = "Dynamic Colors & Enhancements") {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            IosSettingsSwitchRow(
                title = "Material You Dynamic Colors",
                subtitle = "Adopt colors from your Android system wallpaper",
                checked = theme.materialYouEnabled,
                onCheckedChange = viewModel::setMaterialYouEnabled,
                icon = GeekifyIcons.AutoAwesome,
                tileColor = IosPalette.systemBlue
            )
            IosSettingsDivider()
        }
        IosSettingsSwitchRow(
            title = "Dynamic Now Playing Artwork Accent",
            subtitle = "Extract dominant album artwork colors for the expanded player ambient glow",
            checked = theme.dynamicPlayerAccent,
            onCheckedChange = viewModel::setDynamicPlayerAccent,
            icon = GeekifyIcons.Palette,
            tileColor = IosPalette.systemPink
        )
        IosSettingsDivider()
        IosSettingsSwitchRow(
            title = "UI Visual Enhancements",
            subtitle = "Enhanced depth rims, subtle accent gradients, and expressive highlights",
            checked = theme.uiEnhancements,
            onCheckedChange = viewModel::setUiEnhancements,
            icon = GeekifyIcons.Tune,
            tileColor = IosPalette.systemPurple
        )
    }
}

// ============================================================================================
// 2. LIQUID GLASS PAGE
// ============================================================================================
@Composable
private fun LiquidGlassCategoryPage(
    state: AppSettingsState,
    viewModel: CustomizationViewModel
) {
    val glass = state.glass
    val activePreset = state.customization.glassPreset
    val previewShape = RoundedCornerShape(glass.cornerRadiusDp.coerceIn(8f, 36f).dp)
    val tintSwatches = remember {
        listOf(
            0xFF171717L, 0xFF1E293BL, 0xFF2E1065L, 0xFF064E3BL,
            0xFF7C2D12L, 0xFF831843L, 0xFFF8FAFCL, 0xFFC8E65AL
        )
    }

    // Live Acrylic & Liquid Glass Preview rendered using the real Modifier.liquidGlass pipeline
    IosSettingsSection(
        title = "Live Acrylic & Glass Preview",
        footer = "Uses Geekify's actual Liquid Glass renderer so every tint, blur, border, and grain change reflects live."
    ) {
        val previewBackdrop = rememberGlassBackdrop(baseBg = InkBackground)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(156.dp)
                .clip(RoundedCornerShape(18.dp)),
            contentAlignment = Alignment.Center
        ) {
            // Vibrant reference backdrop behind the glass panel
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .glassBackdropSource(previewBackdrop)
                    .background(
                        Brush.linearGradient(
                            colors = listOf(Lilac.copy(alpha = 0.80f), Lime.copy(alpha = 0.72f), Sky.copy(alpha = 0.78f))
                        )
                    )
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(modifier = Modifier.size(54.dp).clip(CircleShape).background(Rose.copy(alpha = 0.85f)))
                    Box(modifier = Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)).background(Peach.copy(alpha = 0.85f)))
                    Box(modifier = Modifier.size(58.dp).clip(CircleShape).background(Lime.copy(alpha = 0.85f)))
                }
            }

            // Real Liquid Glass / Acrylic surface preview
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.84f)
                    .height(84.dp)
                    .liquidGlass(
                        shape = previewShape,
                        style = GlassStyle.Preview,
                        backdrop = previewBackdrop
                    )
                    .padding(horizontal = 18.dp),
                contentAlignment = Alignment.CenterStart
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = if (glass.enabled) "Acrylic Glass · ${glass.tintMode.label}" else "Glass Disabled",
                        color = TextPrimary,
                        fontSize = 14.5.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = "Blur ${glass.blurDp.roundToInt()}dp · Opacity ${(glass.tintAlpha * 100).roundToInt()}% · Radius ${glass.cornerRadiusDp.roundToInt()}dp",
                        color = TextSecondary,
                        fontSize = 11.5.sp
                    )
                }
            }
        }
    }

    Spacer(Modifier.height(22.dp))

    IosSettingsSection(
        title = "Liquid Glass Presets",
        footer = "Select a curated optical profile or fine-tune individual parameters below."
    ) {
        GlassPreset.entries.filter { it != GlassPreset.CUSTOM }.forEachIndexed { idx, preset ->
            if (idx > 0) IosSettingsDivider(insetStart = 16.dp)
            IosSettingsRow(
                title = preset.label,
                subtitle = preset.subtitle,
                value = if (activePreset == preset) "Active" else null,
                selected = activePreset == preset,
                showChevron = false,
                onClick = { viewModel.applyGlassPreset(preset) }
            )
        }
    }

    Spacer(Modifier.height(22.dp))

    IosSettingsSection(title = "Acrylic Tint & Color") {
        GlassTintMode.entries.forEachIndexed { idx, mode ->
            if (idx > 0) IosSettingsDivider(insetStart = 16.dp)
            IosSettingsRow(
                title = mode.label,
                value = if (glass.tintMode == mode) "Active" else null,
                selected = glass.tintMode == mode,
                showChevron = false,
                onClick = { viewModel.setGlassAppearance(tintMode = mode) }
            )
        }
        if (glass.tintMode == GlassTintMode.CUSTOM) {
            IosSettingsDivider(insetStart = 16.dp)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                tintSwatches.forEach { argb ->
                    val selected = glass.customTintArgb == argb
                    Box(
                        modifier = Modifier
                            .size(34.dp)
                            .clip(CircleShape)
                            .background(Color(argb))
                            .border(
                                width = if (selected) 3.dp else 1.dp,
                                color = if (selected) Lime else InkGlassBorder,
                                shape = CircleShape
                            )
                            .clickable { viewModel.setGlassAppearance(customTintArgb = argb) }
                    )
                }
            }
        }
        IosSettingsDivider(insetStart = 16.dp)
        IosSettingsSliderRow(
            title = "Tint & Background Opacity",
            valueLabel = "${(glass.tintAlpha * 100).roundToInt()}%",
            value = glass.tintAlpha,
            valueRange = 0f..0.75f,
            onValueChange = viewModel::setGlassTintAlpha
        )
        IosSettingsDivider(insetStart = 16.dp)
        IosSettingsSliderRow(
            title = "Surface Brightness",
            valueLabel = "${if (glass.brightness >= 0f) "+" else ""}${(glass.brightness * 100).roundToInt()}%",
            value = glass.brightness,
            valueRange = -0.35f..0.35f,
            onValueChange = { viewModel.setGlassAppearance(brightness = it) }
        )
    }

    Spacer(Modifier.height(22.dp))

    IosSettingsSection(title = "Surface, Border & Grain") {
        IosSettingsSliderRow(
            title = "Corner Radius",
            valueLabel = "${glass.cornerRadiusDp.roundToInt()} dp",
            value = glass.cornerRadiusDp,
            valueRange = 8f..36f,
            onValueChange = { viewModel.setGlassAppearance(cornerRadiusDp = it.roundToInt().toFloat()) }
        )
        IosSettingsDivider(insetStart = 16.dp)
        IosSettingsSwitchRow(
            title = "Surface Border Highlight",
            checked = glass.borderEnabled,
            onCheckedChange = { viewModel.setGlassAppearance(borderEnabled = it) }
        )
        if (glass.borderEnabled) {
            IosSettingsDivider(insetStart = 16.dp)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                listOf(0xFFFFFFFFL, 0xFFC8E65AL, 0xFFCF9CF1L, 0xFF38E8FFL, 0xFF14131AL).forEach { argb ->
                    val selected = glass.borderColorArgb == argb
                    Box(
                        modifier = Modifier
                            .size(30.dp)
                            .clip(CircleShape)
                            .background(Color(argb))
                            .border(
                                width = if (selected) 3.dp else 1.dp,
                                color = if (selected) Lime else InkGlassBorder,
                                shape = CircleShape
                            )
                            .clickable { viewModel.setGlassAppearance(borderColorArgb = argb) }
                    )
                }
            }
            IosSettingsDivider(insetStart = 16.dp)
            IosSettingsSliderRow(
                title = "Border Opacity",
                valueLabel = "${(glass.borderOpacity * 100).roundToInt()}%",
                value = glass.borderOpacity,
                valueRange = 0f..0.85f,
                onValueChange = { viewModel.setGlassAppearance(borderOpacity = it) }
            )
            IosSettingsDivider(insetStart = 16.dp)
            IosSettingsSliderRow(
                title = "Border Thickness",
                valueLabel = "${"%.1f".format(glass.borderThicknessDp)} dp",
                value = glass.borderThicknessDp,
                valueRange = 0.5f..3.0f,
                onValueChange = { viewModel.setGlassAppearance(borderThicknessDp = it) }
            )
        }
        IosSettingsDivider(insetStart = 16.dp)
        IosSettingsSliderRow(
            title = "Shadow Elevation",
            valueLabel = "${glass.shadowElevationDp.roundToInt()} dp",
            value = glass.shadowElevationDp,
            valueRange = 0f..24f,
            onValueChange = { viewModel.setGlassAppearance(shadowElevationDp = it.roundToInt().toFloat()) }
        )
        IosSettingsDivider(insetStart = 16.dp)
        IosSettingsSliderRow(
            title = "Acrylic Noise / Grain",
            valueLabel = "${(glass.noiseGrainAlpha * 100).roundToInt()}%",
            value = glass.noiseGrainAlpha,
            valueRange = 0f..0.25f,
            onValueChange = { viewModel.setGlassAppearance(noiseGrainAlpha = it) }
        )
    }

    Spacer(Modifier.height(22.dp))

    IosSettingsSection(title = "Master, Surfaces & Performance") {
        IosSettingsSwitchRow(
            title = "Enable Liquid Glass Engine",
            subtitle = "Real-time backdrop sampling, lens refraction & specular rim highlights",
            checked = glass.enabled,
            onCheckedChange = viewModel::setGlassEnabled,
            icon = GeekifyIcons.AutoAwesome,
            tileColor = IosPalette.systemCyan
        )
        IosSettingsDivider()
        IosSettingsSwitchRow(
            title = "Mini-Player Glass Refraction",
            subtitle = "Sample scrolling content behind the floating Now Playing pill",
            checked = glass.miniPlayerGlass,
            enabled = glass.enabled,
            onCheckedChange = viewModel::setGlassMiniPlayer,
            icon = GeekifyIcons.PlayCircleFilled,
            tileColor = IosPalette.systemGreen
        )
        IosSettingsDivider()
        IosSettingsSwitchRow(
            title = "Navigation Bar Glass Refraction",
            subtitle = "Apply Liquid Glass refraction to the bottom navigation dock",
            checked = glass.navBarGlass,
            enabled = glass.enabled,
            onCheckedChange = viewModel::setGlassNavBar,
            icon = GeekifyIcons.Navigation,
            tileColor = IosPalette.systemBlue
        )
        GlassQualityLevel.entries.forEach { level ->
            IosSettingsDivider(insetStart = 16.dp)
            IosSettingsRow(
                title = "Quality: ${level.label}",
                value = if (glass.qualityLevel == level) "Active" else null,
                selected = glass.qualityLevel == level,
                showChevron = false,
                onClick = { viewModel.setGlassAppearance(qualityLevel = level) }
            )
        }
    }

    Spacer(Modifier.height(22.dp))

    IosSettingsSection(
        title = "Optical Parameters",
        footer = "Adjusting any slider automatically switches the preset to Custom."
    ) {
        IosSettingsSliderRow(
            title = "Backdrop Blur Radius",
            valueLabel = "${glass.blurDp.roundToInt()} dp",
            value = glass.blurDp,
            valueRange = 0f..25f,
            onValueChange = viewModel::setGlassBlurDp
        )
        IosSettingsDivider(insetStart = 16.dp)
        IosSettingsSliderRow(
            title = "Color Saturation Boost",
            valueLabel = "${"%.1f".format(glass.saturation)}x",
            value = glass.saturation,
            valueRange = 0.5f..2.5f,
            onValueChange = viewModel::setGlassSaturation
        )
        IosSettingsDivider(insetStart = 16.dp)
        IosSettingsSliderRow(
            title = "Lens Refraction Strength",
            valueLabel = "${glass.lensAmountDp.roundToInt()} dp",
            value = glass.lensAmountDp,
            valueRange = 0f..32f,
            onValueChange = viewModel::setGlassLensAmountDp
        )
        IosSettingsDivider(insetStart = 16.dp)
        IosSettingsSwitchRow(
            title = "Chromatic Aberration",
            subtitle = "Subtle RGB dispersion along curved glass edges",
            checked = glass.chromaticAberration,
            enabled = glass.enabled,
            onCheckedChange = viewModel::setGlassChromaticAberration
        )
        IosSettingsDivider(insetStart = 16.dp)
        IosSettingsSwitchRow(
            title = "3D Depth Specular Rim",
            subtitle = "Enhanced inner shadow and top-edge highlight",
            checked = glass.depthEffect,
            enabled = glass.enabled,
            onCheckedChange = viewModel::setGlassDepthEffect
        )
    }
}

// ============================================================================================
// 3. ASCII BACKGROUND PAGE
// ============================================================================================
@Composable
private fun AsciiBackgroundCategoryPage(
    state: AppSettingsState,
    viewModel: CustomizationViewModel
) {
    val ascii = state.ascii
    val perfMode = state.customization.asciiPerformanceMode

    IosSettingsSection(title = "Procedural ASCII Canvas") {
        IosSettingsSwitchRow(
            title = "Enable Animated ASCII Background",
            subtitle = "Render live procedural ASCII creatures and atmospheric scenes behind screens",
            checked = ascii.enabled,
            onCheckedChange = viewModel::setAsciiEnabled,
            icon = GeekifyIcons.Tune,
            tileColor = IosPalette.systemPurple
        )
    }

    Spacer(Modifier.height(22.dp))

    IosSettingsSection(
        title = "Performance & Battery Profile",
        footer = "Battery Saver lowers glyph grid density to reduce GPU/CPU load on older devices."
    ) {
        AsciiPerformanceMode.entries.forEachIndexed { idx, mode ->
            if (idx > 0) IosSettingsDivider(insetStart = 16.dp)
            IosSettingsRow(
                title = mode.label,
                subtitle = mode.subtitle,
                value = if (perfMode == mode) "Active" else null,
                selected = perfMode == mode,
                showChevron = false,
                onClick = { viewModel.setAsciiPerformanceMode(mode) }
            )
        }
    }

    Spacer(Modifier.height(22.dp))

    val allAsciiSpecs = remember { AsciiCatalog.CREATURES + AsciiCatalog.SCENES }
    IosSettingsSection(title = "Animation Scene Selection") {
        allAsciiSpecs.forEachIndexed { idx, spec ->
            if (idx > 0) IosSettingsDivider(insetStart = 16.dp)
            IosSettingsRow(
                title = spec.name,
                subtitle = if (spec in AsciiCatalog.CREATURES) "Interactive Creature Simulation" else "Atmospheric Scene Simulation",
                value = if (ascii.animalId == spec.id) "Selected" else null,
                selected = ascii.animalId == spec.id,
                showChevron = false,
                onClick = { viewModel.setAsciiAnimal(spec.id) }
            )
        }
    }

    Spacer(Modifier.height(22.dp))

    IosSettingsSection(title = "Density & Color Tweaks") {
        IosSettingsSliderRow(
            title = "ASCII Glyph Detail",
            valueLabel = "${"%.2f".format(ascii.detail)}x",
            value = ascii.detail,
            valueRange = 0.5f..2.0f,
            onValueChange = viewModel::setAsciiDetail
        )
        IosSettingsDivider(insetStart = 16.dp)
        IosSettingsSliderRow(
            title = "Grid Cell Spacing",
            valueLabel = "${"%.1f".format(ascii.grid)}",
            value = ascii.grid,
            valueRange = 0f..4.0f,
            onValueChange = viewModel::setAsciiGrid
        )
        IosSettingsDivider(insetStart = 16.dp)
        IosSettingsSliderRow(
            title = "Hue Shift",
            valueLabel = "${ascii.hueShift.roundToInt()}°",
            value = ascii.hueShift,
            valueRange = -180f..180f,
            onValueChange = viewModel::setAsciiHueShift
        )
        IosSettingsDivider(insetStart = 16.dp)
        IosSettingsSwitchRow(
            title = "Continuous Rainbow Hue Cycle",
            checked = ascii.colorCycle,
            enabled = ascii.enabled,
            onCheckedChange = viewModel::setAsciiColorCycle
        )
    }
}

// ============================================================================================
// 4. HOME SCREEN PAGE
// ============================================================================================
@Composable
private fun HomeScreenCategoryPage(
    state: AppSettingsState,
    viewModel: CustomizationViewModel
) {
    val home = state.customization.home

    IosSettingsSection(
        title = "Home Section Order & Visibility",
        footer = "Reorder sections or hide shelves you do not use. Changes apply immediately on the Home tab."
    ) {
        home.sectionOrder.forEachIndexed { idx, section ->
            if (idx > 0) IosSettingsDivider(insetStart = 16.dp)
            val isVisible = section !in home.hiddenSections
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = section.label,
                        color = if (isVisible) IosPalette.label else IosPalette.secondaryLabel,
                        fontFamily = AppFont,
                        fontSize = 15.5.sp,
                        lineHeight = 20.5.sp,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = if (isVisible) "Position ${idx + 1}" else "Hidden · Saved slot ${idx + 1}",
                        color = IosPalette.secondaryLabel,
                        fontFamily = AppFont,
                        fontSize = 12.sp
                    )
                }
                Spacer(Modifier.width(8.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(34.dp)
                            .clip(CircleShape)
                            .background(SettingsVisuals.cardRaised)
                            .clickable(enabled = idx > 0) { viewModel.moveHomeSection(section, -1) },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            "↑",
                            color = if (idx > 0) Lime else IosPalette.tertiaryLabel,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Box(
                        modifier = Modifier
                            .size(34.dp)
                            .clip(CircleShape)
                            .background(SettingsVisuals.cardRaised)
                            .clickable(enabled = idx < home.sectionOrder.lastIndex) { viewModel.moveHomeSection(section, 1) },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            "↓",
                            color = if (idx < home.sectionOrder.lastIndex) Lime else IosPalette.tertiaryLabel,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Spacer(Modifier.width(2.dp))
                    IosSwitch(
                        checked = isVisible,
                        onCheckedChange = { viewModel.toggleHomeSectionVisible(section, it) }
                    )
                }
            }
        }
        IosSettingsDivider(insetStart = 16.dp)
        IosSettingsRow(
            title = "Reset to Default Order",
            showChevron = false,
            onClick = { viewModel.resetHomeSectionOrder() }
        )
    }

    Spacer(Modifier.height(22.dp))

    IosSettingsSection(title = "Shelf Layout Mode") {
        HomeLayoutMode.entries.forEachIndexed { idx, mode ->
            if (idx > 0) IosSettingsDivider(insetStart = 16.dp)
            IosSettingsRow(
                title = mode.label,
                value = if (home.layoutMode == mode) "Active" else null,
                selected = home.layoutMode == mode,
                showChevron = false,
                onClick = { viewModel.setHomeLayoutMode(mode) }
            )
        }
    }

    Spacer(Modifier.height(22.dp))

    IosSettingsSection(title = "Card Sizing & Chrome") {
        if (home.layoutMode == HomeLayoutMode.COMPACT_GRID) {
            IosSettingsSliderRow(
                title = "Grid Columns",
                valueLabel = "${home.gridColumns} cols",
                value = home.gridColumns.toFloat(),
                valueRange = 2f..3f,
                onValueChange = { viewModel.setHomeGridColumns(it.roundToInt()) }
            )
            IosSettingsDivider(insetStart = 16.dp)
        }
        IosSettingsSliderRow(
            title = "Daily Picks Artwork Size",
            valueLabel = "${home.artworkSizeDp} dp",
            value = home.artworkSizeDp.toFloat(),
            valueRange = 52f..104f,
            onValueChange = { viewModel.setHomeArtworkSizeDp(it.roundToInt()) }
        )
        IosSettingsDivider(insetStart = 16.dp)
        IosSettingsSliderRow(
            title = "Card Corner Radius",
            valueLabel = "${home.cardCornerRadiusDp} dp",
            value = home.cardCornerRadiusDp.toFloat(),
            valueRange = 8f..36f,
            onValueChange = { viewModel.setHomeCornerRadiusDp(it.roundToInt()) }
        )
        IosSettingsDivider(insetStart = 16.dp)
        IosSettingsSwitchRow(
            title = "Show Top Greeting Header",
            checked = home.showGreetingHeader,
            onCheckedChange = viewModel::setHomeShowGreeting
        )
        IosSettingsDivider(insetStart = 16.dp)
        IosSettingsSwitchRow(
            title = "Show Section Headings",
            checked = home.showSectionTitles,
            onCheckedChange = viewModel::setHomeShowTitles
        )
        IosSettingsDivider(insetStart = 16.dp)
        IosSettingsSwitchRow(
            title = "Show Artist & Subtitle Metadata",
            checked = home.showMetadata,
            onCheckedChange = viewModel::setHomeShowMetadata
        )
    }
}

// ============================================================================================
// 5. NAVIGATION PAGE
// ============================================================================================
@Composable
private fun NavigationCategoryPage(
    state: AppSettingsState,
    viewModel: CustomizationViewModel
) {
    val nav = state.customization.navigation
    val fullOrder = nav.normalizedTabOrder
    val visibleTabs = nav.safeVisibleTabs

    IosSettingsSection(
        title = "Bottom Navigation Destinations",
        footer = "Reorder or disable tabs. Hidden tabs preserve their saved position when re-enabled. At least one primary destination (Home or Library) always remains enabled."
    ) {
        fullOrder.forEachIndexed { idx, tab ->
            if (idx > 0) IosSettingsDivider(insetStart = 16.dp)
            val isVisible = tab in visibleTabs
            val visiblePos = visibleTabs.indexOf(tab)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = tab.label,
                        color = if (isVisible) IosPalette.label else IosPalette.secondaryLabel,
                        fontFamily = AppFont,
                        fontSize = 15.5.sp,
                        lineHeight = 20.5.sp,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = if (isVisible) "Tab ${visiblePos + 1} · Slot ${idx + 1}" else "Hidden · Saved slot ${idx + 1}",
                        color = IosPalette.secondaryLabel,
                        fontFamily = AppFont,
                        fontSize = 12.sp
                    )
                }
                Spacer(Modifier.width(8.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(34.dp)
                            .clip(CircleShape)
                            .background(SettingsVisuals.cardRaised)
                            .clickable(enabled = idx > 0) { viewModel.moveNavTab(tab, -1) },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            "↑",
                            color = if (idx > 0) Lime else IosPalette.tertiaryLabel,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Box(
                        modifier = Modifier
                            .size(34.dp)
                            .clip(CircleShape)
                            .background(SettingsVisuals.cardRaised)
                            .clickable(enabled = idx < fullOrder.lastIndex) { viewModel.moveNavTab(tab, 1) },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            "↓",
                            color = if (idx < fullOrder.lastIndex) Lime else IosPalette.tertiaryLabel,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Spacer(Modifier.width(2.dp))
                    IosSwitch(
                        checked = isVisible,
                        onCheckedChange = { viewModel.toggleNavTabVisible(tab, it) }
                    )
                }
            }
        }
        IosSettingsDivider(insetStart = 16.dp)
        IosSettingsRow(
            title = "Reset to Default Order",
            showChevron = false,
            onClick = { viewModel.resetNavTabOrder() }
        )
    }

    Spacer(Modifier.height(22.dp))

    IosSettingsSection(title = "Navigation Bar Style & Labels") {
        NavBarStyle.entries.forEachIndexed { idx, style ->
            if (idx > 0) IosSettingsDivider(insetStart = 16.dp)
            IosSettingsRow(
                title = style.label,
                value = if (nav.style == style) "Active" else null,
                selected = nav.style == style,
                showChevron = false,
                onClick = { viewModel.setNavStyle(style) }
            )
        }
        IosSettingsDivider(insetStart = 16.dp)
        IosSettingsSwitchRow(
            title = "Show Tab Text Labels",
            subtitle = "Display text captions underneath navigation icons",
            checked = nav.showLabels,
            onCheckedChange = viewModel::setNavShowLabels
        )
        IosSettingsDivider(insetStart = 16.dp)
        IosSettingsSwitchRow(
            title = "Floating Navigation Bar",
            subtitle = "Float the bottom bar as a pill; disable for a full-width docked bar",
            checked = nav.floatingNavBar,
            onCheckedChange = viewModel::setNavFloating
        )
        IosSettingsDivider(insetStart = 16.dp)
        IosSettingsSwitchRow(
            title = "Floating Top Bar",
            subtitle = "Use elevated floating chrome for top headers where supported",
            checked = nav.floatingTopBar,
            onCheckedChange = viewModel::setNavFloatingTopBar
        )
    }

    Spacer(Modifier.height(22.dp))

    IosSettingsSection(title = "Default Startup Destination") {
        listOf("home" to "Home", "search" to "Search", "library" to "Library").forEachIndexed { idx, (key, label) ->
            if (idx > 0) IosSettingsDivider(insetStart = 16.dp)
            IosSettingsRow(
                title = label,
                value = if (state.defaultTab == key) "Default" else null,
                selected = state.defaultTab == key,
                showChevron = false,
                onClick = { viewModel.setDefaultStartupTab(key) }
            )
        }
    }

    Spacer(Modifier.height(22.dp))

    IosSettingsSection(title = "Scroll Collapse Behavior") {
        FloatingTabBarInlineBehavior.entries.forEachIndexed { idx, behavior ->
            if (idx > 0) IosSettingsDivider(insetStart = 16.dp)
            IosSettingsRow(
                title = behavior.name.replace('_', ' ').lowercase().replaceFirstChar { it.uppercase() },
                value = if (state.navInlineBehavior == behavior) "Active" else null,
                selected = state.navInlineBehavior == behavior,
                showChevron = false,
                onClick = { viewModel.setNavInlineBehavior(behavior) }
            )
        }
    }
}

// ============================================================================================
// 6. PLAYER APPEARANCE PAGE
// ============================================================================================
@Composable
private fun PlayerAppearanceCategoryPage(
    state: AppSettingsState,
    viewModel: CustomizationViewModel
) {
    val player = state.customization.player
    val seekbar = state.customization.seekbar
    var previewFraction by remember { mutableFloatStateOf(0.42f) }
    var previewPlaying by remember { mutableStateOf(true) }
    val previewDurationMs = 238_000L // 3:58 sample track

    // Live Now Playing Layout & Interactive Seekbar Preview Card
    IosSettingsSection(
        title = "Live Player Preview",
        footer = "Drag or tap the preview seekbar to test scrubbing, thumb response, and time labels without affecting active playback."
    ) {
        val bgBrush = when (player.backgroundStyle) {
            PlayerBackgroundStyle.AMOLED_PITCH -> Brush.verticalGradient(listOf(Color.Black, Color.Black))
            PlayerBackgroundStyle.SOLID_DARK -> Brush.verticalGradient(listOf(InkBackground, InkBackground))
            PlayerBackgroundStyle.DYNAMIC_GRADIENT -> Brush.verticalGradient(listOf(Lilac.copy(alpha = 0.48f), InkBackground))
            PlayerBackgroundStyle.ANIMATED_GRADIENT -> Brush.linearGradient(listOf(Lilac.copy(alpha = 0.55f), Lime.copy(alpha = 0.30f), InkBackground))
            PlayerBackgroundStyle.LIQUID_GLASS -> Brush.linearGradient(listOf(Color.White.copy(alpha = 0.14f), Lilac.copy(alpha = 0.35f), InkPanel))
            PlayerBackgroundStyle.BLURRED_ARTWORK -> Brush.verticalGradient(listOf(Lime.copy(alpha = 0.35f), InkPanel))
        }
        val previewArtShape = when {
            player.layoutStyle == PlayerLayoutStyle.VINYL_TONEARM || player.artworkShape == PlayerArtworkShape.VINYL_DISC -> CircleShape
            player.artworkShape == PlayerArtworkShape.CIRCLE -> CircleShape
            player.artworkShape == PlayerArtworkShape.SQUARE -> RoundedCornerShape(4.dp)
            else -> RoundedCornerShape(16.dp)
        }
        val previewArtSize = when (player.layoutStyle) {
            PlayerLayoutStyle.MINIMAL -> 52.dp
            PlayerLayoutStyle.ARTWORK_FOCUSED -> 86.dp
            PlayerLayoutStyle.FULLSCREEN_ARTWORK -> 78.dp
            else -> 68.dp
        }
        val previewPosMs = (previewFraction * previewDurationMs).toLong().coerceIn(0L, previewDurationMs)
        val previewRemMs = (previewDurationMs - previewPosMs).coerceAtLeast(0L)
        val previewLabelSize = (12.5f * seekbar.timeLabelScale.coerceIn(0.8f, 1.35f)).sp

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(bgBrush)
                .padding(18.dp),
            horizontalAlignment = if (player.layoutStyle == PlayerLayoutStyle.EXPRESSIVE || player.layoutStyle == PlayerLayoutStyle.STANDARD) Alignment.Start else Alignment.CenterHorizontally
        ) {
            Box(
                modifier = Modifier
                    .size(previewArtSize)
                    .align(Alignment.CenterHorizontally)
                    .clip(previewArtShape)
                    .background(Brush.linearGradient(listOf(Lime, Lilac)))
                    .border(
                        width = if (player.layoutStyle == PlayerLayoutStyle.VINYL_TONEARM) 6.dp else 1.dp,
                        color = if (player.layoutStyle == PlayerLayoutStyle.VINYL_TONEARM) Color(0xFF141418) else Color.White.copy(alpha = 0.25f),
                        shape = previewArtShape
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(GeekifyIcons.Album, contentDescription = null, tint = OnAccent, modifier = Modifier.size(28.dp))
            }
            Spacer(Modifier.height(12.dp))
            if (player.layoutStyle == PlayerLayoutStyle.EXPRESSIVE) {
                Text("PLAYING FROM QUEUE", color = Lime, fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp)
            }
            Text(
                text = player.layoutStyle.label,
                color = Color.White,
                fontSize = if (player.layoutStyle == PlayerLayoutStyle.EXPRESSIVE) 18.sp else 16.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "${player.backgroundStyle.label} · ${seekbar.style.label}",
                color = Color.White.copy(alpha = 0.7f),
                fontSize = 11.5.sp
            )
            Spacer(Modifier.height(10.dp))
            com.geekify.android.ui.player.PlayerProgressBar(
                fraction = previewFraction,
                config = seekbar,
                onChange = { previewFraction = it },
                onChangeFinished = {},
                activeColor = Lime,
                secondaryAccentColor = Lilac,
                isPlaying = previewPlaying
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (seekbar.showElapsedTime) {
                    Text(
                        text = com.geekify.android.ui.player.formatPlaybackTime(previewPosMs),
                        color = Color.White.copy(alpha = 0.76f),
                        fontSize = previewLabelSize,
                        fontWeight = FontWeight.Medium
                    )
                } else {
                    Spacer(Modifier.width(1.dp))
                }
                if (seekbar.showRemainingTime) {
                    Text(
                        text = if (player.showRemainingTime) {
                            "-" + com.geekify.android.ui.player.formatPlaybackTime(previewRemMs)
                        } else {
                            com.geekify.android.ui.player.formatPlaybackTime(previewDurationMs)
                        },
                        color = Color.White.copy(alpha = 0.76f),
                        fontSize = previewLabelSize,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(GeekifyIcons.SkipPrevious, contentDescription = null, tint = Color.White, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(18.dp))
                Box(
                    modifier = Modifier
                        .size(if (player.compactControls) 36.dp else 44.dp)
                        .clip(if (player.roundedPlayButton) CircleShape else RoundedCornerShape(12.dp))
                        .background(Lime)
                        .clickable { previewPlaying = !previewPlaying },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (previewPlaying) GeekifyIcons.Pause else GeekifyIcons.Play,
                        contentDescription = "Toggle preview playback",
                        tint = OnAccent,
                        modifier = Modifier.size(20.dp)
                    )
                }
                Spacer(Modifier.width(18.dp))
                Icon(GeekifyIcons.SkipNext, contentDescription = null, tint = Color.White, modifier = Modifier.size(20.dp))
            }
        }
    }

    Spacer(Modifier.height(22.dp))

    IosSettingsSection(
        title = "Now Playing Layout Style (6 Modes)",
        footer = "Vinyl Player preserves Geekify's signature spinning vinyl disc and interactive stylus tonearm."
    ) {
        PlayerLayoutStyle.entries.forEachIndexed { idx, style ->
            if (idx > 0) IosSettingsDivider(insetStart = 16.dp)
            IosSettingsRow(
                title = style.label,
                subtitle = style.subtitle,
                value = if (player.layoutStyle == style) "Active" else null,
                selected = player.layoutStyle == style,
                showChevron = false,
                onClick = { viewModel.setPlayerLayoutStyle(style) }
            )
        }
    }

    Spacer(Modifier.height(22.dp))

    IosSettingsSection(title = "Artwork Shape, Presentation & Motion") {
        PlayerArtworkShape.entries.forEachIndexed { idx, shape ->
            if (idx > 0) IosSettingsDivider(insetStart = 16.dp)
            IosSettingsRow(
                title = shape.label,
                value = if (player.artworkShape == shape) "Active" else null,
                selected = player.artworkShape == shape,
                showChevron = false,
                onClick = { viewModel.setPlayerArtworkShape(shape) }
            )
        }
        IosSettingsDivider(insetStart = 16.dp)
        PlayerArtworkPresentation.entries.forEachIndexed { idx, pres ->
            if (idx > 0) IosSettingsDivider(insetStart = 16.dp)
            IosSettingsRow(
                title = "Framing: ${pres.label}",
                subtitle = pres.subtitle,
                value = if (player.artworkPresentation == pres) "Active" else null,
                selected = player.artworkPresentation == pres,
                showChevron = false,
                onClick = { viewModel.setPlayerArtworkPresentation(pres) }
            )
        }
        IosSettingsDivider(insetStart = 16.dp)
        PlayerArtworkCropMode.entries.forEachIndexed { idx, crop ->
            if (idx > 0) IosSettingsDivider(insetStart = 16.dp)
            IosSettingsRow(
                title = "Artwork Fit: ${crop.label}",
                subtitle = crop.subtitle,
                value = if (player.artworkCropMode == crop) "Active" else null,
                selected = player.artworkCropMode == crop,
                showChevron = false,
                onClick = { viewModel.setPlayerArtworkCropMode(crop) }
            )
        }
        IosSettingsDivider(insetStart = 16.dp)
        IosSettingsSliderRow(
            title = "Artwork Scale Multiplier",
            valueLabel = "${(player.artworkScale * 100).roundToInt()}%",
            value = player.artworkScale,
            valueRange = 0.75f..1.20f,
            onValueChange = viewModel::setPlayerArtworkScale
        )
        IosSettingsDivider(insetStart = 16.dp)
        IosSettingsSwitchRow(
            title = "Track Change Artwork Transitions",
            subtitle = "Slide and crossfade album covers when skipping tracks",
            checked = player.artworkTransitions,
            onCheckedChange = { viewModel.setPlayerArtworkOptions(transitions = it) }
        )
        IosSettingsDivider(insetStart = 16.dp)
        IosSettingsSwitchRow(
            title = "Animated & Beat-Reactive Artwork",
            subtitle = "Enable dynamic beat scaling and play/pause cover motion",
            checked = player.animatedArtworkEnabled,
            onCheckedChange = { viewModel.setPlayerArtworkOptions(animatedArtwork = it) }
        )
        IosSettingsDivider(insetStart = 16.dp)
        IosSettingsSwitchRow(
            title = "Pause Animated Artwork on Mobile Data",
            subtitle = "Reduce animated artwork effects while on metered cellular connections",
            checked = player.disableAnimatedArtworkOnMobileData,
            enabled = player.animatedArtworkEnabled,
            onCheckedChange = { viewModel.setPlayerArtworkOptions(disableOnMobileData = it) }
        )
    }

    Spacer(Modifier.height(22.dp))

    IosSettingsSection(title = "Player Backdrop Style (6 Modes)") {
        PlayerBackgroundStyle.entries.forEachIndexed { idx, bg ->
            if (idx > 0) IosSettingsDivider(insetStart = 16.dp)
            IosSettingsRow(
                title = bg.label,
                value = if (player.backgroundStyle == bg) "Active" else null,
                selected = player.backgroundStyle == bg,
                showChevron = false,
                onClick = { viewModel.setPlayerBackgroundStyle(bg) }
            )
        }
    }

    Spacer(Modifier.height(22.dp))

    IosSettingsSection(
        title = "Seekbar Style (5 Modes)",
        footer = "Live interactive preview below — scrub or tap to inspect your customized seekbar."
    ) {
        // Inline Interactive Seekbar Preview right above the style controls
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(InkBackground.copy(alpha = 0.65f))
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "${seekbar.style.label} · ${seekbar.thumbStyle.label}",
                    color = TextSecondary,
                    fontSize = 11.5.sp,
                    fontWeight = FontWeight.Medium
                )
                Box(
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(Lime.copy(alpha = 0.16f))
                        .clickable { previewPlaying = !previewPlaying }
                        .padding(horizontal = 10.dp, vertical = 3.dp)
                ) {
                    Text(
                        text = if (previewPlaying) "Playing" else "Paused",
                        color = Lime,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
            com.geekify.android.ui.player.PlayerProgressBar(
                fraction = previewFraction,
                config = seekbar,
                onChange = { previewFraction = it },
                onChangeFinished = {},
                activeColor = Lime,
                secondaryAccentColor = Lilac,
                isPlaying = previewPlaying
            )
            val pPosMs = (previewFraction * previewDurationMs).toLong().coerceIn(0L, previewDurationMs)
            val pRemMs = (previewDurationMs - pPosMs).coerceAtLeast(0L)
            val pLblSp = (12.5f * seekbar.timeLabelScale.coerceIn(0.8f, 1.35f)).sp
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                if (seekbar.showElapsedTime) {
                    Text(
                        text = com.geekify.android.ui.player.formatPlaybackTime(pPosMs),
                        color = TextSecondary,
                        fontSize = pLblSp
                    )
                } else {
                    Spacer(Modifier.width(1.dp))
                }
                if (seekbar.showRemainingTime) {
                    Text(
                        text = "-" + com.geekify.android.ui.player.formatPlaybackTime(pRemMs),
                        color = TextSecondary,
                        fontSize = pLblSp
                    )
                }
            }
        }
        IosSettingsDivider(insetStart = 0.dp)
        PlayerProgressStyle.entries.forEachIndexed { idx, pStyle ->
            if (idx > 0) IosSettingsDivider(insetStart = 16.dp)
            IosSettingsRow(
                title = pStyle.label,
                subtitle = pStyle.subtitle,
                value = if (seekbar.style == pStyle) "Active" else null,
                selected = seekbar.style == pStyle,
                showChevron = false,
                onClick = { viewModel.setPlayerProgressStyle(pStyle) }
            )
        }
    }

    Spacer(Modifier.height(22.dp))

    IosSettingsSection(title = "Seekbar Thumb & Handle") {
        SeekbarThumbStyle.entries.forEachIndexed { idx, thumb ->
            if (idx > 0) IosSettingsDivider(insetStart = 16.dp)
            IosSettingsRow(
                title = thumb.label,
                value = if (seekbar.thumbStyle == thumb) "Active" else null,
                selected = seekbar.thumbStyle == thumb,
                showChevron = false,
                onClick = { viewModel.setSeekbarThumbStyle(thumb) }
            )
        }
        if (seekbar.thumbStyle != SeekbarThumbStyle.HIDDEN) {
            IosSettingsDivider(insetStart = 16.dp)
            IosSettingsSliderRow(
                title = "Thumb Size",
                valueLabel = "${seekbar.thumbSizeDp.roundToInt()} dp",
                value = seekbar.thumbSizeDp,
                valueRange = 6f..24f,
                onValueChange = { viewModel.setSeekbarNumeric(thumbSizeDp = it.roundToInt().toFloat()) }
            )
        }
    }

    Spacer(Modifier.height(22.dp))

    val isWaveSeekbar = seekbar.style == PlayerProgressStyle.WAVY ||
        seekbar.style == PlayerProgressStyle.WAVE_GLOW

    IosSettingsSection(title = "Seekbar Track Geometry & Touch") {
        IosSettingsSliderRow(
            title = "Track Thickness",
            valueLabel = "${seekbar.thicknessDp.roundToInt()} dp",
            value = seekbar.thicknessDp,
            valueRange = 2f..12f,
            onValueChange = { viewModel.setPlayerProgressHeightDp(it.roundToInt().toFloat()) }
        )
        if (!isWaveSeekbar) {
            IosSettingsDivider(insetStart = 16.dp)
            IosSettingsSliderRow(
                title = "Track Corner Radius",
                valueLabel = "${seekbar.cornerRadiusDp.roundToInt()} dp",
                value = seekbar.cornerRadiusDp,
                valueRange = 0f..12f,
                onValueChange = { viewModel.setSeekbarNumeric(cornerRadiusDp = it.roundToInt().toFloat()) }
            )
        }
        IosSettingsDivider(insetStart = 16.dp)
        IosSettingsSliderRow(
            title = "Inactive Track Opacity",
            valueLabel = "${(seekbar.inactiveTrackAlpha * 100).roundToInt()}%",
            value = seekbar.inactiveTrackAlpha,
            valueRange = 0.08f..0.65f,
            onValueChange = { viewModel.setSeekbarNumeric(inactiveAlpha = it) }
        )
        IosSettingsDivider(insetStart = 16.dp)
        IosSettingsSliderRow(
            title = "Touch Target Height",
            valueLabel = "${seekbar.touchTargetHeightDp.roundToInt()} dp",
            value = seekbar.touchTargetHeightDp,
            valueRange = 32f..56f,
            onValueChange = { viewModel.setSeekbarNumeric(touchTargetHeightDp = it.roundToInt().toFloat()) }
        )
    }

    Spacer(Modifier.height(22.dp))

    val seekbarSwatches = remember {
        listOf(
            0xFFC8E65AL to "Lime",
            0xFFCF9CF1L to "Lavender",
            0xFF38E8FFL to "Cyan",
            0xFFFF7A6BL to "Coral",
            0xFFFFD15CL to "Amber",
            0xFF3EF0A8L to "Mint",
            0xFFFFFFFFL to "White"
        )
    }

    IosSettingsSection(title = "Seekbar Colors & Gradients") {
        IosSettingsSwitchRow(
            title = "Custom Active Track Color",
            subtitle = "Override the dynamic player accent for the played track",
            checked = seekbar.useCustomActiveColor,
            onCheckedChange = { viewModel.setSeekbarColors(useCustomActive = it) }
        )
        if (seekbar.useCustomActiveColor) {
            IosSettingsDivider(insetStart = 16.dp)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                seekbarSwatches.forEach { (argb, label) ->
                    val selected = seekbar.customActiveColorArgb == argb
                    Box(
                        modifier = Modifier
                            .size(34.dp)
                            .clip(CircleShape)
                            .background(Color(argb))
                            .border(
                                width = if (selected) 3.dp else 1.dp,
                                color = if (selected) TextPrimary else InkGlassBorder,
                                shape = CircleShape
                            )
                            .clickable { viewModel.setSeekbarColors(customActiveArgb = argb) }
                    )
                }
            }
        }
        IosSettingsDivider(insetStart = 16.dp)
        IosSettingsSwitchRow(
            title = "Custom Thumb Color",
            subtitle = "Choose an independent color for the seekbar handle",
            checked = seekbar.useCustomThumbColor,
            onCheckedChange = { viewModel.setSeekbarColors(useCustomThumb = it) }
        )
        if (seekbar.useCustomThumbColor) {
            IosSettingsDivider(insetStart = 16.dp)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                seekbarSwatches.forEach { (argb, _) ->
                    val selected = seekbar.customThumbColorArgb == argb
                    Box(
                        modifier = Modifier
                            .size(34.dp)
                            .clip(CircleShape)
                            .background(Color(argb))
                            .border(
                                width = if (selected) 3.dp else 1.dp,
                                color = if (selected) TextPrimary else InkGlassBorder,
                                shape = CircleShape
                            )
                            .clickable { viewModel.setSeekbarColors(customThumbArgb = argb) }
                    )
                }
            }
        }
        IosSettingsDivider(insetStart = 16.dp)
        IosSettingsSwitchRow(
            title = "Gradient Active Track",
            subtitle = "Blend two colors across the played segment",
            checked = seekbar.gradientEnabled,
            onCheckedChange = { viewModel.setSeekbarToggles(gradientEnabled = it) }
        )
        if (seekbar.gradientEnabled) {
            SeekbarGradientPreset.entries.forEach { preset ->
                IosSettingsDivider(insetStart = 16.dp)
                IosSettingsRow(
                    title = preset.label,
                    value = if (seekbar.gradientPreset == preset) "Active" else null,
                    selected = seekbar.gradientPreset == preset,
                    showChevron = false,
                    onClick = { viewModel.setSeekbarGradientPreset(preset) }
                )
            }
        }
    }

    if (isWaveSeekbar) {
        Spacer(Modifier.height(22.dp))
        IosSettingsSection(
            title = "Wave & Glow Motion",
            footer = "Animations automatically pause while playback is paused, buffering, or scrubbing."
        ) {
            IosSettingsSwitchRow(
                title = "Enable Seekbar Animations",
                checked = seekbar.animationsEnabled,
                onCheckedChange = { viewModel.setSeekbarToggles(animationsEnabled = it) }
            )
            IosSettingsDivider(insetStart = 16.dp)
            IosSettingsSwitchRow(
                title = "Animated Waveform Motion",
                checked = seekbar.waveAnimationEnabled,
                enabled = seekbar.animationsEnabled,
                onCheckedChange = { viewModel.setSeekbarToggles(waveAnimationEnabled = it) }
            )
            IosSettingsDivider(insetStart = 16.dp)
            IosSettingsSliderRow(
                title = "Wave Amplitude",
                valueLabel = "${"%.1f".format(seekbar.waveAmplitudeDp)} dp",
                value = seekbar.waveAmplitudeDp,
                valueRange = 1f..10f,
                onValueChange = { viewModel.setSeekbarNumeric(waveAmplitudeDp = it) }
            )
            IosSettingsDivider(insetStart = 16.dp)
            IosSettingsSliderRow(
                title = "Wave Frequency",
                valueLabel = "${"%.2f".format(seekbar.waveFrequency)}x",
                value = seekbar.waveFrequency,
                valueRange = 0.5f..2.2f,
                onValueChange = { viewModel.setSeekbarNumeric(waveFrequency = it) }
            )
            IosSettingsDivider(insetStart = 16.dp)
            IosSettingsSliderRow(
                title = "Wave Speed",
                valueLabel = "${"%.2f".format(seekbar.waveSpeed)}x",
                value = seekbar.waveSpeed,
                valueRange = 0.4f..2.5f,
                onValueChange = { viewModel.setSeekbarNumeric(waveSpeed = it) }
            )
            IosSettingsDivider(insetStart = 16.dp)
            IosSettingsSliderRow(
                title = "Animation Intensity",
                valueLabel = "${(seekbar.animationIntensity * 100).roundToInt()}%",
                value = seekbar.animationIntensity,
                valueRange = 0.2f..1.8f,
                onValueChange = { viewModel.setSeekbarNumeric(animationIntensity = it) }
            )
            if (seekbar.style == PlayerProgressStyle.WAVE_GLOW) {
                IosSettingsDivider(insetStart = 16.dp)
                IosSettingsSliderRow(
                    title = "Glow Halo Intensity",
                    valueLabel = "${(seekbar.glowIntensity * 100).roundToInt()}%",
                    value = seekbar.glowIntensity,
                    valueRange = 0f..1f,
                    onValueChange = { viewModel.setSeekbarNumeric(glowIntensity = it) }
                )
            }
        }
    }

    Spacer(Modifier.height(22.dp))

    IosSettingsSection(title = "Seekbar Time Labels & Haptics") {
        IosSettingsSwitchRow(
            title = "Seekbar Haptic Feedback",
            subtitle = "Subtle tactile tick when pressing and releasing the seekbar",
            checked = seekbar.hapticFeedback,
            onCheckedChange = { viewModel.setSeekbarToggles(hapticFeedback = it) }
        )
        IosSettingsDivider(insetStart = 16.dp)
        IosSettingsSwitchRow(
            title = "Show Elapsed Time",
            checked = seekbar.showElapsedTime,
            onCheckedChange = { viewModel.setSeekbarToggles(showElapsedTime = it) }
        )
        IosSettingsDivider(insetStart = 16.dp)
        IosSettingsSwitchRow(
            title = "Show Remaining / Total Time",
            checked = seekbar.showRemainingTime,
            onCheckedChange = { viewModel.setSeekbarToggles(showRemainingTime = it) }
        )
        IosSettingsDivider(insetStart = 16.dp)
        IosSettingsSliderRow(
            title = "Time Label Size",
            valueLabel = "${(seekbar.timeLabelScale * 100).roundToInt()}%",
            value = seekbar.timeLabelScale,
            valueRange = 0.8f..1.35f,
            onValueChange = { viewModel.setSeekbarNumeric(timeLabelScale = it) }
        )
    }

    Spacer(Modifier.height(22.dp))

    IosSettingsSection(title = "Control & Action Visibility") {
        IosSettingsSwitchRow(
            title = "Circular Play/Pause Button",
            subtitle = "Disable for a modern rounded-rectangle play/pause control",
            checked = player.roundedPlayButton,
            onCheckedChange = viewModel::setPlayerRoundedPlayButton
        )
        IosSettingsDivider(insetStart = 16.dp)
        IosSettingsSwitchRow(
            title = "Compact Control Arrangement",
            subtitle = "Tighter transport button spacing and sleeker sizing",
            checked = player.compactControls,
            onCheckedChange = { viewModel.setPlayerControlVisibility(compactControls = it) }
        )
        IosSettingsDivider(insetStart = 16.dp)
        IosSettingsSwitchRow(
            title = "Immersive Player Mode",
            subtitle = "Hide the system status bar while Now Playing is expanded",
            checked = player.immersivePlayer,
            onCheckedChange = { viewModel.setPlayerControlVisibility(immersivePlayer = it) }
        )
        IosSettingsDivider(insetStart = 16.dp)
        IosSettingsSwitchRow(
            title = "Show Album Metadata in Header",
            checked = player.showAlbumMetadata,
            onCheckedChange = { viewModel.setPlayerControlVisibility(showAlbumMetadata = it) }
        )
        IosSettingsDivider(insetStart = 16.dp)
        IosSettingsSwitchRow(
            title = "Show Remaining Time (-m:ss)",
            subtitle = "Disable to show total track duration instead",
            checked = player.showRemainingTime,
            onCheckedChange = { viewModel.setPlayerControlVisibility(showRemainingTime = it) }
        )
        IosSettingsDivider(insetStart = 16.dp)
        IosSettingsSwitchRow(
            title = "Show Offline Download Button",
            checked = player.showDownloadButton,
            onCheckedChange = { viewModel.setPlayerControlVisibility(showDownload = it) }
        )
        IosSettingsDivider(insetStart = 16.dp)
        IosSettingsSwitchRow(
            title = "Show Synchronized Lyrics Toggle",
            checked = player.showLyricsButton,
            onCheckedChange = { viewModel.setPlayerControlVisibility(showLyrics = it) }
        )
        IosSettingsDivider(insetStart = 16.dp)
        IosSettingsSwitchRow(
            title = "Show Sleep Timer Button",
            checked = player.showSleepTimerButton,
            onCheckedChange = { viewModel.setPlayerControlVisibility(showSleepTimer = it) }
        )
        IosSettingsDivider(insetStart = 16.dp)
        IosSettingsSwitchRow(
            title = "Show Favorite Heart Button",
            checked = player.showLikeButton,
            onCheckedChange = { viewModel.setPlayerControlVisibility(showLike = it) }
        )
        IosSettingsDivider(insetStart = 16.dp)
        IosSettingsSwitchRow(
            title = "Show Shuffle Button",
            checked = player.showShuffleButton,
            onCheckedChange = { viewModel.setPlayerControlVisibility(showShuffle = it) }
        )
        IosSettingsDivider(insetStart = 16.dp)
        IosSettingsSwitchRow(
            title = "Show Repeat Button",
            checked = player.showRepeatButton,
            onCheckedChange = { viewModel.setPlayerControlVisibility(showRepeat = it) }
        )
        IosSettingsDivider(insetStart = 16.dp)
        IosSettingsSwitchRow(
            title = "Show Queue Button",
            checked = player.showQueueButton,
            onCheckedChange = { viewModel.setPlayerControlVisibility(showQueue = it) }
        )
        IosSettingsDivider(insetStart = 16.dp)
        IosSettingsSwitchRow(
            title = "Show Up Next Track Strip",
            checked = player.showUpNextPreview,
            onCheckedChange = { viewModel.setPlayerControlVisibility(showUpNext = it) }
        )
    }
}

// ============================================================================================
// 7. MINI PLAYER PAGE
// ============================================================================================
@Composable
private fun MiniPlayerCategoryPage(
    state: AppSettingsState,
    viewModel: CustomizationViewModel
) {
    val mini = state.customization.miniPlayer
    val isExpressive = mini.style == MiniPlayerStyle.EXPRESSIVE

    // Live Mini-Player Preview
    IosSettingsSection(title = "Live Mini-Player Preview") {
        val previewRadius = when (mini.style) {
            MiniPlayerStyle.ROUNDED_CARD -> mini.cornerRadiusDp.coerceAtMost(20).dp
            MiniPlayerStyle.VINTAGE -> 12.dp
            MiniPlayerStyle.MODERN, MiniPlayerStyle.EXPRESSIVE -> (mini.heightDp / 2).coerceIn(24, 34).dp
            else -> mini.cornerRadiusDp.dp
        }
        val previewBg = when {
            isExpressive -> androidx.compose.ui.graphics.lerp(InkElevated, Lime, 0.20f)
            mini.backgroundStyle == MiniPlayerBackgroundStyle.AMOLED_BLACK -> Color.Black
            mini.backgroundStyle == MiniPlayerBackgroundStyle.SOLID_DARK -> InkPanel
            mini.backgroundStyle == MiniPlayerBackgroundStyle.DYNAMIC_GRADIENT -> Lilac.copy(alpha = 0.45f)
            else -> NavPill
        }.copy(alpha = mini.surfaceAlpha)
        val previewArtShape = when {
            isExpressive && mini.expressiveCookieArtwork -> com.geekify.android.ui.player.ScallopedCookieShape
            mini.artworkShape == MiniPlayerArtworkShape.CIRCLE -> CircleShape
            mini.artworkShape == MiniPlayerArtworkShape.ROUNDED -> RoundedCornerShape(10.dp)
            else -> RoundedCornerShape(4.dp)
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(mini.heightDp.dp)
                    .clip(RoundedCornerShape(previewRadius))
                    .background(previewBg)
                    .border(1.dp, Color.White.copy(alpha = 0.14f), RoundedCornerShape(previewRadius))
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = mini.horizontalPaddingDp.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (mini.showArtwork) {
                        Box(
                            modifier = Modifier
                                .size(mini.artworkSizeDp.dp.coerceAtMost((mini.heightDp - 14).dp))
                                .clip(previewArtShape)
                                .background(Brush.linearGradient(listOf(Lime, Lilac))),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(GeekifyIcons.MusicNote, contentDescription = null, tint = OnAccent, modifier = Modifier.size(18.dp))
                        }
                        Spacer(Modifier.width(10.dp))
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Starlight Drift", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                        Text("Geekify Radio", color = Color.White.copy(alpha = 0.7f), fontSize = 11.5.sp, maxLines = 1)
                    }
                    if (mini.showLikeButton) {
                        Icon(GeekifyIcons.Favorite, contentDescription = null, tint = Lime, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                    }
                    if (mini.showPreviousButton) {
                        Icon(GeekifyIcons.SkipPrevious, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                    }
                    if (mini.showPlayPauseButton) {
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(if (isExpressive) RoundedCornerShape(12.dp) else CircleShape)
                                .background(Lime),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(GeekifyIcons.Pause, contentDescription = null, tint = OnAccent, modifier = Modifier.size(18.dp))
                        }
                    }
                    if (mini.showNextButton) {
                        Spacer(Modifier.width(6.dp))
                        Icon(GeekifyIcons.SkipNext, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
                    }
                    if (mini.showQueueButton) {
                        Spacer(Modifier.width(6.dp))
                        Icon(GeekifyIcons.QueueMusic, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
                    }
                    if (mini.showShuffleButton) {
                        Spacer(Modifier.width(6.dp))
                        Icon(GeekifyIcons.Shuffle, contentDescription = null, tint = Lime, modifier = Modifier.size(17.dp))
                    }
                }
                if (mini.showProgressIndicator && !isExpressive && mini.style != MiniPlayerStyle.MODERN) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .padding(horizontal = if (mini.style == MiniPlayerStyle.VINTAGE) 0.dp else 24.dp, vertical = 3.dp)
                            .fillMaxWidth(0.58f)
                            .height(2.dp)
                            .background(Lime)
                    )
                }
            }
        }
    }

    Spacer(Modifier.height(22.dp))

    IosSettingsSection(title = "Mini-Player Style (6 Modes)") {
        MiniPlayerStyle.entries.forEachIndexed { idx, style ->
            if (idx > 0) IosSettingsDivider(insetStart = 16.dp)
            IosSettingsRow(
                title = style.label,
                subtitle = style.subtitle.takeIf { it.isNotBlank() },
                value = if (mini.style == style) "Active" else null,
                selected = mini.style == style,
                showChevron = false,
                onClick = { viewModel.setMiniPlayerStyle(style) }
            )
        }
    }

    Spacer(Modifier.height(22.dp))

    IosSettingsSection(title = "Mini-Player Background") {
        MiniPlayerBackgroundStyle.entries.forEachIndexed { idx, bg ->
            if (idx > 0) IosSettingsDivider(insetStart = 16.dp)
            IosSettingsRow(
                title = bg.label,
                value = if (mini.backgroundStyle == bg) "Active" else null,
                selected = mini.backgroundStyle == bg,
                showChevron = false,
                onClick = { viewModel.setMiniPlayerBackgroundStyle(bg) }
            )
        }
        IosSettingsDivider(insetStart = 16.dp)
        IosSettingsSwitchRow(
            title = "Liquid Glass Surface",
            subtitle = "Apply frosted backdrop glass when using Themed background",
            checked = mini.glassEffect,
            onCheckedChange = { viewModel.setMiniPlayerToggles(glassEffect = it) }
        )
    }

    if (isExpressive) {
        Spacer(Modifier.height(22.dp))
        IosSettingsSection(
            title = "Expressive Mini-Player Features",
            footer = "Metrolist-inspired animated scalloped artwork, circular progress ring, and direct artwork playback control."
        ) {
            IosSettingsSwitchRow(
                title = "Scalloped Expressive Artwork Shape",
                checked = mini.expressiveCookieArtwork,
                onCheckedChange = { viewModel.setMiniPlayerToggles(expressiveCookieArtwork = it) }
            )
            IosSettingsDivider(insetStart = 16.dp)
            IosSettingsSwitchRow(
                title = "Rotating Artwork Shape Motion",
                checked = mini.expressiveShapeMotion,
                enabled = mini.expressiveCookieArtwork,
                onCheckedChange = { viewModel.setMiniPlayerToggles(expressiveShapeMotion = it) }
            )
            IosSettingsDivider(insetStart = 16.dp)
            IosSettingsSwitchRow(
                title = "Circular Progress Ring Overlay",
                checked = mini.expressiveProgressRing,
                onCheckedChange = { viewModel.setMiniPlayerToggles(expressiveProgressRing = it) }
            )
            IosSettingsDivider(insetStart = 16.dp)
            IosSettingsSwitchRow(
                title = "Tap Artwork to Play / Pause",
                checked = mini.expressiveArtworkPlaybackTap,
                onCheckedChange = { viewModel.setMiniPlayerToggles(expressiveArtworkPlaybackTap = it) }
            )
        }
    }

    Spacer(Modifier.height(22.dp))

    IosSettingsSection(title = "Dimensions, Artwork & Surface") {
        MiniPlayerArtworkShape.entries.forEachIndexed { idx, shape ->
            if (idx > 0) IosSettingsDivider(insetStart = 16.dp)
            IosSettingsRow(
                title = "Artwork Shape: ${shape.label}",
                value = if (mini.artworkShape == shape) "Active" else null,
                selected = mini.artworkShape == shape,
                showChevron = false,
                onClick = { viewModel.setMiniPlayerArtworkShape(shape) }
            )
        }
        IosSettingsDivider(insetStart = 16.dp)
        IosSettingsSliderRow(
            title = "Artwork Size",
            valueLabel = "${mini.artworkSizeDp} dp",
            value = mini.artworkSizeDp.toFloat(),
            valueRange = 32f..56f,
            onValueChange = { viewModel.setMiniPlayerArtworkSizeDp(it.roundToInt()) }
        )
        IosSettingsDivider(insetStart = 16.dp)
        IosSettingsSliderRow(
            title = "Bar Height",
            valueLabel = "${mini.heightDp} dp",
            value = mini.heightDp.toFloat(),
            valueRange = 52f..76f,
            onValueChange = { viewModel.setMiniPlayerHeightDp(it.roundToInt()) }
        )
        IosSettingsDivider(insetStart = 16.dp)
        IosSettingsSliderRow(
            title = "Corner Radius",
            valueLabel = "${mini.cornerRadiusDp} dp",
            value = mini.cornerRadiusDp.toFloat(),
            valueRange = 12f..36f,
            onValueChange = { viewModel.setMiniPlayerCornerRadiusDp(it.roundToInt()) }
        )
        IosSettingsDivider(insetStart = 16.dp)
        IosSettingsSliderRow(
            title = "Inner Padding",
            valueLabel = "${mini.horizontalPaddingDp} dp",
            value = mini.horizontalPaddingDp.toFloat(),
            valueRange = 4f..20f,
            onValueChange = { viewModel.setMiniPlayerHorizontalPaddingDp(it.roundToInt()) }
        )
        IosSettingsDivider(insetStart = 16.dp)
        IosSettingsSliderRow(
            title = "Surface Opacity",
            valueLabel = "${(mini.surfaceAlpha * 100).roundToInt()}%",
            value = mini.surfaceAlpha,
            valueRange = 0.30f..1.0f,
            onValueChange = viewModel::setMiniPlayerSurfaceAlpha
        )
        IosSettingsDivider(insetStart = 16.dp)
        IosSettingsSliderRow(
            title = "Shadow Elevation",
            valueLabel = "${mini.elevationDp} dp",
            value = mini.elevationDp.toFloat(),
            valueRange = 0f..16f,
            onValueChange = { viewModel.setMiniPlayerElevationDp(it.roundToInt()) }
        )
    }

    Spacer(Modifier.height(22.dp))

    IosSettingsSection(title = "Visible Mini-Player Controls") {
        IosSettingsSwitchRow(
            title = "Show Album Artwork Thumbnail",
            checked = mini.showArtwork,
            onCheckedChange = { viewModel.setMiniPlayerToggles(showArtwork = it) }
        )
        IosSettingsDivider(insetStart = 16.dp)
        IosSettingsSwitchRow(
            title = "Show Progress Indicator",
            checked = mini.showProgressIndicator,
            onCheckedChange = { viewModel.setMiniPlayerToggles(showProgress = it) }
        )
        IosSettingsDivider(insetStart = 16.dp)
        IosSettingsSwitchRow(
            title = "Show Previous Track Button",
            checked = mini.showPreviousButton,
            onCheckedChange = { viewModel.setMiniPlayerToggles(showPrevious = it) }
        )
        IosSettingsDivider(insetStart = 16.dp)
        IosSettingsSwitchRow(
            title = "Show Play / Pause Button",
            subtitle = "If hidden, tapping the artwork toggles playback",
            checked = mini.showPlayPauseButton,
            onCheckedChange = { viewModel.setMiniPlayerToggles(showPlayPause = it) }
        )
        IosSettingsDivider(insetStart = 16.dp)
        IosSettingsSwitchRow(
            title = "Show Next Track Button",
            checked = mini.showNextButton,
            onCheckedChange = { viewModel.setMiniPlayerToggles(showNext = it) }
        )
        IosSettingsDivider(insetStart = 16.dp)
        IosSettingsSwitchRow(
            title = "Show Favorite Heart Button",
            checked = mini.showLikeButton,
            onCheckedChange = { viewModel.setMiniPlayerToggles(showLike = it) }
        )
        IosSettingsDivider(insetStart = 16.dp)
        IosSettingsSwitchRow(
            title = "Show Shuffle Button",
            checked = mini.showShuffleButton,
            onCheckedChange = { viewModel.setMiniPlayerToggles(showShuffle = it) }
        )
        IosSettingsDivider(insetStart = 16.dp)
        IosSettingsSwitchRow(
            title = "Show Queue Button",
            checked = mini.showQueueButton,
            onCheckedChange = { viewModel.setMiniPlayerToggles(showQueue = it) }
        )
    }
}

// ============================================================================================
// 8. GESTURES PAGE
// ============================================================================================
@Composable
private fun GesturesCategoryPage(
    state: AppSettingsState,
    viewModel: CustomizationViewModel
) {
    val g = state.customization.gestures

    IosSettingsSection(
        title = "Artwork Double-Tap Seeking",
        footer = if (g.artworkDoubleTapSkipEnabled) {
            "Double-tap the left side of album artwork to rewind ${g.artworkSkipDurationSeconds}s, or the right side to fast-forward ${g.artworkSkipDurationSeconds}s."
        } else {
            "Enable Double-Tap Artwork to Skip to configure skip duration and progressive skipping."
        }
    ) {
        IosSettingsSwitchRow(
            title = "Double-Tap Artwork to Skip",
            subtitle = "Double-tap left or right side of album artwork to rewind or fast-forward",
            checked = g.artworkDoubleTapSkipEnabled,
            onCheckedChange = { viewModel.setGestureToggles(artworkDoubleTapSkipEnabled = it) }
        )
        if (g.artworkDoubleTapSkipEnabled) {
            IosSettingsDivider(insetStart = 16.dp)
            Column(
                modifier = Modifier.fillMaxWidth()
            ) {
                IosSettingsSliderRow(
                    title = "Skip Duration",
                    valueLabel = "${g.artworkSkipDurationSeconds} seconds",
                    value = g.artworkSkipDurationSeconds.toFloat(),
                    valueRange = 5f..30f,
                    onValueChange = { raw ->
                        val snapped = GestureCustomizationConfig.normalizeSkipDurationSeconds(raw.roundToInt())
                        if (snapped != g.artworkSkipDurationSeconds) {
                            viewModel.setGestureToggles(artworkSkipDurationSeconds = snapped)
                        }
                    }
                )
                Text(
                    text = "Choose how many seconds to skip when double-tapping the album artwork (5–30s in 5s steps).",
                    color = TextSecondary,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp)
                )
            }
            IosSettingsDivider(insetStart = 16.dp)
            IosSettingsSwitchRow(
                title = "Progressive Skip",
                subtitle = "Quickly repeat double-taps within 1 second to skip progressively longer intervals (1x, 2x, 3x…)",
                checked = g.artworkProgressiveSkipEnabled,
                onCheckedChange = { viewModel.setGestureToggles(artworkProgressiveSkipEnabled = it) }
            )
        }
    }

    Spacer(Modifier.height(22.dp))

    IosSettingsSection(
        title = "Player & Queue Touch Gestures",
        footer = "Adjust gesture triggers and swipe distance sensitivity."
    ) {
        IosSettingsSwitchRow(
            title = "Swipe Album Artwork to Skip Tracks",
            subtitle = "Horizontal swipe left/right on expanded player cover skips next/previous",
            checked = g.swipeArtworkToSkip,
            onCheckedChange = { viewModel.setGestureToggles(swipeArtworkToSkip = it) }
        )
        IosSettingsDivider(insetStart = 16.dp)
        IosSettingsSwitchRow(
            title = "Swipe Down to Collapse Player",
            subtitle = "Vertical pull-down collapses the expanded player back to the mini-player",
            checked = g.swipeDownToCollapsePlayer,
            onCheckedChange = { viewModel.setGestureToggles(swipeDownToCollapse = it) }
        )
        IosSettingsDivider(insetStart = 16.dp)
        IosSettingsSwitchRow(
            title = "Swipe Down to Hide Paused Mini-Player",
            subtitle = "Dismiss the floating mini-player pill while playback is paused",
            checked = g.swipeToDismissMiniPlayer,
            onCheckedChange = { viewModel.setGestureToggles(swipeToDismissMini = it) }
        )
        IosSettingsDivider(insetStart = 16.dp)
        IosSettingsSwitchRow(
            title = "Swipe Queue Items to Remove",
            checked = g.swipeToRemoveQueueItem,
            onCheckedChange = { viewModel.setGestureToggles(swipeToRemoveQueue = it) }
        )
        IosSettingsDivider(insetStart = 16.dp)
        IosSettingsSwitchRow(
            title = "Tactile Haptic Feedback",
            checked = g.hapticFeedback,
            onCheckedChange = { viewModel.setGestureToggles(haptics = it) }
        )
        IosSettingsDivider(insetStart = 16.dp)
        IosSettingsSliderRow(
            title = "Swipe Gesture Sensitivity",
            valueLabel = "${"%.2f".format(g.gestureSensitivity)}x",
            value = g.gestureSensitivity,
            valueRange = 0.6f..1.5f,
            onValueChange = { viewModel.setGestureToggles(sensitivity = it) }
        )
    }
}

// ============================================================================================
// 9. TYPOGRAPHY & DENSITY PAGE
// ============================================================================================
@Composable
private fun TypographyCategoryPage(
    state: AppSettingsState,
    viewModel: CustomizationViewModel,
    onOpenFontsManager: () -> Unit
) {
    val typo = state.customization.typography

    IosSettingsSection(title = "Font Family") {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = typo.resolvedDisplayName,
                color = Lime,
                fontSize = 22.sp,
                fontWeight = FontWeight.ExtraBold,
                fontFamily = AppFont
            )
            Text(
                text = "Aa Bb Cc · 0123456789",
                color = TextPrimary,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                fontFamily = AppFont
            )
            Text(
                text = "The quick brown fox jumps over the lazy dog.",
                color = TextSecondary,
                fontSize = 13.sp,
                fontWeight = FontWeight.Normal,
                fontFamily = AppFont
            )
        }
        IosSettingsDivider(insetStart = 16.dp)
        IosSettingsRow(
            title = "Fonts",
            subtitle = "Import TTF/OTF, download fonts, or reset",
            value = typo.resolvedDisplayName,
            icon = GeekifyIcons.Translate,
            tileColor = IosPalette.systemOrange,
            showChevron = true,
            onClick = onOpenFontsManager
        )
    }

    Spacer(Modifier.height(22.dp))

    IosSettingsSection(title = "Text Scale & Hierarchy") {
        IosSettingsSliderRow(
            title = "Global Text Scale",
            valueLabel = "${(typo.fontScale * 100).roundToInt()}%",
            value = typo.fontScale,
            valueRange = 0.85f..1.25f,
            onValueChange = viewModel::setFontScale
        )
        IosSettingsDivider(insetStart = 16.dp)
        IosSettingsSliderRow(
            title = "Heading & Title Scale",
            valueLabel = "${(typo.titleScale * 100).roundToInt()}%",
            value = typo.titleScale,
            valueRange = 0.85f..1.25f,
            onValueChange = viewModel::setTitleScale
        )
        IosSettingsDivider(insetStart = 16.dp)
        IosSettingsSliderRow(
            title = "Subtitle & Metadata Scale",
            valueLabel = "${(typo.subtitleScale * 100).roundToInt()}%",
            value = typo.subtitleScale,
            valueRange = 0.85f..1.25f,
            onValueChange = viewModel::setSubtitleScale
        )
    }

    Spacer(Modifier.height(22.dp))

    IosSettingsSection(title = "Display Density") {
        DisplayDensityScale.entries.forEachIndexed { idx, scale ->
            if (idx > 0) IosSettingsDivider(insetStart = 16.dp)
            IosSettingsRow(
                title = scale.label,
                value = if (typo.displayDensity == scale) "Selected" else null,
                selected = typo.displayDensity == scale,
                showChevron = false,
                onClick = { viewModel.setDisplayDensity(scale) }
            )
        }
        IosSettingsDivider(insetStart = 16.dp)
        IosSettingsSwitchRow(
            title = "Apply Scaling in Landscape",
            checked = typo.enableLandscapeScaling,
            onCheckedChange = viewModel::setLandscapeScaling
        )
    }
}

// ============================================================================================
// 10. LYRICS APPEARANCE PAGE
// ============================================================================================
@Composable
private fun LyricsCategoryPage(
    state: AppSettingsState,
    viewModel: CustomizationViewModel
) {
    val lyrics = state.customization.lyrics
    val playerBg = state.customization.player.backgroundStyle

    IosSettingsSection(
        title = "Live Lyrics Preview",
        footer = "Interactive sample timeline using the real Sohne Breit lyrics renderer. Tap any line or pause/replay without affecting playback."
    ) {
        com.geekify.android.ui.player.LiveLyricsPreviewCard(
            config = lyrics,
            accentColor = Lime,
            playerBackgroundStyle = playerBg
        )
    }

    Spacer(Modifier.height(22.dp))

    IosSettingsSection(
        title = "Lyrics Animation Style",
        footer = "Each preset provides distinct motion physics, glow, and word-highlighting behavior."
    ) {
        LyricsAnimationStyle.entries.forEachIndexed { idx, style ->
            if (idx > 0) IosSettingsDivider(insetStart = 16.dp)
            IosSettingsRow(
                title = style.label,
                subtitle = style.subtitle,
                value = if (lyrics.animationStyle == style) "Active" else null,
                selected = lyrics.animationStyle == style,
                showChevron = false,
                onClick = { viewModel.setLyricsAnimationStyle(style) }
            )
        }
    }

    Spacer(Modifier.height(22.dp))

    IosSettingsSection(title = "Alignment (Normal & Active Lines)") {
        LyricsTextAlignment.entries.forEachIndexed { idx, alignment ->
            if (idx > 0) IosSettingsDivider(insetStart = 16.dp)
            IosSettingsRow(
                title = "Normal Lines: ${alignment.label}",
                value = if (lyrics.alignment == alignment) "Active" else null,
                selected = lyrics.alignment == alignment,
                showChevron = false,
                onClick = { viewModel.setLyricsAlignment(alignment) }
            )
        }
        IosSettingsDivider(insetStart = 16.dp)
        LyricsTextAlignment.entries.forEachIndexed { idx, alignment ->
            if (idx > 0) IosSettingsDivider(insetStart = 16.dp)
            IosSettingsRow(
                title = "Active Line: ${alignment.label}",
                value = if (lyrics.activeAlignment == alignment) "Active" else null,
                selected = lyrics.activeAlignment == alignment,
                showChevron = false,
                onClick = { viewModel.setLyricsActiveAlignment(alignment) }
            )
        }
        IosSettingsDivider(insetStart = 16.dp)
        IosSettingsSwitchRow(
            title = "Respect Provider Positioning",
            subtitle = "Align backing vocals and duet cues as positioned by the lyrics source",
            checked = lyrics.respectProviderPositioning,
            onCheckedChange = { viewModel.setLyricsToggles(respectProviderPositioning = it) }
        )
    }

    Spacer(Modifier.height(22.dp))

    IosSettingsSection(title = "Typography & Colors") {
        IosSettingsSliderRow(
            title = "Lyrics Font Size",
            valueLabel = "${lyrics.fontSizeSp.roundToInt()} sp",
            value = lyrics.fontSizeSp,
            valueRange = 14f..34f,
            onValueChange = viewModel::setLyricsFontSizeSp
        )
        IosSettingsDivider(insetStart = 16.dp)
        IosSettingsSliderRow(
            title = "Line Spacing Multiplier",
            valueLabel = "${"%.2f".format(lyrics.lineSpacingMultiplier)}x",
            value = lyrics.lineSpacingMultiplier,
            valueRange = 1.1f..2.2f,
            onValueChange = viewModel::setLyricsLineSpacing
        )
        IosSettingsDivider(insetStart = 16.dp)
        IosSettingsSliderRow(
            title = "Subtitle / Translation Font Size",
            valueLabel = "${lyrics.subtitleFontSizeSp.roundToInt()} sp",
            value = lyrics.subtitleFontSizeSp,
            valueRange = 10f..28f,
            onValueChange = viewModel::setLyricsSubtitleFontSizeSp
        )
        IosSettingsDivider(insetStart = 16.dp)
        IosSettingsSwitchRow(
            title = "Neutral White/Black Lyrics Color",
            subtitle = "Use crisp neutral white text instead of themed accent tinting",
            checked = lyrics.useNeutralColor,
            onCheckedChange = { viewModel.setLyricsToggles(neutralColor = it) }
        )
        IosSettingsDivider(insetStart = 16.dp)
        IosSettingsSwitchRow(
            title = "Accent Color Active Line Highlight",
            subtitle = "Tint the currently sung lyric line with your active theme accent color",
            checked = lyrics.activeColorFollowsAccent,
            enabled = !lyrics.useNeutralColor,
            onCheckedChange = { viewModel.setLyricsToggles(accentColor = it) }
        )
    }

    Spacer(Modifier.height(22.dp))

    IosSettingsSection(
        title = "Behavior, Sync & Visual Effects",
        footer = "Global sync offset applies on top of each track's individual offset without overwriting it."
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Global Lyrics Offset", color = IosPalette.label, fontSize = 15.5.sp, fontWeight = FontWeight.Medium)
                Text(
                    text = "${if (lyrics.globalOffsetMs >= 0) "+" else ""}%.2f s".format(lyrics.globalOffsetMs / 1000f),
                    color = Lime,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = { viewModel.setLyricsGlobalOffsetMs(lyrics.globalOffsetMs - 250L) }) {
                    Text("-0.25s", color = Lime, fontSize = 12.sp)
                }
                TextButton(onClick = { viewModel.setLyricsGlobalOffsetMs(0L) }) {
                    Text("Reset", color = TextSecondary, fontSize = 12.sp)
                }
                TextButton(onClick = { viewModel.setLyricsGlobalOffsetMs(lyrics.globalOffsetMs + 250L) }) {
                    Text("+0.25s", color = Lime, fontSize = 12.sp)
                }
            }
        }
        IosSettingsDivider(insetStart = 16.dp)
        IosSettingsSwitchRow(
            title = "Automatic Lyrics Scrolling",
            subtitle = "Keep the active lyric line smoothly centered during playback",
            checked = lyrics.autoScroll,
            onCheckedChange = { viewModel.setLyricsToggles(autoScroll = it) }
        )
        IosSettingsDivider(insetStart = 16.dp)
        IosSettingsSwitchRow(
            title = "Tap Line to Seek",
            subtitle = "Jump playback directly to any tapped lyric timestamp",
            checked = lyrics.tapToSeek,
            onCheckedChange = { viewModel.setLyricsToggles(tapToSeek = it) }
        )
        IosSettingsDivider(insetStart = 16.dp)
        IosSettingsSwitchRow(
            title = "Prefer Word-Synchronized Lyrics",
            subtitle = "Automatically select syllable/word-timed lyrics when available",
            checked = lyrics.preferWordSynced,
            onCheckedChange = { viewModel.setLyricsToggles(preferWordSynced = it) }
        )
        IosSettingsDivider(insetStart = 16.dp)
        IosSettingsSwitchRow(
            title = "Highlight by Exact Playback Timing",
            subtitle = "Disable for a subtle 180ms visual anticipation lead",
            checked = lyrics.activeByRealTiming,
            onCheckedChange = { viewModel.setLyricsToggles(activeByRealTiming = it) }
        )
        IosSettingsDivider(insetStart = 16.dp)
        IosSettingsSwitchRow(
            title = "Show Live Lyric Strip on Player",
            subtitle = "Display the currently sung line directly on the Now Playing screen",
            checked = lyrics.showLyricsOnPlayer,
            onCheckedChange = { viewModel.setLyricsToggles(showLyricsOnPlayer = it) }
        )
        IosSettingsDivider(insetStart = 16.dp)
        IosSettingsSwitchRow(
            title = "Open Fullscreen Lyrics by Default",
            checked = lyrics.fullscreenDefault,
            onCheckedChange = { viewModel.setLyricsToggles(fullscreenDefault = it) }
        )
        IosSettingsDivider(insetStart = 16.dp)
        IosSettingsSwitchRow(
            title = "Hide Status Bar in Fullscreen Lyrics",
            checked = lyrics.hideStatusBarInFullscreen,
            onCheckedChange = { viewModel.setLyricsToggles(hideStatusBarInFullscreen = it) }
        )
        IosSettingsDivider(insetStart = 16.dp)
        IosSettingsSwitchRow(
            title = "Progressive Blur on Inactive Lines",
            subtitle = "Softly blur distant lines for focused depth of field (Android 12+)",
            checked = lyrics.progressiveBlur,
            onCheckedChange = { viewModel.setLyricsToggles(progressiveBlur = it) }
        )
        if (playerBg == PlayerBackgroundStyle.LIQUID_GLASS) {
            IosSettingsDivider(insetStart = 16.dp)
            IosSettingsSwitchRow(
                title = "Plus Lighter Glow Blending",
                subtitle = "Additive luminous glow over Liquid Glass player background",
                checked = lyrics.plusLighterBlend,
                onCheckedChange = { viewModel.setLyricsToggles(plusLighterBlend = it) }
            )
        }
    }

    Spacer(Modifier.height(22.dp))

    IosSettingsSection(title = "Translation, Romanization & Karaoke") {
        IosSettingsSwitchRow(
            title = "Show Original Lyrics",
            checked = lyrics.showOriginalLyrics,
            onCheckedChange = { viewModel.setLyricsToggles(showOriginal = it) }
        )
        IosSettingsDivider(insetStart = 16.dp)
        IosSettingsSwitchRow(
            title = "Show Translated Lyrics",
            subtitle = "Enable on-demand line-by-line translation in the lyrics view",
            checked = lyrics.showTranslation,
            onCheckedChange = { viewModel.setLyricsToggles(showTranslation = it) }
        )
        IosSettingsDivider(insetStart = 16.dp)
        IosSettingsSwitchRow(
            title = "Show Offline Phonetic Romanization",
            subtitle = "Transliterate Korean, Japanese, Cyrillic & CJK scripts offline",
            checked = lyrics.showRomanization,
            onCheckedChange = { viewModel.setLyricsToggles(showRomanization = it) }
        )
        IosSettingsDivider(insetStart = 16.dp)
        IosSettingsSwitchRow(
            title = "Word-by-Word Karaoke Highlight",
            checked = lyrics.wordLevelHighlight,
            onCheckedChange = { viewModel.setLyricsToggles(wordHighlight = it) }
        )
    }
}

// ============================================================================================
// 11. PLAYBACK & AUDIO PREFERENCES PAGE
// ============================================================================================
@Composable
private fun PlaybackPreferencesCategoryPage(
    state: AppSettingsState,
    audio: AudioSettings,
    viewModel: CustomizationViewModel
) {
    IosSettingsSection(title = "Streaming Audio Quality") {
        AudioQualityPreference.entries.forEachIndexed { idx, q ->
            if (idx > 0) IosSettingsDivider(insetStart = 16.dp)
            IosSettingsRow(
                title = q.label,
                subtitle = q.subtitle,
                value = if (state.audioQuality == q) "Active" else null,
                selected = state.audioQuality == q,
                showChevron = false,
                onClick = { viewModel.setAudioQuality(q) }
            )
        }
    }

    Spacer(Modifier.height(22.dp))

    IosSettingsSection(
        title = "DSP, Crossfade & Loudness",
        footer = "Dual-player crossfade overlaps outgoing and incoming tracks smoothly."
    ) {
        IosSettingsSliderRow(
            title = "Crossfade Duration",
            valueLabel = if (audio.crossfadeSeconds == 0) "Off" else "${audio.crossfadeSeconds} s",
            value = audio.crossfadeSeconds.toFloat(),
            valueRange = 0f..12f,
            onValueChange = { viewModel.setCrossfadeSeconds(it.roundToInt()) }
        )
        IosSettingsDivider(insetStart = 16.dp)
        IosSettingsSwitchRow(
            title = "Skip Silence",
            subtitle = "Automatically fast-forward through silent passages",
            checked = audio.skipSilence,
            onCheckedChange = viewModel::setSkipSilence
        )
        IosSettingsDivider(insetStart = 16.dp)
        IosSettingsSwitchRow(
            title = "Loudness Normalization",
            subtitle = "Balance volume levels across quiet and loud tracks",
            checked = audio.normalize,
            onCheckedChange = viewModel::setAudioNormalization
        )
        IosSettingsDivider(insetStart = 16.dp)
        IosSettingsSwitchRow(
            title = "Bass Boost",
            subtitle = "Enhance low-end 60 Hz and 230 Hz biquad filter response",
            checked = audio.eqEnabled && audio.preset == "Bass boost",
            onCheckedChange = viewModel::setBassBoostEnabled
        )
    }

    Spacer(Modifier.height(22.dp))

    IosSettingsSection(title = "Queue & Session Behavior") {
        IosSettingsSwitchRow(
            title = "Persistent Queue & Playback Restoration",
            subtitle = "Restore your queue and track position across app restarts",
            checked = state.persistentQueue,
            onCheckedChange = viewModel::setPersistentQueue
        )
        IosSettingsDivider(insetStart = 16.dp)
        IosSettingsSwitchRow(
            title = "Endless Autoplay Recommendations",
            subtitle = "Automatically queue similar songs when your queue finishes",
            checked = state.autoplayRelated,
            onCheckedChange = viewModel::setAutoplayRelated
        )
        IosSettingsDivider(insetStart = 16.dp)
        IosSettingsSwitchRow(
            title = "Remember Shuffle & Repeat Modes",
            checked = state.rememberShuffleRepeat,
            onCheckedChange = viewModel::setRememberShuffleRepeat
        )
        IosSettingsDivider(insetStart = 16.dp)
        IosSettingsSwitchRow(
            title = "Auto-Skip Unplayable Streams",
            checked = state.autoSkipOnError,
            onCheckedChange = viewModel::setAutoSkipOnError
        )
        IosSettingsDivider(insetStart = 16.dp)
        IosSettingsSwitchRow(
            title = "Keep Screen On While Expanded Player Is Open",
            checked = state.keepScreenOn,
            onCheckedChange = viewModel::setKeepScreenOn
        )
        IosSettingsDivider(insetStart = 16.dp)
        IosSettingsSwitchRow(
            title = "Stop Playback When App Is Swiped Away",
            checked = state.stopPlaybackOnTaskRemoved,
            onCheckedChange = viewModel::setStopOnTaskRemoved
        )
    }
}

// ============================================================================================
// 12. WIDGETS PAGE
// ============================================================================================
@Composable
private fun WidgetsCategoryPage(
    state: AppSettingsState,
    viewModel: CustomizationViewModel
) {
    val widget = state.customization.widgets

    IosSettingsSection(title = "Home Screen Widget Live Preview") {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
                .clip(RoundedCornerShape(22.dp))
                .background(Color(widget.backgroundStyle.argbHex))
                .border(1.dp, Color.White.copy(alpha = 0.12f), RoundedCornerShape(22.dp))
                .padding(14.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (widget.showArtwork) {
                    Box(
                        modifier = Modifier
                            .size(52.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(Brush.linearGradient(listOf(Lime, Lilac))),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(GeekifyIcons.MusicNote, contentDescription = null, tint = OnAccent)
                    }
                    Spacer(Modifier.width(12.dp))
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text("Geekify Now Playing", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    if (widget.showArtist) {
                        Text("Home Screen Widget", color = Color.White.copy(alpha = 0.72f), fontSize = 12.sp)
                    }
                }
                if (widget.showPrevNext) {
                    Icon(GeekifyIcons.SkipPrevious, contentDescription = null, tint = Color.White, modifier = Modifier.size(22.dp))
                    Spacer(Modifier.width(8.dp))
                }
                Icon(GeekifyIcons.PlayArrow, contentDescription = null, tint = Lime, modifier = Modifier.size(28.dp))
                if (widget.showPrevNext) {
                    Spacer(Modifier.width(8.dp))
                    Icon(GeekifyIcons.SkipNext, contentDescription = null, tint = Color.White, modifier = Modifier.size(22.dp))
                }
            }
        }
    }

    Spacer(Modifier.height(22.dp))

    IosSettingsSection(title = "Widget Surface Theme") {
        WidgetBackgroundStyle.entries.forEachIndexed { idx, style ->
            if (idx > 0) IosSettingsDivider(insetStart = 16.dp)
            IosSettingsRow(
                title = style.label,
                value = if (widget.backgroundStyle == style) "Active" else null,
                selected = widget.backgroundStyle == style,
                showChevron = false,
                onClick = { viewModel.setWidgetBackgroundStyle(style) }
            )
        }
    }

    Spacer(Modifier.height(22.dp))

    IosSettingsSection(title = "Widget Elements") {
        IosSettingsSwitchRow(
            title = "Show Album Artwork",
            checked = widget.showArtwork,
            onCheckedChange = { viewModel.setWidgetToggles(showArtwork = it) }
        )
        IosSettingsDivider(insetStart = 16.dp)
        IosSettingsSwitchRow(
            title = "Show Artist Subtitle",
            checked = widget.showArtist,
            onCheckedChange = { viewModel.setWidgetToggles(showArtist = it) }
        )
        IosSettingsDivider(insetStart = 16.dp)
        IosSettingsSwitchRow(
            title = "Show Previous & Next Track Buttons",
            checked = widget.showPrevNext,
            onCheckedChange = { viewModel.setWidgetToggles(showPrevNext = it) }
        )
    }
}
