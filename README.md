# OpenCode for Android

A native Android client for [OpenCode](https://opencode.ai) **V2**. It connects to an OpenCode server on your local
network, or any network your phone can reach, and drives the server's full feature set from your phone.

## Status

Phase 1, *Connect and pair*, is complete. The app pairs with one or more OpenCode servers, keeps a live event
stream to each of them, and reconnects on its own when a server restarts or the network changes. Phase 2 adds the
live read-only view of sessions; the phase table is in
[`docs/ANDROID_APP_PLAN.md`](docs/ANDROID_APP_PLAN.md#6-phases).

What works today:

- Pair by scanning the `opencode pair` QR code, by pasting or sharing the link, or by typing an address and a
  password. A pairing token is stored encrypted in the Android Keystore.
- A server registry with several profiles, a default server, health dots, an edit screen, and a visible
  "unencrypted" badge on any `http://` server that is not on loopback.
- A live event stream per server, with a connection history, an idle watchdog, reconnect with backoff, and a
  resync signal on every `server.connected`.
- A server status page with the server's version and reachable URLs, a "test connection" check, and a pair-again
  prompt when a password or token is rotated on the computer.
- A developer event inspector with the raw JSON of every frame the app receives.
- Optional trust for CA certificates you installed on the phone, for a self-hosted HTTPS server.

Build variants: `play` uses ML Kit's bundled barcode model, `fdroid` uses ZXing and ships no Google Play
Services. Build both with `./gradlew assembleDebug`.

```bash
./gradlew unitTest          # JVM unit tests
./gradlew lintDebug         # Android Lint
./gradlew assembleDebug     # play and fdroid debug APKs
```

Integration tests need the real server:

```bash
eval "$(./scripts/dev-server.sh start)"
./gradlew integrationTest
```

## Documents

| Document | Contents |
| --- | --- |
| [`docs/OPENCODE_V2_FEATURES.md`](docs/OPENCODE_V2_FEATURES.md) | Every OpenCode V2 feature, and how each one can be read or driven through the server API: endpoints, events, auth and pairing, and the feature's limits |
| [`docs/ANDROID_APP_PLAN.md`](docs/ANDROID_APP_PLAN.md) | Architecture, tech stack, and a phased plan from P0 to P10. Includes coverage matrices that map every API operation and event type to a phase. |

## Connecting to your server

On the computer running OpenCode V2:

```bash
opencode service set hostname 0.0.0.0   # listen on the LAN (the default is localhost only)
opencode service start
opencode pair                           # prints a one-time link and a QR code to scan from the app
```

`opencode pair` works once and expires after five minutes. Scanning it stores a 30-day token on the phone. If you
would rather not open a port, forward one over SSH with `ssh -N -L 4096:localhost:4096 user@computer` and connect
to `http://localhost:4096`, or reach the computer over Tailscale. The app's onboarding screen carries the same
instructions.
