# Release — status, compatibility and runbook

Everything a release needs, what the build has been checked against, and an honest statement of what has not been done.

## What exists and what does not

| | State |
| --- | --- |
| Release signing configured | **Yes** — reads `keystore.properties` or the environment, and attaches the config only when all four values are present |
| A keystore in the repository | **No**, and none was created. There is no signing material anywhere in this tree. |
| A signed artifact | **Not produced.** `assemblePlayRelease` produces `app-play-release-unsigned.apk`, named for what it is. |
| Release assembly | **Compile-only unless signed.** `assemblePlayRelease` and `assembleFdroidRelease` can succeed and produce unsigned APKs; that success is not a release gate. |
| Publishable release gate | **Available, not yet run.** `verifyPublishableRelease` requires signing material and verifies both generated APK signatures with `apksigner`; it does not upload or publish. |
| The Play Console track progression | **Not run.** No account, no upload. |
| GitHub Releases | **Not published.** No release was cut. |
| The manual test matrix | **Partly run.** One API 36 phone emulator: the fdroid debug build throughout, the play debug build for A3 and M3, against a disposable 2.0.18 dev server and a real 2.0.20 server (read-only), plus one self-signed HTTPS endpoint (B5, B6). No physical device, no second device, no tablet, no older Android, no TalkBack, no release build. Several rows fail (B10, J1–J6, J9, J11–J13, K6) or are n/a. `MANUAL_TEST_MATRIX.md` has the result of every row. |
| The Phase 10 screens | **Partly built.** The usage dashboard (with its project row), Quick ask, the RPC console, the session event history, synthetic notes and wait-until-idle are reachable and were exercised on the emulator (H1–H3, H5, H8–H10). The widget (J8) and session tabs (J7) were seen on the emulator; the tile (J9) and the home/bottom-bar/session screens (J11–J13) are unexercised; the wide-window layout (J1–J3) needs a tablet or a resizable window. The OpenCode theme import is not built. See `CHANGELOG.md` and item 5 below. |
| Version | `0.1.0`, `versionCode` 1 |

**No signed artifact exists.** That is the accurate statement, and nothing in this repository claims
otherwise.


## Compatibility: what this build has been checked against

What this build has been checked against, and what has not. Each row cites the file, or the row of
`MANUAL_TEST_MATRIX.md` (for example L3), that establishes it.

Status is one of three values:

- **Tested**, with where and how it was run.
- **Built but not run**: an artifact exists, and nothing was executed on it.
- **Not tested**.

The release candidate has not passed the manual matrix, and this document does not change that
(`MANUAL_TEST_MATRIX.md`, header).

### 1. Android versions

| Setting | Value | Source | Status |
| --- | --- | --- | --- |
| minSdk | 26 | `gradle/libs.versions.toml` line 9; `build-logic/.../KotlinAndroid.kt` line 23 | Not tested: no API 26 device (A1) |
| compileSdk | 37 | `libs.versions.toml` line 10; `KotlinAndroid.kt` line 22 | Built but not run: debug and release APKs exist under `app/build/outputs/apk/`, not rebuilt for this record |
| targetSdk | 36 | `libs.versions.toml` line 11; `AndroidApplicationConventionPlugin.kt` line 31 | Tested on API 36 only (header; A3) |

| Android | Device | Matrix rows | Status |
| --- | --- | --- | --- |
| 16 (API 36) | Phone emulator, 1280×2856, 480 dpi | Header; A3 | Tested: fdroid debug build throughout (header); play debug build installed and launched (A3) |
| 8.0 (API 26) | None | A1 | Not tested |
| 10 | None | A4 | Not tested |
| 11 | None | A2, A9 | Not tested |
| 13 | None | A5, A6, A7 | Not tested |
| 14 and later | None (ChromeOS) | A8 | Not tested |

No physical device was used (header).

### 2. Flavors

The flavors are `play` and `fdroid` in one `distribution` dimension (`AndroidApplicationConventionPlugin.kt`
lines 38-58). `fdroid` adds the suffix `.fdroid` to the application id and `-fdroid` to the version name.
The debug build type adds `.debug` (`dev.opencode.android.debug`, M3).

| Build | Artifact on disk | Run | Status |
| --- | --- | --- | --- |
| play debug | `app/build/outputs/apk/play/debug/app-play-debug.apk` | Installed and launched on the API 36 emulator, Add server opened (A3). Installed beside the fdroid debug build, then removed (M3). No server was connected (A3). | Tested in part (A3, M3) |
| play release | `app/build/outputs/apk/play/release/app-play-release-unsigned.apk`, unsigned | Not run. R8 output was never executed (see ProGuard) | Built but not run |
| fdroid debug | `app/build/outputs/apk/fdroid/debug/app-fdroid-debug.apk` | The build the header says ran throughout, on the API 36 emulator | Tested (header; most rows) |
| fdroid release | None found | Signing material is not in the tree (status table; M2) | Not tested |

| Flavor | QR decoder | Status |
| --- | --- | --- |
| play | ML Kit barcode scanning 17.3.0 (`libs.versions.toml` line 40; `app/build.gradle.kts` line 124) | Not tested: the emulator has no camera feed (B1) |
| fdroid | ZXing core 3.5.3 (`libs.versions.toml` line 34; `app/build.gradle.kts` line 125) | Not tested to decode (B1). The Scan tab's camera prompt was seen (B1). |
| fdroid, no Play Services | Static check of `app-fdroid-debug.apk`: no `com/google/mlkit` or `com/google/android/gms` strings, ZXing classes present (L5) | Built but not run: static check only |

### 3. Server versions

| Version | Source | What was run | Status |
| --- | --- | --- | --- |
| 2.0.18 | Vendored contract (`api/opencode-2.0.x/events.json` and `drift-allowlist.json`); `ServerValidator.kt` line 154 (`TESTED_VERSION`); `scripts/dev-server.sh` line 29 (pinned CLI) | The disposable dev server with the fake provider, from the emulator. The header records it as the server for every state-changing check. | Tested (header; most rows) |
| 2.0.20 | The maintainer's real `opencode serve` (header) | Read-only checks (header). Projects, sessions and a timeline read cold and offline (C1). Connected with the untested-version note (L3). The usage route was not asked (H4). No separate run of the matrix screens (L2). | Tested in part (header; C1, L3) |
| Any other version | None available | None (L1, L2) | Not tested |
| Major version other than 2, or not an OpenCode server | None | `ServerValidator.kt` line 170 rejects it in code | Not tested live |
| Server without an experimental route | None available | L4, H4 | Not tested |

A server newer than `TESTED_VERSION` is connected and labelled. The label is `VersionStatus.NEWER_UNTESTED`
(`ServerValidator.kt` line 42), shown with `status_version_untested`: "This server is newer than the version
this app was tested against. Most things work, but some screens may differ." (L3 saw it on 2.0.20.)

`ServerValidator.kt` line 38 says older 2.x releases are accepted, not tested. Only 2.0.18 and 2.0.20 were
run, so this document does not count the older versions as tested.

### 4. Routes that may be absent

`CapabilityPolicy.kt` marks a route `Absent` only on a `404` or `405` (line 165). A `500` or `409` proves
the route exists. An `Absent` route stays recorded for the life of the process, but not every screen acts on it (see the usage row). Experimental switches are off
by default (`ExperimentalPreferences.kt` line 74). All twelve values of `ExperimentalRoute` appear below.

| Feature | Route | What the app does when absent | Present path tested on | Absent path |
| --- | --- | --- | --- | --- |
| File write | `POST /api/experimental/fs/write` (`FS_WRITE`) | Editing is refused (`ReviewViewModel.kt` lines 120-122, 290). The config editor's gate reads the same route (`ConfigViewModels.kt` line 440). | 2.0.18 dev server: write confirmed (G6); `500` from a read-only file, route present (G8) | Not tested |
| Session export and import | `…/session/{id}/export`, `session/import` (`SESSION_EXPORT`, `SESSION_IMPORT`) | Export and import buttons are disabled (`HistoryPanel.kt` lines 149-155). Their state reads the export route only (`HistoryViewModel.kt` lines 322-326). An absent import is recorded (line 288) and nothing reads it. | No matrix row | Not tested |
| Persistent terminals | `…/persistent-pty/…` (`PERSISTENT_PTY`) | Status page says "This server does not offer the persistent-terminal routes" in place of host controls (`ServerStatusScreen.kt` lines 480-481) | No matrix row | Not tested |
| MCP runtime | `…/mcp/…` (`MCP_RUNTIME`) | Add, connect, disconnect and remove are disabled, with a reason line (`McpScreen.kt` lines 193-206, 250; `McpViewModel.kt` line 109) | 2.0.18 dev server: add, connect, disconnect, remove (G4) | Not tested |
| Well-known integration sources | `POST /api/experimental/integration/wellknown` (`WELLKNOWN_INTEGRATION`) | The source card is not shown (`ConnectScreen.kt` line 130; `ConnectViewModel.kt` line 201) | No matrix row | Not tested |
| Global shell setting | `PATCH /api/experimental/config` (`CONFIG_UPDATE`) | "Use this shell" is disabled with a reason (`ConfigScreen.kt` lines 249, 263; `ConfigViewModels.kt` lines 131-136) | Live server, not named: write confirmation reached and cancelled. The PATCH was not sent (G7). | Covered by a test only (G7) |
| Session instructions | `…/session/{id}/instructions/entries` (`SESSION_INSTRUCTIONS`) | Entries are not loaded (`AdminViewModels.kt` lines 668-673) | No matrix row | Not tested |
| Usage statistics | `GET /api/experimental/session/stats` (`SESSION_STATS`) | Manage → Usage is always listed (`HomeScreen.kt` line 337). `InsightsScreen` returns early with a plain message when `InsightsUiState.available` is false (`InsightsScreen.kt`, the `!state.available` branch), so the error card is not shown for an absent route. | 2.0.18 dev server, shown as "Dev": dashboard loads (H1, H3) | Compose test only (`InsightsScreenContentTest`, the absent-route case); no server without the route was run (H4 not run) |
| Session log | `GET /api/experimental/session/{id}/log` (`SESSION_LOG`) | "Event history" row is hidden (`SessionHost.kt` line 362; `SessionLogViewModel.kt` lines 57-62) | 2.0.18 dev server: replay ends at `log.synced` on two sessions; no entry rows seen (H8) | Not tested |
| Quick ask (generate text) | `POST /api/experimental/generate` (`GENERATE_AND_WAIT`) | Message "This server does not have the route that generates text." The field and send are disabled (`QuickAskScreen.kt` lines 40-41, 54, 71). The row stays listed (`HomeScreen.kt` line 342). | 2.0.18 dev server, `fake/text` (H5) | Not tested |
| Wait until idle | `POST /api/experimental/session/{id}/wait` (`GENERATE_AND_WAIT`) | Menu row is hidden (`SessionHost.kt` line 354; `SessionManagementViewModel.kt` lines 69-72) | 2.0.18 dev server, `fake/slow` (H10) | Not tested |
| Pair another device | `POST /api/pair` (`PAIR_DEVICE`) | Minted by the first press only, never on open. The QR carries `http(s)://host[:port]/auth/connect/<code>` for the address this phone uses, built by `PairingLink.linkFor`, which `PairingLink.parse` reads back. Not in the published spec (`OPENCODE_V2_FEATURES.md` §2.3). | Run in part: Manage › "Pair another device" (`PairDeviceScreen`) minted a code and drew its QR on the emulator; nothing scanned or redeemed it, and a second device could not have reached the loopback address in it (B9, pass in part). The entry also stays visible on a server that answers 404 (B10 fails). | Built but not run: the QR matrix and the link round trip are unit tested (`PairingQrTest`, `PairingLinkTest`); the 404 is recorded and tested in `InsightsWireTest`; the absent message is not tested; no second device has redeemed a code |
| Plugin list, check, update | `GET /api/plugin`, `POST /api/plugin/check`, `POST /api/plugin/update` | No experimental gate (`IntegrationSurface.kt` lines 119-124, 443-467). Listed as API, not Exp (`OPENCODE_V2_FEATURES.md` §18). | Plugin list shown, server not named (G5). Update not run (G5). | Not tested |
| Plugin RPC | `POST /api/rpc/{rpcID}/{method}` | No experimental gate. `callRpc` returns an ordinary failure (`InsightsSurface.kt` line 118). | 2.0.18 dev server: error card for unknown plugin `nope`; no successful answer (H6) | Not tested |
| `session.skill` (not in the enum) | `experimental.session.skill` | Not gated. The composer attaches the skill on the next prompt (`LiveComposerIntegrationTest.kt` line 255). | None in the matrix | Tested: `404` on a live 2.0.18 server (`ANDROID_APP_PLAN.md` line 861). Recorded in the plan, not the matrix. |

No absent route was seen on any server in the matrix: L4 is n/a, H4 is n/a and B10 is not built.

### 5. What this record does not claim

- No Android version other than 16 (API 36) was run. API 26, 10, 11, 13 and 14 or later are not tested.
- No physical device was used. Tablet, foldable and ChromeOS layouts are not tested (A4-A8, J1-J3).
- No play build has connected to a server. No play release was run, and no signed build exists (status table).
- No QR code was decoded by either decoder (B1).
- No server other than 2.0.18 and the 2.0.20 read-only server was used. No server older than 2.0.18 and none newer than 2.0.20 was run (L1, L2).
- The header describes the 2.0.20 server as used for read-only checks. Rows that name no server, such as G7, are not counted for it.
- No absent-route path was seen on a server, except the `session.skill` route recorded in the plan.
- HTTPS was checked only against a self-signed endpoint at `127.0.0.1:4443` (B5, B6), as the header also records, so this document claims HTTPS only for B5 and B6.
- Every combination of Android version, flavor and server version other than API 36, fdroid debug, and 2.0.18 (with the read-only 2.0.20 checks) is Not tested.

## Building a signed release

1. **Generate the keystore once.** It cannot be recovered if lost, so store it where the owner's
   secrets live and not in this repository.

   ```bash
   keytool -genkeypair -v \
     -keystore opencode-release.jks \
     -alias opencode \
     -keyalg RSA -keysize 4096 -validity 10000
   ```

   Validity beyond the Play requirement: the key signs every future update, and a key that expires
   means an app nobody can update.

2. **Put the four values in `keystore.properties`**, at the repository root. It is in `.gitignore`
   and must stay there.

   ```properties
   storeFile=/absolute/path/to/opencode-release.jks
   storePassword=…
   keyAlias=opencode
   keyPassword=…
   ```

   For CI, use the environment instead — the same names, so the code path is identical:

   ```
   OPENCODE_KEYSTORE, OPENCODE_STORE_PASSWORD, OPENCODE_KEY_ALIAS, OPENCODE_KEY_PASSWORD
   ```

3. **Compile-check only (optional).**

   ```bash
   ./gradlew clean assemblePlayRelease assembleFdroidRelease
   ```

   These assemble tasks may succeed without signing material and produce `-unsigned.apk` files. That
   is useful for checking compilation, but it is **not** release readiness and those outputs must not
   be archived as publishable artifacts. With the material present they produce signed APKs, but the
   assemble tasks themselves do not verify the signatures.

   ```
   app/build/outputs/apk/play/release/app-play-release.apk
   app/build/outputs/apk/fdroid/release/app-fdroid-release.apk
   ```

   Without signing material, the `releaseMaterialMissing` task prints what is needed and the output
   name includes `-unsigned.apk`. Never rely on the filename as the safety check.

4. **Build and verify the signed release candidate.**

   ```bash
   ./gradlew verifyPublishableRelease
   ```

   This task fails before assembling if the signing material is absent. With the four signing values
   configured, it assembles both flavors and runs `apksigner verify --verbose --print-certs` on each
   signed APK. It is a local verification gate only; it does not upload, publish, or replace the manual
   test matrix. For F-Droid distribution, follow the downstream recipe and F-Droid signing guidance
   below rather than uploading a Play-signed APK.

5. **Run the manual test matrix** ([`MANUAL_TEST_MATRIX.md`](./MANUAL_TEST_MATRIX.md)) on a signed
   build. Not on a debug build: the debug build has a different application id and different
   behaviour in a few places.

6. **Publish**, in this order.

   | Track | Who | Purpose |
   | --- | --- | --- |
   | Internal | The team | Confirms the artifact installs and the server talks to it. A handful of testers. |
   | Closed | A defined list | Confirms it against real hardware and real servers. Weeks, not days. |
   | Production | Everyone | Only after closed has run without a blocking defect. |

   A Play release is a *track* before it is a rollout. Internal is not "production with fewer
   people"; it is the stage where a packaging mistake is cheap.

7. **Cut the GitHub release** with both APKs, their mapping files, and the SHA-256 of each. The
   F-Droid flavor is the one to attach for anyone who wants no Google services at all.

## ProGuard

Minification and resource shrinking are on for release. Three things in this app need a keep rule
and the release is how you find out:

- **kotlinx.serialization.** Generated serializers are reached reflectively. `proguard-rules.pro`
  in `app/` has to keep them; a release build that decodes nothing at runtime is the classic symptom.
- **The event union.** `EventTypes` holds `KSerializer` instances built by hand, so the payloads
  need keeping by name.
- **Room.** Schema entities are constructed reflectively.

**Not verified here:** R8 ran and produced an APK of about 25 MB, but nothing in that APK was
executed, because no device or emulator ran a release build. **Run the manual test matrix on a
minified build, not only on a debug one.** A release-only crash from a stripped serializer is a
common and expensive surprise.

## F-Droid specifics

- The flavor has its own application id (`.fdroid`) and its own version suffix, so both builds can
  be installed side by side and a Play install is never mistaken for the F-Droid one.
- It uses ZXing for QR scanning rather than ML Kit, and ships no Google Play Services dependency.
  Verify on a device: `adb shell pm list packages | grep gms` should be empty.
- F-Droid builds are reproducible from the tag and the recipe, and are signed by F-Droid's own key,
  not by the release key. Do not upload a Play-signed artifact to F-Droid.


## What a release still needs

1. **Complete the manual test matrix and record the results.** It has been partly run on one API 36
   emulator; the remaining rows need the devices, servers and builds the matrix names. **This is the
   open exit criterion.**
2. Produce a signed artifact from real signing material.
3. Run the Play Console track progression: internal, then closed, then production.
4. Publish the GitHub release.
5. Build the one Phase 10 screen that is still missing: the **OpenCode theme import**. The project row
   on the usage dashboard is built and was seen re-querying (H2); the session list beside a session on
   a wide window is built and unit tested, and needs a tablet or resizable-window run (J1–J3); the LAN
   prober was run on the emulator (B7, B8) and still needs a real-network check.
6. Hear accessibility finding **A-1** on a device with TalkBack — the live region is built and unit
   tested (`TurnDividerLiveRegionTest`), and no screen reader has heard it (K2, K3) — before the closed
   track, since it is the difference between the app being usable and not with a screen reader.
7. Re-read the security and privacy reviews' *What a release still has to do* and record the
   decisions. Both name an owner decision that has not been taken: whether the keystore key should
   require user authentication, and whether the unencrypted message cache stays as it is.
