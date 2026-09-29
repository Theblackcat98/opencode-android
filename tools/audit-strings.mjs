#!/usr/bin/env node
/**
 * The plan's §5.4 rule, checked: "every string externalized from P0 on".
 *
 * A Compose call that puts text in the tree instead of in `strings.xml` cannot be translated, and
 * neither can a hardcoded notification title or an error message built in a ViewModel. This reports
 * what is left, per module, so the answer is a number rather than an intention.
 *
 * Usage: node tools/audit-strings.mjs [--json] [--per-module]
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

const mainSources = walk(ROOT)
  .filter((f) => f.includes('/src/main/'))
  .map((f) => [relative(ROOT, f), readFileSync(f, 'utf8')])

/** A call that puts a literal into a UI string slot. */
const STRING_HOLES = [
  // Compose: Text("…"), contentDescription = "…", label = "…", title = "…", hint = "…"
  { rule: 'Text', re: /\bText\(\s*"([^"]{2,})"/g },
  { rule: 'contentDescription', re: /\bcontentDescription\s*=\s*"([^"]{2,})"/g },
  { rule: 'label', re: /\blabel\s*=\s*"([^"]{2,})"/g },
  { rule: 'semanticsLabel', re: /\bsemantics\s*\{\s*contentDescription\s*=\s*"([^"]{2,})"/g },
  { rule: 'headline', re: /\bheadline\s*=\s*"([^"]{2,})"/g },
  { rule: 'placeholder', re: /\bplaceholder\s*=\s*"([^"]{2,})"/g },
  // Compose multi-parameter overloads: Text(text = "…")
  { rule: 'Text(text=)', re: /\bText\(\s*text\s*=\s*"([^"]{2,})"/g },
  { rule: 'stringResource-less', re: /\bstringResource\(\s*R\.string\.[A-Za-z0-9_]+/g },
]

// Values that are not user-visible text: a class name in a message, a test tag, a route.
const NOT_TEXT = new Set(['application', 'utf-8', 'text/plain', 'text/event-stream', 'en'])

/**
 * A literal with no word in it is punctuation joining two already-translated values, which is not a
 * string a translator can change: `"$label. $description"` reads the same in every locale, and
 * making it a resource would mean a translator re-ordering a sentence the code built. A literal
 * that *does* contain a word is a real finding, and those are what this audit counts.
 */
function carriesWords(literal) {
  // A literal whose interpolation is cut short by the pattern is a join, not a sentence: the
  // capture stops at the nested quote, so what is left is `${x}. ${y.joinToString("` with more
  // `${` than `}`.
  const opens = (literal.match(/\$\{/g) ?? []).length
  const closes = (literal.match(/\}/g) ?? []).length
  if (opens > closes) return false
  const wordsOnly = literal.replace(/\$\{[^}]*\}/g, '').replace(/\$\w+/g, '')
  return /[A-Za-z]{2,}/.test(wordsOnly)
}

const findings = []
const punctuation = []
for (const [file, text] of mainSources) {
  for (const { rule, re } of STRING_HOLES) {
    re.lastIndex = 0
    for (const m of text.matchAll(re)) {
      const literal = m[1]
      if (!literal || NOT_TEXT.has(literal)) continue
      // A literal that is only a format placeholder is fine.
      if (/^%[sd]$/.test(literal)) continue
      const line = text.slice(0, m.index).split('\n').length
      const entry = { file, line, rule, literal }
      if (carriesWords(literal)) findings.push(entry)
      else punctuation.push(entry)
    }
  }
}

// Resources, so the ratio is against something.
function stringResourceCount(module) {
  const dir = join(ROOT, module, 'src/main/res/values')
  if (!statSync(dir, { throwIfNoEntry: false })) return 0
  const file = join(dir, 'strings.xml')
  if (!statSync(file, { throwIfNoEntry: false })) return 0
  return [...readFileSync(file, 'utf8').matchAll(/<string\s/g)].length
}

const modules = [
  'app',
  'core/designsystem',
  'feature/servers',
  'feature/sessions',
  'feature/composer',
  'feature/requests',
  'feature/review',
  'feature/execution',
  'feature/integrations',
  'feature/admin',
  'feature/insights',
]

const byModule = new Map()
for (const f of findings) {
  const m = modules.find((name) => f.file.startsWith(name + '/')) ?? 'other'
  byModule.set(m, (byModule.get(m) ?? 0) + 1)
}

const resources = Object.fromEntries(modules.map((m) => [m, stringResourceCount(m)]))

if (process.argv.includes('--json')) {
  console.log(
    JSON.stringify(
      { findings, punctuation, resources, byModule: Object.fromEntries(byModule) },
      null,
      1,
    ),
  )
} else {
  console.log(`Untranslated UI strings: ${findings.length}`)
  console.log(`Punctuation joins (not translatable): ${punctuation.length}`)
  console.log(`\nResource strings per module:`)
  for (const m of modules) {
    const bad = byModule.get(m) ?? 0
    const res = resources[m]
    const flag = bad > 0 ? '  <-- hardcoded' : ''
    console.log(`  ${m.padEnd(24)} resources=${String(res).padStart(4)}  hardcoded=${String(bad).padStart(4)}${flag}`)
  }
  if (process.argv.includes('--per-module')) {
    if (findings.length) {
      console.log(`\nUntranslated:`)
      for (const f of findings) console.log(`  ${f.file}:${f.line} [${f.rule}] ${JSON.stringify(f.literal.slice(0, 60))}`)
    }
    if (punctuation.length) {
      console.log(`\nPunctuation joins:`)
      for (const f of punctuation) console.log(`  ${f.file}:${f.line} ${JSON.stringify(f.literal.slice(0, 50))}`)
    }
  }
}
