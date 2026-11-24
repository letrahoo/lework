package dev.lework.platform

private const val MAX_RUNTIME_LOG_MESSAGE_CHARS = 1_200

private val SECRET_PATTERNS = listOf(
    Regex("""\blework_(?:oa|dt)\.[A-Za-z0-9._-]+\b"""),
    Regex("""(?i)\bBearer\s+[A-Za-z0-9._~+/=-]{6,}\b"""),
    Regex("""(?i)\b(token|access_?token|pairCode|code|secret|authKey|password)\s*=\s*[^\s&;,"']+"""),
)

internal fun sanitizeRuntimeLogMessage(raw: String): String {
    var clean = raw
        .replace('\r', ' ')
        .replace('\n', ' ')
        .trim()
    for (pattern in SECRET_PATTERNS) {
        clean = pattern.replace(clean) { match ->
            val keyGroup = match.groups.getOrNull(1)?.value
            if (keyGroup != null) "$keyGroup=<redacted>" else "<redacted>"
        }
    }
    if (clean.length > MAX_RUNTIME_LOG_MESSAGE_CHARS) {
        clean = clean.take(MAX_RUNTIME_LOG_MESSAGE_CHARS) + "…"
    }
    return clean
}

/**
 * Strips credentials, query parameters, and fragments from a URL before emitting it to runtime logs
 * so pairing codes, OAuth tokens, or session secrets never touch disk.
 */
internal fun redactUrlForLog(rawUrl: String): String {
    val trimmed = rawUrl.trim()
    if (trimmed.isEmpty()) return "<empty>"
    val noFragment = trimmed.substringBefore('#')
    val noQuery = noFragment.substringBefore('?')
    val schemeIdx = noQuery.indexOf("://")
    if (schemeIdx <= 0) return noQuery.take(120)
    val scheme = noQuery.substring(0, schemeIdx + 3)
    val rest = noQuery.substring(schemeIdx + 3)
    val slashIdx = rest.indexOf('/')
    val authority = if (slashIdx >= 0) rest.substring(0, slashIdx) else rest
    val path = if (slashIdx >= 0) rest.substring(slashIdx) else ""
    val cleanAuthority = authority.substringAfterLast('@')
    return (scheme + cleanAuthority + path).take(200)
}

internal fun formatRuntimeLogLine(epochMs: Long, tag: String, message: String): String {
    val parts = localDateTimeParts(epochMs)
    val millis = ((epochMs % 1000L) + 1000L) % 1000L
    val ts = buildString(23) {
        append(parts.year.toString().padStart(4, '0'))
        append('-')
        append(parts.month.toString().padStart(2, '0'))
        append('-')
        append(parts.day.toString().padStart(2, '0'))
        append(' ')
        append(parts.hour.toString().padStart(2, '0'))
        append(':')
        append(parts.minute.toString().padStart(2, '0'))
        append(':')
        append(parts.second.toString().padStart(2, '0'))
        append('.')
        append(millis.toString().padStart(3, '0'))
    }
    val safeTag = tag.trim().ifEmpty { "APP" }.take(24)
    val safeMsg = sanitizeRuntimeLogMessage(message)
    return "$ts [$safeTag] $safeMsg"
}

private fun MatchGroupCollection.getOrNull(index: Int): MatchGroup? =
    if (index in indices) this[index] else null
