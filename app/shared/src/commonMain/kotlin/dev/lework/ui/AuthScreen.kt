package dev.lework.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.lework.platform.appVersionLabel
import dev.lework.platform.canScanQrCode
import dev.lework.platform.loadSavedTsnetControlUrl
import dev.lework.protocol.PlatformType
import dev.lework.store.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AuthEntryView(
    state: ConsoleUiState,
    onLaunchOAuth: () -> Unit,
    onOpenBrowser: () -> Unit,
    onManualConnect: (String, String) -> Unit,
    onRetry: () -> Unit,
    onResetCredentials: () -> Unit = {},
    onScanQr: () -> Unit = {},
    onStartTsnetLogin: (String?) -> Unit = {},
    onSearchTsnetPeers: () -> Unit = {},
    onSelectTsnetHub: (String) -> Unit = {},
    onLogoutTsnet: () -> Unit = {},
    onSelectPairedHost: (String) -> Unit = {},
    onCancelPairAnotherHost: () -> Unit = {},
) {
    var showManualForm by remember(state.oauthEnabled, state.connectionStatus) {
        mutableStateOf(false)
    }
    var showCustomControlUrl by remember {
        mutableStateOf(!loadSavedTsnetControlUrl().isNullOrBlank())
    }
    var customControlUrl by remember {
        mutableStateOf(loadSavedTsnetControlUrl().orEmpty())
    }
    var manualHost by remember(state.discoveredBaseUrl) {
        mutableStateOf(state.discoveredBaseUrl)
    }
    var manualToken by remember { mutableStateOf("") }
    var confirmResetCredentials by remember { mutableStateOf(false) }
    var confirmLogoutTsnet by remember { mutableStateOf(false) }
    val qrSupported = remember { canScanQrCode() }
    val dismissKeyboard = rememberKeyboardDismisser()
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
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() },
            ) { dismissKeyboard() }
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(20.dp),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = Everforest.Bg1,
            border = BorderStroke(1.dp, Everforest.Bg3),
            modifier = Modifier.fillMaxWidth(),
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
                        horizontalArrangement = Arrangement.Start,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = Everforest.Bg0,
                            border = BorderStroke(1.dp, Everforest.Bg3),
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .clickable {
                                    dismissKeyboard()
                                    onCancelPairAnotherHost()
                                }
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
                    }
                }

                LeworkBrandMark(size = 54.dp)

                Text(
                    text = if (state.canReturnToWorkspace) "连接其他 Lework Hub 主机" else "Lework",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Everforest.Fg,
                )

                Text(
                    text = "同一 Wi‑Fi 自动直连 · Tailscale 同账号组网 · 扫码配对",
                    fontSize = 12.5.sp,
                    lineHeight = 18.sp,
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
                            modifier = Modifier.size(28.dp),
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
                                    shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(46.dp),
                                ) {
                                    Text("唤起${providerLabel}授权一键确认", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                                }
                                TextButton(onClick = onOpenBrowser) {
                                    Text("在浏览器中打开授权页 (${challenge.userCode})", fontSize = 12.sp, color = Everforest.Grey2)
                                }
                            } else {
                                CircularProgressIndicator(
                                    color = Everforest.Green,
                                    strokeWidth = 2.5.dp,
                                    modifier = Modifier.size(28.dp),
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
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("重试自动发现", fontWeight = FontWeight.SemiBold)
                        }
                    }
                    ConnectionStatus.CONNECTED -> Unit
                }

                // Quick Pairing & Same-Account Tailscale Mesh Actions
                val showAuthOptions = state.connectionStatus == ConnectionStatus.NEEDS_AUTH ||
                    state.connectionStatus == ConnectionStatus.ERROR ||
                    (state.connectionStatus == ConnectionStatus.CONNECTING && state.discoveredTsnetHubs.isNotEmpty())
                if (showAuthOptions) {
                    if (qrSupported) {
                        OutlinedButton(
                            onClick = onScanQr,
                            border = BorderStroke(1.dp, Everforest.Green.copy(alpha = 0.55f)),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Everforest.Fg),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(44.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.QrCodeScanner,
                                contentDescription = null,
                                tint = Everforest.Green,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(Modifier.width(8.dp))
                            Text("扫码配对主机 (lework pair)", fontSize = 13.5.sp, fontWeight = FontWeight.Medium)
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
                                    } else if (state.tsnetStatus.peers.isNotEmpty()) {
                                        Text(
                                            text = "节点：${state.tsnetStatus.peers.take(3).joinToString(", ") { it.hostName }}",
                                            fontSize = 11.sp,
                                            color = Everforest.Grey1,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
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
                                    Text(
                                        text = "手机与电脑登录同一 Tailscale 账号即可跨网段免配握手（无需开启系统 VPN）",
                                        fontSize = 11.5.sp,
                                        lineHeight = 16.sp,
                                        color = Everforest.Grey1,
                                    )
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
                                            if (state.tsnetLoggingIn) {
                                                CircularProgressIndicator(
                                                    color = Everforest.Green,
                                                    strokeWidth = 2.dp,
                                                    modifier = Modifier.size(14.dp),
                                                )
                                                Spacer(Modifier.width(6.dp))
                                                Text("等待浏览器登录…", fontSize = 12.5.sp)
                                            } else {
                                                Text("登录 Tailscale 自动建联", fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold)
                                            }
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
                            Text(
                                text = "已配对的 Lework Hub 主机",
                                fontSize = 11.5.sp,
                                color = Everforest.Grey1,
                            )
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
                                            val epSample = host.endpoints.firstOrNull().orEmpty()
                                            if (epSample.isNotEmpty()) {
                                                Text(
                                                    text = epSample,
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
                            text = if (showManualForm) "收起手动连接配置" else "手动填写地址 / 粘贴配对链接",
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
                            onClick = {
                                dismissKeyboard()
                                onManualConnect(manualHost, if (isPairingLinkInput) "" else manualToken)
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Everforest.Bg3,
                                contentColor = Everforest.Fg,
                            ),
                            shape = RoundedCornerShape(12.dp),
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

internal fun tsnetPlatformLabel(platform: PlatformType, fallbackOs: String? = null): String? = when (platform) {
    PlatformType.DARWIN -> "macOS"
    PlatformType.LINUX -> "Linux"
    PlatformType.WIN32 -> "Windows"
    PlatformType.OTHER -> fallbackOs?.trim()?.takeIf { it.isNotEmpty() }
}

@Composable
internal fun TsnetDiscoveredHubList(
    hubs: List<DiscoveredTsnetHub>,
    connectingHubId: String?,
    onSelectHub: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .testTag("tsnet_discovered_hub_list"),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = "发现 ${hubs.size} 台可用主机 · 点击选择连接",
            fontSize = 11.5.sp,
            fontWeight = FontWeight.Medium,
            color = Everforest.BoldGold,
        )
        val isBusy = connectingHubId != null
        for (item in hubs) {
            val key = item.selectionKey
            val isConnectingThis = connectingHubId == key
            val shape = RoundedCornerShape(10.dp)
            val platformLabel = tsnetPlatformLabel(item.hub.platform, item.os)
            val subtitle = listOf(item.hostName.trim(), item.tailscaleIp.trim())
                .filter { it.isNotEmpty() }
                .joinToString(" · ")
            Surface(
                shape = shape,
                color = if (isConnectingThis) Everforest.Bg2 else Everforest.Bg1,
                border = BorderStroke(
                    width = 1.dp,
                    color = if (isConnectingThis) Everforest.Green else Everforest.Bg3,
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(shape)
                    .testTag("tsnet_discovered_hub_$key")
                    .clickable(enabled = !isBusy) { onSelectHub(key) },
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Text(
                                text = item.hub.name,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium,
                                color = Everforest.BoldFg,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false),
                            )
                            if (platformLabel != null) {
                                TsnetHostPill(
                                    label = platformLabel,
                                    containerColor = Everforest.Bg2,
                                    borderColor = Everforest.Bg3,
                                    textColor = Everforest.Grey2,
                                )
                            }
                        }
                        if (item.tailscaleSameUser || item.isCurrentActive || item.alreadyPaired) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(5.dp),
                            ) {
                                if (item.tailscaleSameUser) {
                                    TsnetHostPill(
                                        label = "同账号免密",
                                        containerColor = Everforest.BgGreen,
                                        borderColor = Everforest.Green.copy(alpha = 0.45f),
                                        textColor = Everforest.Green,
                                    )
                                }
                                if (item.isCurrentActive) {
                                    TsnetHostPill(
                                        label = "当前主机",
                                        containerColor = Everforest.BgYellow,
                                        borderColor = Everforest.BoldGold.copy(alpha = 0.45f),
                                        textColor = Everforest.BoldGold,
                                    )
                                } else if (item.alreadyPaired) {
                                    TsnetHostPill(
                                        label = "已配对",
                                        containerColor = Everforest.BgBlue,
                                        borderColor = Everforest.Blue.copy(alpha = 0.45f),
                                        textColor = Everforest.Blue,
                                    )
                                }
                            }
                        }
                        if (subtitle.isNotEmpty()) {
                            Text(
                                text = subtitle,
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace,
                                color = Everforest.Grey1,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    if (isConnectingThis) {
                        CircularProgressIndicator(
                            color = Everforest.Green,
                            strokeWidth = 2.dp,
                            modifier = Modifier
                                .size(16.dp)
                                .semantics(mergeDescendants = true) {}
                                .testTag("tsnet_discovered_hub_loading_$key"),
                        )
                    } else {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                            contentDescription = null,
                            tint = if (isBusy) Everforest.Grey0 else Everforest.Grey1,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TsnetHostPill(
    label: String,
    containerColor: Color,
    borderColor: Color,
    textColor: Color,
) {
    Surface(
        shape = RoundedCornerShape(999.dp),
        color = containerColor,
        border = BorderStroke(0.5.dp, borderColor),
    ) {
        Text(
            text = label,
            fontSize = 10.sp,
            fontWeight = FontWeight.Medium,
            color = textColor,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.5.dp),
        )
    }
}
