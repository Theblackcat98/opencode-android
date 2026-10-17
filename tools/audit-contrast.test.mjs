import assert from "node:assert/strict"
import test from "node:test"
import { fileURLToPath } from "node:url"
import {
  composite,
  contrastRatio,
  parseArgb,
  parseColourVals,
  parseDiffMapping,
  parseMarkdownColours,
  parseScheme,
  relativeLuminance,
  resolveColour,
  run,
} from "./audit-contrast.mjs"

const WHITE = parseArgb("0xFFFFFFFF")
const BLACK = parseArgb("0xFF000000")

test("black on white is 21:1", () => {
  assert.ok(Math.abs(contrastRatio(BLACK, WHITE) - 21) < 1e-9)
  // Order does not matter.
  assert.ok(Math.abs(contrastRatio(WHITE, BLACK) - 21) < 1e-9)
})

test("a colour against itself is 1:1", () => {
  const x = parseArgb("0xFF5C9CF5")
  assert.ok(Math.abs(contrastRatio(x, x) - 1) < 1e-9)
})

test("relative luminance is 0 for black and 1 for white", () => {
  assert.ok(Math.abs(relativeLuminance(BLACK)) < 1e-12)
  assert.ok(Math.abs(relativeLuminance(WHITE) - 1) < 1e-9)
})

test("#767676 on white is 4.54:1, as worked out by hand", () => {
  // 0x76 is 118, and 118 / 255 = 0.46275. Then (0.46275 + 0.055) / 1.055 = 0.49075, and
  // 0.49075 ^ 2.4 = 0.18116, which is the relative luminance of the grey, since its channels are
  // equal. So the ratio is (1 + 0.05) / (0.18116 + 0.05) = 4.5422.
  const grey = parseArgb("0x767676")
  assert.ok(Math.abs(relativeLuminance(grey) - 0.18116) < 0.0001)
  assert.ok(Math.abs(contrastRatio(grey, WHITE) - 4.5422) < 0.0005)
})

test("a literal with no alpha is opaque, and the 8-digit form agrees with it", () => {
  assert.deepEqual(parseArgb("0x767676"), parseArgb("0xFF767676"))
  assert.throws(() => parseArgb("0xFFF"), /not a colour literal/)
})

test("a translucent colour composites onto its background", () => {
  // Half-black over white is mid grey.
  const half = composite({ r: 0, g: 0, b: 0, a: 0.5 }, WHITE)
  assert.ok(Math.abs(half.r - 0.5) < 1e-9)
  assert.equal(half.a, 1)
})

test("a translucent colour cannot be measured until it is composited", () => {
  assert.throws(() => contrastRatio({ r: 0, g: 0, b: 0, a: 0.5 }, WHITE), /composite/)
})

test("colour vals and built-ins are read from Kotlin", () => {
  const vals = parseColourVals(
    "object OpenCodePalette {\n    val Peach: Color = Color(0xFFFAB283)\n}\nprivate val ADDED_LIGHT = Color(0xFFD3F2DA)\n",
  )
  assert.equal(vals.size, 2)
  assert.deepEqual(resolveColour("OpenCodePalette.Peach", vals), parseArgb("0xFFFAB283"))
  assert.deepEqual(resolveColour("ADDED_LIGHT", vals), parseArgb("0xFFD3F2DA"))
  assert.deepEqual(resolveColour("Color.White", vals), WHITE)
  assert.equal(resolveColour("Color.Transparent", vals).a, 0)
  assert.equal(resolveColour("scheme.primary", vals), null)
})

test("a scheme's roles are read, and an unknown expression is null rather than dropped", () => {
  const source = [
    "internal val Demo: ColorScheme = lightColorScheme(",
    "    primary = OpenCodePalette.Peach,",
    "    onPrimary = Color.White,",
    "    surface = Color(0xFF0A0A0A),",
    "    error = something.Else,",
    ")",
  ].join("\n")
  const vals = parseColourVals("val Peach: Color = Color(0xFFFAB283)")
  const roles = parseScheme(source, "Demo", vals)
  assert.deepEqual(roles.get("primary"), parseArgb("0xFFFAB283"))
  assert.deepEqual(roles.get("onPrimary"), WHITE)
  assert.equal(roles.get("error"), null)
})

test("the diff mapping keeps alpha and the surface-luminance branch", () => {
  const source = [
    "private const val DARK_SURFACE = 0.5f",
    "fun of(scheme: X = Y): DiffColors = DiffColors(",
    "    // a comment, which is skipped",
    "    added = if (scheme.surface.luminance() < DARK_SURFACE) ADDED_DARK else ADDED_LIGHT,",
    "    comment = scheme.onSurfaceVariant.copy(alpha = 0.7f),",
    "    context = Color.Transparent,",
    "        )",
    "",
  ].join("\n")
  const { mapping, darkSurfaceBelow } = parseDiffMapping(source)
  assert.equal(darkSurfaceBelow, 0.5)
  assert.deepEqual(mapping.get("added"), {
    kind: "by-surface",
    dark: "ADDED_DARK",
    light: "ADDED_LIGHT",
  })
  assert.deepEqual(mapping.get("comment"), { kind: "scheme", role: "onSurfaceVariant", alpha: 0.7 })
  assert.deepEqual(mapping.get("context"), { kind: "colour", expression: "Color.Transparent" })
})

test("the Markdown code block and link colours are read", () => {
  const source = [
    "fun CodeBlock(code: String) {",
    "    Surface(",
    "        modifier = modifier,",
    "        color = MaterialTheme.colorScheme.surfaceVariant,",
    "    ) {",
    "        Text(style = codeTypography.small.copy(color = MaterialTheme.colorScheme.onSurfaceVariant))",
    "    }",
    "}",
    "is MarkdownInline.Link -> withStyle(",
    "    SpanStyle(color = Color(0xFF1A73E8)),",
    ") { append(inline.text) }",
  ].join("\n")
  const read = parseMarkdownColours(source)
  assert.equal(read.codeBlockRole, "surfaceVariant")
  assert.equal(read.labelRole, "onSurfaceVariant")
  assert.deepEqual(read.link, parseArgb("0xFF1A73E8"))
})

test("the repository's sources measure every pair, with nothing unmeasured", () => {
  const root = fileURLToPath(new URL("..", import.meta.url))
  const report = run(root)
  assert.deepEqual(report.unmeasured, [])
  assert.ok(report.runtimeValidated.some((check) => check.area === "OpenCode V2 imported themes"))
  for (const scheme of ["light", "dark"]) {
    const rows = report.pairs.filter((p) => p.scheme === scheme)
    assert.ok(rows.length > 60, `${scheme}: only ${rows.length} pairs measured`)
  }
})

test("a pair that carries no text is judged by the component floor alone", () => {
  const root = fileURLToPath(new URL("..", import.meta.url))
  const outline = run(root).pairs.find((p) => p.pair === "outline / surface")
  // The disconnected-server dot: outline on surface is below 4.5:1 and that is fine, because no
  // text is ever drawn in that pair. It must still clear the 3:1 component floor.
  assert.ok(outline.componentOnly, "outline/surface should be marked component-only")
  assert.ok(outline.ratio < 4.5, "this fixture pair should sit below the text floor to prove the point")
  assert.equal(outline.text, true, "a component-only pair is not held to the text floor")
  assert.ok(outline.uiOrLarge, "a component-only pair is still held to 3:1")
})

test("the repository's pairs meet the WCAG floors, as --strict demands", () => {
  const root = fileURLToPath(new URL("..", import.meta.url))
  const report = run(root)
  const failures = report.pairs.filter((p) => (!p.componentOnly && !p.text) || !p.uiOrLarge)
  assert.deepEqual(
    failures.map((p) => `${p.scheme}: ${p.pair} ${p.ratio.toFixed(2)}:1`),
    [],
    "a colour regressed below 4.5:1 as text or 3:1 as a component",
  )
})
