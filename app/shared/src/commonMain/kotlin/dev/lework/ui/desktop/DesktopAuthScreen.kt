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
import dev.lework.platform.canConnectViaSsh
import dev.lework.platform.loadSavedTsnetControlUrl
import dev.lework.store.ConnectionStatus
import dev.lework.store.ConsoleUiState
import dev.lework.ui.Everforest

@Composable
internal fun DesktopAuthScreen(
    state: ConsoleUiState,
    onLaunchOAuth: () -> Unit,
    onOpenBrowser: () -> Unit,
    onManualConnect: (String, String) -> Unit,
    onRetry: () -> Unit,
    onResetCredentials: () -> Unit,
    onPasteClipboardPairing: () -> Unit,
    onPairViaSsh: (String) -> Unit,
    onStartTsnetLogin: (String?) -> Unit,
    onSearchTsnetPeers: () -> Unit,
    onLogoutTsnet: () -> Unit,
    onSelectPairedHost: (String) -> Unit,
) {
    var showManualForm by remember(state.oauthEnabled, state.connectionStatus) { mutableStateOf(false) }
    var showCustomControlUrl by remember { mutableStateOf(!loadSavedTsnetControlUrl().isNullOrBlank()) }
    var customControlUrl by remember { mutableStateOf(loadSavedTsnetControlUrl().orEmpty()) }
    var manualHost by remember(state.discoveredBaseUrl) { mutableStateOf(state.discoveredBaseUrl) }
    var manualToken by remember { mutableStateOf("") }
    var sshTargetInput by remember { mutableStateOf(state.sshConfigHosts.firstOrNull().orEmpty()) }
    val sshSupported = remember { canConnectViaSsh() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Everforest.Bg0)
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
                dev.lework.ui.LeworkBrandMark(size = 54.dp)

                Text(
                    text = "Lework Desktop",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Everforest.Fg,
                )

                Text(
                    text = "本机自动直连 · SSH 一键连开发机 · Tailscale 跨网组网",
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

                if (state.connectionStatus == ConnectionStatus.NEEDS_AUTH || state.connectionStatus == ConnectionStatus.ERROR) {
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
                                        text = "通过 SSH 一键连接远程开发机",
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = Everforest.Fg,
                                    )
                                }
                                Text(
                                    text = "自动调用本机 SSH 获取一次性配对凭证并校验主机指纹（若端口受限将自动建立本地加密转发）",
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
                                        Text("连接开发机", fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold)
                                    }
                                }
                            }
                        }
                    }

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
                                            Text("登录 Tailscale 自动建联", fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold)
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
                            Text("已配对开发机", fontSize = 11.5.sp, color = Everforest.Grey1)
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
                            shape = RoundedCornerShape(10.dp),
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
