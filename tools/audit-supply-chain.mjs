#!/usr/bin/env node
import { readFileSync, readdirSync } from 'node:fs'
import path from 'node:path'
import process from 'node:process'
import { auditCliLock } from '../scripts/verify-opencode-cli.mjs'

export function auditActionPins(workflows, pins) {
  const findings = []
  const used = new Set()

  for (const [file, source] of Object.entries(workflows)) {
    for (const [index, line] of source.split(/\r?\n/).entries()) {
      const match = line.match(/^\s*(?:-\s*)?uses:\s*(.*?)\s*$/)
      if (!match) continue
      const value = match[1].split(/\s+#/, 1)[0].trim()
      const note = line.includes('#') ? line.slice(line.indexOf('#') + 1).trim() : ''
      if (value.startsWith('./')) continue

      const at = value.lastIndexOf('@')
      if (at < 1) {
        findings.push(`${file}:${index + 1} action reference has no immutable ref`)
        continue
      }
      const repository = value.slice(0, at)
      const ref = value.slice(at + 1)
      const reviewed = pins[repository]
      if (!/^[a-f0-9]{40}$/.test(ref)) {
        findings.push(`${file}:${index + 1} ${repository} is not pinned to a full lowercase commit SHA`)
      }
      if (!reviewed) {
        findings.push(`${file}:${index + 1} ${repository} is missing from config/ci-actions.lock.json`)
        continue
      }
      used.add(repository)
      if (ref !== reviewed.sha) findings.push(`${file}:${index + 1} ${repository} SHA differs from the reviewed lock`)
      if (note !== reviewed.version) findings.push(`${file}:${index + 1} ${repository} release note must be ${reviewed.version}`)
    }
  }

  for (const [repository, reviewed] of Object.entries(pins)) {
    if (!/^v\d+\.\d+\.\d+$/.test(reviewed.version)) {
      findings.push(`${repository} has an invalid reviewed release label`)
    }
    if (!/^[a-f0-9]{40}$/.test(reviewed.sha)) {
      findings.push(`${repository} does not have a full lowercase commit SHA`)
    }
    if (!used.has(repository)) findings.push(`${repository} is locked but unused by CI workflows`)
  }
  return findings
}

export function auditInstallerScripts({ workflows, devServer, installer }) {
  const findings = []
  if (!installer.includes('npm ci --ignore-scripts')) {
    findings.push('the shared CLI installer must use npm ci --ignore-scripts')
  }
  if (!installer.includes('verify-opencode-cli.mjs')) {
    findings.push('the shared CLI installer must verify the installed binary')
  }
  if (!devServer.includes('scripts/install-opencode-cli.sh')) {
    findings.push('dev-server.sh must use the shared locked CLI installer')
  }
  if (!workflows.includes('scripts/install-opencode-cli.sh')) {
    findings.push('CI must use the shared locked CLI installer')
  }
  if (/\bnpm\s+install\b/.test(`${workflows}\n${devServer}\n${installer}`)) {
    findings.push('npm install is forbidden in the managed CLI installation path')
  }
  if (!workflows.includes('node tools/audit-supply-chain.test.mjs') || !workflows.includes('node tools/audit-supply-chain.mjs --check')) {
    findings.push('CI must run the supply-chain regression tests and audit')
  }
  return findings
}

function loadWorkspace(root) {
  const workflowDir = path.join(root, '.github', 'workflows')
  const workflows = Object.fromEntries(
    readdirSync(workflowDir)
      .filter((name) => /\.ya?ml$/i.test(name))
      .map((name) => [path.join('.github', 'workflows', name), readFileSync(path.join(workflowDir, name), 'utf8')]),
  )
  const allWorkflowText = Object.values(workflows).join('\n')
  const packageDir = path.join(root, 'scripts', 'dev-server-cli')
  return {
    workflows,
    allWorkflowText,
    pins: JSON.parse(readFileSync(path.join(root, 'config', 'ci-actions.lock.json'), 'utf8')),
    packageJson: JSON.parse(readFileSync(path.join(packageDir, 'package.json'), 'utf8')),
    packageLock: JSON.parse(readFileSync(path.join(packageDir, 'package-lock.json'), 'utf8')),
    checksums: JSON.parse(readFileSync(path.join(packageDir, 'checksums.json'), 'utf8')),
    devServer: readFileSync(path.join(root, 'scripts', 'dev-server.sh'), 'utf8'),
    installer: readFileSync(path.join(root, 'scripts', 'install-opencode-cli.sh'), 'utf8'),
  }
}

export function auditWorkspace(root) {
  const workspace = loadWorkspace(root)
  const findings = [
    ...auditActionPins(workspace.workflows, workspace.pins),
    ...auditCliLock(workspace.packageJson, workspace.packageLock, workspace.checksums),
    ...auditInstallerScripts({
      workflows: workspace.allWorkflowText,
      devServer: workspace.devServer,
      installer: workspace.installer,
    }),
  ]
  return findings
}

function main() {
  const args = process.argv.slice(2).filter((argument) => argument !== '--check')
  const root = path.resolve(args[0] ?? process.cwd())
  const findings = auditWorkspace(root)
  if (findings.length > 0) {
    console.error(findings.map((finding) => `supply-chain audit error: ${finding}`).join('\n'))
    process.exitCode = 1
    return
  }
  console.log('supply-chain audit passed: all workflow actions and CLI package checksums match the reviewed locks.')
}

if (process.argv[1] && path.resolve(process.argv[1]) === path.resolve(new URL(import.meta.url).pathname)) {
  main()
}
