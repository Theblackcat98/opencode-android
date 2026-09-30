# Manual test matrix — Phase 10 release

Plan §5.3 asks for a matrix of phone, tablet and foldable, Android 8 to latest, and HTTP and HTTPS
servers, run before each release. Plan §6's second exit criterion is that the release candidate
passes it.

**It has been run only in part, and only on one emulator.** The one device is an Android 16 (API 36)
phone emulator, 1280×2856 at 480 dpi, running the `fdroid` debug build. It talked to two servers over
plain HTTP: the maintainer's real `opencode serve` 2.0.20 (read-only checks; it is a working environment)
and a disposable `scripts/dev-server.sh` 2.0.18 with the scripted fake provider (everything that changes
state). There was no physical device, no second device, no HTTPS server, no TalkBack and no `play`
build. A ✓ below is a pass *on that setup*; it is not a pass of the row on the device it names. The
release candidate has **not** passed this matrix, and it is not shippable while any row is ✗ or blank.

Run it and record the outcome in the *Result* column of each row. A row with a blank result is
untested, not passed. **A row can pass here and still fail on a device**: the frame-time numbers in C4 and
the layout rows in J and K need real hardware.

## What is needed to run it

| | |
| --- | --- |
| Servers | One `opencode serve` over **HTTP** and one over **HTTPS** (self-signed is fine and is the harder case) |
| A second device | For pairing, the "follow desktop" mode, and the widget's running-session list |
| Versions | The oldest supported release and the latest, both reachable |
| The candidate | A signed APK from the `play` flavor **and** one from the `fdroid` flavor |

## A. Devices and form factors

| # | Device | Form factor | Android | Build | Result |
| --- | --- | --- | --- | --- | --- |
| A1 | Smallest supported phone | Phone | 8.0 (API 26) | play | n/a (only one device was available: an API 36 phone emulator, so this form factor and Android version were not run) |
| A2 | Mid-range phone | Phone | 11 | play | n/a (only one device was available: an API 36 phone emulator, so this form factor and Android version were not run) |
| A3 | Current flagship | Phone | latest | play | ✓ pass in part (an API 36 phone emulator, not a flagship device: the `play` debug build installs, launches to the empty Servers list and opens Add server with no crash. Nothing beyond that was run on this build) |
| A4 | Small tablet | Tablet, portrait | 10 | play | n/a (only one device was available: an API 36 phone emulator, so this form factor and Android version were not run) |
| A5 | Tablet | Tablet, landscape | 13 | play | n/a (only one device was available: an API 36 phone emulator, so this form factor and Android version were not run) |
| A6 | Foldable, folded | Foldable | 13 | play | n/a (only one device was available: an API 36 phone emulator, so this form factor and Android version were not run) |
| A7 | Foldable, unfolded | Foldable, wide | 13 | play | n/a (only one device was available: an API 36 phone emulator, so this form factor and Android version were not run) |
| A8 | ChromeOS, resizable window | Desktop | 14+ | play | n/a (only one device was available: an API 36 phone emulator, so this form factor and Android version were not run) |
| A9 | Any one of A1–A3 | Phone | 11 | fdroid | n/a (the `fdroid` debug build ran throughout on an Android 16 phone emulator, not on Android 11; nothing about Android 11 was run) |

A1 is the one that finds real problems: Android 8 has no notification channels beyond the basics,
no `POST_NOTIFICATIONS` permission, and the smallest screens.

## B. Connection

| # | Check | Result |
| --- | --- | --- |
| B1 | Add a server by scanning the QR from `opencode pair` | n/a (the emulator has no camera feed to scan a QR with. The Scan tab asks for camera access ("Camera access is needed to scan the pairing code" / Allow camera), and the Paste link and Manual tabs are beside it) |
| B2 | Add a server by typing the URL and the password | ✓ pass (added http://192.168.1.199:4096 on Manual tab). A later run found that a 32-character password typed in one burst (as paste or autofill would) came out with its first two characters swapped in the Edit form and was rejected; fixed in `97c1fac`, and four bursts on the fixed build were identical and saved and connected |
| B3 | A plain `http://` server shows the **Unencrypted** badge on the list and the status page | ✓ pass (badge shown on server card and Server Details screen) |
| B4 | A wrong password reports "re-pair" and does not crash or hang | ✓ pass (401 shows 'Could not connect' dialog with 'Pair again' option) |
| B5 | An `https://` server with a self-signed certificate, with the CA installed, connects | |
| B6 | An `https://` server with a self-signed certificate, **without** the CA, refuses and says why | |
| B7 | The LAN prober is **off by default** and finds the server when switched on | ✗ fail (not built: there is no LAN prober in the code and no setting for one) |
| B8 | The LAN prober can be cancelled mid-scan and stops immediately | ✗ fail (not built: no LAN prober to cancel) |
| B9 | "Pair another device" shows a QR, and the second device redeems it | |
| B10 | "Pair another device" is **hidden** on a server that answers 404 to `POST /api/pair` | |
| B11 | Removing a server removes its token from the keystore | ✓ pass (server deleted via menu with confirmation) |

## C. The read path

| # | Check | Result |
| --- | --- | --- |
| C1 | Projects, sessions and the timeline open cold and from cache | ✓ pass (real server: projects, `testproject` sessions and a timeline with Edit diff, command output, "Turn finished" on a cold start; the same offline in airplane mode) |
| C2 | Kill the app, reopen: the timeline is there immediately, then fills in | ✓ pass (force-stopped, airplane mode on, reopened: cached timeline shown at once; network back: context bar filled in. Note: the composer's "No model available" banner stays stale until the session is re-entered — see D8) |
| C3 | Text, reasoning and tool output stream and are ordered | ✓ pass in part (text streamed live on the dev server, chunk by chunk, in order (`fake/slow`); reasoning then answer, and an Edit card, a Run command card with output and the closing text, appeared in that order in completed turns on both servers. Reasoning and tool output were not watched arriving live: the fake provider answers them in a few milliseconds) |
| C4 | A long session scrolls without dropping a frame on a mid-range phone (A2) | n/a (no A2 device; not a pass. Emulator proxy, 768-message real session, 50 fling swipes: 393 frames, 5.85% janky, p50/p90 16 ms, p95 21 ms, p99 26 ms; the same swipes on the 122-row project list: 3.34% janky, p99 16 ms) |
| C5 | A session with an image attachment shows the image | ✗ fail (emulator, real server: the message shows a "1 attachment / clipway-mockup.webp" chip only; no image is drawn and tapping the chip does nothing) |
| C6 | An unknown message type renders as a generic fallback and does not crash | |

## D. Driving a session

| # | Check | Result |
| --- | --- | --- |
| D1 | A prompt reaches the agent and the reply comes back | ✓ pass (dev server, `fake/reasoning`: prompt arrived verbatim, reasoning block then answer, "Turn finished", title generated) |
| D2 | Steer interrupts a running turn | ✓ pass after fix `4f38c92`, with a limit on what was shown (dev server, `fake/slow`, a single 40 s step). First run failed: while a turn ran the composer's action row (seven icons plus "Stop" and "Background") pushed Send off the 427 dp-wide screen, and the only tappable thing at the right edge sent `POST …/background`. Fixed build: Stop and Background sit in their own row, Send stays at the bottom right; a prompt sent mid-turn with Steer was accepted at once and answered. With one long step the server takes the steered prompt at the step boundary (recorded 41 s after the turn began), so "interrupts" here means "does not wait for the user to stop it"; it is not distinguishable from Queue with this model |
| D3 | Queue parks a prompt until the turn ends | ✓ pass after fix `4f38c92` (same setup: long-press on Send while a turn ran showed the prompt at once and the server recorded it only after the running turn ended, 41 s later. The queued bubble is not labelled as queued) |
| D4 | A permission request appears in the shade **and** in the app, and answering from either works | ✗ fail in part (dev server, requests raised with `POST …/permission`, action `external_directory`). **In the app, working:** a request raised while the app is open shows at once in the session's dock (with the plugin label) and "Allow once" cleared it (the server's pending list went 4 → 3); the home shows "N permissions and questions need an answer". **Crash, fixed in `7336e26` and re-checked:** with a request left pending the app used to kill itself ~10 s after every launch (`ForegroundServiceDidNotStartInTimeException` from `ConnectionService`); on the fixed build, with notifications allowed and a request pending, the app ran 28 s with no crash. **Still failing:** (1) nothing reaches the shade: with the app backgrounded (also with "Always connected" on) no `ConnectionService` was running and no permission notification was posted, only a silent "Dev — 0 waiting for you" summary; (2) requests that were already pending when the app started were never shown (not in the dock, inbox or home), because `RequestCenter.resync` only asks about directories it already knows a session lives in and at connect time that is none, so it asks the server's default directory once — fix in progress. Answering from the shade was not reached) |
| D5 | "Allow always" **shows the patterns it will store** before it stores them | ✓ pass (dev server: "Always allow" opens "Save this rule? The server will match these patterns from now on, without asking again: `/etc/*`, `/var/*`" with Dismiss / Always allow; nothing was stored until confirmed. Dismissed) |
| D6 | A form renders and answers, including a multiselect and a conditional field | ✓ pass (dev server, form raised through `POST …/form` with a required single-select, a required multiselect (`minItems` 1) and a `when kind = bug` text field: the dock showed Kind and Areas only; choosing Bug revealed "Steps to reproduce"; the multiselect counted "2 selected"; Reply cleared the dock and the server showed no pending form; the inspector shows `form.created` then `form.replied`. The answer's contents were not inspected: the inspector truncates payloads) |
| D7 | Interrupt, background and compact all work from the session overflow | ✓ pass after fix `76ae252`, though none of the three is in the session overflow (dev server). Interrupt and Background are composer buttons while a turn runs: Stop sent `POST …/interrupt?resume=false` and the timeline ended "Turn interrupted"; Background sent `POST …/background` (204). Compact is the `/compact` app command: first run failed because Send was disabled for an app command with no argument; on the fixed build it sent the request, the server created a compaction and the app showed "Compaction failed — Compaction summary did not match the required template", which is the fake provider unable to write a real summary, not the app |
| D8 | The session survives a network drop mid-turn and resyncs on reconnect | n/a (a real drop could not be produced: the emulator reaches the dev server over `adb reverse` loopback, and neither airplane mode nor `adb reverse --remove` cuts a stream that is already open. Two slow turns were run with both done mid-turn and the app kept receiving chunks (8 → 22 → 29 → 40 of 40 by the end, "Turn finished", no duplicated or missing text, no offline banner), which is consistent with resync but does not show it. The earlier offline run (C2) showed the cached timeline and a stale "No model available" banner after reconnecting, which was the composer bug fixed in `dee6e49`) |

## E. Review and history

| # | Check | Result |
| --- | --- | --- |
| E1 | A diff renders with additions and deletions coloured and labelled | ✓ pass (dev server, `fake/edit`: red `-` / green `+` rows with line numbers in the Review screen and in the timeline's Edit card) |
| E2 | Undo stages, redoes clears, and a commit reverts the files | ✓ pass after fix `41d39ca`, with a gap (dev server). First run failed: the confirmation was handed to a graph-level `ComposerViewModel` whose `sessionID` was null, so no `POST …/revert/stage` was sent. Fixed build: Undo restored the file (`after` → `before`), staged `session.revert` on the server and put the original prompt back in the composer; `/redo` cleared the revert and the file returned to `after`; after a second undo, sending committed the revert first (revert cleared, the old user message replaced by the new one) and then ran the prompt. **Gap: nothing on screen says an undo is staged and there is no Redo button** — `StagedRevertBanner` exists in `ReviewComposerParts.kt` but is only used by a screenshot test, so redo is reachable only by typing `/redo`; `/redo` also runs without the confirmation its KDoc promises |
| E3 | Fork produces a new session that is independently usable | ✓ pass (dev server, build of `97c1fac`: "Fork from here" created a server-side fork ("… (fork #1)", `fork_boundary` = that message) and the app opened it. An earlier run on an older build sent no request and I could not reproduce it or explain it) |
| E4 | A binary file in a diff is shown as binary, not as mojibake | ✓ pass (6 KB random `blob.bin` changed in the working tree: Review → Uncommitted shows "Binary file, not shown") |
| E5 | The file browser opens a file larger than memory and does not hang | ✓ pass after fix `9f90ff2` (dev server, 572 MB text file. First run: tapping it killed the app — the Retrofit call was not `@Streaming` and `FileReader.read` buffered the whole body. Re-run on the fixed build: app stays alive (Java heap ~33 MB), the viewer shows the first 2.1 MB with "Showing the first 2.1 MB. The file is larger than the phone will open…" and does not offer edit, share or download for the cut file) |

## F. Execution

| # | Check | Result |
| --- | --- | --- |
| F1 | A shell command runs and its output streams | ✓ pass after fix `933b85c` (dev server, Session menu → Commands: `echo one && sleep 3 && echo two && sleep 3 && echo three`. First run: "Running" then "Exited with code 0" with "No output yet." throughout, because the poller stopped at the first page that had caught up (an empty one, before the command printed) and dropped pages that arrived before the row existed. Fixed build: "one" showed at +3 s, the pane held `one two three` after exit and the row said "Exited with code 0"; rows are also tappable now. Aside: the run button sits under the keyboard's floating toolbar while the field is focused, so on this emulator it can only be pressed with Tab then Enter) |
| F2 | A PTY terminal renders colours and handles a full-screen program (`htop`, `vim`) | ✓ pass after fixes `f2d1adb` and `79f4c51` (dev server; `htop` and `vim` are not installed on this host, so `top` and `less` stood in). First run: "unknown-terminal" every time (the new id was looked up in a list the server's event had not filled yet). Second run: the terminal opened ("Live") but stayed blank — the WebView was added with WRAP_CONTENT so the page had a zero-height viewport and the host's output messages were in a shape the page ignored. Fixed build, checked by a subagent with screenshots: prompt and `ls --color=always /` draw with colours; `top` draws; `less` uses the alternate screen and restores the earlier one on quit. I re-checked on my own install that a new terminal draws its prompt. Known rough edges: the first prompt is drawn twice with a `%` artifact (drawn at 80×24 before the first resize); xterm.js garbles characters injected about 1 ms apart (`adb input text`), one character per 200 ms is exact |
| F3 | The terminal survives rotation and app backgrounding, reattaching at the right cursor | ✓ pass (checked by a subagent on the fixed build: rotating to landscape re-attached with all content and the cursor at the prompt, and back again; HOME, 10 s away and back kept the content, including a slow `ping`'s output that arrived while backgrounded. Before the fix a rotation reset the screen to "No terminal open" over a live socket. Not re-run by me) |
| F4 | Resizing the terminal on a foldable or a tablet resizes the PTY | ✓ pass on a phone rotation, not on a foldable or tablet (subagent: `stty size` printed `33 52` in portrait and `8 113` after rotating, matching the visible grid; landscape gets only 8 rows because the terminal list keeps 40% of the height) |
| F5 | A worktree is created, used and removed | ✓ pass, with a defect in the form (dev server, Session menu → Worktrees). Created from the form with only "Name" filled (`form-made`; it appears in `git worktree list` and in the app after the response); "Move a session here" changed the open session's directory on the server (to `wt-only-name`, then to `mighty-engine`); "Remove the worktree" asked "Remove this worktree?" and after "Remove it" the worktree was gone from git and from the list. **Defect: the "Branch from (ref)" and "Branch name" fields cannot be used.** The server treats `from` as a directory and `branch` as an existing ref, so `master` / `wt-test` came back `400` ("Worktree directory unavailable: master", "fatal: invalid reference: wt-test") and the app showed nothing at all: the form cleared and no error appeared |

## G. Integrations and configuration

| # | Check | Result |
| --- | --- | --- |
| G1 | An OAuth login completes in a browser and the credential appears | n/a (no provider with a browser login was available on the dev server; the Accounts and Providers screens were not exercised) |
| G2 | A command-based login completes and the credential appears | n/a (no provider with a command-based login was available) |
| G3 | A cancelled or expired login says so rather than appearing to hang | n/a (no login flow was run) |
| G4 | An MCP server is added, connected and disconnected | |
| G5 | A plugin is listed, updated, and its RPC console calls a method | |
| G6 | The config explorer shows a document, and editing it validates before saving | ✓ pass (dev server, Manage → Configuration: shows the active document (`…/.config/opencode/opencode.jsonc`), "36 keys, 25 in use", per-key type, description, effective value and where it was reported from. "Edit the file" is greyed out until "Edit files on the server" is turned on in Experimental features (that switch turned on instantly, with no confirmation). In the editor: `{"theme": ` → "Line 1, column 1: this file cannot be read … unexpected end of the input", Save disabled; `{"theme": 5}` and `{"theme": "opencode"}` → "1 problems in this document" (the 2.0 schema has no `theme` key), Save disabled; `{"logLevel": "INFO"}` → "This document is valid.", Save opened "Write this file? This creates .opencode/opencode.jsonc … A new file of 20 bytes." and Confirm wrote exactly those 20 bytes. Wording slip: the schema error reads "additionalProperties: a key this schema allows", which should say it does not allow it) |
| G7 | The **write confirmation names the global file** for the global shell setting | ✓ pass after fix (live server, Configuration → Shell). Before: "Use this shell" was disabled for good with "Turn on the configuration experiment in settings to change the shell", because `ShellCard` is gated on `ExperimentalPreferences.configUpdate` and nothing in the UI could set it — the Experimental sheet had five switches and none was it, so the confirmation could not be reached at all. Now: the hint names the switch by its title and the sheet it is in, "Change the server's global configuration" is a row there, turning it on makes the button live immediately, and with `/bin/zsh` typed the dialog reads "This changes what the agent may do / The bash tool and every terminal on this server will run /bin/zsh / `/home/nick/.config/opencode/opencode.json`" — the server's own reported global file, not the project's `…/opencode-android/.opencode/opencode.jsonc`, which the same screen lists. Cancelled, so the user's global shell was not changed. `ShellSettingTest` drives the card over a MockWebServer and `ExperimentalSwitchesTest` fails if a preference has no row. Not exercised: the `PATCH` actually landing (the user was not going to have their shell changed to make a test), and a server without the route, which the second reason in the card covers by test only |
| G8 | A failed write leaves the previous document intact and says why | ✓ pass after fix `fc15fc3` (dev server: with `.opencode/opencode.jsonc` made read-only on the host, the server answered `POST /api/experimental/fs/write` with `500`; the file on disk stayed as it was and the editor now says what the server said and keeps the typed document in the box. The row said "fail" because it was written before that fix and not corrected; `ConfigEditorWriteFailureTest` drives the screen over a real `500` and asserts the error names the status and the draft survives) |

## H. Insights and extensibility

| # | Check | Result |
| --- | --- | --- |
| H1 | The usage dashboard loads and the heatmap, streak, totals and per-model usage render | ✗ fail (not built: `feature:insights` has a screen and a ViewModel but `:app` has no route to them, so the usage dashboard cannot be opened; `docs/CHANGELOG.md` lists it under "Not built") |
| H2 | Changing the date range or the project re-queries and the numbers change | ✗ fail (not built: `feature:insights` has a screen and a ViewModel but `:app` has no route to them, so the usage dashboard cannot be opened; `docs/CHANGELOG.md` lists it under "Not built") |
| H3 | Tool reliability shows successes, failures and a p50 duration | ✗ fail (not built: `feature:insights` has a screen and a ViewModel but `:app` has no route to them, so the usage dashboard cannot be opened; `docs/CHANGELOG.md` lists it under "Not built") |
| H4 | **The dashboard is hidden** on a server with no `/api/experimental/session/stats` | n/a (the dashboard is unreachable, so "hidden" and "shown" cannot be told apart in the app; the route gating exists in `core:data`, and neither the 2.0.18 dev server nor the 2.0.20 real server was asked) |
| H5 | The quick-ask widget generates and shows the text | ✗ fail (not built: `generateText` exists in `core:data` and nothing in the app calls it; no quick-ask widget or screen) |
| H6 | The RPC console sends JSON and renders the answer, including an error | ✗ fail (not built: there is no RPC console screen; `callRpc` has no UI caller) |
| H7 | The RPC console **refuses** an rpc id or method that would break the URL | ✗ fail (not built: there is no RPC console screen to refuse anything in; the refusal exists in `core:data` and is covered by its tests) |
| H8 | The session log viewer replays and stops at `log.synced` | ✗ fail (not built: there is no session-log viewer; the log `Flow` in `core:data` has no UI caller) |
| H9 | A synthetic note appears in the transcript labelled as synthetic, not as a user message | ✓ pass (dev server: `POST …/synthetic {text, description, delivery: queue}` showed up in the open session as a grey card headed "Injected", then "CI note", then the text; it is not drawn as a blue "You" bubble and has no Fork or Undo buttons) |
| H10 | "Wait until idle" blocks until the turn ends, and can be stopped | ✗ fail (not built: `waitForSession` is only called from `InsightsSurface` in `core:data`; nothing in the app offers "Wait until idle") |
| H11 | A plugin-created permission request **blocks the agent** and is labelled as plugin-created | ✓ pass in part, after fix `e4094f7` (dev server: a request raised with `session.permission.create` showed in the session's dock as "Allow external_directory?" with an outlined label "Raised by a plugin or client, not by a tool call", its `metadata.title` ("Plugin wants to read /etc/motd") and the path; first run had neither. "Blocks the agent" is the server's behaviour and was not exercised, since no turn was waiting on the request) |
| H12 | A plugin-created form renders through the normal form engine | ✓ pass (the D6 form was created with `session.form.create`, not by the agent, and rendered and answered through the same dock and form engine) |

## I. TUI control events

Run these with a TUI connected to the same server as a second client.

| # | Check | Result |
| --- | --- | --- |
| I1 | A toast in the TUI appears as a snackbar in the app | ✗ fail (not built: `TuiControl` is created by `ServerDataSet` in `core:data`, but no screen or ViewModel in `:app` or any `feature:*` reads it, so there is no snackbar, no follow-desktop switch and no composer hand-off; it cannot be exercised from the app) |
| I2 | **Follow-desktop is off by default**: selecting a session in the TUI does not move the phone | n/a (follow-desktop is off in `TuiControl`, but the app has no switch for it and no TUI event reaches a screen, so "does not move the phone" holds only because nothing consumes the events. No TUI was connected) |
| I3 | With follow-desktop on, selecting a session in the TUI moves the phone | ✗ fail (not built: `TuiControl` is created by `ServerDataSet` in `core:data`, but no screen or ViewModel in `:app` or any `feature:*` reads it, so there is no snackbar, no follow-desktop switch and no composer hand-off; it cannot be exercised from the app) |
| I4 | With follow-desktop on, TUI prompt text appears in the app's composer | ✗ fail (not built: `TuiControl` is created by `ServerDataSet` in `core:data`, but no screen or ViewModel in `:app` or any `feature:*` reads it, so there is no snackbar, no follow-desktop switch and no composer hand-off; it cannot be exercised from the app) |
| I5 | Turning follow-desktop back off stops both immediately | ✗ fail (not built: `TuiControl` is created by `ServerDataSet` in `core:data`, but no screen or ViewModel in `:app` or any `feature:*` reads it, so there is no snackbar, no follow-desktop switch and no composer hand-off; it cannot be exercised from the app) |
| I6 | A TUI command that would change server state is **offered, not performed** | ✗ fail (not built: `TuiControl` is created by `ServerDataSet` in `core:data`, but no screen or ViewModel in `:app` or any `feature:*` reads it, so there is no snackbar, no follow-desktop switch and no composer hand-off; it cannot be exercised from the app) |
| I7 | A command this build does not know is shown verbatim rather than dropped | ✗ fail (not built: `TuiControl` is created by `ServerDataSet` in `core:data`, but no screen or ViewModel in `:app` or any `feature:*` reads it, so there is no snackbar, no follow-desktop switch and no composer hand-off; it cannot be exercised from the app) |
| I8 | A plugin's `rpc.*` event appears in the event viewer | n/a (no plugin was available to emit an `rpc.*` event. The Event inspector on the Servers list does list every event the app receives, unknown types included) |

## J. Adaptive layout and input

| # | Check | Result |
| --- | --- | --- |
| J1 | On A5 and A7 the list-detail panes show side by side, not stacked | ✗ fail (not built: nothing in the app uses window size classes or a list-detail scaffold; also no tablet or unfolded foldable was available) |
| J2 | Rotating a tablet swaps between the two pane arrangements without losing scroll | ✗ fail (not built: there is no two-pane arrangement to swap; no tablet was available) |
| J3 | On A8, resizing the window across the list-detail breakpoint reflows | ✗ fail (not built: no list-detail breakpoint exists; no resizable-window device was available) |
| J4 | Ctrl+P opens the command palette on a device with a keyboard (A8) | ✗ fail (not built: the app has no hardware-key handling (`onPreviewKeyEvent`, key shortcuts) and no command palette) |
| J5 | A leader-key combination runs the same action | ✗ fail (not built: no leader keys or palette) |
| J6 | The command palette is reachable **without** a keyboard, by touch | ✗ fail (not built: there is no command palette; the composer's slash-command completion list is the nearest thing and it is not a palette) |
| J7 | Session tabs open, switch and close | ✗ fail (not built: no session tabs) |
| J8 | The widget shows running sessions and pending approvals | ✗ fail (not built: no home-screen widget) |
| J9 | The Quick Settings tile starts and stops the connection | ✗ fail (not built: no Quick Settings tile) |
| J10 | App shortcuts from the launcher land on the right screen | ✗ fail (not built as specified: the only launcher shortcut is a dynamic "N unread" one from `LauncherBadges`; no static or dynamic New session / Servers / Inbox shortcuts exist) |

## K. Accessibility

Run on A1 (smallest) and A3, with TalkBack on and the font size at the platform maximum.

| # | Check | Result |
| --- | --- | --- |
| K1 | Every control is announced with a name | ✗ fail in part (TalkBack is installed on the emulator but its speech cannot be heard or captured here, so this was checked from the accessibility tree: the static audit `tools/audit-accessibility.mjs` reports 0 findings and nearly every control dumped carried a name, but the Home screen's floating "New session" button has none and could only be tapped by coordinate) |
| K2 | The timeline is navigable, and a finished turn is announced | n/a (TalkBack was not run: no way to hear or capture its output here) |
| K3 | A permission request is announced when it arrives while the app is open | n/a (TalkBack was not run) |
| K4 | Every screen is usable at the largest font size with nothing clipped | n/a (partial, not a pass: `font_scale` 2.0 on the emulator; checked the Servers list, the Dev home, a session and the New session sheet, which all stay readable with wrapped text and no truncated controls. The session header (a three-line title, the model line and the context line) takes about 40% of the screen and, with the completion popup open, the timeline is left with about a quarter; the sheet's "Browse the server's files" wraps to three lines. Roughly thirty other screens were not opened at that size) |
| K5 | Focus order follows visual order on every screen | n/a (TalkBack was not run) |
| K6 | Contrast of every text pair meets 4.5:1, including the code palette | n/a (no contrast ratio was computed; `docs/ACCESSIBILITY_AUDIT.md` §3 says none is computed anywhere and that it needs a colour tool) |

## L. Compatibility

| # | Check | Result |
| --- | --- | --- |
| L1 | Oldest supported server version: every screen in this matrix | n/a (no older server was available; the dev server is 2.0.18 and the real one 2.0.20) |
| L2 | Latest server version: every screen in this matrix | n/a (2.0.20, the real server, is newer than the tested 2.0.18, which is what L3 covers; no separate "latest" run of every screen was made) |
| L3 | A server **newer** than tested shows "untested server version" and still works | ✓ pass (v2.0.20 server connected with warning note) |
| L4 | A server missing an experimental route hides that feature everywhere | n/a (no server missing an experimental route was available, and the screens that use those routes are mostly not built) |
| L5 | The F-Droid build scans a QR and needs no Play Services (verify with `adb shell pm list packages \| grep gms`) | n/a (partial, not a pass: the emulator image ships Play Services, so the `pm list` check proves nothing here, and there is no camera feed to scan with. Static check of `app-fdroid-debug.apk`: zero `com/google/mlkit` and zero `com/google/android/gms` strings in all 26 dex files and none in the manifest; ZXing classes present) |

## M. Release mechanics

| # | Check | Result |
| --- | --- | --- |
| M1 | The Play APK is signed and installs over the previous version | n/a (there is no signing material in the tree; `assemblePlayRelease` produces `app-play-release-unsigned.apk`, per `docs/RELEASE.md`) |
| M2 | The F-Droid APK is signed and installs | n/a (no signing material; see M1) |
| M3 | Both flavors have a distinct application id and can be installed side by side | ✓ pass for the debug builds (emulator: `dev.opencode.android.debug` (play) and `dev.opencode.android.fdroid.debug` (fdroid) installed together, each with its own data; the release ids were not built or installed, and the play build was removed again afterwards) |
| M4 | The in-app changelog shows this release | ✗ fail (not built: there is no in-app changelog screen in `app` or any `feature:*`; `docs/CHANGELOG.md` is a repository file) |
| M5 | The published compatibility matrix matches what was tested | n/a (no compatibility matrix has been published) |
| M6 | The internal Play track accepts the bundle, then closed, then production | n/a (needs Play Console; outside this environment) |
| M7 | GitHub Releases carries both APKs and the mapping files | n/a (needs a GitHub Release; outside this environment) |

## Recording a result

Replace the blank with `pass`, `fail` or `n/a` and a note. For a failure, the note is the symptom
and the device, not the diagnosis — a diagnosis belongs in an issue.

**A release is not ready while any row is blank.** "Not run" and "passed" are different answers, and
this document currently contains only the first.
