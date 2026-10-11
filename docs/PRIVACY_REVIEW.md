# Privacy review — Phase 10

Plan §5.2 asks for no analytics by default and opt-in crash reports that never include prompt or
file content. This is what the code does, and what the code cannot promise.

## What leaves the device

**Nothing, unless the user connects to a server.**

The app is a client. Every request it makes goes to a server the user added by hand, over an address
the user typed or scanned. There is no third-party endpoint anywhere in the dependency set, and no
analytics or crash SDK among them.

The one exception the app itself makes is the **user-installed CA fetch** in
`experimental.integration.wellknown`, where the *server* fetches a URL the user supplied. The phone
does not fetch it, and the route is behind its own `ExperimentalRoute` id with a comment saying why:
a user who will let the app manage MCP servers has not said they want the server fetching URLs.

## What is stored on the device

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

## Notification action payloads

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

## The home-screen widget

The widget (matrix J8) draws two counts, sessions running and requests waiting, under fixed labels. It carries no
session title, no prompt text, no file content and no request detail, so the most a launcher can show of it is those
two numbers. Where a launcher draws widgets on a locked screen, the numbers are visible there. The counts are withheld
while the active server's stream is down, and the widget then asks the user to open the app. A count left on screen by
a process that has ended stays until the widget's next scheduled update, which is not yet seen on a device.

## Analytics and crash reports

Neither exists. There is no analytics SDK, no crash SDK, and no opt-in switch for either, because
there is nothing to opt in to. The nearest thing is the developer **event inspector**
(`feature/servers/…/EventInspectorScreen.kt`), which records raw event frames in memory so a user
can debug a client. It is:

- on an explicit screen the user navigates to, not on by default;
- in memory only — no file is written, and the list is cleared when it is;
- cleared with `clear()` and pausable.

It does show prompt and file content, because it is showing the user their own session, which is
the point. It is a debug affordance on a debug screen, not telemetry.

## Tracking and identifiers

There is nothing to opt out of, because the app collects no identifier:

- no advertising ID, no device ID, no install ID;
- no account, no sign-in beyond pairing with a server the user chose;
- no `AdvertisingIdClient`, no `FirebaseAnalytics`, no `Crashlytics`, no Sentry.

The only value that identifies anything is the server id, and it is generated locally by the app for
a server the user added. It is not sent anywhere except to that server.

## Network behaviour

- HTTP Basic with the username `opencode` and either the server password or a 30-day token, over an
  address the user configured.
- TLS when the server is `https://`, with the platform trust store plus, per server, the user's own
  CAs.
- Cleartext when the server is `http://`, with a visible badge and a reason in §2.4 of the plan.
- No third-party network access of any kind. The WebView loads bundled assets only.
- The opt-in LAN probe, off by default, sends one unauthenticated request to each address on the
  phone's own local /24. It is described in the next section.

## The LAN probe

The add-server form can look for OpenCode servers on the local network. It is opt-in, and the security
side is in [`SECURITY_REVIEW.md`](./SECURITY_REVIEW.md) §7.

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

**Limits.** This is code reading, and the probe has not been run on a device (matrix B7 and B8). It was
not checked on a network with a server on it, so what it reveals on a real network is not measured.

## What a release still has to do

- **Decide on the message cache.** It is the largest piece of user content on the device and it is
  unencrypted. Three options, and the choice is the owner's: leave it (offline read, weak floor),
  encrypt it under the same Keystore key as the credentials (slower, and a lost key loses the cache),
  or clear it on lock (loses the offline read the plan asks for).
- **Ship the app-lock switch** or state plainly that there is none. The plan offers it as optional;
  the privacy floor is materially different with and without it.
- If crash reporting is ever added, the review has to be redone. The requirement is opt-in and must
  exclude prompt and file content, and the simplest way to guarantee that is to send a stack trace
  and a build id and nothing else.

## Known limitations

- This is code reading. No traffic was captured and no emulator was run, so "nothing leaves the
  device" means "nothing in the source does". A proxy capture on a device would be the real check
  and cannot be done here.
- The F-Droid flavor and the Play flavor share this code, so the analysis holds for both. The Play
  flavor additionally ships ML Kit for QR scanning, which is bundled and makes no network calls.
