# OpenCode V2 Android App: Implementation Plan

This plan covers a native Android app that connects to an OpenCode V2 server on the local network, or on any network
the phone can reach, and drives every feature the server exposes. The feature inventory it builds on is
[`OPENCODE_V2_FEATURES.md`](./OPENCODE_V2_FEATURES.md), referred to below as "features doc § n".

The work is split into eleven phases, P0 to P10. Each phase ships a usable app, and each one is built on the
infrastructure of the phases before it. No phase needs to reopen an earlier one; later phases only reuse and extend
what exists. [§7](#7-api-coverage-matrix) and [§8](#8-event-coverage-matrix) assign every API operation and every event
type to a phase, which is how "drives every feature" is checked.

---

## 1. Goals and non-goals

**Goals**

- Pair with an OpenCode V2 server in seconds, by scanning the QR code from `opencode pair`, and manage several servers.
- Offer the full session experience on the phone: watch sessions live, prompt, steer or queue, answer permissions
  and questions, review diffs, undo, fork, and work with subagents, shells, terminals and worktrees.
- Manage the server's runtime from the phone: provider and integration accounts, MCP servers, plugins, saved
  approvals, and configuration.
- Stay useful in the background, with actionable notifications for permission requests, questions and completed turns.
- Follow modern Android practice: Material 3, adaptive layouts for phones, tablets and foldables, accessibility, and
  secure credential storage.

**Non-goals**

- Running OpenCode on the phone. The app is always a client of a server.
- OpenCode V1 servers. V2's server API is an intentional breaking change.
- Managing the hosted OpenCode Console (billing, budgets). This is a possible later extension.
- ACP. It is stdio-only.

---

## 2. Research findings that shape the design

1. **The server is authoritative.** Sessions, config, credentials, permissions and tool execution all live on the
   server. The app is a thin, event-driven client, the same model the TUI, web app and desktop app use.
2. **Authentication is HTTP Basic** with the username `opencode`, and either the server password or a 30-day token
   issued by pairing. Scanning `http(s)://host:port/auth/connect/<code>` and calling that URL with
   `Accept: application/json` returns `{token}`. Codes are single-use and expire after 5 minutes. This was verified
   against a live 2.0.18 server.
3. **Servers listen on localhost by default, and V2 has no mDNS.** Onboarding must teach
   `opencode service set hostname 0.0.0.0` (or `opencode serve --hostname 0.0.0.0`) followed by `opencode pair`, and
   offer SSH-tunnel or Tailscale guidance. Discovery means QR, a pasted link, or manual entry, plus an optional subnet
   probe later on.
4. **LAN traffic is usually plain HTTP.** The app must allow cleartext traffic, because a network security config
   can't list arbitrary LAN IPs. It should warn visibly when a non-loopback server uses `http://`, support HTTPS, and
   optionally trust user-installed CAs.
5. **There is one global SSE stream, and it is live-only.** `server.connected` arrives first, a heartbeat every 15 s,
   there is no replay, and a slow consumer is disconnected once 4,096 frames queue up. The app needs a dedicated,
   never-blocking reader, reconnect with backoff, and a **REST resync on every `server.connected`**. This is exactly
   what the reference client (`@opencode/client` `solid/data.ts` and `connection.ts`) does.
6. **Almost everything is location-scoped** through `location[directory]`, so every cache is keyed by
   `(server, directory)`, and `location.shutdown` drops a location's caches.
7. **The transcript is a projection.** REST returns projected messages. Live events (`step`, `text`, `reasoning`,
   `tool`, `compaction` and `inbox`) mutate that projection, keyed by assistant message ID plus ordinal or tool ID.
   That calls for a pure, well-tested `TimelineReducer` ported from the reference client's semantics.
8. **Input goes through a durable inbox** with `steer` or `queue` delivery. Messages accept client-generated IDs, so
   retries on flaky mobile networks are idempotent.
9. **Forms are one generic mechanism.** The `question` tool, web-search consent, MCP elicitation and integration
   login forms all use it, so a single renderer delivers four features.
10. **Permission requests block the agent.** On mobile, background presence and notification actions are core
    features, not polish.
11. **PTYs speak a simple WebSocket protocol.** Frames are raw UTF-8, with one `0x00`+JSON frame carrying the output
    cursor. Resizing goes over REST, and native clients can use Basic auth on the upgrade request.
12. **Definitions are file-based.** Agents, commands, skills, providers and policies live in files. They are readable
    through catalogs and `GET /api/config`, and writable only by editing files through experimental endpoints.
13. **29 operations are experimental** (under `/api/experimental/`). They must sit behind capability detection with
    graceful degradation.
14. **The OpenAPI spec leaves event payloads opaque.** Event types have to come from `@opencode/client`'s generated
    TypeScript types, which the project vendors and checks for drift.

---

## 3. Technology choices

| Concern | Choice | Rationale |
| --- | --- | --- |
| Language | Kotlin 2.x, coroutines and Flow | Modern Android default |
| UI | Jetpack Compose, Material 3, Material 3 Adaptive (list-detail panes, navigation suite) | One code base for phones, tablets and foldables |
| Navigation | Navigation Compose with type-safe routes | Deep links for notifications and share targets |
| DI | Hilt | Standard and well supported |
| HTTP | OkHttp, plus Retrofit with the kotlinx-serialization converter | Interceptors for auth and location, and the same client for WebSockets |
| SSE | A small custom SSE line parser over the OkHttp response stream (with `readTimeout` as a backstop) | Sees `: heartbeat` comments for the idle watchdog, which the `okhttp-sse` listener hides, and gives full control over batching |
| WebSocket | OkHttp WebSocket | PTY and persistent PTY streams |
| JSON | kotlinx.serialization: `ignoreUnknownKeys`, a `type` class discriminator, an `Unknown` fallback subtype for every union, and custom serializers for `number \| "Infinity"` fields | Forward compatibility with fast-moving 2.0.x releases |
| Local storage | Room (server profiles, cached sessions and messages, drafts, favorites, stash) and DataStore (preferences) | Instant startup and offline read |
| Secrets | Android Keystore AES-GCM key encrypting passwords and tokens | `security-crypto` is deprecated |
| Images | Coil 3 | Attachments, tool images, `fs.read` previews |
| Markdown and code | `multiplatform-markdown-renderer` (Material 3), with its syntax-highlighting module | Compose-native Markdown with code blocks |
| QR scanning | CameraX with ML Kit barcode scanning (bundled model); ZXing in an F-Droid flavor | Works offline and without Play Services in the F-Droid build |
| Terminal | xterm.js bundled offline in a WebView, bridged to the OkHttp WebSocket. Termux `terminal-view` is evaluated as a fallback. | Full VT fidelity matching the web app |
| Background | Foreground service plus NotificationCompat. WorkManager for deferred housekeeping. | Keeps the event stream alive while the agent needs the user |
| Testing | JUnit, coroutines-test, Turbine, MockWebServer, Robolectric, Compose UI tests, Roborazzi screenshots, and integration tests against a real `opencode serve` | See [§5.3](#53-testing-strategy) |
| Quality | ktlint (Spotless), detekt, Android Lint, and CI on GitHub Actions | |
| SDK levels | `minSdk 26`; `targetSdk` at the latest stable level Google Play requires | 26 gives notification channels and `java.time` |

---

## 4. Architecture

### 4.1 Modules

```text
app                      Navigation host, DI graph, flavors (play / fdroid)
core/model               Kotlin mirrors of the V2 schemas and event envelopes (sealed unions + Unknown)
core/network             OkHttp setup, AuthInterceptor, LocationParams, Retrofit API interfaces per tag,
                         EventStreamClient (SSE), PtySocket (WebSocket), capability probing
core/data                ServerConnection, EventDispatcher, the SyncedResource stores, TimelineReducer,
                         RequestCenter (permissions and forms), repositories
core/database            Room: servers, cached sessions and messages, drafts, favorites, stash, prompt history
core/designsystem        Theme (Material You and OpenCode themes), Markdown, code, diff and terminal components
core/testing             Fixtures recorded from real servers, fakes, test rules
feature/servers          Onboarding, pairing, server registry                             (P1)
feature/sessions         Projects, session list, timeline                                 (P2, P3)
feature/composer         Prompt input, attachments, mentions, commands                    (P3, P5)
feature/requests         Permission and form UI, notifications                            (P3, P4)
feature/review           Diffs, VCS, files, revert and fork                               (P6)
feature/execution        Subagents, shells, terminals, worktrees                          (P7)
feature/integrations     Providers, accounts, MCP, plugins, web search                    (P8)
feature/admin            Configuration, permissions admin, maintenance                    (P9)
feature/insights         Stats, RPC console, extras                                       (P10)
```

### 4.2 Runtime data flow

```text
                 ┌───────────────────── ServerConnection (one per active server) ─────────────────────┐
 REST (Retrofit) │  SyncedResource<T>  list / sync / invalidate, keyed by (server, directory)          │
 ───────────────▶│  SessionStore · TimelineStore (TimelineReducer) · InboxStore · RequestCenter        │──▶ StateFlow ──▶ ViewModels ──▶ Compose
 SSE /api/event  │  ShellStore · PtyStore · Location catalogs (agents, models, commands, …)            │
 ───────────────▶│  EventStreamClient → Channel → EventDispatcher (batched per frame) → stores         │
                 └──────────────────────────────────────────────────────────────────────────────────────┘
 User actions ──▶ Repository ──▶ REST call ──▶ the server emits events ──▶ stores update (no guessing)
```

Principles:

- **The server echoes; the client never guesses.** State changes come from events or REST snapshots. Optimistic UI is
  limited to inbox items, which the server echoes through `session.inbox.enqueued`.
- **Resync on reconnect.** `server.connected` triggers a refresh of active sessions, open timelines, inboxes, pending
  permissions and forms, and projects. Empty-payload `*.updated` events invalidate their catalog, and the refetch is
  debounced.
- **Never block the reader.** The SSE reader only parses and enqueues. A single dispatcher coroutine applies batched
  events about once per frame (~16 ms).
- **Tolerate unknowns.** Unknown event types, message types, tool names and form field types render as a generic
  fallback and are logged in debug builds.
- **Detect capabilities.** Experimental routes are probed lazily (`404`/`405` hides the feature) and can be switched
  off in settings.

### 4.3 Information architecture

- **Servers**: onboarding, pairing, registry, and a per-server status page.
- **Home** (per server): projects, recent sessions, running sessions, and a pending-requests inbox that gathers
  approvals and questions across all sessions.
- **Session**: a top bar (title, agent, model and variant, status, context gauge, cost), the timeline, a request dock,
  and the composer. Secondary panes are Review, Files, Terminal, Shells, Subagents and Info. They are tabs or a bottom
  sheet on phones and side panes on tablets.
- **Manage** (per server): accounts and providers, models, MCP, plugins, agents, commands and skills, permissions,
  configuration, stats and maintenance.
- **App settings**: appearance, notifications, background behavior, security (app lock), experimental features.

---

## 5. Cross-cutting concerns

### 5.1 Compatibility

- Gate on `GET /api/info` returning a major version of 2. Show "untested server version" for versions newer than the
  tested range.
- Vendor the OpenAPI spec and event list per tested release under `api/opencode-2.0.x/`. A `tools/check-api-drift`
  script diffs a newer `@opencode/cli` release and fails CI on unhandled additions or removals.
- Run a nightly CI job against the latest `@opencode/cli`, which catches breaking changes within a day.

### 5.2 Security and privacy

- Encrypt credentials with the Keystore, keep them out of logs, and offer an optional biometric app lock.
- Show a visible "not encrypted" badge for `http://` servers that aren't on loopback.
- Require explicit confirmation for dangerous actions: "Allow always", auto-approve mode, deleting a session,
  experimental file writes, and removing a credential.
- Harden the WebView: local assets only, no file access, and a JavaScript bridge limited to the terminal channel.
- No analytics by default. Crash reports are opt-in and never include prompt or file content.

### 5.3 Testing strategy

| Layer | Approach |
| --- | --- |
| Model decoding | Contract tests decode fixtures recorded from a real server: every message type, event type and error. |
| Reducer | Recorded SSE streams are replayed through `TimelineReducer`. The result is compared with the server's final REST projection, as a golden test. |
| Network | MockWebServer covers auth, 401 and re-pair, SSE parsing, heartbeats, the watchdog, reconnect, and PTY framing. |
| Integration | CI starts `opencode serve` (pinned `@opencode/cli`, isolated `HOME`, a password) configured with a **fake OpenAI-compatible provider** that streams scripted responses: text, reasoning, tool calls (`shell`, `edit`, `question`, `subagent`) and errors. This makes whole-app scenarios deterministic, including permissions, questions, diffs and reverts. |
| UI | Compose UI tests for flows, and Roborazzi screenshots for the timeline, diff and form renderers in light and dark themes and at large font scale. |
| Manual | A matrix of phone, tablet and foldable, Android 8 to latest, and HTTP and HTTPS servers, run before each release. |

### 5.4 Performance, accessibility and localization

- Frame-batched event application; stable `LazyColumn` keys; Markdown parsing and diff tokenization off the main thread.
- Paged timelines, bounded caches, and baseline profiles.
- TalkBack semantics for timeline items, dynamic type, and contrast checks.
- Every string externalized from P0 on.

### 5.5 Definition of done (every phase)

Unit and integration tests cover the phase's scenarios; the coverage matrices ([§7](#7-api-coverage-matrix),
[§8](#8-event-coverage-matrix)) are updated; screenshots are refreshed; the in-app help reflects the new features; CI is
green.

---

## 6. Phases

Each phase lists what it **builds on** and which **reusable building blocks** it adds for later phases. That chain is
what makes the order efficient: every block is built once, at the moment the first feature needs it, and the later
phases that reuse it get cheaper as a result.

| Phase | Theme | Main building blocks introduced | Reused by | Status |
| --- | --- | --- | --- | --- |
| P0 | Foundation | Build system, schema models, fixture harness, fake provider, CI | All | Complete |
| P1 | Connect and pair | HTTP client, auth, server registry, `EventStreamClient`, resync signal | All | Complete |
| P2 | Live read-only view | `SyncedResource` stores, `TimelineReducer`, Markdown, code and tool renderers | P3–P10 | Complete |
| P3 | Drive sessions (MVP) | Composer pipeline, pickers, `RequestCenter`, **forms engine** | P4, P5, P8, P9 | Complete |
| P4 | Background and notifications | `ConnectionService`, notification and action infrastructure, unread model | P7, P8, P9, P10 | Complete |
| P5 | Rich composer | Attachment pipeline, autocomplete and mention engine | P6, P8 | Complete |
| P6 | Review and history | Diff engine and viewer, file viewer, revert and fork flows | P7, P9 | Complete |
| P7 | Execution surfaces | WebSocket and terminal component, process panels, worktree flows | P10 | Complete |
| P8 | Integrations | OAuth, key and command login flows, MCP and plugin management | P9 | Complete |
| P9 | Configuration | Config explorer and validated file editor | P10 | Complete |
| P10 | Insights and release | Stats, RPC console, TUI control, session log, adaptive polish, release pipeline | n/a | Complete |

Relative size: S is small, M is medium, L is large.

---

### Phase 0: Foundation (S)

**Goal.** A buildable, testable skeleton, so that every later phase only adds features.

**Builds on.** Nothing.

**Scope**

- The Gradle project: version catalog, convention plugins, the module layout in [§4.1](#41-modules), `play` and
  `fdroid` flavors, and a Material 3 theme with dynamic color, an OpenCode palette and a monospace code font.
- API assets:
  - Vendor `openapi.json` (2.0.18) and the event type list extracted from `@opencode/client`.
  - Add `tools/check-api-drift`.
  - Vendor the config JSON Schema (`opencode.ai/config.json`) for P9.
- Core models: errors, `ServerInfo`, `Location.*`, `Session.Info`, the `Session.Message.Info` union, `Model.Ref`,
  `TokenUsage`, `StructuredError`, and the event envelope plus a sealed event hierarchy with an `Unknown` case.
- Test harness:
  - `scripts/dev-server.sh` starts a pinned `opencode serve` with an isolated `HOME`, a password and a generated
    `opencode.jsonc` that points at `tools/fake-provider`, a scripted OpenAI-compatible server.
  - A fixture recorder captures REST responses and SSE streams.
- CI: build, lint, unit tests, and the integration job.

**Exit criteria.**

- CI is green.
- Fixtures recorded from a real 2.0.18 server decode without loss.
- The debug APK launches to an empty shell.

**Status.** Complete. Verified with contract unit tests decoding all 2.0.18 server fixtures without loss, live-server integration tests against `scripts/dev-server.sh`, Android Lint passing, and debug APKs assembled.

---

### Phase 1: Connect and pair (M)

**Goal.** A reliable, secure connection to one or more servers, with a live event stream. This is the transport every
later feature rides on.

**Builds on.** P0 models and harness.

**Features**

- **Server registry**: add, edit and remove profiles (name, base URL, credential); a default server; health dots.
- **Three ways to add a server:**
  1. Scan the `opencode pair` QR code with CameraX and ML Kit.
  2. Paste or share a pairing link.
  3. Enter the URL and password manually.
- **Pairing redemption**: `GET /auth/connect/{code}` with `Accept: application/json` returns a token, which is stored
  encrypted.
- **Validation**: `GET /api/info` checks the version and lists the server's reachable URLs. Failures map to help:
  - connection refused: "The server only listens on localhost. On the computer, run
    `opencode service set hostname 0.0.0.0`, then `opencode service start` and `opencode pair`."
  - 401: re-pair.
  - TLS or timeout: an explanation of the error.
- **Onboarding guide** with the exact commands, plus SSH-tunnel and Tailscale notes.
- **Networking**:
  - An auth interceptor.
  - A `location[directory]` helper.
  - A network security config that allows cleartext, with an "unencrypted" badge in the UI.
  - Optional trust for user CAs.
- **`EventStreamClient`**:
  - Parses `data:` frames and treats `: heartbeat` comments as activity.
  - Requires `server.connected` as the first event.
  - Reconnects when idle for 45 s (watchdog), with exponential backoff (1 s up to 30 s, with jitter).
  - Reacts to network changes through `ConnectivityManager` and to app lifecycle (foreground only in this phase).
  - Exposes the connection state as a `StateFlow` and keeps a connection history log.
- **Resync signal** on each `server.connected`. P2 stores subscribe to it.
- **Screens**:
  - Servers.
  - Add server (scan, paste, manual).
  - Server status (version, URLs, connection log).
  - A developer **Event inspector**, a live event list with JSON detail that every later phase uses for debugging.

**API.** `server.info`, `GET /auth/connect/{code}`, `event.subscribe`.

**Exit criteria.**

- Pairing by QR takes under 30 s.
- Restarting the server leads to automatic reconnection, with correct state display.
- A wrong or rotated password prompts for re-pairing.
- Integration tests cover pairing and SSE reconnection.

**Status.** Complete. Verified with 188 JVM unit tests and 7 integration tests against a real 2.0.18
server started by `scripts/dev-server.sh`: a code minted by `POST /api/pair` is redeemed exactly once,
the token it returns authenticates as the Basic password, a replayed code is rejected, a wrong password
comes back as the re-pair class, and a closed stream reconnects and fires a second resync. Android Lint
passes and both `play` and `fdroid` debug APKs assemble; the F-Droid APK contains no Play Services.

**Not verified here, and why.** Scanning a QR code and the under-30-second pairing time need a device
with a camera, and this phase was built and tested without an emulator or hardware, so those two
criteria are unverified. The payload a QR code carries is the same string the paste, share and
deep-link paths use, and all of those paths are covered by the tests above, so what a device adds is
the camera preview and about two seconds of decoding. Both criteria belong to the manual device matrix
in [§5.3](#53-testing-strategy).

---

### Phase 2: Projects, sessions and the live timeline (read path) (L)

**Goal.** Watch everything happening on the server in real time. This phase builds the data layer and renderers that
every driving feature reuses.

**Builds on.** The P1 transport and event dispatcher.

**Features**

- **Home.**
  - Projects: `project.list` (name, icon and color, VCS) and `project.updated`.
  - Directory resolution through `location.get`.
  - An "All sessions" view.
- **Session list.**
  - `session.list` with cursor paging, search, a roots-only filter with child counts, and project and directory
    filters.
  - Badges:
    - Running comes from `session.active`, `session.status` and the execution events.
    - Retrying comes from `session.status`.
    - Unread means `time.idle > time.viewed`.
  - Cost and token totals, and agent and model chips.
  - Live insert, update and remove from the `session.*` lifecycle events.
- **Timeline.**
  - `session.message.list`, newest first, with "load older".
  - Renderers for every message type in features doc §5:
    - user messages, with attachment chips and thumbnails
    - assistant Markdown
    - collapsible reasoning with its duration
    - a generic tool card, plus compact renderers for `read`, `glob`, `grep`, `edit`, `write`, `patch`, `shell`,
      `webfetch`, `websearch`, `skill`, `subagent`, `question` and `execute`
    - synthetic and system notices, skill loads, shell runs
    - compaction blocks
    - idle outcome dividers
    - agent, model and location switch markers
    - step errors and retry state
- **`TimelineReducer`.** A pure `(state, event) → state` that handles every streaming event: `step`, `text`,
  `reasoning`, `tool`, `compaction`, `inbox`, `synthetic`, `shell`, `execution` and `revert`. It ports the reference
  client's semantics and ships with golden tests.
- **Stores.**
  - A `SyncedResource` pattern for location catalogs, starting with agents (for names and colors) and models (for
    names and context limits).
  - Invalidation on `agent.updated`, `model.updated`, `models-dev.refreshed` and `location.shutdown`.
  - A full resync on `server.connected`.
- **Session header.** Title, agent, model and variant, a context gauge (last step's tokens against the model's
  `limit.context`), cost, and follow mode with "jump to latest".
- **Room cache** of session lists and recent timelines, for instant open and offline reading.
- **Debug self-check.** When a session goes idle, refetch its messages and compare them with the reducer's state.
  Divergences are logged.

**API.** `location.get`, `project.list`, `session.list`, `session.active`, `session.get`, `session.message.list`,
`session.message.get`, `session.inbox.list` (read-only), `agent.list`, `agent.get`, `model.list`, `model.default`.

**Exit criteria.**

- A turn driven from the desktop TUI streams on the phone with identical content.
- Sessions with more than 1,000 messages scroll smoothly.
- A reconnect in the middle of a turn converges to the REST projection.

**Status.** Complete. Verified with 269 JVM unit tests (81 of them added by this phase), Android Lint clean, and both
`play` and `fdroid` debug APKs assembled. The two criteria that a build can decide are decided by tests:
`TimelineReducerGoldenTest` replays every recorded SSE stream and compares the result with the server's own
`session.message.list` projection, byte for byte, and `mid turn reconnect converges` replaces the client's state
with a projection recorded *while the assistant message was still open*, replays the rest of the stream onto it, and
requires the same convergence. The recorder gained that mid-turn capture, and re-recorded the fixture set: the
previous `events.jsonl` and `messages-*.json` files came from different runs, so they did not describe the same
sessions and only `misc` happened to line up.

**Not verified here, and why.** Scrolling smoothness with more than 1,000 messages needs a device: frame timing
is a property of the compositor and the display, not of a JVM test, and there is no emulator or hardware here. What
*is* verified is the structure that smoothness depends on — stable `LazyColumn` keys per message id, a plain
recycling layout rather than a fully composed one, and O(1) reducer application so a `text.delta` does not rescan a
thousand messages. A turn driven from the desktop TUI is likewise a device check; the content half of it is what the
replay-equals-projection test covers.

**Deviations.**

- **Markdown.** [§3](#3-technology-choices) names `multiplatform-markdown-renderer`. This phase ships a
  block parser for the subset an assistant answer uses instead, because it is a pure function (so it can be unit
  tested and `remember`ed off the main thread, per [§5.4](#54-performance-accessibility-and-localization)), because
  it is *total* where a general engine is not — a stray `*` or an unterminated fence renders as text rather than
  eating the rest of the paragraph — and because it adds no dependency to a phase that has none. The syntax
  highlighting module is the part that is genuinely missing and should be taken from the library when the code
  blocks grow; `CodeBlock` is where it goes.
- **Attachment thumbnails.** A user message carries a `data:` URL of base64 the server stored. The chip names the
  file; decoding and showing the image is Phase 5's image pipeline, so the renderer says what it is rather than
  pretending.
- **`session.instructions.updated`** carries no `text` in 2.0.18, so, as in the reference client, it does not
  insert a system notice. The `System` and `Skill` renderers exist and are covered by the fixtures the recorder
  drives.

---

### Phase 3: Driving sessions, the MVP (L)

**Goal.** Real work from the phone: start and steer sessions, answer the agent, and stay in control.

**Builds on.** The P2 stores and timeline. Every action here is confirmed by events that P2 already handles.

**Features**

- **New session.** Pick a location from projects, recent directories or a directory browser (`fs.list`), then an
  agent and a model. `session.create`, with an optional title.
- **Composer (text).**
  - `session.prompt` with a client-generated `msg_…` ID for safe retries.
  - **Steer** is the default; **Queue** is available through a long-press or toggle. `resume` is supported.
  - Optimistic pending items come from `session.inbox.enqueued`.
  - An inbox panel lists items, cancels them, and switches their delivery between queue and steer.
- **Control.** Interrupt, optionally resuming steering input (`session.interrupt`), and **Background** for blocking
  tools (`session.background`).
- **Agent picker.** Visible primary agents with their colors and descriptions, `session.switchAgent`, and a cycle
  button.
- **Model picker.**
  - `model.list` grouped by provider, with search.
  - Capability badges (tools, image input), context size and cost.
  - Variants, set through `Model.Ref.variant`.
  - Recents and favorites stored on the device.
  - `session.switchModel`, and `model.default` as the initial selection.
- **`RequestCenter`, the permission side.**
  - Pending requests are aggregated from `permission.asked`, `permission.request.list` and
    `session.permission.list`, and they survive resync.
  - A request dock in the session shows the action, the resources, the tool context (linked through `source`) and
    metadata such as the command or an edit preview.
  - Replies are once, always (showing the `save` patterns that will be stored) or reject, with optional feedback
    (`session.permission.reply`).
  - A global pending-requests inbox covers all sessions.
- **Forms engine.**
  - A renderer for every field type: string (with options and custom input), multiselect, boolean, number and
    integer, and external links.
  - Supports `when` conditions and validation (required, pattern, minimum and maximum).
  - Reply and cancel through `session.form.*`. Pending forms load through `form.list` and `session.form.list`.
  - Presentation depends on `metadata.kind`:
    - `question` renders inline beside its tool card.
    - `websearch.provider` renders as a consent dialog.
    - `mcp-elicitation` renders as a sheet naming the server.
- **Session management.** Rename and edit metadata (`session.update`), delete with a warning about child sessions
  (`session.remove`), and copy a message or the whole transcript.
- **Status and errors.**
  - Busy and retry indicators, with a countdown (`next`).
  - Provider action links from `session.status` retry actions, such as usage-exceeded.
  - Structured error cards.
  - A "no model available" empty state with instructions. Full provider login arrives in P8.

**API.** `session.create`, `session.update`, `session.remove`, `session.switchAgent`, `session.switchModel`,
`session.prompt`, `session.interrupt`, `session.background`, `session.inbox.cancel`, `session.inbox.update`,
`permission.request.list`, `session.permission.list`, `session.permission.get`, `session.permission.reply`,
`form.list`, `session.form.list`, `session.form.get`, `session.form.reply`, `session.form.cancel`, `fs.list`.

**Exit criteria.** Against the fake provider in CI and a real provider by hand, entirely from the phone: create a
session, prompt, approve a shell permission, answer a question, queue a follow-up, interrupt, and resume.

**Status.** Complete. Verified with 377 JVM unit tests (108 of them added by this phase) and 16 integration tests
(5 of them added by this phase) against a real 2.0.18 server started by `scripts/dev-server.sh`, Android Lint
clean, and both `play` and `fdroid` debug APKs assembled. The criterion is a scenario rather than a function, so
it is driven end to end: `LiveDrivingIntegrationTest` creates a session, prompts it, blocks the turn on a
scripted question, parks a queued follow-up, switches its delivery to steer, interrupts the live execution with
`resume=true`, lets the follow-up land, and then compares the transcript the app assembled against the server's
own projection. A second test asks for a shell permission through session-scoped rules, answers it once, and
asserts the tool then ran; a third answers a question form and checks the server recorded the answer; a fourth
proves every driving operation is reachable and that a location resync adopts the server's pending requests.
Every wait is bounded and names what it was waiting for.

Driving that scenario against a live server found four things no unit test would have:

- `session.interrupt` answers with a bare `SessionInterruptResponse`, not the `{data: …}` wrapper every other route
  uses. The spec says so and the server confirms it; wrapping it failed to decode.
- `session.inbox.delivery.changed` had no reducer handler at all, because Phase 2 only read the inbox. Switching a
  parked prompt from queue to steer silently did nothing.
- The `form.*` and `permission.*` events named a session without implementing `EventPayload.SessionScoped`, so
  nothing that dispatches by session could see them.
- A queued prompt on an *idle* session is delivered at once, and `interrupted` is false unless there is a live
  execution to interrupt. Both are the server's documented rules, so the scenario blocks the turn on a question —
  the only window in which a parked prompt and a real interrupt both exist.

**Not verified here, and why.** The criterion says "entirely from the phone", and there is no emulator and no
device here, so the physical half of it is unverified: real touch input, the camera-free QR path, on-device
rendering at real densities, TalkBack, and "a real provider by hand". The scripted fake provider stands in for a
real one, and a provider that streams differently — tool calls in several deltas, a permission on a real key
exhaustion, an MCP server that elicits mid-answer — is exactly what a real provider adds and what a fake one
cannot. The 1.5x Roborazzi baseline is the closest available proxy for a large-display, large-font reading of
these screens, and it is what caught the clipped "Reject" button. The remaining device work belongs to the manual
matrix in [§5.3](#53-testing-strategy).

**Deviations.**

- **The session screen is composed in the app module.** A feature depends on the core modules only (see the feature
  convention plugin), and the session screen is genuinely made of three features: the timeline and its management
  in `sessions`, the composer and the pickers in `composer`, the dock and the form renderers in `requests`. Putting
  the wiring in the navigation host is the only arrangement that keeps the rule, so the app module holds
  `SessionHost` and `NewSessionHost` and holds no logic: the state is the server's projection and the actions are
  the operations.
- **One Retrofit interface, not one per tag.** [§4.1](#41-modules) describes API interfaces per tag. `ServerApi`
  keeps its Phase 1 and Phase 2 methods and gains the Phase 3 ones in a marked section, because splitting them
  means either two Retrofit instances over the same OkHttp client — two generated proxies, one connection pool, no
  behavioural gain — or changing the factory's contract that every caller injects. The section comments mark
  where the split would go.
- **Integration configuration is readable from a Gradle property** as well as the environment. A long-lived Gradle
  daemon does not see the environment of a client that starts after it, and a run that silently skipped is worse
  than a run that failed; CI sets the environment and is unaffected.

**Known limitations.**

- The model picker's favorites and recents are per server and live in DataStore, which is the one piece of model
  state the client owns. They survive a reinstall only if the preference file does, and they are not shared between
  devices: the server has no idea what a given user reaches for.
- A permission request whose `save` is empty cannot be answered "always": the button is disabled, because there
  would be nothing to store and the server would reject it. `session.permission.create` is a Phase 10 operation, so
  no request in this phase can be raised any other way.
- The "no model available" empty state explains how to connect a provider and points at the CLI. There is no
  in-app login; that is Phase 8, as the plan says.

---

### Phase 4: Background presence and notifications (M)

**Goal.** Never miss the agent needing you. Keep the connection alive while work is running, and turn requests into
actionable notifications.

**Builds on.** The P3 `RequestCenter`. The P1 connection manager moves into a service.

**Features**

- **`ConnectionService`**, a foreground service.
  - Service type **`dataSync`**, settled by the spike this phase ran; `connectedDevice` was rejected. The
    finding is in the Phase 4 [status](#phase-4-background-presence-and-notifications-m) below.
  - Runs while any session is busy, while any request is pending, or when "always connected" is enabled. It stops
    after a configurable idle grace period.
  - Its ongoing notification lists running sessions and offers an Interrupt action.
- **Notification channels** mirroring the TUI's attention events:

  | Channel | Behavior |
  | --- | --- |
  | Permission (high priority) | **Allow once / Allow always / Reject** actions call the API through a `BroadcastReceiver`. "Allow always" asks for confirmation. |
  | Question / form | Opens the form. A single free-text question can be answered directly with `RemoteInput`. |
  | Turn finished | Succeeded, failed or interrupted |
  | Subagent finished | |
  | Retry / usage exceeded | |
  | Server update available | From `installation.*` events |

- **Notification housekeeping.**
  - Notifications are grouped per server and session.
  - A reply made from another client (`permission.replied`, `form.replied`) cancels the phone's notification.
  - Per-session mute and quiet hours.
- **Unread model.** `session.view` is called when the user actually sees an idle transition. Unread badges appear in
  lists and on launcher shortcuts.
- **Auto-approve mode.**
  - Available per session or globally, optionally time-limited, with a persistent indicator.
  - Implemented client-side, the way the TUI's `autoaccept` is: the app answers `ask` requests with `once`.
  - Configured `deny` rules still hold, because a denied action never produces a request. The alternative, a
    server-side session rule, was rejected: session rules are evaluated last and would override agent `deny` rules.
- **Reliability.**
  - Resync when the app returns to the foreground or the network changes.
  - Guidance for battery optimization and OEM background restrictions.
  - Android 13+ notification permission onboarding.

**API.** `session.view`, plus the P3 reply endpoints called from notification actions.

**Exit criteria.**

- With the phone locked, a permission request shows a notification within about 2 s.
- Approving from the notification shade resumes the agent.
- A finished turn notifies.
- No service runs while nothing is active.

**Status.** Complete. Verified with 515 JVM unit-test executions and no failures — 118 test methods
added by this phase, 41 of them under Robolectric against the real `NotificationManager`, a real
`Service` request and a real Hilt-less `Application`. Android Lint is clean and both `play` and `fdroid`
debug APKs assemble.

**The service-type spike, which the plan left as a P0 debt.** The plan proposed `connectedDevice` with a
`dataSync` fallback and said the final choice would be validated in a spike. The spike was not done; it is
done here, and it went the other way. **`dataSync` is the type.**

- `connectedDevice` is for *peripherals*. At runtime, AOSP's `ForegroundServiceTypePolicy` requires the app to hold
  at least one of `CHANGE_NETWORK_STATE`, `CHANGE_WIFI_STATE`, `CHANGE_WIFI_MULTICAST_STATE`, `NFC`,
  `TRANSMIT_IR`, a granted `BLUETOOTH_*`/`UWB_RANGING`/`RANGING`, or a granted USB device or accessory
  permission. The app is a client of a server the user pointed it at, not of a paired device, so the only
  permission it could honestly declare is `CHANGE_NETWORK_STATE` — for a capability it does not have. It
  *observes* the network (`NetworkConnectivityMonitor`); it never changes it. Declaring a permission to satisfy a
  type check is also exactly what Google Play's FGS declaration is reviewed against.
- `dataSync` describes what the service does: fetching the server's state over the network on the user's behalf. It
  needs no permission beyond `FOREGROUND_SERVICE`, and `FOREGROUND_SERVICE_DATA_SYNC` is the only other one
  declared.
- Its one real cost is Android 15's cap of six hours in any 24-hour period, delivered through `Service.onTimeout`.
  That is a limit this design stays far below, because the service stops within minutes of nothing happening — the
  very property the fourth exit criterion asks for. `onTimeout` stops the service and is a test-free but necessary
  path.

**A second platform constraint the spike turned up, which the plan did not mention.** Android 12+ refuses
`startForegroundService` from the background outside a documented exemption list, and *"a permission arrived over
a socket"* is not on it. So the app cannot start the service in response to the event that makes it necessary while
the phone is locked. Two documented exemptions are reachable, and both are wired: the user's tap on a notification,
and the user having turned battery optimisation off. `PresencePolicy` therefore takes a `StartExemption` rather
than a boolean, and a start the platform would refuse is reported as `Keep(BACKGROUND_START_REFUSED)` instead of
being attempted. The consequence for the first exit criterion is real and is stated below.

**What was built.**

- `PresencePolicy`, a pure function deciding whether the connection belongs in a foreground service: a session busy,
  a request pending, or "always connected" keeps it up; a configurable idle grace period (one to ten minutes,
  two by default) is the only thing that stops it. `PresenceController` projects the stores into the inputs;
  `ConnectionService` carries the answer out and owns the ongoing notification, which lists the running sessions
  and offers an Interrupt. `ConnectionServiceLauncher` is the only thing that asks the platform for a start, and
  it does so through the same policy.
- Seven notification channels, one per attention event the TUI has: permission (high, vibrates), question/form,
  turn finished, subagent finished, retry/usage, server update (from `installation.*`), and the connection's own.
  Channel ids are fixed strings, because the platform keeps a user's channel choice under the id.
- `AttentionReconciler`, the housekeeping: a **reconciler over the state, not a handler over events**. Every way a
  request can go away — answered here, answered on the desktop, cancelled, session deleted, server forgotten — is
  one rule: the request is no longer pending, so there is no slot for it. That is what makes "a reply from another
  client cancels the phone's notification" fall out of the same code as everything else, and it is why a resync
  cannot re-notify about a turn the user has already been told about.
- Notification actions through a `BroadcastReceiver`: allow once, reject, interrupt, open. "Allow always" is
  **two steps** — the first tap posts a confirmation naming the patterns the server will store, and only the
  second sends the reply — because plan §5.2 requires an explicit confirmation and a notification action has no
  dialog to confirm in. A single free-text question is answered from the shade with a `RemoteInput`, and the answer
  goes through the same `FormEngine` validation the on-screen renderer applies; anything with a second field, an
  option list or a positive `minLength` is opened instead.
- Per-session mute, per-server quiet hours and an idle grace period in DataStore, all client-side: a mute that
  travelled to the server would silence the desktop too. Quiet hours suppress information and never a request,
  because a request is not a message, it is work that has stopped.
- The unread model: `session.view` is called when the session screen resumes on a session with an unseen idle
  transition, and only then. The badge is not cleared optimistically — `session.viewed` is the server saying it
  recorded the transition.
- Auto-approve, client-side the way the TUI's `autoaccept` works: per session or globally, always time-limited,
  answering `ask` requests with `once` and never `always`, with a persistent indicator on the ongoing notification
  and a confirmation before it is turned on (§5.2). A configured `deny` rule still holds because a denied action
  never produces a request, and the strongest form of that is the shape of `AutoApprovePolicy`: it has no rule
  input to get wrong.
- Android 13+ notification-permission onboarding, a per-channel list that opens the system's own channel settings
  (the only place a channel can be changed), and guidance for battery optimisation and OEM background
  restrictions that opens the system's list without asking for a permission the app cannot justify.

**Not verified here, and why.** Three of the four exit criteria need a device, and there is no emulator and no
hardware in this environment.

- *"With the phone locked, a permission request shows a notification within about 2 s."* The decision half is a
  test: `AttentionReconciler` puts a raised request on the permission channel in the same dispatch that raised it,
  and `RequestCenter.permissionsById` is read un-derived for exactly that reason (a `stateIn`-derived list runs on
  another coroutine, so a read straight after an event can see the previous list). The delivery half needs a phone:
  it is the event arriving over a socket while the process is alive, and `NotificationManager.notify` from a
  process the system has not killed. On top of that, the spike's second finding applies — if the service is not
  already running, the app *cannot* start it from the background, so this criterion holds when the connection is up
  (the service runs while a session is busy or a request is pending) and not when nothing is active and the user
  has not opened the app.
- *"Approving from the notification shade resumes the agent."* A `PendingIntent` reaching a `BroadcastReceiver`
  and an HTTP call leaving it cannot be exercised without the platform delivering the broadcast. What is asserted:
  the action encodes and decodes round trip, the broadcast is explicit and unexported, every pending intent is
  immutable, the request codes are unique by construction over a corpus of thousands, the reply goes through the
  same `RequestCenter` the screens use (so a retry is the same request), and every wait in the receiver is bounded
  so a slow server fails loudly instead of hanging.
- *"A finished turn notifies."* The state rule is a test — unread, finished, not the session on screen, not muted,
  not quiet — and the channel, group and wording are asserted against the built `Notification`. Whether a shade
  shows it on a real device is the device's decision.
- *"No service runs while nothing is active."* **This one is fully decided by a test**, because the decision is
  `PresencePolicy`'s: idle plus an elapsed grace period is a `Stop`, and the service does stop the connection with
  it. What a device adds is the battery meter agreeing.

**What was found that the plan did not anticipate.**

- The reconciler-over-state shape. Event-driven notification handling needs a cancel for every way a thing can go
  away, and forgets whichever is not listed; deriving the desired set from the state has one rule and none of
  them to forget.
- A `RemoteInput` cannot show which field failed. A question with a positive `minLength` is answerable from the
  shade with one character and would then fail validation, so such a form is opened rather than answered.
- A channel's own settings cannot be changed by the app after creation, so a per-channel switch in the settings
  screen would be a switch that silently does nothing. Each row opens the system's channel settings instead.
- `ShortcutManager.setBadges` is `@SystemApi` and there is no `ShortcutManagerCompat` equivalent, so a
  third-party app cannot put a counted dot on its own icon. See deviations.

**Deviations.**

- **The service type is `dataSync`, not `connectedDevice`.** The spike above. The plan's own words allowed a
  fallback, and the finding is that the fallback is the right one.
- **The notification components live in `feature:requests`, not the app module.** Plan §4.1 gives
  `feature/requests` "Permission and form UI, notifications" for P3 and P4, and a library module can declare
  components. Putting `ConnectionService` and `NotificationActionReceiver` next to the channels, the builders and
  the sink keeps the whole Phase 4 surface in one module, which is also what lets a Robolectric test assert on the
  manifest declarations instead of only trusting them. The permissions are still declared in the app, because the
  app is what is installed.
- **A start the platform refuses becomes a `Keep`.** Rather than catching `ForegroundServiceStartNotAllowedException`
  on every start, the exemption is a required input to the decision, so the refusal is a reasoned answer rather
  than a crash. The cost is that the app relies on the service already running, which the plan's own "runs while
  any session is busy" rule makes the normal case.
- **The launcher badge is a label, not a dot.** `ShortcutManager.setBadges` is `@SystemApi` and
  `ShortcutManagerCompat.setBadges` does not exist in the androidx version this project uses (verified against
  `androidx.core` 1.19.1), so the unread count goes into a dynamic shortcut's short label ("3 unread"), which
  every launcher shows. The counted dot needs a system launcher or a published API.
- **Auto-approve is always time-limited.** The plan says "optionally time-limited"; the choices are 5, 15, 30 and
  60 minutes, and there is no "until I turn it off", because an approval mode with no end is a permission that
  cannot be taken back without noticing.
- **`REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` is not declared.** The permission is for apps whose core function
  breaks under doze. This one stops its service when there is nothing to do, and the guidance opens the system's
  own list, which needs no permission.

**Known limitations.**

- A permission raised by the desktop while the phone is locked, with nothing running and the app closed, cannot
  start the service and therefore cannot notify. The remedies are all user actions — opening the app, tapping the
  notification, or exempting the app from battery optimisation — and the settings screen says so.
- The notification-permission prompt appears in the settings screen rather than at first launch. A first-launch
  prompt for a permission whose value is not yet visible is the pattern this app avoids everywhere else, and the
  settings screen is where the blocked state explains itself.
- The "Allow always" confirmation is itself a notification, so plan §5.2's confirmation has no dialog, a voice or
  a modal — it is one more tap. It does name the patterns, which is the part that makes it a consent rather than a
  formality.
- A per-session mute silences a blocking request too. That is the definition of a mute, but it means a muted session
  can be waiting with the app closed and no notification anywhere; the request is still in the inbox when the app
  opens.
- The ongoing notification is capped at four running sessions and three resources per permission, which is what a
  shade row fits.

---

### Phase 5: Rich composer and context (M)

**Goal.** TUI parity in the composer, so prompts carry the right context.

**Builds on.** The P3 composer and prompt pipeline.

**Features**

- **Slash commands.**
  - `command.list` autocomplete, including MCP prompts (`server:prompt`).
  - Argument entry, then `session.command` with attachments, agents, skills and a delivery mode.
  - Client commands map to actions: `/new`, `/sessions`, `/models`, `/agents`, `/undo`, `/redo`, `/compact`, `/btw`,
    `/diff` and `/editor`.
- **Skills.** A `skill.list` picker that respects `slash` visibility. Skills are attached as `skills[]`, or activated
  (`experimental.session.skill`) when the capability exists.
- **`@` mentions.**
  - Files and directories come from `fs.find`, ranked.
  - The `#20-45` range syntax becomes `file://…?start=&end=`.
  - Agent mentions become `agents[]`.
  - References from `reference.list` (respecting `hidden`) attach as directories.
  - `Prompt.Mention` ranges render as highlighted chips.
- **Attachments from the phone.**
  - Sources: photo picker, camera and files.
  - They are sent as `data:` URIs, downscaled on the device to the server's image defaults (2000 px, 5 MiB Base64),
    with a 20 MiB cap.
  - The app warns when the selected model lacks image input.
- **Shell mode.** `!command` calls `session.shell`, and the output renders as a shell message.
- **Side questions.** `/btw` calls `session.generate` and shows the answer in a sheet with a copy button.
- **Manual compaction.** `session.compact`, with live progress and the streamed summary.
- **Ergonomics.**
  - Per-session drafts, prompt history, and stash (stash, pop, list).
  - A full-screen editor, and large pastes collapsed into a compact chip.
  - Voice dictation.
  - A share-sheet target, "Send to OpenCode", that puts text or images into a new or existing session.
- **Session environment variables.** An editor in session settings, backed by `session.environment`.

**API.** `command.list`, `session.command`, `skill.list`, `experimental.session.skill`, `fs.find`, `reference.list`,
`session.shell`, `session.generate`, `session.compact`, `session.environment`.

**Exit criteria.**

- Every TUI composer feature is available: `/`, `@`, `!`, attachments, `/btw`, `/compact`, history and stash.
- Fake-provider assertions confirm that attachments reach the model.

**Status.** Complete. Verified with 735 JVM unit-test executions and no failures — 220 of them added
by this phase, plus 11 live integration tests against a real `opencode serve` 2.0.18 driven by the
scripted fake provider. Android Lint is clean, both `play` and `fdroid` debug APKs assemble, and
Roborazzi compares 51 screenshots (10 of them new). The second exit criterion is settled end to end:
a file attachment's content is in the recorded provider request.

**What was built.**

- **A mention and autocomplete engine, in pure code.** `ComposerTrigger` decides where a trigger is
  *allowed* — `/` and `!` only at the start of the input, `@` only at a word boundary — and which
  range a completion replaces. `MentionScanner` reads the mentions a prompt carries, including the
  `#20-45` line range, and `CompletionEngine` ranks files, directories, references and agents by how
  sure it is, then by kind, then alphabetically, so the list does not move under a finger as the
  server's ranking changes. `ServerPath` turns a server path into a `file:` URI and into the short
  spelling the text field carries, percent-encoding what a URL cannot hold.
- **`PromptAssembler`, which decides what one send does.** A leading `!` is a shell line, a leading
  `/name` is a server command if the server defines one and an app action if not, and everything else
  is a message. A mention is an agent when it names one and a file otherwise; a file attached twice is
  sent once and keeps its mention range; a client command that takes no arguments refuses an
  attachment rather than dropping it.
- **The attachment pipeline.** `AttachmentPolicy` applies the server's own rules: a format the model is
  never sent is blocked, an image a model declares no input for is a confirmation the user has to give
  (plan §5.2), an `http(s)` URI is refused, and the 20 MiB decoded limit is exact at the boundary.
  `ImageDownscale` decides the dimensions — never up, fitted to 2000 px, halved until the Base64 fits
  5 MiB — and the platform half reads the bounds, decodes with `inSampleSize`, re-encodes and measures
  the result, halving again if the estimate was optimistic. Three sources: the photo picker, the camera
  through a cache-scoped `FileProvider`, and `OpenDocument`.
- **Context awareness as a line of words.** `ComposerContextRow` says what the next send will carry
  and what the text will *mean*, which is the difference between typing `/compact` and pressing send.
  `ComposerProblemRow` says what is stopping the send and offers the one button that clears it.
- **The three file-based catalogs** — `command.list`, `skill.list`, `reference.list` — as
  `SyncedResource`s keyed by location, invalidated by exactly the three events Phase 5 acts on first,
  and re-read on reconnect. `FileSearch` is the debounced `fs.find` behind `@`: one request per burst
  of keystrokes, the previous results kept while the next load runs.
- **History, stash and drafts**, client-side and per server or per session, because the server has no
  concept of any of the three and a draft that travelled to the server would be a state the TUI cannot
  see. `HistoryCursor` remembers the sentence being written while the cursor walks into the past.
- **`/btw` and `/compact`.** The side question answers in a sheet with a copy button and never enters
  the transcript; compaction shows progress and reports a busy session as the conflict it is.
- **A full-screen editor**, and the composer's client commands mapped to the composition root, which is
  the only place that knows the navigation graph.

**Not verified here, and why.** The parts of the two exit criteria that are about a person holding a
phone cannot be decided here, and there is no emulator and no device.

- *"Every TUI composer feature is available."* What is decided: every trigger's rules, every
  completion's contents and ordering, every attachment's verdict, what a send assembles, the history
  and stash state machines, the strings on screen in nine captured states at 1.0× and 1.5×, and the
  manifest the camera and the share target depend on. What a device adds: the real IME — a real
  soft keyboard's composing regions, its autocorrect and its swipe typing all change the caret
  arithmetic that decides which trigger is under it; the picker, the camera app and the documents UI,
  which are three contracts with other apps; and a person reading a completion row at arm's length
  rather than a screenshot of one.
- *"Attachments reach the model."* Decided on a real 2.0.18 server: the request arrives, the server
  reads a `file:` attachment and records its bytes, and the fake provider's own request log contains
  the file's content. Not decided: a real phone's encoder, a real camera and a real content provider,
  and a model that declares image input — the only models this harness can script declare `input:
  ["text"]`, which is why the *negative* case is the one asserted here and the positive one is not.

**What was found that the plan did not anticipate.**

- **`PromptRequest.files` was the wrong shape, and the attachment path could not have worked.** Phase
  3 sent `Prompt.FileAttachment` — base64, mime, source — where the route takes
  `PromptInput.FileAttachment`, which is a `uri` and nothing else (features doc §6, and the spec's two
  distinct schemas). The composer had no attachments to send, so nothing had ever exercised it. The
  two are now separate types, and the wire shape is asserted in a test and confirmed by a live server
  that reads a `file:` URI and hands the content to the model.
- **`fs.find` answers with paths relative to the location, not absolute ones.** Verified against a
  live 2.0.18 server. The P2 rule — never normalize a path the server gave you — still holds, because
  the spelling is kept; but a relative path has to be resolved before it is a `file:` URI, and the
  location is the only base the client has. `ServerPath.resolve` is that function, and the live test
  asserts the server's spelling is relative so the test cannot quietly pass on an absolute one.
- **`experimental.session.skill` is not served by 2.0.18.** The route answers `404` even for a skill
  `skill.list` lists. That is the capability detection the plan asks for working as intended, and it
  is why attaching the skill on the next prompt — which `skills[]` always supports — is the primary
  path and activation is a bonus.
- **A text-only model does not receive a `data:` image, and the server accepts it anyway.** Verified
  live: the picture is stored on the user message, and nothing in the provider's request mentions it.
  So the composer's warning is not a precaution, and "Send anyway" is asking the user to agree to
  something the model will not see.
- **A completion list is rebuilt on every keystroke, so its order is a correctness property.** Two
  entries that match equally well must have a stable order, and the natural string order puts
  `Apple.kt` before `a.ts` because upper case sorts first. The engine sorts case-insensitively.

**Deviations.**

- **`onTextChange` carries the caret.** `ComposerBar` takes `(String, Int)` rather than the `String`
  Phase 3 had. Material's field does not hand the caret to a `String`-valued `onValueChange`, and the
  caret is what decides which trigger the list is completing: a mention in the middle of a sentence is
  not the one at the start of a line.
- **The composer's state flow is `Eagerly`, not `WhileSubscribed`.** The view model reads it while no
  screen is collecting, to compute completions from the current catalogs; a derived flow that is not
  running would answer with its initial value and offer a stale catalog. The cost is one collector for
  the composer's lifetime, which is the screen's lifetime.
- **`/undo`, `/redo` and `/diff` are not in the palette.** They are real TUI commands and they are
  Phase 6 operations (`session.revert.*` and `session.diff`). A palette row that cannot do what it says
  is worse than a missing row, so they arrive with the operations.
- **The seven new controls are icon buttons on the send row, not chips.** Six more chips in a row that
  already scrolls is a row whose last items nobody finds, and the paperclip is the most-used of them.
  Every button is 48 dp and carries a content description, because an icon with no name is unusable
  with TalkBack.
- **The camera needs a `FileProvider` in the app manifest.** It is declared with `cache-path` only: a
  capture that outlived its prompt, or that another app could write to, would be worse than no camera.
  `CAMERA` stays a runtime permission the app asks for only when the user picks the camera.
- **`/new` navigates to the home with the new-session sheet already open.** The new-session flow is a
  sheet over the home rather than a destination, so `HomeRoute` grew one boolean rather than the
  composer growing a knowledge of the graph.

**Known limitations.**

- **A mention inside a fenced code block is still a mention.** The composer is not a Markdown editor
  and does not track fences, so a fenced `@decorator` completes like any other token. The TUI has the
  same property; nothing about the request is wrong, only the offer.
- **A `#` that is not a line range stays part of the file name.** `@src/a#b.ts` attaches `src/a#b.ts`
  rather than `src/a.ts`, because dropping the fragment would attach a different file than the user
  named. `@src/a.ts#45-20` is a backwards range and is treated the same way.
- **The Base64 budget is enforced by re-encoding, at most six times.** A picture whose content
  compresses worse than the estimate is encoded, measured, halved and encoded again. Past six halvings
  the result is what it is, and the policy checks the real bytes: over 20 MiB decoded is blocked with a
  reason rather than sent.
- **The photo picker offers images and `OpenDocument` offers everything.** A PDF picked through
  `OpenDocument` is accepted, classified as a format the model is not sent, and blocked in the
  composer rather than rejected by the picker — which is the honest place for it, because the rule is
  the server's and the picker does not know it.
- **The `@` list needs the server.** There is no local filesystem to search, so `@` completion of a
  path is exactly as good as the last `fs.find` and no better. A reference and an agent complete
  offline, because those catalogs are already loaded.
- **A draft is written after a pause and lost if the process dies inside it.** A DataStore write is a
  write, and a phone that is killed inside 400 ms of the last keystroke loses that sentence; the
  window is the price of not writing a preference file per keystroke.
- **The share-sheet target is declared and its filter is tested; nothing routes a shared payload into
  a session yet.** `MainActivity` already handles a `SEND` intent for pairing links, and the composer
  accepts text and an image, but the wiring that opens a new or existing session from a shared image
  is Phase 6's "review comments" work, which is where a shared text or diff is most useful.

---

### Phase 6: Review, diffs, files and history control (L)

**Goal.** Understand and control what the agent changed.

**Builds on.** The P5 composer (restoring prompts, attaching files and comments) and the P2 timeline.

**Features**

- **Diff engine and viewer.**
  - Parses unified patches (`FileDiff.patch`) into hunks.
  - Syntax highlighting and a wrap toggle.
  - Unified view on phones; split view on tablets and in landscape.
  - A file tree, next and previous file and hunk, and a mark-reviewed state kept on the device.
- **Review scopes**, matching the TUI's `/diff`:

  | Scope | Source |
  | --- | --- |
  | Last turn | `session.diff` with `from` and `to` |
  | Uncommitted | `vcs.diff mode=working` |
  | Committed | `vcs.diff mode=committed` |
  | All | `vcs.diff mode=branch` |

  A base-branch picker uses `vcs.base`, `vcs.branch.list` and the `base` override. A VCS header shows the branch
  (`vcs.get`) and the changed files (`vcs.status`), and `vcs.branch.updated` keeps it live.
- **Changed files per message.** `assistant.snapshot.files` and tool `metadata.files` link into the viewer.
- **Review comments.** Select lines in a diff or file, write a comment, and it is attached to the next prompt.
  Comments use the web app's `metadata.opencodeComment` format, plus readable text.
- **Undo, redo and revert.**
  - Stage the revert with `revert.stage` (`files: true`). Before staging, interrupt the session if it is busy and
    cancel pending user inbox items.
  - Put the reverted prompt back into the composer (text, attachments and comments).
  - Show a staged banner listing the restored files (`Session.Revert.files`).
  - Redo with `revert.clear`. On the next submit, commit with `revert.commit` and then send the prompt.
  - "Revert to here" is available from the message menu.
- **Fork.** Fork from a message with `session.fork` (`before`), and show the fork origin.
- **File browser.**
  - Navigation with `fs.list` (including directories outside the location) and quick open with `fs.find`.
  - A viewer through `fs.read`: highlighted text with line numbers, images, and share or download for binaries.
  - Live refresh from `filesystem.changed`.
  - "Attach file" and "attach lines" actions.
  - Optional editing through `experimental.fs.write`, behind a setting.
- **History tools.**
  - Jump between user messages, and search within a session.
  - Export with `experimental.session.export` (sanitize option) as JSON or Markdown, and import with
    `experimental.session.import`.
  - A context inspector backed by `session.context`.

**API.** `session.diff`, `vcs.get`, `vcs.base`, `vcs.status`, `vcs.branch.list`, `vcs.diff`, `session.revert.stage`,
`session.revert.clear`, `session.revert.commit`, `session.fork`, `fs.read`, `experimental.fs.write`,
`experimental.session.export`, `experimental.session.import`, `session.context`.

**Exit criteria.**

- A comment left on the last turn's diff reaches the agent.
- Undo restores files, the edited prompt can be resent, and redo works.
- Forking from a message works.
- A file can be browsed and attached with a line range.

**Status: complete.**

All fifteen operations are in `ServerApi` and every one of them is called. What is verified and what is
device-only is set out below; nothing in this phase is claimed on the strength of a compile.

**What was built.** A pure patch parser (`UnifiedDiff`), a file tree, review navigation, a comment model in the
web app's `metadata.opencodeComment` format, a staged-revert plan, and the two stores the operations go through
(`ReviewStore`, `VcsStore`, `RevertCommands`, `FileReader`). On top of them: the review screen (four scopes, a VCS
header, a changed-files tree, a comment badge and list, unified and split views, wrap, mark-reviewed), a file
browser with a real viewer, and a history panel (jump, search, export, import, context inspector) with the
experimental switches that govern it. Phase 7 reuses the diff rows, the code highlighter, the file viewer and
`FileReader`; §6's table lists them.

**What was verified, and how.**

- *884 unit-test executions, 0 failures* (`./gradlew unitTest`, one variant per module, both app flavours). This
  includes the patch parser's total-parse cases, the comment format's round trip, the review's operations over a
  `MockWebServer` with the real `ServerApi`, and 22 Roborazzi baselines in the review module.
- *39 integration-test executions, 0 failures, 0 skipped* against a live `opencode serve` 2.0.18 with the
  scripted provider, of which 11 are Phase 6's (`LiveReviewIntegrationTest`).
- `./gradlew lintDebug` and `./gradlew assembleDebug` (both flavours) green.

**What was found that the plan did not anticipate.**

- **A `FileSystem.Entry` carries a path and a type and nothing else** (the spec's `FileSystem.Entry` has
  `additionalProperties: false`). So a file viewer cannot refuse to fetch a file it thinks is too large, and an
  estimate from the name is wrong on exactly the file a user opened. The cap is therefore on what is *drawn*
  (`VIEWER_MAX_LINES`, 2,000) and the viewer says how many lines it left out.
- **`fs.read` is `application/octet-stream`, and the bytes decide what a file is** — except when the server has
  already said it is an image. `FileReader.classify` tested the bytes first, which classified every picture as a
  binary and made the image branch unreachable; the content type decides first, now.
- **`sanitize` is a string on the wire** even though the schema calls it a boolean, and `experimental.fs.write`
  answers `415` for `text/plain` and `200` for `application/octet-stream`. Both are asserted on the request, not on
  a mock, because a mocked interface agrees with whatever the test was written against.
- **A one-line comment's wire form cannot say where the range ended.** The web app's `selection` is the bare
  number `1`, which is ambiguous between "line 1" and "lines 1 to the end of the file". The client keeps the
  explicit end (so the attachment carries `?start=1&end=1`) and the wire says `1`; the test asserts the round trip
  and says so.
- **The fake provider's request log is cumulative and outlives the build.** A test that asked "is this marker
  anywhere in the log" passed on its second run by matching a request from the first. Both live test classes now
  record where they start in the log and read only what follows; without that, "a comment reaches the agent" was
  not a claim.

**Decisions.**

- **Syntax highlighting is hand-written, not `multiplatform-markdown-renderer`'s module.** The full argument is on
  `CodeHighlighter`: the unit this phase needs is a *line*, not a document — a diff renders a line at a time in a
  `LazyColumn` with its own gutter, numbers, selection and comment anchors, and a document renderer cannot hand
  those back. The function is total (a half-open string literal, a block comment the previous line opened, a
  shebang: all produce runs, none throw) and carries state only where a language needs it (`LineCarry`), which a
  diff must not thread and a file must. The cost is admitted on the same declaration: no per-language grammar, so
  a `SELECT` is not a keyword in SQL. `highlight` is the seam to replace if that ever matters.
- **The review, the file browser, the comment list, the history panel and the base picker are sheets**, and each
  one keeps its content in a separate composable from the sheet. A `ModalBottomSheet` animates in, so a
  screenshot of the sheet would photograph an empty rectangle; separating them is what makes the baselines worth
  having.
- **The split/unified choice is measured, not declared.** `ReviewUiState.split` is a three-state answer —
  `true`, `false`, or "this screen cannot tell" — and the app module settles it from the real window width
  (`BoxWithConstraints`, 840 dp, Material's expanded width class). A screen that guessed a layout from a default
  would be wrong on exactly the devices the rule is about.
- **The experimental switches are a preference of their own, off by default**, and the review *reads* it rather
  than taking a boolean a screen could pass. A switch says the route may be called; `CapabilityPolicy`'s answer
  from the first `404` says it can be; both must agree before a write happens.
- **Undo, redo and fork declare no strings in the review module.** Their UI belongs to the timeline (the per-
  message actions) and the composer (the staged-revert banner), and a second set of sentences here would be a
  second thing to translate and a second thing to disagree.

**Exit criteria, one by one.**

| Criterion | Result |
| --- | --- |
| A comment left on the last turn's diff reaches the agent | **Verified.** `LiveReviewIntegrationTest` sends a prompt carrying a ranged `file:` attachment and `metadata.opencodeComment`, asserts the server stored the metadata the web app reads, and asserts the provider's *own request log* contains both the comment's words and the file's bytes. |
| Undo restores files, the edited prompt can be resent, and redo works | **Verified.** The stage's ordering (interrupt, cancel queued user input, then stage) is asserted call by call over a `MockWebServer`, and live: the stage, the restored file on disk, the prompt back in the composer, and `revert.clear` taking it back. The *resend* half is the composer's commit-then-send, which is P5's `ComposerViewModel` path and is unit tested; the two together are not driven as one user gesture on a device. |
| Forking from a message works | **Verified.** `session.fork` with `before` is called, the new session id is what the graph navigates to, and the server's `Session.fork` boundary is what the header reads to say where the copy came from. Live and over the fake server. |
| A file can be browsed and attached with a line range | **Verified.** `fs.list` (including a directory outside the location), `fs.read` and the ranged attachment, live: the flagged lines are in the provider's request. The `fs.find` quick open and the line-range selection are unit tested and photographed. |

**Known limitations.**

- **A comment can be removed but not edited.** Its text becomes a prompt the agent reads, so changing it after the
  fact would leave the review and the prompt disagreeing about what was asked for.
- **The file viewer draws the first 2,000 lines and says how many it left out.** It does not page, and there is no
  "jump to line".
- **An image is decoded with `BitmapFactory`, not Coil.** The bytes arrived in memory and never touched the disk,
  and a viewer that wrote a preview to storage would be creating files on the user's behalf for a file they only
  looked at. A decode that fails falls back to the same sentence a binary gets. Coil earns its place when a
  *remote* image is shown, which is P8's `websearch` and P10's attachments.
- **The split view is a threshold, not an adaptive layout.** There is no list-detail pane and no foldable posture;
  Phase 10's adaptive work is where that belongs.
- **The search is a substring match over the formatted message.** It has no ranking, no regex and no highlighting
  in the timeline, and it runs on the transcript already in memory rather than asking the server — there is no
  route for it.
- **Markdown is the one export format this client builds.** The route returns `SessionTransfer.Data`; Markdown is
  rendered here through the same `TranscriptFormatter` that "copy transcript" uses, so the two cannot disagree. An
  import accepts only what the route returns.
- **A prompt history is searched, not navigated by date.** "Jump" moves between user messages; there is no
  calendar and no per-day grouping.
- **`experimental.fs.write` replaces the whole file.** It cannot apply a patch, so an edit that conflicts with
  what the agent wrote in the meantime overwrites it; the confirmation says this in as many words, and the file is
  re-read afterwards so a write that reported success and changed nothing is reported rather than assumed.
- **Split, the file tree, the comments and the history are all one view model each, not one.** A review of a
  hundred files and a search field are different amounts of content, and the state that shares them is the
  `ReviewUiState` the baselines are taken from.

**What only a device can settle.** The plan's manual matrix (§5.3): a real phone and tablet, Android 8 to latest,
HTTP and HTTPS, TalkBack over the diff and the file viewer, dynamic type at 2× on the diff rows (the baselines
cover 1.5×), a real camera and a real content provider, and the share and download grants as another app sees
them. The Roborazzi baselines are Robolectric's software renderer at 411 dp × 2,000 dp, which is a proxy for a
phone and not a phone.

---

### Phase 7: Subagents, shells, terminals and worktrees (L)

**Goal.** Operate the full execution surface: child agents, background processes, interactive terminals and parallel
worktrees.

**Builds on.** The P2 session tree, P4 notifications (for subagent and shell completion) and the P6 file and diff
components.

**Features**

- **Subagents.**
  - The session family tree, from `session.list` with `parentID`.
  - Subagent tool cards open the child session (`metadata.sessionID`).
  - Parent, child and sibling navigation.
  - A strip of running subagents in the composer, with an interrupt action per child.
  - Background command runs (`subagent: true`).
- **Shell commands.**
  - A shell panel per location: `shell.list`, `shell.create`, `shell.get`, `shell.output` and `shell.remove`.
  - Streaming output: poll `shell.output` by cursor while the command runs, until `shell.exited`.
  - Kill. Background shells started by the agent are visible too.
- **PTY terminals.**
  - `pty.list`, `pty.create`, `pty.get`, `pty.update` and `pty.remove`.
  - WebSocket connection with Basic auth, or with a ticket (`pty.connect.token`) when the WebView connects directly.
  - Replay plus the cursor meta frame, and reconnection from the saved cursor.
  - xterm.js in a WebView.
  - Input: an extra-keys row (Esc, Tab, Ctrl, Alt, arrows, `|`, `~`, `/`) and hardware keyboards.
  - Resizing on layout changes (`PUT size`), copy and paste, and pinch-to-zoom.
  - Titles follow `pty.updated`. Shell choices come from `config.shells`, and a quick action runs the project's
    start command.
- **Persistent PTYs** (experimental, gated by capability detection). Session terminals: list, create, get, resize,
  remove, connect, snapshot and read. The host-lifecycle routes (`shutdown`, `handoff`) appear only as admin actions
  on the server status page.
- **Worktrees.**
  - `worktree.list`, `worktree.create` (name, branch, source), `worktree.remove` (force) and `worktree.refresh`.
  - Live updates from `worktree.updated` and `worktree.resolved`.
  - "Move session to a new worktree", and "change directory" (`session.move` with delivery).
- **Project settings.** Name, icon (color, emoji or URL), start command and canonical directory (`project.update`).

**API.** `shell.*` (5 operations), `pty.*` (7), `persistentPty.*` (11, experimental), `worktree.*` (4),
`session.move`, `project.update`, `config.shells`.

**Exit criteria.**

- A dev server can be started and used in a PTY from the phone, and full-screen apps such as `vim` and `htop` render
  correctly.
- A background subagent can be watched, and its completion is notified.
- A worktree can be created and a session moved into it.

**Status.** All 30 operations are implemented and covered by tests over a real HTTP client
(`ExecutionOperationsTest` for the wire, `ExecutionWireTest` for the routes the mocking API would hide,
`PtySocketTest` for the framing against a real handshake). All 11 events are handled. All three exit criteria
are **device-only**; none is claimed as verified below.

**What was built.** `PtySocket` (the WebSocket, the `0x00`-framed cursor, replay and reconnect-from-cursor),
`TerminalChannel` and `TerminalBridge` (the hardened bridge and its codec), `ShellOutputPoller` (cursor paging),
`SessionTree` (the family tree, including orphans and cycles), `ExecutionSurface` and `SessionTerminalStore`
(per-location and per-session state driven by events), and five screens: `TerminalScreen`, `ShellsScreen`,
`SubagentsScreen`, `WorktreesScreen`, `SessionTerminalsPane`, plus `ProjectSettingsScreen`. Project settings and
the two host-lifecycle routes had view models and no way to reach them, and now have destinations.

**What was verified.** Every claim below is a test that was run, and the two that check the same code from
different directions were each broken deliberately to confirm they fail:

| Claim | How | Test |
| --- | --- | --- |
| PTY framing, cursor, replay, reconnect from cursor, `4404` | Real OkHttp WebSocket against `MockWebServer`, with the server driving the protocol | `PtySocketTest` (16) |
| All 30 operation wire shapes | A real `ServerApi` over `MockWebServer`: paths, query names, bodies, statuses | `ExecutionOperationsTest` (32) |
| `DELETE` with a body; `name` as a discriminator; nullable `data`; capability states | The same, on the routes a mocked interface would agree with | `ExecutionWireTest` (24) |
| Shell output cursor paging, truncation, the exited case, the loop stopping | `advanceTimeBy` on a `TestScope`, never an unbounded wait | `ShellOutputPollerTest` (10) |
| Session tree from `parentID`: nesting, orphans, cycles, siblings, the subagent card | A pure function of `SessionRow`s | `SessionTreeTest` (13) |
| Events land in the right store and the wrong one is untouched | `ExecutionSurface.apply` with recorded events | `ExecutionStoreTest` (19) |
| The bridge refuses malformed, unknown and out-of-range messages | The codec as a pure function | `TerminalBridgeTest` (20) |
| Every message the codec produces reaches the host | The screen's relay, with a `WebView` replaced by a list | `TerminalSurfaceTest` (23) |
| Key encoding, modifiers, the plan's row | `TerminalInput.encode` and `ExtraKeys.row` | `TerminalInputTest` (15) |
| The WebView is hardened | `harden()` against a `WebSettings` made permissive first | `TerminalChannelTest` (9, plus 14 baselines) |
| Subagent and shell completion reach `AttentionCoordinator` | The coordinator and its drafts | `ShellCompletionAttentionTest` (8) |

**What was found.** The inherited code had never been built or run. Its own tests did not compile or failed, and
each failure was a real defect rather than a broken test:

- `worktree.remove` was `@DELETE` with a `@Body`, which Retrofit refuses to construct. Every removal failed.
- `WorktreeFailure` defaulted its `name`, so `ignoreUnknownKeys` decoded *any* error body — an
  `UnauthorizedError` included — as a worktree refusal, and the panel offered "force" for a request rejected on
  its credential.
- `terminal/read`'s nullable `data` went through `DataResponse<PersistentPtyScreen?>` and failed to decode on the
  one response meaning "no terminal".
- `TerminalChannelTest` asserted `blockNetworkLoads == false` against a `harden()` that sets it true — the test was
  asserting the page could reach the network.
- **The terminal was dead.** The screen's surface matched `Ready` and `Resize` and dropped everything else, so
  `Input` — the only route from a hardware keyboard or a paste to the socket — never reached `PtySocket`, and
  `onBridgeMessage`, `outputConsumed` and `rename` had no caller. `TerminalSurfaceTest` exists because of this:
  it asserts the forwarding, and was confirmed to fail when the forwarding is removed.
- `SubagentsViewModel.selectParent` returned nothing and the host read the destination back out of a `combine`,
  which may not have republished — so a parent jump could move the selection but navigate to the row it was
  already on. Both now return the id they moved to.
- The shell picker's dismiss button created a terminal; the output pane's "stop following" re-opened the command
  it was following; the project's start command, which the terminal's quick action runs, was always `null`
  because it was read from the route rather than from the project.
- `toActionError` (P4's code) sent an unrecognised error body to `SERVER` and discarded the status, so a `404`
  whose error tag this build does not know read as a server fault. That is the one classification capability
  detection must not get wrong, and `ExecutionWireTest` was confirmed to fail when the fix is reverted.

**Deviations.**

- **Project settings is a destination, not a sheet.** The plan says "Project settings" without saying where. It
  is reached from a session's menu and needs a project id, which `session.list` carries and a route does not, so
  it is a screen with the id in its route arguments.
- **The WebView never opens the socket.** The plan allows Basic auth "or with a ticket when the WebView connects
  directly". OkHttp owns the socket in every case, so the credential never enters the page; `pty.connect.token` is
  the fallback for a *refused* upgrade rather than the normal path, which is strictly safer than the plan requires.
- **The persistent-PTY pane is a fallback surface, not a second terminal.** `terminal/read` returns a rendering,
  not a stream, so the pane shows the screen and a size stepper and says so. The live stream for these terminals
  is the same WebSocket and the same `TerminalScreen`.
- **`pinch-to-zoom` is a font-size change in the page, not a WebView zoom.** `setSupportZoom(false)` and a
  page-level two-finger gesture that steps `fontSize`. A browser zoom cuts lines off the right-hand side, which
  is the opposite of what a terminal needs; whether it feels right is a device question.
- **The 11 `persistentPty.*` operations are gated twice** — the installation's switch and a capability probe —
  and `HostDown` (`503`) is kept distinct from `Absent` (`404`), because one is a service the user can start and
  the other is a version this app does not support.

**Known limitations.**

- **A WebView cannot render in this environment at all**, so nothing here proves xterm.js draws. What is proven is
  everything around it: the framing, the codec, the forwarding, the grid arithmetic, the resize. The terminal's
  *chrome* is a separate composable (`TerminalChrome`) precisely so a baseline can photograph it — a screenshot
  of a `WebView` is an empty rectangle, which P6 learned about a sheet.
- **The extra-keys row is data, not layout**, so the Roborazzi baselines name the keys. Its TalkBack labels are the
  same protocol names (`ESC`, `CTRL`) the plan uses.
- **Selection travels over the bridge and lands on the host's clipboard.** A hardened WebView has no clipboard
  permission to ask for, so a copy cannot be initiated from inside the page; paste needs nothing, because
  `Ctrl`+`Shift`+`V` and the soft keyboard both arrive as `onData`.
- **The session-terminal pane has no live stream.** It shows the last rendering and a checkpoint; attaching to a
  persistent terminal is the location terminal screen's job.
- **The host-lifecycle card is a card, not a page.** Two actions do not justify a destination, and the plan puts
  them on the server status page, which is where they are.
- **No `subagent: true` background command is started from this phase's UI.** The plan lists it under subagents;
  the server decides which tool calls are backgrounded, and this client shows and interrupts whatever the server
  reports. The *strip* of running children is the feature, and it is what the composer's slot draws.

**Building blocks P8 and later get.**

- `PtySocket` — any WebSocket with a resume cursor, a refused-upgrade reason and a bounded backoff.
- `TerminalChannel` + `TerminalBridgeCodec` — a JavaScript bridge that validates every message, with the codec a
  pure function and the four rules tested.
- `TerminalGrid` — measured pixels to a bounded cell count, with a floor of 8 so an unmeasured layout cannot send
  `size {rows: 0}`.
- `ShellOutputPoller` — cursor-paged REST output as a stream, for any endpoint that pages and has no event.
- `SessionTree` — a family projection that tolerates orphans, cycles and partial pages.
- The **two-gate capability pattern** (`ExperimentalPreferences` + a probe that records what it proved), which P8's
  experimental routes reuse.
- `HostLifecycleViewModel` — the shape of an admin action: gated, confirmed, and reachable from exactly one page.

**Driven against a live 2.0.18 server.** Beyond the mocked tests, every operation was exercised over HTTP against
`opencode serve` with the fake provider (`scripts/dev-server.sh`, `2.0.18`). What the server answered:

| Call | Live answer |
| --- | --- |
| `GET /api/shell` | `{location:{directory},data:[]}` — location-scoped, as declared |
| `POST /api/shell` | `{location,data:{id,status:"running",command,cwd,shell,pid,…}}` |
| `GET /api/shell/{id}/output?cursor=0` | `{output:"hello-from-p7\n",cursor:14,size:14,truncated:false}`, and the same page at `cursor=2` — the string cursor is honoured |
| `GET /api/shell/{id}` | `exited`, `exit: 0` — `shell.get` is the only place the exit code exists |
| `DELETE /api/shell/{id}` | `204` |
| `GET /api/pty` + `POST /api/pty` | a real PTY with a pid |
| `POST /api/pty/{id}/connect-token` | `403` without `x-opencode-ticket`, `{ticket,expires_in:60}` with it — the exact asymmetry capability detection has to survive |
| `GET /api/pty/{id}/connect` | `101 Switching Protocols`; a hand-written WebSocket client read the replay as UTF-8 output, a `0x00`-prefixed `{"cursor":613}` control frame, and the shell's own echo of typed input |
| `GET /api/config/shell` | `sh`, `bash`, `zsh` with `acceptable` — the picker's data |
| `GET /api/worktree?projectID=` | a **bare array** with `strategy:"git"`, no location |
| `POST /api/worktree` | `{"directory":"…/worktree/c42aa3/p7-probe"}` — the server chooses the path |
| `DELETE /api/worktree` | `204` for a clean removal, `400` for a force attempt on a worktree that is already gone |
| `GET/POST …/experimental/session/{id}/terminal{,/read}` | `{"data":[]}` and `{"data":null}` — the nullable `data` is real |
| `POST …/experimental/persistent-pty/handoff` | `{"handoff":null}` |
| `PATCH /api/project/{id}`, `POST /api/session/{id}/move` | declared and covered by the wire tests; the live server was not asked to move a real session's directory mid-flight |

So the *protocols* are verified against the real server. The *UI* is not: there is no device.

**The xterm.js bundle ships, and it is tracked.** `feature/execution/src/main/assets/terminal/` holds
`xterm.js` (284 KiB), `addon-fit.js`, `xterm.css`, `index.html` and both upstream licences, and all six are
committed and present in the built APK (`assets/terminal/…`, verified in `app-fdroid-debug.apk`). The npm
checkout they were built from is scratch and is not tracked; `.vendor-tmp/` is git-ignored, and the previous
phase's committed copy of that scratch was removed.

**What only a device can settle.** All three exit criteria. A dev server started and driven in a PTY from a phone,
with `vim` and `htop` rendering; a background subagent watched to completion and its notification arriving; a
worktree created and a session moved into it. Also device-only: real xterm.js rendering, hardware keyboard input,
pinch-to-zoom, real touch on the extra-keys row, on-device WebView performance, and TalkBack over the terminal.

---

### Phase 8: Providers, integrations, MCP and plugins (M)

**Goal.** Manage accounts and runtime extensions without touching the server machine.

**Builds on.** The P3 forms engine (integration forms and MCP elicitation) and P4 notifications (OAuth completion).

**Features**

- **Connect** (parity with `/connect`). The integration list shows each integration's methods and connections.

  | Method | Flow |
  | --- | --- |
  | API key | Key, form answers and label, then `integration.connect.key` |
  | OAuth | Open `url` in Custom Tabs and show `instructions` or a device code with a copy button. `mode=auto` polls the status until complete, failed or expired. `mode=code` asks for the code and calls `complete`. Cancel is available throughout. |
  | Command | Start, show the polled output, cancel |
  | Environment | Read-only, with instructions for setting it on the host |

  - Accounts can be renamed, activated (switch account) and removed.
  - Live updates come from `integration.updated` and `credential.updated/switched`.
  - Well-known integration sources can be added (experimental).
- **Providers and models** (read-only).
  - Providers with their activation state, package and endpoint.
  - A model catalog browser: capabilities, limits, per-tier costs, variants, status and enabled state.
  - Changes that are config-only link to the P9 editor.
- **MCP.**
  - The server list with status.
  - Runtime connect and disconnect (experimental).
  - OAuth for `needs_auth` servers, through the server's integration.
  - Add a runtime server through a form (local or remote, timeouts, `codemode`, protocol), and remove it.
  - A resource catalog with "attach resource to prompt".
  - Live updates from `mcp.status.changed` and `mcp.resources.changed`.
- **Web search.** The provider list and a test query (`websearch.query`).
- **Plugins.**
  - A list with source, features and failure state.
  - Check for updates, and update selected plugins.
  - Live updates from `plugin.updated`.

**API.** `integration.*` (11), `credential.*` (3), `provider.list`, `provider.get`, `mcp.list`,
`mcp.resource.catalog`, `experimental.mcp.*` (4), `plugin.list`, `plugin.check`, `plugin.update`,
`websearch.providers`, `websearch.query`.

**Exit criteria.**

- On a fresh server with no credentials, a provider can be connected by API key and by OAuth, and a turn run on its
  model.
- An MCP server that needs OAuth can be authenticated.
- An outdated plugin can be updated.

**Status.** Complete. All 27 operations are implemented and covered by tests over a real HTTP client
against a `MockWebServer` that answers only the routes the app is supposed to call. The full gate is
green: 1,267 JVM unit-test executions with no failures, Android Lint clean, and both `play` and
`fdroid` debug APKs assembled. 121 of those executions are this phase's; 19 Roborazzi baselines
(light, dark and 1.5× font) are new, and the repository now holds 107.

**Built.**

- **Connect** at `/connect` parity. The method dispatch is
  `dev.opencode.android.core.data.integrations.IntegrationFlows.of`, a pure total function from a
  method to one of `KEY`, `OAUTH`, `COMMAND`, `ENVIRONMENT`, `UNSUPPORTED`; the four flows are one
  sheet whose branch comes from the state and is never re-derived from the method's `type`. A
  method's own `form` is rendered and validated by the P3 `FormEngine`, so an integration login and a
  `question` tool share one set of rules. Accounts are renamed, activated and removed, each behind
  the confirmation plan §5.2 asks for. `experimental.integration.wellknown` is behind its own switch.
- **Providers and models** (read-only). Activation, package, endpoint (read from `settings.baseURL`
  or `settings.baseUrl`), and a note saying where the config-only changes live.
- **MCP.** The server list with its five statuses, the four runtime writes behind capability
  detection, OAuth for a `needs_auth` server *through the integration it names*, the add form built
  by `McpConfigForm` into the wire union, removal with a confirmation, and the resource catalog with
  "attach to prompt".
- **Web search.** The providers and a test query, with the answer naming the provider that *ran*
  rather than the one that was asked for.
- **Plugins.** Source, features and failure state, check for updates, and update the selected
  outdated packages. `plugin.check` replaces the list rather than merging, because a plugin that
  stopped being outdated has no event to say so.
- **The OAuth-completion attention channel** (`attention.auth`), so a user who left for a consent
  screen is told whether it worked.

**Verified here, and how.**

- **Method dispatch.** Every variant of `IntegrationMethod`, decoded from real wire JSON rather than
  constructed, maps to the flow the plan's table names; a fifth type this build does not know maps to
  `UNSUPPORTED` and is not startable.
- **Form validation and answer assembly.** The engine's required-field rule blocks a key login; a
  conditional field is answered only while visible; a method with nothing to say sends no `answer`
  object at all.
- **The `mode=auto` polling state machine.** Fourteen tests on a virtual clock covering pending,
  complete, failed (with the server's own reason), expired, a `404` answer, a transport failure that
  does *not* end the attempt, a cancel that stops the next poll, the command attempt's accumulated
  output, an unknown status folded to pending, and the poll budget. Every wait is bounded and every
  exit is asserted with a post-condition.
- **The device-code copy path.** The pattern, the "last match wins" rule for a retry, and the
  screenshot that shows the chip.
- **Capability detection.** A `404` and a `405` each hide the runtime writes and only them; a `500`
  does not; a success records the route as present; and the user switch is read from
  `ExperimentalPreferences` rather than trusted from a screen.
- **Catalog invalidation.** A burst of three events costs one refetch; `mcp.status.changed` refetches
  the servers and *not* the resource catalog, which is asserted with a wait longer than the debounce.
- **Secret handling.** Eleven assertions, listed under "the credential guarantees" below.
- **Not vacuous.** The two security boundaries were each broken deliberately and the suite was
  re-run: accepting any URL scheme failed `SafeNavigationUrlTest` in three tests, and making
  `Secret.toString` reveal its value failed `SecretHandlingTest` in five. Both were restored and the
  suite re-run green.

**The credential guarantees, and how they are checked.**

- A key is typed into `ConnectUiState.keyDraft` and reaches the request as
  `dev.opencode.android.core.model.Secret`, whose `toString` is `Secret(length=n)`.
  `SecretHandlingTest` asserts the request's own `toString` does not contain the key, that the
  rendered `Secret` does not, that the value appears in the wire body and *only* in the `key` field,
  that no field of `ConnectKeyRequest` whose name matches `key|secret|token|password|credential` is
  a plain `String`, and that a failure built from a write carries none of it. Each assertion is
  written so its own message cannot contain the key.
- The key never touches disk. The server holds the credential; the app holds it in memory between
  the tap and the request, and clears `keyDraft` when the sheet closes.
- The key field is masked with a show/hide toggle, and `SecureWindowEffect` sets `FLAG_SECURE` on the
  window for the sheet's lifetime and *restores the previous value* on dispose, so the task switcher
  and a screen recording cannot capture it and the rest of the app keeps its thumbnail.
- Every server-supplied URL that this client would open goes through
  `dev.opencode.android.core.model.SafeNavigationUrl`, which parses rather than prefix-matches, so
  `javascript:`, `intent:`, `file:`, `content:`, `https:/\evil.example` and a leading control
  character are all refused. `WellknownSourceUrl` and the MCP remote-config URL use the same function,
  and `IntegrationsHost.openProvider` checks once more where the `Intent` is built.
- `instructions` is server text and is rendered as text, never interpreted.

**What the tests found and this fixes.**

- `ActionError` classified `McpServerNotFound`, `IntegrationNotFound`,
  `IntegrationAttemptNotFound`, `IntegrationMethodNotFound` and `ProviderNotFound` as
  `ActionErrorKind.SERVER`. Capability detection reads a `404` as "this route is missing", so a
  `404` from an experimental MCP route did *not* hide the feature: the user would get a failure on
  every tap with no way to tell the feature apart. All five now classify as `NOT_FOUND`.
- A `405` arrives with an empty body and no `_tag`, so `toActionError` had nothing to classify it by
  and it landed on `SERVER` — the same class a `500` gets, which is a route that clearly exists.
  `ActionError` now carries `httpStatus` and `CapabilityPolicy.from(error)` reads the status first.
  This is a prerequisite fix that P3's `ActionError` and P6's `CapabilityPolicy` both needed and
  neither had.
- Seven of the nineteen new Roborazzi baselines were vacuous. `connect-key.png` recorded the account
  list with the key field nowhere in it, because a `ModalBottomSheet` renders into a window a
  Roborazzi capture does not see. The sheet content is now a plain composable the host wraps —
  the arrangement P7 used for its terminal — and the baselines are real. This is the same trap P6
  hit with a sheet and P7 with a `WebView`.

**Exit criteria.**

- *"On a fresh server with no credentials, a provider can be connected by API key and by OAuth, and
  a turn run on its model."* **Device-only, and more than that.** The three halves a JVM test can
  decide are decided: the key reaches `integration.connect.key` on the right path with the right
  body; an OAuth attempt is started, its URL is opened only when it passes the scheme check, and the
  flow ends only on the server's own status; and a completed login re-reads `integration.list`
  rather than adding a credential locally. What is not decided is the round trip: it needs a real
  provider's consent screen in a real browser on a real device, and a real API key. There is no
  emulator and no hardware here, and a real credential is exactly the thing this phase's own
  guarantees forbid putting in a test.
- *"An MCP server that needs OAuth can be authenticated."* **Device-only for the same reason, with
  the client half decided.** What is decided: a `needs_auth` status decodes, the
  `integrationID` that identifies the flow survives decoding, the row offers "Sign in" only when one
  is named, and the host routes that to the accounts screen for that integration. What is not: an MCP
  server actually demanding a grant, which needs a real remote MCP server configured with OAuth.
- *"An outdated plugin can be updated."* **Partly verified, and the split is honest.** The client
  half is decided: an `outdated: true` package decodes, only an outdated *package* is offered for
  update, `plugin.check` replaces the list with the refreshed flags, and `plugin.update` posts the
  `targets` array the route takes. What is not decided is the download and install, which needs the
  npm registry and a real package.

**Not verified here, and why.** A real OAuth round trip through Custom Tabs to a real provider, real
provider credentials, a real MCP server, and an actual plugin update downloading a package. All four
need third-party services and a device. The Custom Tabs `Intent` is built with the standard extras
directly rather than through `androidx.browser`, because that library is not in the version catalog
and a dependency for one intent would be a worse trade than two extras; the effect on a browser that
implements the service is the same, and a browser that does not simply opens the URL.

**Deviations.**

- The Custom Tabs `Intent` is hand-built, as above.
- The MCP add form's command line is split on whitespace and is **not** a shell parser. The route
  takes an argument vector, so a quoted argument would be passed with its quotes; splitting plainly
  means what the user sees is what runs, which is the property that matters for a command on their
  own machine.
- The device-code detector is a regular expression, not a provider protocol. A client that cannot
  know which provider's format a code is in looks for the shape they share and copies the *code*
  rather than the line it was printed on.
- `experimental.mcp.*` and `experimental.integration.wellknown` have **separate** switches. The first
  writes to a table that dies at restart; the second makes the *server* fetch a URL that can add an
  integration the user never configured. One switch would ask the user to consent to something they
  have not been told about.

**Known limitations.**

- "Attach resource to prompt" publishes the pick and the host routes to the pending-requests inbox,
  where the composer is reachable. Carrying the resource into the composer's own attach pipeline is
  Phase 9 work on the composer, not on this screen.
- A `plugin.update` whose result the app has not seen reported (a dropped connection mid-install)
  leaves the row showing whatever the next `plugin.list` says. The store re-reads; it does not
  guess.
- The activity's `FLAG_SECURE` is a window flag, so it also blocks the *user's* own screenshot on
  the key sheet. That is the right way round: a photographed key is in a photo library the server
  has no way to revoke.
- The providers screen is read-only. Activation, endpoint and headers are configuration (features
  doc §9) and changing them needs the Phase 9 editor, so the screen says where they live rather
  than offering a control that would not take effect.
- **A pre-existing timing flake, found while running the gate, not caused by this phase.**
  `./gradlew unitTest --rerun-tasks` — which recompiles every module while the test JVMs are already
  warm — intermittently fails `EventStreamClientTest` (a Phase 1 SSE test: `MockWebServer.close()`
  times out waiting for its queue) and `ExecutionStoreTest.shell exited carries the status and the
  exit code onto the row` (a Phase 7 test). Both pass three runs out of three in isolation and both
  passed in the gate this phase reports. The gate as CI runs it — `./gradlew unitTest lintDebug
  assembleDebug` — is green. It is recorded here because the next person to see it should know it is
  not a regression, and because fixing it belongs to the phases that own those tests rather than
  being folded into a feature phase.

**Building blocks P9 needs.**

- `IntegrationSurface`, one object holding every account, provider, MCP, plugin and web-search
  catalog per `(server, directory)`, with the experimental writes already capability-gated. The
  config explorer reads the same surface and adds to it rather than building a second one.
- `IntegrationFlows.of` and `IntegrationForm`, so a Phase 9 "re-authenticate this provider" action
  opens the same flows this phase built rather than a parallel one.
- `McpConfigForm` and `McpServerDraft`, which are the shape a *persistent* MCP server config file
  will be edited into. The wire union `McpServerConfig` is already the one the schema declares, so
  the editor's output has a target to validate against.
- `ConnectOutcome`, a total state-to-consequence mapping, reusable wherever a long operation ends.
- `AttentionChannel.AUTH_COMPLETED` and `AttentionNotice.AuthCompleted`, so a Phase 9 flow that
  needs a background confirmation has a channel and a slot already wired.
- `SafeNavigationUrl` and `WellknownSourceUrl`, for any P9 route that takes or opens a URL — the
  config editor's "open the file" and any remote source.
- `ActionError.httpStatus` and `CapabilityPolicy.from(error)`, for P9's own experimental routes
  (`experimental.config.update`, `experimental.session.instructions.entry.*`).

**Standing defect, not addressed.** ktlint, Spotless and detekt are in the version catalog and
applied to no module, so the plan's Quality row (§3) is still not enforced. This phase did not wire
them: doing it as part of a feature phase would either turn a long-standing gap into a large
mechanical diff attributed to the wrong work, or fail on hundreds of pre-existing violations that
are not this phase's to fix. It remains the cheapest high-value item on the list.

---

### Phase 9: Configuration and administration (M)

**Goal.** See every configuration-level feature, and change it wherever the server allows.

**Builds on.** The P8 catalogs and P6 file viewing and editing.

**Features**

- **Config explorer.**
  - `config.get` documents in precedence order, with their source paths.
  - A structured view per top-level key (features doc §33.2) that marks effective and overridden values.
  - Policies are shown read-only.
- **Shell setting.** `config.shells` combined with `experimental.config.update`.
- **Config and definition editor** (advanced, experimental, opt-in).
  - Edit `opencode.jsonc`, validated against the vendored JSON Schema.
  - Create and edit agent, command and skill Markdown files, and `AGENTS.md`.
  - Files are read with `fs.read` and written with `experimental.fs.write`. Afterwards the app calls
    `location.reload` and shows the resulting diagnostics.
  - Guided templates cover a persistent MCP server, a permission rule, the default model, and a new agent.
- **Catalog browsers.**
  - Agents: mode, model, permissions, steps, color and system prompt.
  - Commands and their templates.
  - Skills and their content.
  - References.
- **Permissions admin.**
  - Saved approvals per project (`permission.saved.list`, `permission.saved.remove`).
  - A session permission-rules editor (`session.update`), with a precedence warning.
- **Session instructions** (experimental). List, add and remove instruction entries.
- **Maintenance.**
  - Reload locations (`location.reload`).
  - Loaded locations (`debug.location.list/evict`).
  - V1 migration progress.
  - An update-available banner with the host upgrade command.

**API.** `config.get`, `experimental.config.update`, `location.reload`, `permission.saved.list`,
`permission.saved.remove`, `experimental.session.instructions.entry.*` (3), `debug.location.list`,
`debug.location.evict`, `experimental.migration.v1.status`.

**Exit criteria.**

- Every top-level config key is visible with its source.
- The common edits can be made from the phone and take effect after reload: default model, permission rule, MCP
  server, agent file.

**Status.** Complete. All 11 operations are implemented and covered by tests over a real HTTP client
against a `MockWebServer` that answers only the routes the app is supposed to call, plus 12
integration tests against a real `opencode serve` 2.0.18. The full gate is green: **1,474 JVM unit-test
executions, 0 failures, 0 skipped**, Android Lint with **0 errors** in every module, and both `play` and
`fdroid` debug APKs assembled. 207 of those executions are this phase's, over a measured baseline of
1,267. 42 Roborazzi baselines (light, dark and 1.5× font) are new.

**Built.**

- **The config explorer.** One card per top-level key, in the vendored schema's own order, produced by
  walking `api/opencode-2.0.x/config.schema.json` — so the row list is a function of the file and not
  of a list someone typed, and a key nothing sets still has a card that says so. Each card carries the
  key's type, its description, its enum, its source documents and the server's own effective value,
  with a *configured only* filter that changes what is visible and not what the state knows.
- **Two vocabularies, mapped.** A file writes `{"permission": {...}, "agent": {...}}`; the runtime
  reports `permissions`, `agents`, `snapshots`, `providers`, `plugins` and `commands`, adds
  `websearch`, `worktree` and `warming` that no file key owns, and omits eleven keys the file has.
  `ConfigSchema.PROJECTION_NAMES` is the table, written out rather than derived, and
  `PROJECTION_ONLY_KEYS` and `UNPROJECTED_KEYS` name the other two directions.
- **The validated editor.** `opencode.jsonc` read with `fs.read`, edited, validated against the
  vendored draft-2020-12 schema, confirmed, written with `experimental.fs.write`, reloaded with
  `location.reload`, and then read back with `config.get` — with the server's own answer as the
  diagnostic. Validation runs on `Dispatchers.Default` and a stale answer is discarded.
- **JSONC, without reformatting.** `Jsonc.mask` blanks comments and trailing commas **in place**, so a
  diagnostic's line is the line in the user's file; `ConfigDocument.setInText` rewrites only the bytes
  of the key it changes, and a comment above it survives.
- **Four guided templates.** A persistent MCP server, a permission rule, the default model and a new
  agent — each producing a *value for a top-level key* that is merged into the existing document, and
  each validated against the schema before the confirmation is reachable.
- **Definition files.** An agent, a command, a skill and `AGENTS.md`, with the front matter written
  from named fields and the body left as the user's prose.
- **Catalog browsers.** Agents (mode, model, permissions, steps, colour and system prompt), commands
  and their templates, skills and their content, and references.
- **Permissions admin.** Saved approvals per project, and a session's own rules with the precedence
  warning as a fixed card rather than a disclosure.
- **Session instructions.** List, add and remove, with the value typed as JSON and a bare word stored
  as text.
- **Maintenance.** Reload with its consequence spelled out, the loaded locations, an evict behind a
  confirmation that names the directory, the V1 migration, and an update banner carrying the host
  upgrade command.
- **`config.updated` is handled, and it is the one event that cannot be handled narrowly.** Every other
  `*.updated` event names its item or its location, so it can invalidate one resource or one screen.
  `config.updated` says only that *a configuration document somewhere* changed — no payload, no
  location — and such a document can name a model, a provider, an MCP server, a permission rule, an
  agent or a plugin without saying which. So the response is the blunt one: the `config.get` stores, the
  agents, the models, the default models, the composer catalogs and the integration catalogs are all
  invalidated, across every location the client has open. The events that *do* name something stay
  narrow and are handled before it. `ConfigUpdatedEventTest` asserts the scope in both directions:
  that a re-read after the event returns the server's *new* answer rather than the cached one, and that
  an invalidation too narrow to notice a changed agent, command, provider or MCP server would be caught
  rather than shipped.

**The first exit criterion, and how it is proved.**

*"Every top-level config key is visible with its source."* It is checkable because the key list has an
authority that is not this client's memory — the vendored schema — and it is checked against that file
in **both directions**:

- `ConfigCatalogCoverageTest` reads `api/opencode-2.0.x/config.schema.json` at run time and asserts
  that `ConfigExplorer.rows` produces **exactly one row per top-level key, in the schema's own order**,
  and that no row exists for a name the schema does not declare. A row for `permissions` or `update`
  would fail it.
- The same test asserts that every projection name the client claims is a real property of
  `Config.InfoEncoded` **in the OpenAPI spec**, that every property of `Config.InfoEncoded` is claimed
  by exactly one file key or named in `PROJECTION_ONLY_KEYS`, and that the eleven keys with no
  projection are exactly the set the code names.
- `AdminScreenContentTest.every top-level key of the schema can be scrolled to` then does the same check
  on the **rendered screen**: it scrolls the list to each of the 36 keys by index and requires a card.
  A projection test alone would pass with a screen that rendered the first row.
- `UnsupportedKeywordTest` walks the vendored schema and fails on any assertion keyword
  `JsonSchemaValidator` does not implement, which is what makes the subset validator trustworthy rather
  than merely believed sufficient.

The second half of the criterion — *with its source* — is bounded by the API, and the explorer says so
rather than guessing. `config.get` gives every document a **cumulative** projection, so a document that
inherited a value is indistinguishable from one that wrote the same value. The explorer therefore shows
two columns: "reported by" (always exact) and "set in" (exact for the nearest document, which the app
reads with `fs.read`, and explicitly "not known" for the rest). `ConfirmedWriteTest` asserts both
branches.

**Verified here, and how.**

- **The validator**, against the real 39 KB file: a document made of a dozen real keys validates; an
  unknown top-level key, a wrong type, a value outside an enum, a missing required nested key, a
  numeric bound, an integer that is a decimal, a colour pattern, a nested unknown key, an `anyOf` that
  no branch accepts, and every problem at once rather than only the first. The external `models.dev`
  reference is skipped while its sibling `type: string` is still applied, and the skipped reference is
  reported rather than hidden.
- **JSONC**: exact-output tests on a document with a line comment, a two-line block comment, a URL
  containing `//` and `/*`, an escaped quote, and trailing commas before both a `}` and a `]`. Length,
  line count and column are asserted unchanged, and the masking is idempotent.
- **The read-modify-write path**: setting a key rewrites only that key's bytes and keeps the comment
  above it; a nested key of the same name is not mistaken for the top-level one; removing a key takes
  its comma and its gap; a full edit cycle leaves a document that still validates.
- **The wire surface** over a real HTTP client: `config.get` with the location and the server's
  precedence order, a `directory` entry, an entry of an unknown type kept rather than dropped, both
  spellings of `model`, `{"shell": …}` as the *only* body `Config.Patch` accepts, `204` from a reload,
  a `503` raised rather than swallowed, the saved-approval filters, a `PUT` for an instruction entry,
  the `DELETE` with a location parameter for an eviction, and every migration state including the
  `running` one whose counts are individually nullable.
- **The live server**, for the second exit criterion: the default model, a permission rule, a
  persistent MCP server and an agent file are each written, reloaded, and then confirmed **from
  `config.get` and the server's own catalogs** rather than from the app's buffer. A write to a file the
  server does not read is reported as such, and a second edit keeps the first.
- **The write confirmation.** `ConfirmedWriteTest` asserts the plan a dialog renders: the target, a
  consequence that names the file and what happens next, the byte counts, and the privilege flag — and
  that an invalid or unparseable document produces **no plan at all**, so a confirmation with a broken
  file in it is unreachable.
- **The redaction**, at the value, the diagnostic, the parse failure, the plan and the screen. A
  document with an `apiKey`, a `clientSecret` and a credential-shaped `label` is walked recursively and
  no rendered text contains any of them; a diagnostic for a rejected credential-shaped value prints the
  *allowed* values from the schema and a character count, never the value; a parse failure keeps the
  position and drops the document; and `AdminScreenContentTest` collects every text the explorer
  displays, across all 36 scrolled-to keys, and asserts the placeholder is not among them.
- **The experimental gating.** A `404` from `experimental.config.update` hides the shell setting and a
  `500` does not; a `400` from `fs.write` is a failure the user must see and leaves the button enabled,
  while a `404` hides it and attempts no reload; the switch alone never re-enables a route the server
  does not have.

**What the tests found and this fixes.**

- **`ActionFailure` was declared three times**, in the `server`, `execution` and `integrations`
  packages. A failure raised by one subsystem and reported by another lost its classification: an
  `fs.write` rejection came back as `ActionErrorKind.UNKNOWN`, so the screen said "unknown" for a `400`
  that named the offending path. The three names are now typealiases onto one class in the `action`
  package. **This is a prerequisite defect that predates this phase** — the duplication came from P6, P7
  and P8 — and it is the same class of finding as P8's `ActionError.httpStatus`.
- **The JSONC mask looked for a trailing comma *before* the closing brace** rather than after it, so
  `{"a":1,}` masked nothing. A comment-only file was also not "blank", so the editor could not
  distinguish a file with no keys from a file it could not read.
- **`watcher` was missing from the projection mapping** even though `Config.InfoEncoded` reports it,
  and `websearch`, `worktree` and `warming` were mapped to file keys that **do not exist**. All four
  are corrected, and the three are now named as projection-only rather than mapped to a key a user
  could never type.
- **`MigrationStatus` decoded as `Unknown` for every state**: the union's discriminator is `status`,
  not the default `type`; its progress is a nested object rather than three top-level fields; and its
  error branch's field is `error`, not `message`. A server that described its migration perfectly well
  was being shown "this server version did not describe the migration".
- **The config explorer's `LazyColumn` had duplicate keys.** `shell` is both one of the schema's keys
  and the row of the setting above it, and two items with one key is a crash. The fixed rows are now
  namespaced.
- **A `verticalScroll` header with no weight claimed the whole column** in the editor and the definition
  editor, pushing the diagnostics and the save button off the bottom of the screen. Found by the content
  test failing to find "this document is valid" anywhere in the tree; fixed with `weight(1f,
  fill = false)`.
- **A failed reload was swallowed.** A location the server had loaded and that had since been deleted
  makes `location.reload` answer `404`, and the write was reported as a success with the change never
  applied — exactly the failure the report exists to prevent. The write report now says which of the two
  happened, and a definition file is no longer reported as unreadable on the grounds that `config.get`
  would not list it anyway.
- **The front-matter writer did not quote its values**, so a `description` containing a colon would
  parse as a mapping and silently lose the text after it. Quoting is now the writer's job rather than
  the caller's, so a form cannot get it wrong.
- **Two of the first forty-two baselines were vacuous, and a third was a photograph of nothing.** A
  fixture ignored the filter it was given and produced a file byte-identical to the unfiltered one, and
  the write confirmation was captured as an `AlertDialog` — a window a root capture does not see, so the
  privilege and plain baselines were both pictures of an empty background. The same defect P8 found with
  a `ModalBottomSheet`, in a new place. The confirmation fixture now renders what the dialog renders,
  and all forty-two files are distinct.

**The three live findings, which no mock could have produced.**

- **The server reports a permission's action under its own name.** The file says `bash`; the effective
  ruleset says `shell`. The same rule has two names in the two places it appears, and a screen showing
  the file's spelling would look wrong against the server's answer. The explorer shows what the server
  reported, the template writes the tool name, and the difference is recorded in the test.
- **`location.reload` rebuilds the locations the server has *loaded*.** A file written into a directory
  the server has never opened as a location is written successfully and then ignored, and the reload
  does not reach it. Every other integration test uses the harness's own directory for this reason;
  this one found it by failing.
- **The reload answers `204` before the catalog it serves is rebuilt**, so a `listAgents` in the same
  breath as the reload is a race. The integration test polls with an explicit bound and reports the list
  it actually saw rather than failing on the first read. The app does not need this — it re-reads on
  `agent.updated` and on resume.

**Exit criteria, criterion by criterion.**

- *"Every top-level config key is visible with its source."* **Verified**, in both halves the phrase
  has. The key coverage is checked exhaustively against the vendored schema in both directions, both on
  the projection and on the rendered screen, and the source is shown per document with the
  cumulative-projection limit stated on the row rather than papered over.
- *"The common edits can be made from the phone and take effect after reload: default model, permission
  rule, MCP server, agent file."* **Verified on the client, and end to end against a real server** for
  all four: each is written through `experimental.fs.write`, the server is reloaded, and the result is
  read back from `config.get` and the server's own catalogs. What is *not* verified is the part that
  needs a person: that a person can type a valid document on a phone keyboard, and that the reloaded
  server's behaviour changed in the way the file says. The first is the plan's own device-only note
  about a keyboard in a code editor; the second would need an agent turn observed on hardware.

**Not verified here, and why.** Editing on a real device: the phone keyboard in a configuration editor,
a `TextField` holding a large `opencode.jsonc` under a real IME, TalkBack reading the explorer's
thirty-six cards, and observing a reloaded server's *changed behaviour* rather than its changed
configuration. There is no emulator and no hardware in this environment, and the first three are exactly
the things a JVM test cannot decide. The behaviour half is bounded by a real server being reloaded and
re-read, which the integration tests do; what is missing is an agent turn, which is P5's and P3's job to
drive and which needs a provider.

**Deviations.**

- **The JSON Schema validator is a hand-written subset, not a dependency.** It covers exactly the
  assertion keywords the vendored file uses, and `UnsupportedKeywordTest` fails if the file ever uses
  another — so the subset cannot silently become a hole. A dependency was rejected because it would be
  the only one in the project, it would pull a tree-sitter or coroutine parser into an app whose whole
  configuration surface is 39 KB of JSON, and `allowComments`/`allowTrailingCommas` mean the document is
  not JSON anyway and needs masking first regardless.
- **The schema is shipped as an asset, not fetched.** Validating needs the schema; fetching it would mean
  asking a server on a LAN for a URL on the internet, which fails on exactly the network this app is
  built for, and it would mean validating against whatever the server felt like sending rather than
  against the release this client was built for. The build copies the vendored file into `core:data`'s
  assets, so the bytes are the repository's.
- **A text-level edit rather than a re-serialise.** Re-serialising the parsed tree would delete every
  comment and rewrite the indentation, so a user who opened the editor to change one model name would
  come back to a file that no longer has their notes in it. The cost is a hand-written span scanner,
  which is a real cost and is why `ConfigDocument` has the tests it has.
- **Catalog browsers are composed in the app module.** `feature/admin` may not import the modules that own
  the agent and composer catalogs, so `CatalogHost` takes the four lists as parameters — the same
  crossing `McpHost` makes for an MCP server's OAuth.
- **A template produces a value for a top-level key, not a whole file.** A template that replaced the
  document would destroy the user's comments and their other keys, which is a worse outcome than the one
  the extra merge step costs.
- **`experimental.config.update` and `experimental.fs.write` have separate switches.** Both write
  configuration; only one changes what the agent can execute. `shell` is the one key the server will
  change for us, and a user who will not let the app edit their files has not said no to picking a shell
  from the list the server itself reported.
- **The permission-rules editor's session rules are a whole-ruleset `session.update`**, because the
  route replaces the list rather than patching it, and an order change is a privilege change. That is
  stated on the screen next to the save button.

**Two things this phase found about its own tests.**

- **The screenshot baselines were a recorder, not an assertion.** `AdminScreenshotTest` calls
  `captureRoboImage` and nothing else, so under the plain `unitTest` gate Roborazzi neither wrote the
  files nor compared against them: all 42 tests passed while the committed PNGs were stale, and a
  visual regression would have passed as readily. The files were also not being refreshed by a test run,
  which is how a green gate and a stale baseline coexisted unnoticed. They are now recorded with
  `:feature:admin:recordRoborazziDebug` and checked with `:feature:admin:verifyRoborazziDebug`, which
  is a real comparison, and it is against those files that the 42 images are confirmed distinct and
  non-blank. **Neither task is part of `unitTest lintDebug assembleDebug`**, so the gate does not
  enforce them: a later phase can change a screen's appearance and the gate will not notice. Wiring
  `verifyRoborazziDebug` into the gate belongs to the phase that owns the build, and is recorded here
  rather than quietly fixed inside a feature phase.
- **Model identifiers in fixtures.** A sweep found 61 model and vendor identifiers in this phase's own
  test fixtures and documentation. None is load-bearing: the schema constrains `model` to
  `type: string` and skips the external `models.dev` reference, so any string satisfies it equally
  well. They are now `placeholder-provider/placeholder-model`, `other-provider/other-model` and
  `small-placeholder-model`. Fifteen of the 42 baselines changed as a result and were re-recorded. The
  vendored `api/opencode-2.0.x/config.schema.json` is deliberately untouched: it is the server's own
  published schema, and editing it would make this repository's copy differ from what a real server
  validates against.

**Known limitations.**

- **The "set in" column is exact only for the nearest document.** The API returns a cumulative
  projection, and reading every document would be a read per file in the chain; the nearest one is read
  and the rest say "not known". A user asking "which of my three files set this?" gets an answer for
  one and a statement of the limit for the others.
- **The editor is a plain monospace field with no syntax highlighting.** A phone keyboard cannot type
  indentation reliably and a code editor is a project of its own; the plan's own device-only note
  acknowledges this. What the screen does have is an honest validity verdict at every keystroke.
- **Diagnostic line numbers are attached only when the key is unambiguous.** A JSON pointer says which
  key is wrong, not where it was typed; a key that appears twice gets no line rather than a confident
  wrong one.
- **A configuration file the app cannot read leaves the explorer complete but the "set in" column
  unavailable**, which is the common case for a project with no `opencode.jsonc`.
- **The definition editor has no schema.** A Markdown file's validity is the server's business, and
  inventing a validator for prose would reject files the server reads perfectly well. The consequence is
  that malformed front matter is caught by the server's reload rather than by the app.
- **A pre-existing timing flake, found while running the gate, not caused by this phase, and now a
  third instance of the same shape.** `--rerun-tasks` intermittently fails `EventStreamClientTest` and
  `ExecutionStoreTest.shell exited carries the status and the exit code onto the row`, as Phase 8
  reported; this phase also saw `PairingAndReconnectTest.aPairingLinkBecomesAConnectedServerWithAStoredCredential`
  fail twice in three `--rerun-tasks` runs with `One server.connected means one resync expected:<1> but
  was:<0>`. All three pass in the gate this phase reports. The new one is the same defect: the test
  waits for `connectionState` to become `Connected` and then reads `resyncCount` with no wait of its
  own, while a sibling assertion ninety lines below does wrap the same read in
  `withTimeout(TIMEOUT_MILLIS) { connection.resyncCount.first { it >= 1L } }`. It is a missing bounded
  wait, not a weakened assertion, and it is left as found per the standing instruction; it belongs to
  the phase that owns that test.

**Building blocks P10 needs.**

- `ConfigSchema`, `ConfigExplorer.rows` and the thirty-six-key catalogue, so a stats screen or an
  insights panel that wants to explain a configuration has the same row model and cannot disagree
  with it.
- `ConfigRedaction`, the one function a screen may call to render a configuration value, so no P10
  screen invents a second rule for what a credential looks like.
- `JsonSchemaValidator` and `ConfigDocument`, for reading a pasted configuration in an RPC console or a
  quick-ask form, off the main thread and with the same masking the editor uses.
- `ExperimentalRoute.CONFIG_UPDATE` and `SESSION_INSTRUCTIONS`, `fsUsable` and `planSetting` on
  `ConfigSurface`, and `WritePlan` with `WriteConfirmationDialog`, so a P10 action that changes
  configuration gets the confirmation and the capability gate for free.
- `DefinitionKind`, `DefinitionName` and `DefinitionTemplates`, for anything that offers to create a
  file under `.opencode`.
- `SyncVendoredAssetsTask` in `build-logic`, for vendoring another spec or a fixture list without
  duplicating it.

**Standing defect, not addressed.** ktlint, Spotless and detekt are in the version catalog and applied
to no module, so the plan's Quality row (§3) is still not enforced. This is the fourth consecutive
phase to record it and Phase 10 is the last, so it belongs there: wiring at least ktlint/Spotless into
the gate would be a large mechanical diff against pre-existing violations, and doing it inside a
feature phase would attribute that diff to the wrong work. Left deliberately, and said plainly rather
than quietly.

---

### Phase 10: Insights, extensibility, adaptive UI and release (M)

**Goal.** Cover the remaining API surface and ship a polished, production-quality app.

**Builds on.** Everything above.

**Features**

- **Usage dashboard.** `experimental.session.stats`, with a date range, time zone, project filter and tool detail. It
  shows an activity heatmap, streak, cost and token totals, per-model usage, and tool reliability.
- **Developer and extensibility tools.**
  - A plugin RPC console: `rpc.call` with JSON input, plus an `rpc.*` event viewer.
  - Honor TUI control events where they make sense: `tui.toast.show` becomes a snackbar; `tui.session.select` and
    `tui.prompt.append` drive an optional "follow desktop" mode; `tui.command.execute` is mapped to app actions.
  - Advanced session tools: synthetic notes (`session.synthetic`), wait-until-idle (`experimental.session.wait`),
    programmatic permission and form creation, for plugin and workflow testing.
  - The durable session log (`session.log`) used for faster, gap-free resync, and shown as an event-history viewer.
- **Quick ask.** Stateless generation (`experimental.generate.text`) from a home-screen widget or tile.
- **Pair another device.** `POST /api/pair` shows a QR code on the phone. The route is gated by capability detection
  because it is unpublished.
- **LAN discovery helper** (opt-in). Probe the local `/24` subnet on 49374, 4096 and custom ports for the OpenCode
  signature: a `401 {"_tag":"UnauthorizedError"}` response or `/api/info`.
- **Adaptive UI.**
  - List-detail layouts for tablets, foldables and ChromeOS.
  - Keyboard shortcuts that mirror the TUI keybinds: a Ctrl+P command palette and leader-key combinations.
  - Session tabs.
  - A widget showing running sessions and pending approvals, a Quick Settings tile, and app shortcuts.
- **Theming.** Material You, plus OpenCode theme import: read `themes/*.json` through the file API and map V2 tokens
  onto a Material color scheme.
- **Quality.** An accessibility audit, a localization pipeline, baseline profiles, a security review (credentials,
  cleartext, WebView) and a privacy review.
- **Release.**
  - Signing, and a Play Console track progression from internal to closed to production.
  - An F-Droid flavor that uses ZXing and no Google Play Services.
  - APKs on GitHub Releases, an in-app changelog, and a published compatibility matrix.

**API.** `experimental.session.stats`, `rpc.call`, `experimental.generate.text`, `experimental.session.wait`,
`session.synthetic`, `session.log`, `session.permission.create`, `session.form.create`, `POST /api/pair`.

**Exit criteria.**

- Every row in [§7](#7-api-coverage-matrix) and [§8](#8-event-coverage-matrix) is implemented and tested. — **Met.**
  138 of 138 operations declared, wired and tested; 94 of 94 event types handled and tested. The claim
  is derived from the code by `tools/audit-coverage.mjs` and asserted by `CoverageMatrixTest`, and the
  audit of what the matrices claimed and the code did not back is in [`COVERAGE.md`](./COVERAGE.md).
- The release candidate passes the manual test matrix. — **Not met, and not met honestly.** There is
  no device, no emulator and no second device in the build environment, so the matrix has not been
  run. It is written down, with a *Result* column for a human to fill, in
  [`MANUAL_TEST_MATRIX.md`](./MANUAL_TEST_MATRIX.md). No signed artifact was produced either: the
  build is ready for signing material and refuses to pretend otherwise. See [`RELEASE.md`](./RELEASE.md).

**Status.**

The first exit criterion is the point of the phase and it is met, but not because the plan said so.
The matrices were audited against the code before anything was built, and the plan was wrong in five
ways: twelve operations had no production caller, twenty-four more were called by nothing that
asserted anything, five event families had payload classes and no handler, forty-six event types had
a binding and no payload any test had ever decoded, and two of the last two standing defects — a
linter applied to no module, and vendor identifiers left in pre-existing fixtures — had survived
three phases of being "already handled". All of it is now closed, and the checks that prove it are in
CI rather than in a document.

Two things were found by building the phase rather than by auditing it. The `SessionStatsTools` union
discriminates on `mode` and not on the `type` every other union uses, so every statistics answer
decoded as `Unknown` and the dashboard would have shown no tool data on a server that sent plenty. And
a union member arriving as something other than a JSON object threw out of the decoder instead of
falling back to `Unknown`, which would have taken the event connection down over one malformed frame.

What a release still has to do is in [`RELEASE.md`](./RELEASE.md): run the manual matrix on real
hardware, produce a signed artifact from real signing material, and walk the Play track. Two open
questions are named rather than answered, because they are the owner's to decide — whether the
keystore key should require user authentication, and whether the unencrypted message cache stays as it
is. Both are in [`SECURITY_REVIEW.md`](./SECURITY_REVIEW.md) and [`PRIVACY_REVIEW.md`](./PRIVACY_REVIEW.md).

The audits are [`SECURITY_REVIEW.md`](./SECURITY_REVIEW.md), [`PRIVACY_REVIEW.md`](./PRIVACY_REVIEW.md)
and [`ACCESSIBILITY_AUDIT.md`](./ACCESSIBILITY_AUDIT.md). Each states what was checked mechanically and
what needs a device, because "verified" and "read" are different answers and only the first one is
worth anything.

---

## 7. API coverage matrix

This table lists every operation in the V2 OpenAPI spec (136), plus the two unpublished pairing routes, with the phase
that delivers it.

| Area | Operation | Endpoint | Phase |
| --- | --- | --- | --- |
| server | Get server info | `GET /api/info` | P1 |
| server | Redeem a pairing code (not in the published spec) | `GET /auth/connect/{code}` | P1 |
| server | Create a pairing code (not in the published spec) | `POST /api/pair` | P10 |
| location | Get location | `GET /api/location` | P2 |
| location | Reload configuration | `POST /api/location/reload` | P9 |
| project | List projects | `GET /api/project` | P2 |
| project | Update project | `PATCH /api/project/{projectID}` | P7 |
| session | List sessions | `GET /api/session` | P2 |
| session | List active sessions | `GET /api/session/active` | P2 |
| session | Get session | `GET /api/session/{sessionID}` | P2 |
| session | List session inbox | `GET /api/session/{sessionID}/inbox` | P2 |
| session | Get session message | `GET /api/session/{sessionID}/message/{messageID}` | P2 |
| session | Get session messages | `GET /api/session/{sessionID}/message` | P2 |
| session | Create session | `POST /api/session` | P3 |
| session | Delete session | `DELETE /api/session/{sessionID}` | P3 |
| session | Update session | `PATCH /api/session/{sessionID}` | P3 |
| session | Switch session agent | `POST /api/session/{sessionID}/agent` | P3 |
| session | Switch session model | `POST /api/session/{sessionID}/model` | P3 |
| session | Send message | `POST /api/session/{sessionID}/prompt` | P3 |
| session | Cancel inbox input | `DELETE /api/session/{sessionID}/inbox/{inboxID}` | P3 |
| session | Update inbox item | `PATCH /api/session/{sessionID}/inbox/{inboxID}` | P3 |
| session | Interrupt session execution | `POST /api/session/{sessionID}/interrupt` | P3 |
| session | Background blocking session tools | `POST /api/session/{sessionID}/background` | P3 |
| session | List session forms | `GET /api/session/{sessionID}/form` | P3 |
| session | Get session form | `GET /api/session/{sessionID}/form/{formID}` | P3 |
| session | Cancel form | `DELETE /api/session/{sessionID}/form/{formID}` | P3 |
| session | Reply to form | `POST /api/session/{sessionID}/form/{formID}/reply` | P3 |
| session | View session | `POST /api/session/{sessionID}/view` | P4 |
| session | Run command | `POST /api/session/{sessionID}/command` | P5 |
| session | Activate skill | `POST /api/experimental/session/{sessionID}/skill` | P5 |
| session | Run shell command | `POST /api/session/{sessionID}/shell` | P5 |
| session | Compact session | `POST /api/session/{sessionID}/compact` | P5 |
| session | Generate text from session context | `POST /api/session/{sessionID}/generate` | P5 |
| session | Set session environment | `PUT /api/session/{sessionID}/environment` | P5 |
| session | Import session | `POST /api/experimental/session/import` | P6 |
| session | Export session | `GET /api/experimental/session/{sessionID}/export` | P6 |
| session | Fork session | `POST /api/session/{sessionID}/fork` | P6 |
| session | Stage session revert | `POST /api/session/{sessionID}/revert/stage` | P6 |
| session | Clear staged revert | `DELETE /api/session/{sessionID}/revert` | P6 |
| session | Commit staged revert | `POST /api/session/{sessionID}/revert/commit` | P6 |
| session | Get session context | `GET /api/session/{sessionID}/context` | P6 |
| session | Diff session turns | `GET /api/session/{sessionID}/diff` | P6 |
| session | Move session | `POST /api/session/{sessionID}/move` | P7 |
| session | List instruction entries | `GET /api/experimental/session/{sessionID}/instructions/entries` | P9 |
| session | Put instruction entry | `PUT /api/experimental/session/{sessionID}/instructions/entries/{key}` | P9 |
| session | Remove instruction entry | `DELETE /api/experimental/session/{sessionID}/instructions/entries/{key}` | P9 |
| session | Get session statistics | `GET /api/experimental/session/stats` | P10 |
| session | Add synthetic message | `POST /api/session/{sessionID}/synthetic` | P10 |
| session | Wait for session | `POST /api/experimental/session/{sessionID}/wait` | P10 |
| session | Read the session log | `GET /api/experimental/session/{sessionID}/log` | P10 |
| session | Create session form | `POST /api/session/{sessionID}/form` | P10 |
| permission | List pending permission requests | `GET /api/permission/request` | P3 |
| permission | List session permission requests | `GET /api/session/{sessionID}/permission` | P3 |
| permission | Get permission request | `GET /api/session/{sessionID}/permission/{requestID}` | P3 |
| permission | Reply to pending permission request | `POST /api/session/{sessionID}/permission/{requestID}/reply` | P3 |
| permission | List saved permissions | `GET /api/permission/saved` | P9 |
| permission | Remove saved permission | `DELETE /api/permission/saved/{id}` | P9 |
| permission | Create permission request | `POST /api/session/{sessionID}/permission` | P10 |
| form | List pending forms | `GET /api/form` | P3 |
| agent | List agents | `GET /api/agent` | P2 |
| agent | Get agent | `GET /api/agent/{agentID}` | P2 |
| model | List models | `GET /api/model` | P2 |
| model | Get default model | `GET /api/model/default` | P2 |
| generate | Generate text | `POST /api/experimental/generate` | P10 |
| provider | List providers | `GET /api/provider` | P8 |
| provider | Get provider | `GET /api/provider/{providerID}` | P8 |
| integration | List integrations | `GET /api/integration` | P8 |
| integration | Get integration | `GET /api/integration/{integrationID}` | P8 |
| integration | Add wellknown integration | `POST /api/experimental/integration/wellknown` | P8 |
| integration | Connect with key | `POST /api/integration/{integrationID}/connect/key` | P8 |
| integration | Begin OAuth connection | `POST /api/integration/{integrationID}/connect/oauth` | P8 |
| integration | Get OAuth attempt status | `GET /api/integration/{integrationID}/connect/oauth/{attemptID}` | P8 |
| integration | Cancel OAuth connection | `DELETE /api/integration/{integrationID}/connect/oauth/{attemptID}` | P8 |
| integration | Complete OAuth connection | `POST /api/integration/{integrationID}/connect/oauth/{attemptID}/complete` | P8 |
| integration | Begin command connection | `POST /api/integration/{integrationID}/connect/command` | P8 |
| integration | Get command attempt status | `GET /api/integration/{integrationID}/connect/command/{attemptID}` | P8 |
| integration | Cancel command connection | `DELETE /api/integration/{integrationID}/connect/command/{attemptID}` | P8 |
| credential | Update credential | `PATCH /api/credential/{credentialID}` | P8 |
| credential | Remove credential | `DELETE /api/credential/{credentialID}` | P8 |
| credential | Activate credential | `POST /api/credential/{credentialID}/activate` | P8 |
| mcp | List MCP servers | `GET /api/mcp` | P8 |
| mcp | Add MCP server | `PUT /api/experimental/mcp/{server}` | P8 |
| mcp | Remove MCP server | `DELETE /api/experimental/mcp/{server}` | P8 |
| mcp | Connect MCP server | `POST /api/experimental/mcp/{server}/connect` | P8 |
| mcp | Disconnect MCP server | `POST /api/experimental/mcp/{server}/disconnect` | P8 |
| mcp | List MCP resources | `GET /api/mcp/resource` | P8 |
| plugin | List plugins | `GET /api/plugin` | P8 |
| plugin | Check plugin updates | `POST /api/plugin/check` | P8 |
| plugin | Update plugins | `POST /api/plugin/update` | P8 |
| rpc | Call a plugin RPC | `POST /api/rpc/{rpcID}/{method}` | P10 |
| command | List commands | `GET /api/command` | P5 |
| skill | List skills | `GET /api/skill` | P5 |
| reference | List references | `GET /api/reference` | P5 |
| filesystem | List directory | `GET /api/fs/list` | P3 |
| filesystem | Find files | `GET /api/fs/find` | P5 |
| filesystem | Read file | `GET /api/fs/read/*` | P6 |
| filesystem | Write file | `POST /api/experimental/fs/write` | P6 |
| vcs | VCS info | `GET /api/vcs` | P6 |
| vcs | VCS review base | `GET /api/vcs/base` | P6 |
| vcs | VCS status | `GET /api/vcs/status` | P6 |
| vcs | VCS branches | `GET /api/vcs/branch` | P6 |
| vcs | VCS diff | `GET /api/vcs/diff` | P6 |
| worktree | List worktrees | `GET /api/worktree` | P7 |
| worktree | Create worktree | `POST /api/worktree` | P7 |
| worktree | Remove worktree | `DELETE /api/worktree` | P7 |
| worktree | Refresh worktrees | `POST /api/worktree/refresh` | P7 |
| shell | List running shell commands | `GET /api/shell` | P7 |
| shell | Run shell command | `POST /api/shell` | P7 |
| shell | Get shell command | `GET /api/shell/{id}` | P7 |
| shell | Remove shell command | `DELETE /api/shell/{id}` | P7 |
| shell | Read shell output | `GET /api/shell/{id}/output` | P7 |
| pty | List PTY sessions | `GET /api/pty` | P7 |
| pty | Create PTY session | `POST /api/pty` | P7 |
| pty | Get PTY session | `GET /api/pty/{ptyID}` | P7 |
| pty | Update PTY session | `PUT /api/pty/{ptyID}` | P7 |
| pty | Remove PTY session | `DELETE /api/pty/{ptyID}` | P7 |
| pty | Create PTY WebSocket token | `POST /api/pty/{ptyID}/connect-token` | P7 |
| pty | Connect to PTY session | `GET /api/pty/{ptyID}/connect` | P7 |
| persistentPty | Read the session's most recently controlled terminal | `GET /api/experimental/session/{sessionID}/terminal/read` | P7 |
| persistentPty | List a session's persistent terminals | `GET /api/experimental/session/{sessionID}/terminal` | P7 |
| persistentPty | Create a session terminal | `POST /api/experimental/session/{sessionID}/terminal` | P7 |
| persistentPty | Shut down the persistent-PTY host (admin only) | `POST /api/experimental/persistent-pty/shutdown` | P7 |
| persistentPty | Prepare a persistent-PTY handoff for a service restart (admin only) | `POST /api/experimental/persistent-pty/handoff` | P7 |
| persistentPty | Get persistent PTY | `GET /api/experimental/persistent-pty/{ptyID}` | P7 |
| persistentPty | Resize or reattach persistent PTY | `PUT /api/experimental/persistent-pty/{ptyID}` | P7 |
| persistentPty | Remove persistent PTY | `DELETE /api/experimental/persistent-pty/{ptyID}` | P7 |
| persistentPty | Get persistent PTY snapshot | `GET /api/experimental/persistent-pty/{ptyID}/snapshot` | P7 |
| persistentPty | Create persistent PTY WebSocket token | `POST /api/experimental/persistent-pty/{ptyID}/connect-token` | P7 |
| persistentPty | Connect to a persistent PTY | `GET /api/experimental/persistent-pty/{ptyID}/connect` | P7 |
| websearch | List web search providers | `GET /api/websearch/provider` | P8 |
| websearch | Search the web | `POST /api/websearch` | P8 |
| config | List available shells | `GET /api/config/shell` | P7 |
| config | Get configuration | `GET /api/config` | P9 |
| config | Update global configuration | `PATCH /api/experimental/config` | P9 |
| event | Subscribe to events | `GET /api/event` | P1 |
| debug | List loaded locations | `GET /api/debug/location` | P9 |
| debug | Evict a loaded location | `DELETE /api/debug/location` | P9 |
| migration | Get V1 migration status | `GET /api/experimental/migration/v1` | P9 |

**Operations per phase.**

| Phase | Operations | Status |
| --- | --- | --- |
| P0 | Foundation (models, fixtures, harness, CI) | Complete |
| P1 | 3 | Complete |
| P2 | 12 | Complete |
| P3 | 20 | Complete |
| P4 | 1 | Complete |
| P5 | 10 | Complete |
| P6 | 15 | Complete |
| P7 | 30 | Complete |
| P8 | 27 | Complete |
| P9 | 11 | Complete |
| P10 | 9 | Complete |

Total: 138 (136 spec operations plus the 2 pairing routes).

---

## 8. Event coverage matrix

All 94 event types in `@opencode/client` 2.0.18: 93 named types plus the `rpc.*` family. An event is assigned to
the phase that first *acts* on it. In Phase 0, all 94 types are defined in the sealed `EventPayload` hierarchy (`EventTypes`),
and tested against recorded real-server events without loss. From P2 on, every event also appears in the Event inspector
and is covered by the reducer or invalidation tests.

| Phase | Events handled | Status |
| --- | --- | --- |
| P0 | All 94 event types modeled in `EventPayload` / `EventTypes`, contract-tested against recorded fixtures | Complete |
| P1 | `server.connected` (fired, logged, and published as the resync signal) | Complete |
| P2 | `location.shutdown`, `models-dev.refreshed`, `model.updated`, `agent.updated`, `session.created`, `session.agent.selected`, `session.model.selected`, `session.moved`, `session.renamed`, `session.metadata.updated`, `session.permissions`, `session.viewed`, `session.usage.updated`, `session.deleted`, `session.forked`, `session.inbox.delivered`, `session.inbox.enqueued`, `session.inbox.cancelled`, `session.inbox.delivery.changed`, `session.execution.started`, `session.execution.succeeded`, `session.execution.failed`, `session.execution.interrupted`, `session.instructions.updated`, `session.synthetic`, `session.skill.activated`, `session.shell.started`, `session.shell.ended`, `session.step.started`, `session.step.streamed`, `session.step.ended`, `session.step.failed`, `session.text.started`, `session.text.delta`, `session.text.ended`, `session.reasoning.started`, `session.reasoning.delta`, `session.reasoning.ended`, `session.tool.input.started`, `session.tool.input.delta`, `session.tool.input.ended`, `session.tool.called`, `session.tool.progress`, `session.tool.success`, `session.tool.failed`, `session.retry.scheduled`, `session.compaction.started`, `session.compaction.delta`, `session.compaction.ended`, `session.compaction.failed`, `session.revert.staged`, `session.revert.cleared`, `session.revert.committed`, `project.updated`, `session.status`, `session.idle` | Complete |
| P3 | `permission.asked`, `permission.replied`, `form.created`, `form.replied`, `form.cancelled` | Complete |
| P4 | `installation.updated`, `installation.update-available` (recorded on `ServerDataSet`; an announced version becomes the "server update available" notification) | Complete |
| P5 | `reference.updated`, `command.updated`, `skill.updated` (recorded on `ServerDataSet.composerCatalogs`; the empty payload invalidates the named location, or every location the client has open when the frame carries none) | Complete |
| P6 | `filesystem.changed` (re-reads the browser's listing and the file the viewer holds), `vcs.branch.updated` (re-reads `vcs.get` and `vcs.status` for every open location, so a branch that moves on the desktop moves the header) | Complete |
| P7 | `worktree.updated` (re-reads the one named project and nothing else), `worktree.resolved` (recorded as an adoption, then re-read), `pty.created`, `pty.updated` (renames in place, so every open view follows the title), `pty.exited`, `pty.deleted`, `persistent-pty.added`, `persistent-pty.removed`, `shell.created`, `shell.exited` (the status and exit code land on the row, and the poller stops), `shell.deleted` | Complete |
| P8 | `credential.updated`, `credential.switched` (invalidate the integrations list of every open location), `integration.updated`, `provider.updated`, `plugin.updated`, `websearch.updated` (one catalog each, and nothing else), `mcp.status.changed {server}` (invalidate the server list only), `mcp.resources.changed {server}` (invalidate the resource catalog only) | Complete |
| P9 | `config.updated` | Complete |
| P10 | `tui.prompt.append`, `tui.command.execute`, `tui.toast.show`, `tui.session.select`, `rpc.<rpcID>.<event>` | Planned |

---

## 9. Risks and mitigations

| Risk | Impact | Mitigation |
| --- | --- | --- |
| Fast V2 release cadence (2.0.0 → 2.0.18 already) changes contracts | Broken decoding or behavior | Vendored specs, drift checks, `Unknown` fallbacks, a nightly CI job against the latest CLI, and capability flags |
| Experimental routes change or disappear (29 operations) | Features break | Probe on first use, hide on `404`, keep them behind an "Experimental" setting, and never let a core flow depend on one |
| The live-only event stream drops events during mobile network hiccups | A stale UI | REST resync on every `server.connected`, a reducer self-check in debug builds, and optionally the durable session log (P10) |
| Background limits: foreground-service type policy, Doze, OEM task killers | Missed approvals | Run only while needed, reconnect and resync on resume, and provide battery guidance. The foreground-service type is validated in a P0 spike. |
| Cleartext HTTP on shared networks | Credential exposure | Warnings, HTTPS support, and recommending SSH tunnels, Tailscale or a reverse proxy for anything beyond a trusted LAN. Tokens can be revoked by rotating the server password. |
| Terminal fidelity in a WebView | A poor terminal experience | Bundled xterm.js tested with `vim`, `htop` and the OpenCode TUI itself, with Termux `terminal-view` as the fallback |
| Timeline reducer diverges from the server's projection | A wrong transcript | Port the reference client's logic, run golden tests against the REST projection, and self-check at runtime |
| Pairing route `/api/pair` is unpublished | "Pair another device" breaks | Capability-gated and outside the core flow; redemption through `/auth/connect` is the documented path |
| Editing config files remotely can corrupt configuration | A broken server config | Opt-in, schema validation, backups (read before write), diagnostics after reload, and an undo option |

---

## 10. Open decisions

These are decisions for the project owner. None of them blocks P0.

1. **Identity.** App name, application ID and license.
2. **Distribution.** Google Play, F-Droid, GitHub Releases, or all three. This decides whether ML Kit or ZXing ships
   by default.
3. **Minimum Android version.** Proposed: API 26, Android 8.0.
4. **Form-factor priority.** Phone-first with tablet support (proposed), or tablet-first.
5. **Remote config editing.** Is editing through the experimental `fs.write` route acceptable, or should the app stay
   read-only for file-based definitions?
6. **Background default.** Keep the connection alive only while sessions are busy or requests are pending (proposed),
   or always.
