package dev.lework.ui.desktop

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.lework.ui.Everforest

// ---------------------------------------------------------------------------
// Strict Desktop Chrome Geometry Tokens (Pixel-Aligned Grid)
// ---------------------------------------------------------------------------

internal val DesktopControlSize: Dp = 30.dp
internal val DesktopControlRadius: Dp = 8.dp
internal val DesktopHeaderHeight: Dp = 44.dp

// ---------------------------------------------------------------------------
// Crisp Lucide-style Vector Glyphs & Engine Brand Marks
// ---------------------------------------------------------------------------

internal val LeworkPanelLeftIcon: ImageVector by lazy {
    ImageVector.Builder(
        name = "LeworkPanelLeft",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        path(
            stroke = SolidColor(Color.White),
            strokeLineWidth = 1.85f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
        ) {
            moveTo(5.5f, 4f)
            lineTo(18.5f, 4f)
            quadTo(21f, 4f, 21f, 6.5f)
            lineTo(21f, 17.5f)
            quadTo(21f, 20f, 18.5f, 20f)
            lineTo(5.5f, 20f)
            quadTo(3f, 20f, 3f, 17.5f)
            lineTo(3f, 6.5f)
            quadTo(3f, 4f, 5.5f, 4f)
            close()
            moveTo(9.2f, 4f)
            lineTo(9.2f, 20f)
        }
    }.build()
}

internal val LeworkSquarePenIcon: ImageVector by lazy {
    ImageVector.Builder(
        name = "LeworkSquarePen",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        path(
            stroke = SolidColor(Color.White),
            strokeLineWidth = 1.85f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
        ) {
            moveTo(12f, 4f)
            lineTo(6.5f, 4f)
            quadTo(4f, 4f, 4f, 6.5f)
            lineTo(4f, 17.5f)
            quadTo(4f, 20f, 6.5f, 20f)
            lineTo(17.5f, 20f)
            quadTo(20f, 20f, 20f, 17.5f)
            lineTo(20f, 12f)
            moveTo(18.2f, 3.8f)
            lineTo(20.2f, 5.8f)
            lineTo(13.2f, 12.8f)
            lineTo(10.2f, 13.8f)
            lineTo(11.2f, 10.8f)
            close()
        }
    }.build()
}

internal val LeworkChartColumnsIcon: ImageVector by lazy {
    ImageVector.Builder(
        name = "LeworkChartColumns",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        path(
            stroke = SolidColor(Color.White),
            strokeLineWidth = 2.0f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
        ) {
            moveTo(6f, 19f)
            lineTo(6f, 13f)
            moveTo(12f, 19f)
            lineTo(12f, 5f)
            moveTo(18f, 19f)
            lineTo(18f, 9.5f)
        }
    }.build()
}

internal val LeworkFilterSlidersIcon: ImageVector by lazy {
    ImageVector.Builder(
        name = "LeworkFilterSliders",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        path(
            stroke = SolidColor(Color.White),
            strokeLineWidth = 1.85f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
        ) {
            moveTo(4f, 7f)
            lineTo(20f, 7f)
            moveTo(7f, 12f)
            lineTo(17f, 12f)
            moveTo(10f, 17f)
            lineTo(14f, 17f)
        }
    }.build()
}

internal val LeworkPinIcon: ImageVector by lazy {
    ImageVector.Builder(
        name = "LeworkPin",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        path(
            fill = SolidColor(Color.White),
        ) {
            // Solid filled diagonal push-pin (tilted thumbtack cap + tapered body + flared collar + sharp needle)
            moveTo(15.46f, 3.30f)
            lineTo(20.70f, 8.54f)
            quadTo(21.25f, 9.09f, 20.40f, 9.94f)
            quadTo(19.60f, 10.74f, 19.07f, 10.30f)
            lineTo(17.87f, 9.10f)
            lineTo(14.55f, 12.85f)
            lineTo(15.61f, 16.60f)
            quadTo(15.45f, 17.45f, 14.12f, 17.52f)
            lineTo(11.29f, 14.69f)
            lineTo(3.80f, 20.20f)
            lineTo(9.31f, 12.71f)
            lineTo(6.48f, 9.88f)
            quadTo(6.55f, 8.55f, 7.40f, 8.39f)
            lineTo(11.15f, 9.45f)
            lineTo(14.90f, 6.13f)
            lineTo(13.70f, 4.93f)
            quadTo(13.26f, 4.40f, 14.06f, 3.60f)
            quadTo(14.91f, 2.75f, 15.46f, 3.30f)
            close()
        }
    }.build()
}

// ---------------------------------------------------------------------------
// Hover Tooltips, Icon Buttons & Native Desktop Modal Container
// ---------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LeworkTooltip(
    label: String,
    shortcut: String? = null,
    subtitle: String? = null,
    enabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    if (!enabled || label.isBlank()) {
        content()
        return
    }
    TooltipBox(
        positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
        tooltip = {
            PlainTooltip(
                shape = RoundedCornerShape(DesktopControlRadius),
                containerColor = Everforest.Bg2,
                contentColor = Everforest.Fg,
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(
                            text = label,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            color = Everforest.Fg,
                        )
                        if (!shortcut.isNullOrBlank()) {
                            Text(
                                text = shortcut,
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace,
                                color = Everforest.BoldGold,
                            )
                        }
                    }
                    if (!subtitle.isNullOrBlank()) {
                        Text(
                            text = subtitle,
                            fontSize = 11.sp,
                            color = Everforest.Grey1,
                        )
                    }
                }
            }
        },
        state = rememberTooltipState(),
        content = content,
    )
}

@Composable
internal fun LeworkIconActionButton(
    icon: ImageVector,
    tooltip: String,
    shortcut: String? = null,
    subtitle: String? = null,
    idleTint: Color = Everforest.Grey1,
    hoverTint: Color = Everforest.Fg,
    active: Boolean = false,
    activeTint: Color = Everforest.Green,
    activeContainerColor: Color = Everforest.Bg2,
    showBadgeDot: Boolean = false,
    badgeColor: Color = Everforest.BoldGold,
    size: Dp = DesktopControlSize,
    iconSize: Dp = 16.dp,
    enabled: Boolean = true,
    testTag: String? = null,
    onClick: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()
    val bgColor = when {
        !enabled -> Color.Transparent
        active -> activeContainerColor
        isHovered -> Everforest.Bg2.copy(alpha = 0.85f)
        else -> Color.Transparent
    }
    val tintColor = when {
        !enabled -> Everforest.Grey0.copy(alpha = 0.55f)
        active -> activeTint
        isHovered -> hoverTint
        else -> idleTint
    }

    LeworkTooltip(
        label = tooltip,
        shortcut = shortcut,
        subtitle = subtitle,
        enabled = enabled,
    ) {
        Box(
            modifier = Modifier
                .size(size)
                .let { mod -> if (testTag != null) mod.testTag(testTag) else mod }
                .clip(RoundedCornerShape(DesktopControlRadius))
                .background(bgColor)
                .hoverable(interactionSource = interactionSource, enabled = enabled)
                .semantics {
                    role = Role.Button
                    contentDescription = tooltip
                }
                .clickable(
                    interactionSource = interactionSource,
                    indication = null,
                    enabled = enabled,
                    onClick = onClick,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = tintColor,
                modifier = Modifier.size(iconSize),
            )
            if (showBadgeDot) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(top = 5.dp, end = 5.dp)
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(badgeColor),
                )
            }
        }
    }
}

@Composable
internal fun DesktopModalFrame(
    onDismiss: () -> Unit,
    minWidth: Dp = 480.dp,
    maxWidth: Dp = 680.dp,
    maxHeight: Dp = 680.dp,
    testTag: String? = null,
    content: @Composable () -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = Everforest.Bg1,
            border = BorderStroke(1.dp, Everforest.Bg3),
            shadowElevation = 16.dp,
            modifier = Modifier
                .padding(24.dp)
                .widthIn(min = minWidth, max = maxWidth)
                .heightIn(max = maxHeight)
                .onPreviewKeyEvent { ev ->
                    if (ev.type == KeyEventType.KeyDown && ev.key == Key.Escape) {
                        onDismiss()
                        true
                    } else {
                        false
                    }
                }
                .let { mod -> if (testTag != null) mod.testTag(testTag) else mod },
        ) {
            Box(modifier = Modifier.padding(top = 14.dp)) {
                content()
            }
        }
    }
}
