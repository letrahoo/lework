package dev.lework.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.lework.platform.epochMillis
import dev.lework.protocol.SessionMeta
import dev.lework.protocol.SessionSource
import dev.lework.protocol.SessionState
import dev.lework.protocol.SubagentSummary
import dev.lework.protocol.ToolStatus
import dev.lework.store.ChatTimelineItem
import dev.lework.store.SessionTranscript
import kotlin.math.abs
import kotlinx.coroutines.delay

private fun SubagentSummary.isActive(): Boolean =
    state == SessionState.RUNNING || state == SessionState.WAITING_PERMISSION

private fun SubagentSummary.accentColor(): Color = when (state) {
    SessionState.RUNNING, SessionState.WAITING_PERMISSION -> Everforest.Orange
    SessionState.ERROR -> Everforest.Red
    SessionState.IDLE -> Everforest.CommentGrey
    else -> Everforest.Green
}

private fun SubagentSummary.displayStatusLabel(): String =
    // Older Hubs also used idle for stale tasks and labeled them completed.
    statusText?.trim()?.takeIf { state != SessionState.IDLE && it.isNotEmpty() } ?: when (state) {
        SessionState.RUNNING -> "运行中…"
        SessionState.WAITING_PERMISSION -> "等待审批…"
        SessionState.ERROR -> "执行出错"
        SessionState.CLOSED -> "已结束"
        SessionState.IDLE -> "已暂停 · 待确认"
    }

private fun SubagentSummary.stepProgressLabel(): String? {
    val total = stepsTotal ?: return null
    if (total <= 0) return null
    val done = (stepsDone ?: 0).coerceIn(0, total)
    return "$done/$total 步"
}

internal fun formatSubagentDuration(startMs: Long?, endMs: Long, includeZero: Boolean = false): String? {
    if (startMs == null || startMs <= 0L || endMs <= 0L) return null
    val sec = ((endMs - startMs) / 1000L).coerceAtLeast(0L)
    if (sec == 0L && !includeZero) return null
    return formatStepElapsedSeconds(sec)
}

internal fun matchSubagentForToolCall(
    tool: ChatTimelineItem.Tool,
    meta: ParsedSubagentToolMeta,
    subagents: List<SubagentSummary>,
): SubagentSummary? {
    if (subagents.isEmpty()) return null
    val refId = meta.referencedId
    if (!refId.isNullOrBlank()) {
        subagents.firstOrNull { sub ->
            sub.id == refId || sub.id.endsWith(":$refId") || sub.id.endsWith(refId)
        }?.let { return it }
    }
    val normTitle = meta.title.trim().lowercase()
    if (normTitle.length >= 3 && normTitle != "子任务") {
        subagents.firstOrNull { sub ->
            sub.title.trim().equals(normTitle, ignoreCase = true) ||
                sub.role?.trim()?.equals(normTitle, ignoreCase = true) == true
        }?.let { return it }
        subagents.firstOrNull { sub ->
            val st = sub.title.trim().lowercase()
            st.length >= 4 && (st.contains(normTitle) || normTitle.contains(st))
        }?.let { return it }
    }
    if (tool.ts > 0L) {
        return subagents
            .mapNotNull { sub ->
                val anchor = sub.createdAt ?: return@mapNotNull null
                val delta = abs(anchor - tool.ts)
                if (delta <= 15_000L) sub to delta else null
            }
            .minByOrNull { it.second }
            ?.first
    }
    return null
}

@Composable
private fun rememberLiveSubagentNowMs(active: Boolean, key: String): Long {
    var nowMs by remember(key) { mutableLongStateOf(epochMillis()) }
    LaunchedEffect(key, active) {
        if (!active) return@LaunchedEffect
        while (true) {
            delay(1_000L)
            nowMs = epochMillis()
        }
    }
    return nowMs
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
        SessionState.IDLE -> Everforest.CommentGrey
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

@Composable
internal fun SubagentStatusStrip(
    sessionId: String,
    subagents: List<SubagentSummary>,
    onSelectSubagent: (SubagentSummary) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (subagents.isEmpty()) return

    val (current, history) = subagents.partition { it.state != SessionState.CLOSED && it.state != SessionState.ERROR }
    val historyOnly = current.isEmpty()
    var expanded by remember(sessionId, historyOnly) { mutableStateOf(false) }
    var showHistory by remember(sessionId, expanded, historyOnly) { mutableStateOf(false) }
    val countLabel = when {
        historyOnly -> "子任务历史 · ${history.size}"
        current.all { it.state == SessionState.RUNNING } -> "${current.size} 运行中"
        else -> "${current.size} 项待跟进"
    }
    val pillFg = if (historyOnly) Everforest.CommentGrey else Everforest.Orange

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = Everforest.Bg1,
        border = BorderStroke(
            width = 1.dp,
            color = if (historyOnly) Everforest.Bg2 else Everforest.Orange.copy(alpha = 0.32f),
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
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 28.dp),
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
                        text = "子任务",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Everforest.HeadingWarm,
                    )
                    Surface(
                        shape = RoundedCornerShape(999.dp),
                        color = pillFg.copy(alpha = 0.10f),
                        border = BorderStroke(0.8.dp, pillFg.copy(alpha = 0.32f)),
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

                if (!expanded && !historyOnly) {
                    Row(
                        modifier = Modifier
                            .weight(1f)
                            .horizontalScroll(rememberScrollState()),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        for (sub in current) {
                            key(sub.id) {
                                SubagentMiniChip(
                                    subagent = sub,
                                    onClick = { onSelectSubagent(sub) },
                                )
                            }
                        }
                    }
                } else if (expanded) {
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
                    modifier = Modifier.fillMaxWidth().heightIn(max = 240.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    HorizontalDivider(color = Everforest.Bg2, thickness = 1.dp)
                    for (sub in current) {
                        key(sub.id) {
                            SubagentDetailRow(
                                subagent = sub,
                                onClick = { onSelectSubagent(sub) },
                            )
                        }
                    }
                    if (!historyOnly && history.isNotEmpty()) {
                        TextButton(onClick = { showHistory = !showHistory }) {
                            Text(if (showHistory) "收起历史" else "查看历史子任务（${history.size}）", color = Everforest.CommentGrey)
                        }
                    }
                    if (historyOnly || showHistory) {
                        for (sub in history) key(sub.id) {
                            SubagentDetailRow(sub, onClick = { onSelectSubagent(sub) })
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
    val nowMs = rememberLiveSubagentNowMs(active, subagent.id)
    val stepLabel = subagent.stepProgressLabel()
    val elapsedLabel = formatSubagentDuration(
        startMs = subagent.createdAt,
        endMs = if (active) nowMs else subagent.updatedAt,
        includeZero = active,
    )
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
            if (stepLabel != null) {
                Text(
                    text = stepLabel,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Medium,
                    color = Everforest.Aqua,
                    maxLines = 1,
                )
            }
            if (!subagent.statusText.isNullOrBlank() || subagent.state != SessionState.RUNNING) {
                Text(
                    text = "· ${subagent.displayStatusLabel()}",
                    fontSize = 10.5.sp,
                    color = Everforest.SoftSand,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 96.dp),
                )
            }
            if (elapsedLabel != null) {
                Text(
                    text = elapsedLabel,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    color = Everforest.CommentGrey,
                    maxLines = 1,
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
    val nowMs = rememberLiveSubagentNowMs(active, subagent.id)
    val stepLabel = subagent.stepProgressLabel()
    val elapsedLabel = formatSubagentDuration(
        startMs = subagent.createdAt,
        endMs = if (active) nowMs else subagent.updatedAt,
        includeZero = active,
    )
    val depth = (subagent.depth ?: 1).coerceAtLeast(1)
    val nestedIndent = ((depth - 1).coerceAtMost(3) * 14).dp
    val statusColor = when (subagent.state) {
        SessionState.RUNNING, SessionState.WAITING_PERMISSION -> Everforest.SoftSand
        SessionState.ERROR -> Everforest.SoftRed
        else -> Everforest.CommentGrey
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = nestedIndent),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        if (depth > 1) {
            Text(
                text = "└",
                fontSize = 11.5.sp,
                fontFamily = FontFamily.Monospace,
                color = Everforest.Bg4,
            )
        }
        Surface(
            shape = RoundedCornerShape(9.dp),
            color = Everforest.Bg0,
            border = BorderStroke(
                width = 0.8.dp,
                color = if (active) Everforest.Orange.copy(alpha = 0.36f) else Everforest.Bg2,
            ),
            modifier = Modifier
                .weight(1f)
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
                        if (stepLabel != null) {
                            Surface(
                                shape = RoundedCornerShape(4.dp),
                                color = Everforest.Aqua.copy(alpha = 0.14f),
                            ) {
                                Text(
                                    text = stepLabel,
                                    fontSize = 10.sp,
                                    fontFamily = FontFamily.Monospace,
                                    color = Everforest.Aqua,
                                    maxLines = 1,
                                    modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp),
                                )
                            }
                        }
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(
                            text = subagent.displayStatusLabel(),
                            fontSize = 11.sp,
                            color = statusColor,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        if (elapsedLabel != null) {
                            Text(
                                text = "· $elapsedLabel",
                                fontSize = 10.5.sp,
                                fontFamily = FontFamily.Monospace,
                                color = Everforest.CommentGrey,
                                maxLines = 1,
                            )
                        }
                    }
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
}

/**
 * First-class inline timeline card for SubAgent / Swarm / Workflow dispatches so multi-agent
 * collaboration is visible directly in the conversation stream and clickable into [SubagentInspectorDialog].
 */
@Composable
internal fun InlineSubagentDispatchCard(
    tool: ChatTimelineItem.Tool,
    matchedSubagent: SubagentSummary?,
    isSessionRunning: Boolean,
    onOpenSubagent: ((SubagentSummary) -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val meta = remember(tool.id, tool.title, tool.input, tool.output) { parseSubagentToolMeta(tool) }
    var detailExpanded by remember(tool.id) { mutableStateOf(false) }

    val resolvedState = matchedSubagent?.state ?: when (tool.status) {
        ToolStatus.COMPLETED -> SessionState.CLOSED
        ToolStatus.FAILED -> SessionState.ERROR
        ToolStatus.IN_PROGRESS, ToolStatus.PENDING ->
            if (isSessionRunning) SessionState.RUNNING else SessionState.CLOSED
    }
    val active = resolvedState == SessionState.RUNNING || resolvedState == SessionState.WAITING_PERMISSION
    val nowMs = rememberLiveSubagentNowMs(active, tool.id)
    val startMs = matchedSubagent?.createdAt ?: tool.ts.takeIf { it > 0L }
    val endMs = if (active) nowMs else (matchedSubagent?.updatedAt ?: tool.ts)
    val elapsedLabel = formatSubagentDuration(startMs, endMs, includeZero = active)
    val stepLabel = matchedSubagent?.stepProgressLabel()
    val displayTitle = matchedSubagent?.title?.takeIf { it.isNotBlank() } ?: meta.title
    val roleLabel = (matchedSubagent?.role ?: meta.roleBadge)
        ?.trim()
        ?.takeIf { it.isNotEmpty() && !it.equals(displayTitle, ignoreCase = true) }
    val statusLine = when {
        matchedSubagent != null -> matchedSubagent.displayStatusLabel()
        !meta.brief.isNullOrBlank() -> meta.brief.replace('\n', ' ').trim()
        resolvedState == SessionState.RUNNING -> "正在执行子任务…"
        resolvedState == SessionState.ERROR -> "执行出错"
        else -> "已完成"
    }
    val canDrillDown = matchedSubagent != null && onOpenSubagent != null
    val hasInlineDetail = !meta.brief.isNullOrBlank() || !tool.output.isNullOrBlank()
    val kindAccent = if (meta.kindLabel == "工作流") Everforest.Aqua else Everforest.Orange
    val borderColor = when {
        active -> kindAccent.copy(alpha = 0.42f)
        resolvedState == SessionState.ERROR -> Everforest.Red.copy(alpha = 0.42f)
        else -> Everforest.Bg3
    }

    Surface(
        shape = RoundedCornerShape(10.dp),
        color = Everforest.Bg1,
        border = BorderStroke(1.dp, borderColor),
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable(enabled = canDrillDown || hasInlineDetail) {
                if (matchedSubagent != null && onOpenSubagent != null) {
                    onOpenSubagent(matchedSubagent)
                } else if (hasInlineDetail) {
                    detailExpanded = !detailExpanded
                }
            }
            .testTag("inline_subagent_card_${tool.id}"),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                SubagentStatusDot(state = resolvedState, sizeDp = 7)

                Surface(
                    shape = RoundedCornerShape(5.dp),
                    color = kindAccent.copy(alpha = 0.14f),
                    border = BorderStroke(0.7.dp, kindAccent.copy(alpha = 0.35f)),
                ) {
                    Text(
                        text = meta.kindLabel,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.SemiBold,
                        fontFamily = FontFamily.Monospace,
                        color = kindAccent,
                        modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp),
                    )
                }

                Text(
                    text = displayTitle,
                    fontSize = 12.5.sp,
                    fontWeight = FontWeight.Medium,
                    color = Everforest.Fg,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )

                if (roleLabel != null) {
                    Surface(
                        shape = RoundedCornerShape(4.dp),
                        color = Everforest.Bg2,
                    ) {
                        Text(
                            text = roleLabel,
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            color = Everforest.InlineCode,
                            maxLines = 1,
                            modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp),
                        )
                    }
                }

                if (stepLabel != null) {
                    Surface(
                        shape = RoundedCornerShape(4.dp),
                        color = Everforest.Aqua.copy(alpha = 0.14f),
                    ) {
                        Text(
                            text = stepLabel,
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            color = Everforest.Aqua,
                            maxLines = 1,
                            modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp),
                        )
                    }
                }

                Spacer(Modifier.weight(1f))

                if (elapsedLabel != null) {
                    Text(
                        text = elapsedLabel,
                        fontSize = 10.5.sp,
                        fontFamily = FontFamily.Monospace,
                        color = Everforest.CommentGrey,
                        maxLines = 1,
                    )
                }

                if (canDrillDown) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                        modifier = Modifier.testTag("inline_subagent_open_${tool.id}"),
                    ) {
                        Text(
                            text = "查看详情",
                            fontSize = 11.sp,
                            color = Everforest.BoldGold,
                            fontWeight = FontWeight.Medium,
                        )
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                            contentDescription = "查看子任务详情",
                            tint = Everforest.BoldGold,
                            modifier = Modifier.size(14.dp),
                        )
                    }
                } else if (hasInlineDetail) {
                    Icon(
                        imageVector = if (detailExpanded) {
                            Icons.Rounded.KeyboardArrowDown
                        } else {
                            Icons.AutoMirrored.Rounded.KeyboardArrowRight
                        },
                        contentDescription = null,
                        tint = Everforest.CommentGrey,
                        modifier = Modifier.size(15.dp),
                    )
                }
            }

            // Tree subtitle row (`└ activity / prompt summary`)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = "└",
                    fontSize = 11.5.sp,
                    fontFamily = FontFamily.Monospace,
                    color = Everforest.Bg4,
                )
                Text(
                    text = statusLine,
                    fontSize = 11.5.sp,
                    color = if (active) Everforest.SoftSand else Everforest.CommentGrey,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            }

            // Standalone expandable prompt / output preview when no live SubagentSummary matches
            AnimatedVisibility(visible = detailExpanded && !canDrillDown && hasInlineDetail) {
                Surface(
                    shape = RoundedCornerShape(7.dp),
                    color = Everforest.BgDim,
                    border = BorderStroke(0.8.dp, Everforest.Bg2),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 2.dp),
                ) {
                    SelectionContainer {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 9.dp, vertical = 7.dp),
                            verticalArrangement = Arrangement.spacedBy(5.dp),
                        ) {
                            if (!meta.brief.isNullOrBlank()) {
                                Text(
                                    text = meta.brief.take(800),
                                    fontSize = 11.5.sp,
                                    lineHeight = 17.sp,
                                    color = Everforest.Fg,
                                )
                            }
                            val cleanedOut = tool.output?.trim().orEmpty()
                            if (cleanedOut.isNotEmpty()) {
                                if (!meta.brief.isNullOrBlank()) {
                                    HorizontalDivider(color = Everforest.Bg2, thickness = 0.8.dp)
                                }
                                Text(
                                    text = cleanedOut.take(800),
                                    fontSize = 11.sp,
                                    lineHeight = 16.sp,
                                    fontFamily = FontFamily.Monospace,
                                    color = Everforest.Grey2,
                                )
                            }
                        }
                    }
                }
            }
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
    val nowMs = rememberLiveSubagentNowMs(subagent.isActive(), subagent.id)
    val inspectorStepLabel = subagent.stepProgressLabel()
    val inspectorElapsed = formatSubagentDuration(
        startMs = subagent.createdAt,
        endMs = if (subagent.isActive()) nowMs else subagent.updatedAt,
        includeZero = subagent.isActive(),
    )
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

    ModalEscapeEffect(onDismiss = onClose)

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
                .dialogEscapeDismiss(onDismiss = onClose)
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
                                if (inspectorStepLabel != null) {
                                    Surface(
                                        shape = RoundedCornerShape(6.dp),
                                        color = Everforest.Aqua.copy(alpha = 0.14f),
                                    ) {
                                        Text(
                                            text = inspectorStepLabel,
                                            fontSize = 10.5.sp,
                                            fontFamily = FontFamily.Monospace,
                                            color = Everforest.Aqua,
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.5.dp),
                                        )
                                    }
                                }
                                if (inspectorElapsed != null) {
                                    Text(
                                        text = inspectorElapsed,
                                        fontSize = 10.5.sp,
                                        fontFamily = FontFamily.Monospace,
                                        color = Everforest.CommentGrey,
                                        maxLines = 1,
                                    )
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
                                    text = "正在加载子任务执行记录…",
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
