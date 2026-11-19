/**
 * Starts two TLS Hubs for the Kotlin client ⇄ Hub end-to-end pinning test
 * (app/shared/src/jvmTest/.../HubTlsPinningIntegrationTest.kt):
 *   - the genuine Hub (knows LEWORK_IT_TOKEN)
 *   - an impostor Hub with its own certificate and a different token
 * Prints a single JSON line with both ports, then stays alive until killed.
 */
import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'

const token = process.env.LEWORK_IT_TOKEN ?? 'it-genuine-token-0123456789'
process.env.QPRC_HOME = fs.mkdtempSync(path.join(os.tmpdir(), 'lework-it-'))

const { HubServer } = await import('../../src/server.ts')
const { DiagnosticEchoAdapter } = await import('../../src/engines/echo-adapter.ts')
const { loadOrCreateHubTls } = await import('../../src/security/tls.ts')

const base = {
  role: 'slave' as const,
  host: '127.0.0.1',
  port: 0,
  workspaceRoots: [os.tmpdir()],
  cpaEndpoint: 'http://127.0.0.1:8317',
}
const genuine = new HubServer({
  config: { ...base, hubId: 'hub_it', name: 'Genuine', token, relayUrls: ['http://relay.example:64450'] },
  adapters: [new DiagnosticEchoAdapter()],
})
const impostor = new HubServer({
  config: { ...base, hubId: 'hub_evil', name: 'Impostor', token: 'impostor-does-not-know-token' },
  adapters: [new DiagnosticEchoAdapter()],
  tls: loadOrCreateHubTls({ dir: fs.mkdtempSync(path.join(os.tmpdir(), 'lework-it-evil-')) }),
})
const g = await genuine.start()
const i = await impostor.start()
console.log(JSON.stringify({ genuinePort: g.port, impostorPort: i.port, fingerprint: genuine.tlsFingerprint }))
