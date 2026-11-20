package com.geekify.android.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.geekify.android.settings.LocalAppSettings
import com.geekify.android.ui.theme.ActiveThemePalette
import com.geekify.android.ui.theme.AppFont
import com.geekify.android.ui.theme.GeekifyIcons
import com.geekify.android.ui.theme.InkGlassBorder
import com.geekify.android.ui.theme.Lime
import com.geekify.android.ui.theme.OnAccent

/**
 * Centralized spacing, sizing, and shape tokens for Geekify's global Settings Design System.
 */
object SettingsDesignTokens {
    val CardCornerRadius = 22.dp
    val IconContainerSize = 42.dp
    val IconContainerCornerRadius = 13.dp
    val IconGlyphSize = 20.dp
    val IconToContentGap = 14.dp
    val RowHorizontalPadding = 16.dp
    val RowVerticalPadding = 14.dp
    val TrailingGap = 12.dp
    val ChevronSize = 18.dp
    val SectionSpacing = 22.dp
    val DividerInsetWithIcon = RowHorizontalPadding + IconContainerSize + IconToContentGap
}

/**
 * Reactive Light/Dark Settings palette backed by Geekify's centralized [ActiveThemePalette].
 */
object IosPalette {
    private val dark: Boolean get() = ActiveThemePalette.isDark

    val systemGroupedBackground: Color get() = com.geekify.android.ui.theme.InkBackground
    val secondarySystemGroupedBackground: Color get() = com.geekify.android.ui.theme.InkPanel
    val tertiarySystemGroupedBackground: Color get() = com.geekify.android.ui.theme.InkElevated
    val systemFill: Color get() = if (dark) Color(0x5C787880) else Color(0x28787880)
    val secondarySystemFill: Color get() = if (dark) Color(0x51787880) else Color(0x1E787880)
    val separator: Color get() = if (dark) Color(0x99545458) else Color(0x2E121118)

    val label: Color get() = com.geekify.android.ui.theme.TextPrimary
    val secondaryLabel: Color get() = com.geekify.android.ui.theme.TextSecondary
    val tertiaryLabel: Color get() = com.geekify.android.ui.theme.TextMuted
    val quaternaryLabel: Color get() = if (dark) Color(0x2EEBEBF5) else Color(0x2E121118)

    // Vibrant system icon tile tints (white glyph inside always retains high contrast)
    val systemRed = Color(0xFFFF453A)
    val systemOrange = Color(0xFFFF9F0A)
    val systemYellow = Color(0xFFD9A700)
    val systemGreen = Color(0xFF28B84D)
    val systemMint = Color(0xFF28B8B0)
    val systemTeal = Color(0xFF2FA4C4)
    val systemCyan = Color(0xFF2E9ED8)
    val systemBlue = Color(0xFF0A84FF)
    val systemIndigo = Color(0xFF5E5CE6)
    val systemPurple = Color(0xFFAF52DE)
    val systemPink = Color(0xFFFF375F)
    val systemGray = Color(0xFF8E8E93)
}

/**
 * Reactive Light/Dark surface tokens shared by every Settings, Customization, Audio, and App Icon screen.
 */
object SettingsVisuals {
    private val dark: Boolean get() = ActiveThemePalette.isDark

    val card: Color get() = if (dark) Color(0xFF191A1E) else Color(0xFFFFFFFF)
    val cardRaised: Color get() = if (dark) Color(0xFF23262D) else Color(0xFFE9ECF4)
    val cardBorder: Color get() = if (dark) Color(0xFF2B2F38) else Color(0x18121118)
    val divider: Color get() = if (dark) Color(0xFF2E323C) else Color(0x18121118)
    val switchOffTrack: Color get() = if (dark) Color(0xFF323640) else Color(0xFFD4D8E2)
    val sliderInactiveTrack: Color get() = if (dark) Color(0xFF323640) else Color(0xFFD4D8E2)
    val muted: Color get() = com.geekify.android.ui.theme.TextSecondary
    val blue: Color get() = if (dark) Color(0xFF3985FF) else Color(0xFF1B6CE8)
    val cyan: Color get() = if (dark) Color(0xFF36C5D8) else Color(0xFF0F92A8)
    val green: Color get() = if (dark) Color(0xFF53D69A) else Color(0xFF1E9B63)
    val heroStart: Color get() = if (dark) Color(0xFF202B3C) else Color(0xFFE6ECF7)
    val heroMiddle: Color get() = if (dark) Color(0xFF1B1D25) else Color(0xFFF2F4FA)
}

val IosCardShape = RoundedCornerShape(SettingsDesignTokens.CardCornerRadius)
val IosIconTileShape = RoundedCornerShape(SettingsDesignTokens.IconContainerCornerRadius)

/**
 * Global Collapsing Large-Title Settings page scaffold.
 * Automatically accounts for [LocalBottomInset] and system navigation bars so the floating
 * mini-player and bottom dock never obscure settings cards or controls.
 */
@Composable
fun IosSettingsPageScaffold(
    title: String,
    scrollState: ScrollState,
    onBack: () -> Unit,
    showBackButton: Boolean = true,
    maxContentWidth: Dp = 720.dp,
    bottomPadding: Dp = 32.dp,
    floatingBottomBar: (@Composable BoxScope.() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val density = LocalDensity.current
    val globalBottomInset = LocalBottomInset.current
    val effectiveBottomPadding = maxOf(bottomPadding, globalBottomInset + 40.dp)

    val collapseThresholdPx = with(density) { 42.dp.toPx() }
    val collapsedFraction by remember(scrollState, collapseThresholdPx) {
        derivedStateOf {
            (scrollState.value.toFloat() / collapseThresholdPx).coerceIn(0f, 1f)
        }
    }
    val topBarTitleAlpha by animateFloatAsState(
        targetValue = if (collapsedFraction > 0.65f) 1f else 0f,
        animationSpec = tween(180),
        label = "iosTopBarTitleAlpha"
    )
    val largeTitleAlpha by animateFloatAsState(
        targetValue = (1f - collapsedFraction * 1.15f).coerceIn(0f, 1f),
        animationSpec = tween(120),
        label = "iosLargeTitleAlpha"
    )

    val asciiEnabled = LocalAppSettings.current.ascii.enabled

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(if (asciiEnabled) Color.Transparent else IosPalette.systemGroupedBackground)
    ) {
        // Scrollable body with guaranteed bottom clearance above floating player + nav bar
        Box(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState),
            contentAlignment = Alignment.TopCenter
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = maxContentWidth)
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .navigationBarsPadding()
                    .padding(horizontal = 16.dp)
                    .padding(top = 60.dp, bottom = effectiveBottomPadding)
            ) {
                Text(
                    text = title,
                    color = IosPalette.label,
                    fontFamily = AppFont,
                    fontSize = 32.sp,
                    lineHeight = 38.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = (-0.5).sp,
                    modifier = Modifier
                        .padding(start = 4.dp, top = 4.dp, bottom = 16.dp)
                        .graphicsLayer { alpha = largeTitleAlpha }
                )

                content()
            }
        }

        // Collapsing Top Navigation Bar
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.TopCenter)
                .background(
                    IosPalette.systemGroupedBackground.copy(
                        alpha = (0.82f + 0.16f * collapsedFraction).coerceIn(0f, 0.98f)
                    )
                )
                .statusBarsPadding()
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
                    .padding(horizontal = 16.dp)
            ) {
                if (showBackButton) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.CenterStart)
                            .size(38.dp)
                            .clip(CircleShape)
                            .background(IosPalette.secondarySystemGroupedBackground)
                            .border(0.75.dp, InkGlassBorder, CircleShape)
                            .bouncyClickable(pressedScale = 0.92f, onClick = onBack),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = GeekifyIcons.ArrowBack,
                            contentDescription = "Back",
                            tint = IosPalette.label,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }

                Text(
                    text = title,
                    color = IosPalette.label,
                    fontFamily = AppFont,
                    fontSize = 16.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(horizontal = 56.dp)
                        .alpha(topBarTitleAlpha)
                )
            }
            if (collapsedFraction > 0.5f) {
                HorizontalDivider(
                    thickness = 0.5.dp,
                    color = IosPalette.separator.copy(alpha = 0.55f * collapsedFraction)
                )
            }
        }

        if (floatingBottomBar != null) {
            floatingBottomBar()
        }
    }
}

/**
 * Global Settings section heading + rounded elevated card + wrapping footer caption.
 */
@Composable
fun IosSettingsSection(
    title: String? = null,
    footer: String? = null,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(modifier = modifier.fillMaxWidth()) {
        if (!title.isNullOrBlank()) {
            Row(
                modifier = Modifier.padding(start = 4.dp, end = 12.dp, bottom = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .width(3.dp)
                        .height(14.dp)
                        .clip(CircleShape)
                        .background(Lime)
                )
                Spacer(Modifier.width(9.dp))
                Text(
                    text = title,
                    color = IosPalette.label,
                    fontFamily = AppFont,
                    fontSize = 14.5.sp,
                    lineHeight = 19.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = (-0.1).sp
                )
            }
        }
        Surface(
            color = SettingsVisuals.card,
            shape = IosCardShape,
            shadowElevation = if (ActiveThemePalette.isDark) 0.dp else 1.5.dp,
            modifier = Modifier
                .fillMaxWidth()
                .border(0.75.dp, SettingsVisuals.cardBorder, IosCardShape)
        ) {
            Column(modifier = Modifier.fillMaxWidth(), content = content)
        }
        if (!footer.isNullOrBlank()) {
            Text(
                text = footer,
                color = SettingsVisuals.muted,
                fontFamily = AppFont,
                fontSize = 12.sp,
                lineHeight = 17.sp,
                fontWeight = FontWeight.Normal,
                modifier = Modifier.padding(start = 6.dp, end = 12.dp, top = 8.dp)
            )
        }
    }
}

/**
 * Hairline separator aligned with the text column after the 42.dp icon tile.
 */
@Composable
fun IosSettingsDivider(insetStart: Dp = SettingsDesignTokens.DividerInsetWithIcon) {
    HorizontalDivider(
        modifier = Modifier.padding(start = insetStart),
        thickness = 0.6.dp,
        color = SettingsVisuals.divider
    )
}

/**
 * Consistent rounded 42.dp settings icon tile with 13.dp corner radius and crisp 20.dp glyph.
 */
@Composable
fun IosIconBadge(
    icon: ImageVector,
    tint: Color,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .size(SettingsDesignTokens.IconContainerSize)
            .clip(IosIconTileShape)
            .background(tint),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier.size(SettingsDesignTokens.IconGlyphSize)
        )
    }
}

/**
 * Global responsive Settings navigation & selection row.
 *
 * Layout:
 * - Left: Consistent 42.dp [IosIconBadge] (optional).
 * - Center (flexible full width): Sohne Breit SemiBold title (wraps naturally, never truncated),
 *   wrapping Regular description, and compact selected-value pill underneath (never squeezes title).
 * - Right: Compact badge, selection checkmark (when `selected && !showChevron`), or navigation chevron.
 */
@Composable
fun IosSettingsRow(
    title: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    tileColor: Color = IosPalette.systemBlue,
    subtitle: String? = null,
    value: String? = null,
    badgeText: String? = null,
    selected: Boolean = false,
    destructive: Boolean = false,
    showChevron: Boolean = true,
    onClick: (() -> Unit)? = null
) {
    val rowBg = if (selected) Lime.copy(alpha = if (ActiveThemePalette.isDark) 0.11f else 0.12f) else Color.Transparent
    val isRadioSelectionRow = !showChevron && selected

    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 58.dp)
            .background(rowBg)
            .then(
                if (onClick != null) Modifier.clickable(onClick = onClick)
                else Modifier
            )
            .padding(
                horizontal = SettingsDesignTokens.RowHorizontalPadding,
                vertical = SettingsDesignTokens.RowVerticalPadding
            ),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (icon != null) {
            IosIconBadge(icon = icon, tint = tileColor)
            Spacer(Modifier.width(SettingsDesignTokens.IconToContentGap))
        }

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            Text(
                text = title,
                color = if (destructive) IosPalette.systemRed else IosPalette.label,
                fontFamily = AppFont,
                fontSize = 15.5.sp,
                lineHeight = 20.5.sp,
                letterSpacing = (-0.15).sp,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium
            )
            if (!subtitle.isNullOrBlank()) {
                Text(
                    text = subtitle,
                    color = IosPalette.secondaryLabel,
                    fontFamily = AppFont,
                    fontSize = 12.5.sp,
                    lineHeight = 17.5.sp,
                    fontWeight = FontWeight.Normal
                )
            }
            if (!value.isNullOrBlank() && !isRadioSelectionRow) {
                Box(
                    modifier = Modifier
                        .padding(top = 3.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(
                            if (selected) Lime.copy(alpha = 0.18f)
                            else SettingsVisuals.cardRaised
                        )
                        .padding(horizontal = 8.dp, vertical = 3.dp)
                ) {
                    Text(
                        text = value,
                        color = if (selected) Lime else IosPalette.secondaryLabel,
                        fontFamily = AppFont,
                        fontSize = 11.5.sp,
                        lineHeight = 15.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }

        if (!badgeText.isNullOrBlank()) {
            Spacer(Modifier.width(SettingsDesignTokens.TrailingGap))
            Box(
                modifier = Modifier
                    .defaultMinSize(minWidth = 22.dp, minHeight = 22.dp)
                    .clip(CircleShape)
                    .background(IosPalette.systemRed)
                    .padding(horizontal = 7.dp, vertical = 2.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = badgeText,
                    color = Color.White,
                    fontFamily = AppFont,
                    fontSize = 11.5.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }

        if (isRadioSelectionRow) {
            Spacer(Modifier.width(SettingsDesignTokens.TrailingGap))
            Box(
                modifier = Modifier
                    .size(24.dp)
                    .clip(CircleShape)
                    .background(Lime),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = GeekifyIcons.Check,
                    contentDescription = "Selected",
                    tint = OnAccent,
                    modifier = Modifier.size(15.dp)
                )
            }
        } else if (showChevron && onClick != null) {
            Spacer(Modifier.width(SettingsDesignTokens.TrailingGap))
            Icon(
                imageVector = GeekifyIcons.ChevronRight,
                contentDescription = null,
                tint = IosPalette.tertiaryLabel,
                modifier = Modifier.size(SettingsDesignTokens.ChevronSize)
            )
        }
    }
}

/**
 * Global responsive Settings toggle row using [IosSwitch].
 * Flexible center column ensures long titles and descriptions never collide with the trailing switch.
 */
@Composable
fun IosSettingsSwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    tileColor: Color = IosPalette.systemBlue,
    subtitle: String? = null,
    enabled: Boolean = true
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 60.dp)
            .clickable(enabled = enabled) { onCheckedChange(!checked) }
            .padding(
                horizontal = SettingsDesignTokens.RowHorizontalPadding,
                vertical = SettingsDesignTokens.RowVerticalPadding
            ),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (icon != null) {
            IosIconBadge(icon = icon, tint = tileColor)
            Spacer(Modifier.width(SettingsDesignTokens.IconToContentGap))
        }

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = title,
                color = if (enabled) IosPalette.label else IosPalette.tertiaryLabel,
                fontFamily = AppFont,
                fontSize = 15.5.sp,
                lineHeight = 20.5.sp,
                letterSpacing = (-0.15).sp,
                fontWeight = FontWeight.Medium
            )
            if (!subtitle.isNullOrBlank()) {
                Text(
                    text = subtitle,
                    color = if (enabled) IosPalette.secondaryLabel else IosPalette.tertiaryLabel,
                    fontFamily = AppFont,
                    fontSize = 12.5.sp,
                    lineHeight = 17.5.sp,
                    fontWeight = FontWeight.Normal
                )
            }
        }

        Spacer(Modifier.width(SettingsDesignTokens.TrailingGap))
        IosSwitch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled
        )
    }
}

/**
 * Global responsive Settings slider row using [IosSlider].
 */
@Composable
fun IosSettingsSliderRow(
    title: String,
    valueLabel: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    tileColor: Color = IosPalette.systemBlue,
    activeColor: Color = Lime,
    onValueLabelClick: (() -> Unit)? = null
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(
                horizontal = SettingsDesignTokens.RowHorizontalPadding,
                vertical = SettingsDesignTokens.RowVerticalPadding
            )
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (icon != null) {
                IosIconBadge(icon = icon, tint = tileColor)
                Spacer(Modifier.width(SettingsDesignTokens.IconToContentGap))
            }
            Text(
                text = title,
                color = IosPalette.label,
                fontFamily = AppFont,
                fontSize = 15.5.sp,
                lineHeight = 20.5.sp,
                letterSpacing = (-0.15).sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(SettingsDesignTokens.TrailingGap))
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(SettingsVisuals.cardRaised)
                    .then(
                        if (onValueLabelClick != null) Modifier.clickable(onClick = onValueLabelClick)
                        else Modifier
                    )
                    .padding(horizontal = 9.dp, vertical = 4.dp)
            ) {
                Text(
                    text = valueLabel,
                    color = IosPalette.label,
                    fontFamily = AppFont,
                    fontSize = 12.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    softWrap = false
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        IosSlider(
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange,
            activeTrackColor = activeColor
        )
    }
}

/**
 * Responsive Settings switch (`51.dp x 31.dp` capsule track with `27.dp` shadowed knob).
 */
@Composable
fun IosSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    activeTrackColor: Color = Lime
) {
    val trackColor by animateColorAsState(
        targetValue = if (checked) activeTrackColor else SettingsVisuals.switchOffTrack,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "iosSwitchTrack"
    )
    val thumbProgress by animateFloatAsState(
        targetValue = if (checked) 1f else 0f,
        animationSpec = spring(dampingRatio = 0.75f, stiffness = 900f),
        label = "iosSwitchThumb"
    )

    val trackWidth = 51.dp
    val trackHeight = 31.dp
    val thumbSize = 27.dp
    val padding = 2.dp
    val travel = trackWidth - thumbSize - padding * 2

    Box(
        modifier = modifier
            .size(width = trackWidth, height = trackHeight)
            .alpha(if (enabled) 1f else 0.45f)
            .clip(CircleShape)
            .background(trackColor)
            .clickable(
                enabled = enabled,
                role = Role.Switch,
                indication = null,
                interactionSource = remember { MutableInteractionSource() }
            ) { onCheckedChange(!checked) }
            .padding(padding),
        contentAlignment = Alignment.CenterStart
    ) {
        Box(
            modifier = Modifier
                .offset(x = travel * thumbProgress)
                .size(thumbSize)
                .shadow(elevation = 2.dp, shape = CircleShape, clip = false)
                .clip(CircleShape)
                .background(Color.White)
        )
    }
}

/**
 * Responsive Settings slider (`4.dp` rounded track + `26.dp` white shadowed circular thumb).
 */
@Composable
fun IosSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    activeTrackColor: Color = Lime,
    inactiveTrackColor: Color = SettingsVisuals.sliderInactiveTrack,
    onValueChangeFinished: (() -> Unit)? = null
) {
    var widthPx by remember { mutableIntStateOf(0) }
    var dragging by remember { mutableStateOf(false) }

    val span = (valueRange.endInclusive - valueRange.start).takeIf { it > 0f } ?: 1f
    val fraction = ((value - valueRange.start) / span).coerceIn(0f, 1f)

    val thumbScale by animateFloatAsState(
        targetValue = if (dragging) 1.12f else 1f,
        animationSpec = spring(dampingRatio = 0.7f, stiffness = 600f),
        label = "iosSliderThumbScale"
    )

    val currentOnValueChange by rememberUpdatedState(onValueChange)
    val currentOnValueChangeFinished by rememberUpdatedState(onValueChangeFinished)

    fun pxToValue(x: Float): Float {
        if (widthPx <= 0) return value
        val f = (x / widthPx.toFloat()).coerceIn(0f, 1f)
        return valueRange.start + f * span
    }

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(34.dp)
            .alpha(if (enabled) 1f else 0.45f)
            .onSizeChanged { widthPx = it.width }
            .then(
                if (enabled) {
                    Modifier
                        .pointerInput(valueRange) {
                            detectTapGestures { offset ->
                                currentOnValueChange(pxToValue(offset.x))
                                currentOnValueChangeFinished?.invoke()
                            }
                        }
                        .pointerInput(valueRange) {
                            detectHorizontalDragGestures(
                                onDragStart = { offset ->
                                    dragging = true
                                    currentOnValueChange(pxToValue(offset.x))
                                },
                                onDragEnd = {
                                    dragging = false
                                    currentOnValueChangeFinished?.invoke()
                                },
                                onDragCancel = {
                                    dragging = false
                                    currentOnValueChangeFinished?.invoke()
                                },
                                onHorizontalDrag = { change, _ ->
                                    change.consume()
                                    currentOnValueChange(pxToValue(change.position.x))
                                }
                            )
                        }
                } else Modifier
            )
    ) {
        val trackHeight = 4.dp.toPx()
        val thumbRadius = 13.dp.toPx() * thumbScale
        val centerY = size.height / 2f
        val usableWidth = (size.width - thumbRadius * 2f).coerceAtLeast(0f)
        val thumbX = thumbRadius + usableWidth * fraction

        // Inactive track
        drawRoundRect(
            color = inactiveTrackColor,
            topLeft = Offset(thumbRadius, centerY - trackHeight / 2f),
            size = Size(usableWidth, trackHeight),
            cornerRadius = CornerRadius(trackHeight / 2f, trackHeight / 2f)
        )

        // Active track
        val activeWidth = (thumbX - thumbRadius).coerceAtLeast(0f)
        if (activeWidth > 0f) {
            drawRoundRect(
                color = activeTrackColor,
                topLeft = Offset(thumbRadius, centerY - trackHeight / 2f),
                size = Size(activeWidth, trackHeight),
                cornerRadius = CornerRadius(trackHeight / 2f, trackHeight / 2f)
            )
        }

        // Soft drop shadow
        drawCircle(
            color = Color.Black.copy(alpha = if (ActiveThemePalette.isDark) 0.30f else 0.16f),
            radius = thumbRadius + 1.dp.toPx(),
            center = Offset(thumbX, centerY + 1.5.dp.toPx())
        )
        // White thumb
        drawCircle(
            color = Color.White,
            radius = thumbRadius,
            center = Offset(thumbX, centerY)
        )
        // Hairline border
        drawCircle(
            color = Color.Black.copy(alpha = 0.10f),
            radius = thumbRadius,
            center = Offset(thumbX, centerY),
            style = Stroke(width = 0.6.dp.toPx())
        )
    }
}
