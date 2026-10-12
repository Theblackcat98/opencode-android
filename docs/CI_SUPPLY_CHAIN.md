# CI supply-chain integrity

CI dependencies are immutable inputs, not floating labels. The build enforces both the GitHub Actions commit pins and the OpenCode CLI package checksums with `node tools/audit-supply-chain.mjs --check`.

## GitHub Actions

Every external `uses:` reference is pinned to a full commit SHA in `.github/workflows/ci.yml`. The corresponding reviewed release label and SHA are recorded in `config/ci-actions.lock.json`; the inline release comment must match that lock. `tools/audit-supply-chain.test.mjs` rejects mutable tags, an unreviewed SHA, a missing lock entry, or a stale version comment.

To update an Action:

1. Review the Action's official release and source in its own GitHub repository.
2. Resolve the selected release tag to a full commit SHA from that same repository; do not copy a SHA from a fork or a secondary list.
3. Update the workflow ref and its `# vX.Y.Z` annotation together with `config/ci-actions.lock.json`.
4. Run `node tools/audit-supply-chain.test.mjs` and `node tools/audit-supply-chain.mjs --check`, then validate the affected CI jobs.

GitHub's [security-hardening guidance](https://docs.github.com/en/actions/security-for-github-actions/security-guides/security-hardening-for-github-actions#using-third-party-actions) describes a full-length commit SHA as the immutable action reference.

## OpenCode CLI

The development-server harness uses the exact `@opencode/cli` version in `scripts/dev-server-cli/package.json`; `package-lock.json` pins the CLI and its platform packages with npm SHA-512 `integrity` values. `checksums.json` mirrors those registry SRI values so the offline audit can detect a lock/checksum mismatch. It also records SHA-256 digests for the installed Linux x64 binaries used by CI and their x64 fallbacks.

`scripts/install-opencode-cli.sh` installs with `npm ci --ignore-scripts` and runs `scripts/verify-opencode-cli.mjs` before returning the executable path. Disabling lifecycle scripts is intentional: the CLI package's postinstall can fall back to a nested, uncommitted `npm install`; the harness instead executes the platform binary that npm installed from the committed lock. npm validates each downloaded package archive against the lockfile's SRI, and the verifier additionally hashes the supported Linux x64 executable. Other locked platform packages still receive npm's archive-integrity verification.

To update the CLI:

1. Review the selected release and platform-package metadata from the official npm registry. Inspect the CLI package's install script before deciding whether lifecycle scripts may run.
2. Change the exact dependency version in `scripts/dev-server-cli/package.json` and regenerate the lock with `npm install --package-lock-only --ignore-scripts --no-audit --no-fund --prefix scripts/dev-server-cli`.
3. Review the package-lock changes. Update `checksums.json` with the reviewed SRI values for the CLI and every optional platform package. Install with `npm ci --ignore-scripts --no-audit --no-fund --prefix scripts/dev-server-cli`; update the Linux x64 binary SHA-256 values only after reviewing the release.
4. Run `node tools/audit-supply-chain.test.mjs`, `node tools/audit-supply-chain.mjs --check`, and `./scripts/install-opencode-cli.sh`.

The documented `OPENCODE_BIN` override is an explicit caller-supplied executable and bypasses the managed CLI installation and its checksum verifier. The default CI and dev-server paths do not set that override.
