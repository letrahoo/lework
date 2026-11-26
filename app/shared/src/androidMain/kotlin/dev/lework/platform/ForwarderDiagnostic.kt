package dev.lework.platform

private const val DIAG_PREFIX = "LEWORK_FWD_DIAG "
private const val MILLIS = "(?:0|[1-9][0-9]{0,12})"
private const val FLAG = "(?:true|false|unknown)"
private val diagnosticLine = Regex(
    "${DIAG_PREFIX}port=([1-9][0-9]{0,4}) " +
        "(?:event=(?:preamble\\.(?:accept|reject)|dial\\.(?:start|ok|deadline|refused|other)) ms=$MILLIS|" +
        "event=dial\\.snapshot status_age_ms=(?:-1|$MILLIS) " +
        "backend=(?:NoState|InUseOtherUser|NeedsLogin|NeedsMachineAuth|Stopped|Starting|Running|unknown) " +
        "peer=(?:found|missing|unknown) handshake=(?:recent|older|not_reported|unknown) " +
        "path_hint=(?:direct_addr|relay_region|none|unknown) have_v4=$FLAG have_v6=$FLAG)",
)

/** Fail closed, including trailing characters, unexpected fields and out-of-range ports. */
internal fun forwarderDiagnosticMessage(line: String): String? {
    val match = diagnosticLine.matchEntire(line) ?: return null
    if (match.groupValues[1].toInt() !in 1..65535) return null
    return line.removePrefix(DIAG_PREFIX)
}
