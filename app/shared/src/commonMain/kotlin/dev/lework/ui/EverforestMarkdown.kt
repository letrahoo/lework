package dev.lework.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.DisableSelection
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CheckBox
import androidx.compose.material.icons.rounded.CheckBoxOutlineBlank
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.lework.platform.copyTextToClipboard
import dev.lework.platform.openExternalUrl
import dev.lework.security.isSafeExternalLink
import dev.lework.store.parseLocalFileLink
import kotlinx.coroutines.delay

internal val LocalFileLinkClickHandler = compositionLocalOf<((String) -> Unit)?> { null }

internal enum class MdTableAlign {
    START,
    CENTER,
    END,
}

internal sealed interface MdBlock {
    data class Heading(val level: Int, val text: String) : MdBlock
    data class Paragraph(val text: String) : MdBlock
    data class CodeFence(val lang: String, val code: String) : MdBlock
    data class MathBlock(val latex: String) : MdBlock
    data class Quote(val alertKind: String?, val lines: List<String>) : MdBlock
    data class ListGroup(val items: List<MdListItem>) : MdBlock
    data class Table(
        val headers: List<String>,
        val rows: List<List<String>>,
        val alignments: List<MdTableAlign> = emptyList(),
    ) : MdBlock
    data object ThematicBreak : MdBlock
}

internal data class MdListItem(
    val indent: Int,
    val orderedNumber: String?,
    val taskChecked: Boolean?,
    val text: String,
)

@Composable
internal fun EverforestMarkdownContent(
    markdown: String,
    modifier: Modifier = Modifier,
) {
    val blocks = remember(markdown) { parseMarkdownBlocks(markdown) }

    SelectionContainer {
        Column(modifier = modifier.fillMaxWidth()) {
            for ((index, block) in blocks.withIndex()) {
                // Layout spacing alone is lost when Compose joins selected Text nodes.
                if (index > 0) MarkdownSelectionSeparator("\n", Modifier.height(9.dp).width(1.dp))
                MarkdownBlockItemView(block, wrapInSelectionContainer = false)
            }
        }
    }
}

@Composable
internal fun MarkdownSelectionSeparator(
    text: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text = text,
        fontSize = 1.sp,
        lineHeight = 1.sp,
        modifier = modifier
            .drawWithContent {}
            .clearAndSetSemantics {},
    )
}

@Composable
internal fun MarkdownBlockItemView(
    block: MdBlock,
    wrapInSelectionContainer: Boolean = false,
) {
    val renderContent: @Composable () -> Unit = {
        when (block) {
            is MdBlock.Heading -> HeadingBlockView(block)
            is MdBlock.Paragraph -> ParagraphBlockView(block.text)
            is MdBlock.CodeFence -> {
                if (block.lang.trim().lowercase() == "mermaid") {
                    MermaidDiagramBlockView(code = block.code)
                } else {
                    CodeFenceBlockView(block.lang, block.code)
                }
            }
            is MdBlock.MathBlock -> MathBlockView(latex = block.latex)
            is MdBlock.Quote -> QuoteBlockView(block)
            is MdBlock.ListGroup -> ListGroupBlockView(block.items)
            is MdBlock.Table -> TableBlockView(block)
            MdBlock.ThematicBreak -> HorizontalDivider(
                color = Everforest.Bg2,
                thickness = 1.dp,
                modifier = Modifier.padding(vertical = 4.dp),
            )
        }
    }
    if (wrapInSelectionContainer && block !is MdBlock.ThematicBreak) {
        SelectionContainer {
            renderContent()
        }
    } else {
        renderContent()
    }
}

@Composable
private fun HeadingBlockView(heading: MdBlock.Heading) {
    val (fontSize, lineHeight) = when (heading.level) {
        1 -> 18.5.sp to 25.sp
        2 -> 16.5.sp to 23.sp
        3 -> 15.5.sp to 22.sp
        else -> 14.5.sp to 21.sp
    }
    val headingColor = if (heading.level <= 2) Everforest.HeadingWarm else Everforest.BoldFg
    val highlightQuery = LocalSearchHighlightQuery.current
    val highlightPrimary = LocalSearchHighlightPrimary.current
    val onFileLinkClick = LocalFileLinkClickHandler.current
    val annotated = remember(heading.text, fontSize, headingColor, highlightQuery, highlightPrimary, onFileLinkClick) {
        applySearchTermHighlights(
            source = parseEverforestInline(
                text = heading.text,
                baseColor = headingColor,
                baseWeight = FontWeight.SemiBold,
                codeFontSizeSp = fontSize.value - 1.5f,
                onFileLinkClick = onFileLinkClick,
            ),
            query = highlightQuery,
            primary = highlightPrimary,
        )
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = if (heading.level <= 2) 5.dp else 2.dp),
    ) {
        Text(
            text = annotated,
            fontSize = fontSize,
            lineHeight = lineHeight,
            fontWeight = FontWeight.SemiBold,
            color = headingColor,
        )
    }
}

@Composable
private fun ParagraphBlockView(text: String) {
    val highlightQuery = LocalSearchHighlightQuery.current
    val highlightPrimary = LocalSearchHighlightPrimary.current
    val onFileLinkClick = LocalFileLinkClickHandler.current
    val annotated = remember(text, highlightQuery, highlightPrimary, onFileLinkClick) {
        applySearchTermHighlights(
            source = parseEverforestInline(text = text, onFileLinkClick = onFileLinkClick),
            query = highlightQuery,
            primary = highlightPrimary,
        )
    }
    Text(
        text = annotated,
        fontSize = 15.sp,
        lineHeight = 23.sp,
        color = Everforest.Fg,
    )
}

@Composable
private fun ListGroupBlockView(items: List<MdListItem>) {
    Column(modifier = Modifier.fillMaxWidth()) {
        for ((index, item) in items.withIndex()) {
            if (index > 0) MarkdownSelectionSeparator("\n", Modifier.height(5.dp).width(1.dp))
            val startPad = (item.indent.coerceIn(0, 4) * 14).dp
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = startPad),
                verticalAlignment = Alignment.Top,
            ) {
                when {
                    item.taskChecked != null -> {
                        Icon(
                            imageVector = if (item.taskChecked) Icons.Rounded.CheckBox else Icons.Rounded.CheckBoxOutlineBlank,
                            contentDescription = null,
                            tint = if (item.taskChecked) Everforest.SoftSage else Everforest.CommentGrey,
                            modifier = Modifier
                                .padding(top = 3.dp, end = 7.dp)
                                .size(16.dp),
                        )
                    }
                    item.orderedNumber != null -> {
                        Text(
                            text = "${item.orderedNumber}.",
                            fontSize = 14.5.sp,
                            lineHeight = 23.sp,
                            color = Everforest.CommentGrey,
                            modifier = Modifier.widthIn(min = 22.dp),
                        )
                        MarkdownSelectionSeparator(" ", Modifier.width(5.dp).height(1.dp))
                    }
                    else -> {
                        Text(
                            text = "·",
                            fontSize = 16.sp,
                            lineHeight = 23.sp,
                            fontWeight = FontWeight.Bold,
                            color = Everforest.CommentGrey,
                        )
                        MarkdownSelectionSeparator(" ", Modifier.width(8.dp).height(1.dp))
                    }
                }

                val highlightQuery = LocalSearchHighlightQuery.current
                val highlightPrimary = LocalSearchHighlightPrimary.current
                val onFileLinkClick = LocalFileLinkClickHandler.current
                val annotated = remember(item.text, highlightQuery, highlightPrimary, onFileLinkClick) {
                    applySearchTermHighlights(
                        source = parseEverforestInline(text = item.text, onFileLinkClick = onFileLinkClick),
                        query = highlightQuery,
                        primary = highlightPrimary,
                    )
                }
                Text(
                    text = annotated,
                    fontSize = 15.sp,
                    lineHeight = 23.sp,
                    color = if (item.taskChecked == true) Everforest.CommentGrey else Everforest.Fg,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun QuoteBlockView(quote: MdBlock.Quote) {
    val accent = when (quote.alertKind) {
        "WARNING", "IMPORTANT" -> Everforest.SoftSand
        "CAUTION" -> Everforest.SoftRed
        "TIP" -> Everforest.InlineCode
        else -> Everforest.Bg4
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .padding(vertical = 2.dp),
    ) {
        Box(
            modifier = Modifier
                .width(2.5.dp)
                .fillMaxHeight()
                .background(accent),
        )
        Column(
            modifier = Modifier.padding(start = 11.dp, top = 2.dp, bottom = 2.dp),
        ) {
            if (quote.alertKind != null) {
                Text(
                    text = quote.alertKind,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = if (accent == Everforest.Bg4) Everforest.CommentGrey else accent,
                )
                if (quote.lines.isNotEmpty()) {
                    MarkdownSelectionSeparator("\n", Modifier.height(4.dp).width(1.dp))
                }
            }
            val joined = quote.lines.joinToString("\n")
            val highlightQuery = LocalSearchHighlightQuery.current
            val highlightPrimary = LocalSearchHighlightPrimary.current
            val onFileLinkClick = LocalFileLinkClickHandler.current
            val annotated = remember(joined, highlightQuery, highlightPrimary, onFileLinkClick) {
                applySearchTermHighlights(
                    source = parseEverforestInline(
                        text = joined,
                        baseColor = Everforest.Grey2,
                        onFileLinkClick = onFileLinkClick,
                    ),
                    query = highlightQuery,
                    primary = highlightPrimary,
                )
            }
            Text(
                text = annotated,
                fontSize = 14.sp,
                lineHeight = 21.sp,
                color = Everforest.Grey2,
            )
        }
    }
}

private class TableGridMetrics {
    var colX: IntArray = IntArray(0)
    var rowY: IntArray = IntArray(0)
}

@Composable
private fun TableBlockView(table: MdBlock.Table) {
    val colCount = maxOf(
        table.headers.size,
        table.rows.maxOfOrNull { it.size } ?: 0,
    ).coerceAtLeast(1)
    val rowCount = 1 + table.rows.size
    val metrics = remember { TableGridMetrics() }

    Surface(
        shape = RoundedCornerShape(10.dp),
        color = Everforest.BgDim,
        border = BorderStroke(1.dp, Everforest.Bg2),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("md_table"),
    ) {
        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            val minTableWidthPx = constraints.maxWidth.takeIf { it != Constraints.Infinity } ?: 0

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
            ) {
                Layout(
                    modifier = Modifier.drawBehind {
                        val cx = metrics.colX
                        val ry = metrics.rowY
                        if (cx.size < 2 || ry.size < 2) return@drawBehind
                        val totalW = cx.last().toFloat()
                        val totalH = ry.last().toFloat()

                        // 1. Header background
                        drawRect(
                            color = Everforest.Bg1,
                            topLeft = Offset.Zero,
                            size = Size(totalW, ry[1].toFloat()),
                        )

                        // 2. Subtle alternating body row tint for easy horizontal scanning
                        for (r in 1 until ry.size - 1) {
                            if (r % 2 == 0) {
                                drawRect(
                                    color = Everforest.Bg1.copy(alpha = 0.28f),
                                    topLeft = Offset(0f, ry[r].toFloat()),
                                    size = Size(totalW, (ry[r + 1] - ry[r]).toFloat()),
                                )
                            }
                        }

                        // 3. Horizontal row dividers (stronger below header, subtle between body rows)
                        drawLine(
                            color = Everforest.Bg3,
                            start = Offset(0f, ry[1].toFloat()),
                            end = Offset(totalW, ry[1].toFloat()),
                            strokeWidth = 1.dp.toPx(),
                        )
                        val bodyStroke = 0.75.dp.toPx()
                        for (r in 2 until ry.size - 1) {
                            val y = ry[r].toFloat()
                            drawLine(
                                color = Everforest.Bg2,
                                start = Offset(0f, y),
                                end = Offset(totalW, y),
                                strokeWidth = bodyStroke,
                            )
                        }

                        // 4. Vertical column dividers so columns never visually run into each other
                        for (c in 1 until cx.size - 1) {
                            val x = cx[c].toFloat()
                            drawLine(
                                color = Everforest.Bg2.copy(alpha = 0.85f),
                                start = Offset(x, 0f),
                                end = Offset(x, totalH),
                                strokeWidth = bodyStroke,
                            )
                        }
                    },
                    content = {
                        // Row 0: Headers (emit invisible TSV/newline separators between cells in composition order)
                        val highlightQuery = LocalSearchHighlightQuery.current
                        val highlightPrimary = LocalSearchHighlightPrimary.current
                        val onFileLinkClick = LocalFileLinkClickHandler.current
                        for (c in 0 until colCount) {
                            if (c > 0) MarkdownSelectionSeparator("\t")
                            val align = table.alignments.getOrElse(c) { MdTableAlign.START }
                            val cellText = table.headers.getOrElse(c) { "" }
                            val annotated = remember(cellText, highlightQuery, highlightPrimary, onFileLinkClick) {
                                applySearchTermHighlights(
                                    source = parseEverforestInline(
                                        text = cellText,
                                        baseColor = Everforest.BoldFg,
                                        baseWeight = FontWeight.SemiBold,
                                        codeFontSizeSp = 12f,
                                        onFileLinkClick = onFileLinkClick,
                                    ),
                                    query = highlightQuery,
                                    primary = highlightPrimary,
                                )
                            }
                            Text(
                                text = annotated,
                                fontSize = 13.sp,
                                lineHeight = 18.5.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = Everforest.BoldFg,
                                textAlign = align.toTextAlign(),
                                modifier = Modifier.testTag("md_table_cell_0_$c"),
                            )
                        }

                        // Rows 1..N: Body cells
                        table.rows.forEachIndexed { rIdx, row ->
                            for (c in 0 until colCount) {
                                MarkdownSelectionSeparator(if (c == 0) "\n" else "\t")
                                val align = table.alignments.getOrElse(c) { MdTableAlign.START }
                                val cellText = row.getOrElse(c) { "" }
                                val annotated = remember(cellText, highlightQuery, highlightPrimary, onFileLinkClick) {
                                    applySearchTermHighlights(
                                        source = parseEverforestInline(
                                            text = cellText,
                                            baseColor = Everforest.Fg,
                                            codeFontSizeSp = 12f,
                                            onFileLinkClick = onFileLinkClick,
                                        ),
                                        query = highlightQuery,
                                        primary = highlightPrimary,
                                    )
                                }
                                Text(
                                    text = annotated,
                                    fontSize = 13.sp,
                                    lineHeight = 19.sp,
                                    color = Everforest.Fg,
                                    textAlign = align.toTextAlign(),
                                    modifier = Modifier.testTag("md_table_cell_${rIdx + 1}_$c"),
                                )
                            }
                        }
                    },
                ) { measurables, _ ->
                    val padH = 11.dp.roundToPx()
                    val padV = 8.dp.roundToPx()
                    val minColW = 40.dp.roundToPx()
                    val maxColW = 284.dp.roundToPx()
                    val minRowH = 32.dp.roundToPx()
                    val totalCells = rowCount * colCount

                    // Step 1: query each cell's single-line intrinsic width (cells occupy even indices 2 * cellIdx)
                    val colWidths = IntArray(colCount) { minColW }
                    for (r in 0 until rowCount) {
                        for (c in 0 until colCount) {
                            val cellIdx = r * colCount + c
                            val intrinsicW = measurables[cellIdx * 2].maxIntrinsicWidth(Constraints.Infinity)
                            val desired = (intrinsicW + padH * 2).coerceIn(minColW, maxColW)
                            if (desired > colWidths[c]) colWidths[c] = desired
                        }
                    }

                    // Step 2: if narrower than viewport card, expand wider columns preferentially so `#` stays compact
                    val naturalSum = colWidths.sum()
                    if (minTableWidthPx > naturalSum && naturalSum > 0) {
                        val extra = minTableWidthPx - naturalSum
                        val flexBase = 6.dp.roundToPx()
                        val weights = LongArray(colCount) { c ->
                            (colWidths[c] - minColW + flexBase).coerceAtLeast(1).toLong()
                        }
                        val weightSum = weights.sum().coerceAtLeast(1L)
                        var distributed = 0
                        for (c in 0 until colCount) {
                            val add = if (c == colCount - 1) {
                                extra - distributed
                            } else {
                                ((extra.toLong() * weights[c]) / weightSum).toInt()
                            }
                            colWidths[c] += add
                            distributed += add
                        }
                    }

                    // Step 3: measure each cell ONCE at its exact column inner width so wrapping & alignment are exact
                    val cellPlaceables = Array(totalCells) { cellIdx ->
                        val c = cellIdx % colCount
                        val innerW = (colWidths[c] - padH * 2).coerceAtLeast(1)
                        measurables[cellIdx * 2].measure(Constraints(minWidth = innerW, maxWidth = innerW))
                    }
                    val sepConstraints = Constraints.fixed(1, 1)
                    val sepPlaceables = Array((totalCells - 1).coerceAtLeast(0)) { sepIdx ->
                        measurables[sepIdx * 2 + 1].measure(sepConstraints)
                    }

                    val rowHeights = IntArray(rowCount) { minRowH }
                    for (r in 0 until rowCount) {
                        for (c in 0 until colCount) {
                            val p = cellPlaceables[r * colCount + c]
                            val h = p.height + padV * 2
                            if (h > rowHeights[r]) rowHeights[r] = h
                        }
                    }

                    val colX = IntArray(colCount + 1)
                    for (c in 0 until colCount) {
                        colX[c + 1] = colX[c] + colWidths[c]
                    }
                    val rowY = IntArray(rowCount + 1)
                    for (r in 0 until rowCount) {
                        rowY[r + 1] = rowY[r] + rowHeights[r]
                    }
                    metrics.colX = colX
                    metrics.rowY = rowY

                    val totalWidth = colX[colCount]
                    val totalHeight = rowY[rowCount]

                    layout(totalWidth, totalHeight) {
                        for (r in 0 until rowCount) {
                            for (c in 0 until colCount) {
                                val cellIdx = r * colCount + c
                                if (cellIdx > 0) {
                                    val sepX = if (c > 0) colX[c] else 0
                                    val sepY = if (c > 0) rowY[r] + padV else rowY[r]
                                    sepPlaceables[cellIdx - 1].placeRelative(sepX, sepY)
                                }
                                cellPlaceables[cellIdx].placeRelative(colX[c] + padH, rowY[r] + padV)
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun MdTableAlign.toTextAlign(): TextAlign = when (this) {
    MdTableAlign.START -> TextAlign.Start
    MdTableAlign.CENTER -> TextAlign.Center
    MdTableAlign.END -> TextAlign.End
}

private const val CODE_FOLD_THRESHOLD_LINES = 26
private const val CODE_PREVIEW_LINES = 20

@Composable
internal fun CodeFenceBlockView(
    lang: String,
    code: String,
) {
    var copies by remember(code) { mutableIntStateOf(0) }
    val copied = copies != 0
    LaunchedEffect(code, copies) {
        if (copied) {
            delay(1_600L)
            copies = 0
        }
    }
    var codeExpanded by remember(code) { mutableStateOf(false) }
    val (totalLines, previewCode) = remember(code) {
        var nlCount = 1
        var cutIdx = -1
        for (idx in code.indices) {
            if (code[idx] == '\n') {
                if (nlCount == CODE_PREVIEW_LINES && cutIdx < 0) {
                    cutIdx = idx
                }
                nlCount++
            }
        }
        val slice = if (nlCount > CODE_FOLD_THRESHOLD_LINES && cutIdx >= 0) {
            code.substring(0, cutIdx)
        } else {
            code
        }
        nlCount to slice
    }
    val canFold = totalLines > CODE_FOLD_THRESHOLD_LINES
    val visibleCode = if (canFold && !codeExpanded) previewCode else code
    val highlightQuery = LocalSearchHighlightQuery.current
    val highlightPrimary = LocalSearchHighlightPrimary.current
    val highlighted = remember(visibleCode, lang, highlightQuery, highlightPrimary) {
        applySearchTermHighlights(
            source = highlightEverforestCode(visibleCode, lang),
            query = highlightQuery,
            primary = highlightPrimary,
        )
    }

    Surface(
        shape = RoundedCornerShape(10.dp),
        color = Everforest.BgDim,
        border = BorderStroke(1.dp, Everforest.Bg2),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            DisableSelection {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Everforest.Bg1.copy(alpha = 0.55f))
                        .padding(horizontal = 12.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = if (canFold) "${lang.ifBlank { "code" }} · $totalLines 行" else lang.ifBlank { "code" },
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        color = Everforest.CommentGrey,
                    )

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .clickable {
                                if (copyTextToClipboard(code)) {
                                    copies++
                                }
                            }
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                    ) {
                        Icon(
                            imageVector = if (copied) Icons.Rounded.Check else Icons.Rounded.ContentCopy,
                            contentDescription = "复制代码",
                            tint = if (copied) Everforest.SoftSage else Everforest.CommentGrey,
                            modifier = Modifier.size(12.5.dp),
                        )
                        Text(
                            text = if (copied) "已复制" else "复制",
                            fontSize = 11.sp,
                            color = if (copied) Everforest.SoftSage else Everforest.CommentGrey,
                        )
                    }
                }
            }

            HorizontalDivider(color = Everforest.Bg2, thickness = 0.5.dp)

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp, vertical = 10.dp),
            ) {
                Text(
                    text = highlighted,
                    fontSize = 12.5.sp,
                    lineHeight = 19.sp,
                    fontFamily = FontFamily.Monospace,
                    color = Everforest.Fg,
                )
            }

            if (canFold) {
                HorizontalDivider(color = Everforest.Bg2, thickness = 0.5.dp)
                DisableSelection {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Everforest.Bg1.copy(alpha = 0.35f))
                            .clickable { codeExpanded = !codeExpanded }
                            .padding(vertical = 6.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = if (codeExpanded) "▴ 收起代码" else "▾ 展开全部 $totalLines 行代码",
                            fontSize = 11.5.sp,
                            color = Everforest.CommentGrey,
                        )
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Markdown Block Parser
// ---------------------------------------------------------------------------

internal fun parseMarkdownBlocks(raw: String): List<MdBlock> {
    if (raw.isBlank()) return emptyList()
    val lines = raw.replace("\r\n", "\n").split('\n')
    val out = mutableListOf<MdBlock>()
    var i = 0

    while (i < lines.size) {
        val line = lines[i]
        val trimmed = line.trim()

        if (trimmed.isEmpty()) {
            i++
            continue
        }

        // 1. Fenced Code Block (``` or ~~~)
        if (trimmed.startsWith("```") || trimmed.startsWith("~~~")) {
            val fence = trimmed.take(3)
            val lang = trimmed.drop(3).trim().substringBefore(' ')
            val codeLines = mutableListOf<String>()
            i++
            while (i < lines.size && !lines[i].trimStart().startsWith(fence)) {
                codeLines.add(lines[i])
                i++
            }
            if (i < lines.size) i++ // consume closing fence
            out.add(MdBlock.CodeFence(lang = lang, code = codeLines.joinToString("\n")))
            continue
        }

        // 1b. Block LaTeX Math (`$$ ... $$` or `\[ ... \]`)
        if (trimmed.startsWith("$$")) {
            val rest = trimmed.removePrefix("$$")
            if (rest.endsWith("$$") && rest.length >= 2) {
                val inner = rest.removeSuffix("$$").trim()
                if (inner.isNotEmpty()) {
                    out.add(MdBlock.MathBlock(inner))
                    i++
                    continue
                }
            } else {
                val mathLines = mutableListOf<String>()
                if (rest.trim().isNotEmpty()) mathLines.add(rest.trim())
                i++
                while (i < lines.size) {
                    val curTrim = lines[i].trim()
                    if (curTrim.endsWith("$$")) {
                        val beforeClose = curTrim.removeSuffix("$$").trim()
                        if (beforeClose.isNotEmpty()) mathLines.add(beforeClose)
                        i++
                        break
                    }
                    mathLines.add(lines[i])
                    i++
                }
                val latexBody = mathLines.joinToString("\n").trim()
                if (latexBody.isNotEmpty()) {
                    out.add(MdBlock.MathBlock(latexBody))
                }
                continue
            }
        }
        if (trimmed.startsWith("\\[")) {
            val rest = trimmed.removePrefix("\\[")
            if (rest.endsWith("\\]") && rest.length >= 2) {
                val inner = rest.removeSuffix("\\]").trim()
                if (inner.isNotEmpty()) {
                    out.add(MdBlock.MathBlock(inner))
                    i++
                    continue
                }
            } else {
                val mathLines = mutableListOf<String>()
                if (rest.trim().isNotEmpty()) mathLines.add(rest.trim())
                i++
                while (i < lines.size) {
                    val curTrim = lines[i].trim()
                    if (curTrim.endsWith("\\]")) {
                        val beforeClose = curTrim.removeSuffix("\\]").trim()
                        if (beforeClose.isNotEmpty()) mathLines.add(beforeClose)
                        i++
                        break
                    }
                    mathLines.add(lines[i])
                    i++
                }
                val latexBody = mathLines.joinToString("\n").trim()
                if (latexBody.isNotEmpty()) {
                    out.add(MdBlock.MathBlock(latexBody))
                }
                continue
            }
        }

        // 2. Thematic Break (---, ***, ___)
        if (
            (trimmed.length >= 3 && trimmed.all { it == '-' }) ||
            (trimmed.length >= 3 && trimmed.all { it == '*' }) ||
            (trimmed.length >= 3 && trimmed.all { it == '_' })
        ) {
            out.add(MdBlock.ThematicBreak)
            i++
            continue
        }

        // 3. ATX Heading (# .. ######)
        val headingMatch = matchHeading(trimmed)
        if (headingMatch != null) {
            out.add(MdBlock.Heading(headingMatch.first, headingMatch.second))
            i++
            continue
        }

        // 4. GFM Table (with or without outer pipes, code-span-safe `|` splitting, 1+-dash separators)
        if (i + 1 < lines.size && hasUnescapedTablePipe(trimmed)) {
            val sepAlignments = parseTableSeparatorAlignments(lines[i + 1].trim())
            if (sepAlignments != null) {
                val headers = splitTableCells(trimmed)
                if (headers.size == sepAlignments.size || trimmed.startsWith("|")) {
                    i += 2
                    val rows = mutableListOf<List<String>>()
                    while (i < lines.size) {
                        val rowTrim = lines[i].trim()
                        if (rowTrim.isEmpty() || !hasUnescapedTablePipe(rowTrim)) break
                        if (rowTrim.startsWith("```") || rowTrim.startsWith("~~~") || rowTrim.startsWith(">")) break
                        rows.add(splitTableCells(rowTrim))
                        i++
                    }
                    out.add(MdBlock.Table(headers = headers, rows = rows, alignments = sepAlignments))
                    continue
                }
            }
        }

        // 5. Blockquote (> ...)
        if (trimmed.startsWith(">")) {
            val qLines = mutableListOf<String>()
            var alertKind: String? = null
            while (i < lines.size && lines[i].trimStart().startsWith(">")) {
                val content = lines[i].trimStart().removePrefix(">").removePrefix(" ")
                if (qLines.isEmpty() && content.startsWith("[!") && content.contains("]")) {
                    val kind = content.substringAfter("[!").substringBefore("]").uppercase()
                    alertKind = kind
                    val rest = content.substringAfter("]").trim()
                    if (rest.isNotEmpty()) qLines.add(rest)
                } else {
                    qLines.add(content)
                }
                i++
            }
            out.add(MdBlock.Quote(alertKind = alertKind, lines = qLines))
            continue
        }

        // 6. Ordered / Unordered / Task List
        val firstListItem = matchListItem(line)
        if (firstListItem != null) {
            val listItems = mutableListOf(firstListItem)
            i++
            while (i < lines.size) {
                val nextLine = lines[i]
                if (nextLine.isBlank()) break
                val nextItem = matchListItem(nextLine)
                if (nextItem != null) {
                    listItems.add(nextItem)
                    i++
                } else if (nextLine.startsWith("  ") && listItems.isNotEmpty()) {
                    // Continuation of previous list item
                    val prev = listItems.removeAt(listItems.lastIndex)
                    listItems.add(prev.copy(text = prev.text + "\n" + nextLine.trim()))
                    i++
                } else {
                    break
                }
            }
            out.add(MdBlock.ListGroup(listItems))
            continue
        }

        // 7. Paragraph
        val paraLines = mutableListOf(line.trimEnd())
        i++
        while (i < lines.size) {
            val peek = lines[i]
            val peekTrim = peek.trim()
            if (
                peekTrim.isEmpty() ||
                peekTrim.startsWith("```") ||
                peekTrim.startsWith("~~~") ||
                peekTrim.startsWith("$$") ||
                peekTrim.startsWith("\\[") ||
                peekTrim.startsWith(">") ||
                matchHeading(peekTrim) != null ||
                matchListItem(peek) != null ||
                (hasUnescapedTablePipe(peekTrim) && i + 1 < lines.size && parseTableSeparatorAlignments(lines[i + 1].trim()) != null) ||
                (peekTrim.length >= 3 && (peekTrim.all { it == '-' } || peekTrim.all { it == '*' }))
            ) {
                break
            }
            paraLines.add(peek.trimEnd())
            i++
        }
        out.add(MdBlock.Paragraph(paraLines.joinToString("\n")))
    }

    return out
}

private fun matchHeading(trimmed: String): Pair<Int, String>? {
    var hashes = 0
    while (hashes < trimmed.length && hashes < 6 && trimmed[hashes] == '#') {
        hashes++
    }
    if (hashes in 1..6 && hashes < trimmed.length && trimmed[hashes] == ' ') {
        return hashes to trimmed.substring(hashes + 1).trim()
    }
    return null
}

private val HTML_BR_REGEX = Regex("<br\\s*/?>", RegexOption.IGNORE_CASE)

private fun hasUnescapedTablePipe(trimmed: String): Boolean {
    var inTicks = 0
    var i = 0
    while (i < trimmed.length) {
        val ch = trimmed[i]
        if (ch == '\\' && i + 1 < trimmed.length) {
            i += 2
            continue
        }
        if (ch == '`') {
            var count = 0
            while (i < trimmed.length && trimmed[i] == '`') {
                count++
                i++
            }
            if (inTicks == 0) inTicks = count
            else if (inTicks == count) inTicks = 0
            continue
        }
        if (ch == '|' && inTicks == 0) return true
        i++
    }
    return false
}

private fun parseTableSeparatorAlignments(trimmed: String): List<MdTableAlign>? {
    if (!hasUnescapedTablePipe(trimmed)) return null
    val cells = splitTableCells(trimmed, normalizeBr = false)
    if (cells.isEmpty()) return null
    val out = ArrayList<MdTableAlign>(cells.size)
    for (rawCell in cells) {
        val compact = rawCell.replace(" ", "").replace("\t", "")
        if (compact.isEmpty()) return null
        val leftColon = compact.startsWith(":")
        val rightColon = compact.endsWith(":")
        val core = compact.removePrefix(":").removeSuffix(":")
        if (core.isEmpty() || !core.all { it == '-' }) return null
        val align = when {
            leftColon && rightColon -> MdTableAlign.CENTER
            rightColon -> MdTableAlign.END
            else -> MdTableAlign.START
        }
        out.add(align)
    }
    return out
}

/**
 * Splits a GFM table row into cells without breaking on `|` inside backtick code spans (`` `a|b` ``)
 * or escaped `\|`, and normalizes `<br>` / `<br/>` tags into real newlines.
 */
internal fun splitTableCells(line: String, normalizeBr: Boolean = true): List<String> {
    var work = line.trim()
    if (work.startsWith("|")) work = work.substring(1)
    if (work.endsWith("|") && !work.endsWith("\\|")) {
        work = work.substring(0, work.length - 1)
    }

    val cells = mutableListOf<String>()
    val cur = StringBuilder()
    var inTicks = 0
    var i = 0
    while (i < work.length) {
        val ch = work[i]
        if (ch == '\\' && i + 1 < work.length && inTicks == 0 && work[i + 1] == '|') {
            cur.append('|')
            i += 2
            continue
        }
        if (ch == '`') {
            var count = 0
            while (i < work.length && work[i] == '`') {
                cur.append('`')
                count++
                i++
            }
            if (inTicks == 0) inTicks = count
            else if (inTicks == count) inTicks = 0
            continue
        }
        if (ch == '|' && inTicks == 0) {
            cells.add(cleanTableCell(cur.toString(), normalizeBr))
            cur.setLength(0)
            i++
            continue
        }
        cur.append(ch)
        i++
    }
    cells.add(cleanTableCell(cur.toString(), normalizeBr))
    return cells
}

private fun cleanTableCell(raw: String, normalizeBr: Boolean): String {
    val trimmed = raw.trim()
    return if (normalizeBr && trimmed.contains("<br", ignoreCase = true)) {
        trimmed.replace(HTML_BR_REGEX, "\n")
    } else {
        trimmed
    }
}

private fun matchListItem(line: String): MdListItem? {
    var spaces = 0
    while (spaces < line.length && (line[spaces] == ' ' || line[spaces] == '\t')) {
        spaces++
    }
    val indent = spaces / 2
    val rest = line.substring(spaces)
    if (rest.length >= 2 && (rest[0] == '-' || rest[0] == '*' || rest[0] == '+') && rest[1] == ' ') {
        val body = rest.substring(2)
        return when {
            body.startsWith("[x] ") || body.startsWith("[X] ") ->
                MdListItem(indent = indent, orderedNumber = null, taskChecked = true, text = body.substring(4))
            body.startsWith("[ ] ") ->
                MdListItem(indent = indent, orderedNumber = null, taskChecked = false, text = body.substring(4))
            else ->
                MdListItem(indent = indent, orderedNumber = null, taskChecked = null, text = body)
        }
    }

    var digits = 0
    while (digits < rest.length && rest[digits].isDigit()) {
        digits++
    }
    if (
        digits in 1..4 &&
        digits + 1 < rest.length &&
        (rest[digits] == '.' || rest[digits] == ')') &&
        rest[digits + 1] == ' '
    ) {
        return MdListItem(
            indent = indent,
            orderedNumber = rest.substring(0, digits),
            taskChecked = null,
            text = rest.substring(digits + 2),
        )
    }
    return null
}

// ---------------------------------------------------------------------------
// Inline Everforest Rich Span Parser (#e6c384 Gold Bold, #83c995 Emerald Code)
// ---------------------------------------------------------------------------

internal fun parseEverforestInline(
    text: String,
    baseColor: Color = Everforest.Fg,
    baseWeight: FontWeight = FontWeight.Normal,
    codeFontSizeSp: Float = 13.5f,
    onFileLinkClick: ((String) -> Unit)? = null,
): AnnotatedString = buildAnnotatedString {
    withStyle(SpanStyle(color = baseColor, fontWeight = baseWeight)) {
        appendInlineRec(text, codeFontSizeSp, onFileLinkClick)
    }
}

private fun AnnotatedString.Builder.appendInlineRec(
    text: String,
    codeFontSizeSp: Float,
    onFileLinkClick: ((String) -> Unit)? = null,
) {
    var i = 0
    val n = text.length
    while (i < n) {
        val ch = text[i]

        // 1. Inline code (`...` or ``...``) -> Low-contrast Shell slate-blue #9ab0c1
        //    When the code span is a single safe http(s) URL (common for PR / CI / Release links),
        //    attach LinkAnnotation.Clickable so tapping opens the URL directly in the browser.
        if (ch == '`') {
            var ticks = 0
            while (i + ticks < n && text[i + ticks] == '`') ticks++
            val delim = "`".repeat(ticks)
            val end = text.indexOf(delim, i + ticks)
            if (end > i + ticks - 1 && end != -1) {
                val rawCode = text.substring(i + ticks, end)
                val trimmedUrl = rawCode.trim()
                val isSingleSafeUrl = isSafeExternalLink(trimmedUrl) &&
                    trimmedUrl.none { it.isWhitespace() } &&
                    trimmedUrl.substringAfter("://").isNotEmpty() &&
                    !trimmedUrl.contains("...")
                val codeStyle = SpanStyle(
                    color = Everforest.InlineCode,
                    background = Everforest.Bg1.copy(alpha = 0.42f),
                    fontFamily = FontFamily.Monospace,
                    fontSize = codeFontSizeSp.sp,
                )
                if (isSingleSafeUrl) {
                    val clickableCodeStyle = codeStyle.copy(textDecoration = TextDecoration.Underline)
                    withLink(
                        LinkAnnotation.Clickable(
                            tag = trimmedUrl,
                            styles = TextLinkStyles(style = clickableCodeStyle),
                            linkInteractionListener = { if (isSafeExternalLink(trimmedUrl)) openExternalUrl(trimmedUrl) },
                        ),
                    ) {
                        append("\u2009")
                        append(rawCode)
                        append("\u2009")
                    }
                } else {
                    withStyle(codeStyle) {
                        append("\u2009")
                        append(rawCode)
                        append("\u2009")
                    }
                }
                i = end + ticks
                continue
            }
        }

        // 1b. Inline LaTeX Math (`$...$`, `\(...\)`, or single-line `$$...$$`)
        if (ch == '$' || ch == '\\') {
            val mathMatch = matchInlineMath(text, i)
            if (mathMatch != null) {
                appendInlineLatexMath(
                    latex = mathMatch.body,
                    baseFontSizeSp = (codeFontSizeSp + 1f).coerceAtLeast(13f),
                )
                i = mathMatch.endExclusive
                continue
            }
        }

        // 2. Markdown Link [label](url) -> Understated underlined foreground (CLI style, no loud blue)
        if (ch == '[') {
            val closeBracket = text.indexOf(']', i + 1)
            if (
                closeBracket > i + 1 &&
                closeBracket + 1 < n &&
                text[closeBracket + 1] == '(' &&
                !text.substring(i + 1, closeBracket).contains('\n')
            ) {
                val closeParen = findMatchingLinkCloseParen(text, closeBracket + 1)
                if (closeParen > closeBracket + 2) {
                    val label = text.substring(i + 1, closeBracket)
                    val url = text.substring(closeBracket + 2, closeParen).trim()
                    val linkStyle = SpanStyle(
                        color = Everforest.Fg,
                        textDecoration = TextDecoration.Underline,
                    )
                    val interactiveLinkStyle = TextLinkStyles(
                        style = linkStyle,
                        hoveredStyle = SpanStyle(
                            color = Everforest.CodeGreen,
                            textDecoration = TextDecoration.Underline,
                        ),
                        pressedStyle = SpanStyle(
                            color = Everforest.BoldGold,
                            textDecoration = TextDecoration.Underline,
                        ),
                    )
                    if (isSafeExternalLink(url)) {
                        withLink(
                            LinkAnnotation.Clickable(
                                tag = url,
                                styles = interactiveLinkStyle,
                                linkInteractionListener = { if (isSafeExternalLink(url)) openExternalUrl(url) },
                            ),
                        ) {
                            append(label)
                        }
                    } else if (onFileLinkClick != null && parseLocalFileLink(url) != null) {
                        val fileLinkStyle = TextLinkStyles(
                            style = SpanStyle(
                                color = Everforest.InlineCode,
                                textDecoration = TextDecoration.Underline,
                            ),
                            hoveredStyle = SpanStyle(
                                color = Everforest.CodeGreen,
                                textDecoration = TextDecoration.Underline,
                            ),
                            pressedStyle = SpanStyle(
                                color = Everforest.BoldGold,
                                textDecoration = TextDecoration.Underline,
                            ),
                        )
                        withLink(
                            LinkAnnotation.Clickable(
                                tag = "file:$url",
                                styles = fileLinkStyle,
                                linkInteractionListener = { onFileLinkClick(url) },
                            ),
                        ) {
                            append(label)
                        }
                    } else {
                        withStyle(linkStyle) {
                            append(label)
                        }
                    }
                    i = closeParen + 1
                    continue
                }
            }
        }

        // 3. Bold + Italic (***...***) -> Subtle warm-white BoldFg #e5dcc8 (matches Shell bold, zero yellow glare)
        if (text.startsWith("***", i)) {
            val end = text.indexOf("***", i + 3)
            if (end > i + 3) {
                withStyle(
                    SpanStyle(
                        color = Everforest.BoldFg,
                        fontWeight = FontWeight.SemiBold,
                        fontStyle = FontStyle.Italic,
                    ),
                ) {
                    appendInlineRec(text.substring(i + 3, end), codeFontSizeSp, onFileLinkClick)
                }
                i = end + 3
                continue
            }
        }

        // 4. Bold (**...**) -> Subtle warm-white BoldFg #e5dcc8 (matches Shell bold, zero yellow glare)
        if (text.startsWith("**", i)) {
            val end = text.indexOf("**", i + 2)
            if (end > i + 2) {
                withStyle(
                    SpanStyle(
                        color = Everforest.BoldFg,
                        fontWeight = FontWeight.SemiBold,
                    ),
                ) {
                    appendInlineRec(text.substring(i + 2, end), codeFontSizeSp, onFileLinkClick)
                }
                i = end + 2
                continue
            }
        }

        // 5. Strikethrough (~~...~~) -> Moss Grey-Green #859289
        if (text.startsWith("~~", i)) {
            val end = text.indexOf("~~", i + 2)
            if (end > i + 2) {
                withStyle(
                    SpanStyle(
                        color = Everforest.CommentGrey,
                        textDecoration = TextDecoration.LineThrough,
                    ),
                ) {
                    append(text.substring(i + 2, end))
                }
                i = end + 2
                continue
            }
        }

        // 6. Italic (*...*)
        if (ch == '*' && i + 1 < n && text[i + 1] != ' ' && text[i + 1] != '*') {
            val end = text.indexOf('*', i + 1)
            if (end > i + 1 && text[end - 1] != ' ' && !text.substring(i + 1, end).contains('\n')) {
                withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                    appendInlineRec(text.substring(i + 1, end), codeFontSizeSp, onFileLinkClick)
                }
                i = end + 1
                continue
            }
        }

        // 7. Bare URL (https://... or http://...) -> stops cleanly before CJK/non-ASCII chars & trailing punctuation
        if (text.startsWith("https://", i, ignoreCase = true) || text.startsWith("http://", i, ignoreCase = true)) {
            val end = scanBareUrlEnd(text, i)
            val url = text.substring(i, end)
            if (url.substringAfter("://").isNotEmpty() && !url.contains("...")) {
                val linkStyle = SpanStyle(
                    color = Everforest.CommentGrey,
                    textDecoration = TextDecoration.Underline,
                )
                withLink(
                    LinkAnnotation.Clickable(
                        tag = url,
                        styles = TextLinkStyles(style = linkStyle),
                        linkInteractionListener = { if (isSafeExternalLink(url)) openExternalUrl(url) },
                    ),
                ) {
                    append(url)
                }
                i = end
                continue
            }
        }

        append(ch)
        i++
    }
}

private fun findMatchingLinkCloseParen(text: String, openParenIdx: Int): Int {
    var depth = 0
    var i = openParenIdx
    while (i < text.length) {
        when (text[i]) {
            '(' -> depth++
            ')' -> {
                depth--
                if (depth == 0) return i
            }
            '\n' -> return -1
        }
        i++
    }
    return -1
}

internal fun scanBareUrlEnd(text: String, start: Int): Int {
    val n = text.length
    var end = start
    while (end < n) {
        val c = text[end]
        if (c.isWhitespace() || c.code !in 0x21..0x7E || c in charArrayOf('<', '>', '"', '\'', '`')) {
            break
        }
        end++
    }
    while (end > start) {
        val last = text[end - 1]
        when {
            last in charArrayOf('.', ',', ';', ':', '!', '?') -> end--
            last == ')' -> {
                val slice = text.substring(start, end)
                if (slice.count { it == ')' } > slice.count { it == '(' }) end-- else break
            }
            last == ']' -> {
                val slice = text.substring(start, end)
                if (slice.count { it == ']' } > slice.count { it == '[' }) end-- else break
            }
            else -> break
        }
    }
    return end
}

// ---------------------------------------------------------------------------
// Everforest TrueColor Syntax Highlighter (Shell aligned with dotfiles/.zshrc)
// ---------------------------------------------------------------------------

private val SHELL_LANGS = setOf("sh", "bash", "zsh", "shell", "console", "terminal")
private val SHELL_RESERVED = setOf(
    "if", "then", "else", "elif", "fi", "for", "in", "do", "done",
    "while", "until", "case", "esac", "function", "export", "local", "return",
)
private val CODE_KEYWORDS = setOf(
    "package", "import", "from", "export", "default", "as",
    "class", "interface", "object", "struct", "enum", "type", "trait", "impl",
    "fun", "function", "fn", "def", "func", "val", "var", "const", "let", "mut",
    "if", "else", "elif", "when", "match", "switch", "case", "for", "while", "loop", "do",
    "return", "break", "continue", "yield", "throw", "try", "catch", "finally", "except",
    "async", "await", "suspend", "go", "defer", "chan", "select",
    "private", "public", "protected", "internal", "override", "open", "abstract", "sealed", "data", "pub", "use",
)
private val CODE_LITERALS = setOf(
    "true", "false", "null", "nil", "None", "True", "False", "undefined", "this", "self", "super",
)

internal fun highlightEverforestCode(code: String, rawLang: String): AnnotatedString {
    if (code.length > 12_000) {
        return AnnotatedString(code)
    }
    val lang = rawLang.trim().lowercase()
    if (lang == "diff" || lang == "patch") {
        return highlightDiff(code)
    }
    if (lang in SHELL_LANGS) {
        return highlightShellZsh(code)
    }
    return highlightGeneralCode(code, lang)
}

private fun highlightDiff(code: String): AnnotatedString = buildAnnotatedString {
    val lines = code.split('\n')
    lines.forEachIndexed { idx, line ->
        val style = when {
            line.startsWith("+++") || line.startsWith("---") || line.startsWith("@@") ->
                SpanStyle(color = Everforest.CommentGrey)
            line.startsWith("+") ->
                SpanStyle(color = Everforest.SoftSage)
            line.startsWith("-") ->
                SpanStyle(color = Everforest.SoftRed)
            else ->
                SpanStyle(color = Everforest.Fg)
        }
        withStyle(style) { append(line) }
        if (idx < lines.lastIndex) append('\n')
    }
}

/**
 * Low-Contrast Shell Highlighter:
 * - Comments & `$` prompt: CommentGrey #859289
 * - Primary executable / reserved word: SoftSand #d9c59e
 * - Quoted strings: SoftSage #9eb89f
 * - Arguments, flags, paths & operators: calm Fg #d8caac
 */
internal fun highlightShellZsh(code: String): AnnotatedString = buildAnnotatedString {
    val lines = code.split('\n')
    lines.forEachIndexed { lineIdx, line ->
        var i = 0
        val n = line.length
        var expectCommand = true

        while (i < n && line[i] == ' ') {
            append(' ')
            i++
        }
        if (i + 1 < n && (line[i] == '$' || line[i] == '%') && line[i + 1] == ' ') {
            withStyle(SpanStyle(color = Everforest.CommentGrey)) {
                append(line.substring(i, i + 2))
            }
            i += 2
        }

        while (i < n) {
            val c = line[i]
            if (c.isWhitespace()) {
                append(c)
                i++
                continue
            }
            if (c == '#' && (i == 0 || line[i - 1].isWhitespace())) {
                withStyle(SpanStyle(color = Everforest.CommentGrey, fontStyle = FontStyle.Italic)) {
                    append(line.substring(i))
                }
                break
            }
            if (c == '\'' || c == '"') {
                var end = i + 1
                while (end < n && line[end] != c) {
                    if (line[end] == '\\' && end + 1 < n) end += 2 else end++
                }
                if (end < n) end++
                withStyle(SpanStyle(color = Everforest.SoftSage)) {
                    append(line.substring(i, end))
                }
                expectCommand = false
                i = end
                continue
            }
            if (c == '|' || c == '&' || c == ';' || c == '>' || c == '<') {
                var end = i + 1
                while (end < n && line[end] in charArrayOf('|', '&', '>', '<')) end++
                val sep = line.substring(i, end)
                withStyle(SpanStyle(color = Everforest.CommentGrey)) {
                    append(sep)
                }
                if (sep.contains('|') || sep.contains('&') || sep.contains(';')) {
                    expectCommand = true
                }
                i = end
                continue
            }
            var end = i
            while (end < n && !line[end].isWhitespace() && line[end] !in charArrayOf('|', '&', ';', '>', '<', '\'', '"')) {
                end++
            }
            val token = line.substring(i, end)
            val style = when {
                token in SHELL_RESERVED -> {
                    expectCommand = token in setOf("then", "else", "do")
                    SpanStyle(color = Everforest.SoftSand, fontWeight = FontWeight.Medium)
                }
                expectCommand && token.contains('=') && !token.startsWith('-') ->
                    SpanStyle(color = Everforest.CommentGrey)
                expectCommand -> {
                    if (token !in setOf("sudo", "env", "time", "nohup")) {
                        expectCommand = false
                    }
                    SpanStyle(color = Everforest.SoftSand, fontWeight = FontWeight.Medium)
                }
                token.startsWith("-") ->
                    SpanStyle(color = Everforest.Grey2)
                else ->
                    SpanStyle(color = Everforest.Fg)
            }
            withStyle(style) { append(token) }
            i = end
        }
        if (lineIdx < lines.lastIndex) append('\n')
    }
}

private fun highlightGeneralCode(code: String, lang: String): AnnotatedString = buildAnnotatedString {
    val hashComment = lang in setOf("py", "python", "yaml", "yml", "toml", "rb", "ruby", "dockerfile", "makefile")
    val isJsonLike = lang in setOf("json", "jsonc", "yaml", "yml")
    var i = 0
    val n = code.length

    while (i < n) {
        val c = code[i]

        if (c == '/' && i + 1 < n && code[i + 1] == '*') {
            val close = code.indexOf("*/", i + 2)
            val end = if (close >= 0) close + 2 else n
            withStyle(SpanStyle(color = Everforest.CommentGrey, fontStyle = FontStyle.Italic)) {
                append(code.substring(i, end))
            }
            i = end
            continue
        }

        if ((c == '/' && i + 1 < n && code[i + 1] == '/') || (hashComment && c == '#')) {
            val nl = code.indexOf('\n', i)
            val end = if (nl >= 0) nl else n
            withStyle(SpanStyle(color = Everforest.CommentGrey, fontStyle = FontStyle.Italic)) {
                append(code.substring(i, end))
            }
            i = end
            continue
        }

        if (c == '"' || c == '\'' || c == '`') {
            val triple = i + 2 < n && code[i + 1] == c && code[i + 2] == c
            var end = if (triple) i + 3 else i + 1
            if (triple) {
                val delim = "$c$c$c"
                val close = code.indexOf(delim, end)
                end = if (close >= 0) close + 3 else n
            } else {
                while (end < n && code[end] != c && code[end] != '\n') {
                    if (code[end] == '\\' && end + 1 < n) end += 2 else end++
                }
                if (end < n) code[end].let { if (it == c) end++ }
            }
            var after = end
            while (after < n && (code[after] == ' ' || code[after] == '\t')) after++
            val isKey = isJsonLike && c == '"' && after < n && code[after] == ':'
            withStyle(
                SpanStyle(
                    color = if (isKey) Everforest.Fg else Everforest.SoftSage,
                    fontWeight = if (isKey) FontWeight.Medium else FontWeight.Normal,
                ),
            ) {
                append(code.substring(i, end))
            }
            i = end
            continue
        }

        if (c.isLetter() || c == '_') {
            var end = i + 1
            while (end < n && (code[end].isLetterOrDigit() || code[end] == '_')) end++
            val word = code.substring(i, end)
            val style = when {
                word in CODE_KEYWORDS ->
                    SpanStyle(color = Everforest.SoftSand, fontWeight = FontWeight.Medium)
                word in CODE_LITERALS ->
                    SpanStyle(color = Everforest.SoftSage)
                else ->
                    SpanStyle(color = Everforest.Fg)
            }
            withStyle(style) { append(word) }
            i = end
            continue
        }

        append(c)
        i++
    }
}
