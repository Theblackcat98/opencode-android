# Test automation plan — from the manual matrix to runnable suites

The release gate is `docs/MANUAL_TEST_MATRIX.md`: **a release is not ready while any row is
blank**. This document maps every row A1–M7 to one of five buckets so the remaining work is
explicit and nobody mistakes "not run" for "passed".

**Bucket legend**

| Mark | Meaning |
| ---- | ------- |
| ✅ | Automated by this change: implemented, wired into CI, runs on every push |
| 🔧 | Automatable with the current product: design sketched, not implemented |
| 🚫 | Blocked: the product UI for this row does not exist in `main` (verified by source grep, 2026-09-29) |
| 🧑 | Needs a human, a physical device, or judgment no assertion can make |
| 🔑 | Release-owner only: signing, publishing, store tracks |

## What this change automates

`app/src/androidTest/` — on-device E2E against a **real** `opencode serve` (no fakes, no stubbed
view models). Every test starts from a wiped app (`ClearDataRule`) and takes its endpoints as
instrumentation arguments, so the same APK runs anywhere:

- `serverUrl` — plain HTTP, e.g. `http://10.0.2.2:4096`
- `serverPassword` — never hard-coded, never logged
- `httpsUrl` — the same server behind `scripts/e2e-https.sh` (Node TLS relay, throwaway
  self-signed CA, SAN `IP:10.0.2.2`, EKU `serverAuth`)
- `caInstalled` — whether `scripts/e2e-ca-install.sh` placed the CA in the emulator's
  user-CA directory (`/data/misc/keychain/cacerts-added`, the on-disk form of a Settings install)

`.github/workflows/instrumented-tests.yml` — runs the suite on KVM-capable CI (it has **not**
run yet; the dev VM has no `/dev/kvm`). Matrix: Play flavor × API 29/34 × CA on/off, plus an
F-Droid job on a no-GMS image (API 34, `target: default`).

| Test | Matrix rows | What it proves |
| ---- | ----------- | -------------- |
| `b1_pastedPairingLinkRedeems` | B1 (half) | A real `POST /api/pair` code redeems through the paste-link tab — the same redemption path the QR scanner feeds |
| `b2_manualEntryAddsServerToList` | B2 | Manual URL + password adds the server |
| `b3_httpServerShowsUnencryptedBadgeOnListAndStatus` | B3 | Unencrypted badge on the list **and** the status page for plain HTTP |
| `b4_wrongPasswordReportsRepairWithoutCrashing` | B4 | Wrong password → error dialog with "pair again", app alive, nothing added |
| `b5_httpsWithCaInstalledAndTrustOptInConnects` | B5 | HTTPS + CA in the user-CA dir + the per-server trust toggle → connects, no badge |
| `b6_httpsWithoutTrustRefusesAndSaysWhy` | B6 | HTTPS + no CA + toggle off → TLS error, no "pair again" (not an auth failure) |
| `b11_removingServerDeletesItFromTheList` | B11 (half) | Delete removes the server from the list (keystore removal is unit-covered by `SecureCredentialStoreTest`) |
| `c1_statusScreenReadsLiveServerData` | C1 (core) | Status screen renders from the loaded profile; the test-connection button disables during the round-trip and re-enables after |
| `c2_serverListSurvivesProcessDeath` | C2 (half) | `am force-stop`, cold relaunch → the server row renders from cache |

Two main-source changes support the suite, both additive: a `manual_name_input` test tag and a
`trust_user_ca_toggle` test tag on the existing user-CA trust toggle (`AddServerScreen.kt`).
No behavior changed.

## Row-by-row mapping

### A. Devices and form factors

| Row | Bucket | Note |
| --- | ------ | ---- |
| A1 | 🧑 | Smallest phone, API 26 — needs a physical device; API 26 emulator image can be added to the workflow as a smoke leg |
| A2 | ✅/🧑 | API 29 emulator leg approximates it; the real mid-range verdict (C4) stays human |
| A3 | ✅/🧑 | API 34 emulator leg approximates it; flagship-only behavior (camera, perf) stays human |
| A4–A7 | 🔧 | Tablet/foldable emulator images + the existing adaptive-layout code; extend the workflow matrix |
| A8 | 🔧 | ChromeOS/ARC++ image or a resizable-window emulator leg |
| A9 | ✅/🧑 | F-Droid job runs on API 34 with no GMS; physical-device confirmation stays manual |

### B. Connection

| Row | Bucket | Note |
| --- | ------ | ---- |
| B1 | ✅/🧑 | ✅ pairing-code redemption path; 🧑 the camera scan half needs a physical camera |
| B2 | ✅ | |
| B3 | ✅ | List and status page |
| B4 | ✅ | |
| B5 | ✅ | Via the per-server trust toggle; see the caveat below |
| B6 | ✅ | |
| B7 | 🚫 | No LAN prober in source — nothing to automate or manually test |
| B8 | 🚫 | Same |
| B9 | 🧑 | Needs a second physical device redeeming a real QR |
| B10 | 🔧 | "Pair another device" hidden on `POST /api/pair` → 404. Automatable: extend the fake/dev server with a 404 mode and assert the entry point is hidden |
| B11 | ✅/🔧 | ✅ list removal; the keystore half is unit-tested, an on-device assertion (re-add fails without password) is possible future work |

**B5 caveat (read before trusting the green).** The app reads user CAs by listing
`/data/misc/keychain/cacerts-added` directly (`AndroidUserCertificateSource`), bypassing the
KeyChain API. That directory's readability by the app UID/SELinux policy is platform-dependent;
if the emulator denies the read, B5 fails with a TLS error — which is *real signal* (manual B5
would hit the same wall), not test flakiness. If it fails, the fix belongs in the product
(a `KeyChain`-backed source or a bundled-CA import), not in the test.

### C. The read path

| Row | Bucket | Note |
| --- | ------ | ---- |
| C1 | ✅/🔧 | ✅ status-screen live data; 🔧 projects/sessions/timeline need a way to reach the per-server home in a test — `ServersScreen` never invokes the `onHome`/`onHomeClick` callback it is given, so either wire that affordance or add test-only deep-link navigation first |
| C2 | ✅/🔧 | ✅ server list survives process death; 🔧 the "timeline fills in after cache" half needs C1's navigation first |
| C3 | 🔧 | Stream ordering over the real server's SSE: send a prompt via the fake provider, assert the timeline renders text/reasoning/tool output in order |
| C4 | 🧑 | Frame drops on a mid-range phone are a human verdict (`dumpsys gfxinfo` can assist, not decide) |
| C5 | 🔧 | Fixture: a session whose transcript contains an image attachment; assert it renders |
| C6 | 🔧 | Fixture: inject an unknown event type (the generated corpus in `tools/gen-event-payloads.mjs` is the source); assert the generic fallback renders and nothing crashes |

### D. Driving a session

All 🔧, none implemented. The pattern is the same everywhere: real server + scripted fake
provider (already in `scripts/dev-server.sh`) + Compose assertions on the session screen.

| Row | Automatable as |
| --- | -------------- |
| D1 | Send a prompt, wait for the fake provider's reply to stream in |
| D2 | Start a long turn, tap steer, assert the turn stops |
| D3 | Queue a prompt mid-turn, assert it sends after the turn ends |
| D4 | Trigger a permission request; assert the notification **and** the in-app banner, answer from each |
| D5 | "Allow always" dialog asserts the stored patterns are shown before confirmation |
| D6 | Fixture a form (multiselect + conditional field), answer it end to end |
| D7 | Interrupt / background / compact from the session overflow menu |
| D8 | Kill the relay mid-turn (network drop), restart it, assert resync |

### E. Review and history

All 🔧, none implemented. Fixtures via the real server (a repo with a prepared git history).

| Row | Automatable as |
| --- | -------------- |
| E1 | Diff renders, additions/deletions colored and labelled |
| E2 | Undo stages / redo clears / commit reverts, verified against the working tree |
| E3 | Fork creates an independent session |
| E4 | Binary file in a diff renders as binary |
| E5 | File browser opens a >memory file without hanging (time-boxed assertion) |

### F. Execution

| Row | Bucket | Note |
| --- | ------ | ---- |
| F1 | 🔧 | Shell command via the real server; assert output streams |
| F2 | 🧑/🔧 | Automation can drive `htop`/`vim`; judging the render is human |
| F3 | 🧑/🔧 | Rotation + backgrounding can be scripted (`adb`, `UiDevice`); "reattaches at the right cursor" is human-verified |
| F4 | 🔧 | Foldable emulator leg + assert the PTY resized (needs F's navigation first) |
| F5 | 🔧 | Worktree create/use/remove through the session UI |

### G. Integrations and configuration

| Row | Bucket | Note |
| --- | ------ | ---- |
| G1 | 🧑 | OAuth against a real provider needs a real browser and real account; scripted only with a throwaway test OAuth app |
| G2 | 🔧 | Command-based login with a fixture script |
| G3 | 🔧 | Expired/cancelled login asserts the error state, not a hang (time-boxed) |
| G4 | 🔧 | Fixture stdio MCP server: add, connect, disconnect |
| G5 | 🔧 | Plugin fixture: list, update, RPC console call |
| G6 | 🔧 | Config explorer: invalid edit is rejected before save, valid edit saves |
| G7 | 🔧 | Assert the write-confirmation dialog names the global file |
| G8 | 🔧 | Failed write: previous document intact + reason shown |

### H. Insights and extensibility

| Row | Bucket | Note |
| --- | ------ | ---- |
| H1–H4 | 🔧 | The insights surface exists (`feature/insights`); assert dashboard loads, range/project re-queries change numbers, and H4's hidden state on a server without `/api/experimental/session/stats` |
| H5 | 🚫/🧑 | No widget in source; if one lands, widget testing is host-driven (`AppWidgetHost`) and partly manual |
| H6–H7 | 🔧 | RPC console: valid call renders the answer, malicious rpc id/method is refused |
| H8 | 🔧 | Session log viewer replays and stops at `log.synced` |
| H9 | 🔧 | Synthetic note renders labelled synthetic |
| H10 | 🔧 | "Wait until idle" blocks until the turn ends; stop cancels it (time-boxed) |
| H11–H12 | 🔧 | Plugin fixture: plugin-created permission request blocks the agent and is labelled; plugin-created form renders |

### I. TUI control events

All 🧑. These rows define a *second client* (a real TUI) interacting with the same server —
follow-desktop, toasts, offered-not-performed commands. A scripted fake-TUI that replays the
event protocol is conceivable future work, but the matrix as written is a two-client manual run.

### J. Adaptive layout and input

| Row | Bucket | Note |
| --- | ------ | ---- |
| J1–J3 | 🔧 | Tablet/foldable/ChromeOS emulator legs (see A4–A8); assert pane arrangements and rotation/window-resize reflow |
| J4–J6 | 🚫 | No command palette in source |
| J7 | 🔧 | Session tabs open/switch/close (needs the session UI navigation from C1) |
| J8 | 🚫 | No widget in source |
| J9 | 🚫 | No Quick Settings tile in source |
| J10 | 🔧 | `adb` deep-link intents assert launcher shortcuts land on the right screen |

### K. Accessibility

All 🧑, by design: TalkBack on, platform-maximum font, on A1 and A3. K1 (every control named)
and K6 (contrast) can gain *assisting* automation (a test that fails on missing content
descriptions; a contrast scan), but the matrix rows demand the experiential pass — a human with
TalkBack on.

### L. Compatibility

| Row | Bucket | Note |
| --- | ------ | ---- |
| L1 | 🔧 | `scripts/dev-server.sh` already pins `OPENCODE_VERSION`: run the whole E2E suite against the oldest supported server |
| L2 | 🔧 | Same, against the latest |
| L3 | 🔧 | Point at a newer-than-tested server, assert the "untested server version" notice and that everything still works |
| L4 | 🔧 | Server without an experimental route → assert the feature hides everywhere (same fixture as H4) |
| L5 | 🔧 | One `adb shell pm list packages \| grep gms` assertion in the F-Droid job — five lines, do it next |

### M. Release mechanics

| Row | Bucket | Note |
| --- | ------ | ---- |
| M1–M2 | 🔑 | Signed builds are Nick's to produce — signing material must never be created by an agent |
| M3 | 🔧 | Assert the two flavors' application IDs differ and both APKs install side by side (`adb install`, no signature needed for debug) |
| M4 | 🔧 | Assert the in-app changelog lists the release |
| M5 | 🔑 | The published matrix is a release-owner attestation |
| M6 | 🔑 | Play tracks are Nick's |
| M7 | 🔑 | GitHub Releases is Nick's |

## Suggested build order for the next automation batch

1. **L5** — five lines in the existing workflow, closes a compatibility row.
2. **M3/M4** — cheap APK-level assertions, no UI needed.
3. **C1/D1 navigation** — unblock the per-server home (wire `onHomeClick` or add test deep links); this one change unlocks C1–C3, D1–D8, E, F, J7.
4. **L1/L2** — re-run the E2E suite against oldest/latest `OPENCODE_VERSION`; multiplies every row's value.
5. **D1–D3, G6–G8** — the highest-risk user journeys after connection.

## What this VM can and cannot do

- ✅ Compile everything (`compilePlayDebugAndroidTestKotlin`), run host-side unit/integration tests.
- ✅ The TLS relay + cert minting (verified end to end with `curl --cacert` on 2026-09-29).
- ❌ No `/dev/kvm` → no emulator → no instrumented test has executed here. Nothing in this
  document claims otherwise.
