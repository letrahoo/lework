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
    onDismissKeyboard: () -> Unit = {},
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
            .testTag("conversation_lazy_list"),
        contentPadding = PaddingValues(vertical = 12.dp),
        verticalArrangement = Arrangement.Bottom,
    ) {
        if (queuedPrompts.isNotEmpty()) {
            item(key = "queued_prompts_card", contentType = "queued_prompts") {
                QueuedPromptsCard(prompts = queuedPrompts)
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
                    is TimelineSegment.AssistantBlock -> MarkdownBlockItemView(segment.block)
                    is TimelineSegment.Single -> MessageTimelineRow(segment.item)
                }
            }
        }

        if (transcript.planEntries.isNotEmpty()) {
            item(key = "plan_card", contentType = "plan_card") {
                Box(modifier = Modifier.padding(bottom = 12.dp)) {
                    PlanCard(entries = transcript.planEntries)
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
private fun QueuedPromptsCard(prompts: List<String>) {
    var expanded by remember(prompts) { mutableStateOf(false) }
    val hasLongContent = prompts.size > 2 || prompts.any { it.length > 90 || it.contains('\n') }
    val displayed = if (expanded) prompts else prompts.take(2)

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = Everforest.Bg1,
        border = BorderStroke(1.dp, Everforest.Bg3),
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (hasLongContent) {
                    Modifier.clip(RoundedCornerShape(12.dp)).clickable { expanded = !expanded }
                } else {
                    Modifier
                },
            ),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp),
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
                if (hasLongContent) {
                    Text(
                        text = if (expanded) "收起" else "展开",
                        fontSize = 11.sp,
                        color = Everforest.CommentGrey,
                    )
                }
            }
            displayed.forEachIndexed { idx, prompt ->
                val cleanText = if (expanded) prompt else prompt.replace('\n', ' ')
                Text(
                    text = if (prompts.size > 1) "${idx + 1}. $cleanText" else cleanText,
                    fontSize = 13.5.sp,
                    lineHeight = 19.sp,
                    maxLines = if (expanded) 12 else 2,
                    overflow = TextOverflow.Ellipsis,
                    color = Everforest.Fg,
                )
            }
            if (!expanded && prompts.size > displayed.size) {
                Text(
                    text = "还有 ${prompts.size - displayed.size} 条待执行…",
                    fontSize = 11.5.sp,
                    color = Everforest.CommentGrey,
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
private fun PlanCard(entries: List<PlanEntry>) {
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
private fun MessageTimelineRow(item: ChatTimelineItem) {
    when (item) {
        is ChatTimelineItem.User -> {
            var lightboxIdx by remember(item.id) { mutableStateOf<Int?>(null) }
            val hasPreviews = item.imagePreviews.isNotEmpty()
            val isAutoPlaceholderOnly =
                hasPreviews && Regex("^（已发送\\s*\\d+\\s*张图片）$").matches(item.text.trim())

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                Surface(
                    color = Everforest.Bg2,
                    shape = RoundedCornerShape(18.dp, 18.dp, 4.dp, 18.dp),
                    border = BorderStroke(1.dp, Everforest.Bg3),
                    modifier = Modifier.widthIn(max = 316.dp),
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
                                        Modifier.size(width = 172.dp, height = 124.dp)
                                    } else {
                                        Modifier.size(82.dp)
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
            EverforestMarkdownContent(
                markdown = item.text,
                modifier = Modifier.padding(vertical = 2.dp),
            )
        }
        is ChatTimelineItem.Tool -> {
            ClaudeCliToolCallRow(item = item)
        }
        is ChatTimelineItem.Notice -> {
            Surface(
                color = if (item.isError) Everforest.BgRed else Everforest.Bg1,
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier.padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Info,
                        contentDescription = null,
                        tint = if (item.isError) Everforest.Red else Everforest.Grey2,
                        modifier = Modifier.size(16.dp),
                    )
                    Text(
                        text = item.text,
                        fontSize = 12.sp,
                        color = if (item.isError) Everforest.Red else Everforest.Fg,
                    )
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

