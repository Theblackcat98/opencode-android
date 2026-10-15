#!/usr/bin/env node
/**
 * The screenshot on every card of `docs/app_screen_flow.html` has to be the screen the card names.
 *
 * `tools/update-screen-flow-shots.mjs` embeds each committed baseline as a 380 px WebP in `thumbData`
 * and records the source file in `image`. Nothing at build time forced those two to agree, and they
 * didn't: a refresh that could not run ImageMagick rewrote 24 `image` paths while leaving the old
 * thumbnails, five cards showed another screen entirely (Project settings showed Permissions, Catalog
 * showed Settings, Permissions showed the session timeline, Maintenance reused the Servers capture,
 * Usage reused the Home capture), and the Usage and Home cards embedded the same picture.
 *
 * This gate parses the canvas and checks:
 *
 *   1. Every node names a baseline that exists, except the two DEVICE_ONLY nodes (`pair_device`,
 *      `changelog`), whose `image` must stay empty because no reproducible baseline can be recorded
 *      for them (CameraX; `:app` has no Robolectric wiring).
 *   2. The embedded thumbnail is the file the node names: normalised cross correlation at 96x214 is
 *      at least MATCH_NCC (0.95; a 380 px resize of the same capture scores 0.99+).
 *   3. No two nodes embed the same capture (pairwise NCC below DUPLICATE_NCC, 0.98) and no two nodes
 *      reference the same file.
 *   4. Every edge matches a button on its source node and targets a known node, every button has an
 *      edge, and no edge is duplicated. A card no edge leads to is a warning, not a failure: the
 *      canvas may legitimately float one (`pair_device` is reached from Settings, which has no card).
 *
 * Checks 1, 3 (path and hash) and 4 need only Node. The pixel checks (2 and the duplicate half of 3)
 * run when ImageMagick is installed (`magick` or `convert`) and are skipped with a notice otherwise.
 *
 * Usage: node tools/audit-screen-flow.mjs
 */
import { execSync } from 'node:child_process'
import { createHash } from 'node:crypto'
import { existsSync, readFileSync } from 'node:fs'
import { dirname, join, relative } from 'node:path'
import { fileURLToPath } from 'node:url'

const ROOT = join(dirname(fileURLToPath(import.meta.url)), '..')
const HTML_PATH = join(ROOT, 'docs/app_screen_flow.html')

/** Keep in sync with `DEVICE_ONLY` in tools/update-screen-flow-shots.mjs. */
export const DEVICE_ONLY = new Set(['changelog', 'pair_device'])

/** A 380 px resize of the same capture scores 0.99+ at this size. */
const MATCH_NCC = 0.95
/** Two captures of the same screen from the same source score 0.996+. */
const DUPLICATE_NCC = 0.98
const SIZE = '96x214'

/** The NODES and EDGES arrays of the canvas. */
export function parseCanvas(html) {
  const nodes = html.match(/const NODES = (\[[\s\S]*?\]);\s*(?:const EDGES|let|function)/)
  const edges = html.match(/const EDGES = (\[[\s\S]*?\]);\s*(?:let|function|\/\/|$)/)
  if (!nodes || !edges) throw new Error(`could not find the NODES and EDGES arrays in ${relative(HTML_PATH)}`)
  return { nodes: JSON.parse(nodes[1]), edges: JSON.parse(edges[1]) }
}

/**
 * The checks that need no image decoder: image references, duplicate thumbnails and paths, and the
 * edge/button graph. `exists` and `deviceOnly` are injectable so the tests can pin the behaviour.
 */
export function staticProblems(nodes, edges, { exists = existsSync, deviceOnly = DEVICE_ONLY } = {}) {
  const problems = []
  const ids = new Set()
  const thumbs = new Map()
  const images = new Map()

  for (const node of nodes) {
    if (ids.has(node.id)) problems.push(`${node.id}: duplicate node id`)
    ids.add(node.id)
    if (!/^data:image\/webp;base64,[A-Za-z0-9+/]+=*$/.test(node.thumbData ?? '')) {
      problems.push(`${node.id}: thumbData is not a non-empty WebP data URL`)
    }
    const thumb = createHash('sha256').update(node.thumbData ?? '').digest('hex')
    if (thumbs.has(thumb)) problems.push(`${node.id} and ${thumbs.get(thumb)} embed the same thumbnail`)
    else thumbs.set(thumb, node.id)

    if (deviceOnly.has(node.id)) {
      if (node.image !== '') {
        problems.push(`${node.id}: device-only node must keep image empty (got ${JSON.stringify(node.image)})`)
      }
      continue
    }
    if (!node.image) {
      problems.push(`${node.id}: has no image reference`)
      continue
    }
    if (!exists(node.image)) problems.push(`${node.id}: ${node.image} does not exist`)
    if (images.has(node.image)) {
      problems.push(`${node.id} and ${images.get(node.image)} reference the same file (${node.image})`)
    } else {
      images.set(node.image, node.id)
    }
  }

  const buttons = new Map()
  for (const node of nodes) {
    for (const button of node.buttons ?? []) {
      buttons.set(
        `${node.id}\u0000${button.label}\u0000${button.target}`,
        `${node.id} -> ${button.target} ("${button.label}")`,
      )
    }
  }
  const seen = new Set()
  for (const edge of edges) {
    if (!ids.has(edge.from)) {
      problems.push(`${edge.id}: unknown source node ${edge.from}`)
      continue
    }
    if (!ids.has(edge.to)) problems.push(`${edge.id}: unknown target node ${edge.to}`)
    const key = `${edge.from}\u0000${edge.label}\u0000${edge.to}`
    if (!buttons.has(key)) {
      problems.push(`${edge.id}: ${edge.from} -> ${edge.to} ("${edge.label}") matches no button on ${edge.from}`)
    }
    if (seen.has(key)) problems.push(`${edge.id}: duplicate edge ${edge.from} -> ${edge.to} ("${edge.label}")`)
    seen.add(key)
  }
  for (const [key, description] of buttons) {
    if (!seen.has(key)) problems.push(`button ${description} has no edge`)
  }
  return problems
}

/** The cosine similarity of two mean-centred vectors; 1.0 means the same capture. */
export function ncc(a, b) {
  let sum = 0
  for (let i = 0; i < a.length; i++) sum += a[i] * b[i]
  return sum
}

/** Mean-centre and unit-scale raw 8-bit pixels for `ncc`. */
export function normalise(bytes) {
  const vector = Float64Array.from(bytes)
  let mean = 0
  for (const value of vector) mean += value
  mean /= vector.length
  let norm = 0
  for (let i = 0; i < vector.length; i++) {
    vector[i] -= mean
    norm += vector[i] * vector[i]
  }
  norm = Math.sqrt(norm)
  if (norm > 0) {
    for (let i = 0; i < vector.length; i++) vector[i] /= norm
  }
  return vector
}

function imageMagick() {
  for (const command of ['magick', 'convert']) {
    try {
      execSync(`${command} -version`, { stdio: 'ignore' })
      return command
    } catch {
      // try the next entry point
    }
  }
  return null
}

/** `96x214` grayscale bytes of a WebP buffer or of a file on disk. */
function gray(command, source) {
  const input = Buffer.isBuffer(source)
  const target = input ? `${command} webp:-` : `${command} ${JSON.stringify(source)}`
  return execSync(`${target} -resize ${SIZE}! -colorspace Gray -depth 8 gray:-`, {
    input: input ? source : undefined,
    maxBuffer: 1 << 24,
  })
}

if (import.meta.url === `file://${process.argv[1]}`) {
  const html = readFileSync(HTML_PATH, 'utf8')
  const { nodes, edges } = parseCanvas(html)
  const problems = staticProblems(nodes, edges, { exists: (path) => existsSync(join(ROOT, path)) })

  const warnings = []
  const incoming = new Set(edges.map((edge) => edge.to))
  for (const node of nodes) {
    if (!incoming.has(node.id) && node.id !== 'servers') warnings.push(`${node.id}: no edge leads to this card`)
  }

  const command = imageMagick()
  if (command) {
    const thumbVectors = new Map()
    for (const node of nodes) {
      const base64 = node.thumbData.slice(node.thumbData.indexOf(',') + 1)
      thumbVectors.set(node.id, normalise(gray(command, Buffer.from(base64, 'base64'))))
    }
    for (const node of nodes) {
      if (DEVICE_ONLY.has(node.id) || !node.image || !existsSync(join(ROOT, node.image))) continue
      const score = ncc(thumbVectors.get(node.id), normalise(gray(command, join(ROOT, node.image))))
      if (score < MATCH_NCC) {
        problems.push(
          `${node.id}: the embedded thumbnail does not match ${node.image} (ncc ${score.toFixed(3)} < ${MATCH_NCC}); ` +
            `re-run tools/update-screen-flow-shots.mjs`,
        )
      }
    }
    const ids = nodes.map((node) => node.id)
    for (let i = 0; i < ids.length; i++) {
      for (let j = i + 1; j < ids.length; j++) {
        const score = ncc(thumbVectors.get(ids[i]), thumbVectors.get(ids[j]))
        if (score > DUPLICATE_NCC) {
          problems.push(`${ids[i]} and ${ids[j]} embed the same capture (ncc ${score.toFixed(3)} > ${DUPLICATE_NCC})`)
        }
      }
    }
  } else {
    console.log('audit-screen-flow: ImageMagick not found; pixel checks skipped (existence and graph checks ran)')
  }

  if (problems.length > 0) {
    console.error(problems.join('\n'))
    console.error(`\n${problems.length} problem(s) in docs/app_screen_flow.html.`)
    process.exit(1)
  }
  for (const warning of warnings) console.warn(`warning: ${warning}`)
  const pixels = command ? ', every thumbnail matches its baseline, no duplicate captures' : ''
  console.log(`audit-screen-flow: ${nodes.length} screens and ${edges.length} edges are consistent${pixels}`)
}
