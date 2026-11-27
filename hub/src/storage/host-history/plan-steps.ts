import type { AgentEvent, PlanEntry, PlanEntryStatus } from '@lework/protocol'

export type PlanStepToolKind =
  | 'update_steps'
  | 'todowrite'
  | 'task_create'
  | 'task_update'
  | 'update_plan'

export function normalizePlanStatus(raw: unknown): PlanEntryStatus {
  if (typeof raw !== 'string') return 'pending'
  const lower = raw.trim().toLowerCase().replace(/[\s-]+/g, '_')
  if (lower === 'done' || lower === 'completed' || lower === 'complete' || lower === 'finished') {
    return 'completed'
  }
  if (lower === 'in_progress' || lower === 'inprogress' || lower === 'active' || lower === 'running') {
    return 'in_progress'
  }
  if (lower === 'skipped' || lower === 'skip' || lower === 'cancelled' || lower === 'canceled') {
    return 'skipped'
  }
  if (lower === 'failed' || lower === 'fail' || lower === 'error') {
    return 'failed'
  }
  return 'pending'
}

export function isPlanEntryTerminal(status: string): boolean {
  return status === 'completed' || status === 'skipped' || status === 'failed'
}

export function isPlanStepToolName(rawName: string): PlanStepToolKind | null {
  const short = rawName.replace(/^default_api:/, '').trim().toLowerCase()
  if (short === 'update_steps') return 'update_steps'
  if (short === 'todowrite' || short === 'todo_write') return 'todowrite'
  if (short === 'taskcreate' || short === 'task_create') return 'task_create'
  if (short === 'taskupdate' || short === 'task_update') return 'task_update'
  if (short === 'update_plan') return 'update_plan'
  return null
}

function tryParseJson(raw: string): unknown {
  const trimmed = raw.trim()
  if (!trimmed) return undefined
  if (
    (trimmed.startsWith('[') && trimmed.endsWith(']')) ||
    (trimmed.startsWith('{') && trimmed.endsWith('}'))
  ) {
    try {
      return JSON.parse(trimmed)
    } catch {
      return undefined
    }
  }
  return undefined
}

function extractRawArray(raw: unknown, depth = 0): unknown[] | null {
  if (depth > 3 || raw == null) return null
  if (Array.isArray(raw)) return raw
  if (typeof raw === 'string') {
    const parsed = tryParseJson(raw)
    return parsed !== undefined ? extractRawArray(parsed, depth + 1) : null
  }
  if (typeof raw === 'object') {
    const obj = raw as Record<string, unknown>
    for (const key of ['steps', 'todos', 'plan', 'entries', 'items']) {
      if (key in obj) {
        const found = extractRawArray(obj[key], depth + 1)
        if (found) return found
      }
    }
  }
  return null
}

export function parsePlanEntriesFromRaw(raw: unknown): PlanEntry[] {
  const arr = extractRawArray(raw)
  if (!arr) return []
  const out: PlanEntry[] = []
  for (const item of arr) {
    if (!item || typeof item !== 'object' || Array.isArray(item)) continue
    const obj = item as Record<string, unknown>
    const rawContent =
      typeof obj.title === 'string'
        ? obj.title
        : typeof obj.content === 'string'
          ? obj.content
          : typeof obj.step === 'string'
            ? obj.step
            : typeof obj.subject === 'string'
              ? obj.subject
              : typeof obj.description === 'string'
                ? obj.description
                : ''
    const content = rawContent.replace(/\s+/g, ' ').trim()
    if (!content) continue
    out.push({
      content,
      status: normalizePlanStatus(obj.status),
    })
  }
  return out
}

export function summarizePlanProgress(
  entries: PlanEntry[],
): { stepsDone: number; stepsTotal: number } | undefined {
  if (entries.length === 0) return undefined
  const stepsDone = entries.filter(e => e.status === 'completed' || e.status === 'skipped').length
  return { stepsDone, stepsTotal: entries.length }
}

/**
 * Symmetric execution-step turn hygiene tracker shared across Hub event extraction and
 * host-history transcript parsers (mirrored in KMP `ConsoleState.kt`):
 * - `plan.updated` installs the latest step list and resets turn-boundary flags.
 * - Ending a turn (`turn.done`, inactive `session.state`, or host parser turn completion)
 *   preserves the step list for that turn's summary card while marking `turnEnded = true`.
 *   If a post-plan user turn already ran and finished without updating the plan, it clears.
 * - A subsequent `message.user` clears any plan that is all-terminal, belonged to an already-ended
 *   earlier turn, or already survived an earlier post-plan user turn without update. An immediate
 *   mid-turn follow-up while the plan's turn is still running keeps the plan visible.
 */
export class PlanTurnHygieneTracker {
  private entries: PlanEntry[] = []
  private turnEnded = false
  private seenPostUpdateUserTurn = false

  get currentEntries(): PlanEntry[] {
    return this.entries
  }

  onPlanUpdated(entries: PlanEntry[]): void {
    this.entries = entries
    this.turnEnded = false
    this.seenPostUpdateUserTurn = false
  }

  /**
   * Marks the current turn as ended. Returns `true` if a previously non-empty plan
   * was cleared because a post-plan user turn completed without updating it.
   */
  onTurnEnded(): boolean {
    if (this.entries.length === 0) {
      this.turnEnded = false
      this.seenPostUpdateUserTurn = false
      return false
    }
    if (this.seenPostUpdateUserTurn) {
      this.entries = []
      this.turnEnded = false
      this.seenPostUpdateUserTurn = false
      return true
    }
    this.turnEnded = true
    return false
  }

  /**
   * Handles a new user message. Returns `true` if a previously non-empty plan was cleared.
   */
  onUserMessage(): boolean {
    if (this.entries.length === 0) return false
    const allTerminal = this.entries.every(e => isPlanEntryTerminal(e.status))
    if (allTerminal || this.turnEnded || this.seenPostUpdateUserTurn) {
      this.entries = []
      this.turnEnded = false
      this.seenPostUpdateUserTurn = false
      return true
    }
    this.seenPostUpdateUserTurn = true
    return false
  }
}

/**
 * Computes the latest active plan snapshot for initial paged `stream.subscribe`,
 * matching client turn hygiene (preserves an interrupted/completed plan for the latest turn
 * and clears stale plans once superseded by a new user turn).
 */
export function extractLatestPlanFromEvents(events: AgentEvent[]): PlanEntry[] | undefined {
  const tracker = new PlanTurnHygieneTracker()
  for (const ev of events) {
    if (ev.type === 'plan.updated') {
      tracker.onPlanUpdated(ev.entries)
    } else if (ev.type === 'message.user') {
      tracker.onUserMessage()
    } else if (
      ev.type === 'turn.done' ||
      (ev.type === 'session.state' &&
        (ev.state === 'idle' || ev.state === 'error' || ev.state === 'closed'))
    ) {
      tracker.onTurnEnded()
    }
  }
  const latest = tracker.currentEntries
  return latest.length > 0 ? latest : undefined
}

interface TrackedClaudeTask {
  id: string
  callId?: string
  content: string
  status: PlanEntryStatus
}

function parseInputObject(raw: unknown): Record<string, unknown> | null {
  if (!raw) return null
  if (typeof raw === 'string') {
    const parsed = tryParseJson(raw)
    return parsed && typeof parsed === 'object' && !Array.isArray(parsed)
      ? (parsed as Record<string, unknown>)
      : null
  }
  return typeof raw === 'object' && !Array.isArray(raw) ? (raw as Record<string, unknown>) : null
}

function extractCreatedTaskIdFromOutput(output: string): string | undefined {
  if (!output) return undefined
  const parsed = tryParseJson(output)
  if (parsed && typeof parsed === 'object' && !Array.isArray(parsed)) {
    const obj = parsed as Record<string, unknown>
    const rawId = obj.id ?? obj.taskId ?? obj.task_id
    if (typeof rawId === 'string' && rawId.trim()) return rawId.trim()
    if (typeof rawId === 'number' && Number.isFinite(rawId)) return String(rawId)
  }
  const m =
    output.match(/\bTask\s+#?([A-Za-z0-9_-]+)\s+created\b/i) ??
    output.match(/["']?(?:task_?id|id)["']?\s*[:=]\s*["']?#?([A-Za-z0-9_-]+)["']?/i)
  return m?.[1]?.trim() || undefined
}

/**
 * Stateful tracker that normalizes Claude Code's `TodoWrite`, `TaskCreate`, and `TaskUpdate`
 * tool calls into unified `PlanEntry[]` snapshots.
 */
export class ClaudeTaskStepTracker {
  private tasks: TrackedClaudeTask[] = []
  private autoSeq = 1

  applyToolUse(toolName: string, callId: string, rawInput: unknown): PlanEntry[] | null {
    const kind = isPlanStepToolName(toolName)
    if (!kind) return null

    if (kind === 'todowrite' || kind === 'update_steps' || kind === 'update_plan') {
      const entries = parsePlanEntriesFromRaw(rawInput)
      this.tasks = entries.map((e, idx) => ({
        id: String(idx + 1),
        content: e.content,
        status: e.status,
      }))
      this.autoSeq = this.tasks.length + 1
      return this.snapshot()
    }

    const obj = parseInputObject(rawInput)
    if (!obj) return null

    if (kind === 'task_create') {
      const rawContent =
        typeof obj.subject === 'string'
          ? obj.subject
          : typeof obj.title === 'string'
            ? obj.title
            : typeof obj.description === 'string'
              ? obj.description
              : ''
      const content = rawContent.replace(/\s+/g, ' ').trim()
      if (!content) return null
      const explicitId =
        typeof obj.taskId === 'string' && obj.taskId.trim()
          ? obj.taskId.trim()
          : typeof obj.id === 'string' && obj.id.trim()
            ? obj.id.trim()
            : String(this.autoSeq++)
      this.tasks.push({
        id: explicitId,
        callId,
        content,
        status: normalizePlanStatus(obj.status),
      })
      return this.snapshot()
    }

    if (kind === 'task_update') {
      const rawId = obj.taskId ?? obj.task_id ?? obj.id
      const taskId =
        typeof rawId === 'string'
          ? rawId.trim()
          : typeof rawId === 'number' && Number.isFinite(rawId)
            ? String(rawId)
            : ''
      if (!taskId) return null
      const idx = this.tasks.findIndex(t => t.id === taskId)
      const rawStatus = typeof obj.status === 'string' ? obj.status.trim().toLowerCase() : ''
      if (rawStatus === 'deleted' || rawStatus === 'delete' || rawStatus === 'removed') {
        if (idx >= 0) {
          this.tasks.splice(idx, 1)
          return this.snapshot()
        }
        return null
      }
      const nextSubject =
        typeof obj.subject === 'string' && obj.subject.trim()
          ? obj.subject.replace(/\s+/g, ' ').trim()
          : typeof obj.title === 'string' && obj.title.trim()
            ? obj.title.replace(/\s+/g, ' ').trim()
            : undefined
      if (idx >= 0) {
        const cur = this.tasks[idx]!
        this.tasks[idx] = {
          ...cur,
          content: nextSubject ?? cur.content,
          status: rawStatus ? normalizePlanStatus(rawStatus) : cur.status,
        }
        return this.snapshot()
      }
      if (nextSubject) {
        this.tasks.push({
          id: taskId,
          content: nextSubject,
          status: rawStatus ? normalizePlanStatus(rawStatus) : 'pending',
        })
        return this.snapshot()
      }
    }

    return null
  }

  applyToolResult(callId: string, outputText: string): void {
    const resolvedId = extractCreatedTaskIdFromOutput(outputText)
    if (!resolvedId) return
    const target = this.tasks.find(t => t.callId === callId)
    if (target) {
      target.id = resolvedId
    }
  }

  reset(): void {
    this.tasks = []
    this.autoSeq = 1
  }

  snapshot(): PlanEntry[] {
    return this.tasks.map(t => ({ content: t.content, status: t.status }))
  }
}

export function extractLatestAgyStepProgress(
  lines: string[],
): { stepsDone: number; stepsTotal: number } | undefined {
  for (let i = lines.length - 1; i >= 0; i--) {
    const line = lines[i]
    if (!line || !line.includes('update_steps')) continue
    try {
      const o = JSON.parse(line) as {
        tool_calls?: Array<{ name?: string; args?: unknown; arguments?: unknown }>
      }
      if (!Array.isArray(o.tool_calls)) continue
      for (let t = o.tool_calls.length - 1; t >= 0; t--) {
        const tc = o.tool_calls[t]
        if (!tc?.name || isPlanStepToolName(tc.name) !== 'update_steps') continue
        const raw = tc.args ?? tc.arguments
        const entries = parsePlanEntriesFromRaw(raw)
        const progress = summarizePlanProgress(entries)
        if (progress) return progress
      }
    } catch {
      // ignore malformed line
    }
  }
  return undefined
}

export function extractLatestClaudeStepProgress(
  lines: string[],
): { stepsDone: number; stepsTotal: number } | undefined {
  const tracker = new ClaudeTaskStepTracker()
  let sawPlan = false
  for (const line of lines) {
    if (!line) continue
    try {
      const o = JSON.parse(line) as {
        type?: string
        message?: { role?: string; content?: unknown }
      }
      if (o.type === 'user' && Array.isArray(o.message?.content)) {
        for (const item of o.message.content as Array<Record<string, unknown>>) {
          if (item?.type === 'tool_result' && typeof item.tool_use_id === 'string') {
            const text =
              typeof item.content === 'string'
                ? item.content
                : Array.isArray(item.content)
                  ? (item.content as Array<{ text?: unknown }>)
                      .map(c => (typeof c?.text === 'string' ? c.text : ''))
                      .join('\n')
                  : ''
            tracker.applyToolResult(item.tool_use_id, text)
          }
        }
      } else if (o.message?.role === 'assistant' && Array.isArray(o.message.content)) {
        for (const part of o.message.content as Array<Record<string, unknown>>) {
          if (part?.type === 'tool_use' && typeof part.name === 'string') {
            const kind = isPlanStepToolName(part.name)
            if (kind) {
              const callId = typeof part.id === 'string' ? part.id : ''
              const res = tracker.applyToolUse(part.name, callId, part.input)
              if (res !== null) sawPlan = true
            }
          }
        }
      }
    } catch {
      // ignore malformed line
    }
  }
  if (!sawPlan) return undefined
  return summarizePlanProgress(tracker.snapshot())
}
