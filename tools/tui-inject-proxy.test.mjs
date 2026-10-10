import assert from 'node:assert/strict'
import { existsSync, mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs'
import http from 'node:http'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'
import test from 'node:test'
import { DEFAULT_EXIT_AFTER, DEFAULT_HOST, DEFAULT_PORT, DEFAULT_TARGET, UsageError, buildFrame, createProxy, parseArgs } from './tui-inject-proxy.mjs'

const ROOT = join(dirname(fileURLToPath(import.meta.url)), '..')
const FIXTURES = join(ROOT, 'core/testing/src/main/resources/fixtures/event-payloads.jsonl')
// Scratch directories live under the repo, never in the system temp directory.
const SCRATCH = join(ROOT, 'tmp')

const DEFAULTS = { id: 'evt_default', created: 1 }
const CONNECTED = 'data: {"id":"evt_connected","type":"server.connected"}\n\n'
// The upstream sends its first frame in two writes, so the proxy has to hold back the half it has.
const CONNECTED_HEAD = CONNECTED.slice(0, 20)
const CONNECTED_TAIL = CONNECTED.slice(20)
const TOAST = 'data: {"id":"evt_file_1","type":"tui.toast.show","created":7,"data":{"message":"Saved","variant":"success"}}\n\n'
const SHORTHAND_SELECT = /^data: \{"id":"evt_inject_\d+_\d+","type":"tui\.session\.select","created":\d+,"data":\{"sessionID":"ses_1"\}\}\n\n$/

const delay = (ms) => new Promise((resolve) => setTimeout(resolve, ms))

async function waitFor(check, ms = 2000) {
  const end = Date.now() + ms
  while (!check()) {
    if (Date.now() > end) assert.fail('timed out waiting for the proxy')
    await delay(10)
  }
}

function request(url, { method = 'GET', headers = {}, body } = {}) {
  return new Promise((resolve, reject) => {
    const req = http.request(url, { method, headers, agent: false }, (res) => {
      const chunks = []
      res.on('data', (chunk) => chunks.push(chunk))
      res.on('error', reject)
      res.on('end', () => resolve({ status: res.statusCode, headers: res.headers, body: Buffer.concat(chunks).toString('utf8') }))
    })
    req.on('error', reject)
    req.end(body)
  })
}

// An open event stream whose text is collected as it arrives. `destroy()` ends it from the client side.
function openStream(url) {
  const stream = { text: '' }
  stream.req = http.get(url, { agent: false, headers: { accept: 'text/event-stream' } }, (res) => {
    res.setEncoding('utf8')
    res.on('error', () => {})
    res.on('data', (chunk) => {
      stream.text += chunk
    })
  })
  stream.req.on('error', () => {})
  stream.destroy = () => stream.req.destroy()
  return stream
}

const listen = (server, host = '127.0.0.1') => new Promise((resolve) => server.listen(0, host, resolve))

// A stub dev server. Its event stream sends the connected frame in two halves, and then stays open.
async function fixture() {
  const streams = []
  const logs = []
  const stub = http.createServer((req, res) => {
    if (req.method === 'GET' && req.url.split('?')[0] === '/api/event') {
      res.writeHead(200, { 'content-type': 'text/event-stream', 'cache-control': 'no-cache' })
      res.write(CONNECTED_HEAD)
      setTimeout(() => {
        if (!res.destroyed) res.write(CONNECTED_TAIL)
      }, 30)
      streams.push(res)
      return
    }
    const chunks = []
    req.on('data', (chunk) => chunks.push(chunk))
    req.on('end', () => {
      res.writeHead(201, { 'content-type': 'application/json', 'x-stub': 'yes' })
      res.end(
        JSON.stringify({
          method: req.method,
          url: req.url,
          authorization: req.headers.authorization ?? null,
          body: Buffer.concat(chunks).toString('utf8'),
        }),
      )
    })
  })
  await listen(stub)
  mkdirSync(SCRATCH, { recursive: true })
  const injectDir = mkdtempSync(join(SCRATCH, 'tui-inject-test-'))
  const proxy = createProxy({
    target: `http://127.0.0.1:${stub.address().port}`,
    injectDir,
    pollMs: 20,
    log: (message) => logs.push(message),
  })
  await listen(proxy.server)
  const base = `http://127.0.0.1:${proxy.server.address().port}`
  return {
    base,
    injectDir,
    logs,
    streams,
    async close() {
      for (const res of streams) res.destroy()
      await proxy.close()
      await new Promise((resolve) => {
        stub.close(() => resolve())
        stub.closeAllConnections()
      })
      rmSync(injectDir, { recursive: true, force: true })
    },
  }
}

const dropEvent = (dir, name, event) => writeFileSync(join(dir, name), JSON.stringify(event))

test('a shorthand gets its id and created and becomes one data line ending in a blank line', () => {
  const frame = buildFrame(
    { type: 'tui.toast.show', data: { message: 'Saved', variant: 'success' } },
    { id: 'evt_t1', created: 42 },
  )
  assert.equal(frame, 'data: {"id":"evt_t1","type":"tui.toast.show","created":42,"data":{"message":"Saved","variant":"success"}}\n\n')
})

test('every TUI line of the event fixture is framed as exactly those bytes', () => {
  const lines = readFileSync(FIXTURES, 'utf8')
    .split('\n')
    .filter((line) => line.startsWith('{"id":') && line.includes('"type":"tui.'))
  assert.deepEqual(
    lines.map((line) => JSON.parse(line).type).sort(),
    ['tui.command.execute', 'tui.prompt.append', 'tui.session.select', 'tui.toast.show'],
  )
  for (const line of lines) {
    assert.equal(buildFrame(JSON.parse(line), DEFAULTS), `data: ${line}\n\n`)
  }
})

test('an event the app would drop is refused, and the message names the field', () => {
  const toast = { type: 'tui.toast.show', data: { message: 'x', variant: 'info' } }
  assert.throws(() => buildFrame({ ...toast, type: 'session.updated' }, DEFAULTS), /type must be one of/)
  assert.throws(() => buildFrame({ ...toast, type: 'constructor' }, DEFAULTS), /type must be one of/)
  assert.throws(() => buildFrame({ type: 'tui.toast.show', data: { message: 'x' } }, DEFAULTS), /data\.variant must be a string/)
  assert.throws(() => buildFrame({ ...toast, data: { ...toast.data, duration: '2' } }, DEFAULTS), /data\.duration must be a number/)
  assert.throws(() => buildFrame({ type: 'tui.session.select', data: 'ses_1' }, DEFAULTS), /data must be an object/)
  assert.throws(() => buildFrame(toast, { created: 1 }), /id is missing/)
  assert.throws(() => buildFrame(toast, { id: 'x' }), /created must be a number/)
  assert.throws(() => buildFrame(['tui.toast.show'], DEFAULTS), /must be a JSON object/)
  assert.doesNotThrow(() => buildFrame({ ...toast, data: { ...toast.data, title: null, duration: 2.5 } }, DEFAULTS))
})

test('the command line has defaults, accepts both spellings, and refuses what it cannot use', () => {
  assert.deepEqual(parseArgs([]), { port: DEFAULT_PORT, host: DEFAULT_HOST, target: DEFAULT_TARGET, exitAfter: DEFAULT_EXIT_AFTER })
  assert.deepEqual(parseArgs(['--port', '4300', '--target=http://127.0.0.1:4199/', '--exit-after', '0', '--host=127.0.0.1']), {
    port: 4300,
    host: '127.0.0.1',
    target: 'http://127.0.0.1:4199',
    exitAfter: 0,
  })
  assert.throws(() => parseArgs(['--port', '70000']), UsageError)
  assert.throws(() => parseArgs(['--port', 'abc']), UsageError)
  assert.throws(() => parseArgs(['--target', 'https://127.0.0.1:4196']), UsageError)
  assert.throws(() => parseArgs(['--target', 'http://127.0.0.1:4196/api']), UsageError)
  assert.throws(() => parseArgs(['--exit-after', '-5']), UsageError)
  assert.throws(() => parseArgs(['--port']), UsageError)
  assert.throws(() => parseArgs(['--bogus']), UsageError)
})

test('a request reaches the dev server with its method, path, query, Authorization and body, and its answer comes back unchanged', async () => {
  const f = await fixture()
  try {
    const res = await request(`${f.base}/api/session/ses_1?limit=2`, {
      method: 'POST',
      headers: { authorization: 'Basic dGVzdDp0ZXN0', 'content-type': 'application/json' },
      body: '{"text":"hi"}',
    })
    assert.equal(res.status, 201)
    assert.equal(res.headers['x-stub'], 'yes')
    assert.deepEqual(JSON.parse(res.body), {
      method: 'POST',
      url: '/api/session/ses_1?limit=2',
      authorization: 'Basic dGVzdDp0ZXN0',
      body: '{"text":"hi"}',
    })
  } finally {
    await f.close()
  }
})

test('a WebSocket upgrade is answered 501 instead of being left open', async () => {
  const f = await fixture()
  try {
    const res = await request(`${f.base}/api/pty/x/connect`, {
      headers: { connection: 'Upgrade', upgrade: 'websocket', 'sec-websocket-version': '13', 'sec-websocket-key': 'dGhlIHNhbXBsZSBub25jZQ==' },
    })
    assert.equal(res.status, 501)
  } finally {
    await f.close()
  }
})

test('the stream is relayed as it was sent, and a dropped file reaches every open stream after server.connected, in name order', async () => {
  const f = await fixture()
  try {
    const first = openStream(`${f.base}/api/event`)
    const second = openStream(`${f.base}/api/event`)
    await waitFor(() => first.text === CONNECTED && second.text === CONNECTED)

    dropEvent(f.injectDir, '001-toast.json', { id: 'evt_file_1', type: 'tui.toast.show', created: 7, data: { message: 'Saved', variant: 'success' } })
    dropEvent(f.injectDir, '002-select.json', { type: 'tui.session.select', data: { sessionID: 'ses_1' } })
    await waitFor(() => first.text.length > CONNECTED.length + TOAST.length && second.text.length > CONNECTED.length + TOAST.length)

    assert.equal(first.text.slice(0, CONNECTED.length + TOAST.length), CONNECTED + TOAST)
    assert.equal(second.text.slice(0, CONNECTED.length + TOAST.length), CONNECTED + TOAST)
    assert.match(first.text.slice(CONNECTED.length + TOAST.length), SHORTHAND_SELECT)
    assert.match(second.text.slice(CONNECTED.length + TOAST.length), SHORTHAND_SELECT)
    assert.equal(existsSync(join(f.injectDir, '001-toast.json')), false)
    assert.equal(existsSync(join(f.injectDir, '002-select.json')), false)
    first.destroy()
    second.destroy()
  } finally {
    await f.close()
  }
})

test('a file dropped before any stream is open waits for one, then is sent after server.connected', async () => {
  const f = await fixture()
  try {
    dropEvent(f.injectDir, '001-prompt.json', { id: 'evt_wait', type: 'tui.prompt.append', created: 3, data: { text: 'hello' } })
    await delay(100)
    assert.equal(existsSync(join(f.injectDir, '001-prompt.json')), true)
    assert.ok(f.logs.some((message) => message.includes('waiting')))

    const stream = openStream(`${f.base}/api/event`)
    await waitFor(() => stream.text.length > CONNECTED.length)
    assert.equal(stream.text, CONNECTED + 'data: {"id":"evt_wait","type":"tui.prompt.append","created":3,"data":{"text":"hello"}}\n\n')
    assert.equal(existsSync(join(f.injectDir, '001-prompt.json')), false)
    stream.destroy()
  } finally {
    await f.close()
  }
})

test('an injected frame never lands inside an upstream frame that is still arriving', async () => {
  const f = await fixture()
  try {
    const stream = openStream(`${f.base}/api/event`)
    await waitFor(() => stream.text === CONNECTED)

    // The upstream has started a frame and not finished it when the file is dropped.
    const upstream = f.streams[0]
    upstream.write('data: {"id":"evt_up_1",')
    dropEvent(f.injectDir, '001-toast.json', { id: 'evt_file_1', type: 'tui.toast.show', created: 7, data: { message: 'Saved', variant: 'success' } })
    await waitFor(() => stream.text === CONNECTED + TOAST)

    upstream.write('"type":"x"}\n\n')
    await waitFor(() => stream.text === CONNECTED + TOAST + 'data: {"id":"evt_up_1","type":"x"}\n\n')
    stream.destroy()
  } finally {
    await f.close()
  }
})

test('a file that is incomplete or not a TUI event is left in place and reported, and sent once it is whole', async () => {
  const f = await fixture()
  try {
    const stream = openStream(`${f.base}/api/event`)
    await waitFor(() => stream.text === CONNECTED)

    const partial = join(f.injectDir, '001-toast.json')
    writeFileSync(partial, '{"id":"evt_file_1","type":"tui.toast.show","created":7,"data":{"message":"Sa')
    await delay(100)
    assert.equal(stream.text, CONNECTED)
    assert.equal(existsSync(partial), true)
    assert.equal(f.logs.filter((message) => message.includes('001-toast.json is left in place')).length, 1)

    writeFileSync(partial, JSON.stringify({ id: 'evt_file_1', type: 'tui.toast.show', created: 7, data: { message: 'Saved', variant: 'success' } }))
    await waitFor(() => stream.text === CONNECTED + TOAST)
    assert.equal(existsSync(partial), false)

    dropEvent(f.injectDir, '002-bad.json', { type: 'session.updated', data: {} })
    await delay(100)
    assert.equal(existsSync(join(f.injectDir, '002-bad.json')), true)
    assert.ok(f.logs.some((message) => message.includes('type must be one of')))
    assert.equal(stream.text, CONNECTED + TOAST)
    stream.destroy()
  } finally {
    await f.close()
  }
})
