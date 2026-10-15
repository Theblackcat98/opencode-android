# tools/AGENTS.md

Node scripts (plus two shell/Python helpers) that encode the repo's executable claims. Each file's header comment is the full documentation — this is just the index.

## Audit gates (the fast gates in root AGENTS.md)

| Script | What it checks |
|---|---|
| `audit-accessibility.mjs` | Plan §5.4 accessibility rules against real sources (contentDescription, stateDescription, heading roles) |
| `audit-contrast.mjs` | WCAG 2.x contrast ratios of the color pairs the app actually paints, read from Kotlin sources |
| `audit-contrast.test.mjs` | Tests for the contrast tool |
| `audit-coverage.mjs` | Plan §7/§8 coverage: every API row declared + wired + tested, every event type registered |
| `audit-matrix.mjs` | Every `docs/MANUAL_TEST_MATRIX.md` row has a valid result marker (blank ≠ passed) |
| `audit-matrix.test.mjs` | Tests for the matrix tool |
| `audit-screen-flow.mjs` | The embedded thumbnails in `docs/app_screen_flow.html` match the screenshot baselines they name; no duplicate captures; every edge has a matching button |
| `audit-screen-flow.test.mjs` | Tests for the screen-flow tool |
| `audit-strings.mjs` | Every user-visible string externalized to `strings.xml` (reports per module) |
| `audit-viewmodels.mjs` | Every production ViewModel is `@HiltViewModel` and gets the right instance (seven screens crashed without this) |
| `audit-viewmodels.test.mjs` | Tests for the viewmodel tool |
| `check-api-drift.mjs` | Guards vendored `api/opencode-2.0.x/` against upstream drift; `extract` regenerates from a client release |
| `check-api-drift.test.mjs` | Tests for the drift tool |
| `gen-event-payloads.mjs` | Builds `event-payloads.jsonl`: one minimal valid envelope per named event type |

## Fixtures and dev harnesses

| Script | What it does |
|---|---|
| `record-fixtures.mjs` | Records REST + SSE fixtures from a real `opencode serve` into `core/testing` resources (response bodies only, never credentials) |
| `fake-provider/` | Scripted OpenAI-compatible provider (models: text, reasoning, shell, edit, question, subagent, error, long, slow, probe) used by `dev-server.sh` |
| `tui-inject-proxy.mjs` | Dev-only reverse proxy injecting `tui.*` events into the app stream so the TUI matrix rows can be exercised without a TUI |
| `tui-inject-proxy.test.mjs` | Tests for the proxy |
| `dump-client-type.py` | Prints one generated TypeScript type from the vendored `@opencode/client` (event payloads are opaque in the OpenAPI spec) |

## Docs and release

| Script | What it does |
|---|---|
| `update-screen-flow-shots.mjs` | Embeds Roborazzi screenshot baselines into `docs/app_screen_flow.html` as WebP; run by `.github/workflows/screen-flow.yml` on push to main |
| `test-release-gate.sh` | Verifies the publishable release task fails closed when CI has no signing material |

Conventions: `*.test.mjs` next to a tool tests that tool. `audit-strings` and `audit-accessibility` always exit 0 — read the output; CI gates strings through a shell pipeline over `audit-strings --json`, not the exit code.
