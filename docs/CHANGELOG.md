# Changelog

All notable changes. The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and
this project uses [semantic versioning](https://semver.org/spec/v2.0.0.html) for the API surface of
the app, not of the server it talks to.

## [Unreleased] — 0.1.0

The first release candidate, and it is **not shippable**. The plan's exit criterion is coverage and
that is met: see [`COVERAGE.md`](./COVERAGE.md) for the audit and
[`MANUAL_TEST_MATRIX.md`](./MANUAL_TEST_MATRIX.md) for what still has to be run by hand.

**Scope, stated plainly.** The wire and state half of Phase 10 is built and tested. The screen half is
not: the operations below exist, are called, are capability-gated and are covered by tests over real
HTTP, but nothing in the app puts them on a display yet. The plan's scope list for the phase is
[`ANDROID_APP_PLAN.md`](./ANDROID_APP_PLAN.md) §6; the difference between it and this list is what is
left to do.

### Added

**The remaining nine operations**, all declared, wired and tested over real HTTP:

- Usage statistics (`experimental.session.stats`) with the range, project, time zone and tool-detail
  parameters the plan names, decoded into an activity heatmap, a streak, cost and token totals,
  per-model usage and tool reliability. The three tool modes are told apart rather than collapsing a
  switched-off column into a zero.
- The durable session log (`session.log`) as a cold, cancellable `Flow`, with `SseParser` extended to
  keep a frame's `id` and `event` so the `log.synced` end-of-replay is not lost.
- Synthetic notes (`session.synthetic`), wait-until-idle (`experimental.session.wait`), and
  programmatic permission and form creation, for plugin and workflow testing.
- A plugin RPC console surface (`rpc.call`) that refuses an id or method that would break the URL, and
  a viewer for `rpc.<id>.<event>`.
- Quick ask (`experimental.generate.text`) and pair another device (`POST /api/pair` — capability-gated,
  because the route is not in the published spec).

**TUI control events**, which had payload classes since P0 and no handler: a toast becomes a snackbar
at the server's own variant, an opt-in "follow desktop" mode mirrors the TUI's session and composer and
is off by default, and a command that would change server state is offered rather than performed. A
command this build does not know is shown verbatim rather than dropped.

**Coverage.** Every row in the plan's §7 and §8 matrices is implemented and tested: 138 of 138
operations declared, wired and tested, and 94 of 94 event types handled and tested. The audit of what
the matrices claimed and the code did not back — twelve operations with no production caller,
twenty-four more that nothing asserted, five event families with no handler, forty-six event types with
a binding and no payload any test had decoded — is in [`COVERAGE.md`](./COVERAGE.md), and CI fails on a
regression.

**Quality and release**

- ktlint through Spotless and detekt, applied to every module and failing `check`. They were in the
  version catalog and applied to nothing for three phases.
- Signing configured and the release tasks gated, with a message that says what is missing.
- CI checks for the coverage matrices, for the generated event corpus, and for externalized strings.
- Documented: a [security review](./SECURITY_REVIEW.md), a [privacy review](./PRIVACY_REVIEW.md), an
  [accessibility audit](./ACCESSIBILITY_AUDIT.md), a [coverage audit](./COVERAGE.md), a
  [manual test matrix](./MANUAL_TEST_MATRIX.md) and a [release runbook](./RELEASE.md).

**Not built:** the insights screens, the RPC console screen, the session-log viewer, the home-screen
widget, the Quick Settings tile, app shortcuts, the command palette and leader keys, session tabs, the
adaptive list-detail layouts, the LAN prober and the OpenCode theme import. The phase plan lists all
of them; this changelog does not claim them.

### Fixed

Found by driving the app against a live server on an emulator, which no test had done:

- **Typing lost characters.** The composer, the new-session fields, and the two search boxes copied the
  view model's text back into the field whenever it differed, but that state echoes each keystroke late
  (it is a `combine(…).stateIn(…)`), so fast typing, a paste or autofill was rewound to a stale echo.
  `TextSync` remembers what the field itself reported and ignores the echo of it; `SyncedTextField`
  applies that to a field.
- **Typing a directory registered a project for every prefix.** `NewSessionViewModel.setPathDraft`
  chose a location on every keystroke of anything that looked like an absolute path, which loads that
  directory's agents and models, and the server registers every directory it is asked about. Typing
  `/home/me/app` asked about `/`, `/h`, `/ho` and the rest. The location now changes only on "Use this
  directory" or the keyboard's Done key. The old docstring claimed this was already so.
- **Home and the session list were frozen snapshots.** `SessionListViewModel` read `.value` of each store
  inside a `map` on the active dataset, so it showed whatever had loaded by then: "No sessions yet" on a
  server still connecting, and a new or renamed session absent until the screen was recreated. It now
  follows the stores, and Home waits for the server to answer before claiming it is empty.
- **Seven screens crashed on open.** `ConfigViewModel`, `ConfigEditorViewModel`, `DefinitionViewModel`,
  `PermissionsViewModel`, `MaintenanceViewModel`, `InstructionsViewModel` and `InsightsViewModel` were not
  `@HiltViewModel`, so `hiltViewModel()` failed with `NoSuchMethodException: <init> []`. The app died on
  tapping Configuration.
- Markdown inside list items (`**bold**`, `` `code` ``, links) was drawn as typed; only paragraphs,
  headings and table cells were parsed.
- "Changed 1 files" is now "Changed 1 file".
- With a model search typed and no match, the list said "Connect a provider on the server first".

Also added while testing:

- An edit, write or patch card shows what changed, drawn from the tool's own arguments (`oldString` and
  `newString`, `content`, or the patch) rather than from `metadata`, which the spec leaves open. Added
  lines are green; the diff palette used the theme's violet, which read as a second red.
- The new-session sheet keeps "Start" pinned above the form and the keyboard, and the model list has a
  search field (the parameter existed and was hard-coded to empty).
- Home has a back arrow to the server list and a top-bar menu for the Manage destinations, which sat
  below every session.

Earlier fixes:

- A union member arriving as something other than a JSON object — `SessionStatus` as a bare string,
  for instance — threw out of the decoder instead of falling back to `Unknown`, which would have
  taken the event connection down over one malformed frame.
- `SessionStatsTools` discriminates on `mode`, not on `type` like every other union, so every
  statistics answer decoded as `Unknown` and the dashboard would have shown no tool data on a server
  that sent plenty.
- An unrecognised TUI command was marked safe to perform automatically. A newer TUI can send any
  name, and performing one whose effect is unknown is how an app breaks a server it does not
  understand.
- The markdown list markers, a shell command's exit code and the state a sign-in row is in while it
  polls were not in `strings.xml`, so they could not be translated.
- A shell command's exit code is formatted with `%1$s` rather than `%1$d`, because the schema models
  it as a number that may be `Infinity` or `NaN` and `%d` throws on values the server can really
  send.

### Security

- The model and vendor identifiers in the test fixtures are gone, replaced with the neutral
  placeholders the rest of the suite already used. The GitHub token prefixes the credential-redaction
  list has to name are deliberately left alone: those are a security control, not a fixture.

### Known limitations

- **The release candidate has not passed the manual test matrix.** There is no device, no emulator
  and no second device in the build environment. The matrix is written down and unrun; see
  [`MANUAL_TEST_MATRIX.md`](./MANUAL_TEST_MATRIX.md).
- **No signed artifact was produced.** No keystore exists and none was created. The build is ready
  for one and refuses to pretend otherwise; see [`RELEASE.md`](./RELEASE.md).
- An unrecognised TUI command is shown but not performed. A newer TUI's commands need an app
  action before they can be run from the phone.
- The terminal is a JavaScript grid with no semantics. The session transcript carries the same output
  and is accessible; the terminal is the power feature, not the accessible one.
- An unencrypted `http://` server is permitted and is required for the common case. Every non-loopback
  one is badged. See the security review §2.

[Unreleased]: https://github.com/Theblackcat98/opencode-android/compare/main...HEAD
