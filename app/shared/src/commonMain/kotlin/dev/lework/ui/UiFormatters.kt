@file:OptIn(kotlin.io.encoding.ExperimentalEncodingApi::class)

package dev.lework.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
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
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
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
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.lework.platform.MobileTsnetStatus
import dev.lework.platform.appVersionLabel
import dev.lework.platform.decodeImageBytes
import dev.lework.platform.dismissNativeKeyboard
import dev.lework.platform.epochMillis
import dev.lework.platform.loadSavedRelayUrls
import dev.lework.protocol.*
import dev.lework.store.*
import kotlin.io.encoding.Base64
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal fun isTailscaleEndpoint(baseUrl: String, tsnetStatus: MobileTsnetStatus? = null): Boolean {
    if (baseUrl.isBlank()) return false
    if (tsnetStatus?.peers?.any { !it.localProxyUrl.isNullOrBlank() && baseUrl.startsWith(it.localProxyUrl) } == true) {
        return true
    }
    val host = baseUrl.substringAfter("://").substringBefore('/').substringBefore(':')
    val parts = host.split('.')
    if (parts.size == 4) {
        val o0 = parts[0].toIntOrNull()
        val o1 = parts[1].toIntOrNull()
        if (o0 == 100 && o1 != null && o1 in 64..127) return true
    }
    return false
}

@OptIn(ExperimentalMaterial3Api::class)
internal fun connectionChannelLabel(
    baseUrl: String,
    tsnetStatus: MobileTsnetStatus? = null,
): String = when {
    isTailscaleEndpoint(baseUrl, tsnetStatus) -> "Tailscale 内网"
    loadSavedRelayUrls().any { it.isNotBlank() && baseUrl.startsWith(it) } -> "远程中继"
    baseUrl.isNotBlank() -> "局域网直连"
    else -> "已连接"
}

// ---------------------------------------------------------------------------
// Visual Helpers & Material Icon Mapping
// ---------------------------------------------------------------------------

internal fun shortEngineLabel(engineId: String): String = when (engineId) {
    "claude" -> "Claude"
    "codex" -> "Codex"
    "agy" -> "AGY"
    "pi" -> "Pi"
    "dsh" -> "DSH"
    else -> engineId
}

internal fun engineAccentColor(engineId: String): Color = when (engineId) {
    "claude" -> Everforest.Orange
    "codex" -> Everforest.Green
    "agy" -> Everforest.Blue
    "pi" -> Everforest.Aqua
    "dsh" -> Everforest.Purple
    else -> Everforest.Grey2
}

internal fun modeDisplayLabel(mode: SessionMode): String = when (mode) {
    SessionMode.AUTO -> "自动模式"
    SessionMode.ACCEPT_EDITS -> "自动改码"
    SessionMode.PLAN -> "仅规划"
    SessionMode.DEFAULT -> "逐步确认"
    SessionMode.BYPASS_PERMISSIONS -> "免审批"
}

internal fun modeDescription(mode: SessionMode): String = when (mode) {
    SessionMode.AUTO -> "自动执行常规命令与文件改动，高风险操作前确认"
    SessionMode.ACCEPT_EDITS -> "自动写入代码编辑，执行终端命令前需你确认"
    SessionMode.PLAN -> "仅输出分析与规划方案，不修改文件或执行命令"
    SessionMode.DEFAULT -> "每次文件修改与终端命令执行前均需手动确认"
    SessionMode.BYPASS_PERMISSIONS -> "跳过全部权限确认，全程自动连续执行"
}

internal fun modeIcon(mode: SessionMode): ImageVector = when (mode) {
    SessionMode.AUTO -> Icons.Rounded.PlayCircle
    SessionMode.ACCEPT_EDITS -> Icons.Rounded.Edit
    SessionMode.PLAN -> Icons.Rounded.Description
    SessionMode.DEFAULT -> Icons.Rounded.Shield
    SessionMode.BYPASS_PERMISSIONS -> Icons.Rounded.Bolt
}

internal fun effortDisplayLabel(effort: ReasoningEffort): String = when (effort) {
    ReasoningEffort.LOW -> "轻量"
    ReasoningEffort.MEDIUM -> "标准"
    ReasoningEffort.HIGH -> "深度"
    ReasoningEffort.XHIGH -> "超高"
    ReasoningEffort.MAX -> "极限"
}

internal fun effortDescription(effort: ReasoningEffort): String = when (effort) {
    ReasoningEffort.LOW -> "快速响应，适合轻量问答与小范围调整"
    ReasoningEffort.MEDIUM -> "均衡响应速度与多步推理质量"
    ReasoningEffort.HIGH -> "投入更多思考预算，适合复杂设计与排查"
    ReasoningEffort.XHIGH -> "高强度推演边界条件与跨模块影响"
    ReasoningEffort.MAX -> "最高推理规格，适合核心疑难攻坚"
}

internal fun fileMaterialIcon(entry: FsEntry): ImageVector {
    if (entry.isDirectory) return Icons.Rounded.Folder
    val ext = entry.name.substringAfterLast('.', "").lowercase()
    return when (ext) {
        "kt", "kts", "ts", "tsx", "js", "mjs", "swift", "py", "go", "rs", "java", "c", "cpp", "h" ->
            Icons.Rounded.Code
        "json", "yaml", "yml", "toml", "xml", "plist" ->
            Icons.Rounded.DataObject
        "md", "txt", "rst" ->
            Icons.Rounded.Description
        "sh", "zsh", "bash" ->
            Icons.Rounded.Terminal
        "png", "jpg", "jpeg", "webp", "svg", "gif" ->
            Icons.Rounded.Image
        else ->
            Icons.AutoMirrored.Rounded.InsertDriveFile
    }
}

internal fun fileMaterialIconTint(entry: FsEntry): Color {
    if (entry.isDirectory) return Everforest.Aqua
    val ext = entry.name.substringAfterLast('.', "").lowercase()
    return when (ext) {
        "kt", "kts", "swift" -> Everforest.Orange
        "ts", "tsx", "js", "mjs" -> Everforest.Blue
        "json", "yaml", "yml", "toml" -> Everforest.Yellow
        "md", "txt" -> Everforest.Green
        "sh", "zsh" -> Everforest.Purple
        else -> Everforest.Grey2
    }
}

internal fun formatBytes(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "${bytes / 1024} KB"
    else -> "${bytes / (1024 * 1024)} MB"
}

/**
 * Unified keyboard & focus dismisser across Compose Multiplatform and iOS UIKit responder chain
 * (including active Chinese Pinyin IME uncommitted marked-text sessions).
 */
@Composable
internal fun rememberKeyboardDismisser(): () -> Unit {
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    return remember(focusManager, keyboardController) {
        {
            focusManager.clearFocus(force = true)
            keyboardController?.hide()
            dismissNativeKeyboard()
        }
    }
}

private val decodedBitmapCache = LinkedHashMap<String, ImageBitmap>(48)

internal fun decodeCachedBase64Bitmap(rawBase64: String?): ImageBitmap? {
    if (rawBase64.isNullOrBlank()) return null
    val clean = rawBase64.substringAfter("base64,", rawBase64).trim()
    if (clean.length < 8) return null
    val key = "${clean.length}_${clean.hashCode()}_${clean.take(20)}_${clean.takeLast(20)}"
    decodedBitmapCache[key]?.let { return it }
    val bmp = runCatching {
        val normalized = clean
            .replace("\n", "")
            .replace("\r", "")
            .replace(" ", "")
        val bytes = Base64.decode(normalized)
        decodeImageBytes(bytes)
    }.getOrNull() ?: return null
    if (decodedBitmapCache.size >= 48) {
        val eldestKey = decodedBitmapCache.keys.firstOrNull()
        if (eldestKey != null) decodedBitmapCache.remove(eldestKey)
    }
    decodedBitmapCache[key] = bmp
    return bmp
}

@Composable
internal fun rememberBase64ImageBitmap(rawBase64: String?): ImageBitmap? {
    val cacheKey = remember(rawBase64) {
        if (rawBase64.isNullOrBlank()) "" else "${rawBase64.length}_${rawBase64.hashCode()}_${rawBase64.take(24)}"
    }
    return remember(cacheKey) { decodeCachedBase64Bitmap(rawBase64) }
}

@Composable
internal fun ImageLightboxDialog(
    imagesBase64: List<String>,
    initialIndex: Int = 0,
    onDismiss: () -> Unit,
) {
    if (imagesBase64.isEmpty()) return
    var currentIdx by remember(imagesBase64, initialIndex) {
        mutableStateOf(initialIndex.coerceIn(0, imagesBase64.lastIndex))
    }
    val currentBmp = rememberBase64ImageBitmap(imagesBase64.getOrNull(currentIdx))

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Everforest.BgDim.copy(alpha = 0.95f))
                .clickable { onDismiss() }
                .windowInsetsPadding(WindowInsets.safeDrawing),
        ) {
            if (currentBmp != null) {
                Image(
                    bitmap = currentBmp,
                    contentDescription = "图片预览",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 12.dp, vertical = 56.dp),
                )
            } else {
                Text(
                    text = "无法解析图片内容",
                    color = Everforest.Grey1,
                    fontSize = 14.sp,
                    modifier = Modifier.align(Alignment.Center),
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.TopCenter)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Surface(
                    shape = RoundedCornerShape(999.dp),
                    color = Everforest.Bg1.copy(alpha = 0.85f),
                    border = BorderStroke(1.dp, Everforest.Bg3),
                ) {
                    Text(
                        text = "${currentIdx + 1} / ${imagesBase64.size}",
                        color = Everforest.Fg,
                        fontSize = 12.5.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp),
                    )
                }

                Surface(
                    shape = CircleShape,
                    color = Everforest.Bg1.copy(alpha = 0.85f),
                    border = BorderStroke(1.dp, Everforest.Bg3),
                    modifier = Modifier
                        .size(34.dp)
                        .clip(CircleShape)
                        .clickable { onDismiss() },
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Rounded.Close,
                            contentDescription = "关闭预览",
                            tint = Everforest.Fg,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            }

            if (imagesBase64.size > 1) {
                Row(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 20.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Surface(
                        shape = RoundedCornerShape(999.dp),
                        color = Everforest.Bg1.copy(alpha = 0.9f),
                        border = BorderStroke(1.dp, Everforest.Bg3),
                        modifier = Modifier
                            .clip(RoundedCornerShape(999.dp))
                            .clickable(enabled = currentIdx > 0) {
                                if (currentIdx > 0) currentIdx--
                            },
                    ) {
                        Text(
                            text = "上一张",
                            fontSize = 12.5.sp,
                            color = if (currentIdx > 0) Everforest.Fg else Everforest.Grey0,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp),
                        )
                    }
                    Surface(
                        shape = RoundedCornerShape(999.dp),
                        color = Everforest.Bg1.copy(alpha = 0.9f),
                        border = BorderStroke(1.dp, Everforest.Bg3),
                        modifier = Modifier
                            .clip(RoundedCornerShape(999.dp))
                            .clickable(enabled = currentIdx < imagesBase64.lastIndex) {
                                if (currentIdx < imagesBase64.lastIndex) currentIdx++
                            },
                    ) {
                        Text(
                            text = "下一张",
                            fontSize = 12.5.sp,
                            color = if (currentIdx < imagesBase64.lastIndex) Everforest.Fg else Everforest.Grey0,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp),
                        )
                    }
                }
            }
        }
    }
}
