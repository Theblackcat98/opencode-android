#!/usr/bin/env node
/**
 * The manual test matrix's Result column, as a gate.
 *
 * docs/MANUAL_TEST_MATRIX.md has one row per manual check, and its own rule is that "a row with a blank
 * result is untested, not passed". Nothing enforced that, so a row could be added with its result left
 * empty, or with a word that is not a result, and the document would still look finished. This reads every
 * row (a line that begins `| <Letter><digits> |`) and fails when its result is blank or does not start with
 * an accepted marker.
 *
 * The markers are the ones the rows use, plus the ones its "Recording a result" section prescribes: `✓ pass`
 * and `✗ fail` are how the rows write a result, and that section names `pass`, `fail` and `n/a`. A marker
 * has to end at a word boundary, so `passed` is not `pass`. The result is the cell under the table's
 * `Result` header, and a row with more cells than its header is a finding of its own: that is an unescaped
 * `|` inside a cell, which GitHub renders by dropping the extra cells.
 *
 * It does not judge what a result says. A `✗ fail` is a real result; the document's own rule, that a
 * release is not ready while any row is blank or ✗, is the part that blocks a release.
 *
 * Usage: node tools/audit-matrix.mjs
 *   Exits 1 when a row fails or when no row is found at all. It prints nothing else on success.
 */
import { readFileSync } from 'node:fs'
import { join, dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const ROOT = join(dirname(fileURLToPath(import.meta.url)), '..')
export const MATRIX = join(ROOT, 'docs/MANUAL_TEST_MATRIX.md')

/** The markers a result may begin with. Each must be followed by a non-letter or the end of the cell. */
export const ACCEPTED_MARKERS = ['✓ pass', '✗ fail', 'pass', 'fail', 'n/a']

/** A matrix row. Header and separator lines (`| # |`, `| --- |`) and prose do not match. */
const ROW = /^\|\s*([A-Z][0-9]+)\s*\|/

/** A table's header line. Its column count is what every row under it must have. */
const HEADER = /^\|\s*#\s*\|/

/**
 * The trimmed cells of one table line, with `\|` kept as text inside its cell.
 *
 * A row begins and ends with a pipe, so the text before the first pipe is not a cell, and neither is
 * the text after the last one when it is blank. A blank cell between two pipes is a cell: `| B1 | Check |   |`
 * has a Result of ''.
 */
export function cellsOf(line) {
  const cells = ['']
  for (let i = 0; i < line.length; i++) {
    const ch = line[i]
    if (ch === '\\' && line[i + 1] === '|') {
      cells[cells.length - 1] += '\\|'
      i++
    } else if (ch === '|') {
      cells.push('')
    } else {
      cells[cells.length - 1] += ch
    }
  }
  const body = cells.slice(1)
  if (body.length > 0 && body[body.length - 1].trim() === '') body.pop()
  return body.map((cell) => cell.trim())
}

/** Whether a result starts with one of [ACCEPTED_MARKERS] and not with a longer word that begins the same way. */
export function acceptsResult(result) {
  return ACCEPTED_MARKERS.some((marker) => {
    if (!result.startsWith(marker)) return false
    const next = result.charAt(marker.length)
    return next === '' || !/[A-Za-z]/.test(next)
  })
}

/**
 * Every matrix row in the document, in order, with its line number, ID, result and cell counts.
 *
 * A row is read under the header line that opens its table (`| # | ... |`), and its result is the cell
 * under that header's last column. The tables differ in width (three columns for most, six for the device
 * table), so the header is what says which cell is the result. A row before any header falls back to its
 * last cell after the ID.
 *
 * @param {string} source the markdown text of the matrix
 */
export function rowsOf(source) {
  const rows = []
  let width = 0
  source.split('\n').forEach((text, index) => {
    if (HEADER.test(text)) {
      width = cellsOf(text).length
      return
    }
    const match = ROW.exec(text)
    if (!match) return
    const cells = cellsOf(text)
    const column = width > 0 ? width - 1 : cells.length - 1
    const result = column >= 1 && column < cells.length ? cells[column] : ''
    rows.push({ line: index + 1, id: match[1], result, cells: cells.length, width })
  })
  return rows
}

/**
 * The problems in a matrix.
 *
 * A row with more cells than its table has columns is `malformed`: an unescaped `|` inside a cell, which
 * GitHub renders by dropping the cells after the last column. A row whose result is blank, or missing, is
 * `blank`, and one whose result does not start with an accepted marker is `unrecognised`. A document with
 * no rows is also a finding, so a renamed heading or a broken table fails the gate rather than passing it
 * with nothing read.
 *
 * @param {string} source the markdown text of the matrix
 * @returns {{ rows: object[], findings: object[] }}
 */
export function auditMatrix(source) {
  const rows = rowsOf(source)
  const findings = []
  for (const row of rows) {
    if (row.width > 0 && row.cells > row.width) findings.push({ ...row, reason: 'malformed' })
    else if (row.result === '') findings.push({ ...row, reason: 'blank' })
    else if (!acceptsResult(row.result)) findings.push({ ...row, reason: 'unrecognised' })
  }
  if (rows.length === 0) findings.push({ line: 0, id: '', result: '', reason: 'no rows' })
  return { rows, findings }
}

if (process.argv[1] && fileURLToPath(import.meta.url) === resolve(process.argv[1])) {
  const { rows, findings } = auditMatrix(readFileSync(MATRIX, 'utf8'))
  if (findings.length > 0) {
    console.error(`audit-matrix: ${findings.length} of ${rows.length} matrix rows fail the result check.`)
    console.error(`A result must begin with one of: ${ACCEPTED_MARKERS.join(', ')}.`)
    for (const f of findings) {
      if (f.reason === 'no rows') {
        console.error('  no matrix rows were found in docs/MANUAL_TEST_MATRIX.md')
      } else if (f.reason === 'blank') {
        console.error(`  ${f.id} (line ${f.line}): the result is blank`)
      } else if (f.reason === 'malformed') {
        const where = `${f.cells} cells under a ${f.width}-column table`
        console.error(`  ${f.id} (line ${f.line}): ${where}, so a | inside a cell is unescaped`)
      } else {
        console.error(`  ${f.id} (line ${f.line}): ${JSON.stringify(f.result.slice(0, 60))}`)
      }
    }
    process.exit(1)
  }
  console.log(`audit-matrix: all ${rows.length} matrix rows have an accepted result.`)
}
