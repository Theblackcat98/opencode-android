# OpenCode for Android

A native Android client for [OpenCode](https://opencode.ai) **V2**. It connects to an OpenCode server on your
local network, or any network your phone can reach, and drives the server's full feature set from your phone.

The documentation lives in [`docs/`](docs/README.md). Start there:

- [`docs/README.md`](docs/README.md) — what the project wants, the honest current status, what the app does,
  how to build and test it, and how to connect.
- [`docs/ANDROID_APP_PLAN.md`](docs/ANDROID_APP_PLAN.md) — architecture and the phase plan.
- [`docs/RELEASE.md`](docs/RELEASE.md) — release status, compatibility and the signing runbook.
- [`docs/CHANGELOG.md`](docs/CHANGELOG.md) — what changed.

Build both flavors with `./gradlew assembleDebug`; run the unit tests with `./gradlew unitTest`.
