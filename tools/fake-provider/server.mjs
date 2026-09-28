#!/usr/bin/env node
/**
 * A scripted, OpenAI-compatible provider.
 *
 * `opencode` talks to it like any other provider, so a whole app run is deterministic: the
 * server's provider calls, tool calls, permission requests, questions, diffs and errors all
 * come from the script in `scenarios.mjs`. No keys, no network, no quota.
 *
 * It implements just enough of the OpenAI chat-completions surface:
 *   GET  /v1/models            the scripted models
 *   POST /v1/chat/completions  a streamed (or buffered) completion
 *   GET  /health               readiness
 *   GET  /__requests           every request received, for harness debugging
 *   POST /__reset              forget the recorded requests
 *
 * Usage: node server.mjs [--port 4097] [--host 127.0.0.1]
 */

import { createServer } from "node:http"
import { MODEL_IDS, SCENARIOS, scenarioFor, stepIndex } from "./scenarios.mjs"

const args = parseArgs(process.argv.slice(2))
const port = Number(args.port ?? process.env.FAKE_PROVIDER_PORT ?? 4097)
const host = args.host ?? process.env.FAKE_PROVIDER_HOST ?? "127.0.0.1"
/** Delay between streamed chunks, so the client sees real incremental frames. */
const chunkDelayMs = Number(args["chunk-delay"] ?? process.env.FAKE_PROVIDER_CHUNK_DELAY_MS ?? 5)

/** @type {{ method: string, path: string, body: unknown }[]} */
const requests = []
let completions = 0

const server = createServer((request, response) => {
  const url = new URL(request.url ?? "/", `http://${request.headers.host ?? "localhost"}`)
  if (request.method === "GET" && url.pathname === "/health") {
    return json(response, 200, { status: "ok", completions })
  }
  if (request.method === "GET" && url.pathname === "/__requests") {
    return json(response, 200, { requests })
  }
  if (request.method === "POST" && url.pathname === "/__reset") {
    requests.length = 0
    completions = 0
    return json(response, 200, { status: "ok" })
  }
  if (request.method === "GET" && (url.pathname === "/v1/models" || url.pathname === "/models")) {
    return json(response, 200, {
      object: "list",
      data: MODEL_IDS.map((id) => ({
        id,
        object: "model",
        created: 0,
        owned_by: "opencode-android-fake-provider",
      })),
    })
  }
  if (request.method === "POST" && url.pathname.endsWith("/chat/completions")) {
    return readJson(request).then((body) => handleCompletion(response, body))
  }
  json(response, 404, { error: { message: `fake-provider: no route for ${request.method} ${url.pathname}` } })
})

async function handleCompletion(response, body) {
  requests.push({ method: "POST", path: "/v1/chat/completions", body })
  const scenarioId = scenarioFor(body?.model)
  const scenario = SCENARIOS[scenarioId]
  const index = Math.min(stepIndex(body?.messages), scenario.steps.length - 1)
  const step = scenario.steps[index]
  const id = `chatcmpl-fake-${(completions += 1)}`
  const created = 0

  if (step.error) {
    log(`error ${scenarioId}#${index} ${step.error.status} ${step.error.message}`)
    return json(response, step.error.status ?? 500, {
      error: { message: step.error.message, type: step.error.type ?? "server_error", code: null },
    })
  }

  const stream = body?.stream !== false
  if (!stream) {
    log(`completion ${scenarioId}#${index} (buffered)`)
    const message = bufferedMessage(step, index)
    return json(response, 200, {
      id,
      object: "chat.completion",
      created,
      model: body?.model,
      choices: [{ index: 0, message, finish_reason: finishReason(step) }],
      usage: usage(),
    })
  }

  log(`completion ${scenarioId}#${index} (streamed)`)
  response.writeHead(200, {
    "content-type": "text/event-stream; charset=utf-8",
    "cache-control": "no-cache, no-transform",
    connection: "keep-alive",
  })
  const send = (delta, finish = null) => {
    response.write(
      `data: ${JSON.stringify({
        id,
        object: "chat.completion.chunk",
        created,
        model: body?.model,
        choices: [{ index: 0, delta, finish_reason: finish }],
      })}\n\n`,
    )
  }
  send({ role: "assistant", content: "" })
  for (const chunk of step.reasoning ?? []) {
    await delay()
    send({ reasoning_content: chunk })
  }
  for (const chunk of step.text ?? []) {
    await delay()
    send({ content: chunk })
  }
  if (step.tool) {
    const argsJson = JSON.stringify(step.tool.args)
    // The reference implementations stream tool arguments in pieces; the halves below keep that
    // shape, so a client that assembles arguments is exercised.
    const cut = Math.max(1, Math.floor(argsJson.length / 2))
    await delay()
    send({
      tool_calls: [
        { index: 0, id: step.tool.id, type: "function", function: { name: step.tool.name, arguments: "" } },
      ],
    })
    await delay()
    send({ tool_calls: [{ index: 0, function: { arguments: argsJson.slice(0, cut) } }] })
    await delay()
    send({ tool_calls: [{ index: 0, function: { arguments: argsJson.slice(cut) } }] })
  }
  await delay()
  send({}, finishReason(step))
  response.write("data: [DONE]\n\n")
  response.end()
}

function finishReason(step) {
  if (step.finish) return step.finish
  return step.tool ? "tool-calls" : "stop"
}

function bufferedMessage(step, index) {
  const message = { role: "assistant", content: (step.text ?? []).join("") || null }
  if (step.reasoning) message.reasoning_content = step.reasoning.join("")
  if (step.tool) {
    message.tool_calls = [
      {
        id: step.tool.id,
        type: "function",
        index: 0,
        function: { name: step.tool.name, arguments: JSON.stringify(step.tool.args) },
      },
    ]
  }
  void index
  return message
}

function usage() {
  return { prompt_tokens: 11, completion_tokens: 7, total_tokens: 18 }
}

function json(response, status, payload) {
  const body = JSON.stringify(payload)
  response.writeHead(status, { "content-type": "application/json", "content-length": Buffer.byteLength(body) })
  response.end(body)
}

function readJson(request) {
  return new Promise((resolve, reject) => {
    const parts = []
    request.on("data", (part) => parts.push(part))
    request.on("end", () => {
      const raw = Buffer.concat(parts).toString("utf8")
      if (!raw) return resolve({})
      try {
        resolve(JSON.parse(raw))
      } catch (error) {
        reject(error)
      }
    })
    request.on("error", reject)
  })
}

function delay() {
  return chunkDelayMs > 0 ? new Promise((resolve) => setTimeout(resolve, chunkDelayMs)) : Promise.resolve()
}

function log(message) {
  if (process.env.FAKE_PROVIDER_QUIET === "1") return
  process.stdout.write(`fake-provider: ${message}\n`)
}

function parseArgs(argv) {
  const parsed = {}
  for (let i = 0; i < argv.length; i += 1) {
    const token = argv[i]
    if (!token.startsWith("--")) continue
    const name = token.slice(2)
    const next = argv[i + 1]
    if (next === undefined || next.startsWith("--")) parsed[name] = true
    else {
      parsed[name] = next
      i += 1
    }
  }
  return parsed
}

server.listen(port, host, () => {
  log(`listening on http://${host}:${port}/v1 (scenarios: ${MODEL_IDS.join(", ")})`)
})

for (const signal of ["SIGINT", "SIGTERM"]) {
  process.on(signal, () => server.close(() => process.exit(0)))
}
