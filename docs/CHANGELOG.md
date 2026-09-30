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
- **Typing was still reordered in every other form.** A 32-character password typed with
  `adb shell input text` into Edit server arrived with its first two characters swapped and was
  rejected. A plain `MutableStateFlow` echoes late too, because `collectAsStateWithLifecycle` hands the
  write to the composition a frame later, so the server forms, the event inspector search, the admin
  fields, the shell and worktree fields, project settings, the connect sheet's key and login fields, the
  MCP form, the plugin, provider and web-search boxes, the history search and a request form's text and
  number answers all showed a stale echo. They use `SyncedTextField` now, and a number field can hold a
  decimal point, which the old field deleted. Tests drive each screen with a state that trails on
  purpose and fail on the old code; not yet seen on a device. The file browser's search and the review
  comment box still have the old behaviour.
- **Typing a directory registered a project for every prefix.** `NewSessionViewModel.setPathDraft`
  chose a location on every keystroke of anything that looked like an absolute path, which loads that
  directory's agents and models, and the server registers every directory it is asked about. Typing
  `/home/me/app` asked about `/`, `/h`, `/ho` and the rest. The location now changes only on "Use this
  directory" or the keyboard's Done key. The old docstring claimed this was already so.
- **Home and the session list were frozen snapshots.** `SessionListViewModel` read `.value` of each store
  inside a `map` on the active dataset, so it showed whatever had loaded by then: "No sessions yet" on a
  server still connecting, and a new or renamed session absent until the screen was recreated. It now
  follows the stores, and Home waits for the server to answer before claiming it is empty.
- **The composer showed what its stores held once.** `ComposerViewModel` read the model, agent, command,
  activity, pending-input, request and revert stores with `.value` inside a `combine` whose own inputs
  were typing and the session id, so a model list that loaded after the screen opened left "No model
  available" up over a server that had models, a resync after a reconnect never reached the screen, and
  Stop did not appear when the session started running. It now follows each store, and so do the
  new-session sheet's project list and directory browser, the home badge and the global inbox, and the
  delete confirmation's subagent count, which had the same read. View-model tests over a real data set
  fail on the old code; not yet seen on a device. `hasAnyModel` is still false between opening a session
  and its model list arriving, so the banner can flash for that request.
- **Seven screens crashed on open.** `ConfigViewModel`, `ConfigEditorViewModel`, `DefinitionViewModel`,
  `PermissionsViewModel`, `MaintenanceViewModel`, `InstructionsViewModel` and `InsightsViewModel` were not
  `@HiltViewModel`, so `hiltViewModel()` failed with `NoSuchMethodException: <init> []`. The app died on
  tapping Configuration.
- **A failed tool card said "Failed" and not why.** `ToolStatus.Failed` carried the server's
  `state.error`, but only the generic card drew its message, so a rejected `edit` showed the attempted
  diff and nothing else. Every kind now draws the message under "Why it failed", capped at six lines
  with the rest one tap away, and it is part of the card's accessibility description. Covered by unit
  tests and Roborazzi baselines (`tool-failed-edit`, `tool-failed-shell-dark`); not yet seen on a device.
- **Send was pushed off the screen while a turn ran.** The composer's seven 48 dp icons, Stop, Background and
  Send shared one `Row`, and Send, laid out last, got the width that was left, which on a phone was none (a tap
  at the far right hit Background); idle it overflowed too below 408 dp. Stop and Background now have a row of
  their own that exists only while a turn runs, and the icons wrap beside Send, so under about 408 dp the last
  icon takes a second line. `ComposerLayoutTest` (320, 360 and 427 dp, 2.0x font) asserts every control is on
  screen, 48 dp square and clear of the others and that Send answers click and long click; it fails on the
  old code. Thirteen existing composer baselines changed because they had drawn Send squeezed. Not yet seen
  on a device.
- **Opening a file larger than memory killed the app.** `fs.read` was a buffering Retrofit call, so a 572 MB
  file was copied whole into the heap on an OkHttp thread (`OutOfMemoryError`, seen as `IllegalStateException:
  Check failed` in `TaskRunner`), then copied again to be classified. `FileReader.read` now streams the body,
  keeps at most 2 MiB of text or binary (8 MiB of a picture, none of one whose `Content-Length` is over that),
  cancels the call so the rest is never downloaded, and returns `truncated` and the size the server reported;
  the viewer says "Showing the first … of …" or "too large to preview" and does not offer edit, share or
  download on a file it holds only the start of. The viewer's content was also never published (nothing set
  `FileBrowserState.content`), so a file that did read was never shown; `FileReader.open` does. A wire test over a streamed
  256 MiB body fails on the old code; not yet seen on a device.
- **A new terminal was reported as unknown and never opened.** "New terminal, Use the default shell" created the
  PTY on the server (`POST /api/pty` answered 200) and the app then said "The terminal could not be opened -
  unknown-terminal". `TerminalViewModel` looked the new id up in its copy of the terminal list, which learns of a
  terminal from the `pty.created` event, and the dispatcher applies that a frame after the response. It now opens
  the `PtyInfo` the create call returned, and `ExecutionCommands.createPty` records that answer in the store so
  the list is right before the event; the event then replaces the same row. The dialog no longer prints the codes
  `unknown-terminal` and `socket-unavailable`: the two reasons the app gives itself are `strings.xml` text and
  what the server said is still shown as it said it. `TerminalOpenTest` drives the view model over a real data set
  and a MockWebServer with both orderings and fails on the old code; not yet seen on a device.
- **A terminal that said Live drew nothing, and could not be brought back after a rotation.** Four separate faults,
  found on the emulator against the dev server. (1) Compose adds the factory's `WebView` to its holder with
  `WRAP_CONTENT`, and Chromium then gives the page a layout viewport of no height (`innerHeight` 506, `100vh` 0), so
  xterm's fit addon measured a container of zero, asked the PTY for `52x1` and clipped the one row it had;
  `terminalWebView` now asks for `MATCH_PARENT`. (2) The channel evaluates `window.__terminal.write({"type":"output",
  "data":…})` and the page's `write` took a string, so every chunk was ignored without an error; the page now reads the
  message the codec encodes, and no longer posts `cursor` and `state` back as messages the codec refuses. (3) A
  reconnect, or a second terminal, marked the page "not ready" although it had said `ready` and never would again, so
  the new socket's output was held for ever; and a rotation re-ran `open`, which threw the open terminal away over a
  live socket, while the WebView that replaced the page was empty and nothing replayed into it. `open` on a location
  that is already bound now changes nothing, a second `ready` is read as a new page and the socket is started again to
  fill it, every new socket empties the page first (`reset`, new `TerminalHostMessage.Reset`), and a terminal opened
  on an existing page is told the grid the page measured. (4) The rows of the terminal list were given `onOpen` and
  never used it, so a listed terminal could not be tapped open. Also: the page's messages were handled on the
  WebView's `JavaBridge` thread, racing the socket's collector for the same state, and are now handed to the main
  thread; the released WebView is destroyed; and output held while the screen is stopped is bounded. On the emulator
  (fdroid debug, 1280x2856 at 480 dpi) the prompt and `ls --color=always /` rendered with colour, `top` and `less`
  drew and `less` restored the screen when quit (`top` does not use the alternate screen on this host, so its last
  frame stays, as in any terminal), `stty size` read `33 52` in portrait and `8 113` after rotating to landscape,
  rotating and backgrounding for ten seconds kept the content and cursor with a ping's output arriving in between,
  and Reconnect and switching terminals replayed once, not twice. `TerminalPageTest`, `TerminalWebViewTest`,
  `TerminalScreenTest` and `TerminalPageContractTest` fail on the old code (14 of them, with the fixes reverted
  together, and the grid test alone). No JVM test can execute the page or draw a WebView; the contract test reads
  `index.html` as text. Not fixed: characters typed at `adb input text` speed arrive duplicated (`ls --color` became
  `ls ---ccolollor`) through xterm.js's composition diffing, exact when sent one at a time 200 ms apart, and one
  character of a slow run was dropped once and not reproduced; in landscape the list keeps 40% of the height and the
  terminal gets 8 rows; the first prompt is drawn at the server's 80x24 before the first resize arrives.
- **App commands with no argument could not be sent.** Typing `/compact`, `/undo`, `/redo`, `/diff`, `/new`,
  `/sessions`, `/models`, `/agents` or `/editor` and picking it from the palette left Send disabled, because
  `ComposerUiState.canSend` required text after the command name and only `/btw <question>` has any. It also
  enabled Send for a lone `!` or `/`, which then did nothing. `PromptAssembler.isSendable` is now the one
  answer (a send is sendable unless `assemble` says `Empty`), the button asks it, and `send()` builds its input
  the same way. `ComposerSendTest` drives the view model over a real data set and a MockWebServer (`/compact`
  posts to `api/session/{id}/compact`, `/btw` alone stays disabled, `/undo` raises the confirmation) and fails
  on the old code; not yet seen on a device. Picking an app command from the palette still only inserts its
  text, as it does for a server command: Send is what runs it.
- **Answering a permission or a form from the global inbox did nothing.** `PendingRequestsHost` asks for a
  `ComposerViewModel` of its own and never opens a session in it, and `replyPermission`, `submitForm` and
  `cancelForm` returned without a word unless one was open, although every request names the session that
  asked. They no longer need one. `ComposerRequestAnswerTest` (MockWebServer) fails on the old code; not yet
  seen on a device. A failed answer from the inbox still shows nothing, because that screen does not display
  the composer's error.
- **"Undo to here" confirmed and did nothing, and files attached from the file browser went nowhere.**
  `OpenCodeApp` took `composer: ComposerViewModel = hiltViewModel()`, which is evaluated in the activity's
  `ViewModelStoreOwner`, not in a route, so the confirmation's `stageUndo` ran on a composer that had never
  been given a session (it returns without a request or an error), the file browser's "Attach file" and
  "Attach lines" went to the same one, and the review's comments were pushed to a third, in the review's own
  entry. The session's back-stack entry now supplies the one composer: `SessionHost` calls `stageUndo` on its
  own, and the review takes the session's instance from the back stack (a review whose session is not there
  closes). `tools/audit-viewmodels.mjs` now fails on a `hiltViewModel()` default on the `NavHost` function and
  on a second `ComposerViewModel` beside `SessionHost`'s; it reports four findings on the previous
  `Routes.kt` and `ReviewHost.kt`. `:app` cannot host a navigation test (no Hilt or Robolectric wiring), so
  that is a source check and nothing here has run the wiring; not yet seen on a device. One thing this makes
  reachable and no test covers: opening the review replaces the composer's review comments with the review's
  (`setReviewComments`), so comments an undo restored are dropped if the review is opened before the next send.
- **A staged undo said nothing and could not be taken back.** After "Undo to here" the server stages the revert, the
  working copy is restored and the prompt goes back into the box, and nothing on screen said an undo was staged:
  `StagedRevertBanner` had been written and screenshotted in the review module and never composed into a screen.
  `ComposerBar` now draws it above the field while `isStaged`, with the count and the file names, and its Redo (a 48 dp
  target, a live region for a screen reader) asks first: `askRedo` opens a confirmation that says the files return to
  how they were before the undo and the box keeps its text, and only its Redo calls `redo()`. `/redo` asks the same
  question instead of redoing at once, which its KDoc had promised. The banner also follows the session's own
  `SessionInfo.revert` and not the server-wide mirror in `revertCommands`, which every session's
  `session.revert.staged` filled: an undo in one session showed its banner, and made a send commit first, in every
  session opened after it. `ComposerLayoutTest` (320, 360 and 427 dp, 2.0x font) fails without the banner;
  `ComposerRevertTest` (MockWebServer) fails on the mirror and on an immediate `/redo`. Four baselines are new
  (`composer-staged-360`, `composer-staged-320-font2`, `composer-staged-none-360`, `composer-redo-confirm-360-font2`)
  and `review-staged-revert` changed with the banner's layout. Not yet seen on a device.
- **A permission request no tool raised looked exactly like a tool's.** `POST /api/session/{id}/permission` lets a
  plugin or another client raise a request; it has no `source`, it blocks the agent like any other, and the dock and
  the global inbox drew it as "Allow external_directory? It would touch /etc/hosts" with nothing saying who was asking
  and its `metadata.title` dropped. `PermissionCard`, which both use, now draws "Raised by a plugin or client, not by
  a tool call" under the title when `source` is null (`PermissionRequest.raisedByToolCall`), and the request's
  `metadata.title` (`PermissionRequest.title`, a string that is not blank) under that, for a tool's request as well.
  `PermissionRequestOriginTest` (payloads as the server sends them) and `PermissionOriginTest` (dock, inbox, 320 dp at
  2.0x font) fail without the label and when it is drawn for every request; four baselines are new
  (`permission-card-plugin`, `permission-card-plugin-large-font`, `request-dock-plugin`, `requests-inbox-plugin`) and the
  existing ones are unchanged. Not done: the notification for such a request (`AttentionNotificationBuilder`) still
  reads "Allow external_directory? … is waiting for you" with no mention of who asked. Not yet seen on a device.
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
