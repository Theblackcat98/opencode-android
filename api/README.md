# Vendored OpenCode V2 API assets

These files pin the server contract the app is built against. Every later phase
decodes, lists, or diffs against them; nothing here may be hand-edited (regenerate
instead, so provenance stays exact).

## Pinned releases

| Package | Version | Provenance |
| --- | --- | --- |
| `@opencode/cli` | 2.0.18 | npm; the newest 2.0.x on 2026-09-28 (`npm view @opencode/cli versions`). The `opencode serve` binary used by `scripts/dev-server.sh` and CI. |
| `@opencode/client` | 2.0.18 | npm; same release line as the CLI. Source of the event type list. |

## Assets (all genuine 2.0.18, verified 2026-09-28)

| File | Source | Check |
| --- | --- | --- |
| `opencode-2.0.x/openapi.json` | The published 2.0.18 spec | 136 operations on 113 paths and 245 schemas, matching the implementation plan's API matrix exactly. A live 2.0.18 server's `/openapi.json` is a strict superset: the same 136 operations plus the 2 unpublished pairing routes (see allowlist). |
| `opencode-2.0.x/events.json` | Generated TypeScript types of `@opencode/client@2.0.18` (`V2Event` union in `dist/promise/generated/types.d.ts`) | 94 entries: 93 named event types plus the `rpc.*` family, matching the plan's event matrix. The OpenAPI spec leaves event payloads opaque, so this file — not the spec — is the source of truth for events. |
| `opencode-2.0.x/config.schema.json` | `https://opencode.ai/config.json` | Byte-identical to the canonical schema on 2026-09-28. Used by the P9 config editor. |
| `opencode-2.0.x/drift-allowlist.json` | Maintained by hand | Known differences `check-api-drift` must not fail on. Every entry carries its reason. |

## Regenerating

```sh
# Event list from a client release (version or extracted package dir):
node tools/check-api-drift.mjs extract --client 2.0.18 --out api/opencode-2.0.x/events.json

# Config schema from its canonical URL:
curl -s https://opencode.ai/config.json -o api/opencode-2.0.x/config.schema.json

# OpenAPI spec from a live server of the pinned release:
eval "$(scripts/dev-server.sh start)"
curl -s -u "opencode:$OPENCODE_PASSWORD" "$OPENCODE_URL/openapi.json" -o /tmp/openapi-live.json
# (strip the 2 unpublished pairing routes before comparing with the published file)
```

## Drift check (runs locally and in CI)

```sh
eval "$(scripts/dev-server.sh start)"
node tools/check-api-drift.mjs check \
  --client <newer-version> \
  --openapi "http://opencode:$OPENCODE_PASSWORD@${OPENCODE_URL#http://}/openapi.json"
node --test tools/check-api-drift.test.mjs
```

Exit 0 means the vendored assets cover the newer release; exit 1 lists unhandled
additions/removals. Handle them (models, fixtures, matrices) and re-extract, or
record a reasoned entry in `drift-allowlist.json`.
