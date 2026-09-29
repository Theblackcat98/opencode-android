# Changelog

All notable changes. The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and
this project uses [semantic versioning](https://semver.org/spec/v2.0.0.html) for the API surface of
the app, not of the server it talks to.

## [Unreleased] — 0.1.0

The first release candidate, and it is **not shippable**. The plan's exit criterion is coverage and
that is met: see [`COVERAGE.md`](./COVERAGE.md) for the audit and
[`MANUAL_TEST_MATRIX.md`](./MANUAL_TEST_MATRIX.md) for what still has to be run by hand.

**Scope, stated plainly.** The wire and state half of Phase 10 is built and tested. One screen is built,
the usage dashboard, and **it is not reachable**: no navigation route and no Manage entry opens it. Every
other Phase 10 capability below has no screen, so nothing in the app puts it on a display. The plan's scope
for the phase is [`ANDROID_APP_PLAN.md`](./ANDROID_APP_PLAN.md) §6, and everything not done is listed in its
[Phase 11](./ANDROID_APP_PLAN.md#phase-11-screens-adaptive-ui-and-the-release-l).

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

**TUI control events**, which had payload classes since P0 and no handler. `TuiControl` maps a toast to
a snackbar model at the server's own variant, holds an opt-in "follow desktop" mode that is off by default
and gates prompt and session mirroring, and marks a command that would change server state as offered
rather than performed; a command this build does not know is kept verbatim rather than dropped. These are
asserted in tests of `TuiControl`. **Nothing in `feature` or `app` collects its flows yet**, so none of it
is visible on a phone.

**The usage dashboard screen** (`feature/insights`): the server's own day buckets drawn as a heatmap, with
the grid described once for a screen reader; a period with no calls says so rather than showing "0%
reliable"; `tools=none` says the column is off; an unknown mode says so instead of drawing zeros. It has a
screen-content test and no screenshot baseline. **It is not linked into the navigation graph or the Manage
row**, so a user cannot open it.

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

**Not built.** Everything not done is listed once, in Phase 11 of the plan. In outline:

- *Data layer only, no screen:* the RPC console and `rpc.*` viewer, the session-log viewer, synthetic
  notes, wait-until-idle, programmatic permission and form creation, quick ask, pair another device, and
  the surfaces for the TUI control flows.
- *Nothing exists:* the LAN prober, the OpenCode theme import, the command palette and leader keys,
  session tabs, adaptive list-detail layouts, the home-screen widget, the Quick Settings tile, app
  shortcuts, baseline profiles, the in-app changelog and the compatibility matrix.

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

- **Several matrix rows cannot pass yet**, because the feature they test does not exist (see the note in [`MANUAL_TEST_MATRIX.md`](./MANUAL_TEST_MATRIX.md)).
- **The release candidate has not passed the manual test matrix.** There is no device, no emulator
  and no second device in the build environment. The matrix is written down and unrun; see
  [`MANUAL_TEST_MATRIX.md`](./MANUAL_TEST_MATRIX.md).
- **No signed artifact was produced.** No keystore exists and none was created. The build is ready
  for one and refuses to pretend otherwise; see [`RELEASE.md`](./RELEASE.md).
- An unrecognised TUI command is kept but not performed, and until Phase 11 no screen shows it. A newer
  TUI's commands need an app action before they can be run from the phone.
- The terminal is a JavaScript grid with no semantics. The session transcript carries the same output
  and is accessible; the terminal is the power feature, not the accessible one.
- An unencrypted `http://` server is permitted and is required for the common case. Every non-loopback
  one is badged. See the security review §2.

[Unreleased]: https://github.com/Theblackcat98/opencode-android/compare/main...HEAD
