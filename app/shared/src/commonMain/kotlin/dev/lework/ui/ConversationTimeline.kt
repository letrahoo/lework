package dev.lework.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsDraggedAsState
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
import androidx.compose.ui.layout.ContentScale
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
import dev.lework.platform.isDesktopPlatform
import dev.lework.platform.loadSavedRelayUrls
import dev.lework.protocol.*
import dev.lework.store.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
private sealed interface TimelineSegment {
    val key: String
    val contentType: String
    val tightBottomSpacing: Boolean get() = false

    data class Single(val item: ChatTimelineItem) : TimelineSegment {
        override val key: String = "${item.id}_${item::class.simpleName}"
        override val contentType: String = "single_${item::class.simpleName}"
    }

    data class AssistantBlock(
        override val key: String,
        val messageId: String,
        val timestampMs: Long,
        val block: MdBlock,
        val isLastBlockOfMessage: Boolean,
    ) : TimelineSegment {
        override val contentType: String = "md_${block::class.simpleName}"
        override val tightBottomSpacing: Boolean = !isLastBlockOfMessage
    }

    data class ProcessTrace(val steps: List<ChatTimelineItem>) : TimelineSegment {
        override val key: String = "trace_${steps.first().id}"
        override val contentType: String = "process_trace"
    }
}

private fun groupTimelineSegments(items: List<ChatTimelineItem>): List<TimelineSegment> {
    if (items.isEmpty()) return emptyList()
    val out = mutableListOf<TimelineSegment>()
    val pendingProcess = mutableListOf<ChatTimelineItem>()

    fun flushProcess() {
        if (pendingProcess.isNotEmpty()) {
            out.add(TimelineSegment.ProcessTrace(pendingProcess.toList()))
            pendingProcess.clear()
        }
    }

    for (item in items) {
        if (item is ChatTimelineItem.Tool || item is ChatTimelineItem.Reasoning) {
            pendingProcess.add(item)
        } else if (item is ChatTimelineItem.Assistant && item.text.length > 480) {
            flushProcess()
            // Virtualize multi-block Assistant responses directly inside LazyColumn so Skia/Compose
            // only measures & shapes the blocks currently visible in the mobile viewport!
            val blocks = parseMarkdownBlocks(item.text)
            if (blocks.size >= 3) {
                blocks.forEachIndexed { idx, block ->
                    out.add(
                        TimelineSegment.AssistantBlock(
                            key = "${item.id}_b$idx",
                            messageId = item.id,
                            timestampMs = item.ts,
                            block = block,
                            isLastBlockOfMessage = idx == blocks.lastIndex,
                        ),
                    )
                }
            } else {
                out.add(TimelineSegment.Single(item))
            }
        } else {
            flushProcess()
            out.add(TimelineSegment.Single(item))
        }
    }
    flushProcess()
    return out
}

@Composable
internal fun ConversationStreamView(
    sessionKey: String,
    activeSession: SessionMeta?,
    transcript: SessionTranscript,
    isSessionRunning: Boolean,
    onLoadOlder: () -> Unit,
    onCancelQueuedPrompt: (Int) -> Unit = {},
    onClearQueuedPrompts: () -> Unit = {},
    onDismissKeyboard: () -> Unit = {},
    showInlineErrorRetry: Boolean = false,
    onRetryLastTurn: (() -> Unit)? = null,
    listTestTag: String = "conversation_lazy_list",
    onOpenPlanArtifact: (() -> Unit)? = null,
    onApprovePlan: (() -> Unit)? = null,
) {
    val listState = rememberLazyListState()
    val isUserDragging by listState.interactionSource.collectIsDraggedAsState()
    LaunchedEffect(isUserDragging) {
        if (isUserDragging) {
            onDismissKeyboard()
        }
    }
    val segments = remember(transcript.items) { groupTimelineSegments(transcript.items) }
    val reversedSegments = remember(segments) { segments.asReversed() }
    val latestErrorNoticeId = remember(transcript.items) {
        transcript.items.lastOrNull { it is ChatTimelineItem.Notice && it.isError }?.id
    }
    val nowMs = remember(sessionKey, transcript.lastSeq) { epochMillis() }
    val showLiveSpinner = activeSession != null &&
        activeSession.id == sessionKey &&
        activeSession.state == SessionState.RUNNING &&
        transcript.pendingPermissions.isEmpty()
    val queuedPrompts = if (
        activeSession != null &&
        activeSession.id == sessionKey &&
        (activeSession.state == SessionState.RUNNING || activeSession.state == SessionState.WAITING_PERMISSION)
    ) {
        activeSession.queuedPrompts.orEmpty()
    } else {
        emptyList()
    }

    var prevLastSeq by remember(sessionKey) { mutableStateOf(transcript.lastSeq) }
    var prevSpinnerVisible by remember(sessionKey) { mutableStateOf(showLiveSpinner) }
    var prevQueuedCount by remember(sessionKey) { mutableStateOf(queuedPrompts.size) }

    // With `reverseLayout = true`:
    // - Index 0 is ALWAYS the exact pixel bottom (no initial jump or landing mid-message!).
    // - Prepending older history appends items to the END of `reversedSegments` (above viewport),
    //   keeping indices 0..current and ongoing fling velocity 100% untouched (never jumps to top!).
    // - Only when new live events or queued prompts arrive at index 0 while near bottom do we pin to 0.
    LaunchedEffect(transcript.lastSeq, showLiveSpinner, queuedPrompts.size) {
        val hasNewTail = transcript.lastSeq > prevLastSeq ||
            (showLiveSpinner && !prevSpinnerVisible) ||
            queuedPrompts.size > prevQueuedCount
        if (hasNewTail) {
            val nearBottom = listState.firstVisibleItemIndex <= 1 && listState.firstVisibleItemScrollOffset < 120
            if (nearBottom) {
                listState.animateScrollToItem(0)
            }
        }
        prevLastSeq = transcript.lastSeq
        prevSpinnerVisible = showLiveSpinner
        prevQueuedCount = queuedPrompts.size
    }

    // Smoothly trigger reverse pagination when scrolling upward near the oldest loaded item
    val nearOldestEdge by remember(reversedSegments.size) {
        derivedStateOf {
            val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            reversedSegments.isNotEmpty() &&
                listState.isScrollInProgress &&
                lastVisible >= (reversedSegments.size - 3).coerceAtLeast(1)
        }
    }
    LaunchedEffect(nearOldestEdge, transcript.hasMoreBefore, transcript.loadingOlder) {
        if (nearOldestEdge && transcript.hasMoreBefore && !transcript.loadingOlder) {
            onLoadOlder()
        }
    }

    LazyColumn(
        state = listState,
        reverseLayout = true,
        modifier = Modifier
            .fillMaxSize()
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() },
            ) { onDismissKeyboard() }
            .padding(horizontal = 16.dp)
            .testTag(listTestTag),
        contentPadding = PaddingValues(vertical = 12.dp),
        verticalArrangement = Arrangement.Bottom,
    ) {
        if (queuedPrompts.isNotEmpty()) {
            item(key = "queued_prompts_card", contentType = "queued_prompts") {
                QueuedPromptsCard(
                    prompts = queuedPrompts,
                    onCancelItem = onCancelQueuedPrompt,
                    onCancelAll = onClearQueuedPrompts,
                )
            }
        }

        if (showLiveSpinner) {
            item(key = "live_spinner_status", contentType = "live_spinner") {
                Box(modifier = Modifier.padding(bottom = if (queuedPrompts.isNotEmpty()) 10.dp else 0.dp)) {
                    ClaudeLiveSpinnerRow(
                        session = activeSession,
                        outputTokens = transcript.outputTokens,
                    )
                }
            }
        }

        items(
            items = reversedSegments,
            key = { it.key },
            contentType = { it.contentType },
        ) { segment ->
            val bottomPad = when {
                segment === reversedSegments.firstOrNull() && !showLiveSpinner && queuedPrompts.isEmpty() -> 0.dp
                segment.tightBottomSpacing -> 9.dp
                else -> 12.dp
            }
            Box(modifier = Modifier.fillMaxWidth().padding(bottom = bottomPad)) {
                when (segment) {
                    is TimelineSegment.ProcessTrace -> ClaudeProcessTraceView(
                        steps = segment.steps,
                        isSessionRunning = isSessionRunning,
                    )
                    is TimelineSegment.AssistantBlock -> {
                        if (segment.isLastBlockOfMessage) {
                            Column(modifier = Modifier.fillMaxWidth()) {
                                MarkdownBlockItemView(segment.block)
                                MessageTimestampCaption(
                                    prefix = "回复",
                                    timestampMs = segment.timestampMs,
                                    nowMs = nowMs,
                                    alignEnd = false,
                                    tag = "msg_time_assistant_${segment.messageId}",
                                )
                            }
                        } else {
                            MarkdownBlockItemView(segment.block)
                        }
                    }
                    is TimelineSegment.Single -> MessageTimelineRow(
                        item = segment.item,
                        nowMs = nowMs,
                        onInlineRetry = if (
                            showInlineErrorRetry &&
                            !isSessionRunning &&
                            onRetryLastTurn != null &&
                            segment.item is ChatTimelineItem.Notice &&
                            segment.item.isError &&
                            segment.item.id == latestErrorNoticeId
                        ) {
                            onRetryLastTurn
                        } else {
                            null
                        },
                    )
                }
            }
        }

        if (transcript.planEntries.isNotEmpty()) {
            item(key = "plan_card", contentType = "plan_card") {
                Box(modifier = Modifier.padding(bottom = 12.dp)) {
                    PlanCard(
                        entries = transcript.planEntries,
                        onOpenPlanArtifact = onOpenPlanArtifact,
                        onApprovePlan = onApprovePlan,
                    )
                }
            }
        }

        if (transcript.hasMoreBefore || transcript.loadingOlder) {
            item(key = "history_older_loader", contentType = "older_loader") {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp, bottom = 12.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Surface(
                        shape = RoundedCornerShape(999.dp),
                        color = Everforest.Bg1,
                        border = BorderStroke(1.dp, Everforest.Bg2),
                        modifier = Modifier
                            .clip(RoundedCornerShape(999.dp))
                            .clickable(enabled = !transcript.loadingOlder) { onLoadOlder() },
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            if (transcript.loadingOlder) {
                                CircularProgressIndicator(
                                    color = Everforest.CommentGrey,
                                    strokeWidth = 1.6.dp,
                                    modifier = Modifier.size(12.dp),
                                )
                                Text(
                                    text = "正在加载更早记录…",
                                    fontSize = 11.5.sp,
                                    color = Everforest.CommentGrey,
                                )
                            } else {
                                Icon(
                                    imageVector = Icons.Rounded.History,
                                    contentDescription = null,
                                    tint = Everforest.CommentGrey,
                                    modifier = Modifier.size(13.dp),
                                )
                                Text(
                                    text = "继续上滑加载更早对话",
                                    fontSize = 11.5.sp,
                                    color = Everforest.CommentGrey,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private val CLAUDE_SPINNER_FRAMES = listOf("·", "✻", "✽", "✢", "✳", "✶")

@Composable
private fun QueuedPromptsCard(
    prompts: List<String>,
    onCancelItem: (Int) -> Unit,
    onCancelAll: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val hasLongContent = prompts.size > 2 || prompts.any { it.length > 90 || it.contains('\n') }
    LaunchedEffect(hasLongContent) {
        if (!hasLongContent) expanded = false
    }
    val isExpanded = expanded && hasLongContent
    val displayed = if (isExpanded) prompts else prompts.take(2)

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = Everforest.Bg1,
        border = BorderStroke(1.dp, Everforest.Bg3),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("queued_prompts_card"),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = if (prompts.size == 1) "↳ 队列待执行" else "↳ 队列待执行 (${prompts.size})",
                    fontSize = 11.5.sp,
                    fontFamily = FontFamily.Monospace,
                    color = Everforest.CommentGrey,
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (prompts.size > 1) {
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = Everforest.Bg2,
                            border = BorderStroke(0.8.dp, Everforest.Bg3),
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .clickable { onCancelAll() }
                                .testTag("queued_prompt_cancel_all"),
                        ) {
                            Text(
                                text = "全部取消",
                                fontSize = 11.sp,
                                color = Everforest.SoftRed,
                                fontWeight = FontWeight.Medium,
                                modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
                            )
                        }
                    }
                    if (hasLongContent) {
                        Text(
                            text = if (isExpanded) "收起" else "展开",
                            fontSize = 11.sp,
                            color = Everforest.CommentGrey,
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .clickable { expanded = !expanded }
                                .padding(horizontal = 4.dp, vertical = 2.dp)
                                .testTag("queued_prompts_expand_toggle"),
                        )
                    }
                }
            }
            displayed.forEachIndexed { idx, prompt ->
                val cleanText = if (isExpanded) prompt else prompt.replace('\n', ' ')
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = if (prompts.size > 1) "${idx + 1}. $cleanText" else cleanText,
                        fontSize = 13.5.sp,
                        lineHeight = 19.sp,
                        maxLines = if (isExpanded) 12 else 2,
                        overflow = TextOverflow.Ellipsis,
                        color = Everforest.Fg,
                        modifier = Modifier
                            .weight(1f)
                            .then(
                                if (hasLongContent) {
                                    Modifier.clickable(
                                        interactionSource = remember { MutableInteractionSource() },
                                        indication = null,
                                    ) { expanded = !expanded }
                                } else {
                                    Modifier
                                },
                            ),
                    )
                    Surface(
                        shape = CircleShape,
                        color = Everforest.Bg2,
                        border = BorderStroke(0.8.dp, Everforest.Bg3),
                        modifier = Modifier
                            .size(24.dp)
                            .clip(CircleShape)
                            .clickable { onCancelItem(idx) }
                            .testTag("queued_prompt_cancel_$idx"),
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Rounded.Close,
                                contentDescription = "取消该条队列消息",
                                tint = Everforest.Grey2,
                                modifier = Modifier.size(13.5.dp),
                            )
                        }
                    }
                }
            }
            if (!isExpanded && prompts.size > displayed.size) {
                Text(
                    text = "还有 ${prompts.size - displayed.size} 条待执行…",
                    fontSize = 11.5.sp,
                    color = Everforest.CommentGrey,
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .clickable { expanded = true }
                        .padding(vertical = 1.dp),
                )
            }
        }
    }
}

@Composable
private fun ClaudeLiveSpinnerRow(
    session: SessionMeta,
    outputTokens: Int,
) {
    var tick by remember(session.id) { mutableStateOf(0) }
    var nowMs by remember(session.id) { mutableStateOf(epochMillis()) }

    LaunchedEffect(session.id) {
        while (true) {
            delay(250L)
            tick++
            nowMs = epochMillis()
        }
    }

    val rawLive = session.liveStatusText?.trim().orEmpty()
    val styledLine = if (rawLive.isNotEmpty()) {
        val parenIdx = rawLive.indexOf(" (")
        buildAnnotatedString {
            if (parenIdx > 0) {
                withStyle(SpanStyle(color = Everforest.SoftSand, fontWeight = FontWeight.Medium)) {
                    append(rawLive.substring(0, parenIdx))
                }
                withStyle(SpanStyle(color = Everforest.CommentGrey)) {
                    append(rawLive.substring(parenIdx))
                }
            } else {
                withStyle(SpanStyle(color = Everforest.SoftSand, fontWeight = FontWeight.Medium)) {
                    append(rawLive)
                }
            }
        }
    } else {
        val startMs = session.turnStartedAt ?: session.updatedAt
        val elapsedSec = ((nowMs - startMs) / 1000L).coerceAtLeast(0L)
        val elapsedStr = formatCliDuration(elapsedSec)
        val tokenStr = if (outputTokens > 0) " · ↓ ${formatCliTokens(outputTokens)} tokens" else ""
        val glyph = CLAUDE_SPINNER_FRAMES[tick % CLAUDE_SPINNER_FRAMES.size]
        buildAnnotatedString {
            withStyle(SpanStyle(color = Everforest.SoftSand, fontWeight = FontWeight.Medium)) {
                append("$glyph Thinking…")
            }
            withStyle(SpanStyle(color = Everforest.CommentGrey)) {
                append(" ($elapsedStr$tokenStr)")
            }
        }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp, horizontal = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = styledLine,
            fontSize = 13.sp,
            lineHeight = 19.sp,
            fontFamily = FontFamily.Monospace,
        )
    }
}

private fun formatCliDuration(sec: Long): String {
    if (sec < 60L) return "${sec}s"
    val mins = sec / 60L
    val rem = sec % 60L
    return "${mins}m ${rem}s"
}

private fun formatCliTokens(tokens: Int): String {
    if (tokens < 1000) return tokens.toString()
    val tenths = (tokens * 10) / 1000
    val whole = tenths / 10
    val frac = tenths % 10
    return "$whole.${frac}k"
}

@Composable
private fun PlanCard(
    entries: List<PlanEntry>,
    onOpenPlanArtifact: (() -> Unit)? = null,
    onApprovePlan: (() -> Unit)? = null,
) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = Everforest.Bg1,
        border = BorderStroke(1.dp, Everforest.Bg3),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Checklist,
                        contentDescription = null,
                        tint = Everforest.Aqua,
                        modifier = Modifier.size(16.dp),
                    )
                    Text(
                        text = "执行计划",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Everforest.Aqua,
                    )
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    if (onOpenPlanArtifact != null) {
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = Everforest.Bg2,
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .clickable { onOpenPlanArtifact() },
                        ) {
                            Text(
                                text = "查看方案",
                                fontSize = 10.5.sp,
                                color = Everforest.BoldGold,
                                modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
                            )
                        }
                    }
                    if (onApprovePlan != null) {
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = Everforest.BgGreen,
                            border = BorderStroke(0.8.dp, Everforest.Green.copy(alpha = 0.5f)),
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .clickable { onApprovePlan() }
                                .testTag("plan_card_approve_btn"),
                        ) {
                            Text(
                                text = "确认执行",
                                fontSize = 10.5.sp,
                                fontWeight = FontWeight.Medium,
                                color = Everforest.Green,
                                modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
                            )
                        }
                    }
                }
            }
            for (e in entries) {
                val (icon, tint) = when (e.status) {
                    "completed" -> Icons.Rounded.CheckCircle to Everforest.Green
                    "in_progress" -> Icons.Rounded.Autorenew to Everforest.Yellow
                    else -> Icons.Rounded.RadioButtonUnchecked to Everforest.Grey0
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(14.dp))
                    Text(e.content, fontSize = 12.sp, color = Everforest.Fg)
                }
            }
        }
    }
}

@Composable
private fun MessageTimestampCaption(
    prefix: String,
    timestampMs: Long,
    nowMs: Long,
    alignEnd: Boolean,
    tag: String,
) {
    val formatted = remember(timestampMs, nowMs) {
        formatMessageTimestamp(epochMs = timestampMs, nowMs = nowMs)
    }
    if (formatted.isEmpty()) return
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                top = 3.dp,
                start = if (alignEnd) 0.dp else 2.dp,
                end = if (alignEnd) 4.dp else 0.dp,
            ),
        horizontalArrangement = if (alignEnd) Arrangement.End else Arrangement.Start,
    ) {
        Text(
            text = "$prefix $formatted",
            fontSize = 10.5.sp,
            lineHeight = 14.sp,
            fontFamily = FontFamily.Monospace,
            color = Everforest.Grey0.copy(alpha = 0.85f),
            modifier = Modifier.testTag(tag),
        )
    }
}

@Composable
private fun MessageTimelineRow(
    item: ChatTimelineItem,
    nowMs: Long,
    onInlineRetry: (() -> Unit)? = null,
) {
    when (item) {
        is ChatTimelineItem.User -> {
            var lightboxIdx by remember(item.id) { mutableStateOf<Int?>(null) }
            val isDesktop = isDesktopPlatform()
            val hasPreviews = item.imagePreviews.isNotEmpty()
            val isAutoPlaceholderOnly =
                hasPreviews && Regex("^（已发送\\s*\\d+\\s*张图片）$").matches(item.text.trim())
            val bubbleMaxWidth = when {
                isDesktop && item.imagePreviews.size > 1 -> 460.dp
                isDesktop && hasPreviews -> 360.dp
                else -> 316.dp
            }

            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.End,
            ) {
                Surface(
                    color = Everforest.Bg2,
                    shape = RoundedCornerShape(18.dp, 18.dp, 4.dp, 18.dp),
                    border = BorderStroke(1.dp, Everforest.Bg3),
                    modifier = Modifier.widthIn(max = bubbleMaxWidth),
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        if (hasPreviews) {
                            Row(
                                modifier = Modifier.horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                item.imagePreviews.forEachIndexed { idx, b64 ->
                                    val bmp = rememberBase64ImageBitmap(b64)
                                    val thumbModifier = if (item.imagePreviews.size == 1) {
                                        if (isDesktop) {
                                            Modifier.size(width = 220.dp, height = 156.dp)
                                        } else {
                                            Modifier.size(width = 172.dp, height = 124.dp)
                                        }
                                    } else {
                                        Modifier.size(if (isDesktop) 96.dp else 82.dp)
                                    }
                                    Surface(
                                        shape = RoundedCornerShape(12.dp),
                                        color = Everforest.Bg1,
                                        border = BorderStroke(1.dp, Everforest.Bg3),
                                        modifier = thumbModifier
                                            .clip(RoundedCornerShape(12.dp))
                                            .clickable { lightboxIdx = idx }
                                            .testTag("user_bubble_img_${item.id}_$idx"),
                                    ) {
                                        if (bmp != null) {
                                            Image(
                                                bitmap = bmp,
                                                contentDescription = "用户发送图片 ${idx + 1}",
                                                contentScale = ContentScale.Crop,
                                                modifier = Modifier.fillMaxSize(),
                                            )
                                        } else {
                                            Box(contentAlignment = Alignment.Center) {
                                                Icon(
                                                    imageVector = Icons.Rounded.Image,
                                                    contentDescription = null,
                                                    tint = Everforest.Green,
                                                    modifier = Modifier.size(22.dp),
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        } else if (item.imageCount > 0) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Image,
                                    contentDescription = null,
                                    tint = Everforest.Green,
                                    modifier = Modifier.size(13.dp),
                                )
                                Text(
                                    text = "已附带 ${item.imageCount} 张图片",
                                    fontSize = 11.5.sp,
                                    color = Everforest.Green,
                                )
                            }
                        }

                        if (!isAutoPlaceholderOnly && item.text.isNotBlank()) {
                            SelectionContainer {
                                Text(
                                    text = item.text,
                                    fontSize = 15.sp,
                                    lineHeight = 22.sp,
                                    color = Everforest.Fg,
                                )
                            }
                        }
                    }
                }
                MessageTimestampCaption(
                    prefix = "发送",
                    timestampMs = item.ts,
                    nowMs = nowMs,
                    alignEnd = true,
                    tag = "msg_time_user_${item.id}",
                )
            }

            if (lightboxIdx != null && hasPreviews) {
                ImageLightboxDialog(
                    imagesBase64 = item.imagePreviews,
                    initialIndex = lightboxIdx ?: 0,
                    onDismiss = { lightboxIdx = null },
                )
            }
        }
        is ChatTimelineItem.Reasoning -> {
            ClaudeProcessTraceView(steps = listOf(item))
        }
        is ChatTimelineItem.Assistant -> {
            Column(modifier = Modifier.fillMaxWidth()) {
                EverforestMarkdownContent(
                    markdown = item.text,
                    modifier = Modifier.padding(vertical = 2.dp),
                )
                MessageTimestampCaption(
                    prefix = "回复",
                    timestampMs = item.ts,
                    nowMs = nowMs,
                    alignEnd = false,
                    tag = "msg_time_assistant_${item.id}",
                )
            }
        }
        is ChatTimelineItem.Tool -> {
            ClaudeCliToolCallRow(item = item)
        }
        is ChatTimelineItem.Notice -> {
            val isNetworkNotice = item.isError && isLikelyNetworkError(item.text)
            Surface(
                color = if (item.isError) Everforest.BgRed else Everforest.Bg1,
                border = if (item.isError) BorderStroke(1.dp, Everforest.Red.copy(alpha = 0.35f)) else null,
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(
                        imageVector = when {
                            isNetworkNotice -> Icons.Rounded.WifiOff
                            item.isError -> Icons.Rounded.ErrorOutline
                            else -> Icons.Rounded.Info
                        },
                        contentDescription = null,
                        tint = if (item.isError) Everforest.Red else Everforest.Grey2,
                        modifier = Modifier.size(16.dp),
                    )
                    Text(
                        text = item.text,
                        fontSize = 12.sp,
                        lineHeight = 17.sp,
                        color = if (item.isError) Everforest.Red else Everforest.Fg,
                        modifier = Modifier.weight(1f),
                    )
                    if (onInlineRetry != null) {
                        TextButton(
                            onClick = onInlineRetry,
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                            modifier = Modifier.heightIn(min = 26.dp),
                        ) {
                            Text(
                                text = "重试继续",
                                fontSize = 11.5.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = Everforest.Green,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun InlineApprovalBanner(
    permission: AgentEvent.PermissionRequested,
    isSubmitting: Boolean = false,
    errorText: String? = null,
    onRefresh: (() -> Unit)? = null,
    onDecision: (requestId: String, optionId: String?, sessionId: String) -> Unit,
) {
    val nonBlankLines = remember(permission.title) {
        permission.title.lines().map { it.trim() }.filter { it.isNotEmpty() }
    }
    val headerTitle = nonBlankLines.firstOrNull() ?: "需要执行审批"
    val cmdPreview = if (nonBlankLines.size > 1) nonBlankLines.drop(1).joinToString("\n") else null

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .testTag("inline_approval_banner"),
        shape = RoundedCornerShape(16.dp),
        color = Everforest.BgYellow,
        border = BorderStroke(1.dp, Everforest.Yellow.copy(alpha = 0.6f)),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (isSubmitting) {
                    CircularProgressIndicator(
                        color = Everforest.BoldGold,
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(16.dp),
                    )
                } else {
                    Icon(
                        imageVector = Icons.Rounded.Shield,
                        contentDescription = null,
                        tint = Everforest.BoldGold,
                        modifier = Modifier.size(18.dp),
                    )
                }
                Text(
                    text = if (isSubmitting) "正在向主机终端发送确认…" else headerTitle,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Everforest.BoldGold,
                    modifier = Modifier.weight(1f),
                )
                if (onRefresh != null && permission.options.isEmpty()) {
                    TextButton(
                        onClick = onRefresh,
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                    ) {
                        Text("同步选项", fontSize = 11.5.sp, color = Everforest.Green)
                    }
                }
            }
            if (!cmdPreview.isNullOrBlank()) {
                if (permission.options.isEmpty()) {
                    Text(
                        text = cmdPreview,
                        fontSize = 12.5.sp,
                        lineHeight = 18.sp,
                        color = Everforest.Fg,
                    )
                } else {
                    val highlightedCmd = remember(cmdPreview) { highlightEverforestCode(cmdPreview, "zsh") }
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = Everforest.BgDim,
                        border = BorderStroke(1.dp, Everforest.Bg3),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            text = highlightedCmd,
                            fontSize = 12.sp,
                            lineHeight = 17.sp,
                            fontFamily = FontFamily.Monospace,
                            color = Everforest.Fg,
                            maxLines = 4,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                        )
                    }
                }
            }
            if (!errorText.isNullOrBlank()) {
                Text(
                    text = errorText,
                    fontSize = 12.sp,
                    lineHeight = 17.sp,
                    color = Everforest.Red,
                )
            }
            if (permission.options.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    for (opt in permission.options) {
                        val isReject = opt.kind == PermissionOptionKind.REJECT_ONCE ||
                            opt.kind == PermissionOptionKind.REJECT_ALWAYS
                        if (isReject) {
                            OutlinedButton(
                                enabled = !isSubmitting,
                                onClick = { onDecision(permission.requestId, opt.id, permission.sessionId) },
                                border = BorderStroke(1.dp, Everforest.Red.copy(alpha = 0.6f)),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = Everforest.Red),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                            ) {
                                Text(opt.name, fontSize = 12.sp)
                            }
                        } else {
                            Button(
                                enabled = !isSubmitting,
                                onClick = { onDecision(permission.requestId, opt.id, permission.sessionId) },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = Everforest.Green,
                                    contentColor = Everforest.BgDim,
                                ),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                            ) {
                                Text(opt.name, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }
                    if (permission.requestId.startsWith("tty_perm_")) {
                        TextButton(
                            enabled = !isSubmitting,
                            onClick = { onDecision(permission.requestId, null, permission.sessionId) },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                        ) {
                            Text("取消 (Esc)", fontSize = 11.5.sp, color = Everforest.CommentGrey)
                        }
                    }
                }
            }
        }
    }
}

