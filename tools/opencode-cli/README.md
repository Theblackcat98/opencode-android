# Pinned OpenCode CLI

`package.json` and `package-lock.json` pin the CLI and all platform packages. `npm ci` verifies each package tarball against its lockfile integrity. `checksums.json` additionally pins the direct CLI tarball and the supported Linux x64 binary variants.

The package's `postinstall` hook runs a second `npm install` in a temporary directory to select/copy a platform binary. That nested install is not governed by this lockfile, so installation uses `npm ci --ignore-scripts` and runs the locked platform binary directly. `scripts/install-opencode-cli.sh` verifies the lock, installs dependencies, chooses a Linux x64 variant, and checks its SHA-256 before returning the executable path.

## Updating the CLI

1. Choose a reviewed release and update the exact `@opencode/cli` version in `package.json`.
2. From the repository root, regenerate the lock with `npm install --package-lock-only --ignore-scripts --no-audit --no-fund --prefix tools/opencode-cli`.
3. Review every new/changed package version, tarball URL, and `integrity` value in `package-lock.json`. Obtain the direct package's `dist.integrity` from the registry over a trusted connection and update `npmIntegrity` in `checksums.json`.
4. Run `npm ci --ignore-scripts --no-audit --no-fund --prefix tools/opencode-cli`, calculate SHA-256 for the supported Linux x64 binary variants, and update their entries in `checksums.json` only after verifying the release source.
5. Run `node tools/verify-opencode-cli.mjs`, `./scripts/install-opencode-cli.sh`, and `./scripts/dev-server.sh start` plus the integration tests. Commit the manifest, lock, checksums, and this note together.

GitHub Actions references are also checked for full 40-character commit SHAs by `tools/verify-opencode-cli.mjs`.
