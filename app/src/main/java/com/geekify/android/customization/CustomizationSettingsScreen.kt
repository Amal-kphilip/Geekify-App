package com.geekify.android.customization

import androidx.activity.compose.BackHandler
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
    onOpenEqualizer: () -> Unit = {},
    initialCategory: CustomizationCategory? = null,
    viewModel: CustomizationViewModel = koinViewModel()
) {
    val state by viewModel.appSettings.collectAsState()
    val audioState by viewModel.audioSettings.collectAsState()
    val toastMessage by viewModel.toastMessage.collectAsState()
    val bottomInset = LocalBottomInset.current

    var activeCategory by rememberSaveable(initialCategory) { mutableStateOf(initialCategory) }
    var showResetAllDialog by rememberSaveable { mutableStateOf(false) }
    val rootScroll = rememberSaveable(saver = ScrollState.Saver) { ScrollState(0) }

    BackHandler(enabled = activeCategory != null) {
        activeCategory = null
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
                    "Reset All Personalization?",
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
        targetState = activeCategory,
        transitionSpec = {
            if (targetState != null) {
                (slideInHorizontally(tween(240)) { it / 3 } + fadeIn(tween(200)))
                    .togetherWith(slideOutHorizontally(tween(200)) { -it / 4 } + fadeOut(tween(150)))
            } else {
                (slideInHorizontally(tween(240)) { -it / 4 } + fadeIn(tween(200)))
                    .togetherWith(slideOutHorizontally(tween(200)) { it / 3 } + fadeOut(tween(150)))
            }
        },
        label = "customizationCategoryNav"
    ) { category ->
        if (category == null) {
            IosSettingsPageScaffold(
                title = "Personalization Studio",
                scrollState = rootScroll,
                onBack = onBack,
                bottomPadding = bottomInset + 40.dp
            ) {
                AnimatedVisibility(visible = toastMessage != null) {
                    toastMessage?.let { msg ->
                        CustomizationToastBanner(message = msg, onDismiss = viewModel::clearToast)
                    }
                }

                LiveStudioHeroCard(state = state)

                Spacer(Modifier.height(22.dp))

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
                    title = "Layout, Navigation & Structure",
                    footer = "Control home shelves, card dimensions, bottom navigation tabs, and typography scale."
                ) {
                    IosSettingsRow(
                        title = CustomizationCategory.HOME_SCREEN.title,
                        subtitle = CustomizationCategory.HOME_SCREEN.subtitle,
                        value = state.customization.home.layoutMode.label,
                        icon = GeekifyIcons.Home,
                        tileColor = IosPalette.systemBlue,
                        onClick = { activeCategory = CustomizationCategory.HOME_SCREEN }
                    )
                    IosSettingsDivider()
                    IosSettingsRow(
                        title = CustomizationCategory.NAVIGATION.title,
                        subtitle = CustomizationCategory.NAVIGATION.subtitle,
                        value = "${state.customization.navigation.safeVisibleTabs.size} tabs",
                        icon = GeekifyIcons.Navigation,
                        tileColor = IosPalette.systemTeal,
                        onClick = { activeCategory = CustomizationCategory.NAVIGATION }
                    )
                    IosSettingsDivider()
                    IosSettingsRow(
                        title = CustomizationCategory.TYPOGRAPHY_DENSITY.title,
                        subtitle = CustomizationCategory.TYPOGRAPHY_DENSITY.subtitle,
                        value = state.customization.typography.fontChoice.label,
                        icon = GeekifyIcons.Translate,
                        tileColor = IosPalette.systemOrange,
                        onClick = { activeCategory = CustomizationCategory.TYPOGRAPHY_DENSITY }
                    )
                }

                Spacer(Modifier.height(22.dp))

                IosSettingsSection(
                    title = "Player, Lyrics & Interaction",
                    footer = "Switch between 6 player layouts, configure the mini-player pill, gestures, and synchronized lyrics."
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
                        title = CustomizationCategory.LYRICS_APPEARANCE.title,
                        subtitle = CustomizationCategory.LYRICS_APPEARANCE.subtitle,
                        value = "${state.customization.lyrics.fontSizeSp.roundToInt()} sp",
                        icon = GeekifyIcons.Lyrics,
                        tileColor = IosPalette.systemMint,
                        onClick = { activeCategory = CustomizationCategory.LYRICS_APPEARANCE }
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
                    title = "Audio, Widgets & Launcher",
                    footer = "Fine-tune crossfade, silence skipping, home screen widgets, and launcher icon."
                ) {
                    IosSettingsRow(
                        title = CustomizationCategory.PLAYBACK_PREFERENCES.title,
                        subtitle = CustomizationCategory.PLAYBACK_PREFERENCES.subtitle,
                        value = state.audioQuality.label,
                        icon = GeekifyIcons.GraphicEq,
                        tileColor = IosPalette.systemRed,
                        onClick = { activeCategory = CustomizationCategory.PLAYBACK_PREFERENCES }
                    )
                    IosSettingsDivider()
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
                    footer = "Restore all visual, layout, and player personalization settings to their original Geekify defaults."
                ) {
                    IosSettingsRow(
                        title = "Reset All Personalization",
                        subtitle = "Restore default theme, glass, layout, player, and typography",
                        icon = GeekifyIcons.RestartAlt,
                        tileColor = IosPalette.systemRed,
                        destructive = true,
                        showChevron = false,
                        onClick = { showResetAllDialog = true }
                    )
                }
            }
        } else {
            val detailScroll = rememberSaveable(
                "cust_cat_${category.name}",
                saver = ScrollState.Saver
            ) { ScrollState(0) }

            IosSettingsPageScaffold(
                title = category.title,
                scrollState = detailScroll,
                onBack = { activeCategory = null },
                bottomPadding = bottomInset + 40.dp
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
                    CustomizationCategory.TYPOGRAPHY_DENSITY -> TypographyCategoryPage(state, viewModel)
                    CustomizationCategory.LYRICS_APPEARANCE -> LyricsCategoryPage(state, viewModel)
                    CustomizationCategory.PLAYBACK_PREFERENCES -> PlaybackPreferencesCategoryPage(
                        state = state,
                        audio = audioState,
                        viewModel = viewModel,
                        onOpenEqualizer = onOpenEqualizer
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
                        text = "LIVE PERSONALIZATION PREVIEW",
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
        title = "Dark Surface Variant",
        footer = "AMOLED Black uses true #000000 pixels to save battery on OLED displays."
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

    IosSettingsSection(title = "Artwork Color Extraction") {
        IosSettingsSwitchRow(
            title = "Dynamic Now Playing Artwork Accent",
            subtitle = "Extract dominant album artwork colors for the expanded player ambient glow",
            checked = theme.dynamicPlayerAccent,
            onCheckedChange = viewModel::setDynamicPlayerAccent,
            icon = GeekifyIcons.Palette,
            tileColor = IosPalette.systemPink
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

    IosSettingsSection(
        title = "Liquid Glass Presets",
        footer = "Select a curated optical profile or fine-tune individual refraction sliders below."
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

    IosSettingsSection(title = "Master & Surface Toggles") {
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
        IosSettingsSliderRow(
            title = "Surface Tint Opacity",
            valueLabel = "${(glass.tintAlpha * 100).roundToInt()}%",
            value = glass.tintAlpha,
            valueRange = 0f..0.60f,
            onValueChange = viewModel::setGlassTintAlpha
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
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = section.label,
                        color = if (isVisible) IosPalette.label else IosPalette.secondaryLabel,
                        fontSize = 15.5.sp,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = if (isVisible) "Position ${idx + 1}" else "Hidden",
                        color = IosPalette.secondaryLabel,
                        fontSize = 12.sp
                    )
                }
                TextButton(
                    onClick = { viewModel.moveHomeSection(section, -1) },
                    enabled = idx > 0
                ) {
                    Text("↑", color = if (idx > 0) Lime else IosPalette.tertiaryLabel, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                }
                TextButton(
                    onClick = { viewModel.moveHomeSection(section, 1) },
                    enabled = idx < home.sectionOrder.lastIndex
                ) {
                    Text("↓", color = if (idx < home.sectionOrder.lastIndex) Lime else IosPalette.tertiaryLabel, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                }
                IosSwitch(
                    checked = isVisible,
                    onCheckedChange = { viewModel.toggleHomeSectionVisible(section, it) }
                )
            }
        }
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
    val visibleTabs = nav.safeVisibleTabs

    IosSettingsSection(
        title = "Bottom Navigation Destinations",
        footer = "At least one primary destination (Home or Library) always remains enabled so you can never get locked out."
    ) {
        NavTabId.DEFAULT_TABS.forEachIndexed { idx, tab ->
            if (idx > 0) IosSettingsDivider(insetStart = 16.dp)
            val isVisible = tab in visibleTabs
            val pos = visibleTabs.indexOf(tab)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = tab.label,
                        color = IosPalette.label,
                        fontSize = 15.5.sp,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = if (isVisible) "Tab ${pos + 1}" else "Hidden",
                        color = IosPalette.secondaryLabel,
                        fontSize = 12.sp
                    )
                }
                if (isVisible) {
                    TextButton(
                        onClick = { viewModel.moveNavTab(tab, -1) },
                        enabled = pos > 0
                    ) {
                        Text("↑", color = if (pos > 0) Lime else IosPalette.tertiaryLabel, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    }
                    TextButton(
                        onClick = { viewModel.moveNavTab(tab, 1) },
                        enabled = pos < visibleTabs.lastIndex
                    ) {
                        Text("↓", color = if (pos < visibleTabs.lastIndex) Lime else IosPalette.tertiaryLabel, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    }
                }
                IosSwitch(
                    checked = isVisible,
                    onCheckedChange = { viewModel.toggleNavTabVisible(tab, it) }
                )
            }
        }
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

    IosSettingsSection(title = "Artwork Framing & Scale") {
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
        IosSettingsSliderRow(
            title = "Artwork Scale Multiplier",
            valueLabel = "${(player.artworkScale * 100).roundToInt()}%",
            value = player.artworkScale,
            valueRange = 0.75f..1.20f,
            onValueChange = viewModel::setPlayerArtworkScale
        )
    }

    Spacer(Modifier.height(22.dp))

    IosSettingsSection(title = "Player Backdrop Style") {
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

    IosSettingsSection(title = "Control & Action Visibility") {
        IosSettingsSwitchRow(
            title = "Rounded Pill Play/Pause Button",
            checked = player.roundedPlayButton,
            onCheckedChange = viewModel::setPlayerRoundedPlayButton
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

    IosSettingsSection(title = "Mini-Player Style") {
        MiniPlayerStyle.entries.forEachIndexed { idx, style ->
            if (idx > 0) IosSettingsDivider(insetStart = 16.dp)
            IosSettingsRow(
                title = style.label,
                value = if (mini.style == style) "Active" else null,
                selected = mini.style == style,
                showChevron = false,
                onClick = { viewModel.setMiniPlayerStyle(style) }
            )
        }
    }

    Spacer(Modifier.height(22.dp))

    IosSettingsSection(title = "Thumbnail Shape & Dimensions") {
        MiniPlayerArtworkShape.entries.forEachIndexed { idx, shape ->
            if (idx > 0) IosSettingsDivider(insetStart = 16.dp)
            IosSettingsRow(
                title = shape.label,
                value = if (mini.artworkShape == shape) "Active" else null,
                selected = mini.artworkShape == shape,
                showChevron = false,
                onClick = { viewModel.setMiniPlayerArtworkShape(shape) }
            )
        }
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
            title = "Show Circular Progress Ring",
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
    viewModel: CustomizationViewModel
) {
    val typo = state.customization.typography

    IosSettingsSection(title = "Application Font Family") {
        AppFontChoice.entries.forEachIndexed { idx, choice ->
            if (idx > 0) IosSettingsDivider(insetStart = 16.dp)
            IosSettingsRow(
                title = choice.label,
                value = if (typo.fontChoice == choice) "Active" else null,
                selected = typo.fontChoice == choice,
                showChevron = false,
                onClick = { viewModel.setFontChoice(choice) }
            )
        }
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

    IosSettingsSection(title = "UI Spacing Density") {
        UiDensityMode.entries.forEachIndexed { idx, mode ->
            if (idx > 0) IosSettingsDivider(insetStart = 16.dp)
            IosSettingsRow(
                title = mode.label,
                value = if (typo.densityMode == mode) "Active" else null,
                selected = typo.densityMode == mode,
                showChevron = false,
                onClick = { viewModel.setDensityMode(mode) }
            )
        }
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
    val align = when (lyrics.alignment) {
        LyricsTextAlignment.START -> TextAlign.Start
        LyricsTextAlignment.CENTER -> TextAlign.Center
        LyricsTextAlignment.END -> TextAlign.End
    }

    IosSettingsSection(title = "Live Lyrics Preview") {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            horizontalAlignment = when (lyrics.alignment) {
                LyricsTextAlignment.START -> Alignment.Start
                LyricsTextAlignment.CENTER -> Alignment.CenterHorizontally
                LyricsTextAlignment.END -> Alignment.End
            }
        ) {
            Text(
                text = "We're drifting through the neon sky",
                color = Color.White.copy(alpha = 0.35f),
                fontSize = (lyrics.fontSizeSp * 0.88f).sp,
                textAlign = align
            )
            Spacer(Modifier.height(10.dp))
            Text(
                text = " الكهرباء · Electric dreams in stereo",
                color = if (lyrics.activeColorFollowsAccent) Lime else Color.White,
                fontSize = lyrics.fontSizeSp.sp,
                lineHeight = (lyrics.fontSizeSp * lyrics.lineSpacingMultiplier).sp,
                fontWeight = FontWeight.ExtraBold,
                textAlign = align
            )
            if (lyrics.showRomanization) {
                Text(
                    text = "Al-kahraba · Phonetic romanization preview",
                    color = Color.White.copy(alpha = 0.65f),
                    fontSize = (lyrics.fontSizeSp * 0.64f).sp,
                    textAlign = align
                )
            }
        }
    }

    Spacer(Modifier.height(22.dp))

    IosSettingsSection(title = "Typography & Alignment") {
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
            valueRange = 1.1f..1.8f,
            onValueChange = viewModel::setLyricsLineSpacing
        )
        IosSettingsDivider(insetStart = 16.dp)
        LyricsTextAlignment.entries.forEach { alignment ->
            IosSettingsRow(
                title = alignment.label,
                value = if (lyrics.alignment == alignment) "Active" else null,
                selected = lyrics.alignment == alignment,
                showChevron = false,
                onClick = { viewModel.setLyricsAlignment(alignment) }
            )
        }
    }

    Spacer(Modifier.height(22.dp))

    IosSettingsSection(title = "Synchronization & Phonetics") {
        IosSettingsSwitchRow(
            title = "Accent Color Active Line Highlight",
            subtitle = "Tint the currently sung lyric line with your active theme accent color",
            checked = lyrics.activeColorFollowsAccent,
            onCheckedChange = { viewModel.setLyricsToggles(accentColor = it) }
        )
        IosSettingsDivider(insetStart = 16.dp)
        IosSettingsSwitchRow(
            title = "Word-by-Word Karaoke Highlight",
            checked = lyrics.wordLevelHighlight,
            onCheckedChange = { viewModel.setLyricsToggles(wordHighlight = it) }
        )
        IosSettingsDivider(insetStart = 16.dp)
        IosSettingsSwitchRow(
            title = "Show Offline Phonetic Romanization",
            subtitle = "Transliterate Hindi, Malayalam, Tamil, Telugu, Korean, Japanese & Arabic script",
            checked = lyrics.showRomanization,
            onCheckedChange = { viewModel.setLyricsToggles(showRomanization = it) }
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
    viewModel: CustomizationViewModel,
    onOpenEqualizer: () -> Unit
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
        IosSettingsDivider(insetStart = 16.dp)
        IosSettingsRow(
            title = "Open 5-Band Equalizer & Pitch Studio",
            subtitle = "Custom frequency bands, tempo speed & pitch shifting",
            value = if (audio.eqEnabled) audio.preset else "Flat",
            icon = GeekifyIcons.GraphicEq,
            tileColor = IosPalette.systemGreen,
            onClick = onOpenEqualizer
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
