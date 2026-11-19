package dev.lework.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.lework.platform.appVersionLabel
import dev.lework.platform.canScanQrCode
import dev.lework.platform.loadSavedTsnetControlUrl
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
    onLogoutTsnet: () -> Unit = {},
    onSelectPairedHost: (String) -> Unit = {},
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
    val qrSupported = remember { canScanQrCode() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Everforest.Bg0)
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
                Surface(
                    shape = CircleShape,
                    color = Everforest.BgGreen,
                    modifier = Modifier.size(48.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Rounded.VerifiedUser,
                            contentDescription = null,
                            tint = Everforest.Green,
                            modifier = Modifier.size(24.dp),
                        )
                    }
                }

                Text(
                    text = "Lework",
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
                if (state.connectionStatus == ConnectionStatus.NEEDS_AUTH || state.connectionStatus == ConnectionStatus.ERROR) {
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
                            Text("扫码配对主机 (hub pair)", fontSize = 13.5.sp, fontWeight = FontWeight.Medium)
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
                                    if (state.tsnetStatus.peers.isNotEmpty()) {
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
                                            colors = ButtonDefaults.buttonColors(
                                                containerColor = Everforest.Bg3,
                                                contentColor = Everforest.Fg,
                                            ),
                                            shape = RoundedCornerShape(10.dp),
                                            modifier = Modifier.weight(1f),
                                        ) {
                                            Text("搜索并握手", fontSize = 12.5.sp)
                                        }
                                        TextButton(onClick = onLogoutTsnet) {
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
                                            label = { Text("Headscale 控制面地址 (可选)", fontSize = 11.sp) },
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
                                                text = if (showCustomControlUrl) "默认官方" else "自建控制面",
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
                                text = "已配对主机",
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
                                                text = host.name,
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
                    TextButton(onClick = { showManualForm = !showManualForm }) {
                        Text(
                            text = if (showManualForm) "收起手动连接配置" else "手动填写地址 / 粘贴配对链接",
                            fontSize = 12.sp,
                            color = Everforest.CommentGrey,
                        )
                    }
                    if (state.connectionStatus == ConnectionStatus.ERROR) {
                        TextButton(onClick = onResetCredentials) {
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
                            modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedTextField(
                            value = manualToken,
                            onValueChange = { manualToken = it },
                            label = { Text("访问令牌 (使用配对链接时可留空)", fontSize = 11.5.sp) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Button(
                            onClick = { onManualConnect(manualHost, manualToken) },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Everforest.Bg3,
                                contentColor = Everforest.Fg,
                            ),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("保存并连接", fontWeight = FontWeight.Medium)
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
}
