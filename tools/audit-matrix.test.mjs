import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import test from 'node:test'
import { MATRIX, acceptsResult, auditMatrix, cellsOf, rowsOf } from './audit-matrix.mjs'

// The header of a three-column table, which is what most of the matrix uses, and of the six-column device table.
const THREE = '| # | Check | Result |'
const SIX = '| # | Device | Form factor | Android | Build | Result |'

// The reasons of the findings for one row under a header, in order. An empty array is a row that passes.
const reasonsOf = (row, header = THREE) => auditMatrix(`${header}\n${row}`).findings.map((f) => f.reason)

// The result the gate reads for one row under a header.
const resultOf = (row, header = THREE) => rowsOf(`${header}\n${row}`)[0].result

test('a blank result fails, in a three-column row and in the six-column device row', () => {
  assert.deepEqual(reasonsOf('| B1 | Add a server by scanning the QR |  |'), ['blank'])
  assert.deepEqual(reasonsOf('| B1 | Add a server by scanning the QR | |'), ['blank'])
  assert.deepEqual(reasonsOf('| A1 | Smallest supported phone | Phone | 8.0 (API 26) | play |   |', SIX), ['blank'])
})

test('a row with no result cell at all is blank, and its check is not read as the result', () => {
  assert.equal(resultOf('| B1 | Add a server by scanning the QR |'), '')
  assert.deepEqual(reasonsOf('| B1 | Add a server by scanning the QR |'), ['blank'])
  assert.deepEqual(reasonsOf('| B1 |'), ['blank'])
})

test('an unknown word fails, including the words that only look like a marker', () => {
  for (const result of ['Not run', 'Not met as written, and not run', 'maybe', 'passed', 'failed']) {
    assert.deepEqual(reasonsOf(`| B1 | Check | ${result} |`), ['unrecognised'], result)
  }
})

test('each accepted marker passes, with or without a note', () => {
  for (const result of [
    '✓ pass',
    '✓ pass (dev server, `fake/reasoning`)',
    '✓ pass in part (emulator only)',
    '✗ fail',
    '✗ fail in part (not built: no prober)',
    'pass',
    'pass, with a gap',
    'fail: the symptom and the device',
    'n/a',
    'n/a (no device was available)',
    'n/a → partly met',
  ]) {
    assert.deepEqual(reasonsOf(`| B1 | Check | ${result} |`), [], result)
  }
})

test('a separator line and a header line are skipped, not read as rows', () => {
  const source = [
    THREE,
    '| --- | --- | --- |',
    '| Servers | One `opencode serve` over **HTTP** |',
    '## B. Connection',
    'Replace the blank with `pass`, `fail` or `n/a`.',
  ].join('\n')
  assert.deepEqual(rowsOf(source), [])
  assert.deepEqual(auditMatrix(source).findings.map((f) => f.reason), ['no rows'])
})

test('an escaped pipe inside a cell does not split the row or make it malformed', () => {
  const line = '| L5 | verify with `adb shell pm list packages \\| grep gms` | n/a (partial, not a pass) |'
  const check = 'verify with `adb shell pm list packages \\| grep gms`'
  assert.deepEqual(cellsOf(line), ['L5', check, 'n/a (partial, not a pass)'])
  assert.equal(resultOf(line), 'n/a (partial, not a pass)')
  assert.deepEqual(reasonsOf(line), [])
})

test('an unescaped pipe inside a result is malformed, not read as the result', () => {
  // The note after the second pipe is a fourth cell under a three-column header, which GitHub drops.
  const line = '| F5 | A worktree is created | ✓ pass, with a defect | **Update:** the form cannot be used |'
  assert.equal(resultOf(line), '✓ pass, with a defect')
  assert.deepEqual(reasonsOf(line), ['malformed'])
})

test('the result is the column under the header, so a six-column table reads its own last column', () => {
  const device = '| A3 | Current flagship | Phone | latest | play | ✓ pass in part (an API 36 emulator) |'
  assert.equal(resultOf(device, SIX), '✓ pass in part (an API 36 emulator)')
  assert.equal(resultOf('| B2 | Add a server | ✓ pass (added a server) |'), '✓ pass (added a server)')
})

test('a marker must end at a word boundary', () => {
  assert.equal(acceptsResult('pass'), true)
  assert.equal(acceptsResult('passed'), false)
  assert.equal(acceptsResult('n/a'), true)
  assert.equal(acceptsResult('n/ab'), false)
})

test('the real matrix document is read, so the gate cannot pass by finding nothing', () => {
  const { rows } = auditMatrix(readFileSync(MATRIX, 'utf8'))
  assert.ok(rows.length > 0, 'no rows were read from docs/MANUAL_TEST_MATRIX.md')
})
