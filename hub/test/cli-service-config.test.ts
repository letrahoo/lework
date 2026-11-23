import { spawnSync } from 'node:child_process'
import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import { collectLanIpv4Addresses } from '../src/bonjour.ts'
import { loadOrCreateConfig, resolveDefaultHostName } from '../src/config.ts'
import {
  augmentedPathEnv,
  extraSearchPaths,
  generateServiceDescriptor,
  listLanAddresses,
  resolveSshServerAddress,
} from '../src/platform/index.ts'

const __dirname = path.dirname(fileURLToPath(import.meta.url))
const repoRoot = path.resolve(__dirname, '../..')
const cliEntry = path.join(repoRoot, 'hub', 'src', 'cli.ts')

describe('Linux Remote Devbox CLI, systemd service descriptor & network interface filtering', () => {
  let tmpHome: string

  beforeEach(() => {
    tmpHome = fs.mkdtempSync(path.join(os.tmpdir(), 'lework-cli-test-'))
  })

  afterEach(() => {
    fs.rmSync(tmpHome, { recursive: true, force: true })
  })

  it('generates Linux systemd --user unit with Node native TS fallback and Go/fnm PATH', () => {
    const emptyRepo = path.join(tmpHome, 'isolated-repo')
    fs.mkdirSync(emptyRepo, { recursive: true })
    const desc = generateServiceDescriptor({
      role: 'master',
      repoRoot: emptyRepo,
      nodeBin: '/home/dev/.local/share/fnm/node-versions/v26.13.0/installation/bin/node',
      platformOverride: 'linux',
    })

    expect(desc.platform).toBe('linux')
    expect(desc.unitOrLabel).toBe('lework-hub-master.service')
    expect(desc.pathHint).toContain(path.join('.config', 'systemd', 'user', 'lework-hub-master.service'))
    expect(desc.content).toContain('--experimental-transform-types')
    expect(desc.content).toContain('/home/dev/.local/share/fnm/node-versions/v26.13.0/installation/bin')
    expect(desc.content).toContain('/usr/local/go/bin')
    expect(desc.content).toContain('/usr/sbin')
    expect(desc.content).toContain('/sbin')
    expect(desc.content).toContain('Restart=always')
  })

  it('includes /usr/sbin and /sbin in extraSearchPaths, augmentedPathEnv, macOS LaunchAgent descriptor, and deploy script', () => {
    const paths = extraSearchPaths()
    expect(paths).toContain('/usr/sbin')
    expect(paths).toContain('/sbin')

    const augEnv = augmentedPathEnv({ PATH: '/usr/bin:/bin' })
    const augParts = (augEnv.PATH ?? '').split(path.delimiter)
    expect(augParts).toContain('/usr/sbin')
    expect(augParts).toContain('/sbin')

    const emptyRepo = path.join(tmpHome, 'mac-repo')
    fs.mkdirSync(emptyRepo, { recursive: true })
    const darwinDesc = generateServiceDescriptor({
      role: 'slave',
      repoRoot: emptyRepo,
      nodeBin: '/opt/homebrew/bin/node',
      platformOverride: 'darwin',
    })
    expect(darwinDesc.platform).toBe('darwin')
    expect(darwinDesc.unitOrLabel).toBe('dev.lework.hub-slave')
    expect(darwinDesc.content).toContain('/usr/sbin')
    expect(darwinDesc.content).toContain('/sbin')

    const deployScript = fs.readFileSync(path.join(repoRoot, 'scripts', 'deploy-master-slave.sh'), 'utf8')
    expect(deployScript).toMatch(/<key>PATH<\/key><string>[^<]*\/usr\/sbin:\/sbin[^<]*<\/string>/)
  })

  it('filters Docker/Podman/virtual bridge interfaces while preserving real Devbox LAN IP', () => {
    const fakeNets: NodeJS.Dict<os.NetworkInterfaceInfo[]> = {
      lo: [
        {
          address: '127.0.0.1',
          netmask: '255.0.0.0',
          family: 'IPv4',
          mac: '00:00:00:00:00:00',
          internal: true,
          cidr: '127.0.0.1/8',
        },
      ],
      docker0: [
        {
          address: '172.17.0.1',
          netmask: '255.255.0.0',
          family: 'IPv4',
          mac: '02:42:ac:11:00:01',
          internal: false,
          cidr: '172.17.0.1/16',
        },
      ],
      'br-8f3a1c': [
        {
          address: '172.18.0.1',
          netmask: '255.255.0.0',
          family: 'IPv4',
          mac: '02:42:ac:12:00:01',
          internal: false,
          cidr: '172.18.0.1/16',
        },
      ],
      eth0: [
        {
          address: '192.168.10.20',
          netmask: '255.255.254.0',
          family: 'IPv4',
          mac: '52:54:00:12:34:56',
          internal: false,
          cidr: '192.168.10.20/23',
        },
      ],
    }

    expect(collectLanIpv4Addresses(fakeNets)).toEqual(['192.168.10.20'])
    expect(listLanAddresses(fakeNets)).toEqual(['192.168.10.20'])
  })

  it('extracts SSH server IP from $SSH_CONNECTION for remote Devbox pairing', () => {
    expect(
      resolveSshServerAddress({
        SSH_CONNECTION: '192.168.10.5 54321 192.168.10.20 22',
      }),
    ).toBe('192.168.10.20')
    expect(
      resolveSshServerAddress({
        SSH_CONNECTION: '::ffff:192.168.10.5 54321 ::ffff:192.168.10.20 22',
      }),
    ).toBe('192.168.10.20')
    expect(resolveSshServerAddress({ SSH_CONNECTION: '127.0.0.1 50000 127.0.0.1 22' })).toBeUndefined()
  })

  it('supports headless `lework config` subcommands without manual JSON editing', () => {
    const dataDir = path.join(tmpHome, 'lework-home')
    const wsDir = path.join(tmpHome, 'my-service')
    fs.mkdirSync(wsDir, { recursive: true })

    const runCli = (args: string[], extraEnv: NodeJS.ProcessEnv = {}) =>
      spawnSync(process.execPath, ['--experimental-transform-types', cliEntry, ...args], {
        cwd: repoRoot,
        encoding: 'utf8',
        env: { ...process.env, LEWORK_HOME: dataDir, ...extraEnv },
      })

    expect(runCli(['config', 'set-name', 'Remote Devbox']).status).toBe(0)
    expect(runCli(['config', 'add-workspace', wsDir]).status).toBe(0)
    expect(runCli(['config', 'add-relay', 'https://devbox.internal:64450']).status).toBe(0)

    const showRes = runCli(['config', 'show'])
    expect(showRes.status).toBe(0)
    expect(showRes.stdout).toContain('Remote Devbox')
    expect(showRes.stdout).toContain(fs.realpathSync(wsDir))
    expect(showRes.stdout).toContain('https://devbox.internal:64450')

    const pairRes = runCli(['pair'], { SSH_CONNECTION: '192.168.10.2 51111 192.168.10.20 22' })
    expect(pairRes.status).toBe(0)
    expect(pairRes.stdout).toContain('https://192.168.10.20:64450')
    expect(pairRes.stdout).toContain('lework://pair?')
  })

  it('resolves human-friendly default host names while preserving readable hostnames', () => {
    // Readable hostnames on any OS are preserved verbatim
    expect(resolveDefaultHostName({ hostname: 'devbox', platform: 'linux' })).toBe('devbox')
    expect(resolveDefaultHostName({ hostname: 'ubuntu-gpu', platform: 'linux' })).toBe('ubuntu-gpu')
    expect(resolveDefaultHostName({ hostname: 'Alice-MacBook', platform: 'darwin' })).toBe('Alice-MacBook')

    // Serial-number-like macOS hostname prefers readable ComputerName
    expect(
      resolveDefaultHostName({
        hostname: 'AB12CD34EF',
        platform: 'darwin',
        computerName: 'Studio MacBook',
        hwModel: 'MacBookPro18,3',
      }),
    ).toBe('Studio MacBook')

    // Serial-like .local hostname + serial-like ComputerName falls back to hw.model mapping
    expect(
      resolveDefaultHostName({
        hostname: 'AB12CD34EF.local',
        platform: 'darwin',
        computerName: 'AB12CD34EF',
        hwModel: 'MacBookPro18,3',
      }),
    ).toBe('MacBook Pro')
    expect(
      resolveDefaultHostName({
        hostname: 'AB12CD34EF',
        platform: 'darwin',
        computerName: '',
        hwModel: 'MacBookAir10,1',
      }),
    ).toBe('MacBook Air')
    expect(
      resolveDefaultHostName({
        hostname: 'AB12CD34EF',
        platform: 'darwin',
        computerName: '',
        hwModel: 'MacStudio1,1',
      }),
    ).toBe('Mac Studio')
    expect(
      resolveDefaultHostName({
        hostname: 'AB12CD34EF',
        platform: 'darwin',
        computerName: '',
        hwModel: 'Macmini9,1',
      }),
    ).toBe('Mac mini')
    expect(
      resolveDefaultHostName({
        hostname: 'AB12CD34EF',
        platform: 'darwin',
        computerName: '',
        hwModel: 'iMac21,1',
      }),
    ).toBe('iMac')
    expect(
      resolveDefaultHostName({
        hostname: 'AB12CD34EF',
        platform: 'darwin',
        computerName: '',
        hwModel: 'MacPro7,1',
      }),
    ).toBe('Mac Pro')

    // Missing or generic Apple Silicon hw.model falls back to "My Mac"
    expect(
      resolveDefaultHostName({
        hostname: 'AB12CD34EF',
        platform: 'darwin',
        computerName: '',
        hwModel: 'Mac15,6',
      }),
    ).toBe('My Mac')
    expect(
      resolveDefaultHostName({
        hostname: '',
        platform: 'darwin',
        computerName: '',
        hwModel: '',
      }),
    ).toBe('My Mac')
  })

  it('never overwrites an existing saved config.name when loading config', () => {
    const dataDir = path.join(tmpHome, 'existing-cfg')
    fs.mkdirSync(dataDir, { recursive: true })
    fs.writeFileSync(
      path.join(dataDir, 'config.json'),
      JSON.stringify({ hubId: 'hub_saved_1', name: 'AB12CD34EF' }, null, 2),
      'utf8',
    )
    const prevHome = process.env.LEWORK_HOME
    process.env.LEWORK_HOME = dataDir
    try {
      const cfg = loadOrCreateConfig()
      expect(cfg.name).toBe('AB12CD34EF')
    } finally {
      if (prevHome === undefined) delete process.env.LEWORK_HOME
      else process.env.LEWORK_HOME = prevHome
    }
  })
})
