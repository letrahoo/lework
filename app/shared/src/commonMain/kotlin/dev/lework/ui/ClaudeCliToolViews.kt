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
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.lework.platform.copyTextToClipboard
import dev.lework.protocol.ToolKind
import dev.lework.protocol.ToolStatus
import dev.lework.store.ChatTimelineItem
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive

internal enum class CliToolCategory {
    BASH,
    SKILL,
    READ,
    EDIT,
    SEARCH,
    TASK,
    FETCH,
    OTHER,
}

internal data class ParsedCliTool(
    val category: CliToolCategory,
    val badge: String,
    val badgeColor: Color,
    /** Human-friendly concise label shown in collapsed trace & 1-line row */
    val conciseLabel: String,
    /** Short token used inside parenthetical summaries */
    val microTag: String,
    val description: String?,
    val fullCommandOrDetail: String?,
    val outputLineCount: Int,
)

internal sealed interface CondensedStep {
    val key: String

    data class Thought(
        override val key: String,
        val combinedText: String,
    ) : CondensedStep

    data class SingleTool(
        override val key: String,
        val tool: ChatTimelineItem.Tool,
        val parsed: ParsedCliTool,
    ) : CondensedStep

    data class ReadCluster(
        override val key: String,
        val entries: List<Pair<ChatTimelineItem.Tool, ParsedCliTool>>,
    ) : CondensedStep
}

private val lenientJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
}

/**
 * Unified Progressive-Disclosure Process Trace:
 * - Level 0 (Default): Folds ALL interleaved Thinking + Skill + Bash + Read/Edit steps of a turn
 *   into ONE single quiet, high-signal line surfacing key facts (Skills used, files modified, Bash intents).
 * - Level 1 (Tap): Unfolds a compact 1-line-per-step trace.
 * - Level 2 (Tap a specific Bash/Skill row): Unfolds the Zsh-highlighted command & scrollable stdout.
 */
@Composable
internal fun ClaudeProcessTraceView(
    steps: List<ChatTimelineItem>,
    isSessionRunning: Boolean = false,
) {
    if (steps.isEmpty()) return

    var traceExpanded by remember { mutableStateOf(false) }
    var showAllExpandedSteps by remember { mutableStateOf(false) }

    val toolsWithParsed = remember(steps) {
        steps.mapNotNull { item ->
            (item as? ChatTimelineItem.Tool)?.let { it to parseCliTool(it) }
        }
    }
    val activeTool = if (isSessionRunning) {
        toolsWithParsed.lastOrNull {
            it.first.status == ToolStatus.IN_PROGRESS || it.first.status == ToolStatus.PENDING
        }
    } else {
        null
    }
    val hasFailure = toolsWithParsed.any { it.first.status == ToolStatus.FAILED }
    val headline = remember(steps, isSessionRunning) {
        buildProcessHeadline(steps, toolsWithParsed, activeTool, hasFailure)
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        // Level 0: Single-Line High-Signal Summary Pill
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 26.dp)
                .clip(RoundedCornerShape(8.dp))
                .clickable { traceExpanded = !traceExpanded }
                .padding(vertical = 4.dp, horizontal = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            if (activeTool != null) {
                Box(
                    modifier = Modifier
                        .size(7.dp)
                        .clip(CircleShape)
                        .background(Everforest.SoftSand),
                )
            } else {
                Icon(
                    imageVector = if (traceExpanded) {
                        Icons.Rounded.KeyboardArrowDown
                    } else {
                        Icons.AutoMirrored.Rounded.KeyboardArrowRight
                    },
                    contentDescription = if (traceExpanded) "收起执行过程" else "展开执行过程",
                    tint = if (hasFailure) Everforest.Red else Everforest.CommentGrey,
                    modifier = Modifier.size(15.dp),
                )
            }

            Text(
                text = headline,
                fontSize = 12.5.sp,
                lineHeight = 18.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )

            if (activeTool != null) {
                Icon(
                    imageVector = if (traceExpanded) {
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

        // Level 1: Compact 1-Line-per-Step Expanded Trace (lazily computed ONLY when expanded!)
        AnimatedVisibility(visible = traceExpanded) {
            val condensed = remember(steps) { condenseTraceSteps(steps) }
            val hiddenCount = if (!showAllExpandedSteps && condensed.size > 36) condensed.size - 30 else 0
            val visibleSteps = if (hiddenCount > 0) condensed.takeLast(30) else condensed

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 7.dp, top = 3.dp, bottom = 4.dp)
                    .height(IntrinsicSize.Min),
            ) {
                Box(
                    modifier = Modifier
                        .width(1.5.dp)
                        .fillMaxHeight()
                        .background(Everforest.Bg3),
                )
                Spacer(Modifier.width(10.dp))

                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    if (hiddenCount > 0) {
                        Text(
                            text = "▴ 展开较早的 $hiddenCount 步执行记录",
                            fontSize = 11.5.sp,
                            color = Everforest.CommentGrey,
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .clickable { showAllExpandedSteps = true }
                                .padding(vertical = 2.dp),
                        )
                    }
                    for (step in visibleSteps) {
                        key(step.key) {
                            when (step) {
                                is CondensedStep.Thought -> CompactThoughtStepRow(step.combinedText)
                                is CondensedStep.ReadCluster -> CompactReadClusterRow(step.entries)
                                is CondensedStep.SingleTool -> ClaudeCliToolCallRow(
                                    item = step.tool,
                                    parsed = step.parsed,
                                    isSessionRunning = isSessionRunning,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

internal fun condenseTraceSteps(steps: List<ChatTimelineItem>): List<CondensedStep> {
    val out = mutableListOf<CondensedStep>()
    val thoughtBuf = mutableListOf<ChatTimelineItem.Reasoning>()
    val readBuf = mutableListOf<Pair<ChatTimelineItem.Tool, ParsedCliTool>>()

    fun flushThoughts() {
        if (thoughtBuf.isNotEmpty()) {
            val joined = thoughtBuf.joinToString("\n\n") { it.text.trim() }.trim()
            if (joined.isNotEmpty()) {
                out.add(CondensedStep.Thought(key = "th_${thoughtBuf.first().id}", combinedText = joined))
            }
            thoughtBuf.clear()
        }
    }

    fun flushReads() {
        if (readBuf.isNotEmpty()) {
            if (readBuf.size == 1) {
                val (t, p) = readBuf.first()
                out.add(CondensedStep.SingleTool(key = t.id, tool = t, parsed = p))
            } else {
                out.add(CondensedStep.ReadCluster(key = "rc_${readBuf.first().first.id}", entries = readBuf.toList()))
            }
            readBuf.clear()
        }
    }

    for (s in steps) {
        when (s) {
            is ChatTimelineItem.Reasoning -> {
                flushReads()
                thoughtBuf.add(s)
            }
            is ChatTimelineItem.Tool -> {
                flushThoughts()
                val p = parseCliTool(s)
                val isQuietRead = (p.category == CliToolCategory.READ || p.category == CliToolCategory.SEARCH) &&
                    s.status == ToolStatus.COMPLETED
                if (isQuietRead) {
                    readBuf.add(s to p)
                } else {
                    flushReads()
                    out.add(CondensedStep.SingleTool(key = s.id, tool = s, parsed = p))
                }
            }
            else -> Unit
        }
    }
    flushThoughts()
    flushReads()
    return out
}

internal fun buildProcessHeadline(
    steps: List<ChatTimelineItem>,
    tools: List<Pair<ChatTimelineItem.Tool, ParsedCliTool>>,
    activeTool: Pair<ChatTimelineItem.Tool, ParsedCliTool>?,
    hasFailure: Boolean,
): AnnotatedString = buildAnnotatedString {
    // Active live state
    if (activeTool != null) {
        val p = activeTool.second
        withStyle(SpanStyle(color = Everforest.SoftSand, fontWeight = FontWeight.Medium)) {
            when (p.category) {
                CliToolCategory.SKILL -> append("正在加载技能 ${p.conciseLabel}")
                CliToolCategory.BASH -> append("正在执行 · ${p.conciseLabel}")
                CliToolCategory.EDIT -> append("正在修改 ${p.conciseLabel}")
                CliToolCategory.TASK -> append("正在调度子任务 · ${p.conciseLabel}")
                else -> append("正在处理 · ${p.conciseLabel}")
            }
        }
        if (tools.size > 1) {
            withStyle(SpanStyle(color = Everforest.FaintGrey)) {
                append(" · 第 ${tools.size} 步")
            }
        }
        return@buildAnnotatedString
    }

    // Thinking-only trace
    if (tools.isEmpty()) {
        val lastLine = steps
            .filterIsInstance<ChatTimelineItem.Reasoning>()
            .lastOrNull()
            ?.text
            ?.lineSequence()
            ?.map { it.trim() }
            ?.lastOrNull { it.isNotEmpty() }
            ?.let { oneLine(it, 42) }
        withStyle(SpanStyle(color = Everforest.CommentGrey, fontStyle = FontStyle.Italic)) {
            append("思考过程")
        }
        if (!lastLine.isNullOrBlank()) {
            withStyle(SpanStyle(color = Everforest.FaintGrey, fontStyle = FontStyle.Italic)) {
                append(" · $lastLine")
            }
        }
        return@buildAnnotatedString
    }

    val skills = tools.filter { it.second.category == CliToolCategory.SKILL }.map { it.second.microTag }.distinct()
    val edits = tools.filter { it.second.category == CliToolCategory.EDIT }.map { it.second.microTag }.distinct()
    val bashes = tools.filter { it.second.category == CliToolCategory.BASH }.map { it.second }
    val readsCount = tools.count { it.second.category == CliToolCategory.READ || it.second.category == CliToolCategory.SEARCH }
    val tasks = tools.filter { it.second.category == CliToolCategory.TASK }.map { it.second }

    var hasPart = false
    fun sep() {
        if (hasPart) {
            withStyle(SpanStyle(color = Everforest.Bg4)) { append(" · ") }
        }
        hasPart = true
    }

    // 1. Always surface Skill names cleanly without loud rainbow colors
    if (skills.isNotEmpty()) {
        sep()
        withStyle(SpanStyle(color = Everforest.CommentGrey)) {
            append("Skill ")
        }
        withStyle(SpanStyle(color = Everforest.Fg, fontWeight = FontWeight.Medium)) {
            append("${skills.take(2).joinToString(", ")}${if (skills.size > 2) "…" else ""}")
        }
    }

    // 2. Surface modified files
    if (edits.isNotEmpty()) {
        sep()
        withStyle(SpanStyle(color = Everforest.CommentGrey)) {
            if (edits.size == 1) {
                append("修改 ")
                withStyle(SpanStyle(color = Everforest.Fg)) { append(edits.first()) }
            } else {
                append("修改 ")
                withStyle(SpanStyle(color = Everforest.Fg)) { append(edits.first()) }
                append(" 等 ${edits.size} 处")
            }
        }
    }

    // 3. Surface Bash executions with concise semantic intent (restrained CLI tone)
    if (bashes.isNotEmpty()) {
        sep()
        withStyle(SpanStyle(color = Everforest.CommentGrey)) {
            append(if (bashes.size == 1) "终端" else "终端×${bashes.size}")
        }
        val intents = bashes.map { it.microTag }.distinct().take(2).joinToString(", ")
        if (intents.isNotEmpty()) {
            withStyle(SpanStyle(color = Everforest.FaintGrey)) {
                append(" ($intents${if (bashes.size > 2) "…" else ""})")
            }
        }
    }

    // 4. Subagent tasks or quiet file reads
    if (tasks.isNotEmpty()) {
        sep()
        withStyle(SpanStyle(color = Everforest.CommentGrey)) {
            append(if (tasks.size == 1) "子任务" else "子任务×${tasks.size}")
        }
        val roles = tasks
            .map { it.microTag }
            .filter { it.isNotBlank() && it != "子任务" }
            .distinct()
        if (roles.isNotEmpty()) {
            withStyle(SpanStyle(color = Everforest.FaintGrey)) {
                append(" (${roles.take(2).joinToString(", ")}${if (roles.size > 2) "…" else ""})")
            }
        }
    }
    if (readsCount > 0 && (skills.isEmpty() || edits.isEmpty())) {
        sep()
        withStyle(SpanStyle(color = Everforest.FaintGrey)) {
            append("查阅 $readsCount 处")
        }
    }

    if (!hasPart) {
        withStyle(SpanStyle(color = Everforest.FaintGrey)) {
            append("已完成 ${tools.size} 步操作")
        }
    }

    if (hasFailure) {
        sep()
        withStyle(SpanStyle(color = Everforest.Red, fontWeight = FontWeight.SemiBold)) {
            append("含报错")
        }
    }
}

@Composable
private fun CompactThoughtStepRow(text: String) {
    var expanded by remember { mutableStateOf(false) }
    val preview = remember(text) {
        text.lineSequence().map { it.trim() }.lastOrNull { it.isNotEmpty() }.orEmpty().ifEmpty { "思考摘要" }
    }
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(5.dp))
                .clickable { expanded = !expanded }
                .padding(vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(5.dp)
                    .clip(CircleShape)
                    .background(Everforest.CommentGrey),
            )
            Text(
                text = buildAnnotatedString {
                    withStyle(SpanStyle(color = Everforest.CommentGrey, fontStyle = FontStyle.Italic, fontWeight = FontWeight.Medium)) {
                        append("Thinking")
                    }
                    if (!expanded) {
                        withStyle(SpanStyle(color = Everforest.FaintGrey, fontStyle = FontStyle.Italic)) {
                            append(" · ${oneLine(preview, 44)}")
                        }
                    }
                },
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        }
        AnimatedVisibility(visible = expanded) {
            SelectionContainer {
                Text(
                    text = text,
                    fontSize = 12.5.sp,
                    lineHeight = 19.sp,
                    fontStyle = FontStyle.Italic,
                    color = Everforest.CommentGrey,
                    modifier = Modifier.padding(start = 11.dp, top = 3.dp, bottom = 4.dp),
                )
            }
        }
    }
}

@Composable
private fun CompactReadClusterRow(entries: List<Pair<ChatTimelineItem.Tool, ParsedCliTool>>) {
    var expanded by remember { mutableStateOf(false) }
    val names = remember(entries) { entries.map { it.second.microTag }.filter { it.isNotEmpty() }.distinct() }
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(5.dp))
                .clickable { expanded = !expanded }
                .padding(vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(5.dp)
                    .clip(CircleShape)
                    .background(Everforest.CommentGrey),
            )
            Text(
                text = buildAnnotatedString {
                    withStyle(SpanStyle(color = Everforest.Fg, fontWeight = FontWeight.SemiBold, fontFamily = FontFamily.Monospace)) {
                        append("Read")
                    }
                    withStyle(SpanStyle(color = Everforest.FaintGrey)) {
                        val preview = names.take(2).joinToString(", ")
                        if (preview.isNotEmpty()) {
                            append("($preview${if (entries.size > 2) " +${entries.size - 2}" else ""})")
                        } else {
                            append("(${entries.size} files)")
                        }
                    }
                },
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        }
        AnimatedVisibility(visible = expanded) {
            Column(
                modifier = Modifier.padding(start = 11.dp, top = 2.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                for ((tool, parsed) in entries) {
                    ClaudeCliToolCallRow(item = tool, parsed = parsed)
                }
            }
        }
    }
}

/**
 * Single 1-Line Compact Tool Row (inside expanded trace):
 *   ● Bash · 编译并签名 iOS 应用                     24 行 ▸
 * Tap to unfold Zsh-highlighted `$ command` + scrollable output.
 */
@Composable
internal fun ClaudeCliToolCallRow(
    item: ChatTimelineItem.Tool,
    parsed: ParsedCliTool = remember(item) { parseCliTool(item) },
    isSessionRunning: Boolean = false,
) {
    var expanded by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    var copied by remember { mutableStateOf(false) }

    val dotColor = when (item.status) {
        ToolStatus.COMPLETED -> Everforest.CommentGrey
        ToolStatus.FAILED -> Everforest.SoftRed
        ToolStatus.IN_PROGRESS, ToolStatus.PENDING ->
            if (isSessionRunning) Everforest.SoftSand else Everforest.CommentGrey
    }
    val hasDetail = !parsed.fullCommandOrDetail.isNullOrBlank() || !item.output.isNullOrBlank()

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(5.dp))
                .clickable(enabled = hasDetail) { expanded = !expanded }
                .padding(vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(5.5.dp)
                    .clip(CircleShape)
                    .background(dotColor),
            )

            Text(
                text = buildAnnotatedString {
                    withStyle(
                        SpanStyle(
                            color = Everforest.Fg,
                            fontWeight = FontWeight.SemiBold,
                            fontFamily = FontFamily.Monospace,
                        ),
                    ) {
                        append(parsed.badge)
                    }
                    if (parsed.conciseLabel.isNotEmpty()) {
                        withStyle(SpanStyle(color = Everforest.CommentGrey)) {
                            append("(${parsed.conciseLabel})")
                        }
                    }
                },
                fontSize = 12.sp,
                lineHeight = 17.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )

            if (parsed.outputLineCount > 1 && !expanded) {
                Text(
                    text = "${parsed.outputLineCount}行",
                    fontSize = 10.5.sp,
                    fontFamily = FontFamily.Monospace,
                    color = Everforest.FaintGrey,
                )
            }

            if (hasDetail) {
                Icon(
                    imageVector = if (expanded) {
                        Icons.Rounded.KeyboardArrowDown
                    } else {
                        Icons.AutoMirrored.Rounded.KeyboardArrowRight
                    },
                    contentDescription = null,
                    tint = Everforest.CommentGrey,
                    modifier = Modifier.size(14.dp),
                )
            }
        }

        // Level 2: Terminal Detail Panel (only when user taps this specific Bash / Skill row)
        AnimatedVisibility(visible = expanded && hasDetail) {
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = Everforest.BgDim,
                border = BorderStroke(1.dp, Everforest.Bg3),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 10.dp, top = 4.dp, bottom = 4.dp),
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    val headerText = parsed.fullCommandOrDetail
                    if (!headerText.isNullOrBlank()) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(Everforest.Bg1)
                                .padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalArrangement = Arrangement.spacedBy(3.dp),
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = when (parsed.category) {
                                        CliToolCategory.BASH -> parsed.description?.let { "# $it" } ?: "Shell"
                                        CliToolCategory.SKILL -> "Skill · ${parsed.conciseLabel}"
                                        else -> parsed.badge
                                    },
                                    fontSize = 10.5.sp,
                                    fontFamily = FontFamily.Monospace,
                                    fontStyle = FontStyle.Italic,
                                    color = Everforest.CommentGrey,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f),
                                )

                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(3.dp),
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(4.dp))
                                        .clickable {
                                            val payload = buildString {
                                                append(headerText)
                                                if (!item.output.isNullOrBlank()) {
                                                    append("\n\n")
                                                    append(item.output)
                                                }
                                            }
                                            copyTextToClipboard(payload)
                                            copied = true
                                            scope.launch {
                                                delay(1_500L)
                                                copied = false
                                            }
                                        }
                                        .padding(horizontal = 4.dp, vertical = 1.dp),
                                ) {
                                    Icon(
                                        imageVector = if (copied) Icons.Rounded.Check else Icons.Rounded.ContentCopy,
                                        contentDescription = "复制",
                                        tint = if (copied) Everforest.Green else Everforest.CommentGrey,
                                        modifier = Modifier.size(11.5.dp),
                                    )
                                    Text(
                                        text = if (copied) "已复制" else "复制",
                                        fontSize = 10.sp,
                                        color = if (copied) Everforest.Green else Everforest.CommentGrey,
                                    )
                                }
                            }

                            SelectionContainer {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .horizontalScroll(rememberScrollState()),
                                ) {
                                    if (parsed.category == CliToolCategory.BASH) {
                                        val zshStyled = remember(headerText) {
                                            highlightShellZsh("$ $headerText")
                                        }
                                        Text(
                                            text = zshStyled,
                                            fontSize = 11.5.sp,
                                            lineHeight = 17.sp,
                                            fontFamily = FontFamily.Monospace,
                                        )
                                    } else if (
                                        parsed.category == CliToolCategory.EDIT &&
                                        (headerText.startsWith("--- ") || headerText.startsWith("*** ") || headerText.contains("\n@@ "))
                                    ) {
                                        val diffStyled = remember(headerText) {
                                            highlightEverforestCode(headerText, "diff")
                                        }
                                        Text(
                                            text = diffStyled,
                                            fontSize = 11.sp,
                                            lineHeight = 16.5.sp,
                                            fontFamily = FontFamily.Monospace,
                                        )
                                    } else {
                                        Text(
                                            text = headerText,
                                            fontSize = 11.5.sp,
                                            lineHeight = 17.sp,
                                            fontFamily = FontFamily.Monospace,
                                            color = Everforest.Fg,
                                        )
                                    }
                                }
                            }
                        }
                    }

                    val out = cleanToolOutput(item.output)
                    if (out.isNotEmpty()) {
                        if (!headerText.isNullOrBlank()) {
                            HorizontalDivider(color = Everforest.Bg2, thickness = 1.dp)
                        }
                        SelectionContainer {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(max = 220.dp)
                                    .verticalScroll(rememberScrollState())
                                    .horizontalScroll(rememberScrollState())
                                    .padding(horizontal = 10.dp, vertical = 7.dp),
                            ) {
                                val outputStyled = remember(out, item.status) {
                                    if (item.status == ToolStatus.FAILED) {
                                        buildAnnotatedString {
                                            withStyle(SpanStyle(color = Everforest.Red)) { append(out) }
                                        }
                                    } else if (out.startsWith("diff ") || out.startsWith("--- ") || out.contains("\n@@ ")) {
                                        highlightEverforestCode(out, "diff")
                                    } else {
                                        buildAnnotatedString {
                                            withStyle(SpanStyle(color = Everforest.Fg)) { append(out) }
                                        }
                                    }
                                }
                                Text(
                                    text = outputStyled,
                                    fontSize = 11.sp,
                                    lineHeight = 16.5.sp,
                                    fontFamily = FontFamily.Monospace,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Semantic Tool Call Parser (Memoized O(1) Lookup + Human Intent over Raw CLI Noise)
// ---------------------------------------------------------------------------

private const val TOOL_CACHE_MASK = 1023
private val toolParseCache = arrayOfNulls<CachedToolParse>(TOOL_CACHE_MASK + 1)

private data class CachedToolParse(
    val id: String,
    val status: ToolStatus,
    val title: String,
    val inputHash: Int,
    val outputLen: Int,
    val parsed: ParsedCliTool,
)

internal fun parseCliTool(item: ChatTimelineItem.Tool): ParsedCliTool {
    val inHash = item.input?.hashCode() ?: 0
    val outLen = item.output?.length ?: 0
    val slot = ((item.id.hashCode() * 31 + item.status.ordinal) xor inHash xor outLen) and TOOL_CACHE_MASK
    val hit = toolParseCache[slot]
    if (
        hit != null &&
        hit.id == item.id &&
        hit.status == item.status &&
        hit.title == item.title &&
        hit.inputHash == inHash &&
        hit.outputLen == outLen
    ) {
        return hit.parsed
    }
    val computed = parseCliToolUncached(item)
    toolParseCache[slot] = CachedToolParse(
        id = item.id,
        status = item.status,
        title = item.title,
        inputHash = inHash,
        outputLen = outLen,
        parsed = computed,
    )
    return computed
}

private fun parseCliToolUncached(item: ChatTimelineItem.Tool): ParsedCliTool {
    val rawTitle = item.title.trim().removePrefix("default_api:")
    val lowerTitle = rawTitle.lowercase()
    val jsonObj = parseInputJsonObject(item.input)
    val cleanedOut = cleanToolOutput(item.output)
    val outLines = if (cleanedOut.isEmpty()) 0 else cleanedOut.lineSequence().count { it.isNotBlank() }

    // 1. Skill
    val explicitSkill = jsonObj?.str("skill") ?: jsonObj?.str("skill_name")
    val pathField = jsonObj?.str("file_path") ?: jsonObj?.str("path") ?: jsonObj?.str("Path")
    val skillFromPath = extractSkillNameFromPath(pathField ?: item.input.orEmpty())

    if (
        lowerTitle == "skill" ||
        lowerTitle.startsWith("skill:") ||
        lowerTitle.startsWith("use skill") ||
        explicitSkill != null ||
        skillFromPath != null
    ) {
        val skillName = (explicitSkill ?: skillFromPath ?: rawTitle.removePrefix("Skill:").trim())
            .ifEmpty { "skill" }
        val shortSkill = skillName.substringAfter(':')
        val skillArgs = jsonObj?.str("args") ?: jsonObj?.str("prompt")
        val detail = buildString {
            append("skill: $skillName")
            if (!skillArgs.isNullOrBlank()) append("\nargs: $skillArgs")
            if (!pathField.isNullOrBlank()) append("\npath: $pathField")
        }
        return ParsedCliTool(
            category = CliToolCategory.SKILL,
            badge = "Skill",
            badgeColor = Everforest.Purple,
            conciseLabel = skillName,
            microTag = shortSkill,
            description = skillArgs,
            fullCommandOrDetail = detail,
            outputLineCount = outLines,
        )
    }

    // 2. Bash / Terminal Execution — prefer human `description`/`toolSummary` over raw CLI flags!
    val cmdFromJson = jsonObj?.extractCommandString()
    val isBash = item.kind == ToolKind.EXECUTE ||
        lowerTitle in setOf("bash", "run_command", "exec_command", "shell", "shell_command", "terminal", "codex command") ||
        rawTitle.startsWith("`") ||
        cmdFromJson != null

    if (isBash) {
        val rawCmd = cmdFromJson
            ?: rawTitle.removeSurrounding("`").takeIf {
                it.isNotBlank() && it.lowercase() !in setOf("bash", "terminal", "codex command", "run_command")
            }
            ?: item.input?.trim().orEmpty()
        val cleanCmd = unwrapShellCommand(rawCmd)
        val desc = (jsonObj?.str("description") ?: jsonObj?.str("toolSummary"))
            ?.trim()
            ?.removeSurrounding("\"")
            ?.takeIf { it.isNotBlank() }

        val concise = desc ?: summarizeCommandExecutable(cleanCmd)
        val micro = desc?.let { oneLine(it, 18) } ?: summarizeCommandExecutable(cleanCmd, maxChars = 16)

        return ParsedCliTool(
            category = CliToolCategory.BASH,
            badge = "Bash",
            badgeColor = Everforest.Green,
            conciseLabel = oneLine(concise, 46),
            microTag = micro,
            description = desc,
            fullCommandOrDetail = cleanCmd.ifEmpty { item.input },
            outputLineCount = outLines,
        )
    }

    // 3. Read file
    if (item.kind == ToolKind.READ || lowerTitle == "read" || lowerTitle == "read_file" || lowerTitle.startsWith("read ")) {
        val p = pathField ?: rawTitle.removePrefix("Read ").removePrefix("read ").trim()
        val base = fileNameOnly(p)
        return ParsedCliTool(
            category = CliToolCategory.READ,
            badge = "Read",
            badgeColor = Everforest.Blue,
            conciseLabel = compactPath(p),
            microTag = base,
            description = null,
            fullCommandOrDetail = p.ifBlank { item.input },
            outputLineCount = outLines,
        )
    }

    // 4. Edit / Write file — synthesize a readable unified diff from (old_string/new_string, Target/Replacement, Content, or patch)
    if (
        item.kind == ToolKind.EDIT ||
        lowerTitle in setOf("update", "edit", "multiedit", "write", "edit_file", "write_file", "apply_patch", "codex file patch")
    ) {
        val badge = if (lowerTitle.contains("write")) "Write" else "Update"
        val p = pathField ?: extractPatchPath(item.input) ?: rawTitle
        val synthesized = synthesizeEditToolDiff(p, jsonObj, item.input)
        val statDesc = synthesized?.second
        val detailBody = synthesized?.first ?: p.ifBlank { item.input }
        val concise = if (statDesc != null) "${compactPath(p)} ($statDesc)" else compactPath(p)
        return ParsedCliTool(
            category = CliToolCategory.EDIT,
            badge = badge,
            badgeColor = Everforest.BoldGold,
            conciseLabel = concise,
            microTag = fileNameOnly(p),
            description = statDesc,
            fullCommandOrDetail = detailBody,
            outputLineCount = outLines,
        )
    }

    // 5. Search / Grep / Glob
    if (
        item.kind == ToolKind.SEARCH ||
        lowerTitle in setOf("grep", "glob", "search", "find_by_name", "grep_search")
    ) {
        val q = jsonObj?.str("pattern") ?: jsonObj?.str("Query") ?: jsonObj?.str("Pattern") ?: pathField.orEmpty()
        return ParsedCliTool(
            category = CliToolCategory.SEARCH,
            badge = "Search",
            badgeColor = Everforest.Aqua,
            conciseLabel = oneLine(q.ifEmpty { rawTitle }, 42),
            microTag = oneLine(q.ifEmpty { "检索" }, 14),
            description = null,
            fullCommandOrDetail = item.input,
            outputLineCount = outLines,
        )
    }

    // 6. Task / Subagent
    if (lowerTitle in setOf("task", "agent", "invoke_subagent")) {
        val role = jsonObj?.str("Role")
            ?: jsonObj?.str("description")
            ?: jsonObj?.str("name")
            ?: jsonObj?.str("subagent_type")
            ?: "子任务"
        return ParsedCliTool(
            category = CliToolCategory.TASK,
            badge = "Task",
            badgeColor = Everforest.Orange,
            conciseLabel = oneLine(role, 42),
            microTag = oneLine(role, 22),
            description = role,
            fullCommandOrDetail = jsonObj?.str("prompt") ?: jsonObj?.str("Prompt") ?: item.input,
            outputLineCount = outLines,
        )
    }

    // 7. Generic Tool
    return ParsedCliTool(
        category = CliToolCategory.OTHER,
        badge = rawTitle.ifEmpty { "Tool" },
        badgeColor = Everforest.Aqua,
        conciseLabel = pathField?.let { compactPath(it) }.orEmpty(),
        microTag = rawTitle.take(14),
        description = null,
        fullCommandOrDetail = item.input,
        outputLineCount = outLines,
    )
}

private fun summarizeCommandExecutable(cmd: String, maxChars: Int = 34): String {
    val firstSegment = cmd.trim().substringBefore('\n').substringBefore(';').substringBefore("&&").trim()
    if (firstSegment.isEmpty()) return "command"
    val tokens = firstSegment.split(Regex("\\s+")).filter {
        it.isNotEmpty() && !it.contains('=') && it !in setOf("sudo", "env", "nohup", "time")
    }
    if (tokens.isEmpty()) return oneLine(firstSegment, maxChars)
    val bin = tokens[0].substringAfterLast('/')
    val sub = tokens.getOrNull(1)?.takeIf { !it.startsWith("-") && !it.startsWith("/") }
    val short = if (sub != null) "$bin $sub" else bin
    return oneLine(short, maxChars)
}

private fun cleanToolOutput(raw: String?): String {
    if (raw.isNullOrBlank()) return ""
    return raw
        .lineSequence()
        .filterNot {
            val t = it.trim()
            t.startsWith("Created At:") ||
                t.startsWith("Completed At:") ||
                t == "Command output:" ||
                t == "Command exited with code 0."
        }
        .joinToString("\n")
        .trim()
}

private fun parseInputJsonObject(raw: String?): JsonObject? {
    if (raw.isNullOrBlank() || !raw.trimStart().startsWith("{")) return null
    return runCatching { lenientJson.parseToJsonElement(raw) as? JsonObject }.getOrNull()
}

private fun JsonObject.str(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() }

private fun JsonObject.extractCommandString(): String? {
    str("command")?.let { return it.removeSurrounding("\"") }
    str("CommandLine")?.let { return it.removeSurrounding("\"") }
    str("cmd")?.let { return it.removeSurrounding("\"") }
    val arr = this["command"] as? JsonArray ?: this["cmd"] as? JsonArray
    if (arr != null && arr.isNotEmpty()) {
        val parts = arr.mapNotNull { (it as? JsonPrimitive)?.jsonPrimitive?.content }
        if (parts.size >= 3 && (parts[0].endsWith("zsh") || parts[0].endsWith("bash") || parts[0].endsWith("sh")) && parts[1].startsWith("-")) {
            return parts.drop(2).joinToString(" ")
        }
        return parts.joinToString(" ")
    }
    return null
}

private fun unwrapShellCommand(cmd: String): String {
    val trimmed = cmd.trim().removeSurrounding("\"")
    for (prefix in listOf("/bin/zsh -lc ", "zsh -lc ", "/bin/bash -lc ", "bash -lc ", "/bin/sh -c ", "sh -c ")) {
        if (trimmed.startsWith(prefix)) {
            return trimmed.removePrefix(prefix).trim().removeSurrounding("\"").removeSurrounding("'")
        }
    }
    return trimmed
}

private fun extractSkillNameFromPath(raw: String): String? {
    val idx = raw.indexOf("/skills/")
    if (idx < 0) return null
    val after = raw.substring(idx + "/skills/".length)
    if (!after.contains("SKILL.md", ignoreCase = true)) return null
    return after.substringBefore('/').trim().takeIf { it.isNotEmpty() }
}

private fun fileNameOnly(raw: String): String {
    val clean = raw.trim().removeSurrounding("\"").trimEnd('/')
    return clean.substringAfterLast('/').ifEmpty { clean }
}

private fun compactPath(raw: String): String {
    val clean = raw.trim().removeSurrounding("\"")
    if (clean.isEmpty()) return ""
    val parts = clean.trimEnd('/').split('/').filter { it.isNotEmpty() }
    return when {
        parts.size >= 2 -> "${parts[parts.size - 2]}/${parts.last()}"
        parts.size == 1 -> parts.first()
        else -> clean
    }
}

private fun oneLine(text: String, maxChars: Int): String {
    val single = text.replace('\n', ' ').replace(Regex("\\s+"), " ").trim()
    return if (single.length <= maxChars) single else single.take(maxChars - 1) + "…"
}

private fun extractPatchPath(rawInput: String?): String? {
    if (rawInput.isNullOrBlank()) return null
    val m = Regex("""\*\*\* (?:Update|Add|Delete) File:\s*([^\r\n]+)""").find(rawInput)
        ?: Regex("""^\+\+\+ b/([^\r\n]+)""", RegexOption.MULTILINE).find(rawInput)
    return m?.groupValues?.getOrNull(1)?.trim()
}

private fun synthesizeEditToolDiff(
    filePath: String,
    jsonObj: kotlinx.serialization.json.JsonObject?,
    rawInput: String?,
): Pair<String, String>? {
    val shortPath = compactPath(filePath).ifEmpty { "file" }

    // Case 1: Claude `Edit` (`old_string` / `new_string`) or AGY `edit_file` (`Target` / `Replacement`)
    val oldText = jsonObj?.str("old_string") ?: jsonObj?.str("Target")
    val newText = jsonObj?.str("new_string") ?: jsonObj?.str("Replacement")
    if (oldText != null || newText != null) {
        val oldLines = (oldText ?: "").lines()
        val newLines = (newText ?: "").lines()
        var prefix = 0
        while (prefix < oldLines.size && prefix < newLines.size && oldLines[prefix] == newLines[prefix]) {
            prefix++
        }
        var suffix = 0
        while (
            suffix < (oldLines.size - prefix) &&
            suffix < (newLines.size - prefix) &&
            oldLines[oldLines.size - 1 - suffix] == newLines[newLines.size - 1 - suffix]
        ) {
            suffix++
        }
        val removed = oldLines.subList(prefix, oldLines.size - suffix)
        val added = newLines.subList(prefix, newLines.size - suffix)
        val ctxBefore = oldLines.subList((prefix - 2).coerceAtLeast(0), prefix)
        val ctxAfter = oldLines.subList(oldLines.size - suffix, (oldLines.size - suffix + 2).coerceAtMost(oldLines.size))

        val diffText = buildString {
            appendLine("--- a/$shortPath")
            appendLine("+++ b/$shortPath")
            appendLine("@@ -${prefix + 1},${removed.size} +${prefix + 1},${added.size} @@")
            ctxBefore.forEach { appendLine(" $it") }
            removed.take(60).forEach { appendLine("-$it") }
            if (removed.size > 60) appendLine("-… (省略 ${removed.size - 60} 行)")
            added.take(60).forEach { appendLine("+$it") }
            if (added.size > 60) appendLine("+… (省略 ${added.size - 60} 行)")
            ctxAfter.forEach { appendLine(" $it") }
        }.trimEnd()
        return diffText to "+${added.size} / -${removed.size}"
    }

    // Case 2: `Write` / `write_file` (`content` / `Content`)
    val createdContent = jsonObj?.str("content") ?: jsonObj?.str("Content")
    if (!createdContent.isNullOrEmpty()) {
        val lines = createdContent.lines()
        val preview = buildString {
            appendLine("--- /dev/null")
            appendLine("+++ b/$shortPath")
            appendLine("@@ -0,0 +1,${lines.size} @@")
            lines.take(50).forEach { appendLine("+$it") }
            if (lines.size > 50) appendLine("+… (共 ${lines.size} 行)")
        }.trimEnd()
        return preview to "+${lines.size}"
    }

    // Case 3: Codex `apply_patch` raw unified/patch text
    val patch = jsonObj?.str("patch") ?: jsonObj?.str("input") ?: rawInput
    if (!patch.isNullOrBlank() && (patch.contains("*** ") || patch.contains("\n@@ ") || patch.startsWith("--- "))) {
        val lines = patch.lines()
        val addCount = lines.count { it.startsWith("+") && !it.startsWith("+++") }
        val delCount = lines.count { it.startsWith("-") && !it.startsWith("---") }
        return patch.trim() to "+$addCount / -$delCount"
    }

    return null
}
