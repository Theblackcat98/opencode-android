#!/usr/bin/env node
/**
 * Guards the vendored OpenCode API assets against silent upstream drift.
 *
 * The OpenAPI spec leaves event payloads opaque (plan section 2, finding 14), so the
 * event type list is extracted from the generated TypeScript types of `@opencode/client`,
 * while the operation list comes from the OpenAPI spec itself. This tool does both:
 *
 *   node tools/check-api-drift.mjs extract --client <version|dir> --out <events.json>
 *       Regenerate api/opencode-2.0.x/events.json from a client release.
 *
 *   node tools/check-api-drift.mjs check --client <version|dir> --openapi <path|url>
 *       [--allowlist <path>]
 *       Exit 0 when the vendored assets cover the newer release, 1 on any unhandled
 *       event/operation addition or removal. Additions that are known but not yet
 *       handled belong in the allowlist file (api/opencode-2.0.x/drift-allowlist.json)
 *       with a reason; everything else fails the check (and CI).
 *
 * `--client 2.0.19` packs that npm release into a temp dir (needs npm + network).
 * `--client <dir>` uses an extracted/installed package as-is (hermetic; used by tests).
 * `--openapi` accepts a file path or an http(s) URL. A live server exposes its own
 * spec at /openapi.json, which is the most faithful newer-release source:
 * check --client 2.0.19 --openapi http://127.0.0.1:4096/openapi.json
 */

import { execFileSync } from "node:child_process"
import { existsSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from "node:fs"
import { tmpdir } from "node:os"
import { join, resolve } from "node:path"

const VENDORED_DIR =
  process.env.CHECK_API_DRIFT_VENDORED ?? resolve(new URL(".", import.meta.url).pathname, "../api/opencode-2.0.x")
const DEFAULT_EVENTS = join(VENDORED_DIR, "events.json")
const DEFAULT_OPENAPI = join(VENDORED_DIR, "openapi.json")
const DEFAULT_ALLOWLIST = join(VENDORED_DIR, "drift-allowlist.json")

function usage() {
  console.error(
    "usage:\n" +
      "  check-api-drift.mjs extract --client <version|dir> --out <events.json>\n" +
      "  check-api-drift.mjs check --client <version|dir> --openapi <path|url> [--allowlist <path>]",
  )
}

function arg(name) {
  const i = process.argv.indexOf(name)
  if (i < 0 || i + 1 >= process.argv.length) {
    console.error(`missing ${name}`)
    usage()
    process.exit(2)
  }
  return process.argv[i + 1]
}

/** Locate dist/\*\*\/generated/types.d.ts under an extracted @opencode/client tree. */
export function findTypesFile(clientDir) {
  const candidates = [
    join(clientDir, "package/dist/promise/generated/types.d.ts"),
    join(clientDir, "dist/promise/generated/types.d.ts"),
  ]
  for (const c of candidates) if (existsSync(c)) return c
  throw new Error(`no generated types.d.ts under ${clientDir}`)
}

/** Read the balanced `{...};` object literal starting at the opening brace index. */
function balancedObject(src, openIndex) {
  let depth = 0
  let inString = null
  for (let i = openIndex; i < src.length; i++) {
    const ch = src[i]
    if (inString) {
      if (ch === "\\") i++
      else if (ch === inString) inString = null
      continue
    }
    if (ch === '"' || ch === "'" || ch === "`") inString = ch
    else if (ch === "{") depth++
    else if (ch === "}") {
      depth--
      if (depth === 0) return src.slice(openIndex, i + 1)
    }
  }
  throw new Error("unbalanced braces in type declaration")
}

/**
 * Parse the generated types into [{tsType, type, rpc, declaration}].
 * `type` is the event's literal `type:` field; the rpc family reports as "rpc.*".
 */
export function parseEventTypes(source) {
  const union = source.match(/export type V2Event = ([^;]+);/)
  if (!union) throw new Error("V2Event union not found in generated types")
  const members = union[1].split("|").map((s) => s.trim()).filter(Boolean)
  return members.map((tsType) => {
    const decl = source.match(new RegExp(`export type ${tsType} = `))
    if (!decl) throw new Error(`declaration of ${tsType} not found`)
    const open = source.indexOf("{", decl.index)
    if (open < 0) throw new Error(`declaration of ${tsType} is not an object literal`)
    const body = balancedObject(source, open)
    const declaration = `export type ${tsType} = ${body};`
    const literal = body.match(/type:\s*"([^"]+)"\s*;/)
    const rpc = body.match(/type:\s*`\$\{"rpc\."\}\$\{string\}`\s*;/)
    if (literal) return { tsType, type: literal[1], rpc: false, declaration }
    if (rpc) return { tsType, type: "rpc.*", rpc: true, declaration }
    throw new Error(`declaration of ${tsType} has no literal type field`)
  })
}

/** Materialize `--client <version|dir>` into a directory. Returns {dir, version, cleanup}. */
export function materializeClient(ref) {
  if (existsSync(ref)) {
    const pkgFile = join(ref, "package.json")
    const version = existsSync(pkgFile) ? (JSON.parse(readFileSync(pkgFile, "utf8")).version ?? ref) : ref
    return { dir: ref, version, cleanup: () => {} }
  }
  const tmp = mkdtempSync(join(tmpdir(), "opencode-client-"))
  execFileSync("npm", ["pack", `@opencode/client@${ref}`, "--pack-destination", tmp], { stdio: "pipe" })
  const tgz = `${tmp}/opencode-client-${ref}.tgz`
  if (!existsSync(tgz)) throw new Error(`npm pack produced no tarball for @opencode/client@${ref}`)
  execFileSync("tar", ["-xzf", tgz, "-C", tmp], { stdio: "pipe" })
  return { dir: join(tmp, "package"), version: ref, cleanup: () => rmSync(tmp, { recursive: true, force: true }) }
}

export function extractEvents(clientDir, clientVersion) {
  const source = readFileSync(findTypesFile(clientDir), "utf8")
  const parsed = parseEventTypes(source)
  const seen = new Set()
  for (const e of parsed) {
    if (seen.has(e.type)) throw new Error(`duplicate event type ${e.type}`)
    seen.add(e.type)
  }
  return {
    client: "@opencode/client",
    clientVersion,
    source: "generated TypeScript types (V2Event union in dist/promise/generated/types.d.ts)",
    eventCount: parsed.length,
    events: [...parsed].sort((a, b) => (a.type < b.type ? -1 : a.type > b.type ? 1 : 0)),
  }
}

export function readOpenapi(ref) {
  const parsed = /^https?:\/\//.test(ref)
    ? JSON.parse(
        execFileSync("curl", ["-s", "-m", "60", ref], { maxBuffer: 256 * 1024 * 1024 }).toString("utf8"),
      )
    : JSON.parse(readFileSync(ref, "utf8"))
  // Fail closed: a 401 page or any other non-spec payload must not read as "everything removed".
  // Credentials can be embedded in the URL (http://opencode:<password>@host:port/openapi.json).
  if (!parsed || typeof parsed !== "object" || !parsed.paths || typeof parsed.paths !== "object") {
    throw new Error(`not an OpenAPI document (no paths object): ${ref}`)
  }
  return parsed
}

/** "METHOD path" strings for every operation in a spec. */
export function listOperations(spec) {
  const ops = []
  for (const [path, item] of Object.entries(spec.paths ?? {})) {
    if (!item || typeof item !== "object") continue
    for (const method of ["get", "post", "put", "patch", "delete", "head", "options", "trace"]) {
      if (item[method] && typeof item[method] === "object") ops.push(`${method.toUpperCase()} ${path}`)
    }
  }
  return ops.sort()
}

function loadAllowlist(path) {
  if (!existsSync(path)) return { addedEvents: {}, removedEvents: {}, addedOperations: {}, removedOperations: {} }
  const raw = JSON.parse(readFileSync(path, "utf8"))
  return {
    addedEvents: raw.addedEvents ?? {},
    removedEvents: raw.removedEvents ?? {},
    addedOperations: raw.addedOperations ?? {},
    removedOperations: raw.removedOperations ?? {},
  }
}

function report(title, items, handled) {
  if (items.length === 0) return []
  const lines = [`${title} (${items.length}):`]
  for (const item of items) {
    lines.push(handled[item] ? `  ~ ${item} (allowlisted: ${handled[item]})` : `  + ${item}`)
  }
  return lines
}

/**
 * Compare newer client/spec against the vendored assets. Returns {ok, output}.
 * Anything added or removed must be named in the allowlist, or the check fails.
 */
export function checkDrift({ vendoredEvents, vendoredOperations, newerEvents, newerOperations, allowlist }) {
  const vendoredEventTypes = new Set(vendoredEvents.events.map((e) => e.type))
  const newerEventTypes = new Set(newerEvents.map((e) => e.type))
  const addedEvents = [...newerEventTypes].filter((t) => !vendoredEventTypes.has(t)).sort()
  const removedEvents = [...vendoredEventTypes].filter((t) => !newerEventTypes.has(t)).sort()
  const vendoredOps = new Set(vendoredOperations)
  const newerOps = new Set(newerOperations)
  const addedOperations = [...newerOps].filter((o) => !vendoredOps.has(o)).sort()
  const removedOperations = [...vendoredOps].filter((o) => !newerOps.has(o)).sort()

  const lines = [
    `events: vendored ${vendoredEventTypes.size}, newer ${newerEventTypes.size}`,
    `operations: vendored ${vendoredOps.size}, newer ${newerOps.size}`,
    ...report("added events", addedEvents, allowlist.addedEvents),
    ...report("removed events", removedEvents, allowlist.removedEvents),
    ...report("added operations", addedOperations, allowlist.addedOperations),
    ...report("removed operations", removedOperations, allowlist.removedOperations),
  ]
  const unhandled =
    addedEvents.filter((t) => !allowlist.addedEvents[t]).length +
    removedEvents.filter((t) => !allowlist.removedEvents[t]).length +
    addedOperations.filter((o) => !allowlist.addedOperations[o]).length +
    removedOperations.filter((o) => !allowlist.removedOperations[o]).length
  if (unhandled > 0) lines.push(`DRIFT: ${unhandled} unhandled change(s); handle them or record them in the allowlist.`)
  else lines.push("OK: no unhandled drift.")
  return { ok: unhandled === 0, output: lines.join("\n") }
}

async function main() {
  const command = process.argv[2]
  if (command === "extract") {
    const ref = arg("--client")
    const out = arg("--out")
    const { dir, version, cleanup } = materializeClient(ref)
    try {
      const events = extractEvents(dir, version)
      writeFileSync(out, `${JSON.stringify(events, null, 2)}\n`)
      console.log(`wrote ${events.eventCount} event types from @opencode/client@${version} to ${out}`)
    } finally {
      cleanup()
    }
    return
  }
  if (command === "check") {
    const ref = arg("--client")
    const openapiRef = arg("--openapi")
    const allowlistPath = process.argv.includes("--allowlist") ? arg("--allowlist") : DEFAULT_ALLOWLIST
    const allowlist = loadAllowlist(allowlistPath)
    const { dir, cleanup } = materializeClient(ref)
    try {
      const source = readFileSync(findTypesFile(dir), "utf8")
      const newerEvents = parseEventTypes(source)
      const newerOperations = listOperations(readOpenapi(openapiRef))
      const vendoredEvents = JSON.parse(readFileSync(DEFAULT_EVENTS, "utf8"))
      const vendoredOperations = listOperations(JSON.parse(readFileSync(DEFAULT_OPENAPI, "utf8")))
      const { ok, output } = checkDrift({ vendoredEvents, vendoredOperations, newerEvents, newerOperations, allowlist })
      console.log(output)
      process.exit(ok ? 0 : 1)
    } finally {
      cleanup()
    }
    return
  }
  usage()
  process.exit(2)
}

if (process.argv[1]?.endsWith("check-api-drift.mjs")) await main()
