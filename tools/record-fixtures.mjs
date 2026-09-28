#!/usr/bin/env node
/**
 * Records fixtures from a real `opencode serve` into core/testing resources.
 *
 * Captures REST responses plus the live SSE stream while driving scripted turns
 * against the fake provider (tools/fake-provider), so every message type, event type
 * and error in the fixtures is reproducible. Nothing secret is recorded: only
 * response bodies, never credentials.
 *
 * Usage:
 *   eval "$(scripts/dev-server.sh start)"
 *   node tools/record-fixtures.mjs --out core/testing/src/main/resources/fixtures
 *
 * Env (all set by dev-server.sh): OPENCODE_URL, OPENCODE_PASSWORD,
 * OPENCODE_DIRECTORY, OPENCODE_VERSION.
 */

import { mkdirSync, writeFileSync } from "node:fs"
import { join } from "node:path"

const args = Object.fromEntries(
  process.argv
    .slice(2)
    .map((t, i, a) => (t.startsWith("--") ? [t.slice(2), a[i + 1]] : null))
    .filter(Boolean),
)
const OUT = args.out ?? "core/testing/src/main/resources/fixtures"
const URL = process.env.OPENCODE_URL
const PASSWORD = process.env.OPENCODE_PASSWORD
const DIRECTORY = process.env.OPENCODE_DIRECTORY
if (!URL || !PASSWORD || !DIRECTORY) {
  console.error("needs OPENCODE_URL, OPENCODE_PASSWORD, OPENCODE_DIRECTORY (eval \"$(scripts/dev-server.sh start)\")")
  process.exit(2)
}

const AUTH = `Basic ${Buffer.from(`opencode:${PASSWORD}`).toString("base64")}`
const LOCATION = `location[directory]=${encodeURIComponent(DIRECTORY)}`

async function api(method, path, body = undefined, { auth = true, query = "" } = {}) {
  const headers = {}
  if (auth) headers.authorization = AUTH
  if (body !== undefined) headers["content-type"] = "application/json"
  const response = await fetch(`${URL}${path}?${LOCATION}${query}`, {
    method,
    headers,
    body: body === undefined ? undefined : JSON.stringify(body),
  })
  const text = await response.text()
  let json = null
  try {
    json = text ? JSON.parse(text) : null
  } catch {
    json = { _unparsed: text }
  }
  return { status: response.status, json }
}

function save(name, value) {
  writeFileSync(join(OUT, name), `${JSON.stringify(value, null, 2)}\n`)
  console.log(`saved ${name}`)
}

const sleep = (ms) => new Promise((r) => setTimeout(r, ms))

/** Every SSE frame of the run, in arrival order. Filled by streamAll(). */
const allFrames = []
let heartbeatCount = 0

/** Opens the global event stream; resolves never (the recorder exits explicitly). */
async function streamAll() {
  const response = await fetch(`${URL}/api/event`, { headers: { authorization: AUTH } })
  if (!response.ok || !response.body) throw new Error(`SSE status ${response.status}`)
  const reader = response.body.getReader()
  const decoder = new TextDecoder()
  let buffer = ""
  for (;;) {
    const { done, value } = await reader.read()
    if (done) throw new Error("SSE stream ended early")
    buffer += decoder.decode(value, { stream: true })
    let cut
    while ((cut = buffer.search(/\r?\n\r?\n/)) >= 0) {
      const raw = buffer.slice(0, cut)
      buffer = buffer.slice(cut).replace(/^\r?\n\r?\n/, "")
      for (const line of raw.split(/\r?\n/)) {
        if (line.startsWith(":")) {
          heartbeatCount++
          continue
        }
        if (!line.startsWith("data:")) continue
        try {
          allFrames.push(JSON.parse(line.slice(5).trim()))
        } catch {
          // Ignore malformed frames; the contract tests only see persisted frames.
        }
      }
    }
  }
}

function answerForm(detail) {
  const answer = {}
  for (const field of detail?.fields ?? []) {
    const key = field.key ?? field.id ?? field.name
    const options = field.options ?? field.items?.options
    if (options?.length) {
      const first = options[0]
      const value = typeof first === "string" ? first : (first.value ?? first.label)
      answer[key] = field.type === "multiselect" ? [value] : value
    } else if (field.type === "boolean") answer[key] = true
    else if (field.type === "number" || field.type === "integer") answer[key] = 0
    else answer[key] = "fixture answer"
  }
  return answer
}

/**
 * Drive a turn to its `idle` message, answering blocking permissions (once) and forms
 * (first options) on the way. Completion is read from the REST projection: 2.0.18 ends
 * a turn with `session.execution.succeeded`/`failed` events and an `idle` message, but
 * emits no `session.idle` event for these flows. Captured permission/form payloads are
 * returned for fixtures.
 */
async function waitForIdle(sessionID, since, timeoutMs = 180000) {
  const start = Date.now()
  const permissions = []
  const forms = []
  const seenPerms = new Set()
  const seenForms = new Set()
  for (;;) {
    const pendingPerms = await api("GET", `/api/session/${sessionID}/permission`)
    for (const req of pendingPerms.json?.data ?? []) {
      if (!seenPerms.has(req.id)) {
        seenPerms.add(req.id)
        permissions.push(req)
      }
      console.log(`replying once to permission ${req.id} (${req.action} ${req.resources?.join(",")})`)
      await api("POST", `/api/session/${sessionID}/permission/${req.id}/reply`, { decision: "once" })
    }
    const pendingForms = await api("GET", `/api/session/${sessionID}/form`)
    for (const form of pendingForms.json?.data ?? []) {
      const detail = (await api("GET", `/api/session/${sessionID}/form/${form.id}`)).json?.data
      if (detail && !seenForms.has(form.id)) {
        seenForms.add(form.id)
        forms.push(detail)
      }
      const answer = answerForm(detail)
      console.log(`replying to form ${form.id}: ${JSON.stringify(answer)}`)
      const replied = await api("POST", `/api/session/${sessionID}/form/${form.id}/reply`, { answer })
      if (replied.status >= 400) {
        console.log(`form reply rejected (${replied.status}): ${JSON.stringify(replied.json)?.slice(0, 300)}`)
      }
    }
    const messages = await api("GET", `/api/session/${sessionID}/message`, undefined, { query: "&limit=5" })
    const done = (messages.json?.data ?? []).some((m) => m.type === "idle" && m.time?.created >= since)
    if (done) return { permissions, forms }
    if (Date.now() - start > timeoutMs) {
      throw new Error(`session ${sessionID}: no idle message within timeout`)
    }
    await sleep(1500)
  }
}

async function existingSession(sessionID) {
  const got = await api("GET", `/api/session/${sessionID}`)
  return got.json?.data?.id ?? null
}

async function runTurn(name, modelID, prompt) {
  const sessionID = `ses_fixture_${name}`
  let sid = await existingSession(sessionID)
  if (!sid) {
    const created = await api("POST", "/api/session", {
      id: sessionID,
      title: `fixture ${name}`,
      model: { id: modelID, providerID: "fake" },
    })
    sid = created.json?.data?.id ?? (await existingSession(sessionID))
  }
  if (!sid) throw new Error(`scenario ${name}: could not create session`)
  console.log(`scenario ${name}: session ${sid}`)
  const since = Date.now()
  const prompted = await api("POST", `/api/session/${sid}/prompt`, {
    id: `msg_fixture_${name}`,
    text: prompt,
  })
  console.log(`scenario ${name}: prompt status ${prompted.status}`)
  const { permissions, forms } = await waitForIdle(sid, since)
  const messages = await api("GET", `/api/session/${sid}/message`, undefined, { query: "&limit=100" })
  const session = await api("GET", `/api/session/${sid}`)
  save(`messages-${name}.json`, messages.json)
  save(`session-${name}.json`, session.json)
  if (permissions.length) save(`permissions-${name}.json`, permissions)
  if (forms.length) save(`forms-${name}.json`, forms)
  console.log(`scenario ${name}: ${messages.json?.data?.length} messages`)
}

async function main() {
  mkdirSync(OUT, { recursive: true })
  mkdirSync(join(OUT, "errors"), { recursive: true })

  save("info.json", (await api("GET", "/api/info")).json)
  save("models.json", (await api("GET", "/api/model")).json)
  save("projects.json", (await api("GET", "/api/project")).json)
  save("sessions-empty.json", (await api("GET", "/api/session")).json)

  const streaming = streamAll()
  streaming.catch((error) => {
    console.error(`SSE failed: ${error.message}`)
    process.exit(1)
  })
  // The burst of catalog events after server.connected is part of every resync.
  await sleep(3000)

  const scenarios = [
    ["text", "text", "Say hello in a few words."],
    ["reasoning", "reasoning", "Think briefly, then answer."],
    ["shell", "shell", "Run the marker shell command and quote its output."],
    ["edit", "edit", "Apply the scripted edit, then confirm."],
    ["question", "question", "Ask me the scripted question."],
    ["subagent", "subagent", "Consult the scripted subagent, then report."],
    ["error", "error", "Answer despite the scripted provider failure."],
    ["long", "long", "Give the long markdown answer."],
  ]
  for (const [name, model, prompt] of scenarios) {
    await runTurn(name, model, prompt)
  }

  // Message types no turn produces: synthetic, shell, compaction, switch markers.
  const misc = "ses_fixture_misc"
  if (!(await existingSession(misc))) {
    await api("POST", "/api/session", {
      id: misc,
      title: "fixture misc",
      model: { id: "text", providerID: "fake" },
    })
  }
  await api("POST", `/api/session/${misc}/synthetic`, {
    id: "msg_fixture_misc_synthetic",
    text: "A note injected for fixtures.",
    description: "fixture",
  })
  const sinceMisc = Date.now()
  await api("POST", `/api/session/${misc}/shell`, { id: "msg_fixture_misc_shell", command: "echo fixture-shell" })
  await api("POST", `/api/session/${misc}/model`, { model: { id: "reasoning", providerID: "fake" } })
  const compact = await api("POST", `/api/session/${misc}/compact`, { id: "msg_fixture_misc_compact" })
  console.log(`compact status: ${compact.status}`)
  // A viewed session may emit session.idle; record whatever the view triggers.
  const viewed = await api("POST", `/api/session/${misc}/view`)
  console.log(`view status: ${viewed.status}`)
  await waitForIdle(misc, sinceMisc)
  save("messages-misc.json", (await api("GET", `/api/session/${misc}/message`, undefined, { query: "&limit=100" })).json)
  save("session-misc.json", (await api("GET", `/api/session/${misc}`)).json)

  save("sessions.json", (await api("GET", "/api/session")).json)

  // Errors: every `_tag` the contract tests cover.
  save("errors/session-not-found.json", (await api("GET", "/api/session/ses_missing0000000000000000")).json)
  save(
    "errors/message-not-found.json",
    (await api("GET", `/api/session/${misc}/message/msg_missing0000000000000000`)).json,
  )
  const noAuth = await api("GET", "/api/model", undefined, { auth: false })
  save("errors/unauthorized.json", { status: noAuth.status, body: noAuth.json })

  const lines = allFrames.map((f) => JSON.stringify(f)).join("\n")
  writeFileSync(join(OUT, "events.jsonl"), `${lines}\n`)
  console.log(`saved events.jsonl (${allFrames.length} frames, ${heartbeatCount} heartbeats skipped)`)

  const types = {}
  for (const f of allFrames) types[f.type] = (types[f.type] ?? 0) + 1
  save("manifest.json", {
    serverVersion: process.env.OPENCODE_VERSION ?? null,
    recordedAt: new Date().toISOString(),
    scenarios: scenarios.map(([name, model]) => ({ name, model })),
    eventTypes: types,
    note: "Recorded against opencode serve with tools/fake-provider. IDs and timestamps vary per run; tests must not assert exact values.",
  })
  process.exit(0)
}

await main()
