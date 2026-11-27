package dev.lework.ui.desktop

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.lework.platform.appVersionLabel
import dev.lework.platform.canConnectViaSsh
import dev.lework.platform.loadSavedTsnetControlUrl
import dev.lework.store.ConnectionStatus
import dev.lework.store.ConsoleUiState
import dev.lework.ui.Everforest
import dev.lework.ui.ModalEscapeEffect
import dev.lework.ui.TsnetDiscoveredHubList
import dev.lework.ui.dialogEscapeDismiss

@Composable
internal fun DesktopAuthScreen(
    state: ConsoleUiState,
    onLaunchOAuth: () -> Unit,
    onOpenBrowser: () -> Unit,
    onManualConnect: (String, String) -> Unit,
    onRetry: () -> Unit,
    onResetCredentials: () -> Unit,
    onPasteClipboardPairing: () -> Unit,
    onConfirmClipboardPairing: () -> Unit = onPasteClipboardPairing,
    onDismissClipboardPairing: () -> Unit = {},
    onPairViaSsh: (String) -> Unit,
    onStartTsnetLogin: (String?) -> Unit,
    onSearchTsnetPeers: () -> Unit,
    onSelectTsnetHub: (String) -> Unit = {},
    onLogoutTsnet: () -> Unit,
    onSelectPairedHost: (String) -> Unit,
    onCancelPairAnotherHost: () -> Unit = {},
) {
    var showManualForm by remember(state.oauthEnabled, state.connectionStatus) { mutableStateOf(false) }
    var showCustomControlUrl by remember { mutableStateOf(!loadSavedTsnetControlUrl().isNullOrBlank()) }
    var customControlUrl by remember { mutableStateOf(loadSavedTsnetControlUrl().orEmpty()) }
    var manualHost by remember(state.discoveredBaseUrl) { mutableStateOf(state.discoveredBaseUrl) }
    var manualToken by remember { mutableStateOf("") }
    var sshTargetInput by remember { mutableStateOf(state.sshConfigHosts.firstOrNull().orEmpty()) }
    var confirmResetCredentials by remember { mutableStateOf(false) }
    var confirmLogoutTsnet by remember { mutableStateOf(false) }
    val sshSupported = remember { canConnectViaSsh() }
    val isPairingLinkInput = remember(manualHost) {
        val trimmed = manualHost.trim()
        trimmed.startsWith("lework://", ignoreCase = true) || trimmed.startsWith("{")
    }

    ModalEscapeEffect(
        enabled = state.canReturnToWorkspace && !confirmResetCredentials && !confirmLogoutTsnet,
        onDismiss = onCancelPairAnotherHost,
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Everforest.Bg0)
            .let { mod ->
                if (state.canReturnToWorkspace && !confirmResetCredentials && !confirmLogoutTsnet) {
                    mod.dialogEscapeDismiss(onDismiss = onCancelPairAnotherHost)
                } else {
                    mod
                }
            }
            .padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            shape = RoundedCornerShape(18.dp),
            color = Everforest.Bg1,
            border = BorderStroke(1.dp, Everforest.Bg3),
            modifier = Modifier
                .widthIn(max = 500.dp)
                .fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (state.canReturnToWorkspace) {
                    val returnHost = state.returnWorkspaceHostName?.takeIf { it.isNotBlank() } ?: "当前主机"
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = Everforest.Bg0,
                            border = BorderStroke(1.dp, Everforest.Bg3),
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .clickable(onClick = onCancelPairAnotherHost)
                                .testTag("auth_btn_return_workspace"),
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(5.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                                    contentDescription = "返回工作台",
                                    tint = Everforest.Green,
                                    modifier = Modifier.size(15.dp),
                                )
                                Text(
                                    text = "返回「$returnHost」",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = Everforest.Fg,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                        Text(
                            text = "Esc 返回",
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            color = Everforest.Grey0,
                        )
                    }
                }

                dev.lework.ui.LeworkBrandMark(size = 54.dp)

                Text(
                    text = if (state.canReturnToWorkspace) "连接其他 Lework Hub 主机" else "Lework Desktop",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Everforest.Fg,
                )

                Text(
                    text = "连接本机或远程 Lework Hub 主机 · SSH · Tailscale",
                    fontSize = 12.5.sp,
                    color = Everforest.Grey2,
                    textAlign = TextAlign.Center,
                )

                when (state.connectionStatus) {
                    ConnectionStatus.DISCOVERING,
                    ConnectionStatus.CONNECTING,
                    ConnectionStatus.RECONNECTING -> {
                        CircularProgressIndicator(
                            color = Everforest.Green,
                            strokeWidth = 2.5.dp,
                            modifier = Modifier.size(26.dp),
                        )
                        Text(
                            text = state.authStatusText,
                            fontSize = 12.sp,
                            color = Everforest.Grey1,
                            textAlign = TextAlign.Center,
                        )
                    }
                    ConnectionStatus.NEEDS_AUTH -> {
                        val challenge = state.oauthChallenge
                        val providerLabel = state.oauthDisplayName ?: "SSO"
                        if (state.oauthEnabled) {
                            if (challenge != null) {
                                Button(
                                    onClick = onLaunchOAuth,
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = Everforest.Green,
                                        contentColor = Everforest.BgDim,
                                    ),
                                    shape = RoundedCornerShape(10.dp),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(42.dp),
                                ) {
                                    Text("唤起${providerLabel}授权一键确认", fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold)
                                }
                                TextButton(onClick = onOpenBrowser) {
                                    Text("在浏览器中打开授权页 (${challenge.userCode})", fontSize = 12.sp, color = Everforest.Grey2)
                                }
                            } else {
                                CircularProgressIndicator(
                                    color = Everforest.Green,
                                    strokeWidth = 2.5.dp,
                                    modifier = Modifier.size(26.dp),
                                )
                            }
                        }
                        if (!state.connectionError.isNullOrBlank()) {
                            Text(
                                text = state.connectionError,
                                fontSize = 12.5.sp,
                                color = Everforest.Red,
                                textAlign = TextAlign.Center,
                            )
                        }
                        Text(
                            text = state.authStatusText,
                            fontSize = 12.sp,
                            color = Everforest.Grey1,
                            textAlign = TextAlign.Center,
                        )
                    }
                    ConnectionStatus.ERROR -> {
                        Text(
                            text = state.connectionError ?: "连接失败",
                            fontSize = 12.5.sp,
                            color = Everforest.Red,
                            textAlign = TextAlign.Center,
                        )
                        Button(
                            onClick = onRetry,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Everforest.Green,
                                contentColor = Everforest.BgDim,
                            ),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("重试自动发现", fontWeight = FontWeight.SemiBold)
                        }
                    }
                    ConnectionStatus.CONNECTED -> Unit
                }

                val showAuthOptions = state.connectionStatus == ConnectionStatus.NEEDS_AUTH ||
                    state.connectionStatus == ConnectionStatus.ERROR ||
                    (state.connectionStatus == ConnectionStatus.CONNECTING && state.discoveredTsnetHubs.isNotEmpty())
                if (showAuthOptions) {
                    val clipPreview = state.clipboardPairingPreview
                    if (clipPreview != null) {
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = Everforest.BgGreen,
                            border = BorderStroke(1.dp, Everforest.Green.copy(alpha = 0.55f)),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("auth_clipboard_quick_pair_card"),
                        ) {
                            Column(
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 11.dp),
                                verticalArrangement = Arrangement.spacedBy(7.dp),
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                                        modifier = Modifier.weight(1f),
                                    ) {
                                        Icon(
                                            imageVector = Icons.Rounded.ContentPaste,
                                            contentDescription = null,
                                            tint = Everforest.Green,
                                            modifier = Modifier.size(16.dp),
                                        )
                                        Text(
                                            text = "检测到剪贴板中的配对链接",
                                            fontSize = 12.5.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            color = Everforest.Green,
                                        )
                                    }
                                    Icon(
                                        imageVector = Icons.Rounded.Close,
                                        contentDescription = "忽略剪贴板配对链接",
                                        tint = Everforest.Grey1,
                                        modifier = Modifier
                                            .size(15.dp)
                                            .clickable(onClick = onDismissClipboardPairing)
                                            .testTag("auth_clipboard_dismiss_btn"),
                                    )
                                }
                                Text(
                                    text = buildString {
                                        append(clipPreview.hostName)
                                        append(" · ")
                                        append(clipPreview.primaryUrl)
                                        clipPreview.shortFingerprint?.let { append(" · 指纹 $it") }
                                    },
                                    fontSize = 11.5.sp,
                                    color = Everforest.Fg,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Button(
                                    onClick = onConfirmClipboardPairing,
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = Everforest.Green,
                                        contentColor = Everforest.BgDim,
                                    ),
                                    shape = RoundedCornerShape(9.dp),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(38.dp)
                                        .testTag("auth_clipboard_quick_pair_btn"),
                                ) {
                                    Text("一键配对连接「${clipPreview.hostName}」", fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold)
                                }
                            }
                        }
                    }

                    if (sshSupported) {
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = Everforest.Bg0,
                            border = BorderStroke(1.dp, Everforest.Bg3),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Column(
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.Terminal,
                                        contentDescription = null,
                                        tint = Everforest.Aqua,
                                        modifier = Modifier.size(17.dp),
                                    )
                                    Text(
                                        text = "通过 SSH 一键连接远程主机",
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = Everforest.Fg,
                                    )
                                }
                                Text(
                                    text = "自动调用本机 SSH 获取一次性配对凭证并校验主机指纹（端口受限时自动建立本地加密转发）",
                                    fontSize = 11.sp,
                                    lineHeight = 15.5.sp,
                                    color = Everforest.Grey1,
                                )
                                if (state.sshConfigHosts.isNotEmpty()) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Text("快捷选择：", fontSize = 11.sp, color = Everforest.CommentGrey)
                                        for (alias in state.sshConfigHosts.take(4)) {
                                            Surface(
                                                shape = RoundedCornerShape(999.dp),
                                                color = if (sshTargetInput == alias) Everforest.BgGreen else Everforest.Bg2,
                                                border = BorderStroke(
                                                    1.dp,
                                                    if (sshTargetInput == alias) Everforest.Green else Everforest.Bg3,
                                                ),
                                                modifier = Modifier
                                                    .clip(RoundedCornerShape(999.dp))
                                                    .clickable(enabled = !state.sshPairingInProgress) {
                                                        sshTargetInput = alias
                                                        onPairViaSsh(alias)
                                                    },
                                            ) {
                                                Text(
                                                    text = alias,
                                                    fontSize = 11.5.sp,
                                                    color = if (sshTargetInput == alias) Everforest.Green else Everforest.Fg,
                                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                                                )
                                            }
                                        }
                                    }
                                }
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    OutlinedTextField(
                                        value = sshTargetInput,
                                        onValueChange = { sshTargetInput = it },
                                        placeholder = { Text("如 devbox 或 user@192.168.10.20", fontSize = 11.5.sp) },
                                        singleLine = true,
                                        modifier = Modifier.weight(1f),
                                    )
                                    Button(
                                        onClick = { onPairViaSsh(sshTargetInput) },
                                        enabled = sshTargetInput.isNotBlank() && !state.sshPairingInProgress,
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = Everforest.BgGreen,
                                            contentColor = Everforest.Green,
                                        ),
                                        shape = RoundedCornerShape(10.dp),
                                        modifier = Modifier.height(48.dp),
                                    ) {
                                        Text("连接主机", fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold)
                                    }
                                }
                            }
                        }
                    }

                    if (clipPreview == null) {
                        OutlinedButton(
                            onClick = onPasteClipboardPairing,
                            border = BorderStroke(1.dp, Everforest.Green.copy(alpha = 0.55f)),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Everforest.Fg),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(40.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.ContentPaste,
                                contentDescription = null,
                                tint = Everforest.Green,
                                modifier = Modifier.size(16.dp),
                            )
                            Spacer(Modifier.width(7.dp))
                            Text("粘贴剪贴板配对链接连接 (lework pair)", fontSize = 12.5.sp, fontWeight = FontWeight.Medium)
                        }
                    }

                    if (state.tsnetStatus.available) {
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = Everforest.Bg0,
                            border = BorderStroke(1.dp, Everforest.Bg3),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Column(
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.Hub,
                                        contentDescription = null,
                                        tint = Everforest.BoldGold,
                                        modifier = Modifier.size(17.dp),
                                    )
                                    Text(
                                        text = "Tailscale 同账号自动组网",
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = Everforest.Fg,
                                    )
                                }
                                if (state.tsnetStatus.backendState == "Running") {
                                    val totalPeers = state.tsnetStatus.peers.size
                                    val onlinePeers = state.tsnetStatus.peers.count { it.online }
                                    val peerHint = when {
                                        onlinePeers > 0 -> "同账号在线节点 $onlinePeers 台"
                                        totalPeers > 0 -> "同账号已注册节点 $totalPeers 台"
                                        else -> "暂未发现其他同账号节点"
                                    }
                                    Text(
                                        text = "当前账号：${state.tsnetStatus.loginName ?: "已登录"} · $peerHint",
                                        fontSize = 11.5.sp,
                                        color = Everforest.Green,
                                    )
                                    if (state.discoveredTsnetHubs.isNotEmpty()) {
                                        TsnetDiscoveredHubList(
                                            hubs = state.discoveredTsnetHubs,
                                            connectingHubId = state.connectingTsnetHubId,
                                            onSelectHub = onSelectTsnetHub,
                                        )
                                    }
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    ) {
                                        Button(
                                            onClick = onSearchTsnetPeers,
                                            enabled = state.connectingTsnetHubId == null,
                                            colors = ButtonDefaults.buttonColors(
                                                containerColor = Everforest.Bg3,
                                                contentColor = Everforest.Fg,
                                            ),
                                            shape = RoundedCornerShape(10.dp),
                                            modifier = Modifier
                                                .weight(1f)
                                                .testTag("tsnet_search_button"),
                                        ) {
                                            Text(
                                                text = if (state.discoveredTsnetHubs.isNotEmpty()) "重新搜索节点" else "搜索并握手",
                                                fontSize = 12.5.sp,
                                            )
                                        }
                                        TextButton(
                                            onClick = { confirmLogoutTsnet = true },
                                            modifier = Modifier.testTag("auth_btn_logout_tsnet"),
                                        ) {
                                            Text("退出账号", fontSize = 12.sp, color = Everforest.Grey1)
                                        }
                                    }
                                } else {
                                    if (showCustomControlUrl) {
                                        OutlinedTextField(
                                            value = customControlUrl,
                                            onValueChange = { customControlUrl = it },
                                            label = { Text("自建服务器地址（如 Headscale，可选）", fontSize = 11.sp) },
                                            singleLine = true,
                                            modifier = Modifier.fillMaxWidth(),
                                        )
                                    }
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                    ) {
                                        Button(
                                            onClick = { onStartTsnetLogin(customControlUrl.ifBlank { null }) },
                                            enabled = !state.tsnetLoggingIn,
                                            colors = ButtonDefaults.buttonColors(
                                                containerColor = Everforest.BgGreen,
                                                contentColor = Everforest.Green,
                                            ),
                                            shape = RoundedCornerShape(10.dp),
                                        ) {
                                            Text("登录 Tailscale 自动建联", fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold)
                                        }
                                        TextButton(onClick = { showCustomControlUrl = !showCustomControlUrl }) {
                                            Text(
                                                text = if (showCustomControlUrl) "使用官方服务" else "使用自建服务器",
                                                fontSize = 11.sp,
                                                color = Everforest.CommentGrey,
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    if (state.pairedHosts.isNotEmpty()) {
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Text("已配对的 Lework Hub 主机", fontSize = 11.5.sp, color = Everforest.Grey1)
                            for (host in state.pairedHosts.take(4)) {
                                Surface(
                                    shape = RoundedCornerShape(10.dp),
                                    color = Everforest.Bg0,
                                    border = BorderStroke(1.dp, Everforest.Bg3),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(10.dp))
                                        .clickable { onSelectPairedHost(host.hubId) },
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = host.displayName,
                                                fontSize = 13.sp,
                                                fontWeight = FontWeight.Medium,
                                                color = Everforest.Fg,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                            host.endpoints.firstOrNull()?.let { ep ->
                                                Text(
                                                    text = ep,
                                                    fontSize = 11.sp,
                                                    color = Everforest.Grey0,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis,
                                                )
                                            }
                                        }
                                        Icon(
                                            imageVector = Icons.Rounded.ChevronRight,
                                            contentDescription = null,
                                            tint = Everforest.Grey1,
                                            modifier = Modifier.size(16.dp),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    TextButton(
                        onClick = { showManualForm = !showManualForm },
                        modifier = Modifier.testTag("auth_btn_toggle_manual"),
                    ) {
                        Text(
                            text = if (showManualForm) "收起手动连接" else "手动填写主机地址",
                            fontSize = 12.sp,
                            color = Everforest.CommentGrey,
                        )
                    }
                    if (state.connectionStatus == ConnectionStatus.ERROR) {
                        TextButton(
                            onClick = { confirmResetCredentials = true },
                            modifier = Modifier.testTag("auth_btn_reset_credentials"),
                        ) {
                            Text("清除旧凭证", fontSize = 12.sp, color = Everforest.Grey1)
                        }
                    }
                }

                if (showManualForm) {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        OutlinedTextField(
                            value = manualHost,
                            onValueChange = { manualHost = it },
                            label = { Text("主机地址或 lework://pair 配对链接", fontSize = 11.5.sp) },
                            singleLine = true,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("auth_manual_host_input"),
                        )
                        if (!isPairingLinkInput) {
                            OutlinedTextField(
                                value = manualToken,
                                onValueChange = { manualToken = it },
                                label = { Text("访问令牌", fontSize = 11.5.sp) },
                                singleLine = true,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("auth_manual_token_input"),
                            )
                        }
                        Button(
                            onClick = { onManualConnect(manualHost, if (isPairingLinkInput) "" else manualToken) },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Everforest.Bg3,
                                contentColor = Everforest.Fg,
                            ),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("auth_manual_submit_btn"),
                        ) {
                            Text(
                                text = if (isPairingLinkInput) "验证并配对" else "保存并连接",
                                fontWeight = FontWeight.Medium,
                            )
                        }
                    }
                }

                Text(
                    text = appVersionLabel(),
                    fontSize = 11.sp,
                    color = Everforest.Grey0,
                )
            }
        }
    }

    if (confirmResetCredentials) {
        ModalEscapeEffect(onDismiss = { confirmResetCredentials = false })
        AlertDialog(
            onDismissRequest = { confirmResetCredentials = false },
            containerColor = Everforest.Bg1,
            titleContentColor = Everforest.Fg,
            textContentColor = Everforest.Grey2,
            title = { Text("清除所有已保存的主机凭证？", fontSize = 16.sp, fontWeight = FontWeight.SemiBold) },
            text = {
                Text(
                    text = "将删除全部已配对的 Lework Hub 主机记录、访问令牌和证书指纹，之后连接任意主机都需要重新配对。",
                    fontSize = 13.sp,
                    lineHeight = 19.sp,
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        confirmResetCredentials = false
                        onResetCredentials()
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Everforest.Red,
                        contentColor = Everforest.BgDim,
                    ),
                    modifier = Modifier.testTag("confirm_reset_credentials_btn"),
                ) {
                    Text("确认清除", fontWeight = FontWeight.SemiBold)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmResetCredentials = false }) {
                    Text("取消", color = Everforest.Grey1)
                }
            },
        )
    }

    if (confirmLogoutTsnet) {
        ModalEscapeEffect(onDismiss = { confirmLogoutTsnet = false })
        AlertDialog(
            onDismissRequest = { confirmLogoutTsnet = false },
            containerColor = Everforest.Bg1,
            titleContentColor = Everforest.Fg,
            textContentColor = Everforest.Grey2,
            title = { Text("退出 Tailscale 账号？", fontSize = 16.sp, fontWeight = FontWeight.SemiBold) },
            text = {
                Text(
                    text = "退出后将停止当前客户端内置的 Tailscale 组网，可随时重新登录恢复。",
                    fontSize = 13.sp,
                    lineHeight = 19.sp,
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        confirmLogoutTsnet = false
                        onLogoutTsnet()
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Everforest.Red,
                        contentColor = Everforest.BgDim,
                    ),
                    modifier = Modifier.testTag("confirm_logout_tsnet_btn"),
                ) {
                    Text("确认退出", fontWeight = FontWeight.SemiBold)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmLogoutTsnet = false }) {
                    Text("取消", color = Everforest.Grey1)
                }
            },
        )
    }
}
