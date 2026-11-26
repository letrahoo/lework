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
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.lework.platform.copyTextToClipboard
import dev.lework.platform.epochMillis
import dev.lework.platform.isDesktopPlatform
import dev.lework.platform.logRuntimeEvent
import dev.lework.protocol.*
import dev.lework.store.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

@OptIn(ExperimentalMaterial3Api::class)
private sealed interface TimelineSegment {
    val key: String
    val contentType: String
    val seq: Int
    val tightBottomSpacing: Boolean get() = false

    data class Single(val item: ChatTimelineItem) : TimelineSegment {
        override val key: String = "${item.id}_${item::class.simpleName}"
        override val contentType: String = "single_${item::class.simpleName}"
        override val seq: Int = item.seq
    }

    data class AssistantBlock(
        override val key: String,
        val messageId: String,
        val fullText: String,
        val timestampMs: Long,
        override val seq: Int,
        val block: MdBlock,
        val isLastBlockOfMessage: Boolean,
    ) : TimelineSegment {
        override val contentType: String = "md_${block::class.simpleName}"
        override val tightBottomSpacing: Boolean = !isLastBlockOfMessage
    }

    data class ProcessTrace(val steps: List<ChatTimelineItem>) : TimelineSegment {
        override val key: String = "trace_${steps.first().id}"
        override val contentType: String = "process_trace"
        override val seq: Int = steps.firstOrNull()?.seq ?: -1
    }
}

private fun groupTimelineSegments(
    items: List<ChatTimelineItem>,
    isDesktop: Boolean,
): List<TimelineSegment> {
    if (items.isEmpty()) return emptyList()
    // Keep normal & medium-long Assistant replies in a single unified SelectionContainer so
    // drag-selection spans headings, paragraphs, lists, tables, and code blocks seamlessly.
    // Virtualize only genuinely huge multi-screen essays inside LazyColumn.
    val virtualizeMinChars = if (isDesktop) 16_000 else 3_200
    val virtualizeMinBlocks = if (isDesktop) 32 else 10
    val maxInlineBlocks = if (isDesktop) 64 else 32

    val out = mutableListOf<TimelineSegment>()
    val pendingProcess = mutableListOf<ChatTimelineItem>()

    fun flushProcess() {
        if (pendingProcess.isNotEmpty()) {
            out.add(TimelineSegment.ProcessTrace(pendingProcess.toList()))
            pendingProcess.clear()
        }
    }

    for (item in items) {
        if (item is ChatTimelineItem.Tool && isSubagentDispatchTool(item)) {
            flushProcess()
            out.add(TimelineSegment.Single(item))
        } else if (item is ChatTimelineItem.Tool || item is ChatTimelineItem.Reasoning) {
            pendingProcess.add(item)
        } else if (item is ChatTimelineItem.Assistant &&
            (item.text.length > virtualizeMinChars || item.text.count { it == '\n' } >= maxInlineBlocks)
        ) {
            flushProcess()
            val blocks = parseMarkdownBlocks(item.text)
            if (blocks.size > maxInlineBlocks || (item.text.length > virtualizeMinChars && blocks.size >= virtualizeMinBlocks)) {
                blocks.forEachIndexed { idx, block ->
                    out.add(
                        TimelineSegment.AssistantBlock(
                            key = "${item.id}_b$idx",
                            messageId = item.id,
                            fullText = item.text,
                            timestampMs = item.ts,
                            seq = item.seq,
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
    subagents: List<SubagentSummary> = emptyList(),
    onOpenSubagent: ((SubagentSummary) -> Unit)? = null,
    pendingJumpSeq: Int? = null,
    pendingJumpNonce: Long = 0L,
    highlightedSeq: Int? = null,
    keepHighlightPersistent: Boolean = false,
    onJumpHandled: () -> Unit = {},
    onClearHighlight: () -> Unit = {},
    onJumpToLatest: (() -> Unit)? = null,
) {
    val listState = rememberLazyListState()
    val isUserDragging by listState.interactionSource.collectIsDraggedAsState()
    LaunchedEffect(isUserDragging) {
        if (isUserDragging) {
            onDismissKeyboard()
        }
    }
    val isDesktop = isDesktopPlatform()
    val segments = remember(transcript.items, isDesktop) { groupTimelineSegments(transcript.items, isDesktop) }
    val reversedSegments = remember(segments) { segments.asReversed() }
    val latestErrorNoticeId = remember(transcript.items) {
        transcript.items.lastOrNull { it is ChatTimelineItem.Notice && it.isError }?.id
    }
    val nowMs = remember(sessionKey, transcript.lastSeq) { epochMillis() }
    val showLiveSpinner = activeSession != null &&
        activeSession.id == sessionKey &&
        activeSession.state == SessionState.RUNNING &&
        transcript.pendingPermissions.isEmpty()
    val hasPlanSteps = transcript.planEntries.isNotEmpty()
    val showBottomExecutionWidget = hasPlanSteps || showLiveSpinner
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
    var prevSpinnerVisible by remember(sessionKey) { mutableStateOf(showBottomExecutionWidget) }
    var prevQueuedCount by remember(sessionKey) { mutableStateOf(queuedPrompts.size) }
    var isAutoScrollingToBottom by remember(sessionKey) { mutableStateOf(false) }

    // With `reverseLayout = true`:
    // - Index 0 is ALWAYS the exact pixel bottom (no initial jump or landing mid-message!).
    // - Prepending older history appends items to the END of `reversedSegments` (above viewport),
    //   keeping indices 0..current and ongoing fling velocity 100% untouched (never jumps to top!).
    // - Update previous watermarks BEFORE suspending so rapid streaming deltas never cancel and
    //   replay bottom pins, never fight active user scrolls, and skip no-op scrolls at `(0, 0)`.
    LaunchedEffect(transcript.lastSeq, showBottomExecutionWidget, queuedPrompts.size) {
        val hasNewTail = transcript.lastSeq > prevLastSeq ||
            (showBottomExecutionWidget && !prevSpinnerVisible) ||
            queuedPrompts.size > prevQueuedCount
        prevLastSeq = transcript.lastSeq
        prevSpinnerVisible = showBottomExecutionWidget
        prevQueuedCount = queuedPrompts.size

        if (hasNewTail && !isUserDragging && !listState.isScrollInProgress) {
            val alreadyAtBottom =
                listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset == 0
            val nearBottom =
                listState.firstVisibleItemIndex <= 1 && listState.firstVisibleItemScrollOffset < 96
            if (!alreadyAtBottom && nearBottom) {
                isAutoScrollingToBottom = true
                try {
                    listState.scrollToItem(0)
                } finally {
                    isAutoScrollingToBottom = false
                }
            }
        }
    }

    // Anomaly-only vertical bounce telemetry (zero log noise in normal operation; logs throttled
    // diagnostics if viewport height or scroll anchor rapidly oscillates without user dragging).
    val latestIsDragging by rememberUpdatedState(isUserDragging)
    val latestSegmentCount by rememberUpdatedState(reversedSegments.size)
    val latestSpinnerVisible by rememberUpdatedState(showLiveSpinner)
    LaunchedEffect(sessionKey) {
        var lastViewportH = -1
        var viewportChanges = 0
        var viewportWindowStartMs = 0L
        var lastAnchorKey = -1L
        var lastScrollDir = 0
        var scrollReversals = 0
        var scrollWindowStartMs = 0L
        var lastReportedMs = 0L

        snapshotFlow {
            Triple(
                listState.layoutInfo.viewportSize.height,
                listState.firstVisibleItemIndex,
                listState.firstVisibleItemScrollOffset,
            )
        }
            .distinctUntilChanged()
            .collect { (viewportH, firstIdx, firstOff) ->
                val now = epochMillis()
                if (viewportH > 0) {
                    if (lastViewportH > 0 && viewportH != lastViewportH) {
                        if (now - viewportWindowStartMs > 2_000L) {
                            viewportWindowStartMs = now
                            viewportChanges = 1
                        } else {
                            viewportChanges++
                        }
                        if (viewportChanges >= 4 && now - lastReportedMs >= 5_000L) {
                            lastReportedMs = now
                            logRuntimeEvent(
                                "TIMELINE",
                                "viewport-jitter sid=$sessionKey h=$lastViewportH->$viewportH changes=$viewportChanges idx=$firstIdx off=$firstOff segs=$latestSegmentCount",
                            )
                        }
                    }
                    lastViewportH = viewportH
                }

                val anchorKey = (firstIdx.toLong() shl 20) + firstOff.toLong()
                if (!latestIsDragging && lastAnchorKey >= 0L && anchorKey != lastAnchorKey) {
                    val dir = if (anchorKey > lastAnchorKey) 1 else -1
                    if (lastScrollDir != 0 && dir != lastScrollDir) {
                        if (now - scrollWindowStartMs > 1_500L) {
                            scrollWindowStartMs = now
                            scrollReversals = 1
                        } else {
                            scrollReversals++
                        }
                        if (scrollReversals >= 4 && now - lastReportedMs >= 5_000L) {
                            lastReportedMs = now
                            logRuntimeEvent(
                                "TIMELINE",
                                "scroll-bounce sid=$sessionKey reversals=$scrollReversals idx=$firstIdx off=$firstOff vpH=$viewportH segs=$latestSegmentCount spinner=$latestSpinnerVisible",
                            )
                        }
                    }
                    lastScrollDir = dir
                }
                lastAnchorKey = anchorKey
            }
    }

    // Smoothly trigger reverse pagination when scrolling upward near the oldest loaded item
    val nearOldestEdge by remember(reversedSegments.size) {
        derivedStateOf {
            if (isAutoScrollingToBottom) return@derivedStateOf false
            val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            val scrolledUpFromBottom =
                listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 24
            reversedSegments.isNotEmpty() &&
                listState.isScrollInProgress &&
                (isUserDragging || scrolledUpFromBottom) &&
                lastVisible >= (reversedSegments.size - 3).coerceAtLeast(1)
        }
    }
    LaunchedEffect(nearOldestEdge, transcript.hasMoreBefore, transcript.loadingOlder) {
        if (nearOldestEdge && transcript.hasMoreBefore && !transcript.loadingOlder) {
            onLoadOlder()
        }
    }

    LaunchedEffect(pendingJumpNonce, pendingJumpSeq, reversedSegments.size) {
        val targetSeq = pendingJumpSeq ?: return@LaunchedEffect
        if (reversedSegments.isEmpty()) return@LaunchedEffect
        // Prefer exact seq match (for multi-block Assistant messages, pick the start of the reply which is the highest reversed index);
        // otherwise pick the closest indexed segment so jumps always land right on context.
        val exactIdx = reversedSegments.indexOfLast { it.seq == targetSeq }
        val resolvedIdx = if (exactIdx >= 0) {
            exactIdx
        } else {
            reversedSegments.withIndex()
                .filter { it.value.seq >= 0 }
                .minByOrNull { kotlin.math.abs(it.value.seq - targetSeq) }
                ?.index ?: -1
        }
        if (resolvedIdx >= 0) {
            val headerOffset = (if (queuedPrompts.isNotEmpty()) 1 else 0) + (if (showBottomExecutionWidget) 1 else 0)
            val index = headerOffset + resolvedIdx
            listState.scrollToItem(index)
            // Reverse layout anchors the bottom of an item. Wait for layout if needed, then reveal the start of tall replies.
            var layout = listState.layoutInfo
            if (layout.viewportSize.height <= 0 || layout.visibleItemsInfo.none { it.index == index }) {
                layout = withTimeoutOrNull(300L) {
                    snapshotFlow { listState.layoutInfo }
                        .first { info ->
                            info.viewportSize.height > 0 && info.visibleItemsInfo.any { it.index == index }
                        }
                } ?: listState.layoutInfo
            }
            val itemHeight = layout.visibleItemsInfo.firstOrNull { it.index == index }?.size ?: 0
            val availableHeight = layout.viewportSize.height - layout.beforeContentPadding - layout.afterContentPadding
            if (availableHeight > 0 && itemHeight > availableHeight) {
                listState.scrollToItem(index, itemHeight - availableHeight)
            }
            onJumpHandled()
        }
    }

    LaunchedEffect(highlightedSeq, keepHighlightPersistent) {
        if (highlightedSeq != null && !keepHighlightPersistent) {
            delay(2_400L)
            onClearHighlight()
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
    LazyColumn(
        state = listState,
        reverseLayout = true,
        modifier = Modifier
            .fillMaxSize()
            .then(
                if (!isDesktop) {
                    Modifier.clickable(
                        indication = null,
                        interactionSource = remember { MutableInteractionSource() },
                    ) { onDismissKeyboard() }
                } else {
                    Modifier
                },
            )
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

        if (hasPlanSteps) {
            item(key = "execution_steps_card", contentType = "execution_steps") {
                Box(modifier = Modifier.padding(bottom = if (queuedPrompts.isNotEmpty()) 10.dp else 0.dp)) {
                    ExecutionStepsCard(
                        sessionKey = sessionKey,
                        activeSession = activeSession,
                        entries = transcript.planEntries,
                        isSessionRunning = showLiveSpinner,
                        outputTokens = transcript.outputTokens,
                        onOpenPlanArtifact = onOpenPlanArtifact,
                        onApprovePlan = onApprovePlan,
                    )
                }
            }
        } else if (showLiveSpinner) {
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
                segment === reversedSegments.firstOrNull() && !showBottomExecutionWidget && queuedPrompts.isEmpty() -> 0.dp
                segment.tightBottomSpacing -> 9.dp
                else -> 12.dp
            }
            val isHighlighted = highlightedSeq != null && segment.seq >= 0 && segment.seq == highlightedSeq
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = bottomPad)
                    .then(
                        if (isHighlighted) {
                            Modifier
                                .clip(RoundedCornerShape(10.dp))
                                .background(Everforest.BoldGold.copy(alpha = 0.09f))
                                .border(1.dp, Everforest.BoldGold.copy(alpha = 0.55f), RoundedCornerShape(10.dp))
                                .padding(horizontal = 8.dp, vertical = 6.dp)
                                .testTag("timeline_highlighted_segment_${segment.seq}")
                        } else {
                            Modifier
                        },
                    ),
            ) {
                when (segment) {
                    is TimelineSegment.ProcessTrace -> ClaudeProcessTraceView(
                        steps = segment.steps,
                        isSessionRunning = isSessionRunning,
                    )
                    is TimelineSegment.AssistantBlock -> {
                        if (segment.isLastBlockOfMessage) {
                            Column(modifier = Modifier.fillMaxWidth()) {
                                MarkdownBlockItemView(segment.block, wrapInSelectionContainer = true)
                                MessageTimestampCaption(
                                    prefix = "回复",
                                    timestampMs = segment.timestampMs,
                                    nowMs = nowMs,
                                    alignEnd = false,
                                    tag = "msg_time_assistant_${segment.messageId}",
                                    copyableText = segment.fullText.takeIf { it.isNotBlank() },
                                    copyTag = "msg_copy_assistant_${segment.messageId}",
                                )
                            }
                        } else {
                            MarkdownBlockItemView(segment.block, wrapInSelectionContainer = true)
                        }
                    }
                    is TimelineSegment.Single -> MessageTimelineRow(
                        item = segment.item,
                        nowMs = nowMs,
                        isSessionRunning = isSessionRunning,
                        subagents = subagents,
                        onOpenSubagent = onOpenSubagent,
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
    if (transcript.hasMoreAfter && onJumpToLatest != null) {
        Surface(
            shape = RoundedCornerShape(999.dp),
            color = Everforest.Bg2,
            border = BorderStroke(1.dp, Everforest.BoldGold.copy(alpha = 0.6f)),
            shadowElevation = 6.dp,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 14.dp)
                .clip(RoundedCornerShape(999.dp))
                .clickable(onClick = onJumpToLatest)
                .testTag("timeline_btn_jump_latest"),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(
                    imageVector = Icons.Rounded.ArrowDownward,
                    contentDescription = null,
                    tint = Everforest.BoldGold,
                    modifier = Modifier.size(14.dp),
                )
                Text(
                    text = "回到最新消息",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    color = Everforest.Fg,
                )
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
                    val expandOnTapModifier = if (hasLongContent && !isExpanded) {
                        Modifier.clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) { expanded = true }
                    } else {
                        Modifier
                    }
                    SelectionContainer(modifier = Modifier.weight(1f).then(expandOnTapModifier)) {
                        Text(
                            text = if (prompts.size > 1) "${idx + 1}. $cleanText" else cleanText,
                            fontSize = 13.5.sp,
                            lineHeight = 19.sp,
                            maxLines = if (isExpanded) 12 else 2,
                            overflow = TextOverflow.Ellipsis,
                            color = Everforest.Fg,
                        )
                    }
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

    val rawLive = session.liveStatusText?.replace(Regex("\\s+"), " ")?.trim().orEmpty()
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
            .height(28.dp)
            .padding(horizontal = 2.dp)
            .testTag("live_spinner_row"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = styledLine,
            fontSize = 13.sp,
            lineHeight = 19.sp,
            fontFamily = FontFamily.Monospace,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
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
private fun MessageTimestampCaption(
    prefix: String,
    timestampMs: Long,
    nowMs: Long,
    alignEnd: Boolean,
    tag: String,
    copyableText: String? = null,
    copyTag: String? = null,
) {
    val formatted = remember(timestampMs, nowMs) {
        formatMessageTimestamp(epochMs = timestampMs, nowMs = nowMs)
    }
    val textToCopy = copyableText?.takeIf { it.isNotBlank() }
    val canCopy = textToCopy != null
    if (formatted.isEmpty() && !canCopy) return

    var copies by remember(textToCopy) { mutableIntStateOf(0) }
    val copied = copies != 0
    LaunchedEffect(textToCopy, copies) {
        if (copied) {
            delay(1_600L)
            copies = 0
        }
    }

    val copyButton: @Composable () -> Unit = {
        if (textToCopy != null) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(3.dp),
                modifier = Modifier
                    .clip(RoundedCornerShape(5.dp))
                    .clickable {
                        if (copyTextToClipboard(textToCopy)) {
                            copies++
                        }
                    }
                    .padding(horizontal = 4.dp, vertical = 1.dp)
                    .then(if (copyTag != null) Modifier.testTag(copyTag) else Modifier),
            ) {
                Icon(
                    imageVector = if (copied) Icons.Rounded.Check else Icons.Rounded.ContentCopy,
                    contentDescription = "复制消息",
                    tint = if (copied) Everforest.SoftSage else Everforest.Grey0.copy(alpha = 0.85f),
                    modifier = Modifier.size(11.dp),
                )
                Text(
                    text = if (copied) "已复制" else "复制",
                    fontSize = 10.5.sp,
                    lineHeight = 14.sp,
                    color = if (copied) Everforest.SoftSage else Everforest.Grey0.copy(alpha = 0.85f),
                )
            }
        }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                top = 3.dp,
                start = if (alignEnd) 0.dp else 2.dp,
                end = if (alignEnd) 4.dp else 0.dp,
            ),
        horizontalArrangement = if (alignEnd) Arrangement.spacedBy(8.dp, Alignment.End) else Arrangement.spacedBy(8.dp, Alignment.Start),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (alignEnd) {
            copyButton()
            if (formatted.isNotEmpty()) {
                Text(
                    text = "$prefix $formatted",
                    fontSize = 10.5.sp,
                    lineHeight = 14.sp,
                    fontFamily = FontFamily.Monospace,
                    color = Everforest.Grey0.copy(alpha = 0.85f),
                    modifier = Modifier.testTag(tag),
                )
            }
        } else {
            if (formatted.isNotEmpty()) {
                Text(
                    text = "$prefix $formatted",
                    fontSize = 10.5.sp,
                    lineHeight = 14.sp,
                    fontFamily = FontFamily.Monospace,
                    color = Everforest.Grey0.copy(alpha = 0.85f),
                    modifier = Modifier.testTag(tag),
                )
            }
            copyButton()
        }
    }
}

private val AUTO_IMAGE_PLACEHOLDER_REGEX = Regex("^（已发送\\s*\\d+\\s*张图片）$")

@Composable
private fun MessageTimelineRow(
    item: ChatTimelineItem,
    nowMs: Long,
    isSessionRunning: Boolean = false,
    subagents: List<SubagentSummary> = emptyList(),
    onOpenSubagent: ((SubagentSummary) -> Unit)? = null,
    onInlineRetry: (() -> Unit)? = null,
) {
    when (item) {
        is ChatTimelineItem.User -> {
            var lightboxIdx by remember(item.id) { mutableStateOf<Int?>(null) }
            val isDesktop = isDesktopPlatform()
            val hasPreviews = item.imagePreviews.isNotEmpty()
            val isAutoPlaceholderOnly =
                (hasPreviews || item.imageCount > 0) && AUTO_IMAGE_PLACEHOLDER_REGEX.matches(item.text.trim())
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
                    copyableText = if (!isAutoPlaceholderOnly && item.text.isNotBlank()) item.text else null,
                    copyTag = "msg_copy_user_${item.id}",
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
                    copyableText = item.text.takeIf { it.isNotBlank() },
                    copyTag = "msg_copy_assistant_${item.id}",
                )
            }
        }
        is ChatTimelineItem.Tool -> {
            if (isSubagentDispatchTool(item)) {
                val meta = remember(item.id, item.title, item.input, item.output) { parseSubagentToolMeta(item) }
                val matched = remember(item.id, item.ts, meta, subagents) {
                    matchSubagentForToolCall(item, meta, subagents)
                }
                InlineSubagentDispatchCard(
                    tool = item,
                    matchedSubagent = matched,
                    isSessionRunning = isSessionRunning,
                    onOpenSubagent = onOpenSubagent,
                )
            } else {
                ClaudeCliToolCallRow(item = item, isSessionRunning = isSessionRunning)
            }
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
                    SelectionContainer(modifier = Modifier.weight(1f)) {
                        Text(
                            text = item.text,
                            fontSize = 12.sp,
                            lineHeight = 17.sp,
                            color = if (item.isError) Everforest.Red else Everforest.Fg,
                        )
                    }
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
    val lines = remember(permission.title) { permission.title.lines().dropWhile { it.isBlank() } }
    val headerTitle = lines.firstOrNull()?.trim() ?: "需要执行审批"
    val cmdPreview = remember(lines) {
        lines.drop(1)
            .dropWhile { it.isBlank() }
            .dropLastWhile { it.isBlank() }
            .joinToString("\n")
            .takeIf { it.isNotBlank() }
    }

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
                    SelectionContainer {
                        Text(
                            text = cmdPreview,
                            fontSize = 12.5.sp,
                            lineHeight = 18.sp,
                            color = Everforest.Fg,
                        )
                    }
                } else {
                    val highlightedCmd = remember(cmdPreview) { highlightEverforestCode(cmdPreview, "zsh") }
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = Everforest.BgDim,
                        border = BorderStroke(1.dp, Everforest.Bg3),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        SelectionContainer {
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
            }
            if (!errorText.isNullOrBlank()) {
                SelectionContainer {
                    Text(
                        text = errorText,
                        fontSize = 12.sp,
                        lineHeight = 17.sp,
                        color = Everforest.Red,
                    )
                }
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

