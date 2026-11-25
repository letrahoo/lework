package dev.lework.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.DisableSelection
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.OpenInFull
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.lework.platform.copyTextToClipboard
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

// ---------------------------------------------------------------------------
// Mermaid AST Model (Pure Kotlin Multiplatform)
// ---------------------------------------------------------------------------

internal enum class MermaidDiagramKind(val displayName: String) {
    FLOWCHART("流程图"),
    SEQUENCE("时序图"),
    STATE("状态图"),
    CLASS("类图"),
    ER("关系图"),
    PIE("饼图"),
    UNSUPPORTED("图表"),
}

internal enum class MermaidDirection {
    TD,
    BT,
    LR,
    RL,
}

internal enum class MermaidNodeShape {
    RECT,
    ROUNDED,
    STADIUM,
    DIAMOND,
    CIRCLE,
    CYLINDER,
    SUBROUTINE,
    STATE_START,
    STATE_END,
    CARD,
}

internal enum class MermaidEdgeStyle {
    SOLID_ARROW,
    SOLID_LINE,
    DASHED_ARROW,
    DASHED_LINE,
    THICK_ARROW,
    BIDIRECTIONAL,
}

internal data class MermaidNode(
    val id: String,
    val label: String,
    val shape: MermaidNodeShape = MermaidNodeShape.RECT,
    val subtitle: String? = null,
    val members: List<String> = emptyList(),
)

internal data class MermaidEdge(
    val from: String,
    val to: String,
    val label: String = "",
    val style: MermaidEdgeStyle = MermaidEdgeStyle.SOLID_ARROW,
    val reverseArrow: Boolean = false,
    val fromMultiplicity: String = "",
    val toMultiplicity: String = "",
)

internal data class MermaidSubgraph(
    val id: String,
    val title: String,
    val nodeIds: List<String>,
    val depth: Int = 0,
)

internal enum class MermaidSeqArrowStyle {
    SOLID_ARROW,
    DASHED_ARROW,
    SOLID_LINE,
    DASHED_LINE,
    CROSS,
    ASYNC,
}

internal data class MermaidSeqParticipant(
    val id: String,
    val label: String,
    val isActor: Boolean = false,
)

internal enum class SeqNotePlacement {
    LEFT_OF,
    RIGHT_OF,
    OVER,
}

internal sealed interface MermaidSeqItem {
    data class Message(
        val from: String,
        val to: String,
        val text: String,
        val style: MermaidSeqArrowStyle,
    ) : MermaidSeqItem

    data class Note(
        val placement: SeqNotePlacement,
        val participants: List<String>,
        val text: String,
    ) : MermaidSeqItem

    data class BlockStart(
        val kind: String,
        val label: String,
    ) : MermaidSeqItem

    data class BlockDivider(
        val kind: String,
        val label: String,
    ) : MermaidSeqItem

    data object BlockEnd : MermaidSeqItem
}

internal data class MermaidPieSlice(
    val label: String,
    val value: Double,
    val fraction: Double,
)

internal sealed interface MermaidDiagram {
    val kind: MermaidDiagramKind

    data class Graph(
        override val kind: MermaidDiagramKind,
        val direction: MermaidDirection,
        val nodes: List<MermaidNode>,
        val edges: List<MermaidEdge>,
        val subgraphs: List<MermaidSubgraph> = emptyList(),
    ) : MermaidDiagram

    data class Sequence(
        val participants: List<MermaidSeqParticipant>,
        val items: List<MermaidSeqItem>,
    ) : MermaidDiagram {
        override val kind: MermaidDiagramKind = MermaidDiagramKind.SEQUENCE
    }

    data class Pie(
        val title: String,
        val showData: Boolean,
        val slices: List<MermaidPieSlice>,
    ) : MermaidDiagram {
        override val kind: MermaidDiagramKind = MermaidDiagramKind.PIE
    }

    data class Unsupported(
        val reason: String,
    ) : MermaidDiagram {
        override val kind: MermaidDiagramKind = MermaidDiagramKind.UNSUPPORTED
    }
}

// ---------------------------------------------------------------------------
// Mermaid Parser Entry Point (Streaming-Safe, Never Throws)
// ---------------------------------------------------------------------------

private val MERMAID_BR_REGEX = Regex("<br\\s*/?>", RegexOption.IGNORE_CASE)
private val MERMAID_INIT_REGEX = Regex("%%\\{.*?}%%", setOf(RegexOption.DOT_MATCHES_ALL))

internal fun parseMermaidDiagram(raw: String): MermaidDiagram {
    return try {
        val cleaned = preprocessMermaidSource(raw)
        val lines = cleaned.split('\n')
        val headerIdx = lines.indexOfFirst { it.trim().isNotEmpty() }
        if (headerIdx < 0) {
            return MermaidDiagram.Unsupported("图表内容为空")
        }
        val header = lines[headerIdx].trim().trimEnd(';')
        val bodyLines = lines.drop(headerIdx + 1)
        val firstWord = header.split(Regex("\\s+"), limit = 2).firstOrNull()?.lowercase().orEmpty()

        when {
            firstWord == "flowchart" || firstWord == "graph" ->
                parseFlowchart(header, bodyLines)
            firstWord == "sequencediagram" ->
                parseSequenceDiagram(bodyLines)
            firstWord == "statediagram" || firstWord == "statediagram-v2" ->
                parseStateDiagram(bodyLines)
            firstWord == "classdiagram" || firstWord == "classdiagram-v2" ->
                parseClassDiagram(bodyLines)
            firstWord == "erdiagram" ->
                parseErDiagram(bodyLines)
            firstWord == "pie" ->
                parsePieChart(header, bodyLines)
            else ->
                MermaidDiagram.Unsupported("暂未支持的图表类型: $firstWord")
        }
    } catch (_: Throwable) {
        MermaidDiagram.Unsupported("图表语法暂无法解析")
    }
}

private fun preprocessMermaidSource(raw: String): String {
    var text = raw.replace("\r\n", "\n").replace('\r', '\n')
    // Strip optional YAML frontmatter (---\n...\n---)
    val trimmedStart = text.trimStart()
    if (trimmedStart.startsWith("---\n")) {
        val closeIdx = trimmedStart.indexOf("\n---", startIndex = 4)
        if (closeIdx > 0) {
            val after = closeIdx + 4
            val nextNl = trimmedStart.indexOf('\n', after)
            text = if (nextNl >= 0) trimmedStart.substring(nextNl + 1) else ""
        }
    }
    text = text.replace(MERMAID_INIT_REGEX, "")
    return text.split('\n').joinToString("\n") { stripMermaidComment(it) }
}

private fun stripMermaidComment(line: String): String {
    var inQuotes = false
    var i = 0
    while (i < line.length) {
        val c = line[i]
        if (c == '"' && (i == 0 || line[i - 1] != '\\')) {
            inQuotes = !inQuotes
        } else if (!inQuotes && c == '%' && i + 1 < line.length && line[i + 1] == '%') {
            return line.substring(0, i)
        }
        i++
    }
    return line
}

private fun normalizeMermaidLabel(raw: String): String {
    var s = raw.trim()
    if (s.length >= 2 && ((s.startsWith("\"") && s.endsWith("\"")) || (s.startsWith("'") && s.endsWith("'")))) {
        s = s.substring(1, s.length - 1)
    }
    s = s.replace("\\\"", "\"")
        .replace("\\n", "\n")
        .replace(MERMAID_BR_REGEX, "\n")
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
    return s.split('\n').joinToString("\n") { it.trim() }.trim()
}

private fun parseDirectionToken(token: String?): MermaidDirection = when (token?.trim()?.uppercase()) {
    "LR" -> MermaidDirection.LR
    "RL" -> MermaidDirection.RL
    "BT" -> MermaidDirection.BT
    "TB", "TD" -> MermaidDirection.TD
    else -> MermaidDirection.TD
}

private fun splitStatementsOutsideQuotes(line: String): List<String> {
    val result = mutableListOf<String>()
    val cur = StringBuilder()
    var inQuotes = false
    var i = 0
    while (i < line.length) {
        val c = line[i]
        if (c == '"' && (i == 0 || line[i - 1] != '\\')) {
            inQuotes = !inQuotes
            cur.append(c)
        } else if (c == ';' && !inQuotes) {
            val stmt = cur.toString().trim()
            if (stmt.isNotEmpty()) result.add(stmt)
            cur.setLength(0)
        } else {
            cur.append(c)
        }
        i++
    }
    val tail = cur.toString().trim()
    if (tail.isNotEmpty()) result.add(tail)
    return result
}

// ---------------------------------------------------------------------------
// 1. Flowchart / Graph Parser
// ---------------------------------------------------------------------------

private data class MutableSubgraph(
    val id: String,
    val title: String,
    val depth: Int,
    val nodeIds: LinkedHashSet<String> = linkedSetOf(),
)

private data class ParsedNodeRef(
    val id: String,
    val explicitLabel: String? = null,
    val explicitShape: MermaidNodeShape? = null,
)

private data class ParsedEdgeOp(
    val style: MermaidEdgeStyle,
    val label: String,
    val nextIndex: Int,
)

private fun parseFlowchart(header: String, bodyLines: List<String>): MermaidDiagram {
    val headerParts = header.split(Regex("\\s+"))
    var direction = parseDirectionToken(headerParts.getOrNull(1))

    val nodesById = LinkedHashMap<String, MermaidNode>()
    val edges = mutableListOf<MermaidEdge>()
    val subgraphStack = mutableListOf<MutableSubgraph>()
    val completedSubgraphs = mutableListOf<MutableSubgraph>()
    var subgraphSeq = 0

    fun registerNode(ref: ParsedNodeRef) {
        val existing = nodesById[ref.id]
        val updated = when {
            existing == null -> MermaidNode(
                id = ref.id,
                label = ref.explicitLabel?.ifBlank { ref.id } ?: ref.id,
                shape = ref.explicitShape ?: MermaidNodeShape.RECT,
            )
            ref.explicitShape != null || ref.explicitLabel != null -> existing.copy(
                label = ref.explicitLabel?.ifBlank { existing.label } ?: existing.label,
                shape = ref.explicitShape ?: existing.shape,
            )
            else -> existing
        }
        nodesById[ref.id] = updated
        for (sg in subgraphStack) {
            sg.nodeIds.add(ref.id)
        }
    }

    for (rawLine in bodyLines) {
        for (stmt in splitStatementsOutsideQuotes(rawLine)) {
            val lower = stmt.lowercase()
            when {
                lower.startsWith("style ") ||
                    lower.startsWith("classdef ") ||
                    lower.startsWith("class ") ||
                    lower.startsWith("linkstyle ") ||
                    lower.startsWith("click ") ||
                    lower.startsWith("acc") -> continue

                lower.startsWith("direction ") -> {
                    if (subgraphStack.isEmpty()) {
                        direction = parseDirectionToken(stmt.substringAfter(' ').trim())
                    }
                    continue
                }

                lower == "end" -> {
                    if (subgraphStack.isNotEmpty()) {
                        completedSubgraphs.add(subgraphStack.removeAt(subgraphStack.lastIndex))
                    }
                    continue
                }

                lower.startsWith("subgraph ") || lower == "subgraph" -> {
                    val rest = stmt.drop("subgraph".length).trim()
                    val sg = parseSubgraphHeader(rest, subgraphSeq++, subgraphStack.size)
                    subgraphStack.add(sg)
                    continue
                }
            }

            scanFlowchartStatement(stmt, ::registerNode) { from, to, label, style ->
                edges.add(MermaidEdge(from = from, to = to, label = label, style = style))
            }
        }
    }

    // Auto-close any open subgraphs during streaming
    while (subgraphStack.isNotEmpty()) {
        completedSubgraphs.add(subgraphStack.removeAt(subgraphStack.lastIndex))
    }

    if (nodesById.isEmpty()) {
        return MermaidDiagram.Unsupported("未解析到流程图节点")
    }

    val subgraphs = completedSubgraphs
        .filter { it.nodeIds.isNotEmpty() }
        .sortedBy { it.depth }
        .map { MermaidSubgraph(id = it.id, title = it.title, nodeIds = it.nodeIds.toList(), depth = it.depth) }

    return MermaidDiagram.Graph(
        kind = MermaidDiagramKind.FLOWCHART,
        direction = direction,
        nodes = nodesById.values.toList(),
        edges = edges,
        subgraphs = subgraphs,
    )
}

private fun parseSubgraphHeader(rest: String, seq: Int, depth: Int): MutableSubgraph {
    if (rest.isEmpty()) {
        return MutableSubgraph(id = "sg_$seq", title = "分组 ${seq + 1}", depth = depth)
    }
    val bracketIdx = rest.indexOf('[')
    if (bracketIdx > 0 && rest.endsWith(']')) {
        val rawId = rest.substring(0, bracketIdx).trim()
        val rawTitle = rest.substring(bracketIdx + 1, rest.length - 1)
        if (rawId.isNotEmpty() && rawId.none { it.isWhitespace() }) {
            return MutableSubgraph(
                id = rawId,
                title = normalizeMermaidLabel(rawTitle).ifBlank { rawId },
                depth = depth,
            )
        }
    }
    val cleanTitle = normalizeMermaidLabel(rest)
    return MutableSubgraph(
        id = "sg_$seq",
        title = cleanTitle.ifBlank { "分组 ${seq + 1}" },
        depth = depth,
    )
}

private fun scanFlowchartStatement(
    stmt: String,
    onNode: (ParsedNodeRef) -> Unit,
    onEdge: (from: String, to: String, label: String, style: MermaidEdgeStyle) -> Unit,
) {
    val n = stmt.length
    var pos = 0

    fun skipSpaces() {
        while (pos < n && stmt[pos].isWhitespace()) pos++
    }

    fun scanNodeGroup(): List<ParsedNodeRef> {
        val group = mutableListOf<ParsedNodeRef>()
        while (pos < n) {
            skipSpaces()
            val ref = scanNodeToken(stmt, pos) ?: break
            onNode(ref.first)
            group.add(ref.first)
            pos = ref.second
            skipSpaces()
            if (pos < n && stmt[pos] == '&') {
                pos++
            } else {
                break
            }
        }
        return group
    }

    var leftGroup = scanNodeGroup()
    if (leftGroup.isEmpty()) return

    while (pos < n) {
        skipSpaces()
        if (pos >= n) break
        val edgeOp = scanFlowchartEdgeOp(stmt, pos) ?: break
        pos = edgeOp.nextIndex
        skipSpaces()
        if (pos >= n) break // streaming partial edge: ignore cleanly
        val rightGroup = scanNodeGroup()
        if (rightGroup.isEmpty()) break
        for (src in leftGroup) {
            for (dst in rightGroup) {
                onEdge(src.id, dst.id, edgeOp.label, edgeOp.style)
            }
        }
        leftGroup = rightGroup
    }
}

private fun isArrowStartAt(s: String, idx: Int): Boolean {
    if (idx >= s.length) return false
    val c = s[idx]
    val next = s.getOrNull(idx + 1)
    return when (c) {
        '-' -> next == '-' || next == '>' || next == '.'
        '=' -> next == '='
        '<' -> next == '-' || next == '='
        '.' -> next == '-' || next == '>'
        else -> false
    }
}

private fun scanNodeToken(stmt: String, start: Int): Pair<ParsedNodeRef, Int>? {
    val n = stmt.length
    var i = start
    while (i < n && stmt[i].isWhitespace()) i++
    if (i >= n) return null

    // Read identifier
    val idStart = i
    while (i < n) {
        val c = stmt[i]
        when {
            c.isLetterOrDigit() || c == '_' || c == '.' -> i++
            c == '-' -> {
                if (isArrowStartAt(stmt, i)) break
                val next = stmt.getOrNull(i + 1)
                if (next != null && (next.isLetterOrDigit() || next == '_')) i++ else break
            }
            else -> break
        }
    }
    if (i == idStart) return null
    val id = stmt.substring(idStart, i)

    var explicitLabel: String? = null
    var explicitShape: MermaidNodeShape? = null

    // Match optional shape immediately following node ID
    if (i < n) {
        val shapeMatch = matchNodeShape(stmt, i)
        if (shapeMatch != null) {
            explicitShape = shapeMatch.first
            explicitLabel = shapeMatch.second
            i = shapeMatch.third
        }
    }

    // Consume optional Mermaid :::className suffix
    if (stmt.startsWith(":::", i)) {
        i += 3
        while (i < n && (stmt[i].isLetterOrDigit() || stmt[i] == '_' || stmt[i] == '-')) i++
    }

    return ParsedNodeRef(id = id, explicitLabel = explicitLabel, explicitShape = explicitShape) to i
}

private fun matchNodeShape(s: String, start: Int): Triple<MermaidNodeShape, String, Int>? {
    // Check 3-char and 2-char compound delimiters before 1-char delimiters
    val compoundSpecs: List<Triple<String, String, MermaidNodeShape>> = listOf(
        Triple("(((", ")))", MermaidNodeShape.CIRCLE),
        Triple("((", "))", MermaidNodeShape.CIRCLE),
        Triple("([", "])", MermaidNodeShape.STADIUM),
        Triple("[(", ")]", MermaidNodeShape.CYLINDER),
        Triple("[[", "]]", MermaidNodeShape.SUBROUTINE),
        Triple("{{", "}}", MermaidNodeShape.DIAMOND),
    )
    for ((open, close, shape) in compoundSpecs) {
        if (s.startsWith(open, start)) {
            val closeIdx = findClosingDelimiter(s, start + open.length, open, close)
            val rawInner = if (closeIdx >= 0) {
                s.substring(start + open.length, closeIdx)
            } else {
                s.substring(start + open.length)
            }
            val endIdx = if (closeIdx >= 0) closeIdx + close.length else s.length
            return Triple(shape, normalizeMermaidLabel(rawInner), endIdx)
        }
    }

    val singleSpec: Triple<Char, Char, MermaidNodeShape>? = when (s[start]) {
        '[' -> Triple('[', ']', MermaidNodeShape.RECT)
        '(' -> Triple('(', ')', MermaidNodeShape.ROUNDED)
        '{' -> Triple('{', '}', MermaidNodeShape.DIAMOND)
        else -> null
    }
    if (singleSpec != null) {
        val (openCh, closeCh, shape) = singleSpec
        val closeIdx = findMatchingSingleCharClose(s, start + 1, openCh, closeCh)
        var rawInner = if (closeIdx >= 0) {
            s.substring(start + 1, closeIdx)
        } else {
            s.substring(start + 1)
        }
        // Gracefully unwrap parallelogram/trapezoid slashes like [/Label/] or [\Label\]
        if (openCh == '[' && rawInner.length >= 2) {
            val first = rawInner.first()
            val last = rawInner.last()
            if ((first == '/' || first == '\\') && (last == '/' || last == '\\')) {
                rawInner = rawInner.substring(1, rawInner.length - 1)
            }
        }
        val endIdx = if (closeIdx >= 0) closeIdx + 1 else s.length
        return Triple(shape, normalizeMermaidLabel(rawInner), endIdx)
    }
    return null
}

private fun findClosingDelimiter(s: String, from: Int, open: String, close: String): Int {
    var inQuotes = false
    var i = from
    while (i < s.length) {
        val c = s[i]
        if (c == '"' && (i == 0 || s[i - 1] != '\\')) {
            inQuotes = !inQuotes
            i++
            continue
        }
        if (!inQuotes && s.startsWith(close, i)) {
            return i
        }
        i++
    }
    return -1
}

private fun findMatchingSingleCharClose(s: String, from: Int, openCh: Char, closeCh: Char): Int {
    var inQuotes = false
    var depth = 1
    var i = from
    while (i < s.length) {
        val c = s[i]
        if (c == '"' && (i == 0 || s[i - 1] != '\\')) {
            inQuotes = !inQuotes
            i++
            continue
        }
        if (!inQuotes) {
            if (c == openCh) depth++
            else if (c == closeCh) {
                depth--
                if (depth == 0) return i
            }
        }
        i++
    }
    return -1
}

private fun scanFlowchartEdgeOp(stmt: String, start: Int): ParsedEdgeOp? {
    val n = stmt.length
    if (start >= n) return null

    // 1. Inline-labeled edges:
    //    `-- text -->`, `-- text ---`, `-. text .->`, `== text ==>`
    if (stmt.startsWith("-- ", start) || stmt.startsWith("--\t", start)) {
        val arrowEnd = findInlineEdgeTerminator(stmt, start + 3, listOf("-->", "--->", "---->", "--o", "--x", "---"))
        if (arrowEnd != null) {
            val (termIdx, term) = arrowEnd
            val label = normalizeMermaidLabel(stmt.substring(start + 3, termIdx))
            val style = if (term.all { it == '-' }) MermaidEdgeStyle.SOLID_LINE else MermaidEdgeStyle.SOLID_ARROW
            return ParsedEdgeOp(style = style, label = label, nextIndex = termIdx + term.length)
        }
    }
    if (stmt.startsWith("-. ", start) || stmt.startsWith("-.\t", start)) {
        val arrowEnd = findInlineEdgeTerminator(stmt, start + 3, listOf(".->", ".-"))
        if (arrowEnd != null) {
            val (termIdx, term) = arrowEnd
            val label = normalizeMermaidLabel(stmt.substring(start + 3, termIdx))
            val style = if (term.endsWith(">")) MermaidEdgeStyle.DASHED_ARROW else MermaidEdgeStyle.DASHED_LINE
            return ParsedEdgeOp(style = style, label = label, nextIndex = termIdx + term.length)
        }
    }
    if (stmt.startsWith("== ", start) || stmt.startsWith("==\t", start)) {
        val arrowEnd = findInlineEdgeTerminator(stmt, start + 3, listOf("==>", "===>", "==="))
        if (arrowEnd != null) {
            val (termIdx, term) = arrowEnd
            val label = normalizeMermaidLabel(stmt.substring(start + 3, termIdx))
            val style = if (term.endsWith(">")) MermaidEdgeStyle.THICK_ARROW else MermaidEdgeStyle.SOLID_LINE
            return ParsedEdgeOp(style = style, label = label, nextIndex = termIdx + term.length)
        }
    }

    // 2. Standard arrow operators (bidirectional, dashed, thick, solid)
    val opResult: Pair<MermaidEdgeStyle, Int>? = matchStandardArrow(stmt, start)
    if (opResult == null) return null

    val (style, afterOp) = opResult
    var pos = afterOp
    while (pos < n && stmt[pos].isWhitespace()) pos++

    // Optional pipe label: `|label|`
    var label = ""
    if (pos < n && stmt[pos] == '|') {
        var endPipe = pos + 1
        var inQuotes = false
        while (endPipe < n) {
            val ch = stmt[endPipe]
            if (ch == '"' && stmt[endPipe - 1] != '\\') {
                inQuotes = !inQuotes
            } else if (ch == '|' && !inQuotes) {
                break
            }
            endPipe++
        }
        if (endPipe < n && stmt[endPipe] == '|') {
            label = normalizeMermaidLabel(stmt.substring(pos + 1, endPipe))
            pos = endPipe + 1
        } else {
            label = normalizeMermaidLabel(stmt.substring(pos + 1))
            pos = n
        }
    }
    return ParsedEdgeOp(style = style, label = label, nextIndex = pos)
}

private fun findInlineEdgeTerminator(s: String, from: Int, terminators: List<String>): Pair<Int, String>? {
    var inQuotes = false
    var i = from
    while (i < s.length) {
        val c = s[i]
        if (c == '"' && (i == 0 || s[i - 1] != '\\')) {
            inQuotes = !inQuotes
            i++
            continue
        }
        if (!inQuotes) {
            for (t in terminators) {
                if (s.startsWith(t, i)) {
                    return i to t
                }
            }
        }
        i++
    }
    return null
}

private fun matchStandardArrow(s: String, start: Int): Pair<MermaidEdgeStyle, Int>? {
    val n = s.length
    var i = start
    val c = s[i]

    // Bidirectional: `<-->`, `<--->`, `<-.->`, `<==>`
    if (c == '<') {
        i++
        if (i < n && s[i] == '-') {
            while (i < n && (s[i] == '-' || s[i] == '.')) i++
            if (i < n && s[i] == '>') return MermaidEdgeStyle.BIDIRECTIONAL to (i + 1)
        } else if (i < n && s[i] == '=') {
            while (i < n && s[i] == '=') i++
            if (i < n && s[i] == '>') return MermaidEdgeStyle.BIDIRECTIONAL to (i + 1)
        }
        return null
    }

    // Thick: `==>`, `===>`, `===`
    if (c == '=' && i + 1 < n && s[i + 1] == '=') {
        while (i < n && s[i] == '=') i++
        return if (i < n && s[i] == '>') {
            MermaidEdgeStyle.THICK_ARROW to (i + 1)
        } else if (i - start >= 3) {
            MermaidEdgeStyle.SOLID_LINE to i
        } else {
            null
        }
    }

    // Dashed or Solid starting with `-`
    if (c == '-') {
        var hasDot = false
        while (i < n && (s[i] == '-' || s[i] == '.')) {
            if (s[i] == '.') hasDot = true
            i++
        }
        val len = i - start
        if (i < n && (s[i] == '>' || s[i] == 'o' || s[i] == 'x')) {
            val end = i + 1
            return if (hasDot) {
                MermaidEdgeStyle.DASHED_ARROW to end
            } else {
                MermaidEdgeStyle.SOLID_ARROW to end
            }
        }
        if (hasDot && len >= 3) {
            return MermaidEdgeStyle.DASHED_LINE to i
        }
        if (!hasDot && len >= 3) {
            return MermaidEdgeStyle.SOLID_LINE to i
        }
    }

    return null
}

// ---------------------------------------------------------------------------
// 2. Sequence Diagram Parser
// ---------------------------------------------------------------------------

private val SEQ_ARROW_SPECS: List<Pair<String, MermaidSeqArrowStyle>> = listOf(
    "-->>" to MermaidSeqArrowStyle.DASHED_ARROW,
    "->>" to MermaidSeqArrowStyle.SOLID_ARROW,
    "--x" to MermaidSeqArrowStyle.CROSS,
    "-x" to MermaidSeqArrowStyle.CROSS,
    "--)" to MermaidSeqArrowStyle.ASYNC,
    "-)" to MermaidSeqArrowStyle.ASYNC,
    "-->" to MermaidSeqArrowStyle.DASHED_LINE,
    "->" to MermaidSeqArrowStyle.SOLID_LINE,
)

private fun parseSequenceDiagram(bodyLines: List<String>): MermaidDiagram {
    val participants = LinkedHashMap<String, MermaidSeqParticipant>()
    val items = mutableListOf<MermaidSeqItem>()

    fun ensureParticipant(rawId: String, alias: String? = null, isActor: Boolean = false): String {
        val id = rawId.trim().removePrefix("+").removePrefix("-").trim()
        if (id.isEmpty()) return ""
        val existing = participants[id]
        if (existing == null) {
            participants[id] = MermaidSeqParticipant(
                id = id,
                label = normalizeMermaidLabel(alias ?: id).ifBlank { id },
                isActor = isActor,
            )
        } else if (alias != null || isActor) {
            participants[id] = existing.copy(
                label = if (alias != null) normalizeMermaidLabel(alias).ifBlank { existing.label } else existing.label,
                isActor = existing.isActor || isActor,
            )
        }
        return id
    }

    for (rawLine in bodyLines) {
        val line = rawLine.trim()
        if (line.isEmpty()) continue
        val lower = line.lowercase()

        when {
            lower == "autonumber" ||
                lower.startsWith("activate ") ||
                lower.startsWith("deactivate ") ||
                lower.startsWith("destroy ") ||
                lower.startsWith("box ") ||
                lower.startsWith("title ") ||
                lower.startsWith("link ") ||
                lower.startsWith("links ") -> continue

            lower.startsWith("participant ") || lower.startsWith("create participant ") -> {
                val rest = line.substringAfter("participant ", "").trim()
                val (id, alias) = splitParticipantAlias(rest)
                ensureParticipant(id, alias, isActor = false)
                continue
            }

            lower.startsWith("actor ") || lower.startsWith("create actor ") -> {
                val rest = line.substringAfter("actor ", "").trim()
                val (id, alias) = splitParticipantAlias(rest)
                ensureParticipant(id, alias, isActor = true)
                continue
            }

            lower == "end" -> {
                items.add(MermaidSeqItem.BlockEnd)
                continue
            }

            lower.startsWith("loop ") || lower == "loop" ||
                lower.startsWith("alt ") || lower == "alt" ||
                lower.startsWith("opt ") || lower == "opt" ||
                lower.startsWith("par ") || lower == "par" ||
                lower.startsWith("critical ") || lower == "critical" ||
                lower.startsWith("break ") || lower == "break" ||
                lower.startsWith("rect ") -> {
                val kind = line.substringBefore(' ').lowercase()
                val label = if (line.contains(' ')) normalizeMermaidLabel(line.substringAfter(' ')) else ""
                items.add(MermaidSeqItem.BlockStart(kind = kind, label = label))
                continue
            }

            lower.startsWith("else ") || lower == "else" ||
                lower.startsWith("and ") || lower == "and" ||
                lower.startsWith("option ") -> {
                val kind = line.substringBefore(' ').lowercase()
                val label = if (line.contains(' ')) normalizeMermaidLabel(line.substringAfter(' ')) else ""
                items.add(MermaidSeqItem.BlockDivider(kind = kind, label = label))
                continue
            }

            lower.startsWith("note ") -> {
                val note = parseSequenceNote(line, ::ensureParticipant)
                if (note != null) items.add(note)
                continue
            }
        }

        // Parse message: `From <arrow> To : text`
        val msg = parseSequenceMessage(line, ::ensureParticipant)
        if (msg != null) {
            items.add(msg)
        }
    }

    if (participants.isEmpty() && items.isEmpty()) {
        return MermaidDiagram.Unsupported("未解析到时序图内容")
    }

    return MermaidDiagram.Sequence(
        participants = participants.values.toList(),
        items = items,
    )
}

private fun splitParticipantAlias(rest: String): Pair<String, String?> {
    val asRegex = Regex("\\s+as\\s+", RegexOption.IGNORE_CASE)
    val match = asRegex.find(rest)
    return if (match != null) {
        val id = rest.substring(0, match.range.first).trim()
        val alias = rest.substring(match.range.last + 1).trim()
        id to alias.ifBlank { null }
    } else {
        rest.trim() to null
    }
}

private fun parseSequenceNote(
    line: String,
    ensureParticipant: (String, String?, Boolean) -> String,
): MermaidSeqItem.Note? {
    val rest = line.drop(5).trim()
    val lower = rest.lowercase()
    val (placement, afterPlacement) = when {
        lower.startsWith("left of ") -> SeqNotePlacement.LEFT_OF to rest.drop(8)
        lower.startsWith("right of ") -> SeqNotePlacement.RIGHT_OF to rest.drop(9)
        lower.startsWith("over ") -> SeqNotePlacement.OVER to rest.drop(5)
        else -> return null
    }
    val colonIdx = afterPlacement.indexOf(':')
    val targetPart = if (colonIdx >= 0) afterPlacement.substring(0, colonIdx) else afterPlacement
    val noteText = if (colonIdx >= 0) normalizeMermaidLabel(afterPlacement.substring(colonIdx + 1)) else ""
    val pIds = targetPart.split(',')
        .map { ensureParticipant(it.trim(), null, false) }
        .filter { it.isNotEmpty() }
    if (pIds.isEmpty()) return null
    return MermaidSeqItem.Note(placement = placement, participants = pIds, text = noteText)
}

private fun parseSequenceMessage(
    line: String,
    ensureParticipant: (String, String?, Boolean) -> String,
): MermaidSeqItem.Message? {
    val colonIdx = line.indexOf(':')
    val headerPart = if (colonIdx >= 0) line.substring(0, colonIdx) else line
    val msgText = if (colonIdx >= 0) normalizeMermaidLabel(line.substring(colonIdx + 1)) else ""

    for ((token, style) in SEQ_ARROW_SPECS) {
        val idx = headerPart.indexOf(token)
        if (idx > 0) {
            val rawFrom = headerPart.substring(0, idx).trim()
            val rawTo = headerPart.substring(idx + token.length).trim()
            if (rawFrom.isEmpty() || rawTo.isEmpty()) continue
            val from = ensureParticipant(rawFrom, null, false)
            val to = ensureParticipant(rawTo, null, false)
            if (from.isNotEmpty() && to.isNotEmpty()) {
                return MermaidSeqItem.Message(from = from, to = to, text = msgText, style = style)
            }
        }
    }
    return null
}

// ---------------------------------------------------------------------------
// 3. State Diagram Parser
// ---------------------------------------------------------------------------

private fun parseStateDiagram(bodyLines: List<String>): MermaidDiagram {
    var direction = MermaidDirection.TD
    val nodesById = LinkedHashMap<String, MermaidNode>()
    val edges = mutableListOf<MermaidEdge>()
    val subgraphStack = mutableListOf<MutableSubgraph>()
    val completedSubgraphs = mutableListOf<MutableSubgraph>()
    var subgraphSeq = 0
    var inMultilineNote = false

    fun ensureStateNode(rawId: String, isStartEndFrom: Boolean? = null): String {
        val trimmed = rawId.trim()
        if (trimmed == "[*]") {
            val pseudoId = if (isStartEndFrom == true) "__state_start__" else "__state_end__"
            if (!nodesById.containsKey(pseudoId)) {
                nodesById[pseudoId] = MermaidNode(
                    id = pseudoId,
                    label = "",
                    shape = if (isStartEndFrom == true) MermaidNodeShape.STATE_START else MermaidNodeShape.STATE_END,
                )
            }
            for (sg in subgraphStack) sg.nodeIds.add(pseudoId)
            return pseudoId
        }
        if (!nodesById.containsKey(trimmed)) {
            nodesById[trimmed] = MermaidNode(
                id = trimmed,
                label = trimmed,
                shape = MermaidNodeShape.ROUNDED,
            )
        }
        for (sg in subgraphStack) sg.nodeIds.add(trimmed)
        return trimmed
    }

    for (rawLine in bodyLines) {
        val line = rawLine.trim()
        if (line.isEmpty()) continue
        val lower = line.lowercase()

        if (inMultilineNote) {
            if (lower == "end note") inMultilineNote = false
            continue
        }
        if (lower.startsWith("note ")) {
            if (!line.contains(':')) inMultilineNote = true
            continue
        }
        if (lower.startsWith("direction ")) {
            direction = parseDirectionToken(line.substringAfter(' ').trim())
            continue
        }
        if (lower.startsWith("classdef ") || lower.startsWith("class ") || lower.startsWith("style ")) {
            continue
        }
        if (line == "}") {
            if (subgraphStack.isNotEmpty()) {
                completedSubgraphs.add(subgraphStack.removeAt(subgraphStack.lastIndex))
            }
            continue
        }
        // Composite state: `state CompositeName {` or `state "Label" as CompositeName {`
        if (lower.startsWith("state ") && line.endsWith("{")) {
            val inner = line.drop(6).dropLast(1).trim()
            val asMatch = Regex("^\"([^\"]+)\"\\s+as\\s+(\\S+)$", RegexOption.IGNORE_CASE).find(inner)
            val (sgId, sgTitle) = if (asMatch != null) {
                asMatch.groupValues[2] to normalizeMermaidLabel(asMatch.groupValues[1])
            } else {
                "sg_state_${subgraphSeq++}" to normalizeMermaidLabel(inner)
            }
            subgraphStack.add(MutableSubgraph(id = sgId, title = sgTitle, depth = subgraphStack.size))
            continue
        }
        // Named state: `state "Display Label" as StateA`
        if (lower.startsWith("state ")) {
            val rest = line.drop(6).trim()
            val asMatch = Regex("^\"([^\"]+)\"\\s+as\\s+(\\S+)$", RegexOption.IGNORE_CASE).find(rest)
            if (asMatch != null) {
                val label = normalizeMermaidLabel(asMatch.groupValues[1])
                val id = asMatch.groupValues[2]
                val existing = nodesById[id]
                nodesById[id] = MermaidNode(
                    id = id,
                    label = label.ifBlank { id },
                    shape = existing?.shape ?: MermaidNodeShape.ROUNDED,
                )
                for (sg in subgraphStack) sg.nodeIds.add(id)
                continue
            }
        }
        // Transition: `A --> B` or `A --> B : label`
        val arrowIdx = line.indexOf("-->")
        if (arrowIdx > 0) {
            val leftRaw = line.substring(0, arrowIdx).trim()
            val rightFull = line.substring(arrowIdx + 3).trim()
            val colonIdx = rightFull.indexOf(':')
            val rightRaw = (if (colonIdx >= 0) rightFull.substring(0, colonIdx) else rightFull).trim()
            val label = if (colonIdx >= 0) normalizeMermaidLabel(rightFull.substring(colonIdx + 1)) else ""
            if (leftRaw.isNotEmpty() && rightRaw.isNotEmpty()) {
                val fromId = ensureStateNode(leftRaw, isStartEndFrom = true)
                val toId = ensureStateNode(rightRaw, isStartEndFrom = false)
                edges.add(MermaidEdge(from = fromId, to = toId, label = label, style = MermaidEdgeStyle.SOLID_ARROW))
                continue
            }
        }
        // Description line: `StateA : Some description`
        val colonIdx = line.indexOf(':')
        if (colonIdx > 0) {
            val id = line.substring(0, colonIdx).trim()
            val desc = normalizeMermaidLabel(line.substring(colonIdx + 1))
            if (id.isNotEmpty() && !id.contains(' ') && desc.isNotEmpty()) {
                val existing = nodesById[id]
                val nextLabel = if (existing == null || existing.label == id) {
                    "$id\n$desc"
                } else {
                    "${existing.label}\n$desc"
                }
                nodesById[id] = MermaidNode(id = id, label = nextLabel, shape = MermaidNodeShape.ROUNDED)
                for (sg in subgraphStack) sg.nodeIds.add(id)
            }
        }
    }

    while (subgraphStack.isNotEmpty()) {
        completedSubgraphs.add(subgraphStack.removeAt(subgraphStack.lastIndex))
    }

    if (nodesById.isEmpty()) {
        return MermaidDiagram.Unsupported("未解析到状态图节点")
    }

    return MermaidDiagram.Graph(
        kind = MermaidDiagramKind.STATE,
        direction = direction,
        nodes = nodesById.values.toList(),
        edges = edges,
        subgraphs = completedSubgraphs
            .filter { it.nodeIds.isNotEmpty() }
            .sortedBy { it.depth }
            .map { MermaidSubgraph(it.id, it.title, it.nodeIds.toList(), it.depth) },
    )
}

// ---------------------------------------------------------------------------
// 4. Class Diagram & ER Diagram Parsers
// ---------------------------------------------------------------------------

private data class MutableCardNode(
    val id: String,
    var label: String = id,
    var subtitle: String? = null,
    val members: MutableList<String> = mutableListOf(),
)

private val CLASS_REL_REGEX = Regex(
    """^([A-Za-z0-9_.\-~\u4e00-\u9fff]+)\s*(?:"([^"]*)"\s*)?(<\|--|--\|>|<\|\.\.|\.\.\|>|<\.\.|\.\.>|<\-\-|\-\->|<\-\->|\*--|--\*|o--|--o|--|\.\.)\s*(?:"([^"]*)"\s*)?([A-Za-z0-9_.\-~\u4e00-\u9fff]+)(?:\s*:\s*(.*))?$""",
)

private fun parseClassDiagram(bodyLines: List<String>): MermaidDiagram {
    var direction = MermaidDirection.TD
    val cards = LinkedHashMap<String, MutableCardNode>()
    val edges = mutableListOf<MermaidEdge>()
    var activeClassId: String? = null

    fun ensureCard(id: String, label: String? = null): MutableCardNode {
        val existing = cards[id]
        if (existing != null) {
            if (!label.isNullOrBlank()) existing.label = label
            return existing
        }
        return MutableCardNode(id = id, label = label?.ifBlank { id } ?: id).also { cards[id] = it }
    }

    for (rawLine in bodyLines) {
        val line = rawLine.trim()
        if (line.isEmpty()) continue
        val lower = line.lowercase()

        if (activeClassId != null) {
            if (line == "}") {
                activeClassId = null
                continue
            }
            val card = cards[activeClassId]
            if (card != null) {
                if (line.startsWith("<<") && line.endsWith(">>")) {
                    card.subtitle = line
                } else {
                    card.members.add(line)
                }
            }
            continue
        }

        if (lower.startsWith("direction ")) {
            direction = parseDirectionToken(line.substringAfter(' ').trim())
            continue
        }
        if (lower.startsWith("note ") || lower.startsWith("style ") || lower.startsWith("classdef ") || lower.startsWith("namespace ")) {
            continue
        }
        if (lower.startsWith("class ")) {
            val rest = line.drop(6).trim()
            val opensBlock = rest.endsWith("{")
            val head = if (opensBlock) rest.dropLast(1).trim() else rest
            val bracketIdx = head.indexOf('[')
            val (id, label) = if (bracketIdx > 0 && head.endsWith(']')) {
                head.substring(0, bracketIdx).trim() to normalizeMermaidLabel(head.substring(bracketIdx + 1, head.length - 1))
            } else {
                head.substringBefore(' ').trim() to null
            }
            if (id.isNotEmpty()) {
                ensureCard(id, label)
                if (opensBlock) activeClassId = id
            }
            continue
        }

        // Relationship line
        val relMatch = CLASS_REL_REGEX.find(line)
        if (relMatch != null) {
            val leftId = relMatch.groupValues[1]
            val leftMult = relMatch.groupValues[2]
            val op = relMatch.groupValues[3]
            val rightMult = relMatch.groupValues[4]
            val rightId = relMatch.groupValues[5]
            val label = normalizeMermaidLabel(relMatch.groupValues[6])
            ensureCard(leftId)
            ensureCard(rightId)
            val isDashed = op.contains("..")
            val reverseArrow = op.startsWith("<") && !op.endsWith(">")
            val style = when {
                op == "<-->" -> MermaidEdgeStyle.BIDIRECTIONAL
                isDashed && (op.contains(">") || op.contains("<")) -> MermaidEdgeStyle.DASHED_ARROW
                isDashed -> MermaidEdgeStyle.DASHED_LINE
                op.contains(">") || op.contains("<") -> MermaidEdgeStyle.SOLID_ARROW
                else -> MermaidEdgeStyle.SOLID_LINE
            }
            edges.add(
                MermaidEdge(
                    from = leftId,
                    to = rightId,
                    label = label,
                    style = style,
                    reverseArrow = reverseArrow,
                    fromMultiplicity = leftMult,
                    toMultiplicity = rightMult,
                ),
            )
            continue
        }

        // Inline member: `ClassName : +String field`
        val colonIdx = line.indexOf(':')
        if (colonIdx > 0) {
            val id = line.substring(0, colonIdx).trim()
            val member = line.substring(colonIdx + 1).trim()
            if (id.isNotEmpty() && !id.contains(' ') && member.isNotEmpty()) {
                val card = ensureCard(id)
                if (member.startsWith("<<") && member.endsWith(">>")) {
                    card.subtitle = member
                } else {
                    card.members.add(member)
                }
            }
        }
    }

    if (cards.isEmpty()) {
        return MermaidDiagram.Unsupported("未解析到类图定义")
    }

    return MermaidDiagram.Graph(
        kind = MermaidDiagramKind.CLASS,
        direction = direction,
        nodes = cards.values.map {
            MermaidNode(
                id = it.id,
                label = it.label,
                shape = MermaidNodeShape.CARD,
                subtitle = it.subtitle,
                members = it.members.toList(),
            )
        },
        edges = edges,
    )
}

private val ER_REL_REGEX = Regex(
    """^("?[A-Za-z0-9_.\-\u4e00-\u9fff]+"?)\s+([|o\{\}]{1,2})(--|\.\.)([|o\{\}]{1,2})\s+("?[A-Za-z0-9_.\-\u4e00-\u9fff]+"?)(?:\s*:\s*(.*))?$""",
)

private fun parseErDiagram(bodyLines: List<String>): MermaidDiagram {
    var direction = MermaidDirection.TD
    val entities = LinkedHashMap<String, MutableCardNode>()
    val edges = mutableListOf<MermaidEdge>()
    var activeEntityId: String? = null

    fun ensureEntity(rawName: String): MutableCardNode {
        val clean = normalizeMermaidLabel(rawName)
        return entities.getOrPut(clean) { MutableCardNode(id = clean, label = clean) }
    }

    for (rawLine in bodyLines) {
        val line = rawLine.trim()
        if (line.isEmpty()) continue
        val lower = line.lowercase()

        if (activeEntityId != null) {
            if (line == "}") {
                activeEntityId = null
                continue
            }
            entities[activeEntityId]?.members?.add(line.replace(Regex("\\s+"), " "))
            continue
        }

        if (lower.startsWith("direction ")) {
            direction = parseDirectionToken(line.substringAfter(' ').trim())
            continue
        }

        // Entity block header: `USER {`
        if (line.endsWith("{")) {
            val name = line.dropLast(1).trim()
            if (name.isNotEmpty()) {
                val ent = ensureEntity(name)
                activeEntityId = ent.id
            }
            continue
        }

        // Relationship line: `USER ||--o{ ORDER : places`
        val relMatch = ER_REL_REGEX.find(line)
        if (relMatch != null) {
            val left = ensureEntity(relMatch.groupValues[1])
            val leftCard = relMatch.groupValues[2]
            val lineKind = relMatch.groupValues[3]
            val rightCard = relMatch.groupValues[4]
            val right = ensureEntity(relMatch.groupValues[5])
            val label = normalizeMermaidLabel(relMatch.groupValues[6])
            edges.add(
                MermaidEdge(
                    from = left.id,
                    to = right.id,
                    label = label,
                    style = if (lineKind == "..") MermaidEdgeStyle.DASHED_LINE else MermaidEdgeStyle.SOLID_LINE,
                    fromMultiplicity = leftCard,
                    toMultiplicity = rightCard,
                ),
            )
        }
    }

    if (entities.isEmpty()) {
        return MermaidDiagram.Unsupported("未解析到关系图实体")
    }

    return MermaidDiagram.Graph(
        kind = MermaidDiagramKind.ER,
        direction = direction,
        nodes = entities.values.map {
            MermaidNode(
                id = it.id,
                label = it.label,
                shape = MermaidNodeShape.CARD,
                members = it.members.toList(),
            )
        },
        edges = edges,
    )
}

// ---------------------------------------------------------------------------
// 5. Pie Chart Parser
// ---------------------------------------------------------------------------

private fun parsePieChart(header: String, bodyLines: List<String>): MermaidDiagram {
    var showData = header.contains("showData", ignoreCase = true)
    var title = ""
    val headerTitleIdx = header.indexOf("title", ignoreCase = true)
    if (headerTitleIdx >= 0) {
        title = normalizeMermaidLabel(header.substring(headerTitleIdx + 5))
    }

    val rawSlices = mutableListOf<Pair<String, Double>>()
    for (rawLine in bodyLines) {
        val line = rawLine.trim()
        if (line.isEmpty()) continue
        val lower = line.lowercase()
        when {
            lower == "showdata" -> showData = true
            lower.startsWith("title ") -> title = normalizeMermaidLabel(line.drop(6))
            else -> {
                val colonIdx = line.lastIndexOf(':')
                if (colonIdx > 0) {
                    val label = normalizeMermaidLabel(line.substring(0, colonIdx))
                    val value = line.substring(colonIdx + 1).trim().toDoubleOrNull()
                    if (label.isNotEmpty() && value != null && value.isFinite() && value > 0.0) {
                        rawSlices.add(label to value)
                    }
                }
            }
        }
    }

    if (rawSlices.isEmpty()) {
        return MermaidDiagram.Unsupported("未解析到饼图数据项")
    }

    val total = rawSlices.sumOf { it.second }.coerceAtLeast(1e-9)
    val slices = rawSlices.map { (label, value) ->
        MermaidPieSlice(label = label, value = value, fraction = value / total)
    }

    return MermaidDiagram.Pie(title = title, showData = showData, slices = slices)
}

// ---------------------------------------------------------------------------
// Deterministic Cycle-Safe Layered Layout Engine (Shared by Flowchart/State/Class/ER)
// ---------------------------------------------------------------------------

internal data class LayoutRect(
    val left: Float,
    val top: Float,
    val width: Float,
    val height: Float,
) {
    val right: Float get() = left + width
    val bottom: Float get() = top + height
    val centerX: Float get() = left + width * 0.5f
    val centerY: Float get() = top + height * 0.5f
}

internal data class PositionedSubgraph(
    val subgraph: MermaidSubgraph,
    val rect: LayoutRect,
)

internal data class GraphLayoutResult(
    val width: Float,
    val height: Float,
    val nodeRects: Map<String, LayoutRect>,
    val nodeLayers: Map<String, Int>,
    val subgraphs: List<PositionedSubgraph>,
    val backEdgeIndices: Set<Int>,
)

internal fun estimateNodeSizeDp(node: MermaidNode): Pair<Float, Float> {
    fun estimateTextLines(text: String, charWidth: Float, lineHeight: Float): Pair<Float, Float> {
        val lines = text.split('\n').ifEmpty { listOf("") }
        val maxLineW = lines.maxOf { line ->
            var w = 0f
            for (ch in line) {
                w += if (ch.code > 0x7F) charWidth * 1.75f else charWidth
            }
            w
        }
        return maxLineW to (lines.size * lineHeight)
    }

    return when (node.shape) {
        MermaidNodeShape.STATE_START -> 24f to 24f
        MermaidNodeShape.STATE_END -> 28f to 28f
        MermaidNodeShape.CARD -> {
            val (titleW, titleH) = estimateTextLines(node.label, 8.2f, 18f)
            val subH = if (node.subtitle != null) 15f else 0f
            val subW = if (node.subtitle != null) estimateTextLines(node.subtitle, 6.8f, 14f).first else 0f
            val memberW = node.members.maxOfOrNull { estimateTextLines(it, 7.0f, 16f).first } ?: 0f
            val bodyH = if (node.members.isNotEmpty()) node.members.size * 17f + 16f else 0f
            val w = max(max(titleW, max(subW, memberW)) + 28f, 124f).coerceAtMost(290f)
            val h = titleH + subH + 18f + bodyH
            w to h
        }
        MermaidNodeShape.DIAMOND -> {
            val (tw, th) = estimateTextLines(node.label, 7.6f, 17f)
            max(tw * 1.62f + 42f, 96f) to max(th * 1.62f + 38f, 66f)
        }
        MermaidNodeShape.CIRCLE -> {
            val (tw, th) = estimateTextLines(node.label, 7.6f, 17f)
            val d = max(max(tw, th) + 28f, 54f)
            d to d
        }
        MermaidNodeShape.CYLINDER -> {
            val (tw, th) = estimateTextLines(node.label, 7.6f, 17f)
            max(tw + 32f, 94f) to max(th + 34f, 52f)
        }
        MermaidNodeShape.SUBROUTINE -> {
            val (tw, th) = estimateTextLines(node.label, 7.6f, 17f)
            max(tw + 42f, 98f) to max(th + 24f, 44f)
        }
        MermaidNodeShape.RECT,
        MermaidNodeShape.ROUNDED,
        MermaidNodeShape.STADIUM,
        -> {
            val (tw, th) = estimateTextLines(node.label, 7.6f, 17f)
            max(tw + 30f, 84f) to max(th + 22f, 42f)
        }
    }
}

internal fun layoutGraphDiagram(
    diagram: MermaidDiagram.Graph,
    measureNodeSize: (MermaidNode) -> Pair<Float, Float> = ::estimateNodeSizeDp,
): GraphLayoutResult {
    if (diagram.nodes.isEmpty()) {
        return GraphLayoutResult(120f, 80f, emptyMap(), emptyMap(), emptyList(), emptySet())
    }

    val nodeIds = diagram.nodes.map { it.id }
    val nodeOrder = nodeIds.withIndex().associate { it.value to it.index }
    val nodeById = diagram.nodes.associateBy { it.id }

    // 1. Cycle breaking via 3-color DFS (guarantees acyclic DAG for layering!)
    val outAdj = LinkedHashMap<String, MutableList<Pair<Int, String>>>()
    val rawInDeg = LinkedHashMap<String, Int>()
    for (id in nodeIds) {
        outAdj[id] = mutableListOf()
        rawInDeg[id] = 0
    }
    diagram.edges.forEachIndexed { idx, edge ->
        if (edge.from != edge.to && edge.from in nodeById && edge.to in nodeById) {
            outAdj[edge.from]?.add(idx to edge.to)
            rawInDeg[edge.to] = (rawInDeg[edge.to] ?: 0) + 1
        }
    }

    val color = HashMap<String, Int>(nodeIds.size) // 0=unvisited, 1=visiting, 2=visited
    val backEdgeIndices = HashSet<Int>()

    fun dfsBreakCycles(u: String) {
        color[u] = 1
        for ((edgeIdx, v) in outAdj[u].orEmpty()) {
            when (color[v] ?: 0) {
                1 -> backEdgeIndices.add(edgeIdx)
                0 -> dfsBreakCycles(v)
            }
        }
        color[u] = 2
    }

    // Visit zero-indegree roots first, then remaining nodes in declaration order
    for (id in nodeIds.filter { (rawInDeg[it] ?: 0) == 0 }) {
        if ((color[id] ?: 0) == 0) dfsBreakCycles(id)
    }
    for (id in nodeIds) {
        if ((color[id] ?: 0) == 0) dfsBreakCycles(id)
    }

    // 2. Kahn topological longest-path layering on the cycle-free DAG
    val dagOut = LinkedHashMap<String, MutableList<String>>()
    val dagIn = LinkedHashMap<String, MutableList<String>>()
    val inDeg = LinkedHashMap<String, Int>()
    for (id in nodeIds) {
        dagOut[id] = mutableListOf()
        dagIn[id] = mutableListOf()
        inDeg[id] = 0
    }
    diagram.edges.forEachIndexed { idx, edge ->
        if (idx !in backEdgeIndices && edge.from != edge.to && edge.from in nodeById && edge.to in nodeById) {
            dagOut[edge.from]?.add(edge.to)
            dagIn[edge.to]?.add(edge.from)
            inDeg[edge.to] = (inDeg[edge.to] ?: 0) + 1
        }
    }

    val layers = HashMap<String, Int>(nodeIds.size)
    for (id in nodeIds) layers[id] = 0
    val queue = ArrayDeque<String>()
    for (id in nodeIds) {
        if ((inDeg[id] ?: 0) == 0) queue.addLast(id)
    }
    while (queue.isNotEmpty()) {
        val u = queue.removeFirst()
        val uLayer = layers[u] ?: 0
        for (v in dagOut[u].orEmpty()) {
            val candidate = uLayer + 1
            if (candidate > (layers[v] ?: 0)) {
                layers[v] = candidate
            }
            val nextDeg = (inDeg[v] ?: 1) - 1
            inDeg[v] = nextDeg
            if (nextDeg == 0) queue.addLast(v)
        }
    }

    val maxLayer = layers.values.maxOrNull() ?: 0
    // Place terminal STATE_END node on the final layer when it has no outgoing edges
    for (node in diagram.nodes) {
        if (node.shape == MermaidNodeShape.STATE_END && dagOut[node.id].isNullOrEmpty() && maxLayer > 0) {
            layers[node.id] = maxLayer
        }
    }

    // 3. Group into layers and reduce crossings via 2-pass barycenter heuristic (respecting subgraphs)
    val deepestSubgraphOf = HashMap<String, String>()
    for (sg in diagram.subgraphs.sortedBy { it.depth }) {
        for (nid in sg.nodeIds) deepestSubgraphOf[nid] = sg.id
    }

    val layerBuckets = MutableList(maxLayer + 1) { mutableListOf<String>() }
    for (id in nodeIds) {
        val l = (layers[id] ?: 0).coerceIn(0, maxLayer)
        layerBuckets[l].add(id)
    }

    // Initial ordering grouped by subgraph then declaration order
    val sgOrder = diagram.subgraphs.withIndex().associate { it.value.id to it.index }
    for (bucket in layerBuckets) {
        bucket.sortWith(
            compareBy<String>(
                { deepestSubgraphOf[it]?.let { sid -> sgOrder[sid] } ?: Int.MAX_VALUE },
                { nodeOrder[it] ?: 0 },
            ),
        )
    }

    fun sortLayerByBarycenter(layerIdx: Int, neighborMap: Map<String, List<String>>, refIndex: Map<String, Int>) {
        val bucket = layerBuckets[layerIdx]
        if (bucket.size <= 1) return
        val bary = HashMap<String, Float>(bucket.size)
        for ((posIdx, id) in bucket.withIndex()) {
            val refs = neighborMap[id].orEmpty().mapNotNull { refIndex[it] }
            bary[id] = if (refs.isNotEmpty()) refs.average().toFloat() else posIdx.toFloat()
        }
        // Keep nodes inside the same subgraph contiguous
        val sgAvg = bucket.groupBy { deepestSubgraphOf[it] }
            .mapValues { (sgKey, members) ->
                if (sgKey == null) 0f else members.mapNotNull { bary[it] }.average().toFloat()
            }
        bucket.sortWith(
            compareBy<String>(
                {
                    val sgKey = deepestSubgraphOf[it]
                    if (sgKey != null) sgAvg[sgKey] ?: 0f else bary[it] ?: 0f
                },
                { deepestSubgraphOf[it] ?: "" },
                { bary[it] ?: 0f },
                { nodeOrder[it] ?: 0 },
            ),
        )
    }

    repeat(2) {
        for (l in 1..maxLayer) {
            val prevPos = layerBuckets[l - 1].withIndex().associate { it.value to it.index }
            sortLayerByBarycenter(l, dagIn, prevPos)
        }
        for (l in (maxLayer - 1) downTo 0) {
            val nextPos = layerBuckets[l + 1].withIndex().associate { it.value to it.index }
            sortLayerByBarycenter(l, dagOut, nextPos)
        }
    }

    // 4. Measure nodes and assign coordinates
    val sizes = diagram.nodes.associate { it.id to measureNodeSize(it) }
    val isHorizontal = diagram.direction == MermaidDirection.LR || diagram.direction == MermaidDirection.RL
    val isReversed = diagram.direction == MermaidDirection.BT || diagram.direction == MermaidDirection.RL

    val hasEdgeLabels = diagram.edges.any { it.label.isNotBlank() }
    val layerGap = if (hasEdgeLabels) 66f else 50f
    val siblingGap = 28f
    val crossSubgraphExtraGap = 24f

    val layerPrimaryThickness = FloatArray(maxLayer + 1) { l ->
        layerBuckets[l].maxOfOrNull { id ->
            val (w, h) = sizes[id] ?: (84f to 42f)
            if (isHorizontal) w else h
        } ?: 42f
    }

    val layerCrossSpans = FloatArray(maxLayer + 1) { l ->
        val bucket = layerBuckets[l]
        if (bucket.isEmpty()) {
            0f
        } else {
            var span = 0f
            bucket.forEachIndexed { idx, id ->
                val (w, h) = sizes[id] ?: (84f to 42f)
                span += if (isHorizontal) h else w
                if (idx > 0) {
                    val prevSg = deepestSubgraphOf[bucket[idx - 1]]
                    val curSg = deepestSubgraphOf[id]
                    span += siblingGap + if (prevSg != curSg) crossSubgraphExtraGap else 0f
                }
            }
            span
        }
    }

    val maxCrossSpan = layerCrossSpans.maxOrNull() ?: 120f
    val rawNodeRects = LinkedHashMap<String, LayoutRect>(diagram.nodes.size)

    var primaryCursor = 0f
    val orderedLayerIndices = if (isReversed) (maxLayer downTo 0).toList() else (0..maxLayer).toList()
    for (l in orderedLayerIndices) {
        val bucket = layerBuckets[l]
        val thickness = layerPrimaryThickness[l]
        var crossCursor = (maxCrossSpan - layerCrossSpans[l]) * 0.5f

        bucket.forEachIndexed { idx, id ->
            if (idx > 0) {
                val prevSg = deepestSubgraphOf[bucket[idx - 1]]
                val curSg = deepestSubgraphOf[id]
                crossCursor += siblingGap + if (prevSg != curSg) crossSubgraphExtraGap else 0f
            }
            val (w, h) = sizes[id] ?: (84f to 42f)
            val rect = if (isHorizontal) {
                LayoutRect(
                    left = primaryCursor + (thickness - w) * 0.5f,
                    top = crossCursor,
                    width = w,
                    height = h,
                )
            } else {
                LayoutRect(
                    left = crossCursor,
                    top = primaryCursor + (thickness - h) * 0.5f,
                    width = w,
                    height = h,
                )
            }
            rawNodeRects[id] = rect
            crossCursor += if (isHorizontal) h else w
        }
        primaryCursor += thickness + layerGap
    }

    // 5. Compute subgraph bounding boxes (innermost first so outer subgraphs wrap inner ones cleanly)
    val maxDepth = diagram.subgraphs.maxOfOrNull { it.depth } ?: 0
    val rawSubgraphs = mutableListOf<PositionedSubgraph>()
    for (sg in diagram.subgraphs.sortedBy { it.depth }) {
        val memberRects = sg.nodeIds.mapNotNull { rawNodeRects[it] }
        if (memberRects.isEmpty()) continue
        val extraNest = (maxDepth - sg.depth) * 10f
        val padSide = 16f + extraNest
        val padTop = 32f + extraNest
        val padBottom = 16f + extraNest
        val minL = memberRects.minOf { it.left } - padSide
        val minT = memberRects.minOf { it.top } - padTop
        val maxR = memberRects.maxOf { it.right } + padSide
        val maxB = memberRects.maxOf { it.bottom } + padBottom
        rawSubgraphs.add(
            PositionedSubgraph(
                subgraph = sg,
                rect = LayoutRect(minL, minT, maxR - minL, maxB - minT),
            ),
        )
    }

    // 6. Normalize coordinates with generous outer padding (+ extra clearance for self-loops & back-edges)
    val hasSelfLoop = diagram.edges.any { it.from == it.to }
    val hasBackEdge = backEdgeIndices.isNotEmpty()
    val extraRight = when {
        hasSelfLoop || (!isHorizontal && hasBackEdge) -> 56f
        else -> 0f
    }
    val extraBottom = if (isHorizontal && (hasBackEdge || hasSelfLoop)) 56f else 0f

    val allRects = rawNodeRects.values + rawSubgraphs.map { it.rect }
    val minX = allRects.minOf { it.left }
    val minY = allRects.minOf { it.top }
    val maxX = allRects.maxOf { it.right } + extraRight
    val maxY = allRects.maxOf { it.bottom } + extraBottom

    val outerPad = 24f
    val shiftX = outerPad - minX
    val shiftY = outerPad - minY

    val finalNodes = rawNodeRects.mapValues { (_, r) ->
        r.copy(left = r.left + shiftX, top = r.top + shiftY)
    }
    val finalSubgraphs = rawSubgraphs.map { psg ->
        psg.copy(rect = psg.rect.copy(left = psg.rect.left + shiftX, top = psg.rect.top + shiftY))
    }

    return GraphLayoutResult(
        width = max(maxX - minX + outerPad * 2f, 140f),
        height = max(maxY - minY + outerPad * 2f, 96f),
        nodeRects = finalNodes,
        nodeLayers = layers,
        subgraphs = finalSubgraphs,
        backEdgeIndices = backEdgeIndices,
    )
}

// ---------------------------------------------------------------------------
// Sequence Diagram Layout Engine
// ---------------------------------------------------------------------------

internal data class SeqBlockFrame(
    val kind: String,
    val label: String,
    val topY: Float,
    val bottomY: Float,
    val depth: Int,
    val dividers: List<Pair<Float, String>>,
)

internal sealed interface PositionedSeqElement {
    data class MessageRow(
        val item: MermaidSeqItem.Message,
        val y: Float,
        val fromX: Float,
        val toX: Float,
    ) : PositionedSeqElement

    data class NoteBox(
        val item: MermaidSeqItem.Note,
        val rect: LayoutRect,
    ) : PositionedSeqElement
}

internal data class SequenceLayoutResult(
    val width: Float,
    val height: Float,
    val headerTopY: Float,
    val headerHeight: Float,
    val lifelineBottomY: Float,
    val showBottomHeaders: Boolean,
    val participantX: Map<String, Float>,
    val participantWidths: Map<String, Float>,
    val elements: List<PositionedSeqElement>,
    val blocks: List<SeqBlockFrame>,
)

internal fun layoutSequenceDiagram(
    diagram: MermaidDiagram.Sequence,
    measureText: (String) -> Pair<Float, Float> = { s ->
        val lines = s.split('\n')
        val w = lines.maxOf { line ->
            line.sumOf { ch -> if (ch.code > 0x7F) 13 else 7 }.toFloat()
        }
        w to (lines.size * 16f)
    },
): SequenceLayoutResult {
    val participants = diagram.participants
    if (participants.isEmpty()) {
        return SequenceLayoutResult(140f, 96f, 20f, 38f, 70f, false, emptyMap(), emptyMap(), emptyList(), emptyList())
    }

    val pIndex = participants.withIndex().associate { it.value.id to it.index }
    val hasActor = participants.any { it.isActor }
    val headerHeight = if (hasActor) 56f else 38f

    val pWidths = participants.associate { p ->
        val (tw, _) = measureText(p.label)
        p.id to max(tw + 26f, 96f)
    }

    // Compute horizontal column gaps so message labels never crowd adjacent lifelines
    val colGaps = FloatArray(max(participants.size - 1, 0)) { idx ->
        val wLeft = pWidths[participants[idx].id] ?: 96f
        val wRight = pWidths[participants[idx + 1].id] ?: 96f
        max((wLeft + wRight) * 0.5f + 26f, 132f)
    }

    var extraLeftPad = 24f
    var extraRightPad = 24f

    for (item in diagram.items) {
        when (item) {
            is MermaidSeqItem.Message -> {
                val i = pIndex[item.from] ?: continue
                val j = pIndex[item.to] ?: continue
                val (lw, _) = measureText(item.text)
                if (i == j) {
                    if (i == participants.lastIndex) {
                        extraRightPad = max(extraRightPad, lw + 78f)
                    } else {
                        colGaps[i] = max(colGaps[i], lw + 72f)
                    }
                } else {
                    val lo = min(i, j)
                    val hi = max(i, j)
                    val currentSpan = (lo until hi).sumOf { colGaps[it].toDouble() }.toFloat()
                    val required = lw + 42f
                    if (required > currentSpan) {
                        val addPerGap = (required - currentSpan) / (hi - lo)
                        for (g in lo until hi) colGaps[g] += addPerGap
                    }
                }
            }
            is MermaidSeqItem.Note -> {
                val (nw, _) = measureText(item.text)
                val noteW = max(nw + 22f, 92f)
                val firstIdx = pIndex[item.participants.firstOrNull()] ?: continue
                val lastIdx = pIndex[item.participants.lastOrNull()] ?: firstIdx
                when (item.placement) {
                    SeqNotePlacement.LEFT_OF -> {
                        if (firstIdx == 0) {
                            extraLeftPad = max(extraLeftPad, noteW + 32f)
                        } else {
                            colGaps[firstIdx - 1] = max(colGaps[firstIdx - 1], noteW + 32f)
                        }
                    }
                    SeqNotePlacement.RIGHT_OF -> {
                        if (lastIdx == participants.lastIndex) {
                            extraRightPad = max(extraRightPad, noteW + 32f)
                        } else {
                            colGaps[lastIdx] = max(colGaps[lastIdx], noteW + 32f)
                        }
                    }
                    SeqNotePlacement.OVER -> {
                        if (firstIdx == lastIdx && firstIdx == 0) {
                            extraLeftPad = max(extraLeftPad, noteW * 0.5f + 16f)
                        }
                        if (firstIdx == lastIdx && lastIdx == participants.lastIndex) {
                            extraRightPad = max(extraRightPad, noteW * 0.5f + 16f)
                        }
                    }
                }
            }
            else -> Unit
        }
    }

    val pX = LinkedHashMap<String, Float>(participants.size)
    val firstHalfW = (pWidths[participants.first().id] ?: 96f) * 0.5f
    var cx = max(extraLeftPad, firstHalfW + 20f)
    participants.forEachIndexed { idx, p ->
        if (idx > 0) cx += colGaps[idx - 1]
        pX[p.id] = cx
    }
    val lastHalfW = (pWidths[participants.last().id] ?: 96f) * 0.5f
    val totalWidth = cx + max(extraRightPad, lastHalfW + 20f)

    // Vertical layout pass
    val headerTopY = 20f
    var curY = headerTopY + headerHeight + 24f

    data class OpenSeqBlock(
        val kind: String,
        val label: String,
        val topY: Float,
        val depth: Int,
        val dividers: MutableList<Pair<Float, String>> = mutableListOf(),
    )

    val openBlocks = mutableListOf<OpenSeqBlock>()
    val closedBlocks = mutableListOf<SeqBlockFrame>()
    val elements = mutableListOf<PositionedSeqElement>()

    for (item in diagram.items) {
        when (item) {
            is MermaidSeqItem.BlockStart -> {
                val b = OpenSeqBlock(
                    kind = item.kind,
                    label = item.label,
                    topY = curY,
                    depth = openBlocks.size,
                )
                openBlocks.add(b)
                curY += 32f
            }
            is MermaidSeqItem.BlockDivider -> {
                if (openBlocks.isNotEmpty()) {
                    openBlocks.last().dividers.add(curY to item.label)
                }
                curY += 28f
            }
            MermaidSeqItem.BlockEnd -> {
                if (openBlocks.isNotEmpty()) {
                    val top = openBlocks.removeAt(openBlocks.lastIndex)
                    closedBlocks.add(
                        SeqBlockFrame(
                            kind = top.kind,
                            label = top.label,
                            topY = top.topY,
                            bottomY = curY + 6f,
                            depth = top.depth,
                            dividers = top.dividers.toList(),
                        ),
                    )
                    curY += 20f
                }
            }
            is MermaidSeqItem.Message -> {
                val fx = pX[item.from] ?: continue
                val tx = pX[item.to] ?: continue
                val (_, lh) = measureText(item.text.ifBlank { " " })
                val isSelf = item.from == item.to
                val arrowY = curY + lh + 6f
                elements.add(PositionedSeqElement.MessageRow(item = item, y = arrowY, fromX = fx, toX = tx))
                curY = arrowY + if (isSelf) 38f else 22f
            }
            is MermaidSeqItem.Note -> {
                val (nw, nh) = measureText(item.text.ifBlank { " " })
                val noteW = max(nw + 22f, 92f)
                val noteH = max(nh + 16f, 32f)
                val xs = item.participants.mapNotNull { pX[it] }
                if (xs.isEmpty()) continue
                val rect = when (item.placement) {
                    SeqNotePlacement.LEFT_OF -> LayoutRect(xs.first() - noteW - 14f, curY, noteW, noteH)
                    SeqNotePlacement.RIGHT_OF -> LayoutRect(xs.last() + 14f, curY, noteW, noteH)
                    SeqNotePlacement.OVER -> {
                        val minX = xs.min()
                        val maxX = xs.max()
                        val spanW = max(maxX - minX + 44f, noteW)
                        val midX = (minX + maxX) * 0.5f
                        LayoutRect(midX - spanW * 0.5f, curY, spanW, noteH)
                    }
                }
                elements.add(PositionedSeqElement.NoteBox(item = item, rect = rect))
                curY += noteH + 18f
            }
        }
    }

    // Auto-close any unclosed blocks safely (streaming resilience)
    while (openBlocks.isNotEmpty()) {
        val top = openBlocks.removeAt(openBlocks.lastIndex)
        closedBlocks.add(
            SeqBlockFrame(
                kind = top.kind,
                label = top.label,
                topY = top.topY,
                bottomY = curY + 6f,
                depth = top.depth,
                dividers = top.dividers.toList(),
            ),
        )
        curY += 16f
    }

    val lifelineBottomY = curY + 8f
    val showBottomHeaders = elements.size >= 4
    val totalHeight = lifelineBottomY + (if (showBottomHeaders) headerHeight + 20f else 20f)
    val maxNoteRight = elements.filterIsInstance<PositionedSeqElement.NoteBox>()
        .maxOfOrNull { it.rect.right + 16f } ?: 0f

    return SequenceLayoutResult(
        width = max(max(totalWidth, maxNoteRight), 220f),
        height = max(totalHeight, 120f),
        headerTopY = headerTopY,
        headerHeight = headerHeight,
        lifelineBottomY = lifelineBottomY,
        showBottomHeaders = showBottomHeaders,
        participantX = pX,
        participantWidths = pWidths,
        elements = elements,
        blocks = closedBlocks.sortedBy { it.depth },
    )
}

// ---------------------------------------------------------------------------
// Formatting Helpers (Pure KMP)
// ---------------------------------------------------------------------------

internal fun formatMermaidPercent(fraction: Double): String {
    val tenths = (fraction * 1000.0).roundToInt()
    val whole = tenths / 10
    val dec = abs(tenths % 10)
    return if (dec == 0) "$whole%" else "$whole.$dec%"
}

internal fun formatMermaidNumber(value: Double): String {
    val hundredths = (value * 100.0).roundToInt()
    return when {
        hundredths % 100 == 0 -> (hundredths / 100).toString()
        hundredths % 10 == 0 -> "${hundredths / 100}.${abs((hundredths / 10) % 10)}"
        else -> {
            val dec = abs(hundredths % 100).toString().padStart(2, '0')
            "${hundredths / 100}.$dec"
        }
    }
}

// ---------------------------------------------------------------------------
// Mermaid Source Code Highlighter (Calm Everforest Palette)
// ---------------------------------------------------------------------------

private val MERMAID_KEYWORDS = setOf(
    "flowchart", "graph", "sequenceDiagram", "stateDiagram", "stateDiagram-v2",
    "classDiagram", "classDiagram-v2", "erDiagram", "pie", "showData",
    "subgraph", "end", "direction", "participant", "actor", "Note", "note",
    "loop", "alt", "else", "opt", "par", "and", "rect", "critical", "break",
    "state", "class", "title", "as", "over", "left", "right", "of",
    "TD", "TB", "LR", "RL", "BT",
)

internal fun highlightMermaidSource(code: String): AnnotatedString = buildAnnotatedString {
    val lines = code.split('\n')
    lines.forEachIndexed { lineIdx, line ->
        var i = 0
        val n = line.length
        while (i < n) {
            val c = line[i]
            if (c == '%' && i + 1 < n && line[i + 1] == '%') {
                withStyle(SpanStyle(color = Everforest.CommentGrey, fontStyle = FontStyle.Italic)) {
                    append(line.substring(i))
                }
                break
            }
            if (c == '"') {
                var end = i + 1
                while (end < n && line[end] != '"') {
                    if (line[end] == '\\' && end + 1 < n) end += 2 else end++
                }
                if (end < n) end++
                withStyle(SpanStyle(color = Everforest.SoftSage)) {
                    append(line.substring(i, end))
                }
                i = end
                continue
            }
            if (c == '-' || c == '=' || c == '>' || c == '<' || c == '|') {
                var end = i + 1
                while (end < n && line[end] in charArrayOf('-', '=', '>', '<', '.', '|', 'o', 'x')) {
                    if (line[end] == 'o' || line[end] == 'x') {
                        val next = line.getOrNull(end + 1)
                        if (next != null && next.isLetterOrDigit()) break
                    }
                    end++
                }
                withStyle(SpanStyle(color = Everforest.CommentGrey)) {
                    append(line.substring(i, end))
                }
                i = end
                continue
            }
            if (c.isLetter() || c == '_') {
                var end = i + 1
                while (end < n && (line[end].isLetterOrDigit() || line[end] == '_' || line[end] == '-')) end++
                val word = line.substring(i, end)
                val style = if (word in MERMAID_KEYWORDS) {
                    SpanStyle(color = Everforest.SoftSand, fontWeight = FontWeight.Medium)
                } else {
                    SpanStyle(color = Everforest.Fg)
                }
                withStyle(style) { append(word) }
                i = end
                continue
            }
            withStyle(SpanStyle(color = Everforest.Fg)) { append(c) }
            i++
        }
        if (lineIdx < lines.lastIndex) append('\n')
    }
}

// ---------------------------------------------------------------------------
// Compose UI: Mermaid Diagram Card + Fullscreen Interactive Viewer
// ---------------------------------------------------------------------------

@Composable
internal fun MermaidDiagramBlockView(
    code: String,
    modifier: Modifier = Modifier,
) {
    val diagram = remember(code) { parseMermaidDiagram(code) }
    val isSupported = diagram !is MermaidDiagram.Unsupported
    var showSource by remember(isSupported) { mutableStateOf(!isSupported) }
    var showFullscreen by remember { mutableStateOf(false) }
    var copies by remember(code) { mutableIntStateOf(0) }
    val copied = copies != 0
    LaunchedEffect(code, copies) {
        if (copied) {
            delay(1_600L)
            copies = 0
        }
    }

    val headerLabel = remember(diagram.kind, isSupported) {
        if (isSupported) "mermaid · ${diagram.kind.displayName}" else "mermaid"
    }

    Surface(
        shape = RoundedCornerShape(10.dp),
        color = Everforest.BgDim,
        border = BorderStroke(1.dp, Everforest.Bg2),
        modifier = modifier
            .fillMaxWidth()
            .testTag("mermaid_diagram_card"),
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // Card Header Bar (exclude chrome labels from parent SelectionContainer drags)
            DisableSelection {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Everforest.Bg1.copy(alpha = 0.55f))
                        .padding(horizontal = 10.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = headerLabel,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        color = Everforest.CommentGrey,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp),
                    ) {
                        if (isSupported) {
                            MermaidModeToggleChip(
                                showSource = showSource,
                                onSelectDiagram = { showSource = false },
                                onSelectSource = { showSource = true },
                            )

                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(3.dp),
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .clickable { showFullscreen = true }
                                    .padding(horizontal = 5.dp, vertical = 2.dp)
                                    .testTag("mermaid_btn_fullscreen"),
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.OpenInFull,
                                    contentDescription = "全屏查看",
                                    tint = Everforest.CommentGrey,
                                    modifier = Modifier.size(12.dp),
                                )
                                Text(
                                    text = "全屏查看",
                                    fontSize = 11.sp,
                                    color = Everforest.CommentGrey,
                                    maxLines = 1,
                                )
                            }
                        }

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(3.dp),
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .clickable {
                                    if (copyTextToClipboard(code)) {
                                        copies++
                                    }
                                }
                                .padding(horizontal = 5.dp, vertical = 2.dp)
                                .testTag("mermaid_btn_copy"),
                        ) {
                            Icon(
                                imageVector = if (copied) Icons.Rounded.Check else Icons.Rounded.ContentCopy,
                                contentDescription = "复制",
                                tint = if (copied) Everforest.SoftSage else Everforest.CommentGrey,
                                modifier = Modifier.size(12.dp),
                            )
                            Text(
                                text = if (copied) "已复制" else "复制",
                                fontSize = 11.sp,
                                color = if (copied) Everforest.SoftSage else Everforest.CommentGrey,
                                maxLines = 1,
                            )
                        }
                    }
                }
            }

            HorizontalDivider(color = Everforest.Bg2, thickness = 0.5.dp)

            if (!isSupported) {
                DisableSelection {
                    MermaidUnsupportedBanner()
                }
                MermaidSourceCodeView(code = code)
            } else if (showSource) {
                MermaidSourceCodeView(code = code)
            } else {
                DisableSelection {
                    BoxWithConstraints(
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("mermaid_canvas"),
                    ) {
                        val viewportWidth = maxWidth
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                        ) {
                            Box(
                                modifier = Modifier
                                    .widthIn(min = viewportWidth)
                                    .padding(horizontal = 8.dp, vertical = 10.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                MermaidDiagramRenderer(diagram = diagram, zoom = 1f)
                            }
                        }
                    }
                }
            }
        }
    }

    if (showFullscreen && isSupported) {
        // Detach from any enclosing message SelectionContainer: Dialog renders in a separate
        // window/root layout hierarchy, so inheriting a parent LocalSelectionRegistrar crashes
        // SelectionManager coordinate conversion when interacting with text inside the dialog.
        DisableSelection {
            MermaidFullscreenDialog(
                diagram = diagram,
                code = code,
                onDismiss = { showFullscreen = false },
            )
        }
    }
}

@Composable
private fun MermaidModeToggleChip(
    showSource: Boolean,
    onSelectDiagram: () -> Unit,
    onSelectSource: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = Everforest.Bg0,
        border = BorderStroke(0.75.dp, Everforest.Bg2),
    ) {
        Row(
            modifier = Modifier.padding(2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .background(if (!showSource) Everforest.Bg2 else Color.Transparent)
                    .clickable(onClick = onSelectDiagram)
                    .padding(horizontal = 6.dp, vertical = 1.5.dp)
                    .testTag("mermaid_tab_diagram"),
            ) {
                Text(
                    text = "图表",
                    fontSize = 10.5.sp,
                    fontWeight = if (!showSource) FontWeight.Medium else FontWeight.Normal,
                    color = if (!showSource) Everforest.BoldFg else Everforest.CommentGrey,
                    maxLines = 1,
                )
            }
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .background(if (showSource) Everforest.Bg2 else Color.Transparent)
                    .clickable(onClick = onSelectSource)
                    .padding(horizontal = 6.dp, vertical = 1.5.dp)
                    .testTag("mermaid_tab_source"),
            ) {
                Text(
                    text = "源码",
                    fontSize = 10.5.sp,
                    fontWeight = if (showSource) FontWeight.Medium else FontWeight.Normal,
                    color = if (showSource) Everforest.BoldFg else Everforest.CommentGrey,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun MermaidUnsupportedBanner() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Everforest.BgYellow.copy(alpha = 0.45f))
            .padding(horizontal = 12.dp, vertical = 7.dp)
            .testTag("mermaid_fallback_banner"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Icon(
            imageVector = Icons.Rounded.Info,
            contentDescription = null,
            tint = Everforest.SoftSand,
            modifier = Modifier.size(14.dp),
        )
        Text(
            text = "暂未支持的图表语法，已切换源码视图",
            fontSize = 12.sp,
            color = Everforest.SoftSand,
        )
    }
}

@Composable
private fun MermaidSourceCodeView(code: String) {
    val highlighted = remember(code) { highlightMermaidSource(code) }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 10.dp)
            .testTag("mermaid_source_view"),
    ) {
        Text(
            text = highlighted,
            fontSize = 12.5.sp,
            lineHeight = 19.sp,
            fontFamily = FontFamily.Monospace,
            color = Everforest.Fg,
        )
    }
}

@Composable
private fun MermaidFullscreenDialog(
    diagram: MermaidDiagram,
    code: String,
    onDismiss: () -> Unit,
) {
    var zoom by remember { mutableStateOf(1.0f) }
    var copies by remember(code) { mutableIntStateOf(0) }
    val copied = copies != 0
    LaunchedEffect(code, copies) {
        if (copied) {
            delay(1_600L)
            copies = 0
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            color = Everforest.BgDim,
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .testTag("mermaid_fullscreen_dialog"),
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // Fullscreen Top Bar
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Everforest.Bg1)
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = "全屏查看 · ${diagram.kind.displayName}",
                        fontSize = 13.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Everforest.BoldFg,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        // Zoom Controls: - | 100% | +
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = Everforest.Bg0,
                            border = BorderStroke(1.dp, Everforest.Bg3),
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(horizontal = 2.dp, vertical = 2.dp),
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(26.dp)
                                        .clip(RoundedCornerShape(6.dp))
                                        .clickable(enabled = zoom > 0.5f) {
                                            zoom = (zoom - 0.25f).coerceAtLeast(0.5f)
                                        }
                                        .testTag("mermaid_fullscreen_zoom_out"),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.Remove,
                                        contentDescription = "缩小",
                                        tint = if (zoom > 0.5f) Everforest.Fg else Everforest.CommentGrey.copy(alpha = 0.45f),
                                        modifier = Modifier.size(15.dp),
                                    )
                                }

                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(6.dp))
                                        .clickable { zoom = 1.0f }
                                        .padding(horizontal = 8.dp, vertical = 3.dp)
                                        .testTag("mermaid_fullscreen_zoom_reset"),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text(
                                        text = "${(zoom * 100).roundToInt()}%",
                                        fontSize = 11.5.sp,
                                        fontFamily = FontFamily.Monospace,
                                        color = Everforest.BoldFg,
                                    )
                                }

                                Box(
                                    modifier = Modifier
                                        .size(26.dp)
                                        .clip(RoundedCornerShape(6.dp))
                                        .clickable(enabled = zoom < 2.5f) {
                                            zoom = (zoom + 0.25f).coerceAtMost(2.5f)
                                        }
                                        .testTag("mermaid_fullscreen_zoom_in"),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.Add,
                                        contentDescription = "放大",
                                        tint = if (zoom < 2.5f) Everforest.Fg else Everforest.CommentGrey.copy(alpha = 0.45f),
                                        modifier = Modifier.size(15.dp),
                                    )
                                }
                            }
                        }

                        // Copy Button
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = Everforest.Bg0,
                            border = BorderStroke(1.dp, Everforest.Bg3),
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .clickable {
                                    if (copyTextToClipboard(code)) {
                                        copies++
                                    }
                                },
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                Icon(
                                    imageVector = if (copied) Icons.Rounded.Check else Icons.Rounded.ContentCopy,
                                    contentDescription = "复制",
                                    tint = if (copied) Everforest.SoftSage else Everforest.Fg,
                                    modifier = Modifier.size(13.dp),
                                )
                                Text(
                                    text = if (copied) "已复制" else "复制",
                                    fontSize = 11.5.sp,
                                    color = if (copied) Everforest.SoftSage else Everforest.Fg,
                                )
                            }
                        }

                        // Close Button
                        Surface(
                            shape = CircleShape,
                            color = Everforest.Bg0,
                            border = BorderStroke(1.dp, Everforest.Bg3),
                            modifier = Modifier
                                .size(30.dp)
                                .clip(CircleShape)
                                .clickable(onClick = onDismiss)
                                .testTag("mermaid_fullscreen_close"),
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Rounded.Close,
                                    contentDescription = "关闭全屏查看",
                                    tint = Everforest.Fg,
                                    modifier = Modifier.size(16.dp),
                                )
                            }
                        }
                    }
                }

                HorizontalDivider(color = Everforest.Bg3, thickness = 1.dp)

                // Bidirectional Scrollable Zoom Canvas
                BoxWithConstraints(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                ) {
                    val minW = maxWidth
                    val minH = maxHeight
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .horizontalScroll(rememberScrollState())
                            .verticalScroll(rememberScrollState()),
                    ) {
                        Box(
                            modifier = Modifier
                                .widthIn(min = minW)
                                .heightIn(min = minH)
                                .padding(20.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            MermaidDiagramRenderer(diagram = diagram, zoom = zoom)
                        }
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Native Canvas Renderers (Flowchart / State / Class / ER / Sequence / Pie)
// ---------------------------------------------------------------------------

@Composable
private fun MermaidDiagramRenderer(
    diagram: MermaidDiagram,
    zoom: Float,
) {
    when (diagram) {
        is MermaidDiagram.Graph -> MermaidGraphCanvas(diagram = diagram, zoom = zoom)
        is MermaidDiagram.Sequence -> MermaidSequenceCanvas(diagram = diagram, zoom = zoom)
        is MermaidDiagram.Pie -> MermaidPieView(diagram = diagram, zoom = zoom)
        is MermaidDiagram.Unsupported -> Unit
    }
}

@Composable
private fun MermaidGraphCanvas(
    diagram: MermaidDiagram.Graph,
    zoom: Float,
) {
    val textMeasurer = rememberTextMeasurer()
    val density = LocalDensity.current

    val nodeLabelStyle = TextStyle(
        color = Everforest.BoldFg,
        fontSize = 12.sp,
        lineHeight = 16.5.sp,
        fontWeight = FontWeight.Medium,
        textAlign = TextAlign.Center,
    )
    val cardTitleStyle = TextStyle(
        color = Everforest.BoldFg,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        fontWeight = FontWeight.SemiBold,
        textAlign = TextAlign.Center,
    )
    val cardSubStyle = TextStyle(
        color = Everforest.CommentGrey,
        fontSize = 10.sp,
        lineHeight = 13.sp,
        fontStyle = FontStyle.Italic,
        textAlign = TextAlign.Center,
    )
    val cardMemberStyle = TextStyle(
        color = Everforest.Fg,
        fontSize = 10.8.sp,
        lineHeight = 15.5.sp,
        fontFamily = FontFamily.Monospace,
        textAlign = TextAlign.Start,
    )
    val edgeLabelStyle = TextStyle(
        color = Everforest.Fg,
        fontSize = 10.5.sp,
        lineHeight = 14.sp,
        textAlign = TextAlign.Center,
    )
    val multStyle = TextStyle(
        color = Everforest.SoftSand,
        fontSize = 9.5.sp,
        fontFamily = FontFamily.Monospace,
    )
    val subgraphTitleStyle = TextStyle(
        color = Everforest.SoftSand,
        fontSize = 10.8.sp,
        fontWeight = FontWeight.Medium,
    )

    val layout = remember(diagram, density) {
        val maxNodeTextPx = with(density) { 210.dp.roundToPx() }
        val maxCardLinePx = with(density) { 250.dp.roundToPx() }
        layoutGraphDiagram(diagram) { node ->
            when (node.shape) {
                MermaidNodeShape.STATE_START -> 24f to 24f
                MermaidNodeShape.STATE_END -> 28f to 28f
                MermaidNodeShape.CARD -> {
                    val titleRes = textMeasurer.measure(
                        text = node.label,
                        style = cardTitleStyle,
                        constraints = Constraints(maxWidth = maxCardLinePx),
                    )
                    val subRes = node.subtitle?.let {
                        textMeasurer.measure(
                            text = it,
                            style = cardSubStyle,
                            constraints = Constraints(maxWidth = maxCardLinePx),
                        )
                    }
                    val memberRes = node.members.map { m ->
                        textMeasurer.measure(
                            text = m,
                            style = cardMemberStyle,
                            constraints = Constraints(maxWidth = maxCardLinePx),
                        )
                    }
                    with(density) {
                        val contentW = max(
                            titleRes.size.width.toDp().value,
                            max(
                                subRes?.size?.width?.toDp()?.value ?: 0f,
                                memberRes.maxOfOrNull { it.size.width.toDp().value } ?: 0f,
                            ),
                        )
                        val headerH = titleRes.size.height.toDp().value +
                            (subRes?.size?.height?.toDp()?.value?.plus(2f) ?: 0f) + 16f
                        val bodyH = if (memberRes.isNotEmpty()) {
                            memberRes.sumOf { it.size.height.toDp().value.toDouble() }.toFloat() +
                                (memberRes.size - 1) * 3f + 14f
                        } else {
                            0f
                        }
                        max(contentW + 26f, 124f) to (headerH + bodyH)
                    }
                }
                else -> {
                    val res = textMeasurer.measure(
                        text = node.label,
                        style = nodeLabelStyle,
                        constraints = Constraints(maxWidth = maxNodeTextPx),
                    )
                    with(density) {
                        val tw = res.size.width.toDp().value
                        val th = res.size.height.toDp().value
                        when (node.shape) {
                            MermaidNodeShape.DIAMOND -> max(tw * 1.62f + 42f, 96f) to max(th * 1.62f + 38f, 66f)
                            MermaidNodeShape.CIRCLE -> {
                                val d = max(max(tw, th) + 28f, 54f)
                                d to d
                            }
                            MermaidNodeShape.CYLINDER -> max(tw + 32f, 92f) to max(th + 34f, 52f)
                            MermaidNodeShape.SUBROUTINE -> max(tw + 42f, 96f) to max(th + 24f, 44f)
                            else -> max(tw + 30f, 84f) to max(th + 22f, 40f)
                        }
                    }
                }
            }
        }
    }

    val isHorizontal = diagram.direction == MermaidDirection.LR || diagram.direction == MermaidDirection.RL

    Canvas(
        modifier = Modifier.size(
            width = (layout.width * zoom).dp,
            height = (layout.height * zoom).dp,
        ),
    ) {
        withTransform({ scale(zoom, zoom, pivot = Offset.Zero) }) {
            // 1. Subgraphs behind nodes
            val dashEffect = PathEffect.dashPathEffect(floatArrayOf(5.dp.toPx(), 4.dp.toPx()), 0f)
            for (psg in layout.subgraphs) {
                val r = psg.rect.toPxRect(this)
                drawRoundRect(
                    color = Everforest.Bg0.copy(alpha = 0.62f),
                    topLeft = r.topLeft,
                    size = r.size,
                    cornerRadius = CornerRadius(10.dp.toPx()),
                )
                drawRoundRect(
                    color = Everforest.Bg4.copy(alpha = 0.85f),
                    topLeft = r.topLeft,
                    size = r.size,
                    cornerRadius = CornerRadius(10.dp.toPx()),
                    style = Stroke(width = 1.dp.toPx(), pathEffect = dashEffect),
                )
                val titleRes = textMeasurer.measure(
                    text = psg.subgraph.title,
                    style = subgraphTitleStyle,
                    constraints = Constraints(maxWidth = max(40, (r.width - 20.dp.toPx()).roundToInt())),
                )
                val badgePadH = 7.dp.toPx()
                val badgePadV = 2.5.dp.toPx()
                val badgeTopLeft = Offset(r.left + 8.dp.toPx(), r.top + 6.dp.toPx())
                drawRoundRect(
                    color = Everforest.Bg1.copy(alpha = 0.92f),
                    topLeft = badgeTopLeft,
                    size = Size(titleRes.size.width + badgePadH * 2f, titleRes.size.height + badgePadV * 2f),
                    cornerRadius = CornerRadius(5.dp.toPx()),
                )
                drawText(
                    textLayoutResult = titleRes,
                    topLeft = Offset(badgeTopLeft.x + badgePadH, badgeTopLeft.y + badgePadV),
                )
            }

            // 2. Edges + Arrowheads + Label Pills
            val outSlotCounts = HashMap<String, Int>()
            val inSlotCounts = HashMap<String, Int>()
            val outTotalCounts = HashMap<String, Int>()
            val inTotalCounts = HashMap<String, Int>()
            diagram.edges.forEach { e ->
                if (e.from != e.to) {
                    outTotalCounts[e.from] = (outTotalCounts[e.from] ?: 0) + 1
                    inTotalCounts[e.to] = (inTotalCounts[e.to] ?: 0) + 1
                }
            }

            val nodeById = diagram.nodes.associateBy { it.id }
            val deferredLabels = mutableListOf<Triple<Offset, String, TextLayoutResult>>()

            diagram.edges.forEachIndexed { edgeIdx, edge ->
                val fromRectDp = layout.nodeRects[edge.from] ?: return@forEachIndexed
                val toRectDp = layout.nodeRects[edge.to] ?: return@forEachIndexed
                val fromShape = nodeById[edge.from]?.shape ?: MermaidNodeShape.RECT
                val toShape = nodeById[edge.to]?.shape ?: MermaidNodeShape.RECT
                val fromRect = fromRectDp.toPxRect(this)
                val toRect = toRectDp.toPxRect(this)

                val strokeColor = when (edge.style) {
                    MermaidEdgeStyle.THICK_ARROW -> Everforest.SoftSand
                    MermaidEdgeStyle.DASHED_ARROW, MermaidEdgeStyle.DASHED_LINE -> Everforest.CommentGrey
                    else -> Everforest.Grey2
                }
                val strokeWidth = when (edge.style) {
                    MermaidEdgeStyle.THICK_ARROW -> 2.4.dp.toPx()
                    else -> 1.4.dp.toPx()
                }
                val pathEffect = when (edge.style) {
                    MermaidEdgeStyle.DASHED_ARROW, MermaidEdgeStyle.DASHED_LINE ->
                        PathEffect.dashPathEffect(floatArrayOf(5.dp.toPx(), 3.5.dp.toPx()), 0f)
                    else -> null
                }

                if (edge.from == edge.to) {
                    // Self-loop curve on right side
                    val loopOut = 32.dp.toPx()
                    val p0 = Offset(fromRect.right, fromRect.top + fromRect.height * 0.28f)
                    val p3 = Offset(fromRect.right, fromRect.top + fromRect.height * 0.72f)
                    val c1 = Offset(fromRect.right + loopOut, p0.y - 10.dp.toPx())
                    val c2 = Offset(fromRect.right + loopOut, p3.y + 10.dp.toPx())
                    val path = Path().apply {
                        moveTo(p0.x, p0.y)
                        cubicTo(c1.x, c1.y, c2.x, c2.y, p3.x, p3.y)
                    }
                    drawPath(
                        path = path,
                        color = strokeColor,
                        style = Stroke(width = strokeWidth, cap = StrokeCap.Round, pathEffect = pathEffect),
                    )
                    if (edge.style != MermaidEdgeStyle.SOLID_LINE && edge.style != MermaidEdgeStyle.DASHED_LINE) {
                        drawArrowhead(p3, Offset(p3.x - c2.x, p3.y - c2.y), strokeColor, 7.5.dp.toPx())
                    }
                    if (edge.label.isNotBlank()) {
                        val mid = evalCubic(p0, c1, c2, p3, 0.5f)
                        val lr = textMeasurer.measure(edge.label, edgeLabelStyle, constraints = Constraints(maxWidth = 140.dp.roundToPx()))
                        deferredLabels.add(Triple(mid, edge.label, lr))
                    }
                    return@forEachIndexed
                }

                val isBack = edgeIdx in layout.backEdgeIndices
                val outIdx = outSlotCounts[edge.from] ?: 0
                outSlotCounts[edge.from] = outIdx + 1
                val inIdx = inSlotCounts[edge.to] ?: 0
                inSlotCounts[edge.to] = inIdx + 1

                val outFrac = slotFraction(outIdx, outTotalCounts[edge.from] ?: 1)
                val inFrac = slotFraction(inIdx, inTotalCounts[edge.to] ?: 1)

                val p0: Offset
                val p3: Offset
                val c1: Offset
                val c2: Offset

                if (isBack) {
                    // Curve back-edges cleanly outside the flow lane
                    val arcOffset = 40.dp.toPx()
                    if (isHorizontal) {
                        p0 = Offset(fromRect.center.x, fromRect.bottom)
                        p3 = Offset(toRect.center.x, toRect.bottom)
                        val maxY = max(fromRect.bottom, toRect.bottom) + arcOffset
                        c1 = Offset(p0.x, maxY)
                        c2 = Offset(p3.x, maxY)
                    } else {
                        p0 = Offset(fromRect.right, fromRect.center.y)
                        p3 = Offset(toRect.right, toRect.center.y)
                        val maxX = max(fromRect.right, toRect.right) + arcOffset
                        c1 = Offset(maxX, p0.y)
                        c2 = Offset(maxX, p3.y)
                    }
                } else if (isHorizontal) {
                    val goingRight = toRect.center.x >= fromRect.center.x
                    p0 = anchorPointHorizontal(fromRect, fromShape, rightSide = goingRight, frac = outFrac)
                    p3 = anchorPointHorizontal(toRect, toShape, rightSide = !goingRight, frac = inFrac)
                    val dx = abs(p3.x - p0.x).coerceAtLeast(28.dp.toPx()) * 0.45f
                    val dir = if (goingRight) 1f else -1f
                    val layerSpan = abs((layout.nodeLayers[edge.to] ?: 0) - (layout.nodeLayers[edge.from] ?: 0))
                    val bowY = if (layerSpan > 1) {
                        val midY = (p0.y + p3.y) * 0.5f
                        val minX = min(p0.x, p3.x)
                        val maxX = max(p0.x, p3.x)
                        val blocker = layout.nodeRects.entries
                            .filter { it.key != edge.from && it.key != edge.to }
                            .map { it.value.toPxRect(this) }
                            .firstOrNull { r -> r.center.x in minX..maxX && midY in (r.top - 10.dp.toPx())..(r.bottom + 10.dp.toPx()) }
                        if (blocker != null) (blocker.bottom - midY + 26.dp.toPx()) else 0f
                    } else {
                        0f
                    }
                    c1 = Offset(p0.x + dx * dir, p0.y + bowY)
                    c2 = Offset(p3.x - dx * dir, p3.y + bowY)
                } else {
                    val goingDown = toRect.center.y >= fromRect.center.y
                    p0 = anchorPointVertical(fromRect, fromShape, bottomSide = goingDown, frac = outFrac)
                    p3 = anchorPointVertical(toRect, toShape, bottomSide = !goingDown, frac = inFrac)
                    val dy = abs(p3.y - p0.y).coerceAtLeast(28.dp.toPx()) * 0.45f
                    val dir = if (goingDown) 1f else -1f
                    val layerSpan = abs((layout.nodeLayers[edge.to] ?: 0) - (layout.nodeLayers[edge.from] ?: 0))
                    val bowX = if (layerSpan > 1) {
                        val midX = (p0.x + p3.x) * 0.5f
                        val minY = min(p0.y, p3.y)
                        val maxY = max(p0.y, p3.y)
                        val blocker = layout.nodeRects.entries
                            .filter { it.key != edge.from && it.key != edge.to }
                            .map { it.value.toPxRect(this) }
                            .firstOrNull { r -> r.center.y in minY..maxY && midX in (r.left - 10.dp.toPx())..(r.right + 10.dp.toPx()) }
                        if (blocker != null) (blocker.right - midX + 26.dp.toPx()) else 0f
                    } else {
                        0f
                    }
                    c1 = Offset(p0.x + bowX, p0.y + dy * dir)
                    c2 = Offset(p3.x + bowX, p3.y - dy * dir)
                }

                val path = Path().apply {
                    moveTo(p0.x, p0.y)
                    cubicTo(c1.x, c1.y, c2.x, c2.y, p3.x, p3.y)
                }
                drawPath(
                    path = path,
                    color = strokeColor,
                    style = Stroke(width = strokeWidth, cap = StrokeCap.Round, pathEffect = pathEffect),
                )

                val arrowSize = 8.dp.toPx()
                val drawEndArrow = when (edge.style) {
                    MermaidEdgeStyle.SOLID_ARROW,
                    MermaidEdgeStyle.DASHED_ARROW,
                    MermaidEdgeStyle.THICK_ARROW,
                    -> !edge.reverseArrow
                    MermaidEdgeStyle.BIDIRECTIONAL -> true
                    else -> false
                }
                val drawStartArrow = when (edge.style) {
                    MermaidEdgeStyle.SOLID_ARROW,
                    MermaidEdgeStyle.DASHED_ARROW,
                    MermaidEdgeStyle.THICK_ARROW,
                    -> edge.reverseArrow
                    MermaidEdgeStyle.BIDIRECTIONAL -> true
                    else -> false
                }
                if (drawEndArrow) {
                    val tangent = Offset(p3.x - c2.x, p3.y - c2.y).takeIf { hypot(it.x, it.y) > 0.5f }
                        ?: Offset(p3.x - p0.x, p3.y - p0.y)
                    drawArrowhead(p3, tangent, strokeColor, arrowSize)
                }
                if (drawStartArrow) {
                    val tangent = Offset(p0.x - c1.x, p0.y - c1.y).takeIf { hypot(it.x, it.y) > 0.5f }
                        ?: Offset(p0.x - p3.x, p0.y - p3.y)
                    drawArrowhead(p0, tangent, strokeColor, arrowSize)
                }

                // ER / Class multiplicities near endpoints
                if (edge.fromMultiplicity.isNotBlank()) {
                    val pt = evalCubic(p0, c1, c2, p3, 0.14f)
                    val mr = textMeasurer.measure(edge.fromMultiplicity, multStyle)
                    drawPillLabel(pt, mr, Everforest.BgDim.copy(alpha = 0.92f), Everforest.Bg3)
                }
                if (edge.toMultiplicity.isNotBlank()) {
                    val pt = evalCubic(p0, c1, c2, p3, 0.86f)
                    val mr = textMeasurer.measure(edge.toMultiplicity, multStyle)
                    drawPillLabel(pt, mr, Everforest.BgDim.copy(alpha = 0.92f), Everforest.Bg3)
                }

                if (edge.label.isNotBlank()) {
                    val mid = evalCubic(p0, c1, c2, p3, 0.5f)
                    val lr = textMeasurer.measure(
                        text = edge.label,
                        style = edgeLabelStyle,
                        constraints = Constraints(maxWidth = 150.dp.roundToPx()),
                    )
                    deferredLabels.add(Triple(mid, edge.label, lr))
                }
            }

            // 3. Nodes
            for (node in diagram.nodes) {
                val rectDp = layout.nodeRects[node.id] ?: continue
                val rect = rectDp.toPxRect(this)
                drawGraphNode(
                    node = node,
                    kind = diagram.kind,
                    rect = rect,
                    textMeasurer = textMeasurer,
                    labelStyle = nodeLabelStyle,
                    cardTitleStyle = cardTitleStyle,
                    cardSubStyle = cardSubStyle,
                    cardMemberStyle = cardMemberStyle,
                )
            }

            // 4. Edge label pills drawn on top so connectors never obscure text
            for ((pos, _, lr) in deferredLabels) {
                drawPillLabel(
                    center = pos,
                    textResult = lr,
                    fill = Everforest.Bg0.copy(alpha = 0.96f),
                    border = Everforest.Bg3,
                )
            }
        }
    }
}

private fun DrawScope.drawGraphNode(
    node: MermaidNode,
    kind: MermaidDiagramKind,
    rect: Rect,
    textMeasurer: TextMeasurer,
    labelStyle: TextStyle,
    cardTitleStyle: TextStyle,
    cardSubStyle: TextStyle,
    cardMemberStyle: TextStyle,
) {
    val borderStroke = Stroke(width = 1.3.dp.toPx(), join = StrokeJoin.Round)
    when (node.shape) {
        MermaidNodeShape.STATE_START -> {
            drawCircle(
                color = Everforest.SoftSage,
                radius = min(rect.width, rect.height) * 0.44f,
                center = rect.center,
            )
            return
        }
        MermaidNodeShape.STATE_END -> {
            val outerR = min(rect.width, rect.height) * 0.46f
            drawCircle(
                color = Everforest.BgDim,
                radius = outerR,
                center = rect.center,
            )
            drawCircle(
                color = Everforest.SoftSand,
                radius = outerR,
                center = rect.center,
                style = Stroke(width = 1.8.dp.toPx()),
            )
            drawCircle(
                color = Everforest.SoftSand,
                radius = outerR * 0.56f,
                center = rect.center,
            )
            return
        }
        MermaidNodeShape.CARD -> {
            val corner = CornerRadius(8.dp.toPx())
            val headerTint = if (kind == MermaidDiagramKind.ER) {
                Everforest.BgBlue.copy(alpha = 0.62f)
            } else {
                Everforest.BgGreen.copy(alpha = 0.55f)
            }
            val borderColor = if (kind == MermaidDiagramKind.ER) {
                Everforest.InlineCode.copy(alpha = 0.72f)
            } else {
                Everforest.SoftSage.copy(alpha = 0.68f)
            }
            drawRoundRect(color = Everforest.Bg1, topLeft = rect.topLeft, size = rect.size, cornerRadius = corner)

            val maxTextW = max(24, (rect.width - 20.dp.toPx()).roundToInt())
            val titleRes = textMeasurer.measure(node.label, cardTitleStyle, constraints = Constraints(maxWidth = maxTextW))
            val subRes = node.subtitle?.let {
                textMeasurer.measure(it, cardSubStyle, constraints = Constraints(maxWidth = maxTextW))
            }
            val headerH = titleRes.size.height + (subRes?.size?.height?.plus(2.dp.toPx()) ?: 0f) + 14.dp.toPx()

            drawRoundRect(
                color = headerTint,
                topLeft = rect.topLeft,
                size = Size(rect.width, min(headerH, rect.height)),
                cornerRadius = corner,
            )
            if (node.members.isNotEmpty() && headerH < rect.height) {
                // Square off bottom of header tint band
                drawRect(
                    color = headerTint,
                    topLeft = Offset(rect.left, rect.top + headerH - 6.dp.toPx()),
                    size = Size(rect.width, 6.dp.toPx()),
                )
                drawLine(
                    color = Everforest.Bg3,
                    start = Offset(rect.left, rect.top + headerH),
                    end = Offset(rect.right, rect.top + headerH),
                    strokeWidth = 1.dp.toPx(),
                )
            }
            drawRoundRect(
                color = borderColor,
                topLeft = rect.topLeft,
                size = rect.size,
                cornerRadius = corner,
                style = borderStroke,
            )

            var ty = rect.top + 7.dp.toPx()
            if (subRes != null) {
                drawText(subRes, topLeft = Offset(rect.center.x - subRes.size.width * 0.5f, ty))
                ty += subRes.size.height + 2.dp.toPx()
            }
            drawText(titleRes, topLeft = Offset(rect.center.x - titleRes.size.width * 0.5f, ty))

            var my = rect.top + headerH + 6.dp.toPx()
            for (member in node.members) {
                val mr = textMeasurer.measure(member, cardMemberStyle, constraints = Constraints(maxWidth = maxTextW))
                drawText(mr, topLeft = Offset(rect.left + 10.dp.toPx(), my))
                my += mr.size.height + 3.dp.toPx()
            }
            return
        }
        MermaidNodeShape.DIAMOND -> {
            val path = Path().apply {
                moveTo(rect.center.x, rect.top)
                lineTo(rect.right, rect.center.y)
                lineTo(rect.center.x, rect.bottom)
                lineTo(rect.left, rect.center.y)
                close()
            }
            drawPath(path, color = Everforest.BgYellow.copy(alpha = 0.72f), style = Fill)
            drawPath(path, color = Everforest.SoftSand.copy(alpha = 0.8f), style = borderStroke)
        }
        MermaidNodeShape.CIRCLE -> {
            val r = min(rect.width, rect.height) * 0.5f
            drawCircle(color = Everforest.BgGreen.copy(alpha = 0.65f), radius = r, center = rect.center)
            drawCircle(color = Everforest.SoftSage.copy(alpha = 0.8f), radius = r, center = rect.center, style = borderStroke)
        }
        MermaidNodeShape.CYLINDER -> {
            val capH = 12.dp.toPx()
            val bodyTop = rect.top + capH * 0.5f
            val bodyBottom = rect.bottom - capH * 0.5f
            val fill = Everforest.BgBlue.copy(alpha = 0.65f)
            val border = Everforest.InlineCode.copy(alpha = 0.82f)
            drawRect(
                color = fill,
                topLeft = Offset(rect.left, bodyTop),
                size = Size(rect.width, bodyBottom - bodyTop),
            )
            drawOval(
                color = fill,
                topLeft = Offset(rect.left, bodyBottom - capH * 0.5f),
                size = Size(rect.width, capH),
            )
            drawArc(
                color = border,
                startAngle = 0f,
                sweepAngle = 180f,
                useCenter = false,
                topLeft = Offset(rect.left, bodyBottom - capH * 0.5f),
                size = Size(rect.width, capH),
                style = borderStroke,
            )
            drawLine(border, Offset(rect.left, bodyTop), Offset(rect.left, bodyBottom), strokeWidth = borderStroke.width)
            drawLine(border, Offset(rect.right, bodyTop), Offset(rect.right, bodyBottom), strokeWidth = borderStroke.width)
            drawOval(
                color = Everforest.BgBlue.copy(alpha = 0.88f),
                topLeft = Offset(rect.left, rect.top),
                size = Size(rect.width, capH),
            )
            drawOval(
                color = border,
                topLeft = Offset(rect.left, rect.top),
                size = Size(rect.width, capH),
                style = borderStroke,
            )
        }
        MermaidNodeShape.SUBROUTINE -> {
            val corner = CornerRadius(6.dp.toPx())
            drawRoundRect(color = Everforest.Bg1, topLeft = rect.topLeft, size = rect.size, cornerRadius = corner)
            drawRoundRect(color = Everforest.InlineCode.copy(alpha = 0.72f), topLeft = rect.topLeft, size = rect.size, cornerRadius = corner, style = borderStroke)
            val inset = 8.dp.toPx()
            drawLine(Everforest.InlineCode.copy(alpha = 0.72f), Offset(rect.left + inset, rect.top), Offset(rect.left + inset, rect.bottom), strokeWidth = 1.1.dp.toPx())
            drawLine(Everforest.InlineCode.copy(alpha = 0.72f), Offset(rect.right - inset, rect.top), Offset(rect.right - inset, rect.bottom), strokeWidth = 1.1.dp.toPx())
        }
        MermaidNodeShape.STADIUM -> {
            val corner = CornerRadius(rect.height * 0.5f)
            drawRoundRect(color = Everforest.BgGreen.copy(alpha = 0.58f), topLeft = rect.topLeft, size = rect.size, cornerRadius = corner)
            drawRoundRect(color = Everforest.SoftSage.copy(alpha = 0.76f), topLeft = rect.topLeft, size = rect.size, cornerRadius = corner, style = borderStroke)
        }
        MermaidNodeShape.ROUNDED -> {
            val corner = CornerRadius(12.dp.toPx())
            drawRoundRect(color = Everforest.Bg1, topLeft = rect.topLeft, size = rect.size, cornerRadius = corner)
            drawRoundRect(color = Everforest.SoftSage.copy(alpha = 0.65f), topLeft = rect.topLeft, size = rect.size, cornerRadius = corner, style = borderStroke)
        }
        MermaidNodeShape.RECT -> {
            val corner = CornerRadius(7.dp.toPx())
            drawRoundRect(color = Everforest.Bg1, topLeft = rect.topLeft, size = rect.size, cornerRadius = corner)
            drawRoundRect(color = Everforest.Bg4, topLeft = rect.topLeft, size = rect.size, cornerRadius = corner, style = borderStroke)
        }
    }

    // Draw centered text label
    if (node.label.isNotBlank()) {
        val horizPad = when (node.shape) {
            MermaidNodeShape.DIAMOND -> rect.width * 0.24f
            MermaidNodeShape.SUBROUTINE -> 24.dp.toPx()
            else -> 14.dp.toPx()
        }
        val maxW = max(24, (rect.width - horizPad).roundToInt())
        val textRes = textMeasurer.measure(node.label, labelStyle, constraints = Constraints(maxWidth = maxW))
        val yAdjust = if (node.shape == MermaidNodeShape.CYLINDER) 3.dp.toPx() else 0f
        drawText(
            textLayoutResult = textRes,
            topLeft = Offset(
                x = rect.center.x - textRes.size.width * 0.5f,
                y = rect.center.y - textRes.size.height * 0.5f + yAdjust,
            ),
        )
    }
}

@Composable
private fun MermaidSequenceCanvas(
    diagram: MermaidDiagram.Sequence,
    zoom: Float,
) {
    val textMeasurer = rememberTextMeasurer()
    val density = LocalDensity.current

    val participantStyle = TextStyle(
        color = Everforest.BoldFg,
        fontSize = 12.sp,
        lineHeight = 15.5.sp,
        fontWeight = FontWeight.Medium,
        textAlign = TextAlign.Center,
    )
    val messageStyle = TextStyle(
        color = Everforest.Fg,
        fontSize = 11.sp,
        lineHeight = 14.5.sp,
        textAlign = TextAlign.Center,
    )
    val noteStyle = TextStyle(
        color = Everforest.Fg,
        fontSize = 10.8.sp,
        lineHeight = 14.5.sp,
        textAlign = TextAlign.Center,
    )
    val blockTagStyle = TextStyle(
        color = Everforest.SoftSand,
        fontSize = 10.sp,
        fontWeight = FontWeight.SemiBold,
        fontFamily = FontFamily.Monospace,
    )
    val blockLabelStyle = TextStyle(
        color = Everforest.Grey2,
        fontSize = 10.5.sp,
        fontStyle = FontStyle.Italic,
    )

    val layout = remember(diagram, density) {
        val maxTextPx = with(density) { 210.dp.roundToPx() }
        layoutSequenceDiagram(diagram) { text ->
            val res = textMeasurer.measure(text, messageStyle, constraints = Constraints(maxWidth = maxTextPx))
            with(density) { res.size.width.toDp().value to res.size.height.toDp().value }
        }
    }

    Canvas(
        modifier = Modifier.size(
            width = (layout.width * zoom).dp,
            height = (layout.height * zoom).dp,
        ),
    ) {
        withTransform({ scale(zoom, zoom, pivot = Offset.Zero) }) {
            val dashLifeline = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 4.dp.toPx()), 0f)
            val dashMessage = PathEffect.dashPathEffect(floatArrayOf(5.dp.toPx(), 3.5.dp.toPx()), 0f)

            // 1. Block frames (loop / alt / opt / par / rect)
            for (blk in layout.blocks) {
                val inset = (12f + blk.depth * 8f).dp.toPx()
                val left = inset
                val right = size.width / zoom - inset
                val top = blk.topY.dp.toPx()
                val bottom = blk.bottomY.dp.toPx()
                val rectW = max(40f, right - left)
                val rectH = max(24f, bottom - top)

                drawRoundRect(
                    color = Everforest.Bg1.copy(alpha = 0.34f),
                    topLeft = Offset(left, top),
                    size = Size(rectW, rectH),
                    cornerRadius = CornerRadius(6.dp.toPx()),
                )
                drawRoundRect(
                    color = Everforest.Bg4.copy(alpha = 0.8f),
                    topLeft = Offset(left, top),
                    size = Size(rectW, rectH),
                    cornerRadius = CornerRadius(6.dp.toPx()),
                    style = Stroke(width = 1.dp.toPx()),
                )

                // Corner tag badge
                val tagRes = textMeasurer.measure(blk.kind, blockTagStyle)
                val tagW = tagRes.size.width + 14.dp.toPx()
                val tagH = tagRes.size.height + 6.dp.toPx()
                drawRoundRect(
                    color = Everforest.Bg2,
                    topLeft = Offset(left, top),
                    size = Size(tagW, tagH),
                    cornerRadius = CornerRadius(4.dp.toPx()),
                )
                drawText(tagRes, topLeft = Offset(left + 7.dp.toPx(), top + 3.dp.toPx()))

                if (blk.label.isNotBlank()) {
                    val lblRes = textMeasurer.measure(
                        text = "[${blk.label}]",
                        style = blockLabelStyle,
                        constraints = Constraints(maxWidth = max(40, (rectW - tagW - 14.dp.toPx()).roundToInt())),
                    )
                    drawText(lblRes, topLeft = Offset(left + tagW + 8.dp.toPx(), top + 3.dp.toPx()))
                }

                // Dividers (`else`, `and`)
                for ((divYDp, divLabel) in blk.dividers) {
                    val dy = divYDp.dp.toPx()
                    drawLine(
                        color = Everforest.Bg4.copy(alpha = 0.85f),
                        start = Offset(left, dy),
                        end = Offset(left + rectW, dy),
                        strokeWidth = 1.dp.toPx(),
                        pathEffect = dashLifeline,
                    )
                    if (divLabel.isNotBlank()) {
                        val dlRes = textMeasurer.measure("[$divLabel]", blockLabelStyle)
                        drawText(dlRes, topLeft = Offset(left + 10.dp.toPx(), dy + 4.dp.toPx()))
                    }
                }
            }

            // 2. Vertical lifelines
            val boxH = min(layout.headerHeight, 38f)
            val boxInsetBottom = (layout.headerHeight - boxH) * 0.5f
            val lifelineEndPx = layout.lifelineBottomY.dp.toPx()
            for (p in diagram.participants) {
                val x = (layout.participantX[p.id] ?: continue).dp.toPx()
                val startYDp = if (p.isActor) {
                    layout.headerTopY + layout.headerHeight
                } else {
                    layout.headerTopY + layout.headerHeight - boxInsetBottom
                }
                val endYDp = if (!p.isActor && layout.showBottomHeaders) {
                    layout.lifelineBottomY + boxInsetBottom
                } else {
                    layout.lifelineBottomY
                }
                drawLine(
                    color = Everforest.Bg4,
                    start = Offset(x, startYDp.dp.toPx()),
                    end = Offset(x, endYDp.dp.toPx()),
                    strokeWidth = 1.2.dp.toPx(),
                    pathEffect = dashLifeline,
                )
            }

            // 3. Top (and optional bottom) participant headers
            fun drawParticipantHeader(p: MermaidSeqParticipant, topYDp: Float) {
                val cx = (layout.participantX[p.id] ?: return).dp.toPx()
                val bw = (layout.participantWidths[p.id] ?: 96f).dp.toPx()
                val bh = layout.headerHeight.dp.toPx()
                val top = topYDp.dp.toPx()
                val left = cx - bw * 0.5f

                if (p.isActor) {
                    // Minimalist Everforest actor figure
                    val headR = 5.5.dp.toPx()
                    val headCy = top + headR + 2.dp.toPx()
                    val stroke = Stroke(width = 1.5.dp.toPx(), cap = StrokeCap.Round)
                    drawCircle(Everforest.SoftSand, radius = headR, center = Offset(cx, headCy), style = stroke)
                    val neckY = headCy + headR
                    val hipY = neckY + 11.dp.toPx()
                    drawLine(Everforest.SoftSand, Offset(cx, neckY), Offset(cx, hipY), strokeWidth = stroke.width)
                    drawLine(Everforest.SoftSand, Offset(cx - 8.dp.toPx(), neckY + 4.dp.toPx()), Offset(cx + 8.dp.toPx(), neckY + 4.dp.toPx()), strokeWidth = stroke.width)
                    drawLine(Everforest.SoftSand, Offset(cx, hipY), Offset(cx - 6.dp.toPx(), hipY + 8.dp.toPx()), strokeWidth = stroke.width)
                    drawLine(Everforest.SoftSand, Offset(cx, hipY), Offset(cx + 6.dp.toPx(), hipY + 8.dp.toPx()), strokeWidth = stroke.width)

                    val lblRes = textMeasurer.measure(p.label, participantStyle, constraints = Constraints(maxWidth = bw.roundToInt()))
                    drawText(lblRes, topLeft = Offset(cx - lblRes.size.width * 0.5f, hipY + 10.dp.toPx()))
                } else {
                    val boxH = min(bh, 38.dp.toPx())
                    val boxTop = top + (bh - boxH) * 0.5f
                    val corner = CornerRadius(7.dp.toPx())
                    drawRoundRect(
                        color = Everforest.Bg1,
                        topLeft = Offset(left, boxTop),
                        size = Size(bw, boxH),
                        cornerRadius = corner,
                    )
                    drawRoundRect(
                        color = Everforest.InlineCode.copy(alpha = 0.72f),
                        topLeft = Offset(left, boxTop),
                        size = Size(bw, boxH),
                        cornerRadius = corner,
                        style = Stroke(width = 1.3.dp.toPx()),
                    )
                    val lblRes = textMeasurer.measure(
                        text = p.label,
                        style = participantStyle,
                        constraints = Constraints(maxWidth = max(24, (bw - 12.dp.toPx()).roundToInt())),
                    )
                    drawText(
                        lblRes,
                        topLeft = Offset(cx - lblRes.size.width * 0.5f, boxTop + (boxH - lblRes.size.height) * 0.5f),
                    )
                }
            }

            for (p in diagram.participants) {
                drawParticipantHeader(p, layout.headerTopY)
                if (layout.showBottomHeaders) {
                    drawParticipantHeader(p, layout.lifelineBottomY)
                }
            }

            // 4. Messages & Notes
            for (el in layout.elements) {
                when (el) {
                    is PositionedSeqElement.MessageRow -> {
                        val msg = el.item
                        val fx = el.fromX.dp.toPx()
                        val tx = el.toX.dp.toPx()
                        val y = el.y.dp.toPx()
                        val isDashed = msg.style == MermaidSeqArrowStyle.DASHED_ARROW || msg.style == MermaidSeqArrowStyle.DASHED_LINE
                        val color = when (msg.style) {
                            MermaidSeqArrowStyle.DASHED_ARROW, MermaidSeqArrowStyle.DASHED_LINE -> Everforest.InlineCode
                            MermaidSeqArrowStyle.CROSS -> Everforest.SoftRed
                            else -> Everforest.SoftSage
                        }
                        val sw = 1.5.dp.toPx()
                        val effect = if (isDashed) dashMessage else null

                        if (msg.from == msg.to) {
                            val loopW = 34.dp.toPx()
                            val loopH = 22.dp.toPx()
                            val path = Path().apply {
                                moveTo(fx, y)
                                lineTo(fx + loopW, y)
                                lineTo(fx + loopW, y + loopH)
                                lineTo(fx, y + loopH)
                            }
                            drawPath(
                                path = path,
                                color = color,
                                style = Stroke(width = sw, join = StrokeJoin.Round, pathEffect = effect),
                            )
                            drawArrowhead(Offset(fx, y + loopH), Offset(-1f, 0f), color, 7.5.dp.toPx())
                            if (msg.text.isNotBlank()) {
                                val tr = textMeasurer.measure(msg.text, messageStyle, constraints = Constraints(maxWidth = 190.dp.roundToPx()))
                                drawText(tr, topLeft = Offset(fx + 8.dp.toPx(), y - tr.size.height - 4.dp.toPx()))
                            }
                        } else {
                            drawLine(
                                color = color,
                                start = Offset(fx, y),
                                end = Offset(tx, y),
                                strokeWidth = sw,
                                cap = StrokeCap.Round,
                                pathEffect = effect,
                            )
                            val dirX = if (tx >= fx) 1f else -1f
                            when (msg.style) {
                                MermaidSeqArrowStyle.CROSS -> {
                                    val r = 4.5.dp.toPx()
                                    drawLine(color, Offset(tx - r, y - r), Offset(tx + r, y + r), strokeWidth = 1.6.dp.toPx())
                                    drawLine(color, Offset(tx - r, y + r), Offset(tx + r, y - r), strokeWidth = 1.6.dp.toPx())
                                }
                                MermaidSeqArrowStyle.SOLID_ARROW,
                                MermaidSeqArrowStyle.DASHED_ARROW,
                                MermaidSeqArrowStyle.ASYNC,
                                -> drawArrowhead(Offset(tx, y), Offset(dirX, 0f), color, 8.dp.toPx())
                                MermaidSeqArrowStyle.SOLID_LINE,
                                MermaidSeqArrowStyle.DASHED_LINE,
                                -> drawArrowhead(Offset(tx, y), Offset(dirX, 0f), color, 6.5.dp.toPx())
                            }
                            if (msg.text.isNotBlank()) {
                                val span = abs(tx - fx)
                                val tr = textMeasurer.measure(
                                    text = msg.text,
                                    style = messageStyle,
                                    constraints = Constraints(maxWidth = max(60, (span - 16.dp.toPx()).roundToInt())),
                                )
                                val midX = (fx + tx) * 0.5f
                                drawText(
                                    textLayoutResult = tr,
                                    topLeft = Offset(midX - tr.size.width * 0.5f, y - tr.size.height - 4.dp.toPx()),
                                )
                            }
                        }
                    }
                    is PositionedSeqElement.NoteBox -> {
                        val r = el.rect.toPxRect(this)
                        val fold = 8.dp.toPx()
                        val notePath = Path().apply {
                            moveTo(r.left, r.top)
                            lineTo(r.right - fold, r.top)
                            lineTo(r.right, r.top + fold)
                            lineTo(r.right, r.bottom)
                            lineTo(r.left, r.bottom)
                            close()
                        }
                        drawPath(notePath, color = Everforest.BgYellow.copy(alpha = 0.8f), style = Fill)
                        drawPath(notePath, color = Everforest.SoftSand.copy(alpha = 0.72f), style = Stroke(width = 1.1.dp.toPx(), join = StrokeJoin.Round))
                        val foldPath = Path().apply {
                            moveTo(r.right - fold, r.top)
                            lineTo(r.right - fold, r.top + fold)
                            lineTo(r.right, r.top + fold)
                        }
                        drawPath(foldPath, color = Everforest.SoftSand.copy(alpha = 0.72f), style = Stroke(width = 1.dp.toPx()))
                        if (el.item.text.isNotBlank()) {
                            val tr = textMeasurer.measure(
                                text = el.item.text,
                                style = noteStyle,
                                constraints = Constraints(maxWidth = max(32, (r.width - 14.dp.toPx()).roundToInt())),
                            )
                            drawText(
                                textLayoutResult = tr,
                                topLeft = Offset(r.center.x - tr.size.width * 0.5f, r.center.y - tr.size.height * 0.5f),
                            )
                        }
                    }
                }
            }
        }
    }
}

private val PIE_EVERFOREST_COLORS = listOf(
    Everforest.SoftSage,
    Everforest.InlineCode,
    Everforest.SoftSand,
    Everforest.Purple,
    Everforest.Orange,
    Everforest.Blue,
    Everforest.SoftRed,
    Everforest.Aqua,
)

@Composable
private fun MermaidPieView(
    diagram: MermaidDiagram.Pie,
    zoom: Float,
) {
    val diameter = (156f * zoom).dp
    Column(
        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (diagram.title.isNotBlank()) {
            Text(
                text = diagram.title,
                fontSize = (13f * zoom.coerceAtMost(1.5f)).sp,
                fontWeight = FontWeight.SemiBold,
                color = Everforest.BoldFg,
            )
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy((22f * zoom.coerceAtMost(1.4f)).dp),
        ) {
            Canvas(modifier = Modifier.size(diameter)) {
                val pad = 4.dp.toPx()
                val arcSize = Size(size.width - pad * 2f, size.height - pad * 2f)
                val topLeft = Offset(pad, pad)
                var startAngle = -90f
                diagram.slices.forEachIndexed { idx, slice ->
                    val sweep = (slice.fraction * 360.0).toFloat()
                    val color = PIE_EVERFOREST_COLORS[idx % PIE_EVERFOREST_COLORS.size]
                    drawArc(
                        color = color,
                        startAngle = startAngle,
                        sweepAngle = sweep,
                        useCenter = true,
                        topLeft = topLeft,
                        size = arcSize,
                    )
                    // Subtle separator line between slices
                    if (diagram.slices.size > 1) {
                        val rad = startAngle * PI / 180.0
                        val r = arcSize.width * 0.5f
                        drawLine(
                            color = Everforest.BgDim,
                            start = center,
                            end = Offset(center.x + (cos(rad) * r).toFloat(), center.y + (sin(rad) * r).toFloat()),
                            strokeWidth = 1.5.dp.toPx(),
                        )
                    }
                    startAngle += sweep
                }
                // Donut center cutout for refined Everforest aesthetics
                drawCircle(
                    color = Everforest.BgDim,
                    radius = arcSize.width * 0.25f,
                    center = center,
                )
                drawCircle(
                    color = Everforest.Bg2,
                    radius = arcSize.width * 0.25f,
                    center = center,
                    style = Stroke(width = 1.dp.toPx()),
                )
            }

            Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                diagram.slices.forEachIndexed { idx, slice ->
                    val swatch = PIE_EVERFOREST_COLORS[idx % PIE_EVERFOREST_COLORS.size]
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Box(
                            modifier = Modifier
                                .size(10.dp)
                                .clip(RoundedCornerShape(3.dp))
                                .background(swatch),
                        )
                        Text(
                            text = slice.label,
                            fontSize = (12f * zoom.coerceAtMost(1.4f)).sp,
                            color = Everforest.Fg,
                        )
                        val detail = if (diagram.showData) {
                            "${formatMermaidNumber(slice.value)} (${formatMermaidPercent(slice.fraction)})"
                        } else {
                            formatMermaidPercent(slice.fraction)
                        }
                        Text(
                            text = detail,
                            fontSize = (11.5f * zoom.coerceAtMost(1.4f)).sp,
                            fontFamily = FontFamily.Monospace,
                            color = Everforest.CommentGrey,
                        )
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Canvas Geometry Helpers
// ---------------------------------------------------------------------------

private fun LayoutRect.toPxRect(scope: DrawScope): Rect = with(scope) {
    Rect(
        left = left.dp.toPx(),
        top = top.dp.toPx(),
        right = right.dp.toPx(),
        bottom = bottom.dp.toPx(),
    )
}

private fun slotFraction(index: Int, total: Int): Float {
    if (total <= 1) return 0.5f
    val clamped = index.coerceIn(0, total - 1)
    return 0.28f + 0.44f * (clamped.toFloat() / (total - 1).toFloat())
}

private fun anchorPointVertical(
    rect: Rect,
    shape: MermaidNodeShape,
    bottomSide: Boolean,
    frac: Float,
): Offset {
    val x = rect.left + rect.width * frac
    val y = when (shape) {
        MermaidNodeShape.DIAMOND -> {
            val halfW = (rect.width * 0.5f).coerceAtLeast(1f)
            val halfH = rect.height * 0.5f
            val inset = (abs(x - rect.center.x) / halfW).coerceIn(0f, 1f) * halfH
            if (bottomSide) rect.bottom - inset else rect.top + inset
        }
        MermaidNodeShape.CIRCLE, MermaidNodeShape.STATE_START, MermaidNodeShape.STATE_END -> {
            val r = min(rect.width, rect.height) * 0.5f
            val dx = (x - rect.center.x).coerceIn(-r, r)
            val dy = kotlin.math.sqrt(max(0f, r * r - dx * dx))
            if (bottomSide) rect.center.y + dy else rect.center.y - dy
        }
        else -> if (bottomSide) rect.bottom else rect.top
    }
    return Offset(x, y)
}

private fun anchorPointHorizontal(
    rect: Rect,
    shape: MermaidNodeShape,
    rightSide: Boolean,
    frac: Float,
): Offset {
    val y = rect.top + rect.height * frac
    val x = when (shape) {
        MermaidNodeShape.DIAMOND -> {
            val halfH = (rect.height * 0.5f).coerceAtLeast(1f)
            val halfW = rect.width * 0.5f
            val inset = (abs(y - rect.center.y) / halfH).coerceIn(0f, 1f) * halfW
            if (rightSide) rect.right - inset else rect.left + inset
        }
        MermaidNodeShape.CIRCLE, MermaidNodeShape.STATE_START, MermaidNodeShape.STATE_END -> {
            val r = min(rect.width, rect.height) * 0.5f
            val dy = (y - rect.center.y).coerceIn(-r, r)
            val dx = kotlin.math.sqrt(max(0f, r * r - dy * dy))
            if (rightSide) rect.center.x + dx else rect.center.x - dx
        }
        else -> if (rightSide) rect.right else rect.left
    }
    return Offset(x, y)
}

private fun evalCubic(p0: Offset, c1: Offset, c2: Offset, p3: Offset, t: Float): Offset {
    val u = 1f - t
    val tt = t * t
    val uu = u * u
    val uuu = uu * u
    val ttt = tt * t
    val x = uuu * p0.x + 3f * uu * t * c1.x + 3f * u * tt * c2.x + ttt * p3.x
    val y = uuu * p0.y + 3f * uu * t * c1.y + 3f * u * tt * c2.y + ttt * p3.y
    return Offset(x, y)
}

private fun DrawScope.drawArrowhead(
    tip: Offset,
    direction: Offset,
    color: Color,
    length: Float,
) {
    val angle = atan2(direction.y.toDouble(), direction.x.toDouble())
    val spread = 0.44 // ~25 degrees
    val x1 = tip.x - (length * cos(angle - spread)).toFloat()
    val y1 = tip.y - (length * sin(angle - spread)).toFloat()
    val x2 = tip.x - (length * cos(angle + spread)).toFloat()
    val y2 = tip.y - (length * sin(angle + spread)).toFloat()
    val path = Path().apply {
        moveTo(tip.x, tip.y)
        lineTo(x1, y1)
        lineTo(x2, y2)
        close()
    }
    drawPath(path = path, color = color, style = Fill)
}

private fun DrawScope.drawPillLabel(
    center: Offset,
    textResult: TextLayoutResult,
    fill: Color,
    border: Color,
) {
    val padH = 5.5.dp.toPx()
    val padV = 2.dp.toPx()
    val w = textResult.size.width + padH * 2f
    val h = textResult.size.height + padV * 2f
    val topLeft = Offset(center.x - w * 0.5f, center.y - h * 0.5f)
    drawRoundRect(
        color = fill,
        topLeft = topLeft,
        size = Size(w, h),
        cornerRadius = CornerRadius(5.dp.toPx()),
    )
    drawRoundRect(
        color = border,
        topLeft = topLeft,
        size = Size(w, h),
        cornerRadius = CornerRadius(5.dp.toPx()),
        style = Stroke(width = 0.75.dp.toPx()),
    )
    drawText(
        textLayoutResult = textResult,
        topLeft = Offset(topLeft.x + padH, topLeft.y + padV),
    )
}
