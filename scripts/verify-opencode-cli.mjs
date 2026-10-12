#!/usr/bin/env node
import { createHash } from 'node:crypto'
import { constants, accessSync, readFileSync } from 'node:fs'
import path from 'node:path'
import process from 'node:process'

const SRI_SHA512 = /^sha512-[A-Za-z0-9+/]{86}==$/

function readJson(file) {
  return JSON.parse(readFileSync(file, 'utf8'))
}

export function auditCliLock(packageJson, packageLock, checksumManifest) {
  const findings = []
  const cliVersion = packageJson.dependencies?.['@opencode/cli']
  if (!cliVersion) findings.push('package.json must pin @opencode/cli as a direct dependency')
  if (checksumManifest.version !== cliVersion) {
    findings.push(`checksum manifest version ${checksumManifest.version} does not match package.json ${cliVersion}`)
  }

  const root = packageLock.packages?.['']
  if (root?.dependencies?.['@opencode/cli'] !== cliVersion) {
    findings.push('package-lock root dependency does not match package.json')
  }

  const cliEntry = packageLock.packages?.['node_modules/@opencode/cli']
  if (!cliEntry) {
    findings.push('package-lock is missing node_modules/@opencode/cli')
    return findings
  }
  if (cliEntry.version !== cliVersion) {
    findings.push(`locked CLI version ${cliEntry.version} does not match ${cliVersion}`)
  }

  const expectedPackages = new Map([['@opencode/cli', cliVersion]])
  for (const [name, version] of Object.entries(cliEntry.optionalDependencies ?? {})) {
    expectedPackages.set(name, version)
  }
  const recordedPackages = checksumManifest.packages ?? {}

  for (const [name, expectedVersion] of expectedPackages) {
    const lockEntry = packageLock.packages?.[`node_modules/${name}`]
    const checksumEntry = recordedPackages[name]
    if (!lockEntry) {
      findings.push(`package-lock is missing ${name}`)
      continue
    }
    if (lockEntry.version !== expectedVersion) {
      findings.push(`${name} lock version ${lockEntry.version} does not match ${expectedVersion}`)
    }
    if (!SRI_SHA512.test(lockEntry.integrity ?? '')) {
      findings.push(`${name} is missing a valid SHA-512 npm integrity value`)
    }
    if (checksumEntry?.version !== expectedVersion) {
      findings.push(`${name} checksum version does not match ${expectedVersion}`)
    }
    if (checksumEntry?.integrity !== lockEntry.integrity) {
      findings.push(`${name} checksum manifest does not match package-lock integrity`)
    }
  }

  for (const name of Object.keys(recordedPackages)) {
    if (!expectedPackages.has(name)) findings.push(`checksum manifest contains untracked package ${name}`)
  }

  const binarySha256 = checksumManifest.binarySha256 ?? {}
  for (const [name, digest] of Object.entries(binarySha256)) {
    if (!expectedPackages.has(name)) findings.push(`binary checksum refers to unlocked package ${name}`)
    if (!/^[a-f0-9]{64}$/.test(digest)) findings.push(`${name} binary checksum is not a SHA-256 hex digest`)
  }
  if (!binarySha256['@opencode/cli-linux-x64']) {
    findings.push('checksum manifest must include the supported Linux x64 CLI binary digest')
  }

  return findings
}

export function resolveInstalledBinary(packageDir) {
  const packageRoot = path.join(packageDir, 'node_modules', '@opencode')
  const preferred = [
    'cli-linux-x64',
    'cli-linux-x64-baseline',
    'cli-linux-x64-musl',
    'cli-linux-x64-baseline-musl',
    'cli-linux-arm64',
    'cli-linux-arm64-musl',
  ]
  for (const name of preferred) {
    const candidate = path.join(packageRoot, name, 'bin', 'opencode')
    try {
      accessSync(candidate, constants.X_OK)
      return { packageName: `@opencode/${name}`, path: candidate }
    } catch {
      // Try the next platform package recorded in the lockfile.
    }
  }
  throw new Error('No executable OpenCode Linux CLI binary was installed from the locked platform packages')
}

export function verifyBinaryChecksum(binaryPath, packageName, checksumManifest) {
  try {
    accessSync(binaryPath, constants.X_OK)
  } catch {
    return [`${binaryPath} is missing or not executable`]
  }
  const packageIntegrity = checksumManifest.packages?.[packageName]?.integrity
  if (!packageIntegrity || !SRI_SHA512.test(packageIntegrity)) {
    return [`${packageName} has no verified npm package integrity value`]
  }
  const expected = checksumManifest.binarySha256?.[packageName]
  if (!expected) return [] // npm ci verifies this package's archive against the locked SRI value.

  const actual = createHash('sha256').update(readFileSync(binaryPath)).digest('hex')
  return actual === expected ? [] : [`${packageName} binary SHA-256 mismatch: ${actual}`]
}

function main() {
  const packageDir = path.resolve(process.argv[2] ?? 'scripts/dev-server-cli')
  const packageJson = readJson(path.join(packageDir, 'package.json'))
  const packageLock = readJson(path.join(packageDir, 'package-lock.json'))
  const checksumManifest = readJson(path.join(packageDir, 'checksums.json'))
  const findings = auditCliLock(packageJson, packageLock, checksumManifest)
  const binary = process.argv[3]
    ? { path: path.resolve(process.argv[3]), packageName: packageNameForBinary(packageDir, process.argv[3]) }
    : resolveInstalledBinary(packageDir)
  findings.push(...verifyBinaryChecksum(binary.path, binary.packageName, checksumManifest))

  if (findings.length > 0) {
    console.error(findings.map((finding) => `supply-chain error: ${finding}`).join('\n'))
    process.exitCode = 1
    return
  }
  if (process.env.PRINT_OPENCODE_BIN === '1') {
    console.log(binary.path)
  } else {
    console.log(`Verified @opencode/cli@${checksumManifest.version} lockfile integrity and ${binary.packageName} binary.`)
  }
}

function packageNameForBinary(packageDir, binaryPath) {
  const modulesDir = path.join(packageDir, 'node_modules')
  const relative = path.relative(modulesDir, path.resolve(binaryPath))
  const parts = relative.split(path.sep)
  if (parts[0] !== '@opencode' || !parts[1]) {
    throw new Error('The verified CLI binary must be inside node_modules/@opencode')
  }
  return `${parts[0]}/${parts[1]}`
}

if (process.argv[1] && path.resolve(process.argv[1]) === path.resolve(new URL(import.meta.url).pathname)) {
  main()
}
