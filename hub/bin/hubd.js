#!/usr/bin/env -S npx tsx
process.argv.splice(2, 0, 'serve')
await import('../src/cli.ts')
