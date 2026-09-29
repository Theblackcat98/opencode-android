# OpenCode for Android

A native Android client for [OpenCode](https://opencode.ai) **V2**. It connects to an OpenCode server on your local
network, or any network your phone can reach, and drives the server's full feature set from your phone.

## Status

Phase 9, *Configuration and administration*, is complete. The app pairs with one or more OpenCode servers,
keeps a live event stream to each of them, **drives** them from the phone, stays reachable while it works, says
what a prompt is about to carry before it sends it, shows what the agent changed and lets you take it back, gives
you a real terminal, a command panel, the subagent tree and parallel worktrees, manages the server's own
accounts, providers, MCP servers and plugins, and now reads and edits the server's configuration from the
phone. The phase table is in [`docs/ANDROID_APP_PLAN.md`](docs/ANDROID_APP_PLAN.md#6-phases).

What works today:

- **The configuration explorer.** Every top-level key of the server's published configuration schema, in the
  schema's own order, with its type, description, source documents and the server's own effective value. The row
  list is generated from the vendored schema rather than typed out, and a test asserts it against that file in
  both directions — so a key nothing sets still has a card, and a card for a key the schema does not declare
  would fail the build.
- **A validated editor for `opencode.jsonc`.** Read with `fs.read`, edited, validated against the vendored
  schema, confirmed with a plan that names the file, the consequence, the byte counts and whether privileges
  change, written with `experimental.fs.write`, reloaded with `location.reload`, and then read back from
  `config.get` — the server's own answer is the diagnostic. Comments and indentation survive; only the bytes of
  the key you changed are rewritten.
- **Guided edits for the four common ones** — a persistent MCP server, a permission rule, the default model and
  a new agent — each merged into your existing document rather than replacing it, and each validated before the
  confirmation is reachable.
- **Definition files.** An agent, a command, a skill and `AGENTS.md`, with front matter written from named
  fields and the body left as your prose.
- **Permissions and instructions.** Saved approvals per project, a session's own rules with the precedence
  warning shown rather than hidden, and session instructions you can list, add and remove.
- **Maintenance from the phone.** Reload with its consequence spelled out, the loaded locations, an evict behind
  a confirmation that names the directory, the V1 migration, and an update banner carrying the host's upgrade
  command.
- **Secrets stay secret.** A configuration file holds API keys, and this is the phase that reads the whole file,
  so values are redacted by the schema's own shape at every point they could travel — the explorer, a
  diagnostic, a parse failure, a write plan and a screenshot. A rejected credential-shaped value reports the
  allowed values and a character count, never the value.
- And from Phase 8:

- **Accounts, at `/connect` parity.** Every integration, the ways into it and the logins that already exist. An
  **API key** is asked for in a masked field with a show/hide toggle, the method's own form (an Azure resource
  name, a GHE domain) is filled through the *same* forms engine a `question` tool uses, and the sheet is marked
  `FLAG_SECURE` so the task switcher cannot snapshot a key. **OAuth** opens the provider in a browser tab, shows
  the server's own instructions, and — because the server is authoritative and a redirect says nothing about
  whether access was granted — polls until it says complete, failed or expired. A **device code** is typed in and
  submitted, and a code printed by a **host command** is spotted in the output with a copy button. **Cancel is
  available throughout**, and an environment connection is shown read-only with the command that sets it. Stored
  accounts can be renamed, switched to and removed, and the last two ask first.
- **Providers, read-only.** Activation state, package and endpoint, with the note that activation, endpoint and
  headers are configuration and belong in the configuration editor.
- **MCP servers.** The list with each server's status, connect and disconnect at runtime, add a server through a
  form (a local command or a remote URL, the three timeouts, Code Mode and the protocol revision), remove one,
  and the resource catalog with "attach to prompt". A server that reports `needs_auth` offers "Sign in", which opens
  the integration it names.
- **Plugins.** What is loaded, from where, what it extends and whether it failed; check for updates; update the
  packages that have one.
- **Web search.** The configured providers and a test query, labelled with the provider that *answered* rather than
  the one that was asked for.
- And from Phase 7:


- **A terminal on the phone.** xterm.js bundled offline in a hardened WebView — local assets only, no file access,
  no network, and one JavaScript bridge whose every message is validated before the host acts on it. Frames are
  decoded per the protocol: raw UTF-8 for output, one `0x00`-prefixed JSON frame for the cursor, and a reconnect
  that resumes from the cursor the server reported rather than replaying scrollback you have already read. The
  extra-keys row carries Esc, Tab, Ctrl, Alt, the arrows and `| ~ /`; a hardware keyboard and the clipboard both
  reach the shell; the grid follows the layout and resizes the PTY over REST; titles follow `pty.updated`; shell
  choices come from `config.shell`; and a quick action runs the project's own start command.
- **Commands in a checkout.** `shell.create` runs one, its output is polled by cursor until the server says it
  has exited, the agent's own background commands are in the same list, and killing one asks first.
- **The subagent tree.** Sessions from `session.list` drawn as a family by `parentID`, with a subagent card that
  opens the child it names, jumps to the parent and between siblings, and a strip above the composer showing the
  children that are still working with a stop button on each.
- **Worktrees.** List, create from a ref, branch and name, refresh, and remove — where a refusal brings the
  server's own reason and the second button says what forcing costs. A worktree row moves the session into it with
  `session.move`, delivery passed through unchanged.
- **Project settings.** Name, icon colour, emoji or URL, start command and canonical directory.
- And from Phase 6:

- **A review of what the agent changed**: the TUI's four `/diff` scopes — last turn, uncommitted, committed and
  against the base branch — with the branch, the base and the changed-file count in a header that follows
  `vcs.branch.updated`. A patch is parsed into hunks, coloured a line at a time, and shown unified on a phone and
  side by side where the window is wide enough, with a wrap toggle, a changed-files tree, next and previous file
  and hunk, and a mark-reviewed that survives leaving the screen.
- **A comment on a line reaches the agent.** Tap a line in a diff, write what should change, and it goes with the
  next prompt in the web app's own `metadata.opencodeComment` format, with the selected lines attached as a ranged
  `file:` URI. The bar carries a badge of how many are waiting, and the list can take one back.
- **Undo, redo and fork from a message.** "Undo to here" stops the session, cancels anything queued behind it,
  restores the files the turn changed, and puts the prompt back in the composer; the banner says which files will
  come back, and the next send commits the rollback before it goes. Redo takes it back. "Fork from here" copies the
  session up to that message, opens the copy, and its header says which message it was cut at.
- **Browse the server's files and read them.** `fs.list` navigation including directories outside the location,
  `fs.find` quick open, and a viewer with line numbers and highlighting for text, a picture for an image, and share
  or download for anything else. Attach the whole file or the lines you tapped. Remote editing exists, is off
  until you turn it on, is a separate switch from everything else because it writes to the machine the agent works
  on, and asks again at the point of the write.
- **History, export and context.** Jump between prompts, search the transcript, export as JSON or as Markdown with
  an option to redact what the server would redact, import a transcript back, and an inspector that lists the
  messages the model can still see with their sizes — which is the question "is it still reading the beginning of
  this conversation?".
- And from Phase 5:

- **A prompt that says what it will carry**: one line under the box names whether it is a message, a command, a
  shell line or an app action, how many attachments and skills ride along, and whether it steers or queues. What
  is stopping the send is said next to it, with a button that clears it.
- **`@` mentions that are files, folders, references and agents**: type `@` and the list is ranked by how sure the
  client is, folders before files, and stable between keystrokes. `@src/a.ts#20-45` attaches those twenty-six
  lines. An address is not a mention, and a slash in the middle of a sentence is not a command.
- **`/` commands and `!` shell lines**: the server's own commands and its MCP prompts, plus the app's own
  `/new`, `/sessions`, `/models`, `/agents`, `/compact`, `/btw` and `/editor`. A project that defines a command of
  the same name gets it.
- **Attachments from the phone**: the photo picker, the camera and any file, downscaled to the server's image
  defaults and re-encoded off the main thread. A picture the selected model declares no image input for is sent
  only after the user says so, and a format the model is never sent is refused with a reason rather than
  dropped silently. Files on the server are attached by path, with a line range if you want one.
- **History, stash and drafts**: per server and per session, on the device, with buttons rather than the arrow
  keys a terminal would use, and a full-screen editor for a long sentence.
- **`/btw` asks a side question** about the session's context and answers in a sheet with a copy button, without
  touching the transcript. `/compact` summarises it, with live progress.
- And from Phase 4:

- **Never miss the agent**: a foreground connection service runs while any session is busy, anything is waiting
  for you, or you have asked to stay connected, and stops after a configurable idle grace period. Its ongoing
  notification lists the running sessions and has an Interrupt button.
- **Answer from the lock screen**: a permission request notifies with *Allow once* and *Reject*. *Always allow* is
  two taps, because it stores a standing rule on the server and you see the patterns first. A question that is one
  free-text field can be typed into the notification itself, validated by the same engine the screen uses.
- **Told what happened**: a finished turn, a finished subagent, a provider retry or a usage limit, and a server
  update. Grouped per server and per session, summarised, and silenced by a per-session mute or quiet hours — which
  never silence a request, because that is work that has stopped rather than news.
- **Unread, honestly**: a session is marked seen when you actually look at it, not when its turn ended, and the
  count is on the app's launcher shortcut.
- **Auto-approve when you want it**: per session or everywhere, always for a limited time, always answering
  "allow once" and never "allow always", with a visible indicator and a confirmation before it goes on. Rules the
  server is configured to deny still hold, because a denied action never asks.
- And from Phase 3:

- **Start a session**: pick a location from the projects, from a directory the server has run in, or by browsing
  the server's filesystem; then an agent and a model, with an optional title. The agents and models offered are the
  ones *that* location defines.
- **Talk to the agent**: steer by default, queue through a toggle or a long press on send, and `resume` for input
  that should wait for a turn. The prompt is on screen the moment you send it, and a network retry sends the same
  prompt rather than a second one.
- **Control a running turn**: stop it, optionally resuming what you had already typed, or send its blocking tools to
  the background so it can finish.
- **Approve and answer**: a request dock in the session, and a global inbox across every session, for permissions
  and questions. "Always allow" shows the exact patterns it will store before it stores them, and a rejection can
  carry a note back to the agent.
- **Every form, one engine**: string, multiselect, boolean, number, integer and external-link fields, with the
  server's own conditional visibility and validation. A question renders beside the tool that asked it, web-search
  consent as a dialog, and an MCP elicitation as a sheet that names the server.
- **Pickers**: agents with their colors and descriptions and a cycle button; models grouped by provider with
  search, capability badges, context size, price, variants, and recents and favorites kept on the device.
- **Manage a session**: rename it, edit its metadata, delete it with a warning that names the subagents that go
  with it, and copy one message or the whole conversation as text.
- **See what went wrong**: a busy indicator, a retry countdown with the provider's own call to action and link, a
  structured error card, and a "no model available" empty state that says how to fix it.
- And from Phase 2:

- A per-server home: the projects with their repository and checkout, the sessions with a live execution, and the
  most recent sessions.
- A session list with search, a roots-only filter with child counts, project and directory filters, cursor paging,
  and the running, retrying and unread badges, cost and token totals, and agent and model chips. A session created
  on the desktop appears in the list as it happens, and a deleted one disappears.
- A live transcript: user prompts with attachment chips, assistant answers as Markdown, collapsible reasoning with
  its duration, a card per tool call, compaction blocks, idle outcome dividers, agent, model and location switch
  markers, and step errors with their retry state. Every message type the server can send has a renderer, and a type
  this client does not know renders as a labelled card rather than disappearing.
- Follow mode with "jump to the latest", and a session header with the agent, model and variant, a context gauge
  against the model's limit, and the cost so far.
- A bounded offline cache: a session opens instantly and reads with no network at all.
- And from Phase 1:

- Pair by scanning the `opencode pair` QR code, by pasting or sharing the link, or by typing an address and a
  password. A pairing token is stored encrypted in the Android Keystore.
- A server registry with several profiles, a default server, health dots, an edit screen, and a visible
  "unencrypted" badge on any `http://` server that is not on loopback.
- A live event stream per server, with a connection history, an idle watchdog, reconnect with backoff, and a
  resync signal on every `server.connected`.
- A server status page with the server's version and reachable URLs, a "test connection" check, and a pair-again
  prompt when a password or token is rotated on the computer.
- A developer event inspector with the raw JSON of every frame the app receives.
- Optional trust for CA certificates you installed on the phone, for a self-hosted HTTPS server.

Build variants: `play` uses ML Kit's bundled barcode model, `fdroid` uses ZXing and ships no Google Play
Services. Build both with `./gradlew assembleDebug`.

```bash
./gradlew unitTest          # JVM unit tests, Robolectric screenshot tests included
./gradlew lintDebug         # Android Lint
./gradlew assembleDebug     # play and fdroid debug APKs
```

Screenshot baselines are refreshed with, and checked with:

```bash
./gradlew :feature:sessions:testDebugUnitTest -Proborazzi.test.record=true
./gradlew :feature:execution:testDebugUnitTest -Proborazzi.test.record=true
./gradlew :feature:integrations:testDebugUnitTest -Proborazzi.test.record=true
./gradlew :feature:admin:testDebugUnitTest -Proborazzi.test.record=true

./gradlew verifyRoborazziDebug   # compares every baseline; fails on a difference
```

`unitTest` runs the screenshot tests, but **without a Roborazzi record or verify flag they neither write
the baselines nor compare against them**, so a visual change passes the gate silently. Run
`verifyRoborazziDebug` when you touch a screen; only `verifyRoborazziDebug` is a real assertion.

Integration tests need the real server:

```bash
eval "$(./scripts/dev-server.sh start)"
./gradlew integrationTest
```

## Documents

| Document | Contents |
| --- | --- |
| [`docs/OPENCODE_V2_FEATURES.md`](docs/OPENCODE_V2_FEATURES.md) | Every OpenCode V2 feature, and how each one can be read or driven through the server API: endpoints, events, auth and pairing, and the feature's limits |
| [`docs/ANDROID_APP_PLAN.md`](docs/ANDROID_APP_PLAN.md) | Architecture, tech stack, and a phased plan from P0 to P10. Includes coverage matrices that map every API operation and event type to a phase. |

## Connecting to your server

On the computer running OpenCode V2:

```bash
opencode service set hostname 0.0.0.0   # listen on the LAN (the default is localhost only)
opencode service start
opencode pair                           # prints a one-time link and a QR code to scan from the app
```

`opencode pair` works once and expires after five minutes. Scanning it stores a 30-day token on the phone. If you
would rather not open a port, forward one over SSH with `ssh -N -L 4096:localhost:4096 user@computer` and connect
to `http://localhost:4096`, or reach the computer over Tailscale. The app's onboarding screen carries the same
instructions.
