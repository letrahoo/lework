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
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.lework.platform.GlobalCliInstallStatus
import dev.lework.platform.PairedHost
import dev.lework.platform.appVersionLabel
import dev.lework.platform.copyTextToClipboard
import dev.lework.platform.inspectGlobalCliStatus
import dev.lework.platform.installGlobalCliCommand
import dev.lework.platform.runtimeLogFilePath
import dev.lework.platform.uninstallGlobalCliCommand
import dev.lework.protocol.ProviderConfigInput
import dev.lework.store.ConsoleUiState
import dev.lework.ui.DesktopAppUpdateSection
import dev.lework.ui.EngineGlyphIcon
import dev.lework.ui.Everforest
import dev.lework.ui.ModalEscapeEffect
import dev.lework.ui.RenameHostDialog
import dev.lework.ui.connectionChannelLabel
import dev.lework.ui.dialogEscapeDismiss
import dev.lework.ui.shortEngineLabel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
internal fun DesktopSettingsDialog(
    state: ConsoleUiState,
    onDismiss: () -> Unit,
    onSwitchHost: (String) -> Unit,
    onRenameHost: (hubId: String, newName: String) -> Unit = { _, _ -> },
    onRemoveHost: (String) -> Unit,
    onPairAnotherHost: () -> Unit,
    onRefreshProviders: (providerId: String?) -> Unit = {},
    onProbeProviderDraft: (ProviderConfigInput) -> Unit = {},
    onUpsertProvider: (ProviderConfigInput, onSuccess: () -> Unit) -> Unit = { _, _ -> },
    onRemoveProvider: (providerId: String) -> Unit = {},
    onClearProviderFeedback: () -> Unit = {},
    onCheckForUpdates: () -> Unit = {},
    onOpenAppUpdateDialog: () -> Unit = {},
    autoInstallGlobalCliNonce: Int = 0,
    onLogout: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var copiedFingerprint by remember { mutableStateOf(false) }
    var copiedLogPath by remember { mutableStateOf(false) }
    var copiedPathExport by remember { mutableStateOf(false) }
    var renamingHost by remember { mutableStateOf<PairedHost?>(null) }
    var confirmingRemoveHost by remember { mutableStateOf<PairedHost?>(null) }
    var confirmingLogout by remember { mutableStateOf(false) }
    var cliStatus by remember { mutableStateOf(inspectGlobalCliStatus()) }
    var cliBusy by remember { mutableStateOf(false) }
    var cliFeedback by remember { mutableStateOf<String?>(null) }
    var cliFeedbackIsError by remember { mutableStateOf(false) }
    val onlineCount = state.engines.count { it.status.ready }
    val logPath = remember { runtimeLogFilePath() }

    LaunchedEffect(copiedFingerprint) {
        if (copiedFingerprint) {
            delay(1_600L)
            copiedFingerprint = false
        }
    }
    LaunchedEffect(copiedLogPath) {
        if (copiedLogPath) {
            delay(1_600L)
            copiedLogPath = false
        }
    }
    LaunchedEffect(copiedPathExport) {
        if (copiedPathExport) {
            delay(1_600L)
            copiedPathExport = false
        }
    }

    fun triggerGlobalCliInstall() {
        if (cliBusy) return
        cliBusy = true
        cliFeedback = null
        cliFeedbackIsError = false
        copiedPathExport = false
        scope.launch {
            val result = installGlobalCliCommand()
            result.onSuccess { next ->
                cliStatus = next
                cliFeedback = next.detail ?: "已注册全局 lework 命令"
                cliFeedbackIsError = false
            }.onFailure { err ->
                cliStatus = inspectGlobalCliStatus()
                cliFeedback = err.message ?: "注册全局 lework 命令失败"
                cliFeedbackIsError = true
            }
            cliBusy = false
        }
    }

    fun triggerGlobalCliUninstall() {
        if (cliBusy) return
        cliBusy = true
        cliFeedback = null
        cliFeedbackIsError = false
        copiedPathExport = false
        scope.launch {
            val result = uninstallGlobalCliCommand()
            result.onSuccess { next ->
                cliStatus = next
                cliFeedback = "已移除全局 lework 命令链接"
                cliFeedbackIsError = false
            }.onFailure { err ->
                cliStatus = inspectGlobalCliStatus()
                cliFeedback = err.message ?: "移除全局 lework 命令失败"
                cliFeedbackIsError = true
            }
            cliBusy = false
        }
    }

    LaunchedEffect(autoInstallGlobalCliNonce) {
        if (autoInstallGlobalCliNonce > 0 && cliStatus.supported) {
            triggerGlobalCliInstall()
        }
    }

    ModalEscapeEffect(onDismiss = onDismiss)

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
                .dialogEscapeDismiss(onDismiss = onDismiss)
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
                            text = "管理当前主机连接、安全凭证与桌面端快捷键",
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
                    if (state.appUpdate.hasUpdate) {
                        DesktopAppUpdateSection(
                            updateState = state.appUpdate,
                            onCheckForUpdates = onCheckForUpdates,
                            onOpenUpdateDialog = onOpenAppUpdateDialog,
                        )
                    }

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

                    // Section 2: Host-Scoped External Model Providers (CPA / OpenRouter / LiteLLM / Custom)
                    DesktopProvidersSection(
                        state = state,
                        onRefreshProviders = onRefreshProviders,
                        onProbeDraft = onProbeProviderDraft,
                        onUpsertProvider = onUpsertProvider,
                        onRemoveProvider = onRemoveProvider,
                        onClearFeedback = onClearProviderFeedback,
                    )

                    // Section 3: Paired Hosts (Mac + Remote Devbox)
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
                                    text = "已配对主机",
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = Everforest.Fg,
                                )
                                Text(
                                    text = "＋ 连接其他主机",
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
                                    text = "当前使用本机直连，可通过右上按钮一键 SSH 连接远程 Linux 主机。",
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
                                                            tooltip = "移除此主机记录",
                                                            size = 24.dp,
                                                            iconSize = 14.dp,
                                                            testTag = "settings_btn_remove_host_${host.hubId}",
                                                            onClick = { confirmingRemoveHost = host },
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
                            DesktopShortcutRow("全局唤起 / 隐藏窗口", "⌃⌘E", "关闭窗口到后台", "⌘W")
                            DesktopShortcutRow("关闭当前弹层", "Esc", "打开设置", "⌘,")
                            DesktopShortcutRow("新建会话", "⌘N", "收起 / 展开侧边栏", "⌘B")
                            DesktopShortcutRow("切换相邻会话", "⌥↑ / ⌥↓", "切换前 9 个会话", "⌘1~9")
                            DesktopShortcutRow("会话历史后退 / 前进", "⌘[ / ⌘]", "全局搜索 / 会话内查找", "⇧⌘F / ⌘F")
                            DesktopShortcutRow("切换引擎与模型", "⌘K", "代码变更 / 会话产物", "⇧⌘D / ⇧⌘A")
                            DesktopShortcutRow("命令面板 / 引用文件", "/ / @", "发送 / 换行", "Enter / ⇧↩")
                        }
                    }

                    if (cliStatus.supported) {
                        DesktopGlobalCliSection(
                            status = cliStatus,
                            busy = cliBusy,
                            feedback = cliFeedback,
                            feedbackIsError = cliFeedbackIsError,
                            copiedPathExport = copiedPathExport,
                            onInstall = ::triggerGlobalCliInstall,
                            onUninstall = ::triggerGlobalCliUninstall,
                            onCopyPathExport = { exportLine ->
                                copyTextToClipboard(exportLine)
                                copiedPathExport = true
                            },
                        )
                    }

                    if (!state.appUpdate.hasUpdate) {
                        DesktopAppUpdateSection(
                            updateState = state.appUpdate,
                            onCheckForUpdates = onCheckForUpdates,
                            onOpenUpdateDialog = onOpenAppUpdateDialog,
                        )
                    }

                    if (!logPath.isNullOrBlank()) {
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = Everforest.Bg0,
                            border = BorderStroke(1.dp, Everforest.Bg2),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 14.dp, vertical = 10.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(
                                    modifier = Modifier.weight(1f),
                                    verticalArrangement = Arrangement.spacedBy(2.dp),
                                ) {
                                    Text(
                                        text = "运行日志",
                                        fontSize = 12.5.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = Everforest.Fg,
                                    )
                                    Text(
                                        text = logPath,
                                        fontSize = 10.5.sp,
                                        fontFamily = FontFamily.Monospace,
                                        color = Everforest.Grey0,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                Text(
                                    text = if (copiedLogPath) "已复制路径" else "复制路径",
                                    fontSize = 11.5.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = Everforest.Green,
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(6.dp))
                                        .clickable {
                                            copyTextToClipboard(logPath)
                                            copiedLogPath = true
                                        }
                                        .padding(horizontal = 8.dp, vertical = 4.dp)
                                        .testTag("settings_btn_copy_runtime_log_path"),
                                )
                            }
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
                        onClick = { confirmingLogout = true },
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

    if (confirmingLogout) {
        ModalEscapeEffect(onDismiss = { confirmingLogout = false })
        AlertDialog(
            onDismissRequest = { confirmingLogout = false },
            containerColor = Everforest.Bg1,
            titleContentColor = Everforest.Fg,
            textContentColor = Everforest.Grey2,
            title = { Text("确认断开当前主机连接？", fontSize = 16.sp, fontWeight = FontWeight.SemiBold) },
            text = {
                Text(
                    text = "断开后将退出当前连接，你的历史会话和项目文件均安全保留在主机上，可随时重新连接。",
                    fontSize = 13.sp,
                    lineHeight = 19.sp,
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        confirmingLogout = false
                        onDismiss()
                        onLogout()
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Everforest.Red,
                        contentColor = Everforest.BgDim,
                    ),
                    modifier = Modifier.testTag("settings_confirm_logout_btn"),
                ) {
                    Text("确认断开", fontWeight = FontWeight.SemiBold)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmingLogout = false }) {
                    Text("取消", color = Everforest.Grey1)
                }
            },
        )
    }

    val hostToRemove = confirmingRemoveHost
    if (hostToRemove != null) {
        ModalEscapeEffect(onDismiss = { confirmingRemoveHost = null })
        AlertDialog(
            onDismissRequest = { confirmingRemoveHost = null },
            containerColor = Everforest.Bg1,
            titleContentColor = Everforest.Fg,
            textContentColor = Everforest.Grey2,
            title = { Text("移除主机记录？", fontSize = 16.sp, fontWeight = FontWeight.SemiBold) },
            text = {
                Text(
                    text = "将移除「${hostToRemove.displayName}」的本地配对记录与访问凭证，后续连接需重新配对。",
                    fontSize = 13.sp,
                    lineHeight = 19.sp,
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        confirmingRemoveHost = null
                        onRemoveHost(hostToRemove.hubId)
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Everforest.Red,
                        contentColor = Everforest.BgDim,
                    ),
                    modifier = Modifier.testTag("settings_confirm_remove_host_btn"),
                ) {
                    Text("确认移除", fontWeight = FontWeight.SemiBold)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmingRemoveHost = null }) {
                    Text("取消", color = Everforest.Grey1)
                }
            },
        )
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

@Composable
private fun DesktopGlobalCliSection(
    status: GlobalCliInstallStatus,
    busy: Boolean,
    feedback: String?,
    feedbackIsError: Boolean,
    copiedPathExport: Boolean,
    onInstall: () -> Unit,
    onUninstall: () -> Unit,
    onCopyPathExport: (String) -> Unit,
) {
    val badgeBg = when {
        status.installed && status.inShellPath -> Everforest.BgGreen
        status.installed -> Everforest.Bg2
        else -> Everforest.Bg1
    }
    val badgeBorder = when {
        status.installed && status.inShellPath -> Everforest.Green.copy(alpha = 0.35f)
        status.installed -> Everforest.BoldGold.copy(alpha = 0.45f)
        else -> Everforest.Bg3
    }
    val badgeColor = when {
        status.installed && status.inShellPath -> Everforest.Green
        status.installed -> Everforest.BoldGold
        else -> Everforest.Grey0
    }
    val badgeText = when {
        status.installed && status.inShellPath -> "已注册"
        status.installed -> "需配置 PATH"
        else -> "未注册"
    }

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = Everforest.Bg0,
        border = BorderStroke(1.dp, Everforest.Bg2),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("settings_global_cli_section"),
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
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Terminal,
                        contentDescription = null,
                        tint = Everforest.BoldGold,
                        modifier = Modifier.size(16.dp),
                    )
                    Text(
                        text = "全局命令行工具 (lework)",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Everforest.Fg,
                    )
                }
                Surface(
                    shape = RoundedCornerShape(999.dp),
                    color = badgeBg,
                    border = BorderStroke(1.dp, badgeBorder),
                ) {
                    Text(
                        text = badgeText,
                        fontSize = 11.sp,
                        color = badgeColor,
                        modifier = Modifier
                            .padding(horizontal = 8.dp, vertical = 3.dp)
                            .testTag("settings_global_cli_badge"),
                    )
                }
            }

            Text(
                text = "注册后可在任意终端直接执行 lework tsnet status、lework pair、lework serve 等命令，无需手动启动后台服务。",
                fontSize = 11.5.sp,
                lineHeight = 16.sp,
                color = Everforest.Grey1,
            )

            status.installedPath?.takeIf { it.isNotBlank() }?.let { path ->
                Text(
                    text = "命令路径：$path",
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    color = Everforest.Grey2,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.testTag("settings_global_cli_path"),
                )
            }

            if (status.installed && !status.inShellPath) {
                val dir = status.installDirectory?.takeIf { it.isNotBlank() }
                if (dir != null) {
                    val exportLine = "export PATH=\"$dir:\$PATH\""
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = exportLine,
                            fontSize = 10.5.sp,
                            fontFamily = FontFamily.Monospace,
                            color = Everforest.BoldGold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            text = if (copiedPathExport) "已复制" else "复制 PATH 配置",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium,
                            color = Everforest.Green,
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .clickable { onCopyPathExport(exportLine) }
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                                .testTag("settings_btn_copy_cli_path_export"),
                        )
                    }
                }
            }

            val feedbackText = feedback?.takeIf { it.isNotBlank() }
                ?: status.detail?.takeIf { !status.installed && it.isNotBlank() }
            if (feedbackText != null) {
                val feedbackColor = when {
                    feedbackIsError -> Everforest.Red
                    !feedback.isNullOrBlank() -> Everforest.Green
                    else -> Everforest.Grey1
                }
                Text(
                    text = feedbackText,
                    fontSize = 11.sp,
                    lineHeight = 15.sp,
                    color = feedbackColor,
                    modifier = Modifier.testTag("settings_global_cli_feedback"),
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Button(
                    onClick = onInstall,
                    enabled = !busy,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (status.installed && status.inShellPath) Everforest.Bg2 else Everforest.BgGreen,
                        contentColor = if (status.installed && status.inShellPath) Everforest.Fg else Everforest.Green,
                    ),
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                    modifier = Modifier.testTag("settings_btn_install_global_cli"),
                ) {
                    Text(
                        text = when {
                            busy -> "正在处理…"
                            status.installed && status.inShellPath -> "重新注册命令"
                            else -> "注册全局 lework 命令"
                        },
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                    )
                }
                if (status.installed) {
                    TextButton(
                        onClick = onUninstall,
                        enabled = !busy,
                        modifier = Modifier.testTag("settings_btn_uninstall_global_cli"),
                    ) {
                        Text(
                            text = "移除命令",
                            fontSize = 12.sp,
                            color = Everforest.Grey1,
                        )
                    }
                }
            }
        }
    }
}
