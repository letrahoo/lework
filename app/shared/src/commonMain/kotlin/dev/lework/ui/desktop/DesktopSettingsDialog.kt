package dev.lework.ui.desktop

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Computer
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.lework.platform.PairedHost
import dev.lework.platform.appVersionLabel
import dev.lework.platform.copyTextToClipboard
import dev.lework.store.ConsoleUiState
import dev.lework.ui.EngineGlyphIcon
import dev.lework.ui.Everforest
import dev.lework.ui.RenameHostDialog
import dev.lework.ui.connectionChannelLabel
import dev.lework.ui.shortEngineLabel

@Composable
internal fun DesktopSettingsDialog(
    state: ConsoleUiState,
    onDismiss: () -> Unit,
    onSwitchHost: (String) -> Unit,
    onRenameHost: (hubId: String, newName: String) -> Unit = { _, _ -> },
    onRemoveHost: (String) -> Unit,
    onPairAnotherHost: () -> Unit,
    onLogout: () -> Unit,
) {
    var copiedFingerprint by remember { mutableStateOf(false) }
    var renamingHost by remember { mutableStateOf<PairedHost?>(null) }
    val onlineCount = state.engines.count { it.status.ready }

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
                .width(540.dp)
                .heightIn(max = 720.dp)
                .onPreviewKeyEvent { ev ->
                    if (ev.type == KeyEventType.KeyDown && ev.key == Key.Escape) {
                        onDismiss()
                        true
                    } else {
                        false
                    }
                }
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
                                    modifier = Modifier.weight(1f),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.Computer,
                                        contentDescription = null,
                                        tint = Everforest.Green,
                                        modifier = Modifier.size(17.dp),
                                    )
                                    Text(
                                        text = state.activeHostDisplayName,
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = Everforest.Fg,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f, fill = false),
                                    )
                                    val activeHub = state.hub
                                    if (activeHub != null) {
                                        LeworkIconActionButton(
                                            icon = Icons.Rounded.Edit,
                                            tooltip = "重命名主机",
                                            size = 24.dp,
                                            iconSize = 13.5.dp,
                                            testTag = "settings_btn_rename_current_host",
                                            onClick = {
                                                renamingHost = state.pairedHosts.firstOrNull { it.hubId == activeHub.id }
                                                    ?: PairedHost(hubId = activeHub.id, name = activeHub.name)
                                            },
                                        )
                                    }
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
                                                EngineGlyphIcon(
                                                    engineId = eng.id,
                                                    size = 13.dp,
                                                    tintOverride = if (ready) null else Everforest.Grey0.copy(alpha = 0.5f),
                                                )
                                                Text(
                                                    text = shortEngineLabel(eng.id),
                                                    fontSize = 11.sp,
                                                    color = if (ready) Everforest.Fg else Everforest.Grey0,
                                                )
                                                Box(
                                                    modifier = Modifier
                                                        .size(5.dp)
                                                        .clip(CircleShape)
                                                        .background(if (ready) Everforest.Green else Everforest.Grey0.copy(alpha = 0.55f)),
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
                                        val hasCustomAlias = !host.customName.isNullOrBlank() && host.customName.trim() != host.name
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
                                                Column(
                                                    modifier = Modifier.weight(1f),
                                                    verticalArrangement = Arrangement.spacedBy(1.dp),
                                                ) {
                                                    Text(
                                                        text = host.displayName,
                                                        fontSize = 12.5.sp,
                                                        fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Normal,
                                                        color = if (isCurrent) Everforest.Green else Everforest.Fg,
                                                        maxLines = 1,
                                                        overflow = TextOverflow.Ellipsis,
                                                    )
                                                    if (hasCustomAlias) {
                                                        Text(
                                                            text = "默认名称：${host.name}",
                                                            fontSize = 10.5.sp,
                                                            color = Everforest.CommentGrey,
                                                            maxLines = 1,
                                                            overflow = TextOverflow.Ellipsis,
                                                        )
                                                    }
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
                                                Row(
                                                    verticalAlignment = Alignment.CenterVertically,
                                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                                ) {
                                                    if (isCurrent) {
                                                        Text(
                                                            text = "当前连接",
                                                            fontSize = 11.sp,
                                                            color = Everforest.Green,
                                                        )
                                                    } else {
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
                                                    }
                                                    LeworkIconActionButton(
                                                        icon = Icons.Rounded.Edit,
                                                        tooltip = "重命名主机",
                                                        size = 24.dp,
                                                        iconSize = 13.5.dp,
                                                        testTag = "settings_btn_rename_host_${host.hubId}",
                                                        onClick = { renamingHost = host },
                                                    )
                                                    if (!isCurrent) {
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
                            DesktopShortcutRow("新建会话", "⌘N", "收起 / 展开侧边栏", "⌘B")
                            DesktopShortcutRow("切换引擎与模型", "⌘K", "查看代码变更与快照", "⇧⌘D")
                            DesktopShortcutRow("刷新会话列表", "⌘R", "打开设置", "⌘,")
                            DesktopShortcutRow("发送消息", "Enter", "输入框换行", "Shift+Enter")
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

    val targetHost = renamingHost
    if (targetHost != null) {
        RenameHostDialog(
            host = targetHost,
            onDismiss = { renamingHost = null },
            onConfirm = { nextName ->
                renamingHost = null
                onRenameHost(targetHost.hubId, nextName)
            },
        )
    }
}

@Composable
private fun DesktopShortcutRow(
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
