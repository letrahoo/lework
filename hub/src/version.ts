import fs from 'node:fs'

function readHubPackageVersion(): string {
  try {
    const raw = fs.readFileSync(new URL('../package.json', import.meta.url), 'utf8')
    const parsed = JSON.parse(raw) as { version?: unknown }
    if (typeof parsed.version === 'string' && parsed.version.trim().length > 0) {
      return parsed.version.trim()
    }
  } catch {
    // Fallback if package.json cannot be resolved in a stripped runtime bundle
  }
  return '0.7.0'
}

export const HUB_VERSION = readHubPackageVersion()
