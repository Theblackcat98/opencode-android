# scripts/AGENTS.md

Shell harnesses for development and CI. Each file's header comment is the full documentation — this is just the index.

| Script | What it does |
|---|---|
| `dev-server.sh` | Starts/stops a pinned `opencode serve` with isolated HOME, generated password, and the scripted fake provider in `tools/fake-provider`. `start` prints `export` lines for `eval "$(…)"`; logs go to stderr. State lives in gitignored `.dev-server/`. Use this script for server lifecycle so process cleanup and state remain consistent. |
| `install-opencode-cli.sh` | Installs the exact CLI and platform packages from `tools/opencode-cli/package-lock.json` with lifecycle scripts disabled, verifies package and binary checksums, and prints the verified executable path. |
| `../tools/verify-opencode-cli.mjs` | Fails on missing npm lock integrities, CLI/package checksum mismatches, or GitHub Actions references that are not pinned to 40-character commit SHAs. |
| `setup-android-sdk.sh` | Installs the Android SDK command-line tools and required packages. Idempotent — safe to re-run; verifies the cmdline-tools archive by SHA-1 and writes `local.properties` if absent. |

Usage:

```bash
eval "$(./scripts/dev-server.sh start)"
./gradlew integrationTest
./scripts/dev-server.sh stop
```
