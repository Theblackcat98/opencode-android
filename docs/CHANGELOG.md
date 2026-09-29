# Changelog

All notable changes. The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and
this project uses [semantic versioning](https://semver.org/spec/v2.0.0.html) for the API surface of
the app, not of the server it talks to.

## [Unreleased] — 0.1.0

The first release candidate. Every feature below is implemented and covered by the plan's §7 and §8
matrices; see [`COVERAGE.md`](./COVERAGE.md) for the audit and [`MANUAL_TEST_MATRIX.md`](./MANUAL_TEST_MATRIX.md)
for what still has to be run by hand.

### Added

**Insights**
- Usage dashboard over `experimental.session.stats`: date range, project filter, time zone and tool
  detail, with an activity heatmap, streak, cost and token totals, per-model usage and tool
  reliability. The three tool modes are told apart rather than collapsing a switched-off column into
  a zero.
- A plugin RPC console: `rpc.call` with a JSON input and the answer rendered verbatim, and a viewer
  for `rpc.<id>.<event>`.
- TUI control events are acted on: `tui.toast.show` becomes a snackbar, `tui.session.select` and
  `tui.prompt.append` drive an opt-in "follow desktop" mode, and `tui.command.execute` maps to an app
  action — offered rather than performed when it would change server state.
- Advanced session tools: synthetic notes, wait-until-idle, and programmatic permission and form
  creation, for plugin and workflow testing.
- The durable session log (`session.log`) as an event-history viewer, and a gap-free resync on it.
- Quick ask: stateless generation (`experimental.generate.text`) from a home-screen widget.
- Pair another device: `POST /api/pair` shows a QR, gated by capability detection because the route
  is unpublished.
- An opt-in LAN prober that looks for the OpenCode signature on the local `/24`.

**Adaptive and theming**
- List-detail layouts for tablets, foldables and ChromeOS.
- Keyboard shortcuts mirroring the TUI keybinds, with a Ctrl+P command palette and leader-key
  combinations.
- Session tabs, a widget showing running sessions and pending approvals, a Quick Settings tile, and
  app shortcuts.
- Material You theming plus OpenCode theme import, reading `themes/*.json` through the file API and
  mapping V2 tokens onto a Material colour scheme.

**Quality and release**
- ktlint through Spotless and detekt, applied to every module and failing `check`. They were in the
  version catalog and applied to nothing for three phases.
- Signing is configured and the release tasks refuse to run without the material, with a message
  that says what is missing.
- A CI check for the plan's coverage matrices, for the generated event corpus, and for externalized
  strings.
- Documented: a [security review](./SECURITY_REVIEW.md), a [privacy review](./PRIVACY_REVIEW.md), an
  [accessibility audit](./ACCESSIBILITY_AUDIT.md), a [coverage audit](./COVERAGE.md), a
  [manual test matrix](./MANUAL_TEST_MATRIX.md) and a [release runbook](./RELEASE.md).

### Fixed

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
