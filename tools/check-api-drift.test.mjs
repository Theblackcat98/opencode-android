import assert from "node:assert/strict"
import { execFileSync } from "node:child_process"
import { mkdtempSync, mkdirSync, rmSync, writeFileSync } from "node:fs"
import { tmpdir } from "node:os"
import { join } from "node:path"
import test from "node:test"
import { fileURLToPath } from "node:url"
import { checkDrift, listOperations, parseEventTypes } from "./check-api-drift.mjs"

const TOOL = fileURLToPath(new URL("./check-api-drift.mjs", import.meta.url))

const MINI_TYPES = `export type MiniA = {
    id: string;
    created: number;
    type: "mini.a";
    location?: LocationRef;
    data: {
        sessionID: string;
    };
};
export type MiniB = {
    id: string;
    type: "mini.b";
    data: {};
};
export type V2EventRpc = {
    id: string;
    created: number;
    type: \`\${"rpc."}\${string}\`;
    location: LocationRef;
    data: {
        [x: string]: any;
    };
};
export type V2Event = MiniA | MiniB | V2EventRpc;
`

test("parseEventTypes reads literals and the rpc family", () => {
  const events = parseEventTypes(MINI_TYPES)
  assert.deepEqual(
    events.map((e) => [e.tsType, e.type, e.rpc]),
    [
      ["MiniA", "mini.a", false],
      ["MiniB", "mini.b", false],
      ["V2EventRpc", "rpc.*", true],
    ],
  )
  assert.match(events[0].declaration, /export type MiniA = \{/)
})

test("parseEventTypes rejects a missing union", () => {
  assert.throws(() => parseEventTypes("export type V2Event = ;"), /V2Event union not found/)
})

const EMPTY_ALLOWLIST = { addedEvents: {}, removedEvents: {}, addedOperations: {}, removedOperations: {} }

function driftFixture({ extraEvent = null, extraOp = null } = {}) {
  const vendoredEvents = { events: [{ type: "mini.a" }, { type: "mini.b" }] }
  const newerEvents = [{ type: "mini.a" }, { type: "mini.b" }]
  if (extraEvent) newerEvents.push({ type: extraEvent })
  const vendoredOperations = ["GET /api/mini"]
  const newerOperations = ["GET /api/mini"]
  if (extraOp) newerOperations.push(extraOp)
  return { vendoredEvents, vendoredOperations, newerEvents, newerOperations, allowlist: EMPTY_ALLOWLIST }
}

test("checkDrift passes on no change", () => {
  const { ok, output } = checkDrift(driftFixture())
  assert.equal(ok, true)
  assert.match(output, /OK: no unhandled drift/)
})

test("checkDrift fails on an unhandled event addition", () => {
  const { ok, output } = checkDrift(driftFixture({ extraEvent: "mini.c" }))
  assert.equal(ok, false)
  assert.match(output, /mini\.c/)
  assert.match(output, /DRIFT: 1 unhandled/)
})

test("checkDrift fails on an unhandled operation addition", () => {
  const { ok } = checkDrift(driftFixture({ extraOp: "POST /api/mini" }))
  assert.equal(ok, false)
})

test("checkDrift passes an allowlisted addition", () => {
  const fixture = driftFixture({ extraEvent: "mini.c" })
  fixture.allowlist = { ...EMPTY_ALLOWLIST, addedEvents: { "mini.c": "planned in P9" } }
  const { ok, output } = checkDrift(fixture)
  assert.equal(ok, true)
  assert.match(output, /allowlisted/)
})

test("checkDrift fails on a removal", () => {
  const fixture = driftFixture()
  fixture.newerEvents = [{ type: "mini.a" }]
  const { ok } = checkDrift(fixture)
  assert.equal(ok, false)
})

test("listOperations enumerates method+path", () => {
  const spec = { paths: { "/api/mini": { get: {}, post: {} }, "/api/other": { delete: {} } } }
  assert.deepEqual(listOperations(spec), ["DELETE /api/other", "GET /api/mini", "POST /api/mini"])
})

/** End-to-end through the CLI with fixture packages (no network). */
function fixtureClientDir(extraTypeDecl, inUnion) {
  const dir = mkdtempSync(join(tmpdir(), "drift-client-"))
  const gen = join(dir, "dist/promise/generated")
  mkdirSync(gen, { recursive: true })
  writeFileSync(join(dir, "package.json"), JSON.stringify({ name: "x", version: "9.9.9" }))
  const union = `export type V2Event = MiniA | MiniB${inUnion ?? ""};`
  writeFileSync(join(gen, "types.d.ts"), `${MINI_TYPES.split("export type V2Event")[0]}${union}${extraTypeDecl ?? ""}`)
  return dir
}

function fixtureVendoredDir() {
  const dir = mkdtempSync(join(tmpdir(), "drift-vendored-"))
  writeFileSync(
    join(dir, "events.json"),
    JSON.stringify({
      client: "@opencode/client",
      clientVersion: "9.9.9",
      source: "fixture",
      eventCount: 2,
      events: [
        { type: "mini.a", tsType: "MiniA", rpc: false, declaration: "export type MiniA = {};" },
        { type: "mini.b", tsType: "MiniB", rpc: false, declaration: "export type MiniB = {};" },
      ],
    }),
  )
  writeFileSync(
    join(dir, "openapi.json"),
    JSON.stringify({ openapi: "3.1.0", paths: { "/api/mini": { get: { operationId: "mini.get" } } } }),
  )
  return dir
}

function runCheck({ clientDir, openapiFile, allowlist, vendoredDir }) {
  const args = ["check", "--client", clientDir, "--openapi", openapiFile]
  if (allowlist) {
    const file = join(mkdtempSync(join(tmpdir(), "drift-allow-")), "allow.json")
    writeFileSync(file, JSON.stringify(allowlist))
    args.push("--allowlist", file)
  }
  try {
    execFileSync("node", [TOOL, ...args], {
      stdio: "pipe",
      env: { ...process.env, CHECK_API_DRIFT_VENDORED: vendoredDir },
    })
    return 0
  } catch (error) {
    return error.status ?? 99
  }
}

test("CLI check passes on no change against fixture assets", () => {
  const vendoredDir = fixtureVendoredDir()
  const clientDir = fixtureClientDir()
  const openapiFile = join(vendoredDir, "openapi.json")
  try {
    assert.equal(runCheck({ clientDir, openapiFile, vendoredDir }), 0)
  } finally {
    rmSync(clientDir, { recursive: true, force: true })
    rmSync(vendoredDir, { recursive: true, force: true })
  }
})

test("CLI check fails on an unhandled addition against fixture assets", () => {
  const vendoredDir = fixtureVendoredDir()
  const extra = `export type MiniC = {
    id: string;
    type: "mini.c";
    data: {};
};
`
  const clientDir = fixtureClientDir(extra, " | MiniC")
  const openapiFile = join(vendoredDir, "openapi.json")
  try {
    assert.equal(runCheck({ clientDir, openapiFile, vendoredDir }), 1)
    assert.equal(
      runCheck({
        clientDir,
        openapiFile,
        vendoredDir,
        allowlist: { addedEvents: { "mini.c": "fixture reason" } },
      }),
      0,
    )
  } finally {
    rmSync(clientDir, { recursive: true, force: true })
    rmSync(vendoredDir, { recursive: true, force: true })
  }
})
