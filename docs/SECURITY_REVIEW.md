# Security review — Phase 10

A reading of the code as it stands, against plan §5.2 and §2.4. It is not a claim that the app is
secure; it is a list of what is enforced, what is not, and where a reader should look before
shipping.

Every finding names the file that produced it, so a disagreement is a diff rather than an opinion.

## Summary

| Area | Verdict |
| --- | --- |
| Credential storage | Enforced — Android Keystore AES-GCM, no plaintext fallback |
| Credentials in logs | Enforced — one `Log` call in the whole tree, and it is not a credential |
| Cleartext HTTP | Permitted and required, with a visible badge on every non-loopback `http://` server |
| User-installed CAs | Not trusted globally, by design; per-server opt-in |
| WebView | Hardened: local assets only, no file access, one bridged method |
| Dangerous-action confirmations | Present for the four actions the plan names |
| Analytics | None. No analytics or crash SDK is a dependency |
| Exported components | One, the launcher activity. The `FileProvider` is not exported |
| Backups | Disabled, with explicit extraction rules |
| Secrets in the repository | None. No keystore, no `local.properties`, no credential literal |

## 1. Credentials

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

## 2. Cleartext and trust

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

## 3. The WebView

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

## 4. Dangerous actions

Plan §5.2 names four: "allow always", auto-approve, deleting a session, and experimental file
writes. Each has a confirmation in front of it:

- **"allow always"** — `PermissionRequest.savedPatterns` exists specifically so the patterns an
  "allow always" would store are on the request row before the user commits to them. The dock shows
  them.
- **Auto-approve** — the settings switch is off by default and its own `ExperimentalRoute` id.
- **Deleting a session** — `SessionCommands.remove` is behind a confirmation in `SessionHost`.
- **Experimental file writes** — `WriteConfirmationDialog` in `feature/admin/…/ConfigEditorScreen.kt:147`,
  shown after the schema validation produced a `WritePlan` naming every key that will change.

Two more that the plan does not name but that Phase 10 added:
`InsightsSurface.createPermissionRequest` (it gates the agent exactly as a tool's request does) and
`experimental.session.wait` (a long request the user cannot stop). **Neither has a confirmation yet,
because neither has a screen.** The surface's comments say the screen must confirm before sending, and
that is a requirement on Phase 11 item 11.5, not something the code enforces today.

## 5. The manifest

One exported component: `MainActivity`, the launcher. The `FileProvider` carries
`android:exported="false"` and `grantUriPermissions="true"`, which is what a share sheet needs.

`android:allowBackup="false"`, `android:fullBackupContent="false"` and an explicit
`dataExtractionRules` file. A server token in the backup set would be a credential handed to anyone
with the device's cloud account, so the backup is off rather than filtered.

## 6. Logging

`grep -rn "Log\.[dvie]" --include="*.kt"` over the whole tree returns **one** call. The debug build
logs unknown union variants and the event inspector records raw frames, which is a developer feature
that is reachable only from an explicit screen; neither is on a release path.

This is the finding most likely to rot, because it depends on nobody adding a `Log.d` with a
`toString()` in it. `Secret` and the classified `ActionError` are what keep that safe today.

## 7. What a release still has to do

- Decide whether the keystore key should require user authentication. See §1.
- Confirm the release build has no `BuildConfig.DEBUG`-gated logging beyond the one call.
- Re-read §2 and §3 against whatever the release's minSdk turns out to be. The `FileProvider` and
  the extraction rules behave differently below API 31.

## Known limitations

- The reviews in this document are code reading. None of it was run: there is no device, so the
  WebView's actual behaviour, the Keystore's behaviour on a specific device, and the notification
  permission flows are all unexercised.
- **The LAN prober does not exist.** An earlier version of this document described one in
  `feature/insights`; there is no such code. It is Phase 11 item 11.8, and it is a network scanner, so
  this review has to be extended when it is written. The requirements to review it against are that it
  is opt-in, off by default, cancellable, and treats `401 {"_tag":"UnauthorizedError"}` as a
  fingerprint rather than something to authenticate against.
- The OpenCode theme import (11.13) reads files from the server and maps them onto the app's colours,
  and the widget, Quick Settings tile and app shortcuts (11.10) add new entry points and exported
  components. None exists, so none is reviewed here, and section 5's "one exported component" holds only
  until they do.
