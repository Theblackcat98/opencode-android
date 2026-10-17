#!/usr/bin/env node
/**
 * The WCAG 2.x contrast of the colour pairs the app paints text and components on.
 *
 * Plan §5.4 asks for text at 4.5:1, and most text gets its pair from a `ColorScheme` role. The code
 * palette does not: a token colour is drawn on a diff row, on a code block and on a selection, and
 * each of those is a different background. So this reads the colours out of the Kotlin sources
 * rather than keeping a second copy of them: the `OpenCodePalette` literals, the two fallback
 * schemes, the `DiffColors.of` mapping, and the code-block and link lines in MarkdownText.kt.
 *
 * A pair whose colour it cannot read is listed as unmeasured with the reason. It is not dropped, so
 * a renamed role shows up as a gap in the report rather than as a shorter table.
 *
 * What it does not compute is in NOT_COMPUTED: dynamic (Material You) colour, which has no static
 * value, colours the server or the user chooses, the terminal's xterm.js palette, and the camera
 * message, which sits over a camera view whose content is not static.
 *
 * It reports and does not gate by default: it exits 0, like audit-strings and audit-accessibility.
 * With `--strict` it exits 1 on any pair below the floors or any pair it could not read, so CI can
 * hold the claim.
 *
 * Usage: node tools/audit-contrast.mjs [--json] [--strict]
 */
import { readFileSync } from 'node:fs'
import { join, dirname } from 'node:path'
import { fileURLToPath } from 'node:url'

const ROOT = join(dirname(fileURLToPath(import.meta.url)), '..')
const DESIGN = 'core/designsystem/src/main/kotlin/dev/opencode/android/core/designsystem'
const FILES = {
  colour: `${DESIGN}/theme/Color.kt`,
  diff: `${DESIGN}/diff/DiffTable.kt`,
  markdown: `${DESIGN}/markdown/MarkdownText.kt`,
}

/** WCAG 2.x: normal text needs 4.5:1; large text and UI components need 3:1. */
export const TEXT_MIN = 4.5
export const UI_OR_LARGE_MIN = 3

/** Token kinds in CodeHighlighter that take a colour from the diff palette. */
const TOKENS = ['keyword', 'string', 'number', 'comment', 'annotation', 'punctuation']

/** The rows DiffRowView paints, with the background each one uses and the colour its text takes. */
const ROWS = [
  { row: 'context row', bg: 'context', text: 'plain' },
  { row: 'selected row', bg: 'selection', text: 'plain' },
  { row: 'added row', bg: 'added', text: 'addedStrong' },
  { row: 'removed row', bg: 'removed', text: 'removedStrong' },
  { row: 'hunk header', bg: 'hunkHeader', text: 'gutter' },
]

/** Scheme pairs by foreground and background role. The first nine are the core pairs; the rest are used by the screens. */
const SCHEME_PAIRS = [
  ['onSurface', 'surface'],
  ['onSurfaceVariant', 'surface'],
  ['onBackground', 'background'],
  ['onPrimary', 'primary'],
  ['onSecondary', 'secondary'],
  ['onError', 'error'],
  ['onPrimaryContainer', 'primaryContainer'],
  ['onSecondaryContainer', 'secondaryContainer'],
  ['onErrorContainer', 'errorContainer'],
  // Containers the screens fill with a Surface, whose content colour is the matching `on` role.
  ['onTertiaryContainer', 'tertiaryContainer'],
  ['onSurfaceVariant', 'surfaceVariant'],
  // A Snackbar takes its colours from the inverse pair by default (EventInspectorScreen).
  ['inverseOnSurface', 'inverseSurface'],
  // Accent roles set as text on the surface: completion kinds, permission effects, server health.
  ['primary', 'surface'],
  ['secondary', 'surface'],
  ['tertiary', 'surface'],
  ['error', 'surface'],
]

/**
 * Pairs that carry no text, so the 4.5:1 text verdict does not apply to them and only the 3:1
 * component floor is measured. The disconnected server dot is `outline`.
 */
const COMPONENT_ONLY = ['outline / surface']

/** What this tool does not compute, and why. These are reported, not measured. */
export const NOT_COMPUTED = [
  {
    area: 'dynamic colour (Material You, API 31+)',
    reason:
      'OpenCodeTheme defaults to dynamicColor = true, so on Android 12+ the scheme comes from the ' +
      'wallpaper and has no static value. The figures are the fallback schemes, which a device sees ' +
      'on Android 11 and lower, or where dynamicColor is false (no caller in the app sets it).',
  },
  {
    area: 'terminal (xterm.js in a WebView)',
    reason:
      'index.html sets only the page background (#101216). The terminal foreground and the ANSI ' +
      'colours are xterm.js defaults, which are not in the app source.',
  },
  {
    area: 'colours chosen by the server or the user',
    reason:
      'agent colours (Pickers.kt agentColor), project colours (ProjectSettingsScreen, an ARGB int) and ' +
      'worktree colours come from data, not from the source.',
  },
  {
    area: 'camera-unavailable message',
    reason:
      'QrCodeScannerView draws Color.White text over a Color.Black box, and a camera AndroidView sits in ' +
      'the same box. What that view shows after a failed bind is not in the source, so the pair is not computed.',
  },
]

// Colour arithmetic

/** `0xAARRGGBB`, or `0xRRGGBB` which is opaque, as channels in 0..1. */
export function parseArgb(literal) {
  const hex = literal.replace(/^0x/i, '')
  if (!/^(?:[0-9a-f]{6}|[0-9a-f]{8})$/i.test(hex)) throw new Error(`not a colour literal: ${literal}`)
  const argb = parseInt(hex.length === 6 ? `ff${hex}` : hex, 16)
  return {
    a: ((argb >>> 24) & 0xff) / 255,
    r: ((argb >>> 16) & 0xff) / 255,
    g: ((argb >>> 8) & 0xff) / 255,
    b: (argb & 0xff) / 255,
  }
}

/** Source-over of a colour with alpha onto an opaque background, in sRGB, as the renderer draws it. */
export function composite(fg, bg) {
  if (bg.a !== 1) throw new Error('the background must be opaque')
  return {
    r: fg.r * fg.a + bg.r * (1 - fg.a),
    g: fg.g * fg.a + bg.g * (1 - fg.a),
    b: fg.b * fg.a + bg.b * (1 - fg.a),
    a: 1,
  }
}

/** The WCAG 2.x linearisation of one sRGB channel in 0..1. */
function linear(channel) {
  return channel <= 0.04045 ? channel / 12.92 : ((channel + 0.055) / 1.055) ** 2.4
}

/** WCAG 2.x relative luminance of an opaque colour: 0 for black, 1 for white. */
export function relativeLuminance(c) {
  return 0.2126 * linear(c.r) + 0.7152 * linear(c.g) + 0.0722 * linear(c.b)
}

/** WCAG 2.x contrast ratio of two opaque colours, from 1 (identical) to 21 (black on white). */
export function contrastRatio(x, y) {
  if (x.a !== 1 || y.a !== 1) throw new Error('composite a translucent colour before measuring it')
  const [hi, lo] = [relativeLuminance(x), relativeLuminance(y)].sort((p, q) => q - p)
  return (hi + 0.05) / (lo + 0.05)
}

// Reading the Kotlin

const BUILT_IN = new Map([
  ['White', parseArgb('0xFFFFFFFF')],
  ['Black', parseArgb('0xFF000000')],
  ['Transparent', parseArgb('0x00000000')],
])

/** Every `val NAME = Color(0x…)` and `val NAME: Color = Color(0x…)` in a source, by name. */
export function parseColourVals(source) {
  const vals = new Map()
  const re = /\bval\s+(\w+)\s*(?::\s*Color\s*)?=\s*Color\(\s*(0x[0-9a-fA-F]{6,8})\s*\)/g
  for (const m of source.matchAll(re)) vals.set(m[1], parseArgb(m[2]))
  return vals
}

/**
 * A colour expression as the Kotlin writes it: a palette name, an inline literal, a named val or a
 * built-in. Anything else is null, and the pair that needs it is reported as unmeasured.
 */
export function resolveColour(expression, vals) {
  const e = expression.trim()
  let m = e.match(/^OpenCodePalette\.(\w+)$/) ?? e.match(/^(\w+)$/)
  if (m) return vals.get(m[1]) ?? null
  m = e.match(/^Color\(\s*(0x[0-9a-fA-F]{6,8})\s*\)$/)
  if (m) return parseArgb(m[1])
  m = e.match(/^Color\.(White|Black|Transparent)$/)
  if (m) return BUILT_IN.get(m[1])
  return null
}

/**
 * The roles of `val NAME: ColorScheme = darkColorScheme(…)` (or light), each resolved to a colour.
 * A role whose expression is not one resolveColour reads maps to null.
 */
export function parseScheme(source, name, vals) {
  const head = source.match(new RegExp(`val\\s+${name}\\s*:\\s*ColorScheme\\s*=\\s*\\w+ColorScheme\\(`))
  if (!head) throw new Error(`${name} not found`)
  const bodyStart = head.index + head[0].length
  const bodyEnd = source.indexOf('\n)', bodyStart)
  if (bodyEnd < 0) throw new Error(`${name} is not closed`)
  const roles = new Map()
  for (const line of source.slice(bodyStart, bodyEnd).split('\n')) {
    const m = line.match(/^\s*(\w+)\s*=\s*(.+?),?\s*$/)
    if (m) roles.set(m[1], resolveColour(m[2], vals))
  }
  return roles
}

/** One expression of the `DiffColors.of` mapping, in the shapes that function uses. */
function readDiffExpression(expression) {
  const e = expression.trim()
  let m = e.match(/^if \(scheme\.surface\.luminance\(\) < DARK_SURFACE\) (\w+) else (\w+)$/)
  if (m) return { kind: 'by-surface', dark: m[1], light: m[2] }
  m = e.match(/^scheme\.(\w+)(?:\.copy\(alpha = ([\d.]+)f\))?$/)
  if (m) return { kind: 'scheme', role: m[1], alpha: m[2] === undefined ? null : Number(m[2]) }
  if (/^(?:Color\.\w+|\w+)$/.test(e)) return { kind: 'colour', expression: e }
  return { kind: 'unknown', expression: e }
}

/** The `DiffColors.of` mapping: each diff role's expression, and the surface luminance that splits dark. */
export function parseDiffMapping(source) {
  const fn = source.indexOf('fun of(')
  if (fn < 0) throw new Error('DiffColors.of not found')
  const constructor = 'DiffColors('
  const open = source.indexOf(constructor, fn)
  const body = source.slice(open + constructor.length)
  const end = body.search(/\n {8}\)/)
  if (end < 0) throw new Error('DiffColors.of is not closed')
  const mapping = new Map()
  for (const line of body.slice(0, end).split('\n')) {
    if (/^\s*\/\//.test(line)) continue
    const m = line.match(/^\s*(\w+)\s*=\s*(.+?),?\s*$/)
    if (m) mapping.set(m[1], readDiffExpression(m[2]))
  }
  // The theme redesign (e186aed) maps every diff role straight from a scheme role, so the
  // surface-luminance branch and its threshold constant no longer exist in the source. The
  // branch shape is still parsed, because a theme that needs per-surface diff variants can
  // bring it back; without the constant such a role is unmeasured rather than fatal.
  const threshold = source.match(/const val DARK_SURFACE = ([\d.]+)f?/)
  return { mapping, darkSurfaceBelow: threshold ? Number(threshold[1]) : null }
}

/**
 * The DiffColors of one scheme, each kept as the colour the renderer uses (alpha included). The
 * `if` on the surface's luminance picks the dark or the light variant the way the code does.
 */
export function diffColours({ mapping, darkSurfaceBelow }, roles, vals) {
  const surface = roles.get('surface')
  if (!surface) throw new Error('surface is not set in the scheme')
  const dark = darkSurfaceBelow === null ? null : relativeLuminance(surface) < darkSurfaceBelow
  const out = new Map()
  for (const [role, expr] of mapping) {
    let colour = null
    if (expr.kind === 'scheme') {
      colour = roles.get(expr.role) ?? null
      if (colour && expr.alpha !== null) colour = { ...colour, a: expr.alpha }
    } else if (expr.kind === 'by-surface') {
      colour = dark === null ? null : vals.get(dark ? expr.dark : expr.light) ?? null
    } else if (expr.kind === 'colour') {
      colour = resolveColour(expr.expression, vals)
    }
    out.set(role, colour)
  }
  return out
}

/** The code block's background and label roles, and the link colour, from MarkdownText.kt. */
export function parseMarkdownColours(source) {
  const block = source.match(/fun CodeBlock\([\s\S]*?Surface\([\s\S]*?color = MaterialTheme\.colorScheme\.(\w+),/)
  const label = source.match(/codeTypography\.small\.copy\(color = MaterialTheme\.colorScheme\.(\w+)\)/)
  const linkLiteral = source.match(/is MarkdownInline\.Link[\s\S]*?Color\(\s*(0x[0-9a-fA-F]{6,8})\s*\)/)
  const linkRole = source.match(/val linkColor = MaterialTheme\.colorScheme\.(\w+)/)
  return {
    codeBlockRole: block?.[1] ?? null,
    labelRole: label?.[1] ?? null,
    linkRole: linkRole?.[1] ?? null,
    link: linkLiteral ? parseArgb(linkLiteral[1]) : null,
  }
}

// Measuring

/**
 * One pair. `fg` and `bg` are `{ name, colour }`, and the background is already opaque. A colour that
 * could not be read is recorded as unmeasured, with the name of the role that was missing.
 * `componentOnly` pairs carry no text, so they are judged against the 3:1 component floor alone.
 */
function measure(scheme, pair, fg, bg, out, componentOnly = false) {
  if (!fg.colour || !bg.colour) {
    const missing = fg.colour ? bg.name : fg.name
    out.unmeasured.push({ scheme, pair, reason: `${missing} is not a colour this reads` })
    return
  }
  const ratio = contrastRatio(composite(fg.colour, bg.colour), bg.colour)
  out.pairs.push({
    scheme,
    pair,
    foreground: fg.name,
    background: bg.name,
    ratio,
    text: componentOnly ? true : ratio >= TEXT_MIN,
    uiOrLarge: ratio >= UI_OR_LARGE_MIN,
    componentOnly,
  })
}

/** Every pair for one fallback scheme. `ctx` holds the parsed vals, the diff mapping and the Markdown colours. */
function measureScheme(scheme, roles, ctx, out) {
  const surface = roles.get('surface')
  const diff = diffColours(ctx.mapping, roles, ctx.vals)
  const role = (name) => ({ name, colour: roles.get(name) ?? null })

  for (const [fg, bg] of SCHEME_PAIRS) {
    measure(scheme, `${fg} / ${bg}`, role(fg), role(bg), out)
  }
  for (const pair of COMPONENT_ONLY) {
    const [fg, bg] = pair.split(' / ')
    measure(scheme, pair, role(fg), role(bg), out, true)
  }

  // Each row's text, gutter, marker and tokens are drawn on the row's background, with the card's
  // surface behind a translucent one. The gutter is the row's text colour on a hunk header, so it is
  // listed once there.
  for (const { row, bg, text } of ROWS) {
    const raw = diff.get(bg)
    const background = { name: bg, colour: raw ? composite(raw, surface) : null }
    const foregrounds = [...(text === 'gutter' ? [] : [text]), 'gutter', 'marker', ...TOKENS]
    for (const f of foregrounds) {
      measure(scheme, `diff ${f} on ${row}`, { name: f, colour: diff.get(f) ?? null }, background, out)
    }
  }

  const codeBackground = role(ctx.markdown.codeBlockRole ?? '')
  if (!ctx.markdown.codeBlockRole) {
    out.unmeasured.push({ scheme, pair: 'code block', reason: 'the CodeBlock surface colour is not readable' })
  } else {
    measure(scheme, 'code block label on code block', role(ctx.markdown.labelRole ?? ''), codeBackground, out)
    for (const f of ['plain', ...TOKENS]) {
      measure(scheme, `code ${f} on code block`, { name: f, colour: diff.get(f) ?? null }, codeBackground, out)
    }
  }

  const linkColour = ctx.markdown.link ?? (ctx.markdown.linkRole ? roles.get(ctx.markdown.linkRole) : null)
  measure(scheme, 'markdown link on surface', { name: 'link', colour: linkColour }, { name: 'surface', colour: surface }, out)

  // The file viewer marks a selected line with secondaryContainer. Its line number is onSurfaceVariant,
  // which is set in FileBrowserScreen. Its text sets no colour, so the content colour of the host
  // Surface is used, which this file does not show. onSurface is assumed for that host.
  const selectedLine = role('secondaryContainer')
  measure(scheme, 'file line number on selected line', role('onSurfaceVariant'), selectedLine, out)
  measure(scheme, 'file text on selected line (host colour assumed onSurface)', role('onSurface'), selectedLine, out)
}

/** Reads the sources and measures both fallback schemes. Throws only when a source file cannot be read at all. */
export function run(root = ROOT) {
  const read = (rel) => readFileSync(join(root, rel), 'utf8')
  const colourSrc = read(FILES.colour)
  const diffSrc = read(FILES.diff)
  const markdownSrc = read(FILES.markdown)
  const vals = new Map([...parseColourVals(colourSrc), ...parseColourVals(diffSrc)])
  const ctx = {
    vals,
    mapping: parseDiffMapping(diffSrc),
    markdown: parseMarkdownColours(markdownSrc),
  }
  const out = { pairs: [], unmeasured: [] }
  for (const [scheme, name] of [
    ['light', 'OpenCodeLightColors'],
    ['dark', 'OpenCodeDarkColors'],
  ]) {
    try {
      measureScheme(scheme, parseScheme(colourSrc, name, vals), ctx, out)
    } catch (error) {
      out.unmeasured.push({ scheme, pair: `every pair in the ${scheme} scheme`, reason: error.message })
    }
  }
  return {
    thresholds: { text: TEXT_MIN, uiOrLarge: UI_OR_LARGE_MIN },
    pairs: out.pairs,
    unmeasured: out.unmeasured,
    notComputed: NOT_COMPUTED,
  }
}

// Output

const pad = (s, n) => String(s).padEnd(n)
const verdict = (ok) => (ok ? 'pass' : 'FAIL')

function printReport(report) {
  console.log("Contrast of the app's colour pairs, WCAG 2.x relative luminance")
  console.log(`Text needs ${TEXT_MIN}:1. Large text and UI components need ${UI_OR_LARGE_MIN}:1.`)
  console.log('Fallback schemes only. Dynamic colour is not computed (see the last section).')
  for (const scheme of ['light', 'dark']) {
    const rows = report.pairs.filter((p) => p.scheme === scheme)
    console.log(`\n${scheme} scheme (${rows.length} pairs measured)`)
    console.log(`  ${pad('pair', 62)}${'ratio'.padStart(7)}  ${pad('4.5:1', 5)}  3:1`)
    for (const p of rows) {
      const textVerdict = p.componentOnly ? 'n/a  ' : pad(verdict(p.text), 5)
      console.log(
        `  ${pad(p.pair, 62)}${p.ratio.toFixed(2).padStart(7)}  ${textVerdict}  ${verdict(p.uiOrLarge)}` +
          (p.componentOnly ? '  (component: no text on this pair)' : ''),
      )
    }
    const below45 = rows.filter((p) => !p.componentOnly && !p.text)
    const below3 = rows.filter((p) => !p.uiOrLarge)
    console.log(`  below 4.5:1 as text: ${below45.length}. below 3:1 at all: ${below3.length}.`)
  }
  if (report.unmeasured.length) {
    console.log('\nUnmeasured (a role or colour this could not read):')
    for (const u of report.unmeasured) console.log(`  ${u.scheme ?? '-'}: ${u.pair}. ${u.reason}`)
  }
  console.log('\nNot computed:')
  for (const n of report.notComputed) console.log(`  ${n.area}. ${n.reason}`)
}

/** The failures --strict refuses to ship: unreadable text, an unreadable component, or a gap in the measurement. */
function strictProblems(report) {
  if (report.error) return [`the colour sources could not be read: ${report.error}`]
  const problems = []
  for (const p of report.pairs) {
    if (!p.componentOnly && !p.text) {
      problems.push(`${p.scheme}: ${p.pair} is ${p.ratio.toFixed(2)}:1 as text; it needs ${TEXT_MIN}:1`)
    }
    if (!p.uiOrLarge) {
      problems.push(`${p.scheme}: ${p.pair} is ${p.ratio.toFixed(2)}:1; even a component needs ${UI_OR_LARGE_MIN}:1`)
    }
  }
  for (const u of report.unmeasured) problems.push(`${u.scheme ?? '-'}: ${u.pair} is unmeasured (${u.reason})`)
  return problems
}

export function main(argv = process.argv.slice(2)) {
  let report
  try {
    report = run()
  } catch (error) {
    report = { error: error.message }
  }
  if (argv.includes('--json')) console.log(JSON.stringify(report, null, 1))
  else if (report.error) console.log(`could not read the colour sources: ${report.error}`)
  else printReport(report)
  if (argv.includes('--strict')) {
    const problems = strictProblems(report)
    if (problems.length) {
      console.error(`\n${problems.length} contrast problem(s) below the WCAG floors:`)
      for (const problem of problems) console.error(`  ${problem}`)
      process.exitCode = 1
    } else {
      console.log('\nstrict: every measured pair meets 4.5:1 as text and 3:1 as a component.')
    }
  }
}

if (process.argv[1]?.endsWith('audit-contrast.mjs')) main()
