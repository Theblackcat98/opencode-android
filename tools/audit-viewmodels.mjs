#!/usr/bin/env node
/**
 * Every production `ViewModel` has to be one Hilt can build.
 *
 * Nothing at build time says so. A screen asks for its model with `hiltViewModel()`, which compiles for any
 * `ViewModel` subclass and fails when the screen opens, with `NoSuchMethodException: <init> []`, because
 * the class has no `@HiltViewModel` and so no factory. Seven screens shipped like that (Configuration,
 * the config editor, commands and skills, permissions, maintenance, instructions and usage) and the app
 * died on tapping the first of them. The unit tests construct these classes directly, so they passed.
 *
 * This reads the sources and fails on any `ViewModel` in `src/main` that is not annotated
 * `@HiltViewModel`. A ViewModel that is deliberately built another way can say so with a
 * `// audit-viewmodels: manual` comment on the line above its class.
 *
 * Usage: node tools/audit-viewmodels.mjs
 */
import { readFileSync, readdirSync, statSync } from 'node:fs'
import { join, dirname, relative } from 'node:path'
import { fileURLToPath } from 'node:url'

const ROOT = join(dirname(fileURLToPath(import.meta.url)), '..')

function walk(dir, out = []) {
  for (const name of readdirSync(dir)) {
    if (['build', '.git', '.gradle', '.probe', '.vendor-tmp', '.dev-server'].includes(name)) continue
    const p = join(dir, name)
    if (statSync(p).isDirectory()) walk(p, out)
    else if (name.endsWith('.kt')) out.push(p)
  }
  return out
}

/** The classes in a file that extend `ViewModel()`, with the annotation lines above each. */
export function viewModelsIn(source) {
  const lines = source.split('\n')
  const found = []
  for (let i = 0; i < lines.length; i++) {
    if (!/:\s*(?:[\w.]+,\s*)*ViewModel\(\)/.test(lines[i]) && !/^\)\s*:\s*ViewModel\(\)/.test(lines[i])) continue
    // The class header is the nearest preceding line that opens a class at the start of a line.
    let start = i
    while (start >= 0 && !/^(?:(?:public|internal|open|abstract|private)\s+)*class\s+\w+/.test(lines[start])) start--
    if (start < 0) continue
    const name = /class\s+(\w+)/.exec(lines[start])[1]
    // Annotations sit directly above the header, up to the first line that is not one.
    const annotations = []
    for (let a = start - 1; a >= 0 && /^\s*(?:@|\/\/)/.test(lines[a]); a--) annotations.push(lines[a].trim())
    found.push({ name, line: start + 1, annotations })
  }
  return found
}

export function audit(files) {
  const problems = []
  for (const [path, source] of files) {
    for (const vm of viewModelsIn(source)) {
      const hilt = vm.annotations.some((a) => a.startsWith('@HiltViewModel'))
      const manual = vm.annotations.some((a) => a.includes('audit-viewmodels: manual'))
      if (!hilt && !manual) problems.push(`${path}:${vm.line}  ${vm.name} is a ViewModel without @HiltViewModel`)
    }
  }
  return problems
}

if (import.meta.url === `file://${process.argv[1]}`) {
  const files = walk(ROOT)
    .filter((f) => f.includes('/src/main/'))
    .map((f) => [relative(ROOT, f), readFileSync(f, 'utf8')])
  const problems = audit(files)
  const count = files.reduce((n, [, s]) => n + viewModelsIn(s).length, 0)
  if (problems.length > 0) {
    console.error(problems.join('\n'))
    console.error(`\n${problems.length} of ${count} ViewModels cannot be built by hiltViewModel().`)
    process.exit(1)
  }
  console.log(`audit-viewmodels: all ${count} ViewModels are @HiltViewModel`)
}
