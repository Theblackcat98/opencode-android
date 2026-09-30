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
| A1 | Smallest supported phone | Phone | 8.0 (API 26) | play | |
| A2 | Mid-range phone | Phone | 11 | play | |
| A3 | Current flagship | Phone | latest | play | |
| A4 | Small tablet | Tablet, portrait | 10 | play | |
| A5 | Tablet | Tablet, landscape | 13 | play | |
| A6 | Foldable, folded | Foldable | 13 | play | |
| A7 | Foldable, unfolded | Foldable, wide | 13 | play | |
| A8 | ChromeOS, resizable window | Desktop | 14+ | play | |
| A9 | Any one of A1–A3 | Phone | 11 | fdroid | |

A1 is the one that finds real problems: Android 8 has no notification channels beyond the basics,
no `POST_NOTIFICATIONS` permission, and the smallest screens.

## B. Connection

| # | Check | Result |
| --- | --- | --- |
| B1 | Add a server by scanning the QR from `opencode pair` | |
| B2 | Add a server by typing the URL and the password | ✓ pass (added http://192.168.1.199:4096 on Manual tab). A later run found that a 32-character password typed in one burst (as paste or autofill would) came out with its first two characters swapped in the Edit form and was rejected; fixed in `97c1fac`, and four bursts on the fixed build were identical and saved and connected |
| B3 | A plain `http://` server shows the **Unencrypted** badge on the list and the status page | ✓ pass (badge shown on server card and Server Details screen) |
| B4 | A wrong password reports "re-pair" and does not crash or hang | ✓ pass (401 shows 'Could not connect' dialog with 'Pair again' option) |
| B5 | An `https://` server with a self-signed certificate, with the CA installed, connects | |
| B6 | An `https://` server with a self-signed certificate, **without** the CA, refuses and says why | |
| B7 | The LAN prober is **off by default** and finds the server when switched on | |
| B8 | The LAN prober can be cancelled mid-scan and stops immediately | |
| B9 | "Pair another device" shows a QR, and the second device redeems it | |
| B10 | "Pair another device" is **hidden** on a server that answers 404 to `POST /api/pair` | |
| B11 | Removing a server removes its token from the keystore | ✓ pass (server deleted via menu with confirmation) |

## C. The read path

| # | Check | Result |
| --- | --- | --- |
| C1 | Projects, sessions and the timeline open cold and from cache | ✓ pass (real server: projects, `testproject` sessions and a timeline with Edit diff, command output, "Turn finished" on a cold start; the same offline in airplane mode) |
| C2 | Kill the app, reopen: the timeline is there immediately, then fills in | ✓ pass (force-stopped, airplane mode on, reopened: cached timeline shown at once; network back: context bar filled in. Note: the composer's "No model available" banner stays stale until the session is re-entered — see D8) |
| C3 | Text, reasoning and tool output stream and are ordered | |
| C4 | A long session scrolls without dropping a frame on a mid-range phone (A2) | n/a (no A2 device; not a pass. Emulator proxy, 768-message real session, 50 fling swipes: 393 frames, 5.85% janky, p50/p90 16 ms, p95 21 ms, p99 26 ms; the same swipes on the 122-row project list: 3.34% janky, p99 16 ms) |
| C5 | A session with an image attachment shows the image | ✗ fail (emulator, real server: the message shows a "1 attachment / clipway-mockup.webp" chip only; no image is drawn and tapping the chip does nothing) |
| C6 | An unknown message type renders as a generic fallback and does not crash | |

## D. Driving a session

| # | Check | Result |
| --- | --- | --- |
| D1 | A prompt reaches the agent and the reply comes back | ✓ pass (dev server, `fake/reasoning`: prompt arrived verbatim, reasoning block then answer, "Turn finished", title generated) |
| D2 | Steer interrupts a running turn | ✓ pass after fix `4f38c92`, with a limit on what was shown (dev server, `fake/slow`, a single 40 s step). First run failed: while a turn ran the composer's action row (seven icons plus "Stop" and "Background") pushed Send off the 427 dp-wide screen, and the only tappable thing at the right edge sent `POST …/background`. Fixed build: Stop and Background sit in their own row, Send stays at the bottom right; a prompt sent mid-turn with Steer was accepted at once and answered. With one long step the server takes the steered prompt at the step boundary (recorded 41 s after the turn began), so "interrupts" here means "does not wait for the user to stop it"; it is not distinguishable from Queue with this model |
| D3 | Queue parks a prompt until the turn ends | ✓ pass after fix `4f38c92` (same setup: long-press on Send while a turn ran showed the prompt at once and the server recorded it only after the running turn ended, 41 s later. The queued bubble is not labelled as queued) |
| D4 | A permission request appears in the shade **and** in the app, and answering from either works | ✗ fail (partial; dev server, request raised with `POST …/permission`, action `external_directory` so that the server answers `ask`. In the app: the home shows "1 permissions and questions need an answer" and the inbox offers Allow once / Always allow / Reject with an optional reason. Not shown: the shade. With notifications allowed through the app's own "Turn on" flow and the app backgrounded, the app had posted only a silent "Dev — 0 waiting for you, 1 in total" summary and no permission notification, and no `ConnectionService` was running: the foreground service started at 04:35 with the app open and stopped 04:38 (the 2-minute idle stop), and switching on "Always connected" did not start it again. Answering from the shade was not reached. **Crash found while trying:** with a permission request left pending the app killed itself about 10 s after every launch with `ForegroundServiceDidNotStartInTimeException: Context.startForegroundService() did not then call Service.startForeground()` for `ConnectionService` (`ConnectionServiceLauncher.ask`); it happened with notifications allowed and again with the permission revoked, and stopped as soon as the pending requests were answered. Fix in progress) |
| D5 | "Allow always" **shows the patterns it will store** before it stores them | ✓ pass (dev server: "Always allow" opens "Save this rule? The server will match these patterns from now on, without asking again: `/etc/*`, `/var/*`" with Dismiss / Always allow; nothing was stored until confirmed. Dismissed) |
| D6 | A form renders and answers, including a multiselect and a conditional field | ✓ pass (dev server, form raised through `POST …/form` with a required single-select, a required multiselect (`minItems` 1) and a `when kind = bug` text field: the dock showed Kind and Areas only; choosing Bug revealed "Steps to reproduce"; the multiselect counted "2 selected"; Reply cleared the dock and the server showed no pending form; the inspector shows `form.created` then `form.replied`. The answer's contents were not inspected: the inspector truncates payloads) |
| D7 | Interrupt, background and compact all work from the session overflow | ✓ pass after fix `76ae252`, though none of the three is in the session overflow (dev server). Interrupt and Background are composer buttons while a turn runs: Stop sent `POST …/interrupt?resume=false` and the timeline ended "Turn interrupted"; Background sent `POST …/background` (204). Compact is the `/compact` app command: first run failed because Send was disabled for an app command with no argument; on the fixed build it sent the request, the server created a compaction and the app showed "Compaction failed — Compaction summary did not match the required template", which is the fake provider unable to write a real summary, not the app |
| D8 | The session survives a network drop mid-turn and resyncs on reconnect | |

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
| F1 | A shell command runs and its output streams | ✗ fail (dev server, Session menu → Commands: `echo one && sleep 3 && echo two && sleep 3 && echo three` ran ("Running", "Stop the command", then "Exited with code 0") and the server wrote `one two three` to its output file and returns it from `GET /api/shell/{id}/output` (`{"data":{"output":"one\ntwo\nthree\n","cursor":14,"size":14,"truncated":false}}`), but the Output pane said "No output yet." before, during and after, also with the row selected. Fix in progress. Aside: the run button sits under the keyboard's floating toolbar while the field is focused, so on this emulator it can only be pressed with Tab then Enter) |
| F2 | A PTY terminal renders colours and handles a full-screen program (`htop`, `vim`) | ✓ pass after fixes `f2d1adb` and `79f4c51` (dev server; `htop` and `vim` are not installed on this host, so `top` and `less` stood in). First run: "unknown-terminal" every time (the new id was looked up in a list the server's event had not filled yet). Second run: the terminal opened ("Live") but stayed blank — the WebView was added with WRAP_CONTENT so the page had a zero-height viewport and the host's output messages were in a shape the page ignored. Fixed build, checked by a subagent with screenshots: prompt and `ls --color=always /` draw with colours; `top` draws; `less` uses the alternate screen and restores the earlier one on quit. I re-checked on my own install that a new terminal draws its prompt. Known rough edges: the first prompt is drawn twice with a `%` artifact (drawn at 80×24 before the first resize); xterm.js garbles characters injected about 1 ms apart (`adb input text`), one character per 200 ms is exact |
| F3 | The terminal survives rotation and app backgrounding, reattaching at the right cursor | ✓ pass (checked by a subagent on the fixed build: rotating to landscape re-attached with all content and the cursor at the prompt, and back again; HOME, 10 s away and back kept the content, including a slow `ping`'s output that arrived while backgrounded. Before the fix a rotation reset the screen to "No terminal open" over a live socket. Not re-run by me) |
| F4 | Resizing the terminal on a foldable or a tablet resizes the PTY | ✓ pass on a phone rotation, not on a foldable or tablet (subagent: `stty size` printed `33 52` in portrait and `8 113` after rotating, matching the visible grid; landscape gets only 8 rows because the terminal list keeps 40% of the height) |
| F5 | A worktree is created, used and removed | ✓ pass, with a defect in the form (dev server, Session menu → Worktrees). Created from the form with only "Name" filled (`form-made`; it appears in `git worktree list` and in the app after the response); "Move a session here" changed the open session's directory on the server (to `wt-only-name`, then to `mighty-engine`); "Remove the worktree" asked "Remove this worktree?" and after "Remove it" the worktree was gone from git and from the list. **Defect: the "Branch from (ref)" and "Branch name" fields cannot be used.** The server treats `from` as a directory and `branch` as an existing ref, so `master` / `wt-test` came back `400` ("Worktree directory unavailable: master", "fatal: invalid reference: wt-test") and the app showed nothing at all: the form cleared and no error appeared |

## G. Integrations and configuration

| # | Check | Result |
| --- | --- | --- |
| G1 | An OAuth login completes in a browser and the credential appears | |
| G2 | A command-based login completes and the credential appears | |
| G3 | A cancelled or expired login says so rather than appearing to hang | |
| G4 | An MCP server is added, connected and disconnected | |
| G5 | A plugin is listed, updated, and its RPC console calls a method | |
| G6 | The config explorer shows a document, and editing it validates before saving | |
| G7 | The **write confirmation names the global file** for the global shell setting | |
| G8 | A failed write leaves the previous document intact and says why | |

## H. Insights and extensibility

| # | Check | Result |
| --- | --- | --- |
| H1 | The usage dashboard loads and the heatmap, streak, totals and per-model usage render | |
| H2 | Changing the date range or the project re-queries and the numbers change | |
| H3 | Tool reliability shows successes, failures and a p50 duration | |
| H4 | **The dashboard is hidden** on a server with no `/api/experimental/session/stats` | |
| H5 | The quick-ask widget generates and shows the text | |
| H6 | The RPC console sends JSON and renders the answer, including an error | |
| H7 | The RPC console **refuses** an rpc id or method that would break the URL | |
| H8 | The session log viewer replays and stops at `log.synced` | |
| H9 | A synthetic note appears in the transcript labelled as synthetic, not as a user message | |
| H10 | "Wait until idle" blocks until the turn ends, and can be stopped | |
| H11 | A plugin-created permission request **blocks the agent** and is labelled as plugin-created | ✗ fail (dev server: a request raised with `session.permission.create` appears in the inbox exactly like a tool's ("Allow external_directory? It would touch /etc/hosts"), with no label saying it was not created by a tool call, and the request's `metadata.title` was not shown; there is no "plugin-created" string anywhere in the code. Whether it blocks the agent is the server's behaviour and was not exercised, since no agent turn was waiting) |
| H12 | A plugin-created form renders through the normal form engine | ✓ pass (the D6 form was created with `session.form.create`, not by the agent, and rendered and answered through the same dock and form engine) |

## I. TUI control events

Run these with a TUI connected to the same server as a second client.

| # | Check | Result |
| --- | --- | --- |
| I1 | A toast in the TUI appears as a snackbar in the app | |
| I2 | **Follow-desktop is off by default**: selecting a session in the TUI does not move the phone | |
| I3 | With follow-desktop on, selecting a session in the TUI moves the phone | |
| I4 | With follow-desktop on, TUI prompt text appears in the app's composer | |
| I5 | Turning follow-desktop back off stops both immediately | |
| I6 | A TUI command that would change server state is **offered, not performed** | |
| I7 | A command this build does not know is shown verbatim rather than dropped | |
| I8 | A plugin's `rpc.*` event appears in the event viewer | |

## J. Adaptive layout and input

| # | Check | Result |
| --- | --- | --- |
| J1 | On A5 and A7 the list-detail panes show side by side, not stacked | |
| J2 | Rotating a tablet swaps between the two pane arrangements without losing scroll | |
| J3 | On A8, resizing the window across the list-detail breakpoint reflows | |
| J4 | Ctrl+P opens the command palette on a device with a keyboard (A8) | |
| J5 | A leader-key combination runs the same action | |
| J6 | The command palette is reachable **without** a keyboard, by touch | |
| J7 | Session tabs open, switch and close | |
| J8 | The widget shows running sessions and pending approvals | |
| J9 | The Quick Settings tile starts and stops the connection | |
| J10 | App shortcuts from the launcher land on the right screen | |

## K. Accessibility

Run on A1 (smallest) and A3, with TalkBack on and the font size at the platform maximum.

| # | Check | Result |
| --- | --- | --- |
| K1 | Every control is announced with a name | |
| K2 | The timeline is navigable, and a finished turn is announced | |
| K3 | A permission request is announced when it arrives while the app is open | |
| K4 | Every screen is usable at the largest font size with nothing clipped | |
| K5 | Focus order follows visual order on every screen | |
| K6 | Contrast of every text pair meets 4.5:1, including the code palette | |

## L. Compatibility

| # | Check | Result |
| --- | --- | --- |
| L1 | Oldest supported server version: every screen in this matrix | |
| L2 | Latest server version: every screen in this matrix | |
| L3 | A server **newer** than tested shows "untested server version" and still works | ✓ pass (v2.0.20 server connected with warning note) |
| L4 | A server missing an experimental route hides that feature everywhere | |
| L5 | The F-Droid build scans a QR and needs no Play Services (verify with `adb shell pm list packages \| grep gms`) | n/a (partial, not a pass: the emulator image ships Play Services, so the `pm list` check proves nothing here, and there is no camera feed to scan with. Static check of `app-fdroid-debug.apk`: zero `com/google/mlkit` and zero `com/google/android/gms` strings in all 26 dex files and none in the manifest; ZXing classes present) |

## M. Release mechanics

| # | Check | Result |
| --- | --- | --- |
| M1 | The Play APK is signed and installs over the previous version | |
| M2 | The F-Droid APK is signed and installs | |
| M3 | Both flavors have a distinct application id and can be installed side by side | |
| M4 | The in-app changelog shows this release | |
| M5 | The published compatibility matrix matches what was tested | |
| M6 | The internal Play track accepts the bundle, then closed, then production | |
| M7 | GitHub Releases carries both APKs and the mapping files | |

## Recording a result

Replace the blank with `pass`, `fail` or `n/a` and a note. For a failure, the note is the symptom
and the device, not the diagnosis — a diagnosis belongs in an issue.

**A release is not ready while any row is blank.** "Not run" and "passed" are different answers, and
this document currently contains only the first.
