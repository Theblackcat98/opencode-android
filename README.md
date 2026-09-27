# OpenCode for Android

A native Android client for [OpenCode](https://opencode.ai) **V2**. It connects to an OpenCode server on your local
network, or any network your phone can reach, and drives the server's full feature set from your phone.

## Status

Planning. No app code yet.

## Documents

| Document | Contents |
| --- | --- |
| [`docs/OPENCODE_V2_FEATURES.md`](docs/OPENCODE_V2_FEATURES.md) | Every OpenCode V2 feature, and how each one can be read or driven through the server API: endpoints, events, auth and pairing, and the feature's limits |
| [`docs/ANDROID_APP_PLAN.md`](docs/ANDROID_APP_PLAN.md) | Architecture, tech stack, and a phased plan from P0 to P10. Includes coverage matrices that map every API operation and event type to a phase. |

## Connecting to your server (preview)

On the computer running OpenCode V2:

```bash
opencode service set hostname 0.0.0.0   # listen on the LAN (the default is localhost only)
opencode service start
opencode pair                           # prints a one-time link and a QR code to scan from the app
```
