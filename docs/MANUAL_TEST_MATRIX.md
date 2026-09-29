# Manual test matrix — Phase 10 release

Plan §5.3 asks for a matrix of phone, tablet and foldable, Android 8 to latest, and HTTP and HTTPS
servers, run before each release. Plan §6's second exit criterion is that the release candidate
passes it.

**It was not run. There is no device, no emulator and no second device in this environment**, so
this document is the matrix and the procedure, not a result, and part of it cannot be run against the
current build (see below). Nothing in the repository claims the
release candidate passed it, and this is the one exit criterion Phase 10 does not meet.

Run it and record the outcome in the *Result* column of each row. A row with a blank result is
untested, not passed.

## Rows that cannot pass yet

**37 rows test a feature that is not reachable in the app**, and are marked **blocked** in the *Result* column
with the [Phase 11](./ANDROID_APP_PLAN.md#phase-11-screens-adaptive-ui-and-the-release-l) item that has to be
built first (H1–H4, for instance, need the usage dashboard linked into navigation). Blocked is neither passed
nor failed: it means the row cannot be run against this build. The matrix was written from the plan's feature
list, not from what was built, so these rows stay in it because the plan still promises the feature. As each
item lands, replace *blocked* with a blank and run the row. **A release is not ready while any row is blocked
either.**

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
| B2 | Add a server by typing the URL and the password | |
| B3 | A plain `http://` server shows the **Unencrypted** badge on the list and the status page | |
| B4 | A wrong password reports "re-pair" and does not crash or hang | |
| B5 | An `https://` server with a self-signed certificate, with the CA installed, connects | |
| B6 | An `https://` server with a self-signed certificate, **without** the CA, refuses and says why | |
| B7 | The LAN prober is **off by default** and finds the server when switched on | **blocked** — LAN prober not built (11.8) |
| B8 | The LAN prober can be cancelled mid-scan and stops immediately | **blocked** — LAN prober not built (11.8) |
| B9 | "Pair another device" shows a QR, and the second device redeems it | **blocked** — no screen (11.7) |
| B10 | "Pair another device" is **hidden** on a server that answers 404 to `POST /api/pair` | **blocked** — no screen (11.7) |
| B11 | Removing a server removes its token from the keystore | |

## C. The read path

| # | Check | Result |
| --- | --- | --- |
| C1 | Projects, sessions and the timeline open cold and from cache | |
| C2 | Kill the app, reopen: the timeline is there immediately, then fills in | |
| C3 | Text, reasoning and tool output stream and are ordered | |
| C4 | A long session scrolls without dropping a frame on a mid-range phone (A2) | |
| C5 | A session with an image attachment shows the image | |
| C6 | An unknown message type renders as a generic fallback and does not crash | |

## D. Driving a session

| # | Check | Result |
| --- | --- | --- |
| D1 | A prompt reaches the agent and the reply comes back | |
| D2 | Steer interrupts a running turn | |
| D3 | Queue parks a prompt until the turn ends | |
| D4 | A permission request appears in the shade **and** in the app, and answering from either works | |
| D5 | "Allow always" **shows the patterns it will store** before it stores them | |
| D6 | A form renders and answers, including a multiselect and a conditional field | |
| D7 | Interrupt, background and compact all work from the session overflow | |
| D8 | The session survives a network drop mid-turn and resyncs on reconnect | |

## E. Review and history

| # | Check | Result |
| --- | --- | --- |
| E1 | A diff renders with additions and deletions coloured and labelled | |
| E2 | Undo stages, redoes clears, and a commit reverts the files | |
| E3 | Fork produces a new session that is independently usable | |
| E4 | A binary file in a diff is shown as binary, not as mojibake | |
| E5 | The file browser opens a file larger than memory and does not hang | |

## F. Execution

| # | Check | Result |
| --- | --- | --- |
| F1 | A shell command runs and its output streams | |
| F2 | A PTY terminal renders colours and handles a full-screen program (`htop`, `vim`) | |
| F3 | The terminal survives rotation and app backgrounding, reattaching at the right cursor | |
| F4 | Resizing the terminal on a foldable or a tablet resizes the PTY | |
| F5 | A worktree is created, used and removed | |

## G. Integrations and configuration

| # | Check | Result |
| --- | --- | --- |
| G1 | An OAuth login completes in a browser and the credential appears | |
| G2 | A command-based login completes and the credential appears | |
| G3 | A cancelled or expired login says so rather than appearing to hang | |
| G4 | An MCP server is added, connected and disconnected | |
| G5 | A plugin is listed, updated, and its RPC console calls a method | **blocked** — the plugin list can be tested; the RPC console half has no screen (11.3) |
| G6 | The config explorer shows a document, and editing it validates before saving | |
| G7 | The **write confirmation names the global file** for the global shell setting | |
| G8 | A failed write leaves the previous document intact and says why | |

## H. Insights and extensibility

| # | Check | Result |
| --- | --- | --- |
| H1 | The usage dashboard loads and the heatmap, streak, totals and per-model usage render | **blocked** — screen exists but is not reachable (11.1) |
| H2 | Changing the date range or the project re-queries and the numbers change | **blocked** — not reachable (11.1) |
| H3 | Tool reliability shows successes, failures and a p50 duration | **blocked** — not reachable (11.1) |
| H4 | **The dashboard is hidden** on a server with no `/api/experimental/session/stats` | **blocked** — not reachable (11.1) |
| H5 | The quick-ask widget generates and shows the text | **blocked** — no screen (11.6) |
| H6 | The RPC console sends JSON and renders the answer, including an error | **blocked** — no screen (11.3) |
| H7 | The RPC console **refuses** an rpc id or method that would break the URL | **blocked** — no screen (11.3) |
| H8 | The session log viewer replays and stops at `log.synced` | **blocked** — no screen (11.4) |
| H9 | A synthetic note appears in the transcript labelled as synthetic, not as a user message | **blocked** — no screen (11.5) |
| H10 | "Wait until idle" blocks until the turn ends, and can be stopped | **blocked** — no screen (11.5) |
| H11 | A plugin-created permission request **blocks the agent** and is labelled as plugin-created | **blocked** — no screen (11.5) |
| H12 | A plugin-created form renders through the normal form engine | **blocked** — no screen (11.5) |

## I. TUI control events

Run these with a TUI connected to the same server as a second client.

| # | Check | Result |
| --- | --- | --- |
| I1 | A toast in the TUI appears as a snackbar in the app | **blocked** — nothing collects `TuiControl` (11.2) |
| I2 | **Follow-desktop is off by default**: selecting a session in the TUI does not move the phone | **blocked** — 11.2 |
| I3 | With follow-desktop on, selecting a session in the TUI moves the phone | **blocked** — 11.2 |
| I4 | With follow-desktop on, TUI prompt text appears in the app's composer | **blocked** — 11.2 |
| I5 | Turning follow-desktop back off stops both immediately | **blocked** — 11.2 |
| I6 | A TUI command that would change server state is **offered, not performed** | **blocked** — 11.2 |
| I7 | A command this build does not know is shown verbatim rather than dropped | **blocked** — 11.2 |
| I8 | A plugin's `rpc.*` event appears in the event viewer | **blocked** — no viewer (11.3) |

## J. Adaptive layout and input

| # | Check | Result |
| --- | --- | --- |
| J1 | On A5 and A7 the list-detail panes show side by side, not stacked | **blocked** — not built (11.12) |
| J2 | Rotating a tablet swaps between the two pane arrangements without losing scroll | **blocked** — not built (11.12) |
| J3 | On A8, resizing the window across the list-detail breakpoint reflows | **blocked** — not built (11.12) |
| J4 | Ctrl+P opens the command palette on a device with a keyboard (A8) | **blocked** — not built (11.9) |
| J5 | A leader-key combination runs the same action | **blocked** — not built (11.9) |
| J6 | The command palette is reachable **without** a keyboard, by touch | **blocked** — not built (11.9) |
| J7 | Session tabs open, switch and close | **blocked** — not built (11.11) |
| J8 | The widget shows running sessions and pending approvals | **blocked** — not built (11.10) |
| J9 | The Quick Settings tile starts and stops the connection | **blocked** — not built (11.10) |
| J10 | App shortcuts from the launcher land on the right screen | **blocked** — not built (11.10) |

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
| L3 | A server **newer** than tested shows "untested server version" and still works | |
| L4 | A server missing an experimental route hides that feature everywhere | |
| L5 | The F-Droid build scans a QR and needs no Play Services (verify with `adb shell pm list packages \| grep gms`) | |

## M. Release mechanics

| # | Check | Result |
| --- | --- | --- |
| M1 | The Play APK is signed and installs over the previous version | |
| M2 | The F-Droid APK is signed and installs | |
| M3 | Both flavors have a distinct application id and can be installed side by side | |
| M4 | The in-app changelog shows this release | **blocked** — not built (11.15) |
| M5 | The published compatibility matrix matches what was tested | **blocked** — not built (11.15) |
| M6 | The internal Play track accepts the bundle, then closed, then production | |
| M7 | GitHub Releases carries both APKs and the mapping files | |

## Recording a result

Replace the blank with `pass`, `fail` or `n/a` and a note. For a failure, the note is the symptom
and the device, not the diagnosis — a diagnosis belongs in an issue.

**A release is not ready while any row is blank or blocked.** "Not run" and "passed" are different answers, and
this document currently contains only the first.
