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
internal fun connectionChannelLabel(baseUrl: String): String = when {
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

internal fun effortDisplayLabel(effort: ReasoningEffort): String = when (effort) {
    ReasoningEffort.LOW -> "轻量"
    ReasoningEffort.MEDIUM -> "标准"
    ReasoningEffort.HIGH -> "深度"
    ReasoningEffort.XHIGH -> "超高"
    ReasoningEffort.MAX -> "极限"
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
