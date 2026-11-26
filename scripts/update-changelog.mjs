#!/usr/bin/env node
/**
 * Lework bilingual CHANGELOG validator and post-release synchronizer.
 *
 * Keeps `CHANGELOG.md` (English) and `CHANGELOG.zh-CN.md` (Simplified Chinese)
 * aligned with SemVer release tags (`vX.Y.Z`) published by `.github/workflows/release.yml`.
 *
 * Usage:
 *   node scripts/update-changelog.mjs --check [--strict-tags] [--cwd <dir>]
 *   node scripts/update-changelog.mjs --write [--dry-run] [--no-gh] [--repo owner/repo] [--cwd <dir>] [--github-output]
 */
import { execFileSync } from 'node:child_process'
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { compareSemver, parseSemver } from './resolve-version.mjs'

const DEFAULT_REPO_ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..')
const DEFAULT_GITHUB_REPO = 'letrahoo/lework'
const UNRELEASED_EN = 'Unreleased'
const UNRELEASED_ZH = '未发布 / Unreleased'

const VERSION_HEADING_RE = /^## \[(v\d+\.\d+\.\d+)\]\s*-\s*(\d{4}-\d{2}-\d{2})\s*$/gm
const LINK_DEF_RE = /^\[([^\]]+)\]:\s*(\S+)\s*$/gm
const COMPARE_HEAD_RE = /\/compare\/(v\d+\.\d+\.\d+)\.\.\.HEAD$/

function git(args, cwd) {
  try {
    return execFileSync('git', args, {
      cwd,
      encoding: 'utf8',
      stdio: ['ignore', 'pipe', 'ignore'],
    }).trim()
  } catch {
    return ''
  }
}

export function detectGithubRepo(cwd = DEFAULT_REPO_ROOT) {
  const remote = git(['remote', 'get-url', 'origin'], cwd)
  if (!remote) return DEFAULT_GITHUB_REPO
  const match = remote.match(/github\.com[:/]([^/\s]+\/[^/\s]+?)(?:\.git)?$/)
  return match ? match[1] : DEFAULT_GITHUB_REPO
}

export function parseChangelogDocument(content) {
  const tags = []
  let m
  VERSION_HEADING_RE.lastIndex = 0
  while ((m = VERSION_HEADING_RE.exec(content)) !== null) {
    tags.push(m[1])
  }

  const linkDefs = new Map()
  LINK_DEF_RE.lastIndex = 0
  while ((m = LINK_DEF_RE.exec(content)) !== null) {
    linkDefs.set(m[1], m[2])
  }

  const hasEnUnreleased = content.includes(`## [${UNRELEASED_EN}]`)
  const hasZhUnreleased = content.includes(`## [${UNRELEASED_ZH}]`)
  const unreleasedLabel = hasZhUnreleased ? UNRELEASED_ZH : hasEnUnreleased ? UNRELEASED_EN : null
  const unreleasedUrl = unreleasedLabel ? (linkDefs.get(unreleasedLabel) ?? null) : null
  const compareMatch = unreleasedUrl ? unreleasedUrl.match(COMPARE_HEAD_RE) : null
  const unreleasedCompareTarget = compareMatch ? compareMatch[1] : null

  return {
    unreleasedLabel,
    unreleasedUrl,
    unreleasedCompareTarget,
    tags,
    linkDefs,
  }
}

export function validateChangelogPair(enContent, zhContent, opts = {}) {
  const { reachableTags = [], strictTags = false } = opts
  const errors = []
  const warnings = []

  const en = parseChangelogDocument(enContent)
  const zh = parseChangelogDocument(zhContent)

  for (const [name, doc, expectedUnreleased] of [
    ['CHANGELOG.md', en, UNRELEASED_EN],
    ['CHANGELOG.zh-CN.md', zh, UNRELEASED_ZH],
  ]) {
    if (doc.unreleasedLabel !== expectedUnreleased) {
      errors.push(`${name}: missing "## [${expectedUnreleased}]" heading`)
    }
    if (doc.tags.length === 0) {
      errors.push(`${name}: contains no version headings`)
      continue
    }
    const seen = new Set()
    for (const tag of doc.tags) {
      if (seen.has(tag)) {
        errors.push(`${name}: duplicate version heading [${tag}]`)
      }
      seen.add(tag)
      if (!doc.linkDefs.has(tag)) {
        errors.push(`${name}: missing bottom reference link for [${tag}]`)
      }
    }
    const latest = doc.tags[0]
    if (!doc.unreleasedUrl) {
      errors.push(`${name}: missing bottom reference link for [${expectedUnreleased}]`)
    } else if (doc.unreleasedCompareTarget !== latest) {
      errors.push(
        `${name}: [${expectedUnreleased}] compare link points to ${doc.unreleasedCompareTarget ?? doc.unreleasedUrl}, expected ${latest}`,
      )
    }
    for (let i = 1; i < doc.tags.length; i++) {
      const prev = parseSemver(doc.tags[i - 1])
      const curr = parseSemver(doc.tags[i])
      if (prev && curr && compareSemver(prev, curr) <= 0) {
        errors.push(`${name}: tags out of descending SemVer order (${doc.tags[i - 1]} before ${doc.tags[i]})`)
      }
    }
  }

  if (en.tags.length !== zh.tags.length || en.tags.some((t, idx) => zh.tags[idx] !== t)) {
    errors.push(
      `CHANGELOG.md and CHANGELOG.zh-CN.md tag lists diverge (EN: ${en.tags.length} tags, ZH: ${zh.tags.length} tags)`,
    )
  }

  const documented = new Set(en.tags)
  const latestDocTag = en.tags[0] ?? null
  const latestDocSemver = latestDocTag ? parseSemver(latestDocTag) : null
  const missingTags = reachableTags.filter(tag => {
    const parsed = parseSemver(tag)
    if (!parsed) return false
    if (documented.has(tag)) return false
    return !latestDocSemver || compareSemver(parsed, latestDocSemver) > 0
  })

  if (missingTags.length > 0) {
    const msg = `Undocumented release tags newer than ${latestDocTag ?? 'none'}: ${missingTags.join(', ')}`
    if (strictTags) {
      errors.push(msg)
    } else {
      warnings.push(msg)
    }
  }

  return {
    ok: errors.length === 0,
    errors,
    warnings,
    latestTag: latestDocTag,
    missingTags,
  }
}

export function stripConventionalPrefix(rawTitle) {
  const cleaned = String(rawTitle ?? '').replace(/\s*\[(?:skip release|no release)\]\s*/gi, '').trim()
  const match = cleaned.match(/^([a-zA-Z]+)(?:\(([^)]+)\))?(!)?\s*[:：]\s*(.+)$/)
  if (!match) {
    return { type: '', scope: '', breaking: false, title: cleaned }
  }
  return {
    type: match[1].toLowerCase(),
    scope: (match[2] ?? '').trim(),
    breaking: Boolean(match[3]),
    title: match[4].trim(),
  }
}

export function isChangelogMaintenanceEntry(title, body = '') {
  const combined = `${title ?? ''}\n${body ?? ''}`
  const parsed = stripConventionalPrefix(title)
  if ((parsed.type === 'docs' || parsed.type === 'chore') && parsed.scope.toLowerCase() === 'changelog') {
    return true
  }
  if (/^(?:docs|chore)(?:\([^)]+\))?\s*[:：]\s*(?:add|update|sync|backfill)\s+.*changelog/i.test(String(title ?? '').trim())) {
    return true
  }
  if (/^\s*sync bilingual CHANGELOG\b/i.test(parsed.title)) {
    return true
  }
  return /\[changelog-sync-bot\]/i.test(combined)
}

export function classifyChange(title, labels = []) {
  const lowerLabels = labels.map(l => String(l).toLowerCase())
  if (lowerLabels.some(l => l.includes('bug') || l === 'fix')) return 'fixed'
  if (lowerLabels.some(l => l.includes('feature') || l === 'enhancement')) return 'added'

  const parsed = stripConventionalPrefix(title)
  if (parsed.type === 'feat' || parsed.type === 'feature') return 'added'
  if (parsed.type === 'fix' || parsed.type === 'bugfix' || parsed.type === 'hotfix') return 'fixed'
  if (parsed.type) return 'changed'

  const lower = parsed.title.toLowerCase()
  if (/^(?:修复|修正|解决|fix\b|prevent\b|eliminate\b|resolve\b|avoid\b)/.test(lower)) return 'fixed'
  if (/^(?:新增|支持|增加|添加|add\b|support\b|introduce\b|implement\b)/.test(lower)) return 'added'
  return 'changed'
}

export function parsePrNumbersFromCommit(subject, body = '') {
  const found = []
  const mergeMatch = String(subject ?? '').match(/^Merge pull request #(\d+)\b/)
  if (mergeMatch) {
    found.push(Number(mergeMatch[1]))
  }
  const squashMatch = String(subject ?? '').match(/\(#(\d+)\)\s*$/)
  if (squashMatch) {
    found.push(Number(squashMatch[1]))
  }
  if (found.length === 0 && body) {
    const bodyMatch = String(body).match(/^Merge pull request #(\d+)\b/m)
    if (bodyMatch) found.push(Number(bodyMatch[1]))
  }
  return [...new Set(found)]
}

function formatDateInShanghai(isoTimestamp) {
  const date = isoTimestamp ? new Date(isoTimestamp) : new Date()
  if (Number.isNaN(date.getTime())) {
    return new Date().toISOString().slice(0, 10)
  }
  // Format in Asia/Shanghai (+08:00) to match existing CHANGELOG.md date convention.
  const shifted = new Date(date.getTime() + 8 * 60 * 60 * 1000)
  return shifted.toISOString().slice(0, 10)
}

export function getReachableSemverTags(cwd = DEFAULT_REPO_ROOT, ref = 'HEAD') {
  const raw = git(['tag', '--merged', ref, '--list', 'v[0-9]*.[0-9]*.[0-9]*'], cwd)
  if (!raw) return []
  return raw
    .split('\n')
    .map(s => s.trim())
    .filter(s => parseSemver(s) !== null)
    .sort((a, b) => compareSemver(parseSemver(a), parseSemver(b)))
}

function fetchPrMetadataViaGh(prNumber, repo, cwd) {
  try {
    const out = execFileSync(
      'gh',
      ['api', `repos/${repo}/pulls/${prNumber}`, '--jq', '{title: .title, labels: [.labels[].name]}'],
      { cwd, encoding: 'utf8', stdio: ['ignore', 'pipe', 'ignore'] },
    ).trim()
    if (!out) return null
    const parsed = JSON.parse(out)
    return {
      title: typeof parsed.title === 'string' ? parsed.title.trim() : '',
      labels: Array.isArray(parsed.labels) ? parsed.labels.map(String) : [],
    }
  } catch {
    return null
  }
}

export function collectTagReleaseData(prevTag, targetTag, opts = {}) {
  const { cwd = DEFAULT_REPO_ROOT, repo = DEFAULT_GITHUB_REPO, useGh = true } = opts
  const tagIsoDate = git(['log', '-1', '--format=%cI', targetTag], cwd)
  const date = formatDateInShanghai(tagIsoDate)
  const range = prevTag ? `${prevTag}..${targetTag}` : targetTag
  const rawLog = git(['log', '--first-parent', '--reverse', '--format=%H%x1f%s%x1f%b%x1e', range], cwd)

  const prEntries = []
  const seenPrs = new Set()
  const directCommits = []

  for (const record of rawLog.split('\x1e')) {
    const trimmed = record.trim()
    if (!trimmed) continue
    const [sha = '', subject = '', body = ''] = trimmed.split('\x1f')
    if (!sha || !subject) continue

    const prNumbers = parsePrNumbersFromCommit(subject, body)
    if (prNumbers.length > 0) {
      for (const prNumber of prNumbers) {
        if (seenPrs.has(prNumber)) continue
        seenPrs.add(prNumber)

        const firstBodyLine = body
          .split('\n')
          .map(line => line.trim())
          .find(Boolean) ?? ''
        let title = subject.startsWith('Merge pull request #')
          ? (firstBodyLine || subject)
          : subject.replace(/\s*\(#\d+\)\s*$/, '').trim()
        let labels = []

        if (useGh) {
          const remote = fetchPrMetadataViaGh(prNumber, repo, cwd)
          if (remote?.title) title = remote.title
          if (remote?.labels?.length) labels = remote.labels
        }

        if (isChangelogMaintenanceEntry(title, body)) continue
        prEntries.push({
          prNumber,
          title,
          labels,
          bucket: classifyChange(title, labels),
        })
      }
    } else if (!subject.startsWith('Merge ')) {
      if (isChangelogMaintenanceEntry(subject, body)) continue
      directCommits.push({
        sha: sha.slice(0, 7),
        fullSha: sha,
        title: subject,
        bucket: classifyChange(subject),
      })
    }
  }

  const entries = prEntries.length > 0 ? prEntries : directCommits
  return {
    tag: targetTag,
    prevTag: prevTag ?? null,
    date,
    entries,
  }
}

function formatCleanDisplayTitle(rawTitle) {
  const parsed = stripConventionalPrefix(rawTitle)
  const base = parsed.title || rawTitle.trim()
  return base.replace(/\.$/, '')
}

export function formatVersionSection(releaseData, opts = {}) {
  const { isZh = false, repo = DEFAULT_GITHUB_REPO } = opts
  const buckets = { added: [], changed: [], fixed: [] }
  for (const entry of releaseData.entries) {
    const target = buckets[entry.bucket] ?? buckets.changed
    target.push(entry)
  }

  const headings = isZh
    ? [
        ['added', '### 新增'],
        ['changed', '### 变更'],
        ['fixed', '### 修复'],
      ]
    : [
        ['added', '### Added'],
        ['changed', '### Changed'],
        ['fixed', '### Fixed'],
      ]

  const lines = [`## [${releaseData.tag}] - ${releaseData.date}`]
  let wroteAny = false

  for (const [key, heading] of headings) {
    const items = buckets[key]
    if (!items || items.length === 0) continue
    wroteAny = true
    lines.push('', heading)
    for (const item of items) {
      const displayTitle = formatCleanDisplayTitle(item.title)
      if (item.prNumber) {
        const prLink = `[#${item.prNumber}](https://github.com/${repo}/pull/${item.prNumber})`
        lines.push(isZh ? `- **${displayTitle}**（${prLink}）` : `- **${displayTitle}** (${prLink})`)
      } else {
        const commitLink = `[\`${item.sha}\`](https://github.com/${repo}/commit/${item.fullSha})`
        lines.push(isZh ? `- **${displayTitle}**（${commitLink}）` : `- **${displayTitle}** (${commitLink})`)
      }
    }
  }

  if (!wroteAny) {
    lines.push(
      '',
      isZh ? '### 变更' : '### Changed',
      isZh ? '- 例行维护与构建发布。' : '- Maintenance and release updates.',
    )
  }

  return lines.join('\n')
}

export function insertReleaseSections(content, releasesDesc, opts = {}) {
  const { isZh = false, repo = DEFAULT_GITHUB_REPO } = opts
  if (releasesDesc.length === 0) return content

  const doc = parseChangelogDocument(content)
  const existing = new Set(doc.tags)
  const freshReleases = releasesDesc.filter(r => !existing.has(r.tag))
  if (freshReleases.length === 0) return content

  const unreleasedLabel = isZh ? UNRELEASED_ZH : UNRELEASED_EN
  const anchorPattern = new RegExp(`(## \\[${unreleasedLabel.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')}\\]\\s*\\n\\s*---\\s*\\n)\\n*`)
  if (!anchorPattern.test(content)) {
    throw new Error(`Unable to locate "## [${unreleasedLabel}]" separator in changelog`)
  }

  const renderedSections = freshReleases
    .map(r => formatVersionSection(r, { isZh, repo }))
    .join('\n\n---\n\n')

  let updated = content.replace(anchorPattern, `$1\n${renderedSections}\n\n---\n\n`)

  const newestTag = freshReleases[0].tag
  const unreleasedLinkRe = new RegExp(
    `^\\[${unreleasedLabel.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')}\\]:\\s*\\S+\\s*$`,
    'm',
  )
  const newCompareDefs = freshReleases.map(r =>
    r.prevTag
      ? `[${r.tag}]: https://github.com/${repo}/compare/${r.prevTag}...${r.tag}`
      : `[${r.tag}]: https://github.com/${repo}/releases/tag/${r.tag}`,
  )
  const replacementBlock = [
    `[${unreleasedLabel}]: https://github.com/${repo}/compare/${newestTag}...HEAD`,
    ...newCompareDefs,
  ].join('\n')

  if (unreleasedLinkRe.test(updated)) {
    updated = updated.replace(unreleasedLinkRe, replacementBlock)
  } else {
    updated = `${updated.trimEnd()}\n\n${replacementBlock}\n`
  }

  return updated
}

export function syncChangelogs(opts = {}) {
  const {
    cwd = DEFAULT_REPO_ROOT,
    repo = detectGithubRepo(cwd),
    write = false,
    dryRun = false,
    useGh = true,
  } = opts

  const enPath = path.join(cwd, 'CHANGELOG.md')
  const zhPath = path.join(cwd, 'CHANGELOG.zh-CN.md')
  const enContent = fs.readFileSync(enPath, 'utf8')
  const zhContent = fs.readFileSync(zhPath, 'utf8')

  const enDoc = parseChangelogDocument(enContent)
  const latestDocTag = enDoc.tags[0] ?? null
  const latestDocSemver = latestDocTag ? parseSemver(latestDocTag) : null
  const reachable = getReachableSemverTags(cwd, 'HEAD')

  const missingAsc = reachable.filter(tag => {
    const parsed = parseSemver(tag)
    if (!parsed) return false
    if (enDoc.tags.includes(tag)) return false
    return !latestDocSemver || compareSemver(parsed, latestDocSemver) > 0
  })

  if (missingAsc.length === 0) {
    return {
      updated: false,
      addedTags: [],
      latestTag: latestDocTag ?? '',
      enContent,
      zhContent,
    }
  }

  const releasesAsc = []
  for (const tag of missingAsc) {
    const idx = reachable.indexOf(tag)
    const prevTag = idx > 0 ? reachable[idx - 1] : latestDocTag
    releasesAsc.push(collectTagReleaseData(prevTag, tag, { cwd, repo, useGh }))
  }
  const releasesDesc = [...releasesAsc].reverse()

  const nextEn = insertReleaseSections(enContent, releasesDesc, { isZh: false, repo })
  const nextZh = insertReleaseSections(zhContent, releasesDesc, { isZh: true, repo })
  const changed = nextEn !== enContent || nextZh !== zhContent

  if (write && !dryRun && changed) {
    fs.writeFileSync(enPath, nextEn, 'utf8')
    fs.writeFileSync(zhPath, nextZh, 'utf8')
  }

  return {
    updated: changed,
    addedTags: releasesDesc.map(r => r.tag),
    latestTag: releasesDesc[0].tag,
    enContent: nextEn,
    zhContent: nextZh,
  }
}

function getArgValue(args, flag) {
  const idx = args.indexOf(flag)
  return idx >= 0 ? (args[idx + 1] ?? '') : ''
}

function main() {
  const args = process.argv.slice(2)
  const cwd = getArgValue(args, '--cwd') || DEFAULT_REPO_ROOT
  const repo = getArgValue(args, '--repo') || detectGithubRepo(cwd)
  const writeGhOutput = args.includes('--github-output')

  if (args.includes('--check')) {
    const strictTags = args.includes('--strict-tags')
    const enContent = fs.readFileSync(path.join(cwd, 'CHANGELOG.md'), 'utf8')
    const zhContent = fs.readFileSync(path.join(cwd, 'CHANGELOG.zh-CN.md'), 'utf8')
    const reachableTags = getReachableSemverTags(cwd, 'HEAD')
    const res = validateChangelogPair(enContent, zhContent, { reachableTags, strictTags })
    for (const w of res.warnings) {
      console.warn(`WARN: ${w}`)
    }
    if (!res.ok) {
      console.error('FAIL: changelog validation failed:\n  - ' + res.errors.join('\n  - '))
      process.exit(1)
    }
    console.log(
      `OK: bilingual CHANGELOG validated (latest=${res.latestTag ?? 'none'}, reachableTags=${reachableTags.length})`,
    )
    return
  }

  const write = args.includes('--write')
  const dryRun = args.includes('--dry-run') || !write
  const useGh = !args.includes('--no-gh')
  const result = syncChangelogs({ cwd, repo, write, dryRun, useGh })

  if (writeGhOutput && process.env.GITHUB_OUTPUT) {
    const lines = [
      `updated=${result.updated}`,
      `added_tags=${result.addedTags.join(',')}`,
      `latest_tag=${result.latestTag}`,
    ]
    fs.appendFileSync(process.env.GITHUB_OUTPUT, lines.join('\n') + '\n', 'utf8')
  }

  if (!result.updated) {
    console.log(`OK: bilingual CHANGELOG already up to date (latest=${result.latestTag || 'none'})`)
    return
  }

  console.log(
    `${dryRun ? '[dry-run] ' : ''}Synced bilingual CHANGELOG with ${result.addedTags.length} tag(s): ${result.addedTags.join(', ')}`,
  )
}

const isMain = process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)
if (isMain) {
  main()
}
