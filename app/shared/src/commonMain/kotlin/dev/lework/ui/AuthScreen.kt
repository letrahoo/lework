package dev.lework.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.InsertDriveFile
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.lework.platform.appVersionLabel
import dev.lework.platform.epochMillis
import dev.lework.platform.loadSavedRelayUrls
import dev.lework.protocol.*
import dev.lework.store.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AuthEntryView(
    state: ConsoleUiState,
    onLaunchOAuth: () -> Unit,
    onOpenBrowser: () -> Unit,
    onManualConnect: (String, String) -> Unit,
    onRetry: () -> Unit,
    onResetCredentials: () -> Unit = {},
) {
    var showManualForm by remember(state.oauthEnabled, state.connectionStatus) {
        mutableStateOf(!state.oauthEnabled && state.connectionStatus == ConnectionStatus.NEEDS_AUTH)
    }
    var manualHost by remember(state.discoveredBaseUrl) {
        mutableStateOf(state.discoveredBaseUrl)
    }
    var manualToken by remember { mutableStateOf("") }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Everforest.Bg0)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = Everforest.Bg1,
            border = BorderStroke(1.dp, Everforest.Bg3),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier.padding(26.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(14.dp),
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
                    text = "同一 Wi‑Fi 免配直连 · 异地支持自定义中继与令牌接入",
                    fontSize = 13.sp,
                    lineHeight = 19.sp,
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
                            fontSize = 13.sp,
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
                        TextButton(onClick = onResetCredentials) {
                            Text("清除旧凭证并重新授权", fontSize = 12.sp, color = Everforest.Grey2)
                        }
                    }
                    ConnectionStatus.CONNECTED -> Unit
                }

                TextButton(onClick = { showManualForm = !showManualForm }) {
                    Text(
                        text = if (showManualForm) "收起手动连接配置" else "手动指定主机地址 / 访问令牌",
                        fontSize = 12.sp,
                        color = Everforest.CommentGrey,
                    )
                }

                if (showManualForm) {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        OutlinedTextField(
                            value = manualHost,
                            onValueChange = { manualHost = it },
                            label = { Text("主机或中继地址 (如 https://192.168.1.10:64450)", fontSize = 11.5.sp) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedTextField(
                            value = manualToken,
                            onValueChange = { manualToken = it },
                            label = { Text("访问令牌 (可选，留空则仅保存地址重新探测)", fontSize = 11.5.sp) },
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

