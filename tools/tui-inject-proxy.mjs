#!/usr/bin/env node
/**
 * A dev-only reverse proxy that puts TUI events into the app's event stream, so the TUI rows of
 * docs/MANUAL_TEST_MATRIX.md (I1, I3, I5 to I7) can be exercised without a TUI. The vendored OpenAPI has no
 * route that publishes a `tui.*` event, so the dev server cannot send one on its own.
 *
 * Start it (it runs until --exit-after, default 1800 seconds, or Ctrl-C):
 *   node tools/tui-inject-proxy.mjs                       # 0.0.0.0:4296 -> http://127.0.0.1:4196
 *   node tools/tui-inject-proxy.mjs --port 4300 --target http://127.0.0.1:4196 --exit-after 600
 *   node tools/tui-inject-proxy.mjs --host 127.0.0.1      # loopback only; the emulator still reaches it
 *
 * Point the app at the proxy (for the emulator, http://10.0.2.2:4296, where 10.0.2.2 is the host's loopback)
 * and log in with the same credentials as for the dev server. The proxy neither reads nor changes them: every
 * request goes upstream with its method, path, query, headers (Authorization included) and body unchanged, and
 * its response comes back unchanged, streamed as it arrives.
 *
 * Send a TUI event: write one JSON file into tmp/tui-inject/ (created at start, under the repo root). Within a
 * quarter second the proxy writes one SSE frame into every open `GET /api/event` stream, then deletes the file:
 *   {"type":"tui.toast.show","data":{"message":"Saved","variant":"success"}}
 *   {"type":"tui.session.select","data":{"sessionID":"ses_..."}}
 *   {"type":"tui.prompt.append","data":{"text":"hello"}}
 *   {"type":"tui.command.execute","data":{"command":"session.list"}}
 * A shorthand gets `id` and `created` (epoch milliseconds, now) and nothing else. A full envelope, shaped as the
 * lines of core/testing/src/main/resources/fixtures/event-payloads.jsonl, is sent with its own fields. Files go out
 * in name order, so prefix them (001-toast.json, 002-select.json) when the order matters.
 *
 * How a frame is delivered:
 * - The frame is `data: <envelope on one line>` and a blank line, the framing EventStreamClient parses.
 * - It goes in only at a frame boundary of the upstream stream, so no upstream frame is split.
 * - It goes in only after the upstream's first `data:` frame (`server.connected`). The app fails a stream whose
 *   first event is anything else, so an earlier frame would cost a reconnect. A file dropped before that waits.
 * - A file that is not complete JSON, or not a valid TUI event, stays where it is and is reported once. Fix or
 *   delete it. A file is deleted only after it has been written to at least one stream.
 *
 * Limits:
 * - Only the four `tui.*` types above are accepted, each checked against the fields the app's model reads. The
 *   app drops a frame it cannot decode without telling anyone, so a bad event is refused here instead.
 * - Only `GET /api/event` gets injected frames. A WebSocket upgrade (the pty connect routes) is answered with 501
 *   and not forwarded.
 * - Nothing is authenticated here. With the default --host 0.0.0.0 the dev server is reachable from the network,
 *   for anyone who has its credentials. Use --host 127.0.0.1 unless a device on the network has to reach it.
 * - `tmp/` is hidden from git on this checkout by .git/info/exclude, not by .gitignore, so a file left behind
 *   here is invisible, but a fresh clone would show it as untracked.
 * - Dev only. It is not a gate, and no audit tool or AGENTS.md entry depends on it.
 *
 * Tests: node tools/tui-inject-proxy.test.mjs
 */
import { mkdirSync, readFileSync } from 'node:fs'
import { readFile, readdir, unlink } from 'node:fs/promises'
import http from 'node:http'
import { dirname, join, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const ROOT = join(dirname(fileURLToPath(import.meta.url)), '..')
export const DEFAULT_PORT = 4296
export const DEFAULT_HOST = '0.0.0.0'
export const DEFAULT_TARGET = 'http://127.0.0.1:4196'
export const DEFAULT_EXIT_AFTER = 1800
export const DEFAULT_INJECT_DIR = join(ROOT, 'tmp', 'tui-inject')

const EVENT_PATH = '/api/event'
const POLL_MS = 250
const USAGE =
  'usage: node tools/tui-inject-proxy.mjs [--port 4296] [--host 0.0.0.0] [--target http://127.0.0.1:4196] [--exit-after 1800]'

/**
 * The four TUI events, and the fields the app's model (core/model EventPayloadsMisc.kt) reads from each.
 * A `?` marks an optional field, which may be absent or null.
 */
const TUI_FIELDS = {
  'tui.toast.show': { message: 'string', variant: 'string', title: 'string?', duration: 'number?' },
  'tui.session.select': { sessionID: 'string' },
  'tui.prompt.append': { text: 'string' },
  'tui.command.execute': { command: 'string' },
}

const isObject = (value) => typeof value === 'object' && value !== null && !Array.isArray(value)

/**
 * The SSE frame for one TUI event: `data:` with the envelope on one line, then the blank line that ends a frame.
 *
 * `input` is a full envelope or a `{type, data}` shorthand. `defaults` supplies the `id` and `created` that the
 * input does not carry, and nothing else is filled in. The clock is not read, so the same arguments always give
 * the same frame. Throws an Error that names the first thing wrong with the event.
 */
export function buildFrame(input, defaults = {}) {
  if (!isObject(input)) throw new Error('an event must be a JSON object')
  if (!Object.hasOwn(TUI_FIELDS, input.type)) {
    throw new Error(`type must be one of ${Object.keys(TUI_FIELDS).join(', ')}, not ${JSON.stringify(input.type)}`)
  }
  const id = input.id ?? defaults.id
  if (typeof id !== 'string' || id === '') throw new Error('id is missing and no default was given')
  const created = input.created ?? defaults.created
  if (!Number.isFinite(created)) throw new Error('created must be a number of epoch milliseconds, or come from the defaults')
  if (!isObject(input.data)) throw new Error('data must be an object')
  for (const [field, kind] of Object.entries(TUI_FIELDS[input.type])) {
    const optional = kind.endsWith('?')
    const expected = optional ? kind.slice(0, -1) : kind
    const value = input.data[field]
    if (optional && (value === undefined || value === null)) continue
    if (typeof value !== expected) throw new Error(`data.${field} must be a ${expected}`)
  }
  const { id: _id, type, created: _created, data, ...rest } = input
  return `data: ${JSON.stringify({ id, type, created, ...rest, data })}\n\n`
}

/** A command line the tool does not accept. */
export class UsageError extends Error {}

const wholeNumber = (value, name, min, max) => {
  const number = Number(value)
  if (!/^\d+$/.test(value) || number < min || number > max) {
    throw new UsageError(`${name} must be a whole number from ${min} to ${max}`)
  }
  return number
}

const SETTINGS = {
  '--port': (options, value) => {
    options.port = wholeNumber(value, '--port', 1, 65535)
  },
  '--host': (options, value) => {
    if (value.trim() === '') throw new UsageError('--host must not be empty')
    options.host = value
  },
  '--target': (options, value) => {
    let url
    try {
      url = new URL(value)
    } catch {
      throw new UsageError(`--target is not a URL: ${value}`)
    }
    if (url.protocol !== 'http:' || (url.pathname !== '/' && url.pathname !== '') || url.search || url.hash) {
      throw new UsageError('--target must be an http:// origin with no path, such as http://127.0.0.1:4196')
    }
    options.target = url.origin
  },
  '--deny': (options, value) => {
    const match = /^([A-Z]+) (\/\S*)=(\d{3})$/.exec(value)
    if (!match) throw new UsageError('--deny takes "METHOD /path=status", for example "POST /api/pair=404"')
    options.deny.push({ method: match[1], path: match[2], status: Number(match[3]) })
  },
  '--stub': (options, value) => {
    const match = /^([A-Z]+) (\/\S*)=(.+)$/.exec(value)
    if (!match) throw new UsageError('--stub takes "METHOD /path=file.json", for example "GET /api/plugin=tmp/plugins.json"')
    options.stub.push({ method: match[1], path: match[2], file: match[3] })
  },
  '--exit-after': (options, value) => {
    const seconds = Number(value)
    if (value.trim() === '' || !Number.isFinite(seconds) || seconds < 0 || seconds > 2_000_000) {
      throw new UsageError('--exit-after must be a number of seconds from 0 to 2000000, 0 for no limit')
    }
    options.exitAfter = seconds
  },
}

/** Reads the command line, given without `node` and the script path. Throws UsageError on anything it does not accept. */
export function parseArgs(argv) {
  const options = { port: DEFAULT_PORT, host: DEFAULT_HOST, target: DEFAULT_TARGET, exitAfter: DEFAULT_EXIT_AFTER, deny: [], stub: [] }
  for (let index = 0; index < argv.length; index += 1) {
    const arg = argv[index]
    const equals = arg.indexOf('=')
    const name = equals === -1 ? arg : arg.slice(0, equals)
    let value = equals === -1 ? undefined : arg.slice(equals + 1)
    if (!Object.hasOwn(SETTINGS, name)) throw new UsageError(`unknown argument ${name}`)
    if (value === undefined) {
      value = argv[index + 1]
      index += 1
      if (value === undefined) throw new UsageError(`${name} needs a value`)
    }
    SETTINGS[name](options, value)
  }
  return options
}

const BLANK_LINE = /\r?\n\r?\n/g
const DATA_LINE = /^data:/m

/** The byte offset just past the last blank line in `bytes`, or 0 while no frame is complete. */
function endOfCompleteFrames(bytes) {
  // latin1 maps one character to one byte, so an index into the string is an index into the bytes.
  let end = 0
  for (const match of bytes.toString('latin1').matchAll(BLANK_LINE)) end = match.index + match[0].length
  return end
}

const hasDataLine = (bytes) => DATA_LINE.test(bytes.toString('latin1'))

const logLine = (message) => console.log(`[tui-inject] ${message}`)

/**
 * The proxy, without a listening socket: the caller listens on `server`. Polling of `injectDir` starts at once,
 * and `close()` stops it, ends every event stream and closes the server.
 */
export function createProxy({ target = DEFAULT_TARGET, injectDir = DEFAULT_INJECT_DIR, pollMs = POLL_MS, log = logLine, deny = [], stub = [] } = {}) {
  const upstream = new URL(target)
  const upstreamHost = upstream.hostname.replace(/^\[(.*)\]$/, '$1')
  mkdirSync(injectDir, { recursive: true })

  // One entry per open event stream. `ready` turns on after the upstream's first data frame has been forwarded.
  const streams = new Set()
  // The last message logged under each key, so a file that stays bad is reported once.
  const notes = new Map()
  let sequence = 0
  let busy = false
  let closed = false

  const server = http.createServer(forward)
  server.on('upgrade', (req, socket) => {
    socket.on('error', () => {})
    log(`${req.method} ${req.url} asked for a WebSocket upgrade, which is not forwarded`)
    socket.end('HTTP/1.1 501 Not Implemented\r\nConnection: close\r\nContent-Length: 0\r\n\r\n')
  })

  function forward(req, res) {
    // A route answered with a fixed status without asking the server, so a client sees a server that lacks it.
    const denied = deny.find((rule) => rule.method === req.method && rule.path === req.url.split('?')[0])
    const stubbed = stub.find((rule) => rule.method === req.method && rule.path === req.url.split('?')[0])
    if (stubbed) {
      // Read at each request, so the file can be edited while the proxy runs.
      req.resume()
      res.writeHead(200, { 'content-type': 'application/json' })
      res.end(readFileSync(stubbed.file))
      return
    }
    if (denied) {
      req.resume()
      res.writeHead(denied.status, { 'content-type': 'application/json' })
      res.end('{}')
      return
    }
    const eventStream = req.method === 'GET' && req.url.split('?')[0] === EVENT_PATH
    const outbound = http.request(
      {
        hostname: upstreamHost,
        port: upstream.port,
        method: req.method,
        path: req.url,
        headers: req.headers,
        agent: false,
      },
      (inbound) => {
        res.writeHead(inbound.statusCode, inbound.statusMessage, inbound.headers)
        res.flushHeaders()
        // A response the upstream cut off is ended by destroying the client's side too, so the app sees the
        // stream close and reconnects instead of waiting on a body that will never finish.
        inbound.on('error', () => res.destroy())
        inbound.on('close', () => {
          if (!inbound.complete) res.destroy()
        })
        if (eventStream) relayEvents(inbound, res)
        else inbound.pipe(res)
      },
    )

    outbound.on('error', (error) => {
      if (res.destroyed || res.writableEnded) return
      const reason = error.code ?? error.message
      log(`${req.method} ${req.url} failed, ${upstream.origin} did not answer (${reason})`)
      if (res.headersSent) {
        res.destroy()
      } else {
        res.writeHead(502, { 'content-type': 'text/plain; charset=utf-8' })
        res.end(`tui-inject-proxy: ${upstream.origin} did not answer (${reason})\n`)
      }
    })
    req.on('error', () => outbound.destroy())
    res.on('close', () => outbound.destroy())
    req.pipe(outbound)
  }

  /** Copies the upstream stream to the client one complete frame at a time, so injected frames fall between frames. */
  function relayEvents(inbound, res) {
    const stream = { res, ready: false }
    streams.add(stream)
    res.on('close', () => streams.delete(stream))

    let pending = Buffer.alloc(0)
    inbound.on('data', (chunk) => {
      if (res.destroyed) return
      pending = Buffer.concat([pending, chunk])
      const end = endOfCompleteFrames(pending)
      if (end === 0) return
      const frames = pending.subarray(0, end)
      pending = pending.subarray(end)
      if (!stream.ready && hasDataLine(frames)) stream.ready = true
      res.write(frames)
    })
    inbound.on('end', () => {
      if (res.destroyed) return
      if (pending.length > 0) res.write(pending)
      res.end()
    })
  }

  const isLive = (stream) => stream.ready && !stream.res.writableEnded && !stream.res.destroyed
  const readyCount = () => [...streams].filter(isLive).length

  function note(key, message) {
    if (notes.get(key) === message) return
    notes.set(key, message)
    log(message)
  }

  function broadcast(frame) {
    let sent = 0
    for (const stream of streams) {
      if (!isLive(stream)) continue
      stream.res.write(frame)
      sent += 1
    }
    return sent
  }

  /** Sends one file's event to every ready stream, then deletes the file. Returns nothing; a bad file is noted and kept. */
  async function deliver(name) {
    const path = join(injectDir, name)
    let text
    try {
      text = await readFile(path, 'utf8')
    } catch (error) {
      if (error.code === 'ENOENT') return
      throw error
    }

    let input
    let frame
    try {
      input = JSON.parse(text)
      const now = Date.now()
      sequence += 1
      frame = buildFrame(input, { id: `evt_inject_${now}_${sequence}`, created: now })
    } catch (error) {
      note(`file:${name}`, `${name} is left in place, not sent: ${error.message}`)
      return
    }

    const sent = broadcast(frame)
    if (sent === 0) return
    await unlink(path).catch((error) => {
      if (error.code !== 'ENOENT') throw error
    })
    log(`sent ${input.type} from ${name} to ${sent} stream(s)`)
  }

  async function scan() {
    if (busy || closed) return
    busy = true
    try {
      const names = (await readdir(injectDir)).filter((name) => name.endsWith('.json') && !name.startsWith('.')).sort()
      for (const key of [...notes.keys()]) {
        if (key.startsWith('file:') && !names.includes(key.slice('file:'.length))) notes.delete(key)
      }
      if (names.length === 0) return
      if (readyCount() === 0) {
        note('waiting', 'event files are waiting until the app has an event stream open')
        return
      }
      notes.delete('waiting')
      for (const name of names) {
        if (closed || readyCount() === 0) break
        await deliver(name)
      }
    } catch (error) {
      note('scan', `could not read ${injectDir}: ${error.message}`)
    } finally {
      busy = false
    }
  }

  const timer = setInterval(() => {
    scan()
  }, pollMs)

  async function close() {
    if (closed) return
    closed = true
    clearInterval(timer)
    for (const stream of streams) {
      if (!stream.res.writableEnded) stream.res.end()
    }
    await new Promise((resolveClose) => {
      server.close(() => resolveClose())
      server.closeAllConnections()
    })
  }

  return { server, close, injectDir }
}

async function main(argv) {
  let options
  try {
    options = parseArgs(argv)
  } catch (error) {
    if (!(error instanceof UsageError)) throw error
    console.error(`tui-inject-proxy: ${error.message}`)
    console.error(USAGE)
    process.exitCode = 2
    return
  }

  const proxy = createProxy({ target: options.target, deny: options.deny, stub: options.stub })
  let limit = null
  let stopping = false
  const stop = async (reason, code = 0) => {
    if (stopping) return
    stopping = true
    clearTimeout(limit)
    logLine(`stopping: ${reason}`)
    await proxy.close()
    process.exit(code)
  }

  proxy.server.on('error', (error) => {
    logLine(`cannot listen on ${options.host}:${options.port}: ${error.message}`)
    stop('listen failed', 1)
  })
  proxy.server.listen(options.port, options.host, () => {
    logLine(`listening on http://${options.host}:${options.port}, forwarding to ${options.target}`)
    logLine(`the emulator reaches it at http://10.0.2.2:${options.port}`)
    logLine(`drop JSON events into ${proxy.injectDir}`)
    logLine(options.exitAfter > 0 ? `exits after ${options.exitAfter} seconds` : 'runs until interrupted')
  })
  if (options.exitAfter > 0) {
    limit = setTimeout(() => stop(`--exit-after ${options.exitAfter}s reached`), options.exitAfter * 1000)
  }
  process.on('SIGINT', () => stop('interrupted'))
  process.on('SIGTERM', () => stop('terminated'))
}

if (process.argv[1] !== undefined && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  main(process.argv.slice(2))
}
