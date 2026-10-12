import assert from 'node:assert/strict'
import { createHash } from 'node:crypto'
import { mkdtempSync, rmSync, writeFileSync } from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import test from 'node:test'
import { auditActionPins, auditInstallerScripts } from './audit-supply-chain.mjs'
import { auditCliLock, verifyBinaryChecksum } from '../scripts/verify-opencode-cli.mjs'

function sri(byte) {
  return `sha512-${Buffer.alloc(64, byte).toString('base64')}`
}

function cliFixture() {
  const packageIntegrity = sri(1)
  const linuxIntegrity = sri(2)
  const packageJson = { dependencies: { '@opencode/cli': '2.0.18' } }
  const packageLock = {
    packages: {
      '': { dependencies: { '@opencode/cli': '2.0.18' } },
      'node_modules/@opencode/cli': {
        version: '2.0.18',
        integrity: packageIntegrity,
        optionalDependencies: { '@opencode/cli-linux-x64': '2.0.18' },
      },
      'node_modules/@opencode/cli-linux-x64': { version: '2.0.18', integrity: linuxIntegrity },
    },
  }
  const binary = Buffer.from('pinned OpenCode test executable')
  const binarySha256 = createHash('sha256').update(binary).digest('hex')
  const checksumManifest = {
    version: '2.0.18',
    packages: {
      '@opencode/cli': { version: '2.0.18', integrity: packageIntegrity },
      '@opencode/cli-linux-x64': { version: '2.0.18', integrity: linuxIntegrity },
    },
    binarySha256: { '@opencode/cli-linux-x64': binarySha256 },
  }
  return { packageJson, packageLock, checksumManifest, binary }
}

test('accepts an immutable action ref that matches the reviewed release lock', () => {
  const sha = 'a'.repeat(40)
  const findings = auditActionPins(
    { 'ci.yml': `      uses: actions/checkout@${sha} # v7.0.1\n` },
    { 'actions/checkout': { version: 'v7.0.1', sha } },
  )
  const inlineFindings = auditActionPins(
    { 'ci.yml': `      - uses: actions/checkout@${sha} # v7.0.1\n` },
    { 'actions/checkout': { version: 'v7.0.1', sha } },
  )
  assert.deepEqual(findings, [])
  assert.deepEqual(inlineFindings, [])
})

test('rejects mutable action tags and SHAs that differ from the reviewed lock', () => {
  const pins = { 'actions/checkout': { version: 'v7.0.1', sha: 'a'.repeat(40) } }
  const tagFindings = auditActionPins(
    { 'ci.yml': '      uses: actions/checkout@v7 # v7.0.1\n' },
    pins,
  )
  assert.ok(tagFindings.some((finding) => finding.includes('full lowercase commit SHA')))

  const changedFindings = auditActionPins(
    { 'ci.yml': `      uses: actions/checkout@${'b'.repeat(40)} # v7.0.1\n` },
    pins,
  )
  assert.ok(changedFindings.some((finding) => finding.includes('differs from the reviewed lock')))
})

test('requires every CLI package lock integrity to match the committed checksum manifest', () => {
  const { packageJson, packageLock, checksumManifest } = cliFixture()
  assert.deepEqual(auditCliLock(packageJson, packageLock, checksumManifest), [])

  packageLock.packages['node_modules/@opencode/cli-linux-x64'].integrity = undefined
  const findings = auditCliLock(packageJson, packageLock, checksumManifest)
  assert.ok(findings.some((finding) => finding.includes('missing a valid SHA-512')))
})

test('rejects a changed supported-platform CLI binary even when its path is executable', () => {
  const { checksumManifest, binary } = cliFixture()
  const directory = mkdtempSync(path.join(os.tmpdir(), 'opencode-cli-integrity-test-'))
  const binaryPath = path.join(directory, 'opencode')
  try {
    writeFileSync(binaryPath, binary, { mode: 0o755 })
    assert.deepEqual(verifyBinaryChecksum(binaryPath, '@opencode/cli-linux-x64', checksumManifest), [])
    writeFileSync(binaryPath, 'tampered executable', { mode: 0o755 })
    assert.ok(verifyBinaryChecksum(binaryPath, '@opencode/cli-linux-x64', checksumManifest).some((finding) => finding.includes('SHA-256 mismatch')))
  } finally {
    rmSync(directory, { recursive: true, force: true })
  }
})

test('requires the default CI and dev-server paths to use npm ci without install scripts', () => {
  assert.deepEqual(
    auditInstallerScripts({
      workflows: 'node tools/audit-supply-chain.test.mjs\nnode tools/audit-supply-chain.mjs --check\n./scripts/install-opencode-cli.sh',
      devServer: 'bin="$("$ROOT/scripts/install-opencode-cli.sh")"',
      installer: 'npm ci --ignore-scripts\nnode "$ROOT/scripts/verify-opencode-cli.mjs"',
    }),
    [],
  )
  const insecureFindings = auditInstallerScripts({
    workflows: 'node tools/audit-supply-chain.test.mjs\nnode tools/audit-supply-chain.mjs --check\n./scripts/install-opencode-cli.sh\nnpm install --prefix scripts/dev-server-cli',
    devServer: 'bin="$("$ROOT/scripts/install-opencode-cli.sh")"',
    installer: 'npm ci --ignore-scripts\nnode "$ROOT/scripts/verify-opencode-cli.mjs"',
  })
  assert.ok(insecureFindings.some((finding) => finding.includes('npm install is forbidden')))
})
