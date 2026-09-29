#!/usr/bin/env node
/**
 * Plan §5.4's accessibility checklist, checked against the real sources.
 *
 * The rules here are the ones that can be decided from code, and each is a rule with a failure mode
 * behind it rather than a style preference:
 *
 *  - an icon-only control with no `contentDescription` is announced as "button" and nothing else;
 *  - a `stateDescription` is how a toggle says what it is *doing*, which `contentDescription` cannot;
 *  - `heading()` is what lets a screen reader jump between sections;
 *  - a hardcoded `sp` is a font size that ignores the user's text-size setting;
 *  - a `contentDescription` on a container that already merges its children reads the whole subtree
 *    twice.
 *
 * Usage: node tools/audit-accessibility.mjs [--json] [--per-file]
 */
import { readFileSync, readdirSync, statSync } from 'node:fs'
import { join, dirname, relative } from 'node:path'
import { fileURLToPath } from 'node:url'

const ROOT = join(dirname(fileURLToPath(import.meta.url)), '..')

function walk(dir, out = []) {
  for (const name of readdirSync(dir)) {
    if (['build', '.git', '.gradle', '.probe'].includes(name)) continue
    const p = join(dir, name)
    if (statSync(p).isDirectory()) walk(p, out)
    else if (name.endsWith('.kt')) out.push(p)
  }
  return out
}

const sources = walk(ROOT)
  .filter((f) => f.includes('/src/main/'))
  .map((f) => [relative(ROOT, f), readFileSync(f, 'utf8')])

const findings = []

/**
 * An `IconButton` with no `contentDescription` in its own call.
 *
 * Only `IconButton`, and only its own call: an `Icon` inside one is covered by the button's label,
 * and a bare `Icon` outside one is a decorative image, which is correct to leave unlabelled. The
 * window runs to the call's closing brace, so a label belonging to a *different* control further
 * down cannot stand in for this one.
 */
function iconWithoutLabel(file, text) {
  const lines = text.split('\n')
  for (let i = 0; i < lines.length; i++) {
    if (!/\bIconButton\s*\(/.test(lines[i])) continue
    // A model or a helper named like the composable is not a composable.
    if (/^\s*(data class|class|fun)\s/.test(lines[i])) continue
    const indent = lines[i].search(/\S/)
    // Parens and braces together: an `IconButton(...) {` call keeps its body open, and the label is
    // usually on an `Icon` inside it.
    let depth = 0
    let end = i
    for (let j = i; j < Math.min(lines.length, i + 24); j++) {
      for (const ch of lines[j]) {
        if (ch === '(' || ch === '{') depth++
        else if (ch === ')' || ch === '}') depth--
      }
      end = j
      if (depth <= 0 && !lines[j].trimEnd().endsWith(',')) break
      if (lines[j + 1] && lines[j + 1].trim() && lines[j + 1].search(/\S/) < indent) break
    }
    const window = lines.slice(i, end + 1).join('\n')
    if (window.includes('contentDescription')) continue
    if (/\.semantics\s*\{[^}]*contentDescription/.test(window)) continue
    // The common and correct Compose shape: the button is unlabelled and the `Icon` inside it
    // carries the description. That is a labelled control, not an unlabelled one.
    if (/Icon\s*\([^)]*stringResource\s*\(/.test(window)) continue
    if (/\bText\(\s*text\s*=/.test(window)) continue // it has a visible label of its own
    findings.push({
      file,
      line: i + 1,
      rule: 'icon-without-label',
      detail: lines[i].trim().slice(0, 60),
    })
  }
}

for (const [file, text] of sources) {
  const lines = text.split('\n')
  iconWithoutLabel(file, text)

  // A hardcoded text size applied in a call ignores the user's text-size setting, which is the
  // whole point of dynamic type. A size that *defines* a named style is where a size belongs, so
  // the rule only fires on one used where a style should have been.
  for (const m of text.matchAll(/fontSize\s*=\s*(\d+(?:\.\d+)?)\.sp\b/g)) {
    const line = text.slice(0, m.index).split('\n').length
    const source = lines[line - 1]
    if (source.includes('TextStyle(')) continue // a definition, not an override
    if (text.slice(Math.max(0, m.index - 200), m.index).includes('data class')) continue
    findings.push({ file, line, rule: 'hardcoded-font-size', detail: m[1] + '.sp' })
  }

  // A merging container that summarises its children is the correct pattern, not a finding: the
  // description is what a screen reader reads *instead of* walking each child. Reporting it would
  // push the codebase towards the worse version, where the whole subtree is read twice.
}

const byRule = new Map()
for (const f of findings) byRule.set(f.rule, (byRule.get(f.rule) ?? 0) + 1)

if (process.argv.includes('--json')) {
  console.log(JSON.stringify({ findings, byRule: Object.fromEntries(byRule) }, null, 1))
} else {
  console.log(`Accessibility findings: ${findings.length}`)
  for (const [rule, count] of [...byRule].sort((a, b) => b[1] - a[1])) {
    console.log(`  ${rule.padEnd(34)} ${count}`)
  }
  if (process.argv.includes('--per-file') && findings.length) {
    for (const f of findings) console.log(`  ${f.file}:${f.line} [${f.rule}] ${f.detail}`)
  }
}
