package dev.lework.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.AccountTree
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.lework.protocol.SessionMeta
import dev.lework.protocol.SessionSource
import dev.lework.protocol.SessionState
import dev.lework.protocol.SubagentSummary
import dev.lework.store.SessionTranscript
import kotlinx.coroutines.delay

private fun SubagentSummary.isActive(): Boolean =
    state == SessionState.RUNNING || state == SessionState.WAITING_PERMISSION

private fun SubagentSummary.accentColor(): Color = when (state) {
    SessionState.RUNNING, SessionState.WAITING_PERMISSION -> Everforest.Orange
    SessionState.ERROR -> Everforest.Red
    else -> Everforest.Green
}

private fun SubagentSummary.displayStatusLabel(): String =
    statusText?.trim()?.takeIf { it.isNotEmpty() } ?: when (state) {
        SessionState.RUNNING -> "运行中…"
        SessionState.WAITING_PERMISSION -> "等待审批…"
        SessionState.ERROR -> "执行出错"
        SessionState.CLOSED -> "已结束"
        SessionState.IDLE -> "已完成"
    }

@Composable
private fun SubagentStatusDot(
    state: SessionState,
    sizeDp: Int = 7,
) {
    val active = state == SessionState.RUNNING || state == SessionState.WAITING_PERMISSION
    var pulseBright by remember(active) { mutableStateOf(true) }
    LaunchedEffect(active) {
        if (!active) return@LaunchedEffect
        while (true) {
            delay(650L)
            pulseBright = !pulseBright
        }
    }
    val color = when (state) {
        SessionState.RUNNING, SessionState.WAITING_PERMISSION -> Everforest.Orange
        SessionState.ERROR -> Everforest.Red
        else -> Everforest.Green
    }
    val alpha = if (active && !pulseBright) 0.42f else 1f
    Box(
        modifier = Modifier
            .size(sizeDp.dp)
            .alpha(alpha)
            .clip(CircleShape)
            .background(color),
    )
}

/**
 * Compact, Everforest Dark live strip surfacing parallel SubAgents attached to the active parent session.
 * Shows a single-row header with live running/completed count, 1-click mini chips, and an expandable detail list.
 */
@Composable
internal fun SubagentStatusStrip(
    subagents: List<SubagentSummary>,
    onSelectSubagent: (SubagentSummary) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (subagents.isEmpty()) return

    var expanded by remember { mutableStateOf(false) }
    val runningCount = subagents.count { it.isActive() }
    val errorCount = subagents.count { it.state == SessionState.ERROR }
    val pillBg = when {
        runningCount > 0 -> Everforest.Orange.copy(alpha = 0.16f)
        errorCount > 0 -> Everforest.BgRed
        else -> Everforest.BgGreen
    }
    val pillBorder = when {
        runningCount > 0 -> Everforest.Orange.copy(alpha = 0.48f)
        errorCount > 0 -> Everforest.Red.copy(alpha = 0.45f)
        else -> Everforest.Green.copy(alpha = 0.4f)
    }
    val pillFg = when {
        runningCount > 0 -> Everforest.Orange
        errorCount > 0 -> Everforest.SoftRed
        else -> Everforest.Green
    }
    val countLabel = when {
        runningCount > 0 && runningCount < subagents.size -> "$runningCount/${subagents.size} 运行中"
        runningCount > 0 -> "$runningCount 运行中"
        errorCount > 0 -> "$errorCount 出错 · 共 ${subagents.size}"
        else -> "${subagents.size} 已完成"
    }

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = Everforest.Bg1,
        border = BorderStroke(
            width = 1.dp,
            color = if (runningCount > 0) Everforest.Orange.copy(alpha = 0.32f) else Everforest.Bg3,
        ),
        modifier = modifier
            .fillMaxWidth()
            .testTag("subagent_status_strip"),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 7.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            // Compact single-row header with quick-access mini chips when collapsed
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(7.dp))
                        .clickable { expanded = !expanded }
                        .padding(horizontal = 3.dp, vertical = 2.dp)
                        .testTag("subagent_strip_toggle"),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.AccountTree,
                        contentDescription = null,
                        tint = pillFg,
                        modifier = Modifier.size(14.dp),
                    )
                    Text(
                        text = "SubAgents",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        fontFamily = FontFamily.Monospace,
                        color = Everforest.HeadingWarm,
                    )
                    Surface(
                        shape = RoundedCornerShape(999.dp),
                        color = pillBg,
                        border = BorderStroke(0.8.dp, pillBorder),
                    ) {
                        Text(
                            text = countLabel,
                            fontSize = 10.5.sp,
                            fontWeight = FontWeight.Medium,
                            color = pillFg,
                            modifier = Modifier.padding(horizontal = 7.dp, vertical = 1.5.dp),
                        )
                    }
                    Icon(
                        imageVector = if (expanded) {
                            Icons.Rounded.KeyboardArrowDown
                        } else {
                            Icons.AutoMirrored.Rounded.KeyboardArrowRight
                        },
                        contentDescription = if (expanded) "收起子任务列表" else "展开子任务列表",
                        tint = Everforest.CommentGrey,
                        modifier = Modifier.size(16.dp),
                    )
                }

                if (!expanded) {
                    Row(
                        modifier = Modifier
                            .weight(1f)
                            .horizontalScroll(rememberScrollState()),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        for (sub in subagents) {
                            key(sub.id) {
                                SubagentMiniChip(
                                    subagent = sub,
                                    onClick = { onSelectSubagent(sub) },
                                )
                            }
                        }
                    }
                } else {
                    Spacer(Modifier.weight(1f))
                    Text(
                        text = "点击查看执行详情",
                        fontSize = 11.sp,
                        color = Everforest.CommentGrey,
                    )
                }
            }

            // Expanded detailed list
            AnimatedVisibility(visible = expanded) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    HorizontalDivider(color = Everforest.Bg2, thickness = 1.dp)
                    for (sub in subagents) {
                        key(sub.id) {
                            SubagentDetailRow(
                                subagent = sub,
                                onClick = { onSelectSubagent(sub) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SubagentMiniChip(
    subagent: SubagentSummary,
    onClick: () -> Unit,
) {
    val active = subagent.isActive()
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = if (active) Everforest.Bg2 else Everforest.Bg0,
        border = BorderStroke(
            width = 0.8.dp,
            color = if (active) Everforest.Orange.copy(alpha = 0.42f) else Everforest.Bg3,
        ),
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .testTag("subagent_chip_${subagent.id}"),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            SubagentStatusDot(state = subagent.state, sizeDp = 6)
            Text(
                text = subagent.title,
                fontSize = 11.5.sp,
                color = Everforest.Fg,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 148.dp),
            )
            if (active && !subagent.statusText.isNullOrBlank()) {
                Text(
                    text = "· ${subagent.statusText}",
                    fontSize = 10.5.sp,
                    color = Everforest.SoftSand,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 96.dp),
                )
            }
        }
    }
}

@Composable
private fun SubagentDetailRow(
    subagent: SubagentSummary,
    onClick: () -> Unit,
) {
    val active = subagent.isActive()
    val statusColor = when (subagent.state) {
        SessionState.RUNNING, SessionState.WAITING_PERMISSION -> Everforest.SoftSand
        SessionState.ERROR -> Everforest.SoftRed
        else -> Everforest.CommentGrey
    }

    Surface(
        shape = RoundedCornerShape(9.dp),
        color = Everforest.Bg0,
        border = BorderStroke(
            width = 0.8.dp,
            color = if (active) Everforest.Orange.copy(alpha = 0.36f) else Everforest.Bg2,
        ),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(9.dp))
            .clickable(onClick = onClick)
            .testTag("subagent_item_${subagent.id}"),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SubagentStatusDot(state = subagent.state, sizeDp = 7)

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text = subagent.title,
                        fontSize = 12.5.sp,
                        fontWeight = FontWeight.Medium,
                        color = Everforest.Fg,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    val role = subagent.role?.trim()?.takeIf { it.isNotEmpty() && it != subagent.title }
                    if (role != null) {
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = Everforest.Bg2,
                        ) {
                            Text(
                                text = role,
                                fontSize = 10.sp,
                                fontFamily = FontFamily.Monospace,
                                color = Everforest.InlineCode,
                                maxLines = 1,
                                modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp),
                            )
                        }
                    }
                }
                Text(
                    text = subagent.displayStatusLabel(),
                    fontSize = 11.sp,
                    color = statusColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            Icon(
                imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                contentDescription = "查看子任务详情",
                tint = Everforest.CommentGrey,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

/**
 * Drill-down modal inspector rendering a selected SubAgent's live status, workspace metadata,
 * tool process trace, and markdown replies using Everforest Dark styling.
 */
@Composable
internal fun SubagentInspectorDialog(
    subagent: SubagentSummary,
    transcript: SessionTranscript,
    loading: Boolean,
    error: String?,
    onRefresh: () -> Unit,
    onClose: () -> Unit,
) {
    val accent = subagent.accentColor()
    val workspaceName = remember(subagent.workspace) {
        subagent.workspace
            ?.trimEnd('/')
            ?.substringAfterLast('/')
            ?.takeIf { it.isNotBlank() }
    }
    val syntheticSession = remember(subagent, transcript.lastSeq) {
        SessionMeta(
            id = subagent.id,
            engineId = if (subagent.id.startsWith("host:claude")) "claude" else "agy",
            cwd = subagent.workspace.orEmpty(),
            title = subagent.title,
            state = subagent.state,
            createdAt = subagent.createdAt ?: subagent.updatedAt,
            updatedAt = subagent.updatedAt,
            lastSeq = transcript.lastSeq,
            source = SessionSource.HOST,
            liveStatusText = if (subagent.isActive()) subagent.displayStatusLabel() else null,
            turnStartedAt = subagent.createdAt ?: subagent.updatedAt,
        )
    }

    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = Everforest.Bg0,
            border = BorderStroke(1.dp, Everforest.Bg3),
            shadowElevation = 18.dp,
            modifier = Modifier
                .fillMaxWidth(0.94f)
                .widthIn(max = 840.dp)
                .fillMaxHeight(0.86f)
                .onPreviewKeyEvent { ev ->
                    if (ev.type == KeyEventType.KeyDown && ev.key == Key.Escape) {
                        onClose()
                        true
                    } else {
                        false
                    }
                }
                .testTag("subagent_inspector_dialog"),
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // Header
                Surface(
                    color = Everforest.Bg1,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 11.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        SubagentStatusDot(state = subagent.state, sizeDp = 9)

                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(3.dp),
                        ) {
                            Text(
                                text = subagent.title,
                                fontSize = 14.5.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = Everforest.HeadingWarm,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                modifier = Modifier.horizontalScroll(rememberScrollState()),
                            ) {
                                Surface(
                                    shape = RoundedCornerShape(999.dp),
                                    color = accent.copy(alpha = 0.16f),
                                    border = BorderStroke(0.8.dp, accent.copy(alpha = 0.42f)),
                                ) {
                                    Text(
                                        text = subagent.displayStatusLabel(),
                                        fontSize = 10.5.sp,
                                        fontWeight = FontWeight.Medium,
                                        color = accent,
                                        modifier = Modifier.padding(horizontal = 7.dp, vertical = 1.5.dp),
                                    )
                                }
                                val role = subagent.role?.trim()?.takeIf { it.isNotEmpty() && it != subagent.title }
                                if (role != null) {
                                    Surface(
                                        shape = RoundedCornerShape(6.dp),
                                        color = Everforest.Bg2,
                                    ) {
                                        Text(
                                            text = role,
                                            fontSize = 10.5.sp,
                                            fontFamily = FontFamily.Monospace,
                                            color = Everforest.InlineCode,
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.5.dp),
                                        )
                                    }
                                }
                                if (workspaceName != null) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(3.dp),
                                    ) {
                                        Icon(
                                            imageVector = Icons.Rounded.FolderOpen,
                                            contentDescription = null,
                                            tint = Everforest.CommentGrey,
                                            modifier = Modifier.size(12.dp),
                                        )
                                        Text(
                                            text = workspaceName,
                                            fontSize = 11.sp,
                                            fontFamily = FontFamily.Monospace,
                                            color = Everforest.Grey2,
                                            maxLines = 1,
                                        )
                                    }
                                }
                            }
                        }

                        // Refresh button
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = Everforest.Bg2,
                            border = BorderStroke(0.8.dp, Everforest.Bg3),
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .clickable(enabled = !loading, onClick = onRefresh)
                                .testTag("subagent_inspector_refresh_btn"),
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                if (loading) {
                                    CircularProgressIndicator(
                                        color = Everforest.SoftSand,
                                        strokeWidth = 1.6.dp,
                                        modifier = Modifier.size(13.dp),
                                    )
                                } else {
                                    Icon(
                                        imageVector = Icons.Rounded.Refresh,
                                        contentDescription = "刷新",
                                        tint = Everforest.Fg,
                                        modifier = Modifier.size(14.dp),
                                    )
                                }
                                Text(
                                    text = "刷新",
                                    fontSize = 11.5.sp,
                                    color = Everforest.Fg,
                                )
                            }
                        }

                        // Close button
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = Everforest.Bg2,
                            border = BorderStroke(0.8.dp, Everforest.Bg3),
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .clickable(onClick = onClose)
                                .testTag("subagent_inspector_close_btn"),
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(3.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Close,
                                    contentDescription = "关闭",
                                    tint = Everforest.Grey2,
                                    modifier = Modifier.size(14.dp),
                                )
                                Text(
                                    text = "关闭",
                                    fontSize = 11.5.sp,
                                    color = Everforest.Grey2,
                                )
                            }
                        }
                    }
                }

                HorizontalDivider(color = Everforest.Bg2, thickness = 1.dp)

                // Transcript body
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .background(Everforest.Bg0),
                ) {
                    when {
                        loading && transcript.items.isEmpty() -> {
                            Column(
                                modifier = Modifier.align(Alignment.Center),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                CircularProgressIndicator(
                                    color = Everforest.SoftSand,
                                    strokeWidth = 2.dp,
                                    modifier = Modifier.size(22.dp),
                                )
                                Text(
                                    text = "正在加载 SubAgent 执行记录…",
                                    fontSize = 12.5.sp,
                                    color = Everforest.CommentGrey,
                                )
                            }
                        }
                        !error.isNullOrBlank() && transcript.items.isEmpty() -> {
                            Column(
                                modifier = Modifier
                                    .align(Alignment.Center)
                                    .padding(24.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.ErrorOutline,
                                    contentDescription = null,
                                    tint = Everforest.SoftRed,
                                    modifier = Modifier.size(26.dp),
                                )
                                Text(
                                    text = error,
                                    fontSize = 12.5.sp,
                                    color = Everforest.Fg,
                                )
                                OutlinedButton(
                                    onClick = onRefresh,
                                    border = BorderStroke(1.dp, Everforest.Bg3),
                                ) {
                                    Text("重新加载", fontSize = 12.sp, color = Everforest.Green)
                                }
                            }
                        }
                        transcript.items.isEmpty() -> {
                            Column(
                                modifier = Modifier.align(Alignment.Center),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                Text(
                                    text = if (subagent.isActive()) "子任务刚启动，等待输出…" else "暂无可展示的执行记录",
                                    fontSize = 13.sp,
                                    color = Everforest.CommentGrey,
                                )
                            }
                        }
                        else -> {
                            ConversationStreamView(
                                sessionKey = subagent.id,
                                activeSession = syntheticSession,
                                transcript = transcript,
                                isSessionRunning = subagent.isActive(),
                                onLoadOlder = {},
                                onDismissKeyboard = {},
                                listTestTag = "subagent_inspector_lazy_list",
                            )
                        }
                    }
                }
            }
        }
    }
}
