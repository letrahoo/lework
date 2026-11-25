package dev.lework.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.WifiOff
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
import dev.lework.platform.copyTextToClipboard
import dev.lework.store.ConnectionStatus
import dev.lework.store.TurnRecoveryBannerState
import kotlinx.coroutines.delay

/**
 * Shared Everforest Dark network/turn error recovery banner rendered above the composer
 * on both Mobile and Desktop. Surfaces frequent network error counts clearly and provides
 * a one-tap button to reconnect (if needed) and seamlessly resume the previous turn.
 */
@Composable
internal fun NetworkErrorRecoveryBanner(
    banner: TurnRecoveryBannerState,
    connectionStatus: ConnectionStatus,
    isRetrying: Boolean,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val needsReconnect = connectionStatus != ConnectionStatus.CONNECTED
    val actionLabel = when {
        isRetrying -> "正在重试…"
        needsReconnect -> "重连并继续"
        else -> "重试继续"
    }
    val hasUnsentDraft = !banner.failedPromptText.isNullOrBlank() || banner.failedImages.isNotEmpty()
    val resumeHint = when {
        hasUnsentDraft -> "已保留上一条未发送内容，重试将原样发送"
        needsReconnect -> "网络恢复后点击将自动重连并继续上一轮任务"
        else -> "将基于当前会话上下文无缝继续上一轮任务"
    }

    val copyableDetail = banner.detail.ifBlank { banner.headline }
    var copies by remember(copyableDetail) { mutableIntStateOf(0) }
    val copiedDetail = copies != 0
    LaunchedEffect(copyableDetail, copies) {
        if (copiedDetail) {
            delay(1_600L)
            copies = 0
        }
    }

    Surface(
        shape = RoundedCornerShape(14.dp),
        color = Everforest.BgRed,
        border = BorderStroke(1.dp, Everforest.Red.copy(alpha = 0.55f)),
        modifier = modifier
            .fillMaxWidth()
            .testTag("network_error_recovery_banner"),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    imageVector = if (banner.isNetworkError) Icons.Rounded.WifiOff else Icons.Rounded.ErrorOutline,
                    contentDescription = null,
                    tint = Everforest.Red,
                    modifier = Modifier.size(18.dp),
                )
                Text(
                    text = banner.headline,
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Everforest.BoldFg,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (banner.errorCount >= 2) {
                    Surface(
                        shape = RoundedCornerShape(999.dp),
                        color = Everforest.Red.copy(alpha = 0.2f),
                        border = BorderStroke(0.8.dp, Everforest.Red.copy(alpha = 0.5f)),
                        modifier = Modifier.testTag("recovery_error_count_badge"),
                    ) {
                        Text(
                            text = "×${banner.errorCount}",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            fontFamily = FontFamily.Monospace,
                            color = Everforest.Red,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp),
                        )
                    }
                }
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(24.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .clickable {
                            if (copyTextToClipboard(copyableDetail)) {
                                copies++
                            }
                        }
                        .testTag("copy_recovery_banner_button"),
                ) {
                    Icon(
                        imageVector = if (copiedDetail) Icons.Rounded.Check else Icons.Rounded.ContentCopy,
                        contentDescription = "复制异常详情",
                        tint = if (copiedDetail) Everforest.SoftSage else Everforest.Grey2,
                        modifier = Modifier.size(14.dp),
                    )
                }
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(24.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .clickable(onClick = onDismiss)
                        .testTag("dismiss_recovery_banner_button"),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Close,
                        contentDescription = "关闭异常提示",
                        tint = Everforest.Grey2,
                        modifier = Modifier.size(15.dp),
                    )
                }
            }

            if (banner.detail.isNotBlank()) {
                SelectionContainer {
                    Text(
                        text = banner.detail,
                        fontSize = 12.sp,
                        lineHeight = 16.5.sp,
                        color = Everforest.Fg.copy(alpha = 0.92f),
                        maxLines = 4,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = resumeHint,
                    fontSize = 11.sp,
                    lineHeight = 15.sp,
                    color = Everforest.Grey2,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Button(
                    onClick = onRetry,
                    enabled = !isRetrying,
                    shape = RoundedCornerShape(9.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Everforest.Green,
                        contentColor = Everforest.BgDim,
                        disabledContainerColor = Everforest.BgGreen,
                        disabledContentColor = Everforest.Fg,
                    ),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                    modifier = Modifier
                        .heightIn(min = 32.dp)
                        .testTag("retry_last_turn_button"),
                ) {
                    if (isRetrying) {
                        CircularProgressIndicator(
                            color = Everforest.Fg,
                            strokeWidth = 1.8.dp,
                            modifier = Modifier.size(13.dp),
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Rounded.Refresh,
                            contentDescription = null,
                            modifier = Modifier.size(15.dp),
                        )
                    }
                    Spacer(Modifier.width(5.dp))
                    Text(
                        text = actionLabel,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }
    }
}
