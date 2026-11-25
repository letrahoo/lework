package dev.lework.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.lework.platform.copyTextToClipboard
import dev.lework.platform.epochMillis
import dev.lework.platform.isDesktopPlatform
import dev.lework.protocol.*
import dev.lework.store.ConsoleUiState
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Compact Everforest quick-access strip surfacing Proposed Plans & session artifacts
 * beneath the workspace header (complements [SubagentStatusStrip] cleanly without duplication).
 */
@Composable
internal fun ArtifactsQuickBar(
    state: ConsoleUiState,
    onOpenArtifacts: (artifactId: String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val artifacts = state.activeSessionArtifacts
    if (artifacts.isEmpty()) return
    val actionablePlan = artifacts.firstOrNull { it.actionable || it.kind == ArtifactKind.PLAN }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 14.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (actionablePlan != null) {
            Surface(
                shape = RoundedCornerShape(999.dp),
                color = Everforest.BgYellow,
                border = BorderStroke(1.dp, Everforest.BoldGold.copy(alpha = 0.5f)),
                modifier = Modifier
                    .clip(RoundedCornerShape(999.dp))
                    .clickable { onOpenArtifacts(actionablePlan.id) }
                    .testTag("lineage_plan_pill"),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.AssignmentTurnedIn,
                        contentDescription = null,
                        tint = Everforest.BoldGold,
                        modifier = Modifier.size(13.5.dp),
                    )
                    Text(
                        text = "执行方案：${actionablePlan.title}",
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.Medium,
                        color = Everforest.BoldGold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.widthIn(max = 220.dp),
                    )
                }
            }
        }

        Surface(
            shape = RoundedCornerShape(999.dp),
            color = Everforest.Bg1,
            border = BorderStroke(1.dp, Everforest.Bg3),
            modifier = Modifier
                .clip(RoundedCornerShape(999.dp))
                .clickable { onOpenArtifacts(null) }
                .testTag("lineage_artifacts_pill"),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.5.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                Icon(
                    imageVector = Icons.Rounded.Description,
                    contentDescription = null,
                    tint = Everforest.Green,
                    modifier = Modifier.size(13.dp),
                )
                Text(
                    text = "产物 (${artifacts.size})",
                    fontSize = 11.5.sp,
                    color = Everforest.Fg,
                )
            }
        }
    }
}

@Composable
internal fun ArtifactsInspectorSheet(
    state: ConsoleUiState,
    onRefresh: () -> Unit,
    onOpenArtifact: (String) -> Unit,
    onCloseArtifact: () -> Unit,
    onApprovePlan: (String) -> Unit,
    onRevisePlan: (String) -> Unit,
) {
    var copied by remember { mutableStateOf(false) }
    var lightboxImage by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val opened = state.openedArtifact
    val artifacts = state.activeSessionArtifacts

    Column(
        modifier = (
            if (isDesktopPlatform()) {
                Modifier.fillMaxSize()
            } else {
                Modifier.fillMaxWidth().heightIn(min = 420.dp, max = 660.dp)
            }
            )
            .padding(horizontal = 16.dp)
            .padding(bottom = 16.dp)
            .testTag("artifacts_inspector_sheet"),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "会话产物",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Everforest.Fg,
                )
                val subtitle = state.activeSession?.let { sess ->
                    "${shortEngineLabel(sess.engineId)} · 共 ${artifacts.size} 项产物"
                } ?: "选择会话后查看执行方案、清单与产物"
                Text(
                    text = subtitle,
                    fontSize = 11.5.sp,
                    color = Everforest.CommentGrey,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                if (state.artifactsLoading) {
                    CircularProgressIndicator(
                        color = Everforest.Green,
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(16.dp),
                    )
                }
                IconButton(onClick = onRefresh) {
                    Icon(
                        imageVector = Icons.Rounded.Refresh,
                        contentDescription = "刷新产物",
                        tint = Everforest.Grey2,
                        modifier = Modifier.size(19.dp),
                    )
                }
            }
        }

        state.artifactError?.let { err ->
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = Everforest.BgRed,
                border = BorderStroke(1.dp, Everforest.Red.copy(alpha = 0.35f)),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = err,
                    fontSize = 11.5.sp,
                    color = Everforest.Red,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                )
            }
        }

        if (opened != null) {
            val art = opened.artifact
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { onCloseArtifact() }
                        .padding(vertical = 4.dp)
                        .testTag("artifact_back_btn"),
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                        contentDescription = "返回产物列表",
                        tint = Everforest.Green,
                        modifier = Modifier.size(17.dp),
                    )
                    Text(
                        text = art.title,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Everforest.BoldFg,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (!opened.isBase64) {
                    TextButton(
                        onClick = {
                            copyTextToClipboard(opened.content)
                            copied = true
                            scope.launch {
                                delay(1_500L)
                                copied = false
                            }
                        },
                    ) {
                        Text(
                            text = if (copied) "已复制" else "复制内容",
                            fontSize = 11.5.sp,
                            color = if (copied) Everforest.Green else Everforest.Grey2,
                        )
                    }
                }
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ArtifactKindBadge(art.kind)
                ArtifactSourceBadge(art.source)
                art.fileName?.let { fn ->
                    Text(
                        text = fn,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        color = Everforest.CommentGrey,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            Surface(
                shape = RoundedCornerShape(12.dp),
                color = Everforest.BgDim,
                border = BorderStroke(1.dp, Everforest.Bg2),
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .testTag("artifact_preview_surface"),
            ) {
                if (opened.isBase64) {
                    val bmp = rememberBase64ImageBitmap(opened.content)
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .clickable { lightboxImage = opened.content }
                            .padding(12.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (bmp != null) {
                            Image(
                                bitmap = bmp,
                                contentDescription = art.title,
                                contentScale = ContentScale.Fit,
                                modifier = Modifier.fillMaxSize(),
                            )
                        } else {
                            Text("图片预览解码失败", fontSize = 12.sp, color = Everforest.CommentGrey)
                        }
                    }
                } else if (art.mimeType.contains("markdown", ignoreCase = true)) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(14.dp),
                    ) {
                        EverforestMarkdownContent(markdown = opened.content)
                        if (opened.truncated) {
                            Spacer(Modifier.height(10.dp))
                            Text(
                                text = "… 内容较长，已展示前 128 KB",
                                fontSize = 11.sp,
                                color = Everforest.CommentGrey,
                            )
                        }
                    }
                } else {
                    val lang = art.fileName?.substringAfterLast('.', "") ?: ""
                    val styled = remember(opened.content, lang) {
                        highlightEverforestCode(opened.content, lang)
                    }
                    SelectionContainer {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .verticalScroll(rememberScrollState())
                                .horizontalScroll(rememberScrollState())
                                .padding(12.dp),
                        ) {
                            Text(
                                text = styled,
                                fontSize = 11.5.sp,
                                lineHeight = 17.sp,
                                fontFamily = FontFamily.Monospace,
                            )
                            if (opened.truncated) {
                                Spacer(Modifier.height(8.dp))
                                Text(
                                    text = "… 内容较长，已展示前 128 KB",
                                    fontSize = 11.sp,
                                    color = Everforest.CommentGrey,
                                )
                            }
                        }
                    }
                }
            }

            if (art.actionable || art.kind == ArtifactKind.PLAN) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = Everforest.Bg1,
                    border = BorderStroke(1.dp, Everforest.Green.copy(alpha = 0.45f)),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = "方案确认后将自动切换为执行模式并开始实施",
                            fontSize = 11.5.sp,
                            color = Everforest.Grey2,
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Button(
                                onClick = { onApprovePlan(art.title) },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = Everforest.Green,
                                    contentColor = Everforest.BgDim,
                                ),
                                shape = RoundedCornerShape(9.dp),
                                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("artifact_approve_plan_btn"),
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.PlayArrow,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                )
                                Spacer(Modifier.width(4.dp))
                                Text("确认并开始执行", fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold)
                            }
                            OutlinedButton(
                                onClick = { onRevisePlan(art.title) },
                                border = BorderStroke(1.dp, Everforest.Bg3),
                                shape = RoundedCornerShape(9.dp),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                                modifier = Modifier.testTag("artifact_revise_plan_btn"),
                            ) {
                                Text("提出修订意见", fontSize = 12.sp, color = Everforest.Fg)
                            }
                        }
                    }
                }
            }
        } else if (artifacts.isEmpty()) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(horizontal = 24.dp),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Description,
                        contentDescription = null,
                        tint = Everforest.CommentGrey,
                        modifier = Modifier.size(30.dp),
                    )
                    Text(
                        text = if (state.artifactsLoading) "正在扫描当前会话产物…" else "当前会话暂无结构化产物",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        color = Everforest.Grey2,
                    )
                    Text(
                        text = "AGY 执行方案/清单/验收总结、Claude 规划文档与 Artifact、Codex 任务计划都会统一沉淀在此处",
                        fontSize = 11.5.sp,
                        color = Everforest.CommentGrey,
                    )
                }
            }
        } else {
            val nowMs = remember(artifacts) { epochMillis() }
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(artifacts, key = { it.id }) { art ->
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = Everforest.Bg1,
                        border = BorderStroke(
                            1.dp,
                            if (art.actionable || art.kind == ArtifactKind.PLAN) {
                                Everforest.BoldGold.copy(alpha = 0.4f)
                            } else {
                                Everforest.Bg2
                            },
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .clickable { onOpenArtifact(art.id) }
                            .testTag("artifact_item_${art.id}"),
                    ) {
                        Column(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    ArtifactKindBadge(art.kind)
                                    ArtifactSourceBadge(art.source)
                                }
                                Text(
                                    text = formatRelativeShortTime(art.updatedAt, nowMs),
                                    fontSize = 10.5.sp,
                                    color = Everforest.CommentGrey,
                                )
                            }
                            Text(
                                text = art.title,
                                fontSize = 13.5.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = Everforest.Fg,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (!art.summary.isNullOrBlank()) {
                                Text(
                                    text = art.summary,
                                    fontSize = 12.sp,
                                    lineHeight = 17.sp,
                                    color = Everforest.Grey2,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = buildString {
                                        art.fileName?.let { append(it) }
                                        art.sizeBytes?.let { sz ->
                                            if (isNotEmpty()) append(" · ")
                                            append(formatArtifactSize(sz))
                                        }
                                    }.ifBlank { art.mimeType },
                                    fontSize = 10.5.sp,
                                    fontFamily = FontFamily.Monospace,
                                    color = Everforest.CommentGrey,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f),
                                )
                                Icon(
                                    imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                                    contentDescription = null,
                                    tint = Everforest.CommentGrey,
                                    modifier = Modifier.size(16.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    val previewImg = lightboxImage
    if (previewImg != null) {
        ImageLightboxDialog(
            imagesBase64 = listOf(previewImg),
            initialIndex = 0,
            onDismiss = { lightboxImage = null },
        )
    }
}

@Composable
private fun ArtifactKindBadge(kind: ArtifactKind) {
    val (label, tint, bg) = when (kind) {
        ArtifactKind.PLAN -> Triple("执行方案", Everforest.BoldGold, Everforest.BgYellow)
        ArtifactKind.TASK_LIST -> Triple("任务清单", Everforest.Aqua, Everforest.BgGreen)
        ArtifactKind.WALKTHROUGH -> Triple("验收总结", Everforest.Green, Everforest.BgGreen)
        ArtifactKind.HTML -> Triple("网页预览", Everforest.Blue, Everforest.Bg2)
        ArtifactKind.IMAGE -> Triple("图片", Everforest.Purple, Everforest.Bg2)
        ArtifactKind.CODE -> Triple("代码", Everforest.Orange, Everforest.Bg2)
        ArtifactKind.OTHER -> Triple("文档", Everforest.Grey2, Everforest.Bg2)
    }
    Surface(
        shape = RoundedCornerShape(5.dp),
        color = bg,
    ) {
        Text(
            text = label,
            fontSize = 10.5.sp,
            fontWeight = FontWeight.Medium,
            color = tint,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.5.dp),
        )
    }
}

@Composable
private fun ArtifactSourceBadge(source: ArtifactSource) {
    val label = when (source) {
        ArtifactSource.AGY_BRAIN -> "AGY"
        ArtifactSource.CLAUDE_PLAN -> "Claude 规划"
        ArtifactSource.CLAUDE_ARTIFACT -> "Claude 产物"
        ArtifactSource.CODEX_PLAN -> "Codex 计划"
        ArtifactSource.SESSION_PLAN -> "会话计划"
        ArtifactSource.OTHER -> "产物"
    }
    Surface(
        shape = RoundedCornerShape(5.dp),
        color = Everforest.Bg2.copy(alpha = 0.7f),
    ) {
        Text(
            text = label,
            fontSize = 10.sp,
            color = Everforest.CommentGrey,
            modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.5.dp),
        )
    }
}

private fun formatArtifactSize(bytes: Long): String = when {
    bytes < 1024L -> "$bytes B"
    bytes < 1024L * 1024L -> "${(bytes * 10 / 1024L) / 10.0} KB"
    else -> "${(bytes * 10 / (1024L * 1024L)) / 10.0} MB"
}

private fun formatRelativeShortTime(tsMs: Long, nowMs: Long): String {
    if (tsMs <= 0L) return ""
    val diffSec = ((nowMs - tsMs) / 1000L).coerceAtLeast(0L)
    return when {
        diffSec < 60L -> "刚刚"
        diffSec < 3600L -> "${diffSec / 60L} 分钟前"
        diffSec < 86400L -> "${diffSec / 3600L} 小时前"
        else -> "${diffSec / 86400L} 天前"
    }
}
