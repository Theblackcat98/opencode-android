# OpenCode for Android

A native Android client for [OpenCode](https://opencode.ai) **V2**. It connects to an OpenCode server on your local
network, or any network your phone can reach, and drives the server's full feature set from your phone.

## Status

Phase 4, *Background presence and notifications*, is complete. The app pairs with one or more OpenCode servers,
keeps a live event stream to each of them, **drives** them from the phone, and now stays reachable while it works:
a permission request or a question reaches a locked phone, and can be answered there. The phase table is in
[`docs/ANDROID_APP_PLAN.md`](docs/ANDROID_APP_PLAN.md#6-phases).

What works today:

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

Screenshot baselines are refreshed with:

```bash
./gradlew :feature:sessions:testDebugUnitTest -Proborazzi.test.record=true
```

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
