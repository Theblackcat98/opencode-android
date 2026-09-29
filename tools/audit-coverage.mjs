#!/usr/bin/env node
/**
 * The §7 and §8 coverage audit, as an executable claim.
 *
 * Plan §7 has 138 rows and §8 has 94 event types. "Every row is implemented and tested" is the
 * Phase 10 exit criterion, so it is checked mechanically instead of by reading the plan again:
 *
 *   §7  a row is IMPLEMENTED when `ServerApi` declares the exact method and path, and WIRED when
 *       a production source file calls that function, and TESTED when a test source file drives
 *       the production declaration that calls it (tests drive stores and view models, so the API
 *       function name itself never appears in a test).
 *   §8  an event is HANDLED when `EventTypes` binds it and either `ServerDataSet.apply` matches its
 *       payload class or the payload is session-scoped (the reducer owns those), and TESTED when a
 *       test source file names the payload class.
 *
 * Usage: node tools/audit-coverage.mjs [--json] [--strict]
 *   --strict  exit 1 on any gap. The default prints the gaps and exits 0, so a working tree that
 *             is mid-phase still reports.
 *
 * `core/network`'s `ServerApiCoverageTest` runs the same rules from the JVM, so CI fails on a
 * matrix row that nothing backs.
 */
import { readFileSync, readdirSync, statSync } from 'node:fs'
import { join, relative, dirname } from 'node:path'
import { fileURLToPath } from 'node:url'

const ROOT = join(dirname(fileURLToPath(import.meta.url)), '..')
const PLAN = join(ROOT, 'docs/ANDROID_APP_PLAN.md')
const API = join(ROOT, 'core/network/src/main/kotlin/dev/opencode/android/core/network/ServerApi.kt')
const EVENT_TYPES = join(ROOT, 'core/model/src/main/kotlin/dev/opencode/android/core/model/event/EventTypes.kt')
const DATASET = join(ROOT, 'core/data/src/main/kotlin/dev/opencode/android/core/data/server/ServerDataSet.kt')

function kotlinFiles(dir, out = []) {
  for (const name of readdirSync(dir)) {
    if (name === 'build' || name === '.gradle' || name === '.git') continue
    const p = join(dir, name)
    if (statSync(p).isDirectory()) kotlinFiles(p, out)
    else if (name.endsWith('.kt')) out.push(p)
  }
  return out
}

const all = kotlinFiles(ROOT)
const prod = all.filter((f) => f.includes('/src/main/') && !f.endsWith('ServerApi.kt'))
const test = all.filter((f) => f.includes('/src/test/') || f.includes('/src/androidTest/'))
const readAll = (list) => list.map((f) => [relative(ROOT, f), readFileSync(f, 'utf8')])
const prodText = readAll(prod)
const testText = test.map((f) => readFileSync(f, 'utf8')).join('\n')

// ------------------------------------------------------------------ §7 API coverage

const apiSrc = readFileSync(API, 'utf8')
const apiLines = apiSrc.split('\n')
const apiFns = new Map() // "METHOD path" -> kotlin function
for (let i = 0; i < apiLines.length; i++) {
  const ann = apiLines[i].match(/@(GET|POST|PATCH|PUT|DELETE|MULTIPART|HTTP)\((.*)\)/)
  if (!ann) continue
  const [, kw, args] = ann
  let method
  let path
  if (kw === 'HTTP') {
    method = args.match(/method\s*=\s*"(\w+)"/)?.[1]
    path = args.match(/path\s*=\s*"([^"]*)"/)?.[1]
  } else {
    method = kw === 'MULTIPART' ? 'POST' : kw
    path = args.match(/"([^"]*)"/)?.[1] ?? ''
  }
  if (!method || path === undefined) continue
  for (let j = i + 1; j < Math.min(i + 6, apiLines.length); j++) {
    const fn = apiLines[j].match(/(?:suspend\s+)?fun\s+(\w+)/)?.[1]
    if (fn) {
      apiFns.set(`${method} /${path.replace(/^\//, '')}`, fn)
      break
    }
  }
}

const plan = readFileSync(PLAN, 'utf8')
const sec7 = plan.split('## 7. API coverage matrix')[1].split('## 8. Event coverage matrix')[0]
const sec8 = plan.split('## 8. Event coverage matrix')[1].split('## 9. Risks')[0]
const rows = [...sec7.matchAll(/^\|\s*([a-zA-Z]+)\s*\|\s*([^|]+?)\s*\|\s*`([^`]+)`\s*\|\s*(P\d+)\s*\|\s*$/gm)].map(
  (m) => ({ area: m[1], name: m[2], endpoint: m[3], phase: m[4] }),
)

// Rows that are deliberately not Retrofit annotations. Each needs a reason that is still true, and
// each still needs a test, so the row is matched by the name of the thing that implements it.
const DEVIATIONS = {
  'GET /api/fs/read/*':
    'the spec path is a wildcard and Retrofit has no wildcard path, so readFile takes the tail as a @Url and the caller composes api/fs/read/<path>',
  'DELETE /api/worktree':
    'worktree.remove is a DELETE with a body, so it is declared with @HTTP(method = "DELETE", hasBody = true)',
  'GET /api/event':
    'the SSE reader needs the raw response stream, so EventStreamClient builds it over OkHttp directly',
}
const DEVIATION_HOSTS = {
  'GET /api/fs/read/*': ['FileReader', 'readFile'],
  'DELETE /api/worktree': ['removeWorktree', 'ExecutionCommands'],
  'GET /api/event': ['EventStreamClient'],
}

const apiGaps = []
for (const row of rows) {
  const key = row.endpoint
  if (DEVIATIONS[key]) {
    row.implemented = true
    row.deviation = DEVIATIONS[key]
    row.fn = null
  } else {
    const fn = apiFns.get(key)
    row.fn = fn ?? null
    row.implemented = Boolean(fn)
  }

  const callers = []
  if (row.fn) {
    const rx = new RegExp(`\\.${row.fn}\\s*\\(`)
    for (const [file, text] of prodText) {
      if (!rx.test(text)) continue
      // the innermost enclosing declaration, which is what a test would drive
      const decls = [...text.matchAll(/\n\s*(?:@\w+(?:\([^)]*\))?\s*)*(?:public |internal |private |override |suspend |inline |operator )*(?:fun|val|var|class|object)\s+(?:<[^>]*>\s*)?(\w+)/g)]
      const idx = text.search(rx)
      const prior = decls.filter((d) => d.index < idx)
      callers.push({ file, host: prior.length ? prior[prior.length - 1][1] : '?' })
    }
  }
  row.callers = callers
  row.wired = callers.length > 0 || Boolean(row.deviation)

  const hosts = [...new Set(callers.map((c) => c.host))]
  const probes = row.deviation ? DEVIATION_HOSTS[row.endpoint] : hosts
  const hit = probes.filter((h) => new RegExp(`\\b${h}\\b`).test(testText))
  row.hosts = hosts
  row.tested = hit.length > 0

  if (!row.implemented || !row.wired || !row.tested) apiGaps.push(row)
}

// ------------------------------------------------------------------ §8 event coverage

const etSrc = readFileSync(EVENT_TYPES, 'utf8')
const bound = new Map() // type -> payload class
for (const m of etSrc.matchAll(/Binding\("([^"]+)",\s*([\w.]+)::class/g)) {
  bound.set(m[1], m[2].split('.').pop())
}
const dsSrc = readFileSync(DATASET, 'utf8')

/**
 * A payload is HANDLED when one of the four dispatch points in `ServerDataSet.apply` claims it:
 *
 *  - the `when` names its class directly (an invalidation, a store update, an adoption), or
 *  - it implements `EventPayload.SessionScoped`, which `apply` routes into the reducer
 *    (`timelines[scoped.sessionID]?.apply(event)`), or
 *  - it reaches `requests.apply(event)`, the `RequestCenter` for permissions and forms, which
 *    `apply` calls for every frame.
 *
 * The rule is by mechanism, not by a hand-maintained list, so a new payload class is covered the
 * moment it is declared `SessionScoped` and reported as a gap the moment it is not handled at all.
 */
const fanout = new Set()
for (const m of dsSrc.matchAll(/is\s+(?:EventPayload\.)?([A-Z]\w*)/g)) fanout.add(m[1])
// Everything the reducer owns: a payload that implements `EventPayload.SessionScoped` is routed
// into the timeline store by `apply`, whatever file declares it.
{
  const modelSources = kotlinFiles(join(ROOT, 'core/model/src/main/kotlin')).map((f) => readFileSync(f, 'utf8'))
  if (!modelSources.some((t) => /interface\s+SessionScoped/.test(t))) {
    throw new Error('EventPayload.SessionScoped is gone; update this rule')
  }
  for (const t of modelSources) {
    // The parameter list must not run past another `class` declaration, or a helper type declared
    // between two payloads swallows the payload that follows it.
    for (const m of t.matchAll(
      /(?:data\s+class|class)\s+(\w+)\s*\(((?:(?!\bclass\b)[\s\S])*?)\)\s*:\s*EventPayload,\s*EventPayload\.SessionScoped/g,
    )) {
      fanout.add(m[1])
    }
  }
}
// `requests.apply(event)` runs for every frame, so a permission or form payload is handled there.
const requestCenter = kotlinFiles(join(ROOT, 'core/data/src/main/kotlin')).find((f) => f.endsWith('RequestCenter.kt'))
const requestText = requestCenter ? readFileSync(requestCenter, 'utf8') : ''
const inRequestCenter = new Set(
  [...requestText.matchAll(/is\s+(?:EventPayload\.)?([A-Z]\w*)/g)].map((m) => m[1]),
)

const sec8Types = new Set()
for (const m of sec8.matchAll(/`([a-z][a-z0-9]*(?:\.[a-z0-9*]+)+)`/g)) sec8Types.add(m[1])

/**
 * `server.connected` never reaches `ServerDataSet.apply`. The SSE reader consumes it, because the
 * stream is required to open with it and it is the signal the connection manager resyncs on
 * (plan §4.2), so it is handled one layer up. Listed here rather than special-cased in the rule.
 */
const HANDLED_BY_THE_READER = new Set(['ServerConnected'])

const CORPUS = join(ROOT, 'core/testing/src/main/resources/fixtures/event-payloads.jsonl')
/**
 * An event is TESTED when the generated corpus carries a payload of its type, or a test names its
 * payload class. The corpus is one minimal instance of every declared type, generated from the
 * vendored declarations and decoded by `EventPayloadCoverageTest`, so "in the corpus" means "some
 * test decoded it and checked the model did not drop a field".
 */
const corpusText = readFileSync(CORPUS, 'utf8')

const eventGaps = []
const eventRows = []
for (const [type, cls] of bound) {
  const isHandled = fanout.has(cls) || inRequestCenter.has(cls) || HANDLED_BY_THE_READER.has(cls)
  const inCorpus = new RegExp(`"type":\\s*"${type.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')}"`).test(corpusText)
  const namedInTest = new RegExp(`\\b${cls}\\b`).test(testText)
  const tested = inCorpus || namedInTest
  const r = { type, cls, handled: isHandled, tested, inCorpus }
  eventRows.push(r)
  if (!isHandled || !tested) eventGaps.push(r)
}
// The `rpc.<id>.<event>` family: `EventTypes.decodePayload` routes every `rpc.` prefix to
// EventPayload.Rpc, so it is bound by construction and only needs a test that proves it.
{
  const tested = new RegExp('\\bRpc\\b').test(testText)
  const r = { type: 'rpc.*', cls: 'Rpc', handled: true, tested }
  eventRows.push(r)
  if (!tested) eventGaps.push(r)
}

// ------------------------------------------------------------------ report

const implemented = rows.filter((r) => r.implemented).length
const wired = rows.filter((r) => r.wired).length
const tested = rows.filter((r) => r.tested).length
const evHandled = eventRows.filter((r) => r.handled).length
const evTested = eventRows.filter((r) => r.tested).length

const report = {
  api: { total: rows.length, implemented, wired, tested, gaps: apiGaps },
  events: { total: eventRows.length, handled: evHandled, tested: evTested, gaps: eventGaps },
}

if (process.argv.includes('--json')) {
  console.log(JSON.stringify(report, null, 1))
} else {
  console.log(`§7 API coverage:   ${rows.length} rows | declared ${implemented} | wired ${wired} | tested ${tested}`)
  console.log(`§8 Event coverage: ${eventRows.length} types | handled ${evHandled} | tested ${evTested}`)
  if (apiGaps.length) {
    console.log(`\n§7 gaps (${apiGaps.length}):`)
    for (const g of apiGaps) {
      const why = [
        !g.implemented ? 'not declared' : null,
        !g.wired ? 'no production caller' : null,
        !g.tested ? 'no test drives it' : null,
      ].filter(Boolean)
      console.log(`  ${g.phase} ${g.endpoint} ${g.name} -- ${why.join(', ')}  fn=${g.fn ?? '-'}`)
    }
  }
  if (eventGaps.length) {
    console.log(`\n§8 gaps (${eventGaps.length}):`)
    for (const g of eventGaps) console.log(`  ${g.type} (${g.cls}) -- ${!g.handled ? 'unhandled' : ''} ${!g.tested ? 'untested' : ''}`)
  }
  if (!apiGaps.length && !eventGaps.length) console.log('\nNo gaps.')
}

if (process.argv.includes('--strict') && (apiGaps.length || eventGaps.length)) process.exit(1)
