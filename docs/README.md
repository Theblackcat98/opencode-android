# OpenCode for Android

A native Android client for [OpenCode](https://opencode.ai) **V2**. It connects to an OpenCode server on your
local network, or any network your phone can reach, and drives the server's full feature set from your phone.

The app is a client: sessions, configuration, credentials, permissions and tool execution all live on the
server. The app keeps a live event stream to each server, reads state from the server, and never guesses it.
This document is the front door: what the project wants, what is true today, what the app does, how to build
it, and where to read next.

## What we want out of this

**Goals**

- Pair with an OpenCode V2 server in seconds — scan the QR code from `opencode pair` — and manage several servers.
- Offer the full session experience on the phone: watch sessions live, prompt, steer or queue, answer permissions
  and questions, review diffs, undo, fork, and work with subagents, shells, terminals and worktrees.
- Manage the server's runtime from the phone: integration accounts, MCP servers, plugins, saved approvals, and
  configuration.
- Stay useful in the background, with actionable notifications for permission requests, questions and completed
  turns.
- Follow modern Android practice: Material 3, adaptive layouts for phones, tablets and foldables, accessibility,
  and secure credential storage.

**Non-goals**

- Running OpenCode on the phone. The app is always a client of a server.
- OpenCode V1 servers. V2's server API is an intentional breaking change.
- The hosted OpenCode Console (billing, budgets). A possible later extension.
- ACP. It is stdio-only.

**Definition of done, every phase.** Unit and integration tests cover the phase's scenarios; the coverage
matrices are updated; screenshots are refreshed; the in-app help reflects the new features; CI is green. The
plan's full statement is §5.5 of [`ANDROID_APP_PLAN.md`](./ANDROID_APP_PLAN.md).

**Where the work stands.** The P0–P10 plan is complete. Remaining work is tracked as GitHub issues labeled
`no-device` (verifiable with unit tests and CI) or `needs-device` (requires on-device testing); the plan
describes this as Phase 11. There is no shipped release: the release candidate has not passed the manual test
matrix and no signed artifact exists. Both are stated plainly below.

## Status — the honest version

**Coverage is complete and enforced.** 138 of 138 API operations are declared, wired and tested, and 94 of 94
event types are handled and tested. The claim is derived from the code by `tools/audit-coverage.mjs` and
asserted by `CoverageMatrixTest` in CI, not written down and trusted; the audit of what the matrices once
claimed and the code did not back is in [`COVERAGE.md`](./COVERAGE.md).

**The release candidate has not passed the manual test matrix.** The matrix has been run only in part, on one
Android 16 (API 36) phone emulator: the `fdroid` debug build throughout, the `play` debug build for two rows,
against a disposable 2.0.18 dev server (everything that changes state) and the maintainer's real 2.0.20 server
(read-only), plus one self-signed HTTPS endpoint. There was no physical device, no second device, no tablet,
no TalkBack run, and no release build of either flavor. Several rows fail (B10, J1–J6, J9, J11–J13, K6) and
many are n/a for want of hardware. The per-row record is [`MANUAL_TEST_MATRIX.md`](./MANUAL_TEST_MATRIX.md);
what it means for shipping is in [`RELEASE.md`](./RELEASE.md).

**No signed artifact exists.** There is no signing material in the tree, and none was created.
`assemblePlayRelease` produces `app-play-release-unsigned.apk`, named for what it is.
`verifyPublishableRelease` fails closed until signing material is present.

**Open decisions remain.** The plan's §10 lists the product ones (identity, distribution, minimum Android
version, form-factor priority, remote configuration editing, background default); the reviews in
[`REVIEWS.md`](./REVIEWS.md) name the owner decisions that security and privacy turn on. None of them blocks
development.

## What the app does

The phase-by-phase scope is §6 of the plan; the order a user notices changes in is
[`CHANGELOG.md`](./CHANGELOG.md). This is the map.

**Servers and connection**

- Pair by scanning `opencode pair`'s QR code, by pasting or sharing the link, or by typing an address and a
  password. A pairing token is stored encrypted in the Android Keystore.
- A server registry with several profiles, a default server, health dots, an edit screen, and a visible
  "unencrypted" badge on any `http://` server that is not on loopback.
- A live event stream per server with a connection history, an idle watchdog, reconnect with backoff, and a
  resync on every `server.connected`.
- A server status page with the server's version and reachable URLs, a "test connection" check, and a
  pair-again prompt when a password or token is rotated.
- A developer event inspector with the raw JSON of every frame the app receives.
- Optional trust for CA certificates installed on the phone; an opt-in LAN probe that looks for servers on the
  phone's own private /24 and finds nothing else.

**Sessions and the timeline**

- A per-server home: the projects the user added, each with its newest root sessions, and a pending-requests
  inbox across all sessions.
- A session list with search, a roots-only filter with child counts, project and directory filters, cursor
  paging, running/retrying/unread badges, cost and token totals, and agent and model chips. A session created
  on the desktop appears as it happens; a deleted one disappears.
- A live transcript: user prompts with attachment chips, assistant answers as Markdown, collapsible reasoning
  with duration, a card per tool call, compaction blocks, idle outcome dividers, agent/model/location markers,
  and step errors with retry state. Every message type the server can send has a renderer; an unknown type
  renders as a labelled card rather than disappearing.
- Follow mode with "jump to the latest", and a session header with agent, model and variant, a context gauge
  against the model's limit, and the cost so far.
- A bounded offline cache: a session opens instantly and reads with no network at all.
- Session tools sheet, session tabs, and a session list beside the session on a wide window.

**Driving a session**

- Start a session from the projects, a directory the server has run in, or by browsing the server's
  filesystem, then pick an agent and a model.
- Steer by default, queue through a toggle or a long press on send, and `resume` for input that should wait for
  a turn. The prompt is on screen the moment you send it, and a network retry sends the same prompt rather than
  a second one.
- Stop a running turn, optionally resuming what you had already typed, or send its blocking tools to the
  background so it can finish.
- A request dock in the session and a global inbox for permissions and questions. "Always allow" shows the
  exact patterns it will store first, and a rejection can carry a note back to the agent.
- One forms engine for string, multiselect, boolean, number, integer and external-link fields, with the
  server's own conditional visibility and validation. A question renders beside the tool that asked it,
  web-search consent as a dialog, and an MCP elicitation as a sheet that names the server.
- Agent and model pickers: model search grouped by provider, capability badges, context size, price, variants,
  and device-side recents and favorites.
- Rename a session, edit its metadata, delete it with a warning that names the subagents that go with it, and
  copy one message or the whole conversation as text.
- A busy indicator, a retry countdown with the provider's own call to action and link, a structured error card,
  and a "no model available" empty state that says how to fix it.

**Composer and context**

- One line under the box says what a prompt will carry — message, command, shell line or app action, how many
  attachments and skills ride along, and whether it steers or queues — and what is stopping the send.
- `@` mentions of files, folders, references and agents, ranked and stable between keystrokes;
  `@src/a.ts#20-45` attaches those lines.
- `/` commands and `!` shell lines: the server's commands and MCP prompts, plus the app's own `/new`,
  `/sessions`, `/models`, `/agents`, `/compact`, `/btw` and `/editor`.
- Attachments from the phone: the photo picker, the camera and any file, downscaled and re-encoded off the
  main thread; an image the selected model cannot take is sent only after the user says so. Files on the
  server attach by path, with an optional line range.
- History, stash and drafts per server and per session, on the device, with buttons rather than arrow keys and
  a full-screen editor for a long sentence. `/btw` asks a side question in a sheet; `/compact` summarises the
  session with live progress.

**Background presence and notifications**

- A foreground connection service runs while any session is busy, anything is waiting for you, or you have
  asked to stay connected, and stops after a configurable idle grace period. Its notification lists the
  running sessions and has an Interrupt button.
- A permission request notifies with *Allow once* and *Reject*. *Always allow* is two taps because it stores a
  standing rule on the server. A single free-text question can be typed into the notification itself.
- Finished turns, finished subagents, provider retries, usage limits and server updates are reported, grouped
  per server and session, and silenced by a per-session mute or quiet hours — which never silence a request.
- A session is marked seen when you actually look at it, and the count is on the app's launcher shortcut.
- Auto-approve, per session or everywhere, always for a limited time, answering "allow once" and never "allow
  always", with a visible indicator. Rules the server is configured to deny still hold.
- A home-screen widget showing sessions running and requests waiting, a Quick Settings connection tile, and
  static launcher shortcuts for Servers and New session.

**Review and history**

- The TUI's four `/diff` scopes — last turn, uncommitted, committed, and against the base branch — with a
  header that follows `vcs.branch.updated`. Patches are parsed into hunks, coloured a line at a time, and
  shown unified or side by side with a wrap toggle, a changed-files tree, next/previous file and hunk, and a
  mark-reviewed that survives leaving the screen.
- Tap a line in a diff, write what should change, and it goes with the next prompt in the web app's own
  `metadata.opencodeComment` format, with the selected lines attached as a ranged `file:` URI.
- "Undo to here" stops the session, cancels anything queued behind it, restores the files the turn changed,
  and puts the prompt back in the composer; the banner says which files will come back. Redo takes it back;
  "Fork from here" copies the session up to that message.
- Browse the server's files (`fs.list`, including directories outside the location), quick open (`fs.find`),
  and a viewer with line numbers and highlighting for text, a picture for an image, and share or download for
  anything else. Remote editing exists, is off until you turn it on as a separate switch, and asks again at
  the point of the write.
- Jump between prompts, search the transcript, export as JSON or Markdown with an option to redact what the
  server would redact, import a transcript back, and inspect which messages the model can still see.

**Execution**

- A terminal on the phone: xterm.js bundled offline in a hardened WebView — local assets only, no file access,
  no network, one JavaScript bridge whose every message is validated before the host acts on it. Frames are
  decoded per the protocol; a reconnect resumes from the cursor the server reported. A hardware keyboard and
  the clipboard both reach the shell, the grid resizes the PTY over REST, and a quick action runs the
  project's start command.
- Commands in a checkout: `shell.create` runs one, its output is polled by cursor until the server says it has
  exited, the agent's own background commands are in the same list, and killing one asks first.
- The subagent tree: sessions drawn as a family by `parentID`, with a strip above the composer showing the
  children that are still working with a stop button on each.
- Worktrees: list, create, refresh and remove, where a refusal brings the server's own reason and the second
  button says what forcing costs. A worktree row moves the session into it with `session.move`.
- Project settings: name, icon colour, emoji or URL, start command and canonical directory.

**Integrations and configuration**

- Accounts at `/connect` parity: API keys in a masked field with a show/hide toggle and `FLAG_SECURE` on the
  sheet; OAuth polled until the server says complete, failed or expired; device codes; host commands whose
  output is spotted with a copy button; cancel throughout; environment connections read-only. Stored accounts
  can be renamed, switched to and removed, the last two asking first.
- Providers, read-only, with the note that activation, endpoint and headers belong in the configuration
  editor. MCP servers with status, runtime connect/disconnect, an add form, removal, the resource catalog, and
  "Sign in" for a server that reports `needs_auth`. Plugins with what is loaded, what they extend, whether
  they failed, and update checks. A web-search test query labelled with the provider that answered.
- A configuration explorer: every top-level key of the server's published schema, in the schema's order, with
  its type, description, source documents and the server's own effective value, generated from the vendored
  schema rather than typed out.
- A validated editor for `opencode.jsonc`: read, edit, validate against the schema, confirm a plan that names
  the file, the consequence and the byte counts, write, reload, then read back from `config.get`. Comments and
  indentation survive; only the bytes of the key you changed are rewritten. Guided edits merge a new MCP
  server, permission rule, default model or agent into the existing document.
- Definition files for an agent, a command, a skill and `AGENTS.md`, with front matter written from named
  fields and the body left as your prose.
- Saved approvals per project, a session's own rules with the precedence warning shown, session instructions
  you can list, add and remove, maintenance from the phone (reload, loaded locations, evict, V1 migration, an
  update banner), and secrets redacted by the schema's own shape everywhere they could travel.

**Insights and extensibility**

- A usage dashboard from `experimental.session.stats`: an activity heatmap, a streak, cost and token totals,
  per-model usage and tool reliability, with a project row that re-queries.
- Quick ask (`experimental.generate.text`) from a screen under Manage. The home-screen widget shows the
  running and waiting counts, the Quick Settings tile starts and stops the connection service, and launcher
  shortcuts open Servers and New session.
- An RPC console for plugin `rpc.call` with JSON input and error rendering, a session event-history viewer
  (`session.log`), synthetic notes, wait-until-idle, and programmatic permission and form creation.
- TUI control events: a toast becomes a snackbar; an opt-in "follow desktop" mode mirrors the TUI's session
  and composer and is off by default; a command that would change server state is offered rather than
  performed.
- "Pair another device": the phone mints a pairing code and draws its QR link.
- Adaptive UI: a session list beside the session on windows at least 840 dp wide, session tabs, a Ctrl+P
  command palette, leader-key combinations, a home-screen widget, a Quick Settings tile, and launcher
  shortcuts.

**Not built yet.** The OpenCode theme import (read `themes/*.json` and map V2 tokens onto a Material colour
scheme), an Inbox launcher shortcut, and the release steps in [`RELEASE.md`](./RELEASE.md). They are tracked
in GitHub issues.

## Build and test

Build both flavors with:

```bash
./gradlew unitTest          # JVM unit tests, Robolectric screenshot tests included
./gradlew lintDebug         # Android Lint
./gradlew spotlessCheck     # ktlint via Spotless — the formatting gate
./gradlew assembleDebug     # play and fdroid debug APKs
```

`play` uses ML Kit's bundled barcode model; `fdroid` uses ZXing and ships no Google Play Services. The
`fdroid` build adds `.fdroid` to the application id and `-fdroid` to the version name; debug adds `.debug`.

Detekt is configured for Kotlin modules but is **not** currently a gate: `./gradlew check` fails on its
findings, and the CI step is disabled while the configuration is reviewed. Do not add a baseline.

**Screenshots are not asserted by `unitTest`.** Without a Roborazzi flag the screenshot tests neither record
nor compare, so a visual change passes silently. Record per module, and verify with:

```bash
./gradlew :feature:sessions:testDebugUnitTest -Proborazzi.test.record=true

./gradlew verifyRoborazziDebug   # compares every baseline; fails on a difference
```

**Integration tests need a real server.** They skip, not fail, without one:

```bash
eval "$(./scripts/dev-server.sh start)"
./gradlew integrationTest
./scripts/dev-server.sh stop
```

A long-lived Gradle daemon does not reliably see a client's environment, so pass the server as Gradle
properties when in doubt: `-Popencode.it.url=… -Popencode.it.password=… -Popencode.it.directory=… -Popencode.it.fakeProviderUrl=…`.

`scripts/dev-server.sh` pins `@opencode/cli@2.0.18`, uses an isolated `HOME`, and starts a scripted
OpenAI-compatible provider that serves deterministic turns.

**Fast gates.** Node, no Gradle, no network, about a second each:

```bash
node tools/audit-coverage.mjs --strict      # §7/§8 matrices vs the code
node tools/gen-event-payloads.mjs --check   # event corpus is current
node tools/check-api-drift.test.mjs
node tools/audit-viewmodels.test.mjs && node tools/audit-viewmodels.mjs
node tools/audit-strings.mjs                # reports; CI gates it
node tools/audit-accessibility.mjs          # reports; read the output
node tools/audit-matrix.test.mjs && node tools/audit-matrix.mjs
```

`tools/AGENTS.md` documents every script; root `AGENTS.md` describes how the repository is laid out and which
gates run where.

## Connecting to your server

On the computer running OpenCode V2:

```bash
opencode service set hostname 0.0.0.0   # listen on the LAN (the default is localhost only)
opencode service start
opencode pair                           # prints a one-time link and a QR code to scan from the app
```

`opencode pair` works once and expires after five minutes. Scanning it stores a 30-day token on the phone. If
you would rather not open a port, forward one over SSH with `ssh -N -L 4096:localhost:4096 user@computer` and
connect to `http://localhost:4096`, or reach the computer over Tailscale. The app's onboarding screen carries
the same instructions.

An `http://` server is permitted — most LAN servers are plain HTTP — and every non-loopback one is badged as
unencrypted. HTTPS works, including a self-hosted server with a CA the user installed, trusted per server
rather than globally.

## Documents

| Document | Contents |
| --- | --- |
| [`ANDROID_APP_PLAN.md`](./ANDROID_APP_PLAN.md) | Architecture, technology choices, the P0–P10 phase plan, and the §7/§8 coverage matrices the audit parses |
| [`OPENCODE_V2_FEATURES.md`](./OPENCODE_V2_FEATURES.md) | Every OpenCode V2 feature and how it can be read or driven through the server API: endpoints, events, auth, pairing, and limits |
| [`COVERAGE.md`](./COVERAGE.md) | The §7/§8 coverage audit: what the matrices claimed, what the code actually backed, and how the claim is checked by the build |
| [`MANUAL_TEST_MATRIX.md`](./MANUAL_TEST_MATRIX.md) | The pre-release manual matrix — devices, servers, features, accessibility — with the result of every row |
| [`RELEASE.md`](./RELEASE.md) | Release status, the compatibility record, signing, the Play track progression, the F-Droid specifics, and what a release still needs |
| [`REVIEWS.md`](./REVIEWS.md) | The security, privacy and accessibility reviews, read against the code rather than the plan |
| [`CHANGELOG.md`](./CHANGELOG.md) | What changed, in the order a user would notice it. Bundled into the app's Changelog screen |
| [`design.md`](./design.md) | The visual language: palette, typography, spacing, components |
| [`MOTION.md`](./MOTION.md) | The motion language: decisions and their bindings |
| [`app_screen_flow.html`](./app_screen_flow.html) | A generated screen-flow page built from the Roborazzi screenshot baselines |

Root [`AGENTS.md`](../AGENTS.md) is the working guide for agents and contributors: module layout, the two
verification tiers, and the conventions that differ from the defaults.
