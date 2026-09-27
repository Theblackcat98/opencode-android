# OpenCode V2: Feature Inventory and Server Control Surface

This document lists every OpenCode V2 feature and says, for each one, whether a remote client (our Android app) can
drive or change it through the OpenCode server. The implementation plan in
[`ANDROID_APP_PLAN.md`](./ANDROID_APP_PLAN.md) is built on this inventory.

## Sources and verification

| Source | What it provided |
| --- | --- |
| OpenCode V2 docs (the content served at `https://opencode.ai/v2/docs`) | Every page under Docs, CLI, Build, API and Console. The site was not reachable from the research environment, so the docs were read from their source: `anomalyco/opencode`, branch `v2`, `services/www/src/docs/content/**` (commit `96f2350`, 2026-09-27). The page list comes from `lib/navigation.ts`, the same file that builds the site's sidebar. |
| V2 API reference (`/v2/docs/api`) | Generated from `services/www/openapi.json`: OpenAPI 3.1, 136 operations on 113 paths, 245 schemas. |
| `@opencode/client` 2.0.18 generated types | The event stream's payload types. The OpenAPI spec types each event only as an opaque JSON string. |
| Server source (`packages/server`, `packages/protocol`, `packages/core`) | Authentication, pairing, the location header, the SSE framing, the PTY WebSocket protocol, and the two pairing routes that are missing from the published spec. |
| A live `opencode serve` (npm `@opencode/cli@2.0.18`) | Confirmed Basic auth, the pairing-code redemption, SSE framing and heartbeats, the event order for a prompt and a shell command, message projection, and cursor pagination. |

Current release at the time of writing: **2.0.18** (npm `@opencode/cli`, `@opencode/client`).

### Legend

| Tag | Meaning |
| --- | --- |
| **API** | Can be read and driven through the HTTP API or event stream. |
| **Read** | Visible through the API, but it cannot be changed through the API. |
| **Exp** | Available only through an `/api/experimental/...` route, which may change without notice. |
| **Config** | Controlled only by configuration files, `opencode.json(c)` or `.opencode/**`. Remotely changeable only by editing those files (see [Configuration editing](#335-editing-configuration-remotely)). |
| **CLI** | Controlled only from the machine running the server, through `opencode service …`, environment variables, and so on. |
| **Client** | A client-side feature of the TUI or web app. The Android app must implement it natively. |
| **N/A** | Not supported in V2. |

---

## 1. Architecture at a glance

- **Client/server.** A single server process owns sessions, configuration, integrations, credentials, permissions,
  plugins and tool execution. The TUI, `opencode run`, `opencode mini`, the desktop app and the web app are all clients
  of that server. The Android app is simply another client.
- **Shared background service.** By default the CLI discovers or starts one background server per user account. It
  listens on port `49374` (the channel default), is bound to `localhost`, and requires a password.
- **Locations.** Most API calls are scoped to a *Location*, which is a working directory. The server loads that
  directory's configuration, plugins, models, agents, commands, skills, MCP servers and so on. One server can serve
  many locations at once.
- **Projects.** A project groups the locations that share a repository, identified by a project ID. Each project has a
  canonical checkout and zero or more worktrees ("sandboxes").
- **Sessions.** A session is a durable conversation stored in SQLite on the server. Work flows through a durable
  **inbox**: user prompts, synthetic input, compaction requests and moves are enqueued, then delivered to the agent
  loop. Each delivery is either *steered* in at the next step boundary or *queued* until the current turn finishes.
- **Events.** `GET /api/event` is a single Server-Sent Events stream covering every location on the server. It is
  live-only: there is no replay, and a slow consumer is disconnected. Clients rebuild their state from REST
  snapshots each time they reconnect.

---

## 2. Server, connectivity and authentication

### 2.1 Server modes (CLI)

| Feature | How | Control |
| --- | --- | --- |
| Shared background service | Auto-started by the CLI. `opencode service start/stop/restart/status` | CLI |
| Service settings: hostname, port, password, CORS, env vars | `opencode service set/get/unset <key>`. Changing one stops the service. | CLI |
| Foreground server | `opencode serve --hostname 0.0.0.0 --port 4096 --cors <origin>`. Prints the URL and password. | CLI |
| Private per-process server | `opencode --standalone` | CLI |
| Connect a CLI to a specific server | `opencode --server <url>` | CLI |
| Reload configuration without a restart | `opencode reload`, or `POST /api/location/reload` | **API** |
| Raw API calls | `opencode api <METHOD> <path>` or `opencode api <operationId> --param k=v --data '{}'` | CLI |
| Docker | Versioned images such as `ghcr.io/anomalyco/opencode:2.0.0` | CLI |

> **LAN setup the app must explain.** The shared service listens on localhost only by default. To reach it from a
> phone, the user must run `opencode service set hostname 0.0.0.0` (then `opencode service start`), or run
> `opencode serve --hostname 0.0.0.0`. The alternatives are SSH port forwarding, Tailscale, or a reverse proxy.
> **V2 has no mDNS or Bonjour advertisement.** The V1 `server.mdns` config field is ignored with a warning.

### 2.2 Authentication **API**

- **HTTP Basic auth** with the fixed username `opencode` and the server password. With no password, auth is disabled.
- **Session tokens.** A pairing code is exchanged for a token of the form `<expiresUnix>.<hmac>`, valid for 30 days.
  The token is accepted anywhere the password is: send it as the Basic-auth password. Changing the server password
  revokes every token.
- **Query-string credential.** `?auth_token=<base64(user:pass)>` is accepted too, which helps WebView and WebSocket
  cases.
- **Browser cookie.** `opencode_session_<port>` is used by the web UI (same-origin only).
- **Unauthorized response.** `401 {"_tag":"UnauthorizedError","message":"Authentication required"}`. A
  `WWW-Authenticate: Basic` challenge is added for non-browser clients.

### 2.3 Pairing (QR code / link) **API**

| Step | Endpoint | Notes |
| --- | --- | --- |
| Print pairing links and a QR code | `opencode pair [--url https://external.example]` | CLI. Links are single-use and expire after 5 minutes. |
| Create a pairing code from an authenticated client | `POST /api/pair` → `{ code, expires_in }` | Present in the server source but not in the published spec. The app can use it to pair *other* devices. |
| Redeem a pairing link | `GET /auth/connect/{code}` with `Accept: application/json` → `{ token }` | Needs no auth. A code works once. Browsers get a cookie and a redirect instead of JSON. |

The link format is `http(s)://<host>:<port>/auth/connect/<code>`, where the code matches `[A-Za-z0-9_-]+`. The
official web app parses the scanned QR payload with exactly this pattern, then stores
`{url: origin, password: token}`.

### 2.4 Server identity and health **API**

| Feature | Endpoint |
| --- | --- |
| Version, PID, reachable URLs, temp path | `GET /api/info` → `ServerInfo {version, pid, urls[], paths.tmp}`. When bound to `0.0.0.0`, `urls` lists every non-internal interface address. |
| Loaded locations (debug) | `GET /api/debug/location`, `DELETE /api/debug/location` (evict cached services for a location) |
| V1 → V2 history migration progress | `GET /api/experimental/migration/v1` **Exp** |

### 2.5 CORS and origins **CLI**

The server accepts `localhost`, `127.0.0.1`, `tauri://`, `oc://renderer`, `*.opencode.ai`, and any origins configured
with `service set cors` or `serve --cors`. Native Android HTTP clients send no `Origin` header, so CORS does not affect
the app. It only matters for a WebView-hosted page, such as an xterm.js terminal.

### 2.6 Locations **API**

| Feature | How |
| --- | --- |
| Select a location for a request | Query `location[directory]=/abs/path` (deepObject style) or header `x-opencode-directory: <url-encoded path>`. Defaults to the server's working directory. |
| Resolve a location and its project | `GET /api/location` → `{directory, project: {id, directory, canonical}}` |
| Rebuild every location (config reload) | `POST /api/location/reload`. Cancels pending permissions and forms. Running sessions continue at the next step boundary. Emits `location.shutdown`. |
| Location-scoped responses | Most list endpoints return `{ location: {directory}, data: [...] }` |

### 2.7 Network and proxy **CLI**

`HTTP_PROXY`, `HTTPS_PROXY`, `NO_PROXY` and `NODE_EXTRA_CA_CERTS` apply to the server process, set through
`service set env`. The API cannot change them.

### 2.8 Updates **Read** / **Config** / **CLI**

- The `update` config field (`disable` | `notify` | `auto`) is global only.
- The events `installation.update-available {version}` and `installation.updated {version}` let the app show a banner.
- Upgrading requires `opencode upgrade [version] [--method]` on the server machine. An automatic update does not
  restart a running server.

---

## 3. Projects **API**

| Feature | Endpoint / event |
| --- | --- |
| List known projects | `GET /api/project` → `Project[] {id, canonical, vcs?, name?, icon?{url, override, color}, commands?{start}, time, sandboxes[]}` |
| Rename, set icon, change canonical directory, set the workspace start command | `PATCH /api/project/{projectID}` `{canonical?, name?, icon?, commands?}` |
| Live updates | `project.updated` (full project payload) |
| Worktrees ("sandboxes") | See [§29](#29-worktrees-api) |

---

## 4. Sessions **API**

### 4.1 Session object (`Session.Info`)

| Field | Meaning |
| --- | --- |
| `id` (`ses…`), `parentID?` | Child sessions (subagents, background commands) have a `parentID`. |
| `fork?` | `{sessionID, boundary: {type: "before" or "through", messageID}}` |
| `projectID`, `location.directory`, `subpath?` | Where the session runs. |
| `agent?`, `model?` | The current selection. A `Model.Ref` is `{id, providerID, variant?}`. |
| `cost`, `tokens` | `{input, output, reasoning, cache: {read, write}}` |
| `outcome?` | The last run's result: `succeeded`, `failed` or `interrupted` |
| `time` | `{created, updated, idle?, viewed?, archived?}`. Compare `idle` with `viewed` to derive "unread". |
| `title?`, `metadata?`, `permissions?` | The title, free-form metadata, and session-scoped permission rules |
| `revert?` | A staged revert: `{messageID, partID?, snapshot?, files?}` |

### 4.2 Session operations

| Feature | Endpoint | Notes |
| --- | --- | --- |
| List sessions | `GET /api/session` | `limit` (default 50), `order` (`asc` or `desc`), `search`, `parentID` (`null` means roots only), `directory`, `project`, `subpath`, `cursor`. Returns `{data, cursor: {previous, next}}`. |
| Active (running) sessions | `GET /api/session/active` | A map of session ID → `{type: "running"}` |
| Get a session | `GET /api/session/{id}` | |
| Create a session | `POST /api/session` | `{id?, title?, agent?, model?, location?, metadata?, permissions?}` |
| Rename, set metadata, set session permission rules | `PATCH /api/session/{id}` | `{title?, metadata?, permissions?}` |
| Delete a session and its children | `DELETE /api/session/{id}` | |
| Fork | `POST /api/session/{id}/fork` | `{before?: messageID}`. Omit `before` to copy the full history. |
| Switch agent | `POST /api/session/{id}/agent` | `{agent}` |
| Switch model or variant | `POST /api/session/{id}/model` | `{model: {id, providerID, variant?}}` |
| Move to another directory or worktree | `POST /api/session/{id}/move` | `{directory, delivery?}`. Applied at a safe boundary. |
| Send a prompt | `POST /api/session/{id}/prompt` | `{id?, text, files?, agents?, skills?, metadata?, delivery?: "steer" or "queue", resume?}` → an inbox item. `409` if an `id` is reused with a different payload. |
| Run a slash command | `POST /api/session/{id}/command` | `{name, text (arguments), files?, agents?, skills?, delivery?}` |
| Activate a skill | `POST /api/experimental/session/{id}/skill` **Exp** | `{id, resume?}` |
| Add synthetic input | `POST /api/session/{id}/synthetic` | `{id?, text, description?, metadata?, delivery?, resume?}` |
| Run a user shell command in the session | `POST /api/session/{id}/shell` | `{id?, command}`. The TUI's `!` mode. Output lands in the transcript as a `shell` message and is fed back to the agent. |
| Compact | `POST /api/session/{id}/compact` | `{id?, delivery?}`. Steers by default. Idempotent when an `id` is given. |
| Interrupt | `POST /api/session/{id}/interrupt?resume=true/false` | → `{interrupted}`. With `resume=true`, pending steering input resumes and queued prompts stay parked. |
| Move blocking tools to the background | `POST /api/session/{id}/background` | The TUI's Ctrl+B |
| Wait until idle | `POST /api/experimental/session/{id}/wait` **Exp** | |
| Stage a revert (undo) | `POST /api/session/{id}/revert/stage` | `{messageID, files?}` → `Session.Revert`. `409` while busy. |
| Clear a staged revert (redo) | `DELETE /api/session/{id}/revert` | |
| Commit a staged revert | `POST /api/session/{id}/revert/commit` | The TUI commits before submitting the edited prompt. |
| Active model context | `GET /api/session/{id}/context` | Messages after the last compaction |
| Per-turn file diffs | `GET /api/session/{id}/diff?from=&to=&context=` | `FileDiff.Info[] {file, patch, additions, deletions, status}` |
| Inbox: pending work | `GET /api/session/{id}/inbox` | User, synthetic, compaction and move items |
| Inbox: cancel an item | `DELETE /api/session/{id}/inbox/{inboxID}` | |
| Inbox: change delivery (queue ↔ steer) | `PATCH /api/session/{id}/inbox/{inboxID}` | `{delivery}` |
| Side question without touching history (`/btw`) | `POST /api/session/{id}/generate` | `{prompt}` → `{text}` |
| Messages (paged) | `GET /api/session/{id}/message` | `limit`, `order`, `cursor`, `type` filter |
| One message | `GET /api/session/{id}/message/{messageID}` | |
| Durable event log (replay + follow) | `GET /api/experimental/session/{id}/log?after=<seq>&follow=true` **Exp** | SSE. Ends with `log.synced {seq}`. |
| Shell environment for the session | `PUT /api/session/{id}/environment` | `{variables: {…}}` replaces the environment |
| Mark viewed (unread tracking) | `POST /api/session/{id}/view` | `{idle: <time.idle observed>}` |
| API-managed instruction entries | `GET/PUT/DELETE /api/experimental/session/{id}/instructions/entries/{key}` **Exp** | Keys match `^[a-z0-9][a-z0-9._-]*$`. Changes are announced at the next step. |
| Forms owned by a session | See [§16](#16-forms-api) | |
| Permission requests owned by a session | See [§14](#14-permissions-api) | |
| Session-bound terminals | See [§32](#32-persistent-ptys-exp) | |
| Usage statistics | `GET /api/experimental/session/stats?from&to&project&timezone&tools=none/summary/detail` **Exp** | Sessions, subagents, prompts, steps, tokens, cost, active days, streak, daily activity, per-model usage, and tool reliability (p50 durations). This is the API behind `opencode stats`. |
| Export a transcript | `GET /api/experimental/session/{id}/export?sanitize=true` **Exp** | `{info, messages}`. `sanitize` redacts sensitive data. |
| Import a transcript | `POST /api/experimental/session/import` **Exp** | `{info, messages, location?}`. Import parents before children. |
| Share a session | N/A | "OpenCode V2 does not support session sharing yet." The `share` config is accepted but ignored. |

### 4.3 Session status

- `session.status` events carry `{type: "idle"}`, `{type: "busy"}`, or
  `{type: "retry", attempt, message, next, action?}`. The optional `action` gives a provider-supplied title, message,
  label and link, used for example for "usage exceeded, upgrade" dialogs.
- `session.execution.started` marks the start of a run. `succeeded`, `failed {error}` and
  `interrupted {reason: user, shutdown, superseded or inactivity}` mark the end. `session.idle` fires when the session
  becomes idle.

---

## 5. Session timeline (message model) **API**

`Session.Message.Info` is a union discriminated by `type`:

| `type` | Contents |
| --- | --- |
| `user` | `text`, `files?[]` (attachments with `data`, `mime`, `source`, `name`), `agents?[]`, `skills?[]`, `metadata?` |
| `synthetic` | Text injected by the system, a plugin or the API, for example shell output handed back to the agent or a background-task completion |
| `system` | System notice text |
| `skill` | A loaded skill: `skill`, `name`, `text` |
| `shell` | A user `!` shell: `shellID`, `command`, `status` (running, exited, timeout or killed), `exit`, `output {output, cursor, size, truncated}` |
| `assistant` | `agent`, `model`, and `content[]` holding **text**, **reasoning** (with timing) and **tool** parts. Also `snapshot {start, end, files}`, `finish`, `cost`, `tokens`, `error?` and `retry?` |
| `compaction` | `running`, `completed` or `failed`, with `reason` (auto or manual), `summary`, `recent`, cost and tokens |
| `idle` | `outcome`: succeeded, failed or interrupted. Marks the end of a turn. |
| `agent-switched`, `model-switched`, `location-switched` | Markers with previous and new values |

**Tool parts** hold `{id, name, executed?, state, time {created, ran?, completed?}}`. The `state` is one of:

- `streaming {input: string}`, where the model is still writing arguments
- `running {input, metadata}`
- `completed {input, content[], metadata?}`
- `error {input, error, content?, metadata?}`

Tool content is `text` or `file {uri, mime, name}`.

Tool metadata worth rendering:

| Tool | Metadata |
| --- | --- |
| `edit`, `write`, `patch` | `files` (per-file diff previews) |
| `subagent` | `sessionID` and `status` of the child session |
| `shell` | `shellID`, output metadata |
| `question` | `answers` |

**Streaming.** The live transcript is rebuilt from events. See the [event catalog](#36-event-catalog-api):
`session.step.started` creates the assistant message, and the `text.*`, `reasoning.*`, `tool.input.*`, `tool.called`,
`tool.progress`, `tool.success` and `tool.failed` events fill in its content by ordinal or tool ID. `step.ended` and
`step.failed` finalize it.

---

## 6. Prompt input **API**

| Feature | Detail |
| --- | --- |
| Text | `text` (required) |
| Delivery | `steer` (default for prompts) injects at the next step boundary while the agent is busy. `queue` waits for the current turn. The TUI uses Enter to steer and Alt+Enter to queue. |
| `resume: false` | Admit the input without starting the agent loop |
| Idempotency | A client-supplied `id` (`msg_…`) makes retries safe. Reusing an ID with a different payload returns `409`. |
| File attachments | `files[]: {uri, name?, description?, mention?}`. `uri` is an absolute `file:` URL for a file or directory on the **server**. Text line ranges use `?start=20&end=60`. A `data:` URL carries inline content, for example a photo from the phone. HTTP(S) URLs are not supported. The limit is 20 MiB decoded per item. |
| Agent mentions | `agents[]: {name, mention?}` |
| Skill attachments | `skills[]: {id, mention?}` |
| Mentions | `{start, end, text}` ranges tie an attachment to the text the user typed, such as `@src/a.ts` |
| Metadata | Free-form. For example, the web app stores review comments as `metadata.opencodeComment {path, selection, comment, preview, origin}`. |
| Model-visible formats | UTF-8 text, directories (a non-recursive listing), and PNG, JPEG, GIF and WebP. SVG counts as text. PDF, audio, video and other binaries are *not* sent to the model. |

---

## 7. Agents

| Feature | Control |
| --- | --- |
| List and get agents: `id, name, description, mode (primary, subagent or all), hidden, color, model?, steps?, system?, permissions, request` | **API** `GET /api/agent`, `GET /api/agent/{id}` |
| Select an agent for a session | **API** `POST /session/{id}/agent`, or `agent` on create |
| Built-ins: `build`, `plan` (primary), `general`, `explore` (subagents); hidden `compaction`, `title`, `summary` | **Read** |
| Define or override agents: Markdown files in `~/.config/opencode/agents/`, `.opencode/agents/` (nested paths give IDs like `team/reviewer`), or `agents` in the config | **Config** |
| `default_agent` | **Config** |
| Options: `description`, `mode`, `model` (`provider/model#variant`), `system`, `permissions`, `steps`, `hidden`, `color`, `disabled`, `request` (preserved but not yet sent) | **Config** |
| Live change notification | **API** event `agent.updated` (refetch) |

---

## 8. Models and variants

| Feature | Control |
| --- | --- |
| Model catalog, ordered by release date: `id, modelID, providerID, name, family, capabilities {tools, input[], output[]}, variants[], cost[] (tiers, cache), limit {context, input?, output}, status (alpha, beta, deprecated or active), enabled, time.released` | **API** `GET /api/model` (location-scoped) |
| Default model | **API** `GET /api/model/default` |
| Select a model or variant per session | **API** `POST /session/{id}/model` |
| Variant cycling and selection (reasoning effort, for example) | **API**, through `Model.Ref.variant` |
| Recent and favorite models, F2 cycling | **Client**. The TUI keeps these locally. |
| One-shot stateless generation | **Exp** `POST /api/experimental/generate {prompt, model?}` |
| Configure models: aliases (`modelID`), `capabilities`, `limit`, `cost`, `variants[]`, `settings`, `headers`, `body`, `compatibility.reasoningField`, `disabled` | **Config** |
| Local model discovery (Ollama, LM Studio, vLLM) | **Read**. Automatic, with results showing up in the catalog. |
| Catalog refresh from models.dev | **API** event `models-dev.refreshed` |
| Change notification | **API** event `model.updated` |

---

## 9. Providers

| Feature | Control |
| --- | --- |
| List and get providers: `id, name, activation (auto, enabled or disabled), package, integrationID?, settings, headers, body, canonical?` | **API** `GET /api/provider`, `GET /api/provider/{id}` (**Read**) |
| Custom providers, endpoints (`settings.baseURL`), headers and body, runtime `package`, WebSocket transport, native compaction | **Config** |
| Built-in cloud providers (Azure resource name, Bedrock profile and region, Vertex project and location) | **Config** / **CLI** (environment) |
| Allow or deny providers (`provider.use` policy) | **Config** ([§15](#15-policies-config)) |
| Change notification | **API** `provider.updated` |

---

## 10. Integrations and credentials (`/connect`) **API**

Integrations cover model providers, web search providers, GitHub Copilot, MCP OAuth and similar.

| Feature | Endpoint |
| --- | --- |
| List and get integrations with their auth methods and connections | `GET /api/integration`, `GET /api/integration/{id}` → `{id, name, methods[], connections[]}` |
| Method types | `key {label, form?}`, `oauth {id, label, form?}`, `command {id, label, command[]}`, `env {names[]}` |
| Connect with an API key (plus optional form answers and a label) | `POST /api/integration/{id}/connect/key {key, answer?, label?}` |
| OAuth: start | `POST …/connect/oauth {methodID, answer?, label?}` → `{attemptID, url, instructions, mode: auto or code, time}` |
| OAuth: poll | `GET …/connect/oauth/{attemptID}` → pending, complete, failed or expired |
| OAuth: submit a code | `POST …/connect/oauth/{attemptID}/complete {code?}` |
| OAuth: cancel | `DELETE …/connect/oauth/{attemptID}` |
| Command auth: start, poll (with output), cancel | `POST …/connect/command`, `GET/DELETE …/connect/command/{attemptID}` |
| Add a well-known integration source | **Exp** `POST /api/experimental/integration/wellknown {url}` |
| Rename a credential | `PATCH /api/credential/{id} {label}` |
| Activate a credential (switch account) | `POST /api/credential/{id}/activate` |
| Remove a credential (log out) | `DELETE /api/credential/{id}` |
| Environment connections | **Read**. They appear as `connections[{type: "env", name}]` and can only be changed on the server (`service set env`). |
| Events | `integration.updated`, `credential.updated`, `credential.switched {integrationID, credentialID}` |

Credentials are stored in the server's SQLite database. The newest login becomes the active account.

---

## 11. Commands

| Feature | Control |
| --- | --- |
| List commands `{name, description?}`, including MCP prompts named `<server>:<prompt>` | **API** `GET /api/command` |
| Run a command | **API** `POST /session/{id}/command` |
| Define commands: `.opencode/commands/*.md` (nested names like `team/review`) or `commands` in the config. Fields: `template`, `description`, `agent`, `model`, `subagent` (run in a background child), `$ARGUMENTS`, `$1..$n`, and `` !`shell` `` blocks | **Config** |
| Change notification | **API** `command.updated` |

---

## 12. Skills

| Feature | Control |
| --- | --- |
| List skills `{id, name, description?, autoinvoke?, path, content}` | **API** `GET /api/skill` |
| Attach a skill to a prompt | **API** `skills[]` on prompt or command |
| Activate a skill in a session | **Exp** `POST /api/experimental/session/{id}/skill` |
| Skill sources: `~/.config/opencode/skills`, `.opencode/skills`, `.claude/skills`, `.agents/skills`, the `skills` config array (paths and HTTP catalogs with `index.json`) | **Config** |
| Frontmatter: `name`, `description`, `slash`, `metadata.opencode/autoinvoke` | **Config** |
| Skill permission (`skill` action) | **Config** |
| Change notification | **API** `skill.updated` |

---

## 13. Tools **Read** (observed in the timeline) / **Config** (availability)

| Tool | Purpose | Permission action and resource |
| --- | --- | --- |
| `read` | Files and directories. Text is paged (2,000 lines or 50 KiB). Images and PDF go to the model. | `read` + path |
| `glob` | Path patterns (100 results, 30 s) | `glob` + pattern |
| `grep` | Content search (100 results, 30 s) | `grep` + regex |
| `edit` | Exact string replacement | `edit` + path |
| `write` | Create or replace a file | `edit` + path |
| `patch` | Multi-file patches, for GPT models | `edit` + each path |
| `shell` | Shell commands. 2-minute foreground timeout. `background: true` for long-running processes. | `shell` + each scanned command |
| `webfetch` | Fetch a URL as markdown, text or HTML | `webfetch` + URL |
| `websearch` | Web search through the selected provider | `websearch` + query |
| `question` | Ask the user structured questions. Surfaces as a **form**. | `question` + `*` |
| `skill` | Load a skill | `skill` + skill ID |
| `subagent` | Start a child session, in the foreground or background | `subagent` + agent ID |
| `execute` | Code Mode: JavaScript that composes tools. MCP tools are grouped here by default. | `execute` + `*` |
| `opencode.session_rename`, `opencode.session_move` | Code Mode session utilities | none |
| `browser.*` | Controls the desktop app's attached browser. Not relevant on Android. | `browser` + `*` |
| MCP tools `<server>_<tool>` | From connected MCP servers | `<server>_<tool>` + `*` |

Other tool-related config: `tool_output {max_lines, max_bytes}`, `media.image {auto_resize, max_width, max_height,
max_base64_bytes}`, `websearch: false`, and `formatter`. All of these are **Config**.

---

## 14. Permissions **API**

| Feature | Control |
| --- | --- |
| Rules: an ordered `permissions: [{action, resource, effect: allow, ask or deny}]` where the last match wins, set globally or per agent | **Config** |
| Session-scoped rules, evaluated after the agent's rules and inherited by children | **API** `PATCH /api/session/{id} {permissions}`, or on create. Event `session.permissions`. |
| Pending requests for a location | **API** `GET /api/permission/request` |
| Pending requests for a session | **API** `GET /api/session/{id}/permission`, `GET …/permission/{requestID}` |
| A request's contents | `{id, sessionID, action, resources[], save?[] (patterns "always" would save), metadata?, source? {type: tool, messageID, id}, message?}` |
| Reply to a request | **API** `POST /api/session/{id}/permission/{requestID}/reply {decision: once, always or reject, message?}`. `reject` rejects every pending request in the session. `message` is feedback to the agent. |
| Create a request programmatically | **API** `POST /api/session/{id}/permission` |
| Saved ("always") approvals | **API** `GET /api/permission/saved?projectID=` and `DELETE /api/permission/saved/{id}` |
| Events | `permission.asked` (the full request) and `permission.replied {sessionID, requestID, reply}` |
| Auto-approve mode | **Client**. The TUI setting `session.permissions: autoaccept` and the `permission.mode` toggle answer `ask` requests on the client side. |
| Experimental portable shell scanner | **Config** `experimental.portable_shell_scanner` |

Default base policy: allow everything; ask for `external_directory` and for `.env` reads.

---

## 15. Policies **Config**

`experimental.policies: [{action: "provider.use" or "permission", resource, effect: allow or deny}]`. Broader
configuration wins, and Console workspace policies have the final say. A denial surfaces as a tool error ("Blocked by
configuration policy" or "Blocked by <Org>'s policy"). Policies cannot be read or changed through a dedicated API;
they can only be seen in `GET /api/config` documents.

---

## 16. Forms **API**

Forms are V2's generic mechanism for interactive input.

| Source (`form.metadata.kind`) | Use |
| --- | --- |
| `question` (plus `tool {messageID, id}`) | The `question` tool. One field per question (`q0`, `q1`, …): `string` with options plus `custom: true`, or `multiselect`. |
| `websearch.provider` | First-use web search consent and provider selection |
| `mcp-elicitation` (plus `server`, `message`) | An MCP server asking the user for input |
| Integration method `form` | Extra details needed for a key or OAuth login, such as an Azure resource name or a GHE domain |
| Plugins and the API | Arbitrary forms through `POST /api/session/{id}/form` |

| Feature | Endpoint |
| --- | --- |
| Pending forms for a location | `GET /api/form` |
| Pending forms for a session | `GET /api/session/{id}/form` |
| Form detail and state (pending, answered or cancelled) | `GET /api/session/{id}/form/{formID}` |
| Create a form | `POST /api/session/{id}/form {id?, title, metadata?, fields[]}` |
| Answer | `POST …/form/{formID}/reply {answer: {key: value}}` |
| Cancel (dismissing a question cancels it) | `DELETE …/form/{formID}` |
| Events | `form.created {form}`, `form.replied`, `form.cancelled` |

**Field types:**

| Type | Attributes |
| --- | --- |
| `string` | `format` (email, uri, date or date-time), min/max length, `pattern`, `placeholder`, `default`, `options[]`, `custom` |
| `number`, `integer` | `minimum`, `maximum`, `default` |
| `boolean` | `default` |
| `multiselect` | `options`, `minItems`, `maxItems`, `custom`, `default[]` |
| `external` | `url`, a link the user must open |

Every field supports `key`, `title`, `description`, `required`, `hidden`, and conditional `when[{key, op: eq or neq,
value}]`.

---

## 17. MCP servers

| Feature | Control |
| --- | --- |
| List servers with status: `connected`, `pending`, `disabled`, `failed {error}` or `needs_auth {error}` (with `integrationID` for OAuth) | **API** `GET /api/mcp` |
| Connect or disconnect at runtime, overriding `disabled` until restart | **Exp** `POST /api/experimental/mcp/{server}/connect` and `…/disconnect` |
| Add or replace a server at runtime (local or remote config) | **Exp** `PUT /api/experimental/mcp/{server} {config}` |
| Remove at runtime (until restart) | **Exp** `DELETE /api/experimental/mcp/{server}` |
| OAuth sign-in for `needs_auth` | **API** through the server's `integrationID` ([§10](#10-integrations-and-credentials-connect-api)) |
| Resource and template catalog `{server, name, uri or uriTemplate, description, mimeType}` | **API** `GET /api/mcp/resource` |
| MCP prompts appear as commands (`<server>:<prompt>`) | **API** (commands) |
| Persistent configuration under `mcp.servers.<name>`: `type` (local or remote), `command`, `cwd`, `environment`, `url`, `headers`, `oauth {client_id, client_secret, scope, callback_port, redirect_uri, auth_server_metadata_url}` or `false`, `disabled`, `codemode`, `timeout {startup, catalog, execution}`, `protocol` (legacy, auto or 2026-07-28) | **Config** |
| Events | `mcp.status.changed {server}`, `mcp.resources.changed {server}` |
| CLI management (`opencode mcp add/list/auth/logout`) | **CLI** |

---

## 18. Plugins and plugin RPC

| Feature | Control |
| --- | --- |
| List enabled plugins: `{id?, source (builtin, package {target, version, outdated, updating}, local {path} or sdk), features {server, tui, rpc}, state (active, or failed with an error)}` | **API** `GET /api/plugin` |
| Check for updates (one or all) | **API** `POST /api/plugin/check {target?}` |
| Update package plugins | **API** `POST /api/plugin/update {targets[]}` |
| Install, remove, or enable and disable with wildcard or `-id` rules | **Config** (`plugins` array) / **CLI** `opencode plugin add/remove` |
| Call a plugin RPC method | **API** `POST /api/rpc/{rpcID}/{method} {input}` → `{output}` |
| Plugin RPC events | **API** events typed `rpc.<rpcID>.<event>` with `location` and `data` |
| TUI (CLI) plugins in `cli.json` | **Client**. Terminal-only, and they don't apply to Android. |
| Change notification | **API** `plugin.updated` |

---

## 19. References

| Feature | Control |
| --- | --- |
| List references `{name, path, description?, hidden?, source (local, or git with repository and branch)}` | **API** `GET /api/reference` |
| Attach a reference to a prompt | **API**. Pass the reference path as a directory `file:` attachment, which gives the model a non-recursive listing. |
| Define references: local paths or git repositories cloned to `~/.local/share/opencode/repos/…` and refreshed every 24 h | **Config** |
| Change notification | **API** `reference.updated` |

---

## 20. Attachments

See [§6](#6-prompt-input-api). Limits: 20 MiB decoded per item. Images are normalized by the `media.image` config
(**Config**). The desktop picker caps a selection at 20 MiB in total; other clients may set lower limits.

---

## 21. Snapshots, undo, redo and revert **API**

| Feature | Detail |
| --- | --- |
| Automatic snapshots before and after each model step (Git-based, stored under the data directory) | **Read**. `assistant.snapshot {start, end, files}` |
| `/undo`: stage a rollback to before a user message, restore files, and put the prompt back in the composer | `POST …/revert/stage {messageID, files: true}` |
| `/redo`: cancel the staged rollback | `DELETE …/revert` |
| Accept the rollback when the next prompt is sent | `POST …/revert/commit`, then send the prompt |
| Revert to an earlier message | Stage with that `messageID` |
| Events | `session.revert.staged {revert}`, `session.revert.cleared`, `session.revert.committed {to}` |
| Turn snapshots on or off | **Config** `snapshots: false` |

Reference clients, before staging, interrupt a running session and cancel pending user inbox items. The server
rejects a revert while the session is busy (`409 SessionBusyError`).

---

## 22. Compaction

| Feature | Control |
| --- | --- |
| Manual compaction | **API** `POST …/compact` |
| Progress | **API** events `session.compaction.started {reason, recent}`, `.delta {text}` (the streamed summary), `.ended`, `.failed`, and `compaction` messages |
| `compaction.auto`, `keep.tokens`, `buffer`; per-provider or per-model `settings.compaction: native or summary` | **Config** |

---

## 23. Warming **Config**

`warming: true` or `{prompt, interval, duration}` sends periodic keep-alive model calls to keep provider caches warm.
It has no API and no timeline footprint: warming responses are discarded.

---

## 24. Web search

| Feature | Control |
| --- | --- |
| List providers (Exa, Firecrawl, Parallel, Tavily, plus Console) | **API** `GET /api/websearch/provider` |
| Run a search directly | **API** `POST /api/websearch {query, providerID?}` → `{providerID, results[{url, title, content, time.published}]}` |
| First-use consent and provider choice | **API** (a form of kind `websearch.provider`) |
| Credentials | **API** (integrations) / **CLI** (environment) |
| `websearch.provider` (or `random`), `websearch: false` | **Config** |
| Change notification | **API** `websearch.updated` |

---

## 25. Formatters **Config**

`formatter: true`, or an object with built-in overrides and custom `{command[], environment, extensions[], disabled}`.
There are 26 built-ins (prettier, biome, gofmt, rustfmt, ruff, ktlint and others). There is no API; effects show up
only in file diffs.

---

## 26. Instructions

| Feature | Control |
| --- | --- |
| `AGENTS.md` discovery (global, then the workspace up to home or the project root); nested files load on read; live edits send updates | **Config** (files) |
| Instruction changes visible to clients (which sources changed, not their contents) | **API** event `session.instructions.updated {delta: {source: hash or "removed"}}` |
| Session-specific instruction entries (the API-managed sixth instruction layer) | **Exp** instruction-entry endpoints ([§4.2](#42-session-operations)) |
| `instructions` config array | **N/A**. Accepted, but not loaded in V2. |
| `CLAUDE.md` fallback | **N/A** |
| `OPENCODE_DISABLE_PROJECT_CONFIG=1` | **CLI** |

---

## 27. Filesystem **API**

| Feature | Endpoint |
| --- | --- |
| Read a file, relative to the location (raw bytes) | `GET /api/fs/read/<path>` |
| List a directory (absolute or relative path, including parents and siblings) | `GET /api/fs/list?path=` → `[{path, type: file or directory}]` |
| Ranked recursive find (for `@` completion) | `GET /api/fs/find?query=&type=file or directory&limit=` |
| Write a file (raw body; any absolute path; creates parent directories) | **Exp** `POST /api/experimental/fs/write?path=` |
| Live changes | Event `filesystem.changed {file, event: add, change or unlink}` |
| File watcher ignore list | **Config** `watcher.ignore` |

---

## 28. Version control and diffs **API**

| Feature | Endpoint |
| --- | --- |
| Branch info (current and default), VCS provider | `GET /api/vcs` |
| Review base, inferred from reflog or the default branch | `GET /api/vcs/base` → `{name, ref, source}` or `null` |
| Working-copy status | `GET /api/vcs/status` → `[{file, additions, deletions, status}]` |
| Branches (search, limit) | `GET /api/vcs/branch` |
| Diffs: `working` (HEAD → working copy), `branch` (merge-base → working copy), `committed` (merge-base → HEAD); optional `base` override and `context` lines | `GET /api/vcs/diff?mode=&base=&context=` |
| Per-turn session diffs ("Last turn") | `GET /api/session/{id}/diff` |
| Branch changes | Event `vcs.branch.updated {branch}` |

No commit, push or checkout endpoints exist. Those happen through the agent's shell tool, a user `!` shell, or a
terminal.

---

## 29. Worktrees **API**

| Feature | Endpoint |
| --- | --- |
| List the saved worktree inventory for a project | `GET /api/worktree?projectID=` → `[{directory, strategy?}]` |
| Create (runs the project's setup script) | `POST /api/worktree {projectID, from?, branch?, directory?, name?}` → `{directory}` |
| Remove | `DELETE /api/worktree {projectID, directory, force}` |
| Rediscover and reconcile | `POST /api/worktree/refresh {projectID}` |
| Move a session into a worktree | `POST /api/session/{id}/move {directory}` |
| Events | `worktree.updated {projectID}`, `worktree.resolved {projectID, directory, previous, adopted?}` |
| Parent directory for new worktrees; plugin worktree strategies | **Config** `worktree.directory` / plugins |

---

## 30. Shell commands (non-interactive) **API**

| Feature | Endpoint |
| --- | --- |
| List running commands for a location | `GET /api/shell` |
| Run a command (combined output captured to a file) | `POST /api/shell {command, cwd?, timeout?, metadata?}` → `Shell.Info` |
| Status and exit code | `GET /api/shell/{id}` |
| Page through output by byte cursor | `GET /api/shell/{id}/output?cursor=&limit=` → `{output, cursor, size, truncated}` |
| Kill and remove | `DELETE /api/shell/{id}` |
| Events | `shell.created {info}`, `shell.exited {id, exit, status}`, `shell.deleted` |
| Shell selection | **API** `GET /api/config/shell` (available shells) and **Exp** `PATCH /api/experimental/config {shell}` |

---

## 31. PTY terminals **API**

| Feature | Endpoint |
| --- | --- |
| List PTYs, including exited ones kept until removal | `GET /api/pty` |
| Create a PTY | `POST /api/pty {command?, args?, cwd?, title?, env?}` |
| Get a PTY (with its exit code) | `GET /api/pty/{id}` |
| Rename or resize | `PUT /api/pty/{id} {title?, size {rows, cols}}` |
| Kill and remove | `DELETE /api/pty/{id}` |
| Stream I/O over a WebSocket | `GET /api/pty/{id}/connect?cursor=<n>` (WebSocket upgrade). Native clients authenticate with Basic auth; browsers use a `ticket`. |
| Ticket for header-less WebSocket clients | `POST /api/pty/{id}/connect-token` with header `x-opencode-ticket: 1` → `{ticket, expires_in}` (single use) |
| Events | `pty.created`, `pty.updated {info}`, `pty.exited {id, exitCode}`, `pty.deleted` |

**Wire protocol.** Outbound frames are raw UTF-8 terminal output, with replay sent in chunks of up to 64 KiB. After
the replay the server sends one control frame, a `0x00` byte followed by JSON `{"cursor": n}`. Save that cursor to
resume with `?cursor=n`. `-1` tails from the current end, and omitting the cursor replays the full retained buffer.
Inbound frames are UTF-8 text typed by the user. Resize goes over REST (`PUT size`). Close code `4404` means the
session was not found or has exited.

---

## 32. Persistent PTYs **Exp**

Session-bound terminals that survive across attachments and can be read by the agent:

| Feature | Endpoint |
| --- | --- |
| List or create a session's terminals | `GET` and `POST /api/experimental/session/{id}/terminal` (`{command?, args, cwd?, title, env, size?}`) |
| Read the most recently controlled terminal (screen text, cursor, foreground process) | `GET /api/experimental/session/{id}/terminal/read?lines=` |
| Get, update (size, attachmentID), remove | `GET`, `PUT`, `DELETE /api/experimental/persistent-pty/{id}` |
| Snapshot (text, checkpoint, cursor) | `GET /api/experimental/persistent-pty/{id}/snapshot` |
| Connect over a WebSocket | `GET …/{id}/connect?cursor=&role=&attachment_id=&takeover=&input_protocol=&ticket=`, plus `…/connect-token` |
| Shutdown or handoff (process lifecycle) | `POST /api/experimental/persistent-pty/shutdown` and `…/handoff` |
| Events | `persistent-pty.added {sessionID, terminal}`, `persistent-pty.removed {sessionID, ptyID}` |

---

## 33. Configuration

### 33.1 Files and precedence **Config**

`~/.config/opencode/opencode.json(c)` (global) → direct `opencode.json(c)` files from the farthest directory to the
closest → `.opencode/opencode.json(c)` files in the same order. The last one wins for scalar values; arrays such as
`plugins`, `skills` and `permissions` combine. The schema lives at `https://opencode.ai/config.json`. Files reload
automatically. V1 fields are normalized in memory.

### 33.2 Top-level keys

All of these are **Config**. `shell` is the only one with an API setter (**Exp**).

| Key | Purpose |
| --- | --- |
| `shell` | Shell for the terminal and shell tools (**Exp** `PATCH /api/experimental/config {shell}` sets it in global config) |
| `model` | Default `provider/model` |
| `default_agent` | Default primary agent |
| `update` | `disable`, `notify` or `auto` (global only) |
| `share` | `manual`, `auto` or `disabled`. Accepted, but sharing is unsupported. |
| `enterprise` | Enterprise settings |
| `username` | Accepted, but not displayed |
| `permissions` | Ordered permission rules |
| `agents` | Agent definitions and overrides |
| `snapshots` | Turn filesystem snapshots on or off |
| `watcher` | `{ignore: []}` |
| `formatter` | Formatters |
| `lsp` | Accepted, but V2 runs no LSP |
| `media` | Image normalization |
| `tool_output` | `{max_lines, max_bytes}` |
| `mcp` | `{timeout, servers}` |
| `compaction` | `{auto, keep.tokens, buffer}` |
| `skills` | Extra skill paths or URLs |
| `commands` | Command definitions |
| `instructions` | Accepted, but not loaded |
| `references` | Named local or git references |
| `websearch` | `{provider}` or `false` |
| `plugins` | Plugin entries and enable or disable rules |
| `worktree` | `{directory}` |
| `warming` | Cache warming |
| `providers` | Providers and models |
| `experimental` | `policies`, `subagent_depth`, `portable_shell_scanner` and more |

### 33.3 Configuration API **API**

| Feature | Endpoint |
| --- | --- |
| Configuration documents and discovery sources for a location, lowest to highest priority (each document carries its parsed `info` and `path`) | `GET /api/config` → `Config.Entry[]` (`document {path, info}` or `directory {path}`) |
| Available shells | `GET /api/config/shell` → `[{path, name, acceptable}]` |
| Patch global config (currently `shell` only) | **Exp** `PATCH /api/experimental/config` |
| Reload every location | `POST /api/location/reload` |
| Change notification | Event `config.updated` |

### 33.4 Terminal client settings (`~/.config/opencode/cli.json`) **Client**

Theme, animations, cursor, mouse, scroll, prompt (editor context, paste mode, image preview), session display
(sidebar, scrollbar, thinking, grouping, image preview, tps, markdown, new-session location, permissions
prompt or autoaccept), tabs, diffs, attention (notifications, sounds, volume, sound pack, per-event sounds), terminal
(title, copy), mini mode, keybinds, CLI plugins, debug, experimental. None of these reach the server. The Android app
should offer its own equivalents where they make sense.

### 33.5 Editing configuration remotely

The API cannot mutate configuration beyond `shell`. A client can still manage **Config**-only features by editing
files on the server:

- Read the raw text with `GET /api/fs/read/<path>` (relative to a location).
- Write it with **Exp** `POST /api/experimental/fs/write?path=<abs path>`.
- The file watcher reloads it. `POST /api/location/reload` forces a reload.

This covers `opencode.jsonc`, `.opencode/agents/*.md`, `.opencode/commands/*.md`, `.opencode/skills/**` and
`AGENTS.md`. Because this path is experimental and bypasses validation, clients should validate against the published
JSON Schema and show `GET /api/config` diagnostics afterwards.

---

## 34. Themes **Client**

V2 themes are JSON token trees: a `base` tree, `light` and `dark` hue palettes, a `categorical` list for agent
colors, and `syntax`, `diff`, `markdown`, `background.raised` and `@dialog` surfaces. There are 33 built-ins (opencode,
tokyonight, catppuccin, dracula, gruvbox, nord and others). They live in `~/.config/opencode/themes/*.json` and
`.opencode/themes/*.json`. Themes belong to the terminal client, and the server does not expose them. An app can
import theme JSON files through the filesystem API to match the user's TUI theme.

---

## 35. Stats, import and export

| Feature | Control |
| --- | --- |
| `opencode stats` (days, models, cost, JSON) | **Exp** `GET /api/experimental/session/stats` |
| `opencode session export [--sanitize]` and `import` | **Exp** export and import endpoints |
| `opencode session list` and `delete` | **API** |

---

## 36. Event catalog **API**

`GET /api/event` is an SSE stream of `data: <json>` frames. The first event is always `server.connected`. A
`: heartbeat` comment arrives every 15 s. Each envelope is `{id, created, type, location?, data, metadata?}`. Durable
events also carry `durable {aggregateID, seq, version}`. The stream is live-only, and an overflow of 4,096 queued
frames closes it. Clients must resync from REST after reconnecting.

| Group | Events |
| --- | --- |
| Connection | `server.connected`, `location.shutdown` |
| Catalog invalidation (payload `{}`, so refetch) | `models-dev.refreshed`, `integration.updated`, `credential.updated`, `provider.updated`, `model.updated`, `agent.updated`, `command.updated`, `skill.updated`, `config.updated`, `plugin.updated`, `reference.updated`, `websearch.updated` |
| Credentials | `credential.switched {integrationID, credentialID}` |
| Session lifecycle | `session.created`, `session.deleted`, `session.forked`, `session.renamed`, `session.moved`, `session.metadata.updated`, `session.permissions`, `session.viewed`, `session.usage.updated`, `session.agent.selected`, `session.model.selected`, `session.status`, `session.idle` |
| Inbox | `session.inbox.enqueued {inboxID, item}`, `session.inbox.delivered`, `session.inbox.cancelled`, `session.inbox.delivery.changed` |
| Execution | `session.execution.started`, `.succeeded`, `.failed {error}`, `.interrupted {reason}` |
| Instructions | `session.instructions.updated {delta, text?}` |
| Input echoes | `session.synthetic`, `session.skill.activated`, `session.shell.started`, `session.shell.ended {output}` |
| Model steps | `session.step.started {assistantMessageID, agent, model, snapshot}`, `session.step.streamed`, `session.step.ended {finish, cost, tokens, snapshot, files}`, `session.step.failed {error}`, `session.retry.scheduled {attempt, at, error}` |
| Text and reasoning streaming | `session.text.started`, `session.text.delta`, `session.text.ended`, `session.reasoning.started`, `session.reasoning.delta`, `session.reasoning.ended` (all keyed by `assistantMessageID` and `ordinal`) |
| Tool calls | `session.tool.input.started {id, name}`, `.input.delta`, `.input.ended`, `session.tool.called {input, executed}`, `session.tool.progress {metadata}`, `session.tool.success {content, metadata}`, `session.tool.failed {error}` |
| Compaction | `session.compaction.started`, `.delta`, `.ended`, `.failed` |
| Revert | `session.revert.staged`, `.cleared`, `.committed` |
| Permissions | `permission.asked`, `permission.replied` |
| Forms | `form.created`, `form.replied`, `form.cancelled` |
| Shells | `shell.created`, `shell.exited`, `shell.deleted` |
| PTY | `pty.created`, `pty.updated`, `pty.exited`, `pty.deleted`, `persistent-pty.added`, `persistent-pty.removed` |
| Workspace | `project.updated`, `worktree.updated`, `worktree.resolved`, `vcs.branch.updated`, `filesystem.changed` |
| MCP | `mcp.status.changed`, `mcp.resources.changed` |
| Installation | `installation.update-available`, `installation.updated` |
| TUI remote control | `tui.prompt.append {text}`, `tui.command.execute {command}`, `tui.toast.show {title, message, variant, duration}`, `tui.session.select {sessionID}` |
| Plugin RPC | `rpc.<rpcID>.<event>` |

Deltas and progress events (`*.delta`, `tool.progress`, `usage.updated`) are ephemeral. Everything that changes the
projected history is durable.

---

## 37. Errors **API**

JSON errors have the shape `{_tag, message, …}`. Tags include `UnauthorizedError` (401), `ForbiddenError` (403),
`InvalidRequestError {kind, field}` (400), `InvalidCursorError`, `ConflictError {resource}` (409), `SessionBusyError`
(409), `*NotFoundError` (404), `ServiceUnavailableError` (503, for example while the model catalog is still settling),
`CommandExecutionError`, `FormAlreadySettledError`, `InstructionEntryValueTooLargeError` (413), `RpcInternalError`
and `UnknownError` (500). Session and model errors inside the timeline are `StructuredError {type, message, status?}`,
for example `provider.auth` with status 403.

---

## 38. Client-side features to replicate natively **Client**

These features live in the TUI or web app rather than on the server. Each one maps onto server APIs that already
exist, so a native client can offer the same experience.

| Area | TUI / web behavior |
| --- | --- |
| Session tabs | Multiple open sessions, next or previous, next unread, close and reopen, 10 numbered slots, history back and forward |
| Session list | Search, pin and unpin, quick-switch slots 1–9, recent sessions and projects (Ctrl+O), timeline dialog |
| Composer | Multi-line input, steer (Enter) vs queue (Alt+Enter), `@file#20-45` mentions, `!` shell mode, `/` command palette, `/btw` side questions, external editor, compact paste placeholders, image previews, history (up and down), **stash** (stash, pop, list), skill selector, editor-context attachment |
| Models and agents | `/models` with favorites and a provider shortcut, recent cycling (F2), favorite cycling, variant cycling and list, `/agents`, agent cycling (Shift+Tab) |
| Session actions | New, rename, delete, export transcript to an editor, copy transcript, move (cd), fork from a message, compact, interrupt (Esc), background (Ctrl+B), undo and redo, queued-prompt manager (undo or delete a queued prompt), toggle thinking, toggle exploration grouping |
| Subagents | Subagent picker in the composer, next and previous child, jump to parent, interrupt a subagent |
| Shells in the composer | List running shells, view output (follow, scroll), kill |
| Diff viewer (`/diff`) | Scopes All, Committed, Uncommitted and Last turn; base branch selection; file tree; split or unified; single-patch mode; wrap; hunk and file navigation; mark reviewed |
| Requests | Permission prompt (full-screen toggle), auto-approve toggle, question dock, web search consent dock |
| Dialogs | MCP server toggles and authentication, plugin manager (install, update, check, toggle), `/connect` providers and accounts (add, activate, rename, delete) |
| Status and debug | Status view (server, version, MCP, plugins), debug info, restart service, pair device (QR), help, docs, which-key |
| Attention | System notifications when unfocused; sounds for `default`, `question`, `permission`, `error`, `done` and `subagent_done`; volume; sound packs |
| Appearance | Themes and light/dark mode lock, tokens-per-second display, rendered or source markdown, image previews, sidebar, scrollbar |
| Web app extras | Server registry (several servers, a default server, health indicators, SSH and WSL helpers), pairing page (show a QR to pair other devices), file tree and file viewer, review panel with line comments attached to prompts, terminal panel, usage-exceeded dialogs, notification settings |
| `opencode mini` and `opencode run` | Minimal UI and non-interactive automation (`--format json`, `--file`, `--continue`, `--agent`, `--model`) |

---

## 39. CLI command → API mapping

| CLI | API equivalent |
| --- | --- |
| `opencode run "…"` / `--continue` / `--file` / `--agent` / `--model` | `session.create` + `session.prompt` (plus attachments) + events |
| `opencode session list, delete, export, import` | `session.list`, `session.remove`, experimental export and import |
| `opencode auth list, login, logout, switch` | `integration.list`, `integration.connect.*`, `credential.remove`, `credential.activate` |
| `opencode models` | `model.list` |
| `opencode mcp list, add, auth, logout` | `mcp.list`, `experimental.mcp.add` (runtime only), OAuth through integrations, `credential.remove` |
| `opencode plugin list, check, update` (and `add`, `remove`, which are config) | `plugin.list`, `plugin.check`, `plugin.update` |
| `opencode stats` | `experimental.session.stats` |
| `opencode reload` | `location.reload` |
| `opencode pair` | `POST /api/pair` + `GET /auth/connect/{code}` |
| `opencode api …` | Any operation |
| `opencode debug config, agents, paths` | `config.get`, `agent.list` (paths are CLI only) |
| `opencode service …`, `serve`, `upgrade`, `uninstall`, `acp`, `mini` | CLI only |

---

## 40. Other surfaces (context only)

| Surface | Relevance |
| --- | --- |
| **Web UI** | Served by the same server; `opencode pair` signs a browser in. It is also a PWA with a QR scanner. |
| **Desktop app** | macOS, Windows and Linux. Its attached browser drives the `browser` tool namespace. |
| **ACP** (`opencode acp`) | Agent Client Protocol over stdio for editors such as Zed. It runs a private server and has no network port, so it isn't usable from Android. |
| **`@opencode/client`** | The TypeScript HTTP client, generated from the same contract. It is the reference for request and response shapes and event handling. |
| **`@opencode/sdk`** | Embeds the server in-process (Node or Cloudflare Durable Objects). Not relevant to Android. |
| **Effect APIs** | Effect variants of the plugin, client and SDK APIs. Not relevant. |
| **Plugins API** (`@opencode/plugin`) | Server-side extension: transforms, hooks, tools, RPC, storage, integrations, worktree strategies. Its effects show up in the catalogs and events above. |

---

## 41. OpenCode Console (hosted service)

Console is a separate service at `opencode.ai/console`, reached with a Console API key. The local server does not
proxy it.

- Zen inference, with curated models and pay-as-you-go pricing
- **Go**, a $10/month plan for open models
- BYOK gateway
- Hosted web search
- Budgets API (`/api/v1/budgets/members`)
- Organization policies, which reach V2 clients as policy statements through the `opencode` provider

The app sees Console only indirectly: the `opencode` provider and its models, `session.status` retry actions such as
usage-exceeded links, and policy denials. A Console-key budget view is possible, but it is out of scope for a
local-server client.

---

## 42. Not supported in V2

Session sharing, LSP servers, tools and diagnostics, the `instructions` config array, the `CLAUDE.md` fallback, sending
per-agent `request` overlays, mDNS discovery, the V1 plugin API, the V1 server API, and the `scout` agent.

---

## 43. Summary for the Android client

- **138 HTTP operations**: the 136 in the published spec plus `POST /api/pair` and `GET /auth/connect/{code}`.
  Also 1 SSE event stream with **94 event types** (93 named types plus the `rpc.*` family), 1 SSE session log, and
  2 WebSocket endpoints (PTY and persistent PTY).
- **Everything session-related can be driven remotely.** That covers creating, prompting, steering or queueing,
  interrupting, backgrounding, compacting, undo and redo, forking, moving, diffs, permissions, questions and other
  forms, subagents, shells, terminals and worktrees.
- **Accounts and runtime state can be driven remotely:** provider and integration login, credentials, MCP runtime
  state and OAuth, plugin updates, and saved permissions.
- **Definitions are file-based:** agents, commands, skills, providers, models, policies, formatters, snapshots,
  compaction and warming. The app can show all of them (catalog APIs plus `GET /api/config`). It can edit them only
  through the experimental file-write route.
- **Server lifecycle stays on the host machine:** hostname, port, password, CORS, environment variables, upgrades.
  The app should explain these steps rather than try to perform them.
