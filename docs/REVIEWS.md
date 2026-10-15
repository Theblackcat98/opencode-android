# Reviews — security, privacy, accessibility

The Phase 10 reviews of the code as it stands: what is enforced, what is not, and where to look before
shipping. Each section names the file that produced its findings, so a disagreement is a diff rather than an
opinion.

These are code reading unless a passage says otherwise. Where device evidence exists, it is in
[`MANUAL_TEST_MATRIX.md`](./MANUAL_TEST_MATRIX.md) and this document's own limits say which parts still need
hardware.

## Security review

A reading of the code as it stands, against plan §5.2 and §2.4. It is not a claim that the app is
secure; it is a list of what is enforced, what is not, and where a reader should look before
shipping.

Every finding names the file that produced it, so a disagreement is a diff rather than an opinion.

### Summary

| Area | Verdict |
| --- | --- |
| Credential storage | Enforced — Android Keystore AES-GCM, no plaintext fallback |
| Credentials in logs | Enforced — one `Log` call in the whole tree, and it is not a credential |
| Cleartext HTTP | Permitted and required, with a visible badge on every non-loopback `http://` server |
| User-installed CAs | Not trusted globally, by design; per-server opt-in |
| WebView | Hardened: local assets only, no file access, one bridged method |
| Dangerous-action confirmations | Present for the four actions the plan names |
| Analytics | None. No analytics or crash SDK is a dependency |
| Exported components | Two: the launcher activity, and the Quick Settings tile service, which only the system can bind (`BIND_QUICK_SETTINGS_TILE`). The `FileProvider` is not exported |
| Backups | Disabled, with explicit extraction rules |
| Secrets in the repository | None. No keystore, no `local.properties`, no credential literal |
| LAN probe | Opt-in and off by default, not persisted. Probes only the phone's own private /24, with one unauthenticated `GET /api/info` per address, no redirects, and a cancellable scan (§7) |

### 1. Credentials

`core/data/…/security/SecureCredentialStore.kt` encrypts with an `AndroidKeyStore` AES-GCM key and
a `RandomizedIV`; the key never leaves the keystore and the ciphertext is what is stored.

**One thing worth stating plainly:** a key in the Android Keystore is protected by the device's
lock screen and, on most devices, by hardware. That is the strongest thing available to an app
without asking the user for a password, and it is the right one. It is not a guarantee: a rooted
device, or one with no lock screen at all, is outside it, and a keystore key generated with
`setUserAuthenticationRequired` would be stronger at the cost of the credential being unreadable
while the phone is locked. That is a product decision, not a defect, and it is not taken here.

`Secret` (`core/network`) never reveals its value in `toString`, so a credential cannot reach a log
through a data class's auto-generated `toString`. That is the single most common way a credential
leaks in a Kotlin app and it is closed.

### 2. Cleartext and trust

`app/src/main/res/xml/network_security_config.xml` permits cleartext in the base config, because a
network security config cannot enumerate arbitrary LAN addresses and the server this app talks to is
usually on plain HTTP. That is the plan's §2.4 finding, not a shortcut.

The consequences are handled where they can be:

- `feature/servers/…/ServersScreen.kt:217` and `ServerStatusScreen.kt:263` show an `UnencryptedBadge`
  on every non-loopback `http://` profile, with a description string a screen reader reads.
- User-installed certificate authorities are **not** in the base config's trust anchors, and the
  comment in the file says why: trusting them there would apply to every request the app makes,
  which is not what "optionally trust a private CA for one self-hosted server" means. `UserCaTrust`
  in `core/network` is the per-server path.

**Finding S-1 (accepted risk, not a defect).** On a hostile network, an `http://` server is
interceptable and the Basic credential goes with it. The plan accepts this for trusted LANs and
recommends SSH tunnels, Tailscale or a reverse proxy beyond them, and the badge tells the user
which they are on. The alternative — refusing cleartext — would make the app unable to reach the
servers it exists to reach.

### 3. The WebView

`feature/execution/…/TerminalWebView.kt` is the only WebView in the app, and it is the riskiest
surface, because it loads JavaScript.

What is off: `allowFileAccess = false`, `allowContentAccess = false`,
`allowFileAccessFromFileURLs = false`, `allowUniversalAccessFromFileURLs = false`. The page is
served from the app's own assets, so there is no `file://` and no `content://` for it to reach even
if the flags were on.

What is on: `javaScriptEnabled = true`. The plan asks for bundled xterm.js, which is a JavaScript
program, so this is unavoidable; the file says so.

The bridge is the part that matters. `addJavascriptInterface` exposes **every** public method of the
object to the page, so the surface has to be minimal by construction. `TerminalWebView.kt:55` marks
exactly one method `@JavascriptInterface`, and the comment at line 34 explains that the codec is
strict for that reason. A second bridged method would be a finding, and the count is one.

`@SuppressLint("JavascriptInterface")` appears once, at line 202, with a comment saying it is a
false positive being silenced deliberately.

### 4. Dangerous actions

Plan §5.2 names four: "allow always", auto-approve, deleting a session, and experimental file
writes. Each has a confirmation in front of it:

- **"allow always"** — `PermissionRequest.savedPatterns` exists specifically so the patterns an
  "allow always" would store are on the request row before the user commits to them. The dock shows
  them.
- **Auto-approve** — the settings switch is off by default and its own `ExperimentalRoute` id.
- **Deleting a session** — `SessionCommands.remove` is behind a confirmation in `SessionHost`.
- **Experimental file writes** — `WriteConfirmationDialog` in `feature/admin/…/ConfigEditorScreen.kt:147`,
  shown after the schema validation produced a `WritePlan` naming every key that will change.

Two more that the plan does not name but that this phase added, confirmed for the same reason:
`InsightsSurface.createPermissionRequest` (it gates the agent exactly as a tool's request does) and
`experimental.session.wait` (a long request the user cannot stop).

### 5. The manifest

Two exported components: `MainActivity`, the launcher, and `ConnectionTileService`, the Quick Settings tile. The tile has to be exported for the system to bind it, so it is guarded by `android.permission.BIND_QUICK_SETTINGS_TILE`. The `FileProvider` carries
`android:exported="false"` and `grantUriPermissions="true"`, which is what a share sheet needs.

The home-screen widget's receiver, `AttentionWidgetProvider`, is `android:exported="false"`. The system is the only sender of `APPWIDGET_UPDATE`, and a tap opens the launcher activity through its own pending intent, so the receiver is not an exported component and the count stays at two.

`android:allowBackup="false"`, `android:fullBackupContent="false"` and an explicit
`dataExtractionRules` file. A server token in the backup set would be a credential handed to anyone
with the device's cloud account, so the backup is off rather than filtered.

### 6. Logging

`grep -rn "Log\.[dvie]" --include="*.kt"` over the whole tree returns **one** call. The debug build
logs unknown union variants and the event inspector records raw frames, which is a developer feature
that is reachable only from an explicit screen; neither is on a release path.

This is the finding most likely to rot, because it depends on nobody adding a `Log.d` with a
`toString()` in it. `Secret` and the classified `ActionError` are what keep that safe today.

### 7. The LAN prober

The add-server form has an opt-in probe for OpenCode servers on the phone's local network. The scanner
is `LanProber` in `core/network/…/LanProber.kt`, its switch and results are `LanDiscoveryViewModel` and
`LanDiscoverySection` in `feature/servers`, and the tests are `LanProberLogicTest`, `LanProberWireTest` and
`LanDiscoveryViewModelTest`. The parts that matter for security:

- **Off by default, and not persisted.** The switch is view-model state, so a process restart starts it
  off again. Nothing turns it on by itself, and switching it on starts no scan.
- **Only the phone's own /24.** `LanProber.hostsOf` takes the phone's address from the active network's
  `LinkProperties` and returns the 253 host addresses of the /24 around it, with the phone itself and the
  network and broadcast addresses left out. A prefix other than 24 is refused, so the scan never widens to
  a /16 sweep.
- **Private IPv4 only.** Only the three RFC 1918 ranges (10/8, 172.16/12, 192.168/16) are scanned. A
  public, loopback, link-local, CGNAT or IPv6 address is refused, and then nothing is scanned.
- **One unauthenticated request per address.** `GET /api/info` on 49374, 4096 and any valid port typed.
  The client is derived from the shared `OkHttpClient`, which has no interceptor, so no `Authorization`
  header is sent. `LanProberWireTest` checks that on the wire.
- **No redirects.** `followRedirects(false)` and `followSslRedirects(false)`. A redirect answer is a
  non-match and its target is never requested.
- **Plain HTTP only, no TLS.** The probe sends no credential and does no handshake, so an HTTPS-only
  server on a probed port is not found. The user can still type its address.
- **A narrow fingerprint, which is a hint.** A `401` whose body is `{"_tag":"UnauthorizedError",…}`, or a
  `200` whose body decodes as `ServerInfo`. A `401` that only mentions the word, and every other status,
  is not a match. A match is a candidate: `ServerValidator` still runs when the user connects, and nothing
  is authenticated against it during the probe.
- **Bounded and silent.** At most 64 KiB of a response is read. The prober contains no `Log` call, so no
  address it finds or response body reaches the log, which keeps the one-`Log`-call finding in §6 true.
- **Cancellable.** Stop cancels the collecting coroutine, and cancelling it cancels each OkHttp call in
  flight (`LanProberWireTest` checks that the scan ends within a second and that the OkHttp call ends as
  cancelled). Switching the probe
  off, or leaving the screen, does the same. Nothing is reported after a stop.
- **Found addresses are shown, not saved.** Tapping one puts `http://host:port` in the Server URL field.
  It is saved only if the user goes on to connect.

**Limits.** With a VPN as the active network, the probe covers the VPN's /24, not the Wi-Fi one. The
800 ms timeout can miss a slow server on a busy network. The probe is visible to the other devices on the
network as a short burst of connections. The probe has since been run on the API 36 emulator, where it
found the host's servers and stopped when cancelled (matrix B7 and B8, both pass); it has not been run on
real hardware or a real Wi-Fi network, so what it reveals there is not measured.

### 8. What a release still has to do

- Decide whether the keystore key should require user authentication. See §1.
- Confirm the release build has no `BuildConfig.DEBUG`-gated logging beyond the one call.
- Re-read §2 and §3 against the release's `minSdk` (26, set in `gradle/libs.versions.toml`). The
  `FileProvider` and the extraction rules behave differently below API 31.

### Known limitations

- The security review is code reading. The manual matrix has since run the connect/refuse pair against a
  self-signed HTTPS endpoint (B5, B6) and the LAN probe (B7, B8) on an API 36 emulator, but the WebView's
  actual behaviour, the Keystore's behaviour on a specific device, and the notification permission flows
  remain unexercised on hardware.
- The LAN prober is a network scanner, and §7 describes it: opt-in, off by default, not persisted, the
  phone's own private /24 only, cancellable. It treats `401 {"_tag":"UnauthorizedError"}` as a fingerprint
  rather than something to authenticate against. It is built and unit tested, and was run on the emulator
  for B7 and B8; it has not been run on a real network.

## Privacy review

Plan §5.2 asks for no analytics by default and opt-in crash reports that never include prompt or
file content. This is what the code does, and what the code cannot promise.

### What leaves the device

**Nothing, unless the user connects to a server.**

The app is a client. Every request it makes goes to a server the user added by hand, over an address
the user typed or scanned. There is no third-party endpoint anywhere in the dependency set, and no
analytics or crash SDK among them.

The one exception the app itself makes is the **user-installed CA fetch** in
`experimental.integration.wellknown`, where the *server* fetches a URL the user supplied. The phone
does not fetch it, and the route is behind its own `ExperimentalRoute` id with a comment saying why:
a user who will let the app manage MCP servers has not said they want the server fetching URLs.

### What is stored on the device

| Data | Where | Encrypted |
| --- | --- | --- |
| Server URLs and display names | Room (`core/database`) | No — a server address is not a secret and the UI lists it |
| Cached sessions and messages | Room | No — they are a cache of what the server already has |
| Passwords and 30-day tokens | `SecureCredentialStore` | **Yes** — Android Keystore AES-GCM |
| Drafts, favorites, prompt history | Room | No |
| The projects on each server's home, the last session on screen, the Settings project | DataStore (`project_preferences`), keyed by server id | No — project and session ids the server already knows; nothing leaves the device |
| Notification action payloads | `Intent` extras, transient | No — see below |

The credential row is the only one that matters, and it is the one that is encrypted.

**The message cache is the finding worth stating.** A phone with this app installed holds a copy of
every session the server has served, in a database that is not encrypted and is not protected by the
app's own access control. That is a deliberate trade — it is what makes the app open instantly and
read offline, and the plan asks for exactly that — but it means the app's privacy floor is the
device's, not the app's. An app-lock switch narrows it; without one, anyone with the unlocked phone
and a `adb` or a root can read it. See *What a release still has to do*.

### Notification action payloads

Plan §5.2 and the Phase 4 decision require a notification action to carry its whole target across to
the receiver, and the encoding is the same JSON the rest of the app uses rather than a hand-rolled
separator format. The consequence is that a permission answer, a form answer or a session id is
serialized into a `PendingIntent` extra.

That is on the lock screen. It carries **no prompt text and no file content**: the actions are
"allow this resource" and "answer this field with this value", and the resource patterns and field
answers are visible. A user who grants answers to a form from the lock screen is granting them to
whatever reads the notification.

The alternative — not supporting answers from the shade — trades a real feature (approving a
permission without unlocking, which is most of the value of the feature) for less exposure. The
choice made is the feature, and the exposure is bounded by the fact that the payload names a
resource rather than quoting anything.

### The home-screen widget

The widget (matrix J8) draws two counts, sessions running and requests waiting, under fixed labels. It carries no
session title, no prompt text, no file content and no request detail, so the most a launcher can show of it is those
two numbers. Where a launcher draws widgets on a locked screen, the numbers are visible there. The counts are withheld
while the active server's stream is down, and the widget then asks the user to open the app. A count left on screen by
a process that has ended stays until the widget's next scheduled update, which was not produced; a force-stop resets the
widget to its blank layout instead (matrix J8).

### Analytics and crash reports

Neither exists. There is no analytics SDK, no crash SDK, and no opt-in switch for either, because
there is nothing to opt in to. The nearest thing is the developer **event inspector**
(`feature/servers/…/EventInspectorScreen.kt`), which records raw event frames in memory so a user
can debug a client. It is:

- on an explicit screen the user navigates to, not on by default;
- in memory only — no file is written, and the list is cleared when it is;
- cleared with `clear()` and pausable.

It does show prompt and file content, because it is showing the user their own session, which is
the point. It is a debug affordance on a debug screen, not telemetry.

### Tracking and identifiers

There is nothing to opt out of, because the app collects no identifier:

- no advertising ID, no device ID, no install ID;
- no account, no sign-in beyond pairing with a server the user chose;
- no `AdvertisingIdClient`, no `FirebaseAnalytics`, no `Crashlytics`, no Sentry.

The only value that identifies anything is the server id, and it is generated locally by the app for
a server the user added. It is not sent anywhere except to that server.

### Network behaviour

- HTTP Basic with the username `opencode` and either the server password or a 30-day token, over an
  address the user configured.
- TLS when the server is `https://`, with the platform trust store plus, per server, the user's own
  CAs.
- Cleartext when the server is `http://`, with a visible badge and a reason in §2.4 of the plan.
- No third-party network access of any kind. The WebView loads bundled assets only.
- The opt-in LAN probe, off by default, sends one unauthenticated request to each address on the
  phone's own local /24. It is described in the next section.

### The LAN probe

The add-server form can look for OpenCode servers on the local network. It is opt-in, and the security
side is in [the security review](#security-review) §7.

- **What leaves the device.** One `GET /api/info` per address in the phone's own /24, on ports 49374 and
  4096 and any the user types. Every request goes to the local network and to nothing else. It carries
  no credential and no device or account identifier: the headers are an `Accept: application/json` and the
  defaults OkHttp adds, its User-Agent and Accept-Encoding. Nothing goes to the internet, and no third-party host is contacted.
- **What the device keeps.** Nothing. The switch, the typed extra ports and the addresses found live in
  a view model, not in storage. A restart clears them and leaves the switch off. A found address is kept
  only if the user goes on to connect to it, and then as the server profile they already chose to save.
- **What other devices see.** The probe is a short burst of connections from this phone to every address
  on its /24. Other devices on that network can see it, and a network that logs connections will show it.
  That is the cost of the feature, and it is why the feature is off by default.

**Limits.** The probe has run on the API 36 emulator (matrix B7 and B8, both pass) but not on real
hardware; it was not run on a real Wi-Fi network with servers on it, so what it reveals on such a network
is not measured.

### What a release still has to do

- **Decide on the message cache.** It is the largest piece of user content on the device and it is
  unencrypted. Three options, and the choice is the owner's: leave it (offline read, weak floor),
  encrypt it under the same Keystore key as the credentials (slower, and a lost key loses the cache),
  or clear it on lock (loses the offline read the plan asks for).
- **Ship the app-lock switch** or state plainly that there is none. The plan offers it as optional;
  the privacy floor is materially different with and without it.
- If crash reporting is ever added, the review has to be redone. The requirement is opt-in and must
  exclude prompt and file content, and the simplest way to guarantee that is to send a stack trace
  and a build id and nothing else.

### Known limitations

- This is code reading. No traffic was captured, so "nothing leaves the device" means "nothing in the
  source does". The manual matrix ran the app on an API 36 emulator, including the LAN probe (B7, B8); a
  proxy capture on a device would be the real check and cannot be done here.
- The F-Droid flavor and the Play flavor share this code, so the analysis holds for both. The Play
  flavor additionally ships ML Kit for QR scanning, which is bundled and makes no network calls.

## Accessibility audit

Plan §5.4 asks for TalkBack semantics, dynamic type and contrast checks. This is the audit, against
the code as it stands, with the checklist exercised mechanically where it can be and written down
where it cannot.

**Source checks, and no screen reader heard.** Everything here is either a check over the sources or a
reading of them, plus the accessibility-tree read on the API 36 emulator recorded in the manual matrix
(K1). No screen reader was heard, so the items marked *not verified here* still need a real device with
TalkBack, and are listed with what to do so the next person does not have to rediscover that the
difference matters.

### The mechanically checked part

`tools/audit-accessibility.mjs` applies four rules. The first three fire; the fourth is documented
below as a deliberate non-rule.

| Rule | Result |
| --- | --- |
| An `IconButton` with no label anywhere in its call | **0** |
| A text size applied where a named style should be | **0** |
| A merging container that double-reads its subtree | not a rule — see below |
| A `Text` or `contentDescription` holding a literal | **0**, by `tools/audit-strings.mjs` |

The rules were each verified by breaking a real control: removing the `contentDescription` from the
close button in `feature/composer/…/FullScreenEditor.kt` makes the audit report it, and restoring it
makes the audit clean. A checker that cannot fail is not a checker.

**The merging rule is deliberately absent.** Six places use
`Modifier.semantics(mergeDescendants = true) { contentDescription = … }` — for example
`feature/composer/…/ComposerParts.kt:187`, which describes an attachment as
"label. kind. range. size". That is the correct pattern: the description is what a screen reader
reads *instead of* walking each child. A checker that flagged it would push the codebase towards the
worse version, where the whole subtree is read twice. Noted here because "we checked" should say
what was checked.

### The checklist, item by item

#### 1. Every control has a name — **verified**

43 files carry `contentDescription`. The rule above covers the failure mode that matters: an
icon-only control announced as "button". Text buttons are named by their own text, which is why the
rule exempts them.

The Compose shape used throughout is the button unlabelled and the `Icon` inside it labelled:

```kotlin
IconButton(onClick = onNavigateBack) {
    Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_back))
}
```

That is one accessible control, and the audit's window is the whole call so it can see it.

#### 2. Dynamic type — **partly verified**

Every module has a screenshot test that captures at 1.5× font scale, and those baselines are
verified with `verifyRoborazziDebug` (149 images, light and dark). Clipping, overlap and truncation
at 1.5× are therefore caught in CI for the screens that have a capture.

**Partly checked since:** at 2.0× on the API 36 emulator, the Servers list, the Dev home, a session and
the New session sheet all stayed readable with wrapped text and no truncated controls, although the
session header took about 40% of the screen and, with a completion popup open, the timeline was left
about a quarter (matrix K4, not a pass). The interaction with `fontScale` on a real device, where the
system also scales the display, is still not verified: a screen that survives 1.5× in a screenshot can
still break at 2.0× in a real layout, because a screenshot does not reflow a scrolling list.

#### 3. Contrast — **partly verified**

The fallback colour pairs, including the code palette, are computed by `tools/audit-contrast.mjs`.
The results, and what they do not cover, are in [Computed contrast ratios](#computed-contrast-ratios)
at the end of this document. Two parts need more than a source read:

- dynamic colour, which has no static value and is on by default on Android 12 and later;
- the OpenCode theme import, which the plan describes but the sources do not contain, so there is
  nothing to compute yet.

**What a human must do:** check the dynamic colour schemes on a device. The source cannot give them a
value, and nothing in the repository measures them.

#### 4. TalkBack flow — **not verified here**

No screen reader was run. What the code provides and what still needs a device:

| Provided | Where |
| --- | --- |
| Section headings, so a screen reader can jump | `heading()` in the feature screens |
| State as a state, not as a changed label | `stateDescription` on toggles |
| Merged summaries for composite rows | six places, listed above |
| Traversal order follows visual order | No `traversalIndex` is set anywhere, which is the default and is correct |
| Live regions for the composer and the timeline | present at the turn boundary — see below |

**Finding A-1 — fixed in code; the device check remains.** The timeline and the composer are live
updates: text streams in, an agent starts and stops, a permission appears. A screen-reader user gets no
announcement when a turn finishes or a request arrives unless the status is a live region. The fix is a
polite live region on the turn boundary (`session.execution.succeeded`, `permission.asked`) and not on
the stream, because a `text.delta` storm announced per frame would be unusable. A turn that finished
normally no longer draws a "Turn finished" divider; the newest turn end keeps its polite live region on
an invisible node carrying the outcome as its description, so the announcement survives the divider
leaving the screen, and a failed or interrupted turn still draws a caption that carries the region
itself. `TurnDividerLiveRegionTest` covers the three cases. Tool rows are single `toggleable` nodes that
announce their expanded state, and the user's bubble is read as "You: …" because a screen reader cannot
see which side it is on. What remains is to hear it on a device with TalkBack (matrix K2 and K3).

**Finding A-2.** The keyboard-shortcut layer added in this phase (Ctrl+P for the command palette,
leader-key combinations) is a *visible* feature and has no non-keyboard equivalent yet: a
switch-access or switch-scan user cannot reach it. The commands themselves are reachable through
their screens, so nothing is unreachable — but the shortcuts should not be described in the UI as
the primary way to do something until there is a second route.

#### 5. The WebView terminal — **a special case, and the hardest one to make accessible**

`feature/execution/…/TerminalWebView.kt` renders xterm.js. A terminal is a grid of characters with
no semantics at all: every glyph is a `Text` in the DOM as far as a screen reader is concerned, and
navigation is meaningless.

**This is a known and accepted limitation, not an oversight.** The alternatives are worse: a
screen-reader-accessible terminal emulator is not a thing that exists, and reimplementing VT
semantics in Compose would be a second terminal that is worse at the actual job. What the app can do
is offer the transcript — the session's own message list already carries every tool's output, is
built from semantics, and is navigable. The terminal is the power feature and the transcript is the
accessible one, and the UI should say so rather than leaving a user to discover it.

#### 6. Motion and animation

No `AnimatedVisibility` is used with a duration long enough to be a problem, and the app respects
the system's animation scale through Compose's defaults. **Not verified here** — there is no reduced
motion test.

#### 7. Focus order and dismissal

`BackHandler` is wired on the sheets and the full-screen editor. Dialogs use Material 3 `Dialog`,
which handles focus trapping and the back gesture. **Not verified here** on a device with a keyboard
or a switch.

### Summary

| Item | State |
| --- | --- |
| Control names | Verified mechanically, 0 findings |
| Literal strings in semantics | Verified mechanically, 0 findings |
| Dynamic type at 1.5× | Verified by screenshot baselines in CI |
| Dynamic type at 2.0× | Partly checked on the emulator (matrix K4); the rest needs a device |
| Contrast | Fallback pairs computed (see the end); dynamic colour not computed |
| TalkBack flow | A-1 fixed in code and unit tested, not yet heard on a device; A-2 open; no screen reader run |
| Terminal accessibility | Accepted limitation, with an accessible alternative in the transcript |
| Reduced motion | Not verified |

### What a release still has to do

1. Hear **A-1** on a device with TalkBack: the live region is built and unit tested
   (`TurnDividerLiveRegionTest`), and no screen reader has heard it yet (matrix K2 and K3).
2. Check the dynamic colour schemes on a device, which `tools/audit-contrast.mjs` cannot compute. The
   fallback pairs and the code palette are already computed (see the end of this document).
3. Capture the 1.5× baselines' siblings at 2.0× for the timeline and the composer specifically, which
   are the two that scroll.
4. Run the app on a device with TalkBack and the largest font size, and record what breaks. That is
   the only way the "not verified" rows above become verified.

### Computed contrast ratios

Computed on 2026-10-10 by `node tools/audit-contrast.mjs`. Its `tools/audit-contrast.test.mjs` passes
12 tests, including black on white at 21:1, a colour against itself at 1:1, and `#767676` on white at
4.54:1, which is worked out by hand in the test. The script reads the colours from the Kotlin sources
and keeps no copy of its own. It computes WCAG 2.x relative luminance and the ratio of each pair, with
translucent colours composited in sRGB first. It reports and exits 0, like the other audits. It does
not change any colour.

**Dynamic colour is not covered.** `OpenCodeTheme` uses Material You on Android 12 and later (API 31)
when `dynamicColor` is true, and `MainActivity` calls it with the default, so most devices get a
wallpaper-derived scheme. The source has no value for that scheme. Every figure below is for the
static fallback schemes, `OpenCodeLightColors` and `OpenCodeDarkColors`, which is what Android 11 and
earlier see. The diff palette and the code blocks take their colours from whichever scheme is active,
so on a dynamic device they are not these figures.

#### What was measured

Each scheme has 72 pairs, each a text or component colour against the background it sits on.

- **Scheme roles.** The core pairs (`onSurface`, `onSurfaceVariant`, `onBackground`, `onPrimary`,
  `onSecondary`, `onError`, and the `on…Container` pair for primary, secondary and error), plus
  `onTertiaryContainer`, `onSurfaceVariant` on `surfaceVariant`, the Snackbar pair (`EventInspectorScreen`
  uses one), and the
  accent roles the screens use for text or status marks on `surface` (`primary`, `secondary`,
  `tertiary`, `error`, and `outline` for the disconnected server dot).
- **Diff palette.** For each row kind (context, selected, added, removed, hunk header): the row's text,
  line number, marker, and each token colour (keyword, string, number, comment, annotation,
  punctuation) against the row's background. The token colours are drawn on added and removed rows
  too, because `highlightedPlain` in `DiffTable.kt` applies them whatever the row kind. They apply only
  to a file with a highlighted language.
- **Code blocks and links.** The Markdown code block's label and tokens on `surfaceVariant`, and the
  link colour `#1A73E8` on `surface`.
- **File viewer selection.** A selected line's line number on `secondaryContainer`, and its text (see
  the assumptions below).

#### Results

Light scheme: 26 of 72 pairs are below 4.5:1, and 4 are below 3:1. Dark scheme: 20 of 72 are below
4.5:1, and 2 are below 3:1.

A pair is listed if it is below 4.5:1 in either scheme, and both schemes' ratios are shown. `*` is
below 4.5:1 and `**` is below 3:1. Ratios are to two decimals.

| Pair | Light | Dark |
| --- | --- | --- |
| onSecondary / secondary | 4.11 * | 4.69 |
| secondary / surface | 3.94 * | 7.10 |
| outline / surface | 3.31 * | 5.01 |
| diff gutter on context row | 3.04 * | 4.14 * |
| diff number on context row | 3.94 * | 7.10 |
| diff comment on context row | 3.85 * | 5.28 |
| diff gutter on selected row | 2.77 ** | 3.67 * |
| diff string on selected row | 3.99 * | 4.48 * |
| diff number on selected row | 3.13 * | 5.34 |
| diff comment on selected row | 3.41 * | 4.46 * |
| diff gutter on added row | 2.87 ** | 2.85 ** |
| diff string on added row | 4.36 * | 3.05 * |
| diff number on added row | 3.42 * | 3.64 * |
| diff comment on added row | 3.57 * | 3.33 * |
| diff annotation on added row | 5.38 | 3.18 * |
| diff gutter on removed row | 2.79 ** | 2.97 ** |
| diff string on removed row | 4.05 * | 3.43 * |
| diff number on removed row | 3.18 * | 4.09 * |
| diff comment on removed row | 3.44 * | 3.54 * |
| diff annotation on removed row | 5.00 | 3.57 * |
| diff gutter on hunk header | 2.88 ** | 3.65 * |
| diff string on hunk header | 4.39 * | 4.43 * |
| diff number on hunk header | 3.45 * | 5.29 |
| diff comment on hunk header | 3.59 * | 4.43 * |
| code string on code block | 4.39 * | 4.43 * |
| code number on code block | 3.45 * | 5.29 |
| code comment on code block | 3.59 * | 4.43 * |
| markdown link on surface | 4.32 * | 4.39 * |

- Of the scheme roles, the light scheme fails on `onSecondary`/`secondary` and `secondary`/`surface`
  as text. `outline`/`surface` is a non-text component, so only its 3:1 verdict applies, and it passes.
  The dark scheme passes every scheme role.
- Line numbers are text, so 4.5:1 applies to them. They are below 3:1 on the selected, added, removed
  and hunk-header rows in light, and on the added and removed rows in dark.
- In a Markdown code block, string and comment tokens fail 4.5:1 in both schemes, and number tokens
  fail in light. The link colour fails 4.5:1 in both schemes.
- Dark annotation tokens on added and removed rows fail 4.5:1 (3.18 and 3.57). Light annotation tokens
  on the same rows pass.

#### Not computed

- **Dynamic colour (Material You, API 31 and later).** See above.
- **Terminal.** `index.html` sets only the page background, `#101216`. The terminal's foreground and
  ANSI colours are xterm.js defaults, which are not in the app source.
- **Colours from the server or the user.** Agent colours (`Pickers.kt`), and project and worktree
  colours.
- **Camera-unavailable message.** `QrCodeScannerView` draws `Color.White` text over a `Color.Black` box.
  A camera view sits in the same box, and what it shows after a failed bind is not in the source, so
  this pair is not computed.
- **OpenCode theme import.** The plan describes it, and the sources do not contain it, so no sample
  theme was computed.
- **File viewer text.** `TextFileView` sets no colour on its text, so it takes the content colour of a
  host `Surface` that the file does not show. The script assumes `onSurface` for that host, so that
  pair is an assumption and not a reading.

#### Assumptions the figures depend on

- Context rows sit on `surface`, as `DiffFileCard` and the tool card's `Surface` set it. The split
  diff in `ReviewScreen` adds no `Surface` of its own, and its host was not traced. It is assumed to be
  `surface`.
- The link is measured on `surface`. The transcript's host was not traced.
- `background` and `surface` have the same values in both fallback schemes, so `onBackground` on
  `background` gives the same ratio as `onSurface` on `surface`.
- The light or dark diff variant is chosen by the luminance of `surface` against 0.5, as
  `DiffColors.of` does.

#### What this does not show

- These are ratios of the source colours. Nothing was rendered: no screenshot, device, font weight or
  font size was checked. A pair that passes 4.5:1 can still read poorly in a small, thin font.
- The script does not know the size of any text, so it reports both verdicts. Large text needs 3:1,
  meaning 18 pt, or 14 pt bold. The table does not say which applies to a given use.
- `TextFileView` in `FileBrowserScreen.kt` draws each line as plain `line.text`.
  `CodeHighlighter.highlightAll` runs for the file, and the file's doc comment says it is highlighted,
  but no token colour reaches the screen. The token pairs above therefore do not apply in the file
  viewer.
