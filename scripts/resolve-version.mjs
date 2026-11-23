#!/usr/bin/env node
/**
 * Lework unified cross-platform version resolver & release calculator.
 *
 * Versioning scheme:
 *   - Canonical product version: SemVer `X.Y.Z` (Git tag `vX.Y.Z`), baseline floored by root `package.json`.
 *   - Android `versionCode`: `MAJOR * 1_000_000 + MINOR * 10_000 + PATCH * 100`
 *       (e.g. 0.7.0 -> 70000, 0.7.1 -> 70100, 1.0.0 -> 1000000; strictly monotonic & always > legacy 15).
 *   - macOS Desktop `jpackage` `packageVersion`:
 *       jpackage requires MAJOR >= 1 on macOS DMG bundles, so pre-1.0 versions `0.Y.Z` map to `1.Y.Z`
 *       (continuing existing `0.6.9 -> 1.6.9` upgrade path), while `>= 1.0.0` maps 1:1 to `X.Y.Z`.
 *       The visible UI label & DMG filename always use the canonical product version `vX.Y.Z`.
 *   - iOS `CFBundleShortVersionString`: `X.Y.Z`, `CFBundleVersion`: `YYYYMMDD.HHmm` (UTC).
 *
 * Usage:
 *   node scripts/resolve-version.mjs --next [--bump auto|patch|minor|major] [--version X.Y.Z] [--json] [--github-output]
 *   node scripts/resolve-version.mjs --check
 *   node scripts/resolve-version.mjs --sync <X.Y.Z>
 */
import { execFileSync } from 'node:child_process'
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const REPO_ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..')
const SEMVER_RE = /^v?(\d+)\.(\d+)\.(\d+)$/

export function parseSemver(raw) {
  const m = String(raw ?? '').trim().match(SEMVER_RE)
  if (!m) return null
  return { major: Number(m[1]), minor: Number(m[2]), patch: Number(m[3]) }
}

export function formatSemver(v) {
  return `${v.major}.${v.minor}.${v.patch}`
}

export function compareSemver(a, b) {
  if (a.major !== b.major) return a.major - b.major
  if (a.minor !== b.minor) return a.minor - b.minor
  return a.patch - b.patch
}

export function computeAndroidVersionCode(v) {
  return v.major * 1_000_000 + v.minor * 10_000 + v.patch * 100
}

export function computeDesktopPackageVersion(v) {
  // macOS jpackage requires MAJOR >= 1; shifting major by +1 (0.7.0 -> 1.7.0, 1.0.0 -> 2.0.0)
  // guarantees strict monotonicity across the 0.x -> 1.0 transition while continuing 0.6.9 -> 1.6.9.
  return `${v.major + 1}.${v.minor}.${v.patch}`
}

export function computeIosBuildVersion(now = new Date()) {
  const p = n => String(n).padStart(2, '0')
  return `${now.getUTCFullYear()}${p(now.getUTCMonth() + 1)}${p(now.getUTCDate())}.${p(now.getUTCHours())}${p(now.getUTCMinutes())}`
}

function git(args) {
  try {
    return execFileSync('git', args, { cwd: REPO_ROOT, encoding: 'utf8', stdio: ['ignore', 'pipe', 'ignore'] }).trim()
  } catch {
    return ''
  }
}

function readJsonVersion(relPath) {
  const full = path.join(REPO_ROOT, relPath)
  const obj = JSON.parse(fs.readFileSync(full, 'utf8'))
  return String(obj.version ?? '').trim()
}

function readPlistShortVersion(relPath) {
  const raw = fs.readFileSync(path.join(REPO_ROOT, relPath), 'utf8')
  const m = raw.match(/<key>CFBundleShortVersionString<\/key>\s*<string>([^<]+)<\/string>/)
  return m ? m[1].trim() : ''
}

function getLatestReachableTag() {
  const raw = git(['tag', '--merged', 'HEAD', '--list', 'v[0-9]*.[0-9]*.[0-9]*', '--sort=-v:refname'])
  for (const line of raw.split('\n')) {
    const trimmed = line.trim()
    const parsed = parseSemver(trimmed)
    if (parsed) return { tag: trimmed, semver: parsed }
  }
  return null
}

function getHeadTag() {
  const raw = git(['tag', '--points-at', 'HEAD', '--list', 'v[0-9]*.[0-9]*.[0-9]*'])
  for (const line of raw.split('\n')) {
    const trimmed = line.trim()
    if (parseSemver(trimmed)) return trimmed
  }
  return ''
}

function bumpSemver(v, mode) {
  if (mode === 'major') return { major: v.major + 1, minor: 0, patch: 0 }
  if (mode === 'minor') return { major: v.major, minor: v.minor + 1, patch: 0 }
  return { major: v.major, minor: v.minor, patch: v.patch + 1 }
}

function inferAutoBumpFromCommits(sinceTag) {
  const range = sinceTag ? `${sinceTag}..HEAD` : 'HEAD'
  const log = git(['log', range, '--format=%s%n%b%n---COMMIT---'])
  if (!log) return 'patch'
  if (/(^|\n)\s*BREAKING CHANGE:/i.test(log) || /\[major\]/i.test(log) || /^[a-z]+(\([^)]+\))?!:/m.test(log)) {
    return 'major'
  }
  if (/\[minor\]/i.test(log)) {
    return 'minor'
  }
  return 'patch'
}

function resolveNext({ bump = 'auto', explicitVersion = '', force = false }) {
  const baseRaw = readJsonVersion('package.json')
  const baseSemver = parseSemver(baseRaw)
  if (!baseSemver) {
    throw new Error(`Invalid SemVer in package.json: "${baseRaw}"`)
  }

  const latest = getLatestReachableTag()
  const headTag = getHeadTag()
  const headSubject = git(['log', '-1', '--format=%s'])
  const headBody = git(['log', '-1', '--format=%b'])
  const headText = `${headSubject}\n${headBody}`

  let shouldRelease = true
  let skipReason = ''

  if (!force && !explicitVersion) {
    if (headTag) {
      shouldRelease = false
      skipReason = `HEAD is already tagged with ${headTag}`
    } else if (/\[skip release\]|\[no release\]/i.test(headText) || /^chore\(release\):/i.test(headSubject)) {
      shouldRelease = false
      skipReason = 'Commit explicitly skipped release'
    }
  }

  let nextSemver
  let strategy = ''

  if (explicitVersion) {
    const parsed = parseSemver(explicitVersion)
    if (!parsed) throw new Error(`Invalid explicit --version "${explicitVersion}" (expected X.Y.Z)`)
    nextSemver = parsed
    strategy = 'explicit'
  } else if (bump === 'major' || bump === 'minor' || bump === 'patch') {
    const anchor = latest && compareSemver(latest.semver, baseSemver) >= 0 ? latest.semver : baseSemver
    nextSemver = bumpSemver(anchor, bump)
    strategy = `manual-${bump}`
  } else {
    // Auto mode: if package.json baseline is strictly ahead of latest tag (e.g. 0.7.0 > v0.6.9),
    // release the declared milestone directly; otherwise auto-bump from latest tag.
    if (!latest || compareSemver(baseSemver, latest.semver) > 0) {
      nextSemver = baseSemver
      strategy = latest ? `milestone-floor(${latest.tag}->v${formatSemver(baseSemver)})` : 'initial-baseline'
    } else {
      const inferred = inferAutoBumpFromCommits(latest.tag)
      nextSemver = bumpSemver(latest.semver, inferred)
      strategy = `auto-${inferred}(from ${latest.tag})`
    }
  }

  const version = formatSemver(nextSemver)
  return {
    shouldRelease,
    skipReason,
    strategy,
    baseVersion: baseRaw,
    latestTag: latest?.tag ?? '',
    version,
    tag: `v${version}`,
    androidVersionCode: computeAndroidVersionCode(nextSemver),
    desktopPackageVersion: computeDesktopPackageVersion(nextSemver),
    iosBuildVersion: computeIosBuildVersion(),
  }
}

function runCheck() {
  const rootVer = readJsonVersion('package.json')
  const hubVer = readJsonVersion('hub/package.json')
  const iosVer = readPlistShortVersion('app/iosApp/iosApp/Info.plist')
  const parsedRoot = parseSemver(rootVer)
  if (!parsedRoot) {
    console.error(`FAIL: root package.json version "${rootVer}" is not valid X.Y.Z SemVer`)
    process.exit(1)
  }
  const mismatches = []
  if (hubVer !== rootVer) mismatches.push(`hub/package.json (${hubVer}) != package.json (${rootVer})`)
  if (iosVer !== rootVer) mismatches.push(`app/iosApp/iosApp/Info.plist (${iosVer}) != package.json (${rootVer})`)
  if (mismatches.length > 0) {
    console.error('FAIL: version drift detected:\n  - ' + mismatches.join('\n  - '))
    process.exit(1)
  }
  console.log(
    `OK: unified version=${rootVer}, androidVersionCode=${computeAndroidVersionCode(parsedRoot)}, desktopPackageVersion=${computeDesktopPackageVersion(parsedRoot)}`,
  )
}

function runSync(targetRaw) {
  const parsed = parseSemver(targetRaw)
  if (!parsed) {
    console.error(`Invalid target SemVer: "${targetRaw}" (expected X.Y.Z)`)
    process.exit(1)
  }
  const version = formatSemver(parsed)
  const iosBuild = computeIosBuildVersion()

  for (const rel of ['package.json', 'hub/package.json']) {
    const full = path.join(REPO_ROOT, rel)
    const raw = fs.readFileSync(full, 'utf8')
    const updated = raw.replace(/"version"\s*:\s*"[^"]+"/, `"version": "${version}"`)
    fs.writeFileSync(full, updated, 'utf8')
  }

  const plistPath = path.join(REPO_ROOT, 'app/iosApp/iosApp/Info.plist')
  let plist = fs.readFileSync(plistPath, 'utf8')
  plist = plist.replace(
    /(<key>CFBundleShortVersionString<\/key>\s*<string>)[^<]+(<\/string>)/,
    `$1${version}$2`,
  )
  plist = plist.replace(
    /(<key>CFBundleVersion<\/key>\s*<string>)[^<]+(<\/string>)/,
    `$1${iosBuild}$2`,
  )
  fs.writeFileSync(plistPath, plist, 'utf8')

  console.log(
    `Synced version=${version} (androidVersionCode=${computeAndroidVersionCode(parsed)}, desktopPackageVersion=${computeDesktopPackageVersion(parsed)}, iosBuild=${iosBuild})`,
  )
}

function main() {
  const args = process.argv.slice(2)
  if (args.includes('--check')) {
    runCheck()
    return
  }
  const syncIdx = args.indexOf('--sync')
  if (syncIdx >= 0) {
    runSync(args[syncIdx + 1])
    return
  }

  const bumpIdx = args.indexOf('--bump')
  const bump = bumpIdx >= 0 ? (args[bumpIdx + 1] ?? 'auto') : 'auto'
  const verIdx = args.indexOf('--version')
  const explicitVersion = verIdx >= 0 ? (args[verIdx + 1] ?? '') : ''
  const force = args.includes('--force')
  const writeGh = args.includes('--github-output')
  const asJson = args.includes('--json')

  const result = resolveNext({ bump, explicitVersion, force })

  if (writeGh && process.env.GITHUB_OUTPUT) {
    const lines = [
      `should_release=${result.shouldRelease}`,
      `skip_reason=${result.skipReason}`,
      `strategy=${result.strategy}`,
      `version=${result.version}`,
      `tag=${result.tag}`,
      `android_version_code=${result.androidVersionCode}`,
      `desktop_package_version=${result.desktopPackageVersion}`,
      `ios_build_version=${result.iosBuildVersion}`,
    ]
    fs.appendFileSync(process.env.GITHUB_OUTPUT, lines.join('\n') + '\n', 'utf8')
  }

  if (asJson) {
    console.log(JSON.stringify(result, null, 2))
  } else {
    console.log(
      `should_release=${result.shouldRelease} tag=${result.tag} version=${result.version} androidVersionCode=${result.androidVersionCode} desktopPackageVersion=${result.desktopPackageVersion} strategy=${result.strategy}${result.skipReason ? ` skip_reason="${result.skipReason}"` : ''}`,
    )
  }
}

const isMain = process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)
if (isMain) {
  main()
}
