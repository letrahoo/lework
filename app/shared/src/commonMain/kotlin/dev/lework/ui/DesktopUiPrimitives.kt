package dev.lework.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import dev.lework.platform.appVersionLabel
import dev.lework.platform.copyTextToClipboard
import dev.lework.store.ConsoleUiState

// ---------------------------------------------------------------------------
// Crisp Lucide-style Vector Glyphs (matching T3 Code Desktop Chrome)
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

internal val LeworkClaudeStarIcon: ImageVector by lazy {
    ImageVector.Builder(
        name = "LeworkClaudeStar",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        path(
            stroke = SolidColor(Color.White),
            strokeLineWidth = 1.85f,
            strokeLineCap = StrokeCap.Round,
        ) {
            // 10-ray organic starburst glyph
            moveTo(12f, 3.2f); lineTo(12f, 20.8f)
            moveTo(3.2f, 12f); lineTo(20.8f, 12f)
            moveTo(5.8f, 5.8f); lineTo(18.2f, 18.2f)
            moveTo(18.2f, 5.8f); lineTo(5.8f, 18.2f)
            moveTo(8.5f, 3.9f); lineTo(15.5f, 20.1f)
            moveTo(20.1f, 8.5f); lineTo(3.9f, 15.5f)
        }
    }.build()
}

internal val LeworkCodexHexIcon: ImageVector by lazy {
    ImageVector.Builder(
        name = "LeworkCodexHex",
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
            moveTo(12f, 3.2f)
            lineTo(19.6f, 7.6f)
            lineTo(19.6f, 16.4f)
            lineTo(12f, 20.8f)
            lineTo(4.4f, 16.4f)
            lineTo(4.4f, 7.6f)
            close()
            moveTo(12f, 8.2f)
            lineTo(15.4f, 10.1f)
            lineTo(15.4f, 13.9f)
            lineTo(12f, 15.8f)
            lineTo(8.6f, 13.9f)
            lineTo(8.6f, 10.1f)
            close()
        }
    }.build()
}

internal val LeworkAgyDeltaIcon: ImageVector by lazy {
    ImageVector.Builder(
        name = "LeworkAgyDelta",
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
            moveTo(4.5f, 19.2f)
            quadTo(9.5f, 6.2f, 12f, 4.2f)
            quadTo(14.5f, 6.2f, 19.5f, 19.2f)
            moveTo(7.8f, 15.2f)
            quadTo(12f, 12.2f, 16.2f, 15.2f)
        }
    }.build()
}

internal fun engineBrandGlyph(engineId: String): ImageVector = when (engineId.lowercase()) {
    "claude" -> LeworkClaudeStarIcon
    "codex" -> LeworkCodexHexIcon
    "agy" -> LeworkAgyDeltaIcon
    "pi" -> Icons.Rounded.Code
    else -> Icons.Rounded.Terminal
}

@Composable
internal fun EngineGlyphIcon(
    engineId: String,
    size: Dp = 15.dp,
    tintOverride: Color? = null,
) {
    Icon(
        imageVector = engineBrandGlyph(engineId),
        contentDescription = null,
        tint = tintOverride ?: engineAccentColor(engineId),
        modifier = Modifier.size(size),
    )
}

// ---------------------------------------------------------------------------
// Reusable Hover Tooltip & Compact Desktop Icon Buttons
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
                shape = RoundedCornerShape(8.dp),
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
    size: Dp = 30.dp,
    iconSize: Dp = 16.5.dp,
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
                .clip(RoundedCornerShape(8.dp))
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

// ---------------------------------------------------------------------------
// Desktop Settings & Connection Preferences Modal
// ---------------------------------------------------------------------------

@Composable
internal fun DesktopSettingsDialog(
    state: ConsoleUiState,
    onDismiss: () -> Unit,
    onSwitchHost: (String) -> Unit,
    onRemoveHost: (String) -> Unit,
    onPairAnotherHost: () -> Unit,
    onLogout: () -> Unit,
) {
    var copiedFingerprint by remember { mutableStateOf(false) }
    val onlineCount = state.engines.count { it.status.ready }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = Everforest.Bg1,
            border = BorderStroke(1.dp, Everforest.Bg3),
            modifier = Modifier
                .widthIn(min = 440.dp, max = 560.dp)
                .fillMaxWidth()
                .heightIn(max = 720.dp)
                .testTag("desktop_settings_dialog"),
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            text = "设置",
                            fontSize = 17.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Everforest.Fg,
                        )
                        Text(
                            text = "管理当前开发机连接、安全凭证与桌面端快捷键",
                            fontSize = 12.sp,
                            color = Everforest.CommentGrey,
                        )
                    }
                    LeworkIconActionButton(
                        icon = Icons.Rounded.Close,
                        tooltip = "关闭设置",
                        shortcut = "Esc",
                        testTag = "desktop_settings_close_btn",
                        onClick = onDismiss,
                    )
                }

                HorizontalDivider(color = Everforest.Bg2)

                Column(
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    // Section 1: Current Host & TLS Encryption
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = Everforest.Bg0,
                        border = BorderStroke(1.dp, Everforest.Bg2),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.Computer,
                                        contentDescription = null,
                                        tint = Everforest.Green,
                                        modifier = Modifier.size(17.dp),
                                    )
                                    Text(
                                        text = state.hub?.name ?: "本机工作站",
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = Everforest.Fg,
                                    )
                                }
                                Surface(
                                    shape = RoundedCornerShape(999.dp),
                                    color = Everforest.BgGreen,
                                    border = BorderStroke(1.dp, Everforest.Green.copy(alpha = 0.35f)),
                                ) {
                                    Text(
                                        text = "${connectionChannelLabel(state.discoveredBaseUrl, state.tsnetStatus)} · $onlineCount 个引擎就绪",
                                        fontSize = 11.sp,
                                        color = Everforest.Green,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                                    )
                                }
                            }

                            if (state.discoveredBaseUrl.isNotBlank()) {
                                Text(
                                    text = "连接地址：${state.discoveredBaseUrl}",
                                    fontSize = 11.5.sp,
                                    fontFamily = FontFamily.Monospace,
                                    color = Everforest.Grey1,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }

                            state.hubFingerprint?.let { fp ->
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        text = "端到端加密指纹：$fp",
                                        fontSize = 11.5.sp,
                                        fontFamily = FontFamily.Monospace,
                                        color = Everforest.BoldGold,
                                    )
                                    Text(
                                        text = if (copiedFingerprint) "已复制" else "复制指纹",
                                        fontSize = 11.5.sp,
                                        color = Everforest.Green,
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(6.dp))
                                            .clickable {
                                                copyTextToClipboard(fp)
                                                copiedFingerprint = true
                                            }
                                            .padding(horizontal = 6.dp, vertical = 2.dp),
                                    )
                                }
                            }

                            if (state.engines.isNotEmpty()) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    for (eng in state.engines) {
                                        val ready = eng.status.ready
                                        Surface(
                                            shape = RoundedCornerShape(6.dp),
                                            color = Everforest.Bg1,
                                            border = BorderStroke(1.dp, Everforest.Bg2),
                                        ) {
                                            Row(
                                                modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(5.dp),
                                            ) {
                                                Box(
                                                    modifier = Modifier
                                                        .size(6.dp)
                                                        .clip(CircleShape)
                                                        .background(if (ready) Everforest.Green else Everforest.Grey0),
                                                )
                                                Text(
                                                    text = shortEngineLabel(eng.id),
                                                    fontSize = 11.sp,
                                                    color = if (ready) Everforest.Fg else Everforest.Grey0,
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // Section 2: Paired Hosts (Mac + Remote Devbox)
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = Everforest.Bg0,
                        border = BorderStroke(1.dp, Everforest.Bg2),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = "已配对开发机",
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = Everforest.Fg,
                                )
                                Text(
                                    text = "＋ 连接其他开发机",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = Everforest.BoldGold,
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(6.dp))
                                        .clickable {
                                            onDismiss()
                                            onPairAnotherHost()
                                        }
                                        .padding(horizontal = 6.dp, vertical = 2.dp)
                                        .testTag("settings_btn_pair_another"),
                                )
                            }

                            if (state.pairedHosts.isEmpty()) {
                                Text(
                                    text = "当前使用本机工作站直连，可通过右上按钮一键 SSH 连接远程 Linux 开发机。",
                                    fontSize = 11.5.sp,
                                    color = Everforest.Grey1,
                                )
                            } else {
                                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    for (host in state.pairedHosts) {
                                        val isCurrent = host.hubId == state.hub?.id
                                        Surface(
                                            shape = RoundedCornerShape(8.dp),
                                            color = if (isCurrent) Everforest.BgGreen else Everforest.Bg1,
                                            border = BorderStroke(
                                                1.dp,
                                                if (isCurrent) Everforest.Green.copy(alpha = 0.35f) else Everforest.Bg2,
                                            ),
                                            modifier = Modifier.fillMaxWidth(),
                                        ) {
                                            Row(
                                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.SpaceBetween,
                                            ) {
                                                Column(modifier = Modifier.weight(1f)) {
                                                    Text(
                                                        text = host.name,
                                                        fontSize = 12.5.sp,
                                                        fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Normal,
                                                        color = if (isCurrent) Everforest.Green else Everforest.Fg,
                                                        maxLines = 1,
                                                        overflow = TextOverflow.Ellipsis,
                                                    )
                                                    host.endpoints.firstOrNull()?.let { ep ->
                                                        Text(
                                                            text = ep,
                                                            fontSize = 10.5.sp,
                                                            fontFamily = FontFamily.Monospace,
                                                            color = Everforest.Grey0,
                                                            maxLines = 1,
                                                            overflow = TextOverflow.Ellipsis,
                                                        )
                                                    }
                                                }
                                                if (isCurrent) {
                                                    Text(
                                                        text = "当前连接",
                                                        fontSize = 11.sp,
                                                        color = Everforest.Green,
                                                    )
                                                } else {
                                                    Row(
                                                        verticalAlignment = Alignment.CenterVertically,
                                                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                                                    ) {
                                                        Text(
                                                            text = "切换",
                                                            fontSize = 11.5.sp,
                                                            fontWeight = FontWeight.Medium,
                                                            color = Everforest.Green,
                                                            modifier = Modifier
                                                                .clip(RoundedCornerShape(6.dp))
                                                                .clickable {
                                                                    onDismiss()
                                                                    onSwitchHost(host.hubId)
                                                                }
                                                                .padding(horizontal = 8.dp, vertical = 4.dp),
                                                        )
                                                        LeworkIconActionButton(
                                                            icon = Icons.Rounded.Close,
                                                            tooltip = "移除此开发机记录",
                                                            size = 24.dp,
                                                            iconSize = 14.dp,
                                                            onClick = { onRemoveHost(host.hubId) },
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // Section 3: Keyboard Shortcuts
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = Everforest.Bg0,
                        border = BorderStroke(1.dp, Everforest.Bg2),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Text(
                                text = "桌面端快捷键",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = Everforest.Fg,
                            )
                            ShortcutRow("新建会话", "⌘N", "收起 / 展开侧边栏", "⌘B")
                            ShortcutRow("切换引擎与模型", "⌘K", "查看代码变更与快照", "⇧⌘D")
                            ShortcutRow("刷新会话列表", "⌘R", "打开设置", "⌘,")
                            ShortcutRow("发送消息", "Enter", "输入框换行", "Shift+Enter")
                        }
                    }
                }

                HorizontalDivider(color = Everforest.Bg2)

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "版本 ${appVersionLabel()} · 登录身份：${state.authUserName ?: "本人"}",
                        fontSize = 11.5.sp,
                        color = Everforest.Grey0,
                    )
                    TextButton(
                        onClick = {
                            onDismiss()
                            onLogout()
                        },
                        modifier = Modifier.testTag("settings_btn_logout"),
                    ) {
                        Text(
                            text = "断开连接",
                            fontSize = 12.5.sp,
                            color = Everforest.Red,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ShortcutRow(
    leftLabel: String,
    leftKey: String,
    rightLabel: String,
    rightKey: String,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(leftLabel, fontSize = 11.5.sp, color = Everforest.Grey2)
            Text(
                text = leftKey,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                color = Everforest.BoldGold,
            )
        }
        Row(
            modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(rightLabel, fontSize = 11.5.sp, color = Everforest.Grey2)
            Text(
                text = rightKey,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                color = Everforest.BoldGold,
            )
        }
    }
}
