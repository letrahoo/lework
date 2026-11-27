import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'

const VALID_SKILL_NAME = /^[a-z0-9][a-z0-9_-]{0,95}$/i
const MAX_SKILL_BODY_BYTES = 48 * 1024
const MAX_LIST_HEADER_BYTES = 4096
const MAX_LIST_SKILLS = 64

export interface PiSkill {
  name: string
  description: string
}

// Do not render installed Markdown as clickable links or remotely loaded images.
function asSource(text: string): string {
  const fence = '`'.repeat(1 + Math.max(2, ...Array.from(text.matchAll(/`+/g), m => m[0].length)))
  return `${fence}\n${text}\n${fence}`
}

function resolveCanonicalDir(target: string): string {
  try {
    return fs.realpathSync(target)
  } catch {
    return path.resolve(target)
  }
}

function parseExtraStoreRoots(envValue: string | undefined): string[] {
  if (!envValue) return []
  const roots: string[] = []
  for (const raw of envValue.split(path.delimiter)) {
    const trimmed = raw.trim()
    if (!trimmed) continue
    try {
      roots.push(fs.realpathSync(trimmed))
    } catch {
      // ignore missing/unreadable configured store directory
    }
  }
  return roots
}

function isAllowedSkillDir(
  home: string,
  sharedReal: string,
  realDir: string,
  extraRoots: string[],
): boolean {
  const parent = path.dirname(realDir)
  if (parent === sharedReal) return true
  if (extraRoots.includes(parent)) return true

  // Allow external skill managers that symlink entries from ~/.agents/skills/<name>
  // into a dedicated hidden user skill store such as ~/.<store>/skills/<pkg> or
  // ~/.<store>/<subdir>/skills/<pkg>. Never follow links to arbitrary home/system paths.
  const relParent = path.relative(home, parent)
  if (!relParent || relParent.startsWith('..') || path.isAbsolute(relParent)) {
    return false
  }
  const parts = relParent.split(path.sep)
  const isHiddenTopDir =
    Boolean(parts[0]) && parts[0]!.startsWith('.') && parts[0]!.length > 1 && parts[0] !== '..'
  if (!isHiddenTopDir) return false
  return (parts.length === 2 && parts[1] === 'skills') || (parts.length === 3 && parts[2] === 'skills')
}

/**
 * Only the shared user install directory (`~/.agents/skills`) is discoverable.
 * Skill managers may install packages as symlinks into a hidden user skill store
 * (or directories listed in `LEWORK_SKILL_STORE_DIRS`). Never resolve model-supplied
 * paths, scan the whole home directory, or follow symlinks on `SKILL.md` itself.
 * Revalidate on each read.
 */
function readEntry(
  home: string,
  name: string,
  limit: number,
  extraRoots: string[],
): string | undefined {
  if (!VALID_SKILL_NAME.test(name)) return undefined
  const shared = path.join(home, '.agents', 'skills')
  const sharedReal = resolveCanonicalDir(shared)
  let fd: number | undefined
  try {
    const dir = fs.realpathSync(path.join(shared, name))
    if (!isAllowedSkillDir(home, sharedReal, dir, extraRoots)) return undefined
    const file = path.join(dir, 'SKILL.md')
    const flags =
      fs.constants.O_RDONLY |
      (fs.constants.O_NOFOLLOW ?? 0) |
      (fs.constants.O_NONBLOCK ?? 0)
    fd = fs.openSync(file, flags)
    if (!fs.fstatSync(fd).isFile()) return undefined
    const buf = Buffer.alloc(limit + 1)
    const len = fs.readSync(fd, buf, 0, buf.length, 0)
    return buf.toString('utf8', 0, Math.min(len, limit)) + (len > limit ? '\n[Skill text truncated]' : '')
  } catch {
    return undefined
  } finally {
    if (fd !== undefined) fs.closeSync(fd)
  }
}

export class PiSkillCatalog {
  private readonly home: string
  private readonly explicitExtraRoots?: string[]

  constructor(home = os.homedir(), extraStoreRoots?: string[]) {
    this.home = resolveCanonicalDir(home)
    this.explicitExtraRoots = extraStoreRoots?.map(resolveCanonicalDir)
  }

  private getExtraStoreRoots(): string[] {
    if (this.explicitExtraRoots) return this.explicitExtraRoots
    return parseExtraStoreRoots(process.env.LEWORK_SKILL_STORE_DIRS)
  }

  list(): PiSkill[] {
    const skills: PiSkill[] = []
    let entries: string[]
    try {
      entries = fs.readdirSync(path.join(this.home, '.agents', 'skills')).sort()
    } catch {
      return []
    }
    const extraRoots = this.getExtraStoreRoots()
    for (const name of entries) {
      const header = readEntry(this.home, name, MAX_LIST_HEADER_BYTES, extraRoots)
      if (!header?.trim()) continue
      const front = /^---\r?\n([\s\S]*?)\r?\n---(?:\r?\n|$)/.exec(header)?.[1] ?? ''
      const desc = /^description:[ \t]*(.*(?:\r?\n[ \t]+[^\r\n]*)*)/m.exec(front)?.[1] ?? ''
      const description = desc
        .replace(/^[>|][-+]?\s*/, '')
        .replace(/\s+/g, ' ')
        .trim()
        .replace(/^["']|["']$/g, '')
        .slice(0, 320)
      skills.push({ name, description })
      if (skills.length >= MAX_LIST_SKILLS) break
    }
    return skills
  }

  handleCommand(text: string): string | undefined {
    const [command, ...args] = text.trim().split(/\s+/)
    if (command !== '/skills' && command !== '/skill') return undefined
    const usage = '输入 /skills 查看本机技能；输入 /skill 技能名 读取说明（不会执行，也不会发送给模型）。'
    if (command === '/skills' && args.length === 0) {
      const catalog = this.list()
      if (catalog.length === 0) return `本机尚未安装共享技能（~/.agents/skills）。\n${usage}`
      const rows = catalog.map(s => `${s.name}${s.description ? ` — ${s.description}` : ''}`)
      return `本机技能（${rows.length} 项，最多显示 ${MAX_LIST_SKILLS} 项）\n\n${asSource(rows.join('\n'))}\n\n${usage}`
    }
    if (command !== '/skill' || args.length !== 1) return usage
    const name = args[0]!
    const body = readEntry(this.home, name, MAX_SKILL_BODY_BYTES, this.getExtraStoreRoots())
    if (!body?.trim()) return `未找到可读取的技能。${usage}`
    return `本机技能说明：${name}（仅读取）\n\n${asSource(body)}`
  }
}
