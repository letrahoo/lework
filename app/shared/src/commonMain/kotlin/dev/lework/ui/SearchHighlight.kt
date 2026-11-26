package dev.lework.ui

import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight

internal val LocalSearchHighlightQuery = compositionLocalOf<String?> { null }
internal val LocalSearchHighlightPrimary = compositionLocalOf { true }

internal fun findSearchMatchRanges(
    text: String,
    query: String,
): List<IntRange> {
    val terms = query.trim().split(Regex("\\s+")).filter { it.isNotBlank() }.distinctBy { it.lowercase() }
    if (terms.isEmpty() || text.isEmpty()) return emptyList()
    val lower = text.lowercase()
    val ranges = mutableListOf<IntRange>()
    for (term in terms) {
        val needle = term.lowercase()
        var pos = 0
        while (pos < lower.length) {
            val found = lower.indexOf(needle, startIndex = pos)
            if (found < 0) break
            ranges.add(found until (found + needle.length))
            pos = found + needle.length
        }
    }
    if (ranges.isEmpty()) return emptyList()
    val sorted = ranges.sortedBy { it.first }
    val merged = mutableListOf<IntRange>()
    for (r in sorted) {
        val last = merged.lastOrNull()
        if (last != null && r.first <= last.last + 1) {
            merged[merged.lastIndex] = last.first..maxOf(last.last, r.last)
        } else {
            merged.add(r)
        }
    }
    return merged
}

internal fun searchMatchSpanStyle(primary: Boolean): SpanStyle = if (primary) {
    SpanStyle(
        color = Everforest.BgDim,
        background = Everforest.BoldGold,
        fontWeight = FontWeight.SemiBold,
    )
} else {
    SpanStyle(
        color = Everforest.BoldGold,
        background = Everforest.BoldGold.copy(alpha = 0.24f),
        fontWeight = FontWeight.SemiBold,
    )
}

internal fun applySearchTermHighlights(
    source: AnnotatedString,
    query: String?,
    primary: Boolean = true,
): AnnotatedString {
    val cleanQuery = query?.trim().orEmpty()
    if (cleanQuery.isEmpty() || source.text.isEmpty()) return source
    val ranges = findSearchMatchRanges(source.text, cleanQuery)
    if (ranges.isEmpty()) return source
    val style = searchMatchSpanStyle(primary)
    return buildAnnotatedString {
        append(source)
        for (r in ranges) {
            addStyle(style, r.first, (r.last + 1).coerceAtMost(source.length))
        }
    }
}

internal fun highlightPlainTextForSearch(
    text: String,
    query: String?,
    primary: Boolean = true,
): AnnotatedString {
    val cleanQuery = query?.trim().orEmpty()
    if (cleanQuery.isEmpty() || text.isEmpty()) return AnnotatedString(text)
    val ranges = findSearchMatchRanges(text, cleanQuery)
    if (ranges.isEmpty()) return AnnotatedString(text)
    val style = searchMatchSpanStyle(primary)
    return buildAnnotatedString {
        var cursor = 0
        for (r in ranges) {
            if (r.first > cursor) {
                append(text.substring(cursor, r.first))
            }
            pushStyle(style)
            append(text.substring(r.first, (r.last + 1).coerceAtMost(text.length)))
            pop()
            cursor = (r.last + 1).coerceAtMost(text.length)
        }
        if (cursor < text.length) {
            append(text.substring(cursor))
        }
    }
}
