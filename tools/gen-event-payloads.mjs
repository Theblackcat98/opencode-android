#!/usr/bin/env node
/**
 * Builds `core/testing/src/main/resources/fixtures/event-payloads.jsonl`: one minimal valid
 * envelope for each of the 93 named event types in `api/opencode-2.0.x/events.json`.
 *
 * The recorded stream (`events.jsonl`) only exercises 47 of them, so 46 types had a registry entry
 * and nothing that proved the payload decodes. This walks the vendored TypeScript declaration for
 * each event, fills every required field with the least interesting value its type allows, and
 * resolves the handful of named references the declarations use from [REFERENCES].
 *
 * A reference the table does not know is a hard error, not a guess: the point of the corpus is that
 * a payload really does satisfy the published type.
 *
 * Usage: node tools/gen-event-payloads.mjs [--check]
 *   --check  fail if the committed corpus differs from what this generates.
 */
import { readFileSync, writeFileSync } from 'node:fs'
import { join, dirname } from 'node:path'
import { fileURLToPath } from 'node:url'

const ROOT = join(dirname(fileURLToPath(import.meta.url)), '..')
const SPEC = join(ROOT, 'api/opencode-2.0.x/events.json')
const OPENAPI = join(ROOT, 'api/opencode-2.0.x/openapi.json')
const OUT = join(ROOT, 'core/testing/src/main/resources/fixtures/event-payloads.jsonl')

const SCHEMAS = JSON.parse(readFileSync(OPENAPI, 'utf8')).components.schemas

/**
 * The event declarations name types the way `@opencode/client`'s generator spells them and the
 * OpenAPI document names them the way the server does. This is the whole translation, so a new
 * reference in a future event list has to be added here deliberately rather than guessed at.
 */
const REFERENCE_SCHEMA = {
  FormAnswer1: 'Form.Answer',
  FormInfo1: 'Form.Info',
  InstructionEntrySnapshot: 'InstructionEntry.Info',
  LocationRef: 'Location.PublicRef',
  ModelRef: 'Model.Ref',
  MoneyUSD: 'Money.USD',
  PermissionReply: 'Permission.Reply',
  PermissionRuleset: 'Permission.Ruleset',
  PermissionSource: 'Permission.Source',
  PersistentPtyInfo: 'PersistentPty.Info',
  ProjectCommands: 'Project.Commands',
  ProjectIcon: 'Project.Icon',
  ProjectTime: 'Project.Time',
  ProjectVcs: 'Project.Vcs',
  Pty: 'Pty',
  SessionForkBoundary: 'Session.ForkBoundary',
  SessionInboxDelivery: 'Session.Inbox.Delivery',
  SessionInboxItem: 'Session.Inbox.Info',
  SessionMessageProviderState1: 'Session.Message.ProviderState',
  SessionMetadata: 'Session.Metadata',
  SessionProviderContext: 'Session.ProviderContext',
  SessionRevert: 'Session.Revert',
  SessionStatus: 'Session.Status',
  SessionStructuredError: 'Session.StructuredError',
  ShellInfo: 'Shell.Info',
  TokenUsageInfo: 'TokenUsage.Info',
}

/** Types the client generator names but the spec models as an inline tagged union. */
const LITERALS = {
  // `SessionStatus` is decoded by a discriminator serializer, so it is an object, not a string.
  SessionStatus: { type: 'busy' },
  JsonValue: 'x',
  SessionInboxDelivery: 'queue',
  ToolContent1: { type: 'text', text: 'x' },
}

/** The least interesting instance of an OpenAPI schema: every required field, nothing else. */
function fromSchema(name, seen = new Set()) {
  if (seen.has(name)) throw new Error(`recursive schema ${name}`)
  const schema = SCHEMAS[name]
  if (!schema) throw new Error(`no schema named ${name}`)
  return instantiate(schema, name, seen)
}

function instantiate(node, where, seen) {
  if (node.$ref) {
    if (seen.has(node.$ref)) throw new Error(`recursive schema ${node.$ref} at ${where}`)
    return fromSchema(node.$ref.split('/').pop(), new Set([...seen, node.$ref]))
  }
  if (node.enum) return node.enum[0]
  if (node.const !== undefined) return node.const
  const alts = node.oneOf ?? node.anyOf
  if (alts?.length) return instantiate(alts[0], where, seen)
  if (node.allOf?.length) {
    return Object.assign({}, ...node.allOf.map((a) => instantiate(a, where, seen)))
  }
  switch (node.type) {
    case 'string':
      return 'x'
    case 'number':
    case 'integer':
      return 1
    case 'boolean':
      return true
    case 'null':
      return null
    case 'array': {
      // A list the schema insists is non-empty, such as a form's fields, needs one element to be
      // a valid instance at all. An empty list is the honest answer for everything else.
      if ((node.minItems ?? 0) < 1) return []
      const element = node.prefixItems?.[0] ?? node.items
      if (element === undefined) throw new Error(`non-empty array with no item type at ${where}`)
      return [instantiate(element, `${where}[0]`, seen)]
    }
    case 'object':
    case undefined: {
      // An object is valid exactly when every required member is, so recurse into `required`
      // whether the schema is named or written inline.
      const out = {}
      for (const key of node.required ?? []) {
        const property = node.properties?.[key]
        if (property === undefined) throw new Error(`required member ${key} has no type at ${where}`)
        out[key] = instantiate(property, `${where}.${key}`, seen)
      }
      return out
    }
    default:
      throw new Error(`unhandled schema type ${JSON.stringify(node.type)} at ${where}`)
  }
}

/** Strips comments and collapses whitespace, so a declaration can be scanned as text. */
function body(declaration) {
  const start = declaration.indexOf('{')
  const end = declaration.lastIndexOf('}')
  return declaration.slice(start, end + 1)
}

/** Splits an object type body on `;` at depth 0, keeping nested braces intact. */
function members(text) {
  const out = []
  let depth = 0
  let current = ''
  for (const ch of text) {
    if (ch === '{' || ch === '(' || ch === '[') depth++
    if (ch === '}' || ch === ')' || ch === ']') depth--
    if (ch === ';' && depth === 0) {
      out.push(current)
      current = ''
    } else {
      current += ch
    }
  }
  if (current.trim()) out.push(current)
  return out.map((m) => m.trim()).filter(Boolean)
}

/**
 * Splits a type expression on the `|`s that are its own, ignoring the ones inside `{}` and `[]`.
 * `{ a: string | null }` is one object type; `string | null` is a union of two.
 */
function topLevelUnion(t) {
  const parts = []
  let depth = 0
  let current = ''
  for (const ch of t) {
    if (ch === '{' || ch === '(' || ch === '[') depth++
    if (ch === '}' || ch === ')' || ch === ']') depth--
    if (ch === '|' && depth === 0) {
      parts.push(current)
      current = ''
    } else {
      current += ch
    }
  }
  parts.push(current)
  return parts.map((p) => p.trim()).filter((p) => p && p !== 'undefined' && p !== 'null')
}

/** The value for a TypeScript type expression, or `undefined` when it is optional. */
function value(typeExpr, where) {
  const t = typeExpr.trim()

  // Optional fields are omitted rather than filled, so the corpus stays minimal and every value
  // present is one the published type demands.
  if (t.endsWith('?')) return undefined

  // A string literal, the discriminator in most of these unions.
  const lit = t.match(/^"([^"]*)"$/)
  if (lit) return lit[1]

  if (t === 'string' || t === 'number' || t === 'boolean' || t === 'null') {
    return { string: 'x', number: 1, boolean: true, null: null }[t]
  }
  if (t === '{}' || t === 'any' || t === 'unknown' || t === 'object') return {}

  // A named reference: a spec schema, or one of the few types the client generator names and the
  // spec models as an inline union.
  if (/^[A-Z]\w*$/.test(t)) {
    if (LITERALS[t] !== undefined) return LITERALS[t]
    const schema = REFERENCE_SCHEMA[t]
    if (schema === undefined) throw new Error(`unmapped reference ${t} at ${where}`)
    if (SCHEMAS[schema] === undefined) throw new Error(`${t} maps to ${schema}, which the spec does not define`)
    return fromSchema(schema)
  }

  // An empty list satisfies any element type, so a union of lists needs no element.
  if (t.startsWith('Array<') || t.startsWith('Record<')) return []

  // A tuple, `[T, ...Array<T>]`, which the server requires to be non-empty: one element.
  if (t.startsWith('[') && t.endsWith(']')) {
    const first = topLevelUnion(t.slice(1, -1).split(',')[0])
    if (first.length !== 1) throw new Error(`unhandled tuple ${JSON.stringify(t)} at ${where}`)
    return [value(first[0], `${where}[0]`)]
  }
  if (t.startsWith('(')) return []

  if (t.startsWith('{')) {
    const out = {}
    for (const m of members(t.slice(1, t.lastIndexOf('}')))) {
      // An index signature accepts any object, so `{}` already satisfies one and there is nothing
      // to invent. It also contains the `:` this loop would otherwise split on.
      if (/^\[[^\]]*\]/.test(m)) continue
      const idx = m.indexOf(':')
      if (idx < 0) continue
      const optional = m.slice(0, idx).trim().endsWith('?')
      const name = m.slice(0, idx).trim().replace(/\?$/, '')
      if (optional) continue
      const v = value(m.slice(idx + 1), `${where}.${name}`)
      if (v !== undefined) out[name] = v
    }
    return out
  }

  // A union: the first alternative that is not `undefined` or `null`.
  const union = topLevelUnion(t)
  if (union.length && (union.length > 1 || union[0] !== t)) return value(union[0], where)

  throw new Error(`unhandled type ${JSON.stringify(t)} at ${where}`)
}

/** Reads a `data: { ... }` member out of the event envelope type. */
function dataOf(declaration) {
  const b = body(declaration)
  for (const m of members(b.slice(1, b.lastIndexOf('}')))) {
    const idx = m.indexOf(':')
    if (idx < 0) continue
    if (m.slice(0, idx).trim() !== 'data') continue
    return value(m.slice(idx + 1), 'data')
  }
  throw new Error(`no data member in ${declaration.slice(0, 60)}`)
}

const events = JSON.parse(readFileSync(SPEC, 'utf8')).events.filter((e) => !e.type.startsWith('rpc.'))
if (events.length !== 93) throw new Error(`expected 93 named types, found ${events.length}`)

const lines = events.map((e, i) => {
  const envelope = {
    id: `evt_${String(i + 1).padStart(3, '0')}`,
    type: e.type,
    created: 1,
  }
  // `durable` is required on the events that carry a session aggregate.
  if (/durable:/.test(e.declaration)) {
    envelope.durable = { aggregateID: 'ses_1', seq: 1, version: 1 }
  }
  envelope.location = fromSchema(REFERENCE_SCHEMA.LocationRef)
  envelope.data = dataOf(e.declaration)
  return JSON.stringify(envelope)
})

const text = lines.join('\n') + '\n'
if (process.argv.includes('--check')) {
  const current = readFileSync(OUT, 'utf8')
  if (current !== text) {
    console.error('event-payloads.jsonl is stale; run: node tools/gen-event-payloads.mjs')
    process.exit(1)
  }
  console.log(`event-payloads.jsonl matches the vendored declarations (${lines.length} types)`)
} else {
  writeFileSync(OUT, text)
  console.log(`wrote ${lines.length} payloads to ${OUT.replace(ROOT + '/', '')}`)
}
