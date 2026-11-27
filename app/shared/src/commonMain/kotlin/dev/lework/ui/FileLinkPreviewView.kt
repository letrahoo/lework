package dev.lework.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.InsertDriveFile
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.lework.platform.canOpenLocalFileExternally
import dev.lework.platform.copyTextToClipboard
import dev.lework.platform.isDesktopPlatform
import dev.lework.platform.openLocalFileExternally
import dev.lework.store.LinkedFilePreviewEntry
import dev.lework.store.extractReadablePreviewFromHtml
import dev.lework.store.fileExtensionOf
import dev.lework.store.isHtmlExtension
import dev.lework.store.isMarkdownExtension
import dev.lework.store.parentDirectoryOf
import dev.lework.ui.desktop.DesktopModalFrame
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
internal fun DesktopFilePreviewDialog(
    entry: LinkedFilePreviewEntry,
    canNavigateBack: Boolean,
    onNavigateBack: () -> Unit,
    onOpenNestedLink: (rawTarget: String, baseDirectory: String?) -> Unit,
    onClose: () -> Unit,
) {
    DesktopModalFrame(
        onDismiss = onClose,
        minWidth = 640.dp,
        maxWidth = 900.dp,
        maxHeight = 760.dp,
        testTag = "desktop_file_preview_dialog",
    ) {
        FileLinkPreviewContent(
            entry = entry,
            canNavigateBack = canNavigateBack,
            showCloseButton = true,
            onNavigateBack = onNavigateBack,
            onOpenNestedLink = onOpenNestedLink,
            onClose = onClose,
        )
    }
}

@Composable
internal fun FileLinkPreviewContent(
    entry: LinkedFilePreviewEntry,
    canNavigateBack: Boolean,
    showCloseButton: Boolean,
    onNavigateBack: () -> Unit,
    onOpenNestedLink: (rawTarget: String, baseDirectory: String?) -> Unit,
    onClose: () -> Unit,
) {
    val ext = remember(entry.resolvedPath) { fileExtensionOf(entry.resolvedPath) }
    val isMarkdown = remember(ext) { isMarkdownExtension(ext) }
    val isHtml = remember(ext) { isHtmlExtension(ext) }
    val detectedLang = remember(entry.resolvedPath, entry.content) {
        detectCodeLanguage(entry.resolvedPath, entry.content.orEmpty())
    }
    val canOpenExternal = remember(entry.resolvedPath) { canOpenLocalFileExternally(entry.resolvedPath) }
    var htmlShowSource by remember(entry.resolvedPath) { mutableStateOf(false) }
    var markdownShowSource by remember(entry.resolvedPath, entry.startLine) {
        mutableStateOf(entry.startLine != null)
    }
    var copiedKind by remember(entry.resolvedPath) { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    val lineBadge = remember(entry.startLine, entry.endLine) {
        val s = entry.startLine ?: return@remember null
        val e = entry.endLine
        if (e != null && e > s) "L$s–L$e" else "L$s"
    }

    Column(
        modifier = (
            if (isDesktopPlatform()) {
                Modifier.fillMaxWidth().heightIn(min = 480.dp, max = 720.dp)
            } else {
                Modifier.fillMaxWidth().heightIn(min = 420.dp, max = 660.dp)
            }
            )
            .padding(horizontal = 18.dp)
            .padding(bottom = 18.dp)
            .testTag("file_preview_content"),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // ── Top Bar ──
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (canNavigateBack) {
                IconButton(
                    onClick = onNavigateBack,
                    modifier = Modifier
                        .size(30.dp)
                        .testTag("file_preview_back_btn"),
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                        contentDescription = "返回上一个文件",
                        tint = Everforest.Green,
                        modifier = Modifier.size(18.dp),
                    )
                }
            } else {
                Icon(
                    imageVector = when {
                        isMarkdown -> Icons.Rounded.Description
                        isHtml -> Icons.Rounded.Language
                        else -> Icons.Rounded.Code
                    },
                    contentDescription = null,
                    tint = when {
                        isMarkdown -> Everforest.Green
                        isHtml -> Everforest.BoldGold
                        else -> Everforest.Aqua
                    },
                    modifier = Modifier.size(19.dp),
                )
            }

            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text = entry.displayName,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Everforest.BoldFg,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .weight(1f, fill = false)
                            .testTag("file_preview_title"),
                    )
                    if (detectedLang.id != "plain") {
                        Surface(
                            shape = RoundedCornerShape(999.dp),
                            color = Everforest.Bg2.copy(alpha = 0.8f),
                            border = BorderStroke(1.dp, Everforest.Bg3),
                            modifier = Modifier.testTag("file_preview_lang_badge"),
                        ) {
                            Text(
                                text = detectedLang.displayName,
                                fontSize = 10.5.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Medium,
                                color = Everforest.Aqua,
                                modifier = Modifier.padding(horizontal = 7.dp, vertical = 1.5.dp),
                            )
                        }
                    }
                    if (lineBadge != null) {
                        Surface(
                            shape = RoundedCornerShape(999.dp),
                            color = Everforest.BgYellow,
                            border = BorderStroke(1.dp, Everforest.BoldGold.copy(alpha = 0.45f)),
                        ) {
                            Text(
                                text = lineBadge,
                                fontSize = 10.5.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Medium,
                                color = Everforest.BoldGold,
                                modifier = Modifier.padding(horizontal = 7.dp, vertical = 1.5.dp),
                            )
                        }
                    }
                }
                Text(
                    text = entry.resolvedPath,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    color = Everforest.CommentGrey,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.testTag("file_preview_path"),
                )
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                FilePreviewActionChip(
                    label = if (copiedKind == "path") "已复制路径" else "复制路径",
                    accentColor = if (copiedKind == "path") Everforest.Green else Everforest.Grey2,
                    testTag = "file_preview_copy_path_btn",
                    onClick = {
                        if (copyTextToClipboard(entry.resolvedPath)) {
                            copiedKind = "path"
                            scope.launch {
                                delay(1_500L)
                                if (copiedKind == "path") copiedKind = null
                            }
                        }
                    },
                )
                val copyableContent = if (isHtml && !htmlShowSource && !entry.companionMarkdownContent.isNullOrBlank()) {
                    entry.companionMarkdownContent
                } else {
                    entry.content
                }
                if (!copyableContent.isNullOrEmpty() && !entry.isBinary) {
                    FilePreviewActionChip(
                        label = if (copiedKind == "content") "已复制内容" else "复制内容",
                        accentColor = if (copiedKind == "content") Everforest.Green else Everforest.Grey2,
                        testTag = "file_preview_copy_content_btn",
                        onClick = {
                            if (copyTextToClipboard(copyableContent)) {
                                copiedKind = "content"
                                scope.launch {
                                    delay(1_500L)
                                    if (copiedKind == "content") copiedKind = null
                                }
                            }
                        },
                    )
                }
                if (canOpenExternal) {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = Everforest.BgGreen,
                        border = BorderStroke(1.dp, Everforest.Green.copy(alpha = 0.5f)),
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { openLocalFileExternally(entry.resolvedPath) }
                            .testTag("file_preview_open_external_btn"),
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Rounded.OpenInNew,
                                contentDescription = null,
                                tint = Everforest.Green,
                                modifier = Modifier.size(13.dp),
                            )
                            Text(
                                text = if (isHtml) "在浏览器打开" else "默认应用打开",
                                fontSize = 11.5.sp,
                                fontWeight = FontWeight.Medium,
                                color = Everforest.Green,
                            )
                        }
                    }
                }
                if (showCloseButton) {
                    IconButton(
                        onClick = onClose,
                        modifier = Modifier
                            .size(30.dp)
                            .testTag("file_preview_close_btn"),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Close,
                            contentDescription = "关闭文件预览",
                            tint = Everforest.Grey1,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            }
        }

        if (isMarkdown && !entry.loading && entry.error == null && !entry.isBinary) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                FilePreviewModePill(
                    label = "阅读预览",
                    selected = !markdownShowSource,
                    testTag = "file_preview_tab_rendered",
                    onClick = { markdownShowSource = false },
                )
                FilePreviewModePill(
                    label = "Markdown 源码",
                    selected = markdownShowSource,
                    testTag = "file_preview_tab_source",
                    onClick = { markdownShowSource = true },
                )
            }
        }

        if (isHtml && !entry.loading && entry.error == null && !entry.isBinary) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    val hasCompanion = !entry.companionMarkdownContent.isNullOrBlank()
                    FilePreviewModePill(
                        label = if (hasCompanion) "看板预览" else "阅读预览",
                        selected = !htmlShowSource,
                        testTag = "file_preview_tab_rendered",
                        onClick = { htmlShowSource = false },
                    )
                    FilePreviewModePill(
                        label = "HTML 源码",
                        selected = htmlShowSource,
                        testTag = "file_preview_tab_source",
                        onClick = { htmlShowSource = true },
                    )
                }
                val hintText = if (!entry.companionMarkdownPath.isNullOrBlank()) {
                    "已关联同目录文档预览，点条目可直接展开"
                } else {
                    "应用内安全阅读视图"
                }
                Text(
                    text = hintText,
                    fontSize = 11.sp,
                    color = Everforest.CommentGrey,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        HorizontalDivider(color = Everforest.Bg2)

        // ── Preview Body ──
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = Everforest.BgDim,
            border = BorderStroke(1.dp, Everforest.Bg2),
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .testTag("file_preview_body"),
        ) {
            when {
                entry.loading -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            CircularProgressIndicator(
                                color = Everforest.Green,
                                strokeWidth = 2.dp,
                                modifier = Modifier.size(18.dp),
                            )
                            Text(
                                text = "正在读取文件内容…",
                                fontSize = 13.sp,
                                color = Everforest.Grey2,
                            )
                        }
                    }
                }

                entry.error != null -> {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(24.dp),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.ErrorOutline,
                            contentDescription = null,
                            tint = Everforest.SoftRed,
                            modifier = Modifier.size(28.dp),
                        )
                        Spacer(Modifier.height(10.dp))
                        Text(
                            text = "无法在应用内预览此文件",
                            fontSize = 14.5.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Everforest.Fg,
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = entry.error,
                            fontSize = 12.5.sp,
                            color = Everforest.CommentGrey,
                            textAlign = TextAlign.Center,
                        )
                    }
                }

                entry.isBinary -> {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(24.dp),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.InsertDriveFile,
                            contentDescription = null,
                            tint = Everforest.BoldGold,
                            modifier = Modifier.size(28.dp),
                        )
                        Spacer(Modifier.height(10.dp))
                        Text(
                            text = "该文件为二进制格式，暂不支持直接文本预览",
                            fontSize = 13.5.sp,
                            color = Everforest.Fg,
                        )
                        if (canOpenExternal) {
                            Spacer(Modifier.height(12.dp))
                            Button(
                                onClick = { openLocalFileExternally(entry.resolvedPath) },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = Everforest.Green,
                                    contentColor = Everforest.BgDim,
                                ),
                            ) {
                                Text("使用默认应用打开", fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }
                }

                isMarkdown && !markdownShowSource -> {
                    val docBaseDir = remember(entry.resolvedPath) { parentDirectoryOf(entry.resolvedPath) }
                    CompositionLocalProvider(
                        LocalFileLinkClickHandler provides { target ->
                            onOpenNestedLink(target, docBaseDir)
                        },
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .verticalScroll(rememberScrollState())
                                .padding(horizontal = 20.dp, vertical = 16.dp),
                        ) {
                            EverforestMarkdownContent(markdown = entry.content.orEmpty())
                            if (entry.truncated) {
                                Spacer(Modifier.height(12.dp))
                                Text(
                                    text = "… 文件内容较长，当前已展示前 256 KB",
                                    fontSize = 11.5.sp,
                                    color = Everforest.CommentGrey,
                                )
                            }
                        }
                    }
                }

                isHtml && !htmlShowSource -> {
                    val companionMd = entry.companionMarkdownContent
                    val previewMarkdown = remember(companionMd, entry.content) {
                        if (!companionMd.isNullOrBlank()) {
                            companionMd
                        } else {
                            extractReadablePreviewFromHtml(entry.content.orEmpty())
                                .ifBlank { "该 HTML 页面内容由前端脚本动态生成，可点击右上角「在浏览器打开」查看完整交互视图，或切换到「HTML 源码」查看代码。" }
                        }
                    }
                    val docBaseDir = remember(entry.companionMarkdownPath, entry.resolvedPath) {
                        parentDirectoryOf(entry.companionMarkdownPath ?: entry.resolvedPath)
                    }
                    CompositionLocalProvider(
                        LocalFileLinkClickHandler provides { target ->
                            onOpenNestedLink(target, docBaseDir)
                        },
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .verticalScroll(rememberScrollState())
                                .padding(horizontal = 20.dp, vertical = 16.dp),
                        ) {
                            EverforestMarkdownContent(markdown = previewMarkdown)
                            if (entry.companionMarkdownTruncated || (companionMd.isNullOrBlank() && entry.truncated)) {
                                Spacer(Modifier.height(12.dp))
                                Text(
                                    text = "… 页面内容较长，当前已展示前 256 KB",
                                    fontSize = 11.5.sp,
                                    color = Everforest.CommentGrey,
                                )
                            }
                        }
                    }
                }

                else -> {
                    CodeFilePreviewBody(
                        content = entry.content.orEmpty(),
                        filePath = entry.resolvedPath,
                        startLine = entry.startLine,
                        endLine = entry.endLine,
                        truncated = entry.truncated,
                    )
                }
            }
        }
    }
}

@Composable
private fun CodeFilePreviewBody(
    content: String,
    filePath: String,
    startLine: Int?,
    endLine: Int?,
    truncated: Boolean,
) {
    val highlightedLines = remember(content, filePath) {
        highlightFileLines(content = content, filePathOrLang = filePath)
    }
    val gutterWidthDp = remember(highlightedLines.size) {
        val digits = highlightedLines.size.coerceAtLeast(1).toString().length.coerceIn(2, 5)
        (digits * 8 + 18).dp
    }
    val listState = rememberLazyListState()
    LaunchedEffect(content, startLine) {
        if (startLine != null && startLine > 0 && highlightedLines.isNotEmpty()) {
            val targetIdx = (startLine - 4).coerceIn(0, highlightedLines.lastIndex)
            listState.scrollToItem(targetIdx)
        }
    }

    SelectionContainer {
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .padding(vertical = 8.dp)
                .testTag("file_preview_code_list"),
        ) {
            itemsIndexed(highlightedLines) { idx, highlightedLine ->
                val lineNo = idx + 1
                val isHighlighted = startLine != null &&
                    lineNo >= startLine &&
                    lineNo <= (endLine ?: startLine)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(if (isHighlighted) Everforest.BgYellow else Color.Transparent)
                        .padding(horizontal = 10.dp, vertical = 1.5.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    Text(
                        text = lineNo.toString(),
                        fontSize = 11.5.sp,
                        lineHeight = 18.sp,
                        fontFamily = FontFamily.Monospace,
                        color = if (isHighlighted) Everforest.BoldGold else Everforest.CommentGrey.copy(alpha = 0.7f),
                        fontWeight = if (isHighlighted) FontWeight.SemiBold else FontWeight.Normal,
                        textAlign = TextAlign.End,
                        modifier = Modifier
                            .width(gutterWidthDp)
                            .padding(end = 12.dp),
                    )
                    Text(
                        text = highlightedLine,
                        fontSize = 12.sp,
                        lineHeight = 18.sp,
                        fontFamily = FontFamily.Monospace,
                        color = Everforest.Fg,
                        modifier = Modifier
                            .weight(1f)
                            .horizontalScroll(rememberScrollState()),
                    )
                }
            }
            if (truncated) {
                item(key = "file_preview_truncated_footer") {
                    Text(
                        text = "… 文件较大，当前已展示前 256 KB",
                        fontSize = 11.5.sp,
                        color = Everforest.CommentGrey,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun FilePreviewActionChip(
    label: String,
    accentColor: Color,
    testTag: String,
    onClick: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = Everforest.Bg2.copy(alpha = 0.65f),
        border = BorderStroke(1.dp, Everforest.Bg3),
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .testTag(testTag),
    ) {
        Text(
            text = label,
            fontSize = 11.5.sp,
            color = accentColor,
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
        )
    }
}

@Composable
private fun FilePreviewModePill(
    label: String,
    selected: Boolean,
    testTag: String,
    onClick: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(999.dp),
        color = if (selected) Everforest.Bg2 else Color.Transparent,
        border = BorderStroke(1.dp, if (selected) Everforest.CodeGreen.copy(alpha = 0.65f) else Everforest.Bg3),
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .clickable(onClick = onClick)
            .testTag(testTag),
    ) {
        Text(
            text = label,
            fontSize = 11.5.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected) Everforest.CodeGreen else Everforest.Grey1,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
        )
    }
}
