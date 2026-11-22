import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import type { AgentEvent, SessionMeta } from '@lework/protocol'

export interface HostTranscriptEntry {
  meta: SessionMeta
  filePath: string
  nativeId: string
}

export interface CachedTranscript {
  mtimeMs: number
  size: number
  events: AgentEvent[]
  extraWorkspaces: string[]
  isRunning: boolean
  turnStartedAt?: number
  queuedPrompts?: string[]
  lastUserInputTs?: number
  recentUserTexts?: string[]
  model?: string
  reasoningEffort?: SessionMeta['reasoningEffort']
}

export interface ParsedHostTranscript {
  events: AgentEvent[]
  workspaces: string[]
  isRunning: boolean
  turnStartedAt?: number
  queuedPrompts?: string[]
  lastUserInputTs?: number
  recentUserTexts?: string[]
  model?: string
  reasoningEffort?: SessionMeta['reasoningEffort']
}

export function collectRecentFiles(
  rootDir: string,
  predicate: (p: string) => boolean,
  limit: number,
): Array<{ path: string; mtimeMs: number; birthtimeMs: number }> {
  const found: Array<{ path: string; mtimeMs: number; birthtimeMs: number }> = []
  const stack = [rootDir]
  while (stack.length > 0) {
    const dir = stack.pop()!
    let entries: fs.Dirent[]
    try {
      entries = fs.readdirSync(dir, { withFileTypes: true })
    } catch {
      continue
    }
    for (const e of entries) {
      const full = path.join(dir, e.name)
      if (e.isDirectory()) {
        stack.push(full)
      } else if (e.isFile() && predicate(full)) {
        try {
          const st = fs.statSync(full)
          found.push({ path: full, mtimeMs: st.mtimeMs, birthtimeMs: st.birthtimeMs })
        } catch {
          // ignore
        }
      }
    }
  }
  found.sort((a, b) => b.mtimeMs - a.mtimeMs)
  return found.slice(0, limit)
}

export function readFirstAndLastLines(
  filePath: string,
  firstCount: number,
  lastCount: number,
): { first: string[]; last: string[] } {
  const st = fs.statSync(filePath)
  const chunkSize = Math.min(st.size, 48 * 1024)
  const fd = fs.openSync(filePath, 'r')
  try {
    const headBuf = Buffer.alloc(chunkSize)
    fs.readSync(fd, headBuf, 0, chunkSize, 0)
    const first = headBuf.toString('utf8').split('\n').filter(Boolean).slice(0, firstCount)

    if (st.size <= chunkSize) {
      const all = headBuf.toString('utf8').split('\n').filter(Boolean)
      return { first, last: all.slice(-lastCount) }
    }
    const tailBuf = Buffer.alloc(chunkSize)
    fs.readSync(fd, tailBuf, 0, chunkSize, st.size - chunkSize)
    const last = tailBuf.toString('utf8').split('\n').slice(1).filter(Boolean).slice(-lastCount)
    return { first, last }
  } finally {
    fs.closeSync(fd)
  }
}

export function readFullSessionLines(filePath: string): string[] {
  const st = fs.statSync(filePath)
  const maxBytes = 24 * 1024 * 1024
  const fd = fs.openSync(filePath, 'r')
  try {
    const offset = Math.max(0, st.size - maxBytes)
    const len = st.size - offset
    const buf = Buffer.alloc(len)
    fs.readSync(fd, buf, 0, len, offset)
    const lines = buf.toString('utf8').split('\n')
    const clean = offset > 0 ? lines.slice(1) : lines
    return clean.filter(Boolean)
  } finally {
    fs.closeSync(fd)
  }
}

export function extractProjectRootFromToolInput(
  args: Record<string, unknown> | undefined,
  sink: string[],
  homeDir = os.homedir(),
): void {
  if (!args) return
  const rawPath = (args.Path ?? args.path ?? args.file_path ?? args.Cwd ?? args.cwd) as unknown
  if (typeof rawPath !== 'string' || !rawPath.startsWith('/')) return
  for (const bucket of ['my-projects', 'projects', 'repos', 'code', 'work', 'personal']) {
    const prefix = path.join(homeDir, bucket) + path.sep
    if (rawPath.startsWith(prefix)) {
      const rest = rawPath.slice(prefix.length)
      const repoName = rest.split(path.sep)[0]
      if (repoName && !repoName.startsWith('.')) {
        sink.push(path.join(homeDir, bucket, repoName))
      }
      return
    }
  }
}

export function mergeUniqueWorkspaces(candidates: string[], homeDir = os.homedir()): string[] {
  const out: string[] = []
  const seenReal = new Set<string>()
  const genericDirs = new Set([homeDir, path.join(homeDir, 'work'), path.join(homeDir, 'personal')])

  const specific: string[] = []
  const generic: string[] = []

  for (const raw of candidates) {
    if (!raw) continue
    try {
      const real = fs.realpathSync(raw)
      if (!fs.statSync(real).isDirectory()) continue
      if (seenReal.has(real)) continue
      seenReal.add(real)
      if (genericDirs.has(real)) {
        generic.push(real)
      } else {
        specific.push(real)
      }
    } catch {
      // ignore missing path
    }
  }
  out.push(...specific)
  if (out.length === 0 && generic.length > 0) {
    out.push(generic[0]!)
  }
  return out
}

export function classifyRawToolCall(
  rawName: string,
  rawInput?: string,
): {
  title: string
  kind: 'read' | 'edit' | 'delete' | 'move' | 'search' | 'execute' | 'think' | 'fetch' | 'other'
  input?: string
} {
  const short = rawName.replace(/^default_api:/, '')
  const lower = short.toLowerCase()

  // Detect Skill invocations either directly (`Skill`) or when reading `.../skills/<name>/SKILL.md`
  if (lower === 'skill' || lower === 'use_skill') {
    return { title: 'Skill', kind: 'other', input: rawInput }
  }
  if (rawInput) {
    const skillMatch = rawInput.match(/\/skills\/([A-Za-z0-9_.-]+)\/SKILL\.md/i)
    if (skillMatch?.[1]) {
      return {
        title: 'Skill',
        kind: 'other',
        input: JSON.stringify({ skill: skillMatch[1], raw: rawInput.slice(0, 600) }),
      }
    }
  }

  if (
    lower === 'bash' ||
    lower === 'run_command' ||
    lower === 'exec_command' ||
    lower === 'shell' ||
    lower === 'shell_command' ||
    lower === 'terminal'
  ) {
    return { title: 'Bash', kind: 'execute', input: rawInput }
  }

  if (lower === 'read' || lower === 'read_file' || lower === 'view_file') {
    return { title: 'Read', kind: 'read', input: rawInput }
  }

  if (
    lower === 'edit' ||
    lower === 'multiedit' ||
    lower === 'edit_file' ||
    lower === 'apply_patch' ||
    lower === 'replace_file_content'
  ) {
    return { title: 'Update', kind: 'edit', input: rawInput }
  }

  if (lower === 'write' || lower === 'write_file' || lower === 'create_file') {
    return { title: 'Write', kind: 'edit', input: rawInput }
  }

  if (lower === 'grep' || lower === 'glob' || lower === 'search' || lower === 'find_by_name' || lower === 'grep_search') {
    return { title: short, kind: 'search', input: rawInput }
  }

  if (lower === 'webfetch' || lower === 'websearch' || lower === 'search_web') {
    return { title: short, kind: 'fetch', input: rawInput }
  }

  if (lower === 'task' || lower === 'agent' || lower === 'invoke_subagent') {
    return { title: 'Task', kind: 'other', input: rawInput }
  }

  return { title: short, kind: 'other', input: rawInput }
}
