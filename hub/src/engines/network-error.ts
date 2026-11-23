const NETWORK_ERROR_PATTERNS: RegExp[] = [
  /\bECONNRESET\b/i,
  /\bECONNREFUSED\b/i,
  /\bECONNABORTED\b/i,
  /\bENOTFOUND\b/i,
  /\bETIMEDOUT\b/i,
  /\bEAI_AGAIN\b/i,
  /\bEHOSTUNREACH\b/i,
  /\bENETUNREACH\b/i,
  /\bENETDOWN\b/i,
  /\bENOTCONN\b/i,
  /\bEPIPE\b/i,
  /\bUND_ERR_[A-Z0-9_]+\b/i,
  /\bERR_SOCKET_[A-Z0-9_]+\b/i,
  /\bERR_NETWORK\b/i,
  /\bERR_TLS_[A-Z0-9_]+\b/i,
  /fetch failed/i,
  /socket hang up/i,
  /network\s*(error|is unreachable|unreachable|changed|offline|down|timeout|failed)/i,
  /connection\s*(refused|reset|closed|aborted|lost|dropped|timed out|failed|error)/i,
  /api\s*connection\s*error|apiconnectionerror/i,
  /tls\s*handshake/i,
  /unable to resolve host|getaddrinfo/i,
  /no route to host/i,
  /broken pipe/i,
  /premature close/i,
  /unexpected\s+eof|\beof\b/i,
  /timed\s*out|\btimeout\b/i,
  /\boffline\b/i,
  /\bdns\b/i,
  /\b(502|503|504)\b/,
  /bad gateway|service unavailable|gateway timeout/i,
  /断网|网络|连接断开|连接超时|连接中断|超时|不可达|无法连接/,
]

export function isLikelyNetworkError(message: string | undefined | null): boolean {
  if (!message) return false
  return NETWORK_ERROR_PATTERNS.some(re => re.test(message))
}

export function extractErrorMessage(err: unknown): string {
  if (err instanceof Error) {
    const base = err.message?.trim() ?? ''
    const cause = (err as { cause?: unknown }).cause
    let causeDetail = ''
    if (cause && typeof cause === 'object') {
      const c = cause as { code?: unknown; message?: unknown }
      const codeStr = typeof c.code === 'string' ? c.code.trim() : ''
      const msgStr = typeof c.message === 'string' ? c.message.trim() : ''
      if (codeStr && msgStr && !msgStr.includes(codeStr)) {
        causeDetail = `${codeStr}: ${msgStr}`
      } else {
        causeDetail = msgStr || codeStr
      }
    } else if (typeof cause === 'string') {
      causeDetail = cause.trim()
    }
    if (causeDetail && (!base || /^fetch failed$/i.test(base) || !base.includes(causeDetail))) {
      return base ? `${base} (${causeDetail})` : causeDetail
    }
    return base || err.name || 'Unknown error'
  }
  return String(err ?? '').trim()
}

export function summarizeStderrLines(raw: string, maxLines = 4, maxLen = 500): string {
  const lines = raw
    .split(/\r?\n/)
    .map(l => l.trim())
    .filter(l => l.length > 0 && !/^warning:/i.test(l))
  return lines.slice(-maxLines).join('\n').slice(0, maxLen)
}

/**
 * Formats an engine/turn error for `AgentEvent` (`type: 'error'`).
 * Normalizes cryptic upstream network/connectivity failures with a clear Chinese prefix
 * while keeping the concise technical reason intact.
 */
export function formatEngineTurnError(err: unknown, fallbackStderr?: string): string {
  const rawPrimary = extractErrorMessage(err)
  const stderrClean = fallbackStderr ? summarizeStderrLines(fallbackStderr) : ''

  let combined = rawPrimary
  if (
    stderrClean &&
    (!combined || /^(ACP|Claude ACP|Codex app-server|AGY|agy).*(exited|异常结束)/i.test(combined))
  ) {
    combined = stderrClean
  }

  const clean = combined.replace(/\s+/g, ' ').trim().slice(0, 320)
  if (!clean) return '任务执行异常中断'
  if (isLikelyNetworkError(clean)) {
    if (clean.startsWith('网络连接异常')) return clean
    return `网络连接异常: ${clean}`
  }
  return clean
}
