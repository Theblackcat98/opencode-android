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
 * **It also checks which instance a screen gets, because `hiltViewModel()` answers for any owner.** The
 * default of `x: T = hiltViewModel()` is evaluated where the function is *called*, in whatever
 * `ViewModelStoreOwner` is current there, and nothing says the instance was ever told which session it is
 * for. Two shapes produced a `ComposerViewModel` that nobody had opened, and every method that needs a
 * session returns without a word when it has none: "Undo to here" confirmed and did nothing, and files
 * attached from the file browser went to a composer no screen showed.
 *
 *  1. **The graph function takes no ViewModel with a `hiltViewModel()` default.** A function that builds a
 *     `NavHost` evaluates that default outside every route, in the activity's owner; a `composable<…>`
 *     lambda that then uses it is talking to a third instance. Take it inside the lambda, with the entry.
 *  2. **A session-bound ViewModel is defaulted in one host only.** `ComposerViewModel` belongs to
 *     `SessionHost`, which opens the session in it; another host that defaults its own is a second composer
 *     with no session. Hand it the session's instance instead (`hiltViewModel(sessionEntry)`), or, if it
 *     truly never needs a session, say so with a `// audit-viewmodels: own-instance` comment above the
 *     parameter, which is a claim a reviewer can check.
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

/** The ViewModels whose state is a session's, and the one file allowed to default its own. */
export const SESSION_BOUND = new Map([['ComposerViewModel', 'SessionHost.kt']])

const OWN_INSTANCE = 'audit-viewmodels: own-instance'

/**
 * The source with every comment and the inside of every string turned into spaces.
 *
 * Positions and line breaks are kept, so an offset found in the result is the same offset in the source; what
 * is gone is anything that only *talks about* code, such as a KDoc that mentions `hiltViewModel()`.
 */
export function blanked(source) {
  let out = ''
  let i = 0
  const n = source.length
  const keep = (c) => (c === '\n' ? '\n' : ' ')
  while (i < n) {
    const c = source[i]
    const two = source.slice(i, i + 2)
    if (two === '//') {
      while (i < n && source[i] !== '\n') out += (i++, ' ')
    } else if (two === '/*') {
      let depth = 0
      do {
        const pair = source.slice(i, i + 2)
        if (pair === '/*') {
          depth++
          out += '  '
          i += 2
        } else if (pair === '*/') {
          depth--
          out += '  '
          i += 2
        } else {
          out += keep(source[i++])
        }
      } while (i < n && depth > 0)
    } else if (source.startsWith('"""', i)) {
      out += '"""'
      i += 3
      while (i < n && !source.startsWith('"""', i)) out += keep(source[i++])
      out += '"""'
      i += 3
    } else if (c === '"') {
      out += c
      i++
      while (i < n && source[i] !== '"') {
        if (source[i] === '\\') out += (i++, ' ')
        out += keep(source[i++])
      }
      out += '"'
      i++
    } else if (c === "'" && (source[i + 2] === "'" || (source[i + 1] === '\\' && source[i + 3] === "'"))) {
      const width = source[i + 1] === '\\' ? 4 : 3
      out += "'" + ' '.repeat(width - 2) + "'"
      i += width
    } else {
      out += c
      i++
    }
  }
  return out.slice(0, n)
}

/** The index of the bracket that closes the one at `open`, or -1. Arrows (`->`) are not brackets. */
function closing(text, open) {
  const pairs = { '(': ')', '{': '}', '[': ']' }
  const stack = []
  for (let i = open; i < text.length; i++) {
    const c = text[i]
    if (pairs[c]) stack.push(pairs[c])
    else if (c === stack[stack.length - 1]) {
      stack.pop()
      if (stack.length === 0) return i
    }
  }
  return -1
}

/** Splits a parameter list on the commas that are not inside a bracket or a generic. */
function splitParameters(list) {
  const parts = []
  let depth = 0
  let start = 0
  for (let i = 0; i < list.length; i++) {
    const c = list[i]
    if ('({[<'.includes(c)) depth++
    else if (')}]'.includes(c) || (c === '>' && list[i - 1] !== '-')) depth--
    else if (c === ',' && depth === 0) {
      parts.push([start, list.slice(start, i)])
      start = i + 1
    }
  }
  if (list.slice(start).trim() !== '') parts.push([start, list.slice(start)])
  return parts
}

/** The functions of a file: name, where it is, its parameters with their defaults, and its block body. */
export function functionsIn(source) {
  const text = blanked(source)
  const found = []
  const header = /(?:^|\n)[ \t]*(?:(?:public|private|internal|inline|tailrec|operator|suspend)\s+)*fun\s+(?:<[^>]*>\s*)?(?:[\w.<>?]+\.)?(\w+)\s*\(/g
  for (const match of text.matchAll(header)) {
    const open = match.index + match[0].length - 1
    const close = closing(text, open)
    if (close < 0) continue
    const after = /[{=]/.exec(text.slice(close + 1))
    const bodyOpen = after && after[0] === '{' ? close + 1 + after.index : -1
    const bodyClose = bodyOpen >= 0 ? closing(text, bodyOpen) : -1
    const parameters = splitParameters(text.slice(open + 1, close)).map(([offset, raw]) => {
      const declared = /^\s*(?:vararg\s+)?(\w+)\s*:\s*([^=]+?)\s*(?:=\s*([\s\S]*?))?\s*$/.exec(raw)
      if (!declared) return null
      const at = open + 1 + offset + raw.search(/\S/)
      return {
        name: declared[1],
        type: declared[2].replace(/\?$/, '').replace(/^.*\./, ''),
        default: declared[3] ?? null,
        line: text.slice(0, at).split('\n').length,
      }
    }).filter(Boolean)
    found.push({
      name: match[1],
      line: text.slice(0, open).split('\n').length,
      parameters,
      body: bodyOpen >= 0 && bodyClose > bodyOpen ? text.slice(bodyOpen, bodyClose + 1) : '',
    })
  }
  return found
}

/** Whether the comment lines directly above (or on) `line` carry the own-instance claim. */
function claimsOwnInstance(source, line) {
  const lines = source.split('\n')
  if (lines[line - 1].includes(OWN_INSTANCE)) return true
  for (let i = line - 2; i >= 0 && /^\s*(?:\/\/|\/\*|\*)/.test(lines[i]); i--) {
    if (lines[i].includes(OWN_INSTANCE)) return true
  }
  return false
}

/** Which instance a screen gets: the two shapes in the file comment that produce a composer nobody opened. */
export function auditScopes(files) {
  const problems = []
  for (const [path, source] of files) {
    if (!source.includes('hiltViewModel')) continue
    const file = path.split('/').pop()
    for (const fn of functionsIn(source)) {
      const graph = /\bNavHost\s*\(/.test(fn.body)
      for (const parameter of fn.parameters) {
        if (!/^hiltViewModel\s*(?:<[^>]*>)?\s*\(\s*\)$/.test(parameter.default ?? '')) continue
        const where = `${path}:${parameter.line}  ${fn.name}(${parameter.name}: ${parameter.type} = hiltViewModel())`
        if (graph) {
          problems.push(
            `${where} is on the function that builds the NavHost, so the default is evaluated outside every route, ` +
              `in the activity's ViewModelStoreOwner. Take it inside the composable<...> lambda with hiltViewModel(entry).`,
          )
        }
        const home = SESSION_BOUND.get(parameter.type)
        if (home && file !== home && !claimsOwnInstance(source, parameter.line)) {
          problems.push(
            `${where} is a second ${parameter.type} beside the one ${home} opens the session in, and nothing opens ` +
              `this one. Pass the session's instance in (hiltViewModel(sessionEntry)), or, if it never needs a session, ` +
              `say so with a "// ${OWN_INSTANCE}" comment above the parameter.`,
          )
        }
      }
    }
  }
  return problems
}

if (import.meta.url === `file://${process.argv[1]}`) {
  const files = walk(ROOT)
    .filter((f) => f.includes('/src/main/'))
    .map((f) => [relative(ROOT, f), readFileSync(f, 'utf8')])
  const problems = audit(files)
  const scopes = auditScopes(files)
  const count = files.reduce((n, [, s]) => n + viewModelsIn(s).length, 0)
  if (problems.length > 0 || scopes.length > 0) {
    console.error([...problems, ...scopes].join('\n'))
    if (problems.length > 0) console.error(`\n${problems.length} of ${count} ViewModels cannot be built by hiltViewModel().`)
    if (scopes.length > 0) console.error(`\n${scopes.length} screens would get a ViewModel that no session was opened in.`)
    process.exit(1)
  }
  console.log(`audit-viewmodels: all ${count} ViewModels are @HiltViewModel, and no screen defaults a second session's`)
}
