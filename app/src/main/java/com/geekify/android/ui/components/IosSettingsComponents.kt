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
import com.geekify.android.ui.theme.GeekifyIcons

/**
 * Cupertino / iOS 26 Dark Grouped Settings palette and reusable building blocks.
 */
object IosPalette {
    val systemGroupedBackground = Color(0xFF000000)
    val secondarySystemGroupedBackground = Color(0xFF1C1C1E)
    val tertiarySystemGroupedBackground = Color(0xFF2C2C2E)
    val systemFill = Color(0x5C787880)
    val secondarySystemFill = Color(0x51787880)
    val separator = Color(0x99545458)

    val label = Color(0xFFFFFFFF)
    val secondaryLabel = Color(0x99EBEBF5)
    val tertiaryLabel = Color(0x4DEBEBF5)
    val quaternaryLabel = Color(0x2EEBEBF5)

    // Vibrant dark-mode system icon tile tints
    val systemRed = Color(0xFFFF453A)
    val systemOrange = Color(0xFFFF9F0A)
    val systemYellow = Color(0xFFFFD60A)
    val systemGreen = Color(0xFF30D158)
    val systemMint = Color(0xFF63E6E2)
    val systemTeal = Color(0xFF40C8E0)
    val systemCyan = Color(0xFF64D2FF)
    val systemBlue = Color(0xFF0A84FF)
    val systemIndigo = Color(0xFF5E5CE6)
    val systemPurple = Color(0xFFBF5AF2)
    val systemPink = Color(0xFFFF375F)
    val systemGray = Color(0xFF8E8E93)
}

val IosCardShape = RoundedCornerShape(20.dp)
val IosIconTileShape = RoundedCornerShape(7.dp)

/**
 * Collapsing Large-Title Settings page scaffold.
 * Displays a translucent top navigation bar whose small centered title fades in once the
 * scrollable large title scrolls beneath the bar.
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

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(IosPalette.systemGroupedBackground)
    ) {
        // Scrollable body
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
                    .padding(horizontal = 16.dp)
                    .padding(top = 60.dp, bottom = bottomPadding)
            ) {
                Text(
                    text = title,
                    color = IosPalette.label,
                    fontSize = 34.sp,
                    lineHeight = 41.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = (-0.5).sp,
                    modifier = Modifier
                        .padding(start = 4.dp, top = 4.dp, bottom = 14.dp)
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
                        alpha = (0.78f + 0.18f * collapsedFraction).coerceIn(0f, 0.96f)
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
                            .border(0.5.dp, Color.White.copy(alpha = 0.10f), CircleShape)
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
                    fontSize = 17.sp,
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
 * Sentence-case iOS grouped section header + rounded `#1C1C1E` card + optional footer caption.
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
            Text(
                text = title,
                color = IosPalette.secondaryLabel,
                fontSize = 13.sp,
                lineHeight = 18.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = (-0.08).sp,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 7.dp)
            )
        }
        Surface(
            color = IosPalette.secondarySystemGroupedBackground,
            shape = IosCardShape,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.fillMaxWidth(), content = content)
        }
        if (!footer.isNullOrBlank()) {
            Text(
                text = footer,
                color = IosPalette.secondaryLabel,
                fontSize = 13.sp,
                lineHeight = 18.sp,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 7.dp)
            )
        }
    }
}

/**
 * Hairline separator inset 58.dp from the start edge so it aligns with the text column
 * past the 30.dp icon tile (`16.dp` row padding + `30.dp` tile + `12.dp` gap = `58.dp`).
 */
@Composable
fun IosSettingsDivider(insetStart: Dp = 58.dp) {
    HorizontalDivider(
        modifier = Modifier.padding(start = insetStart),
        thickness = 0.5.dp,
        color = IosPalette.separator
    )
}

/**
 * Rounded 30.dp iOS icon badge with 7.dp corner radius and 18.dp white glyph.
 */
@Composable
fun IosIconBadge(
    icon: ImageVector,
    tint: Color,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .size(30.dp)
            .clip(IosIconTileShape)
            .background(tint),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier.size(18.dp)
        )
    }
}

/**
 * Standard iOS Settings navigation / action row.
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
    val rowBg = if (selected) IosPalette.systemFill else Color.Transparent
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .background(rowBg)
            .then(
                if (onClick != null) Modifier.clickable(onClick = onClick)
                else Modifier
            )
            .padding(horizontal = 16.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (icon != null) {
            IosIconBadge(icon = icon, tint = tileColor)
            Spacer(Modifier.width(12.dp))
        }

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                color = if (destructive) IosPalette.systemRed else IosPalette.label,
                fontSize = 17.sp,
                lineHeight = 22.sp,
                letterSpacing = (-0.35).sp,
                fontWeight = FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (!subtitle.isNullOrBlank()) {
                Text(
                    text = subtitle,
                    color = IosPalette.secondaryLabel,
                    fontSize = 13.sp,
                    lineHeight = 17.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 1.dp)
                )
            }
        }

        if (!badgeText.isNullOrBlank()) {
            Spacer(Modifier.width(8.dp))
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
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }

        if (!value.isNullOrBlank()) {
            Spacer(Modifier.width(8.dp))
            Text(
                text = value,
                color = IosPalette.secondaryLabel,
                fontSize = 16.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 150.dp)
            )
        }

        if (showChevron && onClick != null) {
            Spacer(Modifier.width(6.dp))
            Icon(
                imageVector = GeekifyIcons.ChevronRight,
                contentDescription = null,
                tint = IosPalette.tertiaryLabel,
                modifier = Modifier.size(16.dp)
            )
        }
    }
}

/**
 * Standard iOS Settings toggle row using [IosSwitch].
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
            .heightIn(min = 48.dp)
            .clickable(enabled = enabled) { onCheckedChange(!checked) }
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (icon != null) {
            IosIconBadge(icon = icon, tint = tileColor)
            Spacer(Modifier.width(12.dp))
        }

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                color = if (enabled) IosPalette.label else IosPalette.secondaryLabel,
                fontSize = 17.sp,
                lineHeight = 22.sp,
                letterSpacing = (-0.35).sp,
                fontWeight = FontWeight.Normal
            )
            if (!subtitle.isNullOrBlank()) {
                Text(
                    text = subtitle,
                    color = IosPalette.secondaryLabel,
                    fontSize = 13.sp,
                    lineHeight = 17.sp,
                    modifier = Modifier.padding(top = 1.dp)
                )
            }
        }

        Spacer(Modifier.width(12.dp))
        IosSwitch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled
        )
    }
}

/**
 * Standard iOS Settings slider row using [IosSlider].
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
    activeColor: Color = IosPalette.systemBlue,
    onValueLabelClick: (() -> Unit)? = null
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 11.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (icon != null) {
                IosIconBadge(icon = icon, tint = tileColor)
                Spacer(Modifier.width(12.dp))
            }
            Text(
                text = title,
                color = IosPalette.label,
                fontSize = 16.sp,
                letterSpacing = (-0.3).sp,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = valueLabel,
                color = IosPalette.secondaryLabel,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                modifier = if (onValueLabelClick != null) {
                    Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .clickable(onClick = onValueLabelClick)
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                } else {
                    Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                }
            )
        }
        Spacer(Modifier.height(6.dp))
        IosSlider(
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange,
            activeTrackColor = activeColor
        )
    }
}

/**
 * Authentic iOS Switch (`51.dp x 31.dp` capsule track with `27.dp` shadowed white knob).
 */
@Composable
fun IosSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    activeTrackColor: Color = IosPalette.systemGreen
) {
    val trackColor by animateColorAsState(
        targetValue = if (checked) activeTrackColor else IosPalette.tertiarySystemGroupedBackground,
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
 * Authentic iOS Slider (`4.dp` rounded track + `27.dp` white shadowed circular thumb that swells on drag).
 */
@Composable
fun IosSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    activeTrackColor: Color = IosPalette.systemBlue,
    inactiveTrackColor: Color = IosPalette.tertiarySystemGroupedBackground,
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
            color = Color.Black.copy(alpha = 0.30f),
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
            color = Color.Black.copy(alpha = 0.08f),
            radius = thumbRadius,
            center = Offset(thumbX, centerY),
            style = Stroke(width = 0.5.dp.toPx())
        )
    }
}
