#!/usr/bin/env node
// The audit's own tests. It has to parse the canvas out of the page, and it has to say no to exactly the
// mistakes that shipped: a card whose file is gone, a device-only card that names a file anyway, two cards
// embedding the same capture, two cards naming the same file, an edge that no button draws, a button no
// edge draws, and a duplicated edge. The pixel helpers are pinned to the obvious round trips.
import assert from 'node:assert/strict'
import { ncc, normalise, parseCanvas, staticProblems } from './audit-screen-flow.mjs'

const node = (over = {}) => ({
  id: 'a',
  title: 'A',
  route: 'ARoute',
  image: 'shots/a.png',
  thumbData: 'data:image/webp;base64,AAAA',
  buttons: [{ label: 'Go', target: 'b', action: 'go()' }],
  ...over,
})

const edge = (over = {}) => ({ id: 'e1', from: 'a', to: 'b', label: 'Go', action: 'go()', type: 'nav', buttonIndex: 0, ...over })

const canvas = (nodes, edges) => `const NODES = ${JSON.stringify(nodes)};\nconst EDGES = ${JSON.stringify(edges)};\n`

// parseCanvas
const parsed = parseCanvas(canvas([node()], [edge()]))
assert.deepEqual(parsed.nodes.map((n) => n.id), ['a'])
assert.deepEqual(parsed.edges.map((e) => e.id), ['e1'])
assert.throws(() => parseCanvas('<html></html>'))

// A consistent canvas is clean.
const a = node()
const b = node({ id: 'b', title: 'B', route: 'BRoute', image: 'shots/b.png', thumbData: 'data:image/webp;base64,BBBB', buttons: [] })
const clean = { exists: () => true, deviceOnly: new Set() }
assert.deepEqual(staticProblems([a, b], [edge()], clean), [])

// A card whose file is gone.
assert.match(staticProblems([a, b], [edge()], { exists: (p) => p !== 'shots/a.png', deviceOnly: new Set() }).join('\n'), /shots\/a\.png does not exist/)

// The device-only cards must keep their image reference empty.
const deviceOnly = new Set(['a'])
assert.deepEqual(staticProblems([node({ image: '' }), b], [edge()], { exists: () => true, deviceOnly }), [])
const named = staticProblems([a, b], [edge()], { exists: () => true, deviceOnly })
assert.match(named.join('\n'), /device-only node must keep image empty/)

// Two cards embedding the same capture, or naming the same file.
assert.match(staticProblems([a, node({ id: 'b', image: 'shots/b.png', thumbData: a.thumbData })], [], clean).join('\n'), /embed the same thumbnail/)
assert.match(staticProblems([a, node({ id: 'b', thumbData: 'data:image/webp;base64,BBBB' })], [], clean).join('\n'), /reference the same file/)

// A missing thumbnail is not a data URL.
assert.match(staticProblems([node({ thumbData: '' })], [], clean).join('\n'), /not a non-empty WebP data URL/)

// Edge and button graph.
assert.match(staticProblems([a, b], [edge({ label: 'Nope' })], clean).join('\n'), /matches no button/)
assert.match(staticProblems([a, b], [edge({ to: 'c' })], clean).join('\n'), /unknown target node c/)
assert.match(staticProblems([a, b], [edge(), edge()], clean).join('\n'), /duplicate edge/)
assert.match(staticProblems([a, b], [], clean).join('\n'), /button a -> b \("Go"\) has no edge/)

// Pixel helpers: identical vectors correlate at 1, reversed ones at -1.
const vector = normalise(Buffer.from([1, 2, 3, 4, 5, 6, 7, 8]))
assert.equal(Math.round(ncc(vector, vector) * 1000) / 1000, 1)
assert.equal(Math.round(ncc(vector, normalise(Buffer.from([8, 7, 6, 5, 4, 3, 2, 1]))) * 1000) / 1000, -1)

console.log('audit-screen-flow tests: ok')
