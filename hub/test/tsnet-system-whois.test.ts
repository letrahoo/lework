import fs from 'node:fs'
import type net from 'node:net'
import os from 'node:os'
import path from 'node:path'
import { afterAll, describe, expect, it } from 'vitest'
import { TsnetManager } from '../src/auth/tsnet-manager.ts'

const tempDir = fs.mkdtempSync(path.join(os.tmpdir(), 'lework-tsnet-sys-whois-'))

afterAll(() => {
  fs.rmSync(tempDir, { recursive: true, force: true })
})

function mockSocket(remoteAddress: string, remotePort: number): net.Socket {
  return { remoteAddress, remotePort } as unknown as net.Socket
}

describe('TsnetManager macOS/userspace netstack proxyMapper & WhoIs verification', () => {
  it('verifies macOS Tailscale.app netstack loopback proxy sockets (127.0.0.1:<port>) while rejecting UDP-only mappings and normal local sockets', async () => {
    const mgr = new TsnetManager({
      dataDir: tempDir,
      binaryPath: null,
      autoStart: false,
      hubPort: 64450,
      systemTailscaleCli: '/mock/tailscale',
      execCli: async (_file, args) => {
        if (args[0] === 'status') {
          return {
            stdout: JSON.stringify({
              BackendState: 'Running',
              Self: { UserID: 1001, HostName: 'Dev Mac mini', TailscaleIPs: ['100.64.0.10'] },
              User: { '1001': { LoginName: 'owner@example.com', DisplayName: 'Owner' } },
            }),
          }
        }
        const target = args.at(-1)
        // Unscoped WhoIs searches both protocols; only port 54321 has a TCP mapping.
        const tcpMatch = target === '127.0.0.1:54321'
        const udpMatch = target === '127.0.0.1:54322' && !args.includes('--proto=tcp')
        if (args[0] === 'whois' && (tcpMatch || udpMatch)) {
          return {
            stdout: JSON.stringify({
              Node: {
                ComputedName: 'lework-android-phone',
                Addresses: ['100.64.0.21/32', 'fd7a:115c:a1e0::21/128'],
              },
              UserProfile: {
                ID: 1001,
                LoginName: 'owner@example.com',
                DisplayName: 'Owner',
              },
            }),
          }
        }
        throw new Error(`no match for ${target ?? ''}`)
      },
    })

    // 1. Proxied macOS Tailscale.app netstack socket on 127.0.0.1:54321 -> verified with peer's real Tailscale IP!
    const proxied = await mgr.verifyInboundSocket(mockSocket('::ffff:127.0.0.1', 54321))
    expect(proxied).toEqual({
      sameUser: true,
      loginName: 'owner@example.com',
      displayName: 'Owner',
      nodeName: 'lework-android-phone',
      tailscaleIp: '100.64.0.21',
    })

    const udpOnly = await mgr.verifyInboundSocket(mockSocket('127.0.0.1', 54322))
    expect(udpOnly).toBeNull()

    // Ordinary non-Tailscale local process on 127.0.0.1:59999 -> rejected (null)
    const plainLocal = await mgr.verifyInboundSocket(mockSocket('127.0.0.1', 59999))
    expect(plainLocal).toBeNull()
  })

  it('handles IPv6 Tailscale ULA without port parse errors and rejects tagged peers', async () => {
    const whoisTargets: string[] = []
    const mgr = new TsnetManager({
      dataDir: tempDir,
      binaryPath: null,
      autoStart: false,
      systemTailscaleCli: '/mock/tailscale',
      execCli: async (_file, args) => {
        if (args[0] === 'status') {
          return {
            stdout: JSON.stringify({
              BackendState: 'Running',
              Self: { UserID: 777 },
            }),
          }
        }
        if (args[0] === 'whois') {
          whoisTargets.push(args[2]!)
          if (args[2] === '100.99.1.2') {
            return {
              stdout: JSON.stringify({
                Node: { ComputedName: 'ci-runner', Tags: ['tag:ci'], Addresses: ['100.99.1.2/32'] },
                UserProfile: { ID: 777, LoginName: 'owner@example.com', DisplayName: 'Owner' },
              }),
            }
          }
          return {
            stdout: JSON.stringify({
              Node: {
                ComputedName: 'lework-ios-iphone',
                Addresses: ['100.64.0.22/32', 'fd7a:115c:a1e0::22/128'],
              },
              UserProfile: { ID: 777, LoginName: 'owner@example.com', DisplayName: 'Owner' },
            }),
          }
        }
        throw new Error('unexpected command')
      },
    })

    const v6Peer = await mgr.verifyInboundSocket(mockSocket('fd7a:115c:a1e0::22', 61000))
    expect(v6Peer?.sameUser).toBe(true)
    expect(v6Peer?.nodeName).toBe('lework-ios-iphone')
    expect(whoisTargets).toEqual(['fd7a:115c:a1e0::22'])

    const v4Peer = await mgr.verifyInboundSocket(mockSocket('100.64.0.22', 61001))
    expect(v4Peer?.sameUser).toBe(true)

    const tagged = await mgr.verifyInboundSocket(mockSocket('100.99.1.2', 61002))
    expect(tagged).toBeNull()
  })

  it('uses the current local account on every handshake and fails closed without a live identity', async () => {
    const peerA = mockSocket('100.64.0.21', 61000)
    const peerB = mockSocket('100.64.0.22', 61000)
    let status: { BackendState: string; Self?: { UserID: number } } | null = {
      BackendState: 'Running', Self: { UserID: 1001 },
    }
    const mgr = new TsnetManager({
      dataDir: tempDir,
      binaryPath: null,
      autoStart: false,
      systemTailscaleCli: '/mock/tailscale',
      execCli: async (_file, args) => {
        if (args[0] === 'status' && status) return { stdout: JSON.stringify(status) }
        if (args[0] === 'whois') {
          const uid = args.at(-1) === peerA.remoteAddress ? 1001 : 1002
          return { stdout: JSON.stringify({ UserProfile: { ID: uid, LoginName: `${uid}@example.com` } }) }
        }
        throw new Error('status unavailable')
      },
    })

    expect((await mgr.verifyInboundSocket(peerA))?.sameUser).toBe(true)
    status = { BackendState: 'Running', Self: { UserID: 1002 } }
    expect(await mgr.verifyInboundSocket(peerA)).toBeNull()
    expect((await mgr.verifyInboundSocket(peerB))?.sameUser).toBe(true)

    status = { BackendState: 'Stopped', Self: { UserID: 1002 } }
    expect(await mgr.verifyInboundSocket(peerB)).toBeNull()
    status = { BackendState: 'Running' }
    expect(await mgr.verifyInboundSocket(peerB)).toBeNull()
    status = null
    expect(await mgr.verifyInboundSocket(peerB)).toBeNull()
  })
})
