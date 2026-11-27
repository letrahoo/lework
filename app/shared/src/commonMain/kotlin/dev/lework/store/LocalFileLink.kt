package dev.lework.store

internal data class ParsedLocalFileLink(
    val path: String,
    val startLine: Int? = null,
    val endLine: Int? = null,
)

private val EXPLICIT_SCHEME_RE = Regex("^([a-zA-Z][a-zA-Z0-9+.-]*):(.*)$")
private val HASH_LINE_RANGE_RE = Regex("^[Ll]?(\\d+)(?:-[Ll]?(\\d+))?$")
private val COLON_LINE_RANGE_RE = Regex("^(.*):(\\d+)(?:-(\\d+))?$")
private val FILE_EXTENSION_RE = Regex("^[A-Za-z0-9._\\-\\u0080-\\uFFFF]+\\.[A-Za-z0-9]{1,12}$")

/**
 * Parses Markdown link targets that refer to local files into a normalized path and optional line range.
 *
 * Supported forms:
 * - `file:///Users/alice/notes.md#L10-L20`
 * - `file://localhost/Users/alice/%E6%80%BB%E8%A7%88.html`
 * - `/abs/path/to/file.kt:42`
 * - `~/.agent-memory/concepts/entries/internal-infra/rgo.md`
 * - `app/shared/src/commonMain/kotlin/dev/lework/store/ConsoleStore.kt#L25`
 * - `./OVERVIEW.md` or `../entries/rgo.md`
 *
 * Returns `null` for external web URLs (`http://`, `https://`), non-file schemes (`mailto:`, `javascript:`, etc.),
 * remote `file://other-host/...` URLs, or pure same-document `#anchor` links.
 */
internal fun parseLocalFileLink(rawUrl: String): ParsedLocalFileLink? {
    val trimmed = rawUrl.trim()
    if (trimmed.isEmpty() || trimmed.startsWith("#")) return null
    if (trimmed.any { it == '\n' || it == '\r' || it == '\u0000' }) return null

    val schemeMatch = EXPLICIT_SCHEME_RE.matchEntire(trimmed)
    val withoutScheme: String
    val fromFileUri: Boolean
    if (schemeMatch != null) {
        val scheme = schemeMatch.groupValues[1].lowercase()
        val rest = schemeMatch.groupValues[2]
        // Single-letter Windows drive path like C:\... is not treated as an external URI scheme.
        if (scheme == "file") {
            fromFileUri = true
            withoutScheme = when {
                rest.startsWith("///") -> rest.substring(2)
                rest.startsWith("//localhost/") -> rest.substring("//localhost".length)
                rest.startsWith("//") -> return null
                rest.startsWith("/") -> rest
                else -> return null
            }
        } else if (scheme.length == 1 && (rest.startsWith("/") || rest.startsWith("\\"))) {
            fromFileUri = false
            withoutScheme = trimmed
        } else {
            return null
        }
    } else {
        fromFileUri = false
        withoutScheme = trimmed
    }

    val hashIdx = withoutScheme.indexOf('#')
    val beforeHash = if (hashIdx >= 0) withoutScheme.substring(0, hashIdx) else withoutScheme
    val fragment = if (hashIdx >= 0) withoutScheme.substring(hashIdx + 1) else ""
    val beforeQuery = beforeHash.substringBefore('?')
    if (beforeQuery.isBlank()) return null

    val decodedRawPath = if (fromFileUri || beforeQuery.contains('%')) {
        percentDecodeUtf8(beforeQuery)
    } else {
        beforeQuery
    }.trim()
    if (decodedRawPath.isEmpty() || decodedRawPath.any { it == '\n' || it == '\r' || it == '\u0000' }) {
        return null
    }

    var startLine: Int? = null
    var endLine: Int? = null
    var cleanPath = decodedRawPath

    if (fragment.isNotBlank()) {
        val lineMatch = HASH_LINE_RANGE_RE.matchEntire(fragment.trim())
        if (lineMatch != null) {
            val s = lineMatch.groupValues[1].toIntOrNull()?.takeIf { it > 0 }
            val e = lineMatch.groupValues[2].toIntOrNull()?.takeIf { it > 0 }
            if (s != null) {
                startLine = s
                endLine = if (e != null && e >= s) e else s
            }
        }
    }

    if (startLine == null) {
        val colonMatch = COLON_LINE_RANGE_RE.matchEntire(cleanPath)
        if (colonMatch != null) {
            val base = colonMatch.groupValues[1]
            val s = colonMatch.groupValues[2].toIntOrNull()?.takeIf { it > 0 }
            val e = colonMatch.groupValues[3].toIntOrNull()?.takeIf { it > 0 }
            if (base.isNotBlank() && s != null) {
                cleanPath = base
                startLine = s
                endLine = if (e != null && e >= s) e else s
            }
        }
    }

    if (!looksLikeLocalFilePath(cleanPath, fromFileUri)) return null

    return ParsedLocalFileLink(
        path = cleanPath,
        startLine = startLine,
        endLine = endLine,
    )
}

private fun looksLikeLocalFilePath(path: String, fromFileUri: Boolean): Boolean {
    if (path.isBlank()) return false
    if (fromFileUri) return path.startsWith("/")
    if (path.startsWith("/") || path.startsWith("~/") || path.startsWith("./") || path.startsWith("../")) {
        return path.length > 1
    }
    if (path.contains('/')) {
        return !path.startsWith("//")
    }
    return FILE_EXTENSION_RE.matches(path)
}

/**
 * Pure Kotlin UTF-8 percent-decoder safe for KMP `commonMain` (no `java.net.URLDecoder`).
 */
internal fun percentDecodeUtf8(input: String): String {
    if (!input.contains('%')) return input
    val out = StringBuilder(input.length)
    val bytes = ByteArray(input.length)
    var i = 0
    while (i < input.length) {
        if (input[i] == '%' && i + 2 < input.length) {
            var byteCount = 0
            while (i + 2 < input.length && input[i] == '%') {
                val hi = hexDigit(input[i + 1])
                val lo = hexDigit(input[i + 2])
                if (hi < 0 || lo < 0) break
                bytes[byteCount++] = ((hi shl 4) or lo).toByte()
                i += 3
            }
            if (byteCount > 0) {
                out.append(bytes.decodeToString(0, byteCount))
                continue
            }
        }
        out.append(input[i])
        i++
    }
    return out.toString()
}

private fun hexDigit(c: Char): Int = when (c) {
    in '0'..'9' -> c - '0'
    in 'a'..'f' -> c - 'a' + 10
    in 'A'..'F' -> c - 'A' + 10
    else -> -1
}

/**
 * Resolves a parsed local file path against an optional base directory (workspace cwd or current previewed
 * document's parent directory) and lexically normalizes `.` and `..` segments cleanly.
 *
 * Preserves leading `~/` so the Hub expands `~` against the Hub host's home directory.
 */
internal fun resolveLocalFilePath(parsedPath: String, baseDirectory: String?): String {
    val clean = parsedPath.trim().replace('\\', '/')
    if (clean.startsWith("~/")) {
        val normalizedTail = normalizeRelativeSegments(clean.removePrefix("~/"))
        return if (normalizedTail.isEmpty()) "~" else "~/$normalizedTail"
    }
    if (clean.startsWith("/")) {
        return "/" + normalizeRelativeSegments(clean.removePrefix("/"))
    }
    val base = baseDirectory?.trim()?.replace('\\', '/')?.trimEnd('/').orEmpty()
    if (base.isEmpty()) return normalizeRelativeSegments(clean)
    return if (base.startsWith("~/")) {
        val combined = "${base.removePrefix("~/")}/$clean"
        val normalizedTail = normalizeRelativeSegments(combined)
        if (normalizedTail.isEmpty()) "~" else "~/$normalizedTail"
    } else if (base.startsWith("/")) {
        val combined = "${base.removePrefix("/")}/$clean"
        "/" + normalizeRelativeSegments(combined)
    } else {
        normalizeRelativeSegments("$base/$clean")
    }
}

private fun normalizeRelativeSegments(path: String): String {
    val stack = mutableListOf<String>()
    for (seg in path.split('/')) {
        when {
            seg.isEmpty() || seg == "." -> Unit
            seg == ".." -> if (stack.isNotEmpty()) stack.removeAt(stack.lastIndex)
            else -> stack.add(seg)
        }
    }
    return stack.joinToString("/")
}

internal fun parentDirectoryOf(path: String): String? {
    val trimmed = path.trim().replace('\\', '/').trimEnd('/')
    val slash = trimmed.lastIndexOf('/')
    if (slash < 0) return null
    if (slash == 0) return "/"
    return trimmed.substring(0, slash)
}

internal fun fileExtensionOf(path: String): String {
    val name = path.trimEnd('/').substringAfterLast('/')
    val dot = name.lastIndexOf('.')
    if (dot <= 0 || dot == name.lastIndex) return ""
    return name.substring(dot + 1).lowercase()
}

internal fun isMarkdownExtension(ext: String): Boolean =
    ext == "md" || ext == "markdown" || ext == "mdx"

internal fun isHtmlExtension(ext: String): Boolean =
    ext == "html" || ext == "htm"

/**
 * Candidate companion Markdown paths for an HTML document (e.g. Concept Memory's `overview.html` -> `OVERVIEW.md`),
 * enabling rich native preview with clickable doc links directly inside Lework Desktop.
 */
internal fun companionMarkdownCandidates(htmlPath: String): List<String> {
    val ext = fileExtensionOf(htmlPath)
    if (!isHtmlExtension(ext)) return emptyList()
    val parent = parentDirectoryOf(htmlPath) ?: return emptyList()
    val fileName = htmlPath.trimEnd('/').substringAfterLast('/')
    val stem = fileName.substringBeforeLast('.')
    val candidates = mutableListOf<String>()
    if (stem.equals("overview", ignoreCase = true)) {
        candidates.add("$parent/OVERVIEW.md")
        candidates.add("$parent/overview.md")
    } else if (stem.isNotBlank()) {
        candidates.add("$parent/$stem.md")
        candidates.add("$parent/${stem.uppercase()}.md")
    }
    return candidates.distinct()
}

private val HTML_SCRIPT_STYLE_RE = Regex("<(script|style|noscript|svg|template)\\b[^>]*>[\\s\\S]*?</\\1>", RegexOption.IGNORE_CASE)
private val HTML_COMMENT_RE = Regex("<!--[\\s\\S]*?-->")
private val HTML_TITLE_RE = Regex("<title\\b[^>]*>([\\s\\S]*?)</title>", RegexOption.IGNORE_CASE)
private val HTML_HEADING_RE = Regex("<h([1-6])\\b[^>]*>([\\s\\S]*?)</h\\1>", RegexOption.IGNORE_CASE)
private val HTML_LINK_RE = Regex("<a\\b[^>]*href=[\"']([^\"']+)[\"'][^>]*>([\\s\\S]*?)</a>", RegexOption.IGNORE_CASE)
private val HTML_LI_RE = Regex("<li\\b[^>]*>([\\s\\S]*?)</li>", RegexOption.IGNORE_CASE)
private val HTML_BR_RE = Regex("<br\\s*/?>", RegexOption.IGNORE_CASE)
private val HTML_HR_RE = Regex("<hr\\s*/?>", RegexOption.IGNORE_CASE)
private val HTML_BLOCK_END_RE = Regex("</(p|div|section|article|header|footer|tr|blockquote|pre|ul|ol|table)>", RegexOption.IGNORE_CASE)
private val HTML_TD_END_RE = Regex("</(td|th)>", RegexOption.IGNORE_CASE)
private val HTML_ANY_TAG_RE = Regex("<[^>]+>")

/**
 * Converts arbitrary HTML into a clean, readable Markdown summary for safe in-app preview when
 * no companion Markdown file exists.
 */
internal fun extractReadablePreviewFromHtml(html: String): String {
    if (html.isBlank()) return ""
    val title = HTML_TITLE_RE.find(html)?.groupValues?.getOrNull(1)
        ?.let { decodeBasicHtmlEntities(HTML_ANY_TAG_RE.replace(it, "")).trim() }
        ?.takeIf { it.isNotEmpty() }

    var body = html
    val bodyOpen = Regex("<body\\b[^>]*>", RegexOption.IGNORE_CASE).find(body)
    if (bodyOpen != null) {
        body = body.substring(bodyOpen.range.last + 1)
    }
    val bodyClose = Regex("</body>", RegexOption.IGNORE_CASE).find(body)
    if (bodyClose != null) {
        body = body.substring(0, bodyClose.range.first)
    }

    body = HTML_COMMENT_RE.replace(body, "")
    body = HTML_SCRIPT_STYLE_RE.replace(body, "")
    body = HTML_HEADING_RE.replace(body) { m ->
        val level = m.groupValues[1].toIntOrNull()?.coerceIn(1, 4) ?: 2
        val text = decodeBasicHtmlEntities(HTML_ANY_TAG_RE.replace(m.groupValues[2], "")).trim()
        if (text.isEmpty()) "" else "\n\n${"#".repeat(level)} $text\n\n"
    }
    body = HTML_LINK_RE.replace(body) { m ->
        val href = m.groupValues[1].trim()
        val label = decodeBasicHtmlEntities(HTML_ANY_TAG_RE.replace(m.groupValues[2], "")).trim()
        if (label.isEmpty()) "" else if (href.isNotEmpty() && !href.startsWith("#")) "[$label]($href)" else label
    }
    body = HTML_LI_RE.replace(body) { m ->
        val text = decodeBasicHtmlEntities(HTML_ANY_TAG_RE.replace(m.groupValues[1], "")).trim()
        if (text.isEmpty()) "" else "\n- $text"
    }
    body = HTML_BR_RE.replace(body, "\n")
    body = HTML_HR_RE.replace(body, "\n\n---\n\n")
    body = HTML_TD_END_RE.replace(body, " · ")
    body = HTML_BLOCK_END_RE.replace(body, "\n\n")
    body = HTML_ANY_TAG_RE.replace(body, "")
    body = decodeBasicHtmlEntities(body)

    val cleanedLines = body.lines()
        .map { it.trim().replace(Regex("\\s{2,}"), " ") }
        .fold(mutableListOf<String>()) { acc, line ->
            if (line.isEmpty()) {
                if (acc.isNotEmpty() && acc.last().isNotEmpty()) acc.add("")
            } else {
                acc.add(line)
            }
            acc
        }
        .joinToString("\n")
        .trim()

    return buildString {
        if (title != null && !cleanedLines.startsWith("# $title")) {
            append("# ").append(title)
            if (cleanedLines.isNotEmpty()) append("\n\n")
        }
        append(cleanedLines)
    }.trim()
}

private fun decodeBasicHtmlEntities(text: String): String = text
    .replace("&nbsp;", " ")
    .replace("&amp;", "&")
    .replace("&lt;", "<")
    .replace("&gt;", ">")
    .replace("&quot;", "\"")
    .replace("&#39;", "'")
    .replace("&apos;", "'")
    .replace("&middot;", "·")
    .replace("&mdash;", "—")
    .replace("&ndash;", "–")
