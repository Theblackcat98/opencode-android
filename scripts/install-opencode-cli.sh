#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CLI_DIR="$ROOT/scripts/dev-server-cli"
STATE_DIR="$ROOT/.dev-server"
LOCK_STAMP="$STATE_DIR/opencode-cli-lock.sha256"

command -v node >/dev/null || { printf 'opencode-cli: node is required\n' >&2; exit 1; }
command -v npm >/dev/null || { printf 'opencode-cli: npm is required\n' >&2; exit 1; }
command -v sha256sum >/dev/null || { printf 'opencode-cli: sha256sum is required\n' >&2; exit 1; }

pinned_version="$(node -e 'process.stdout.write(require(process.argv[1]).dependencies["@opencode/cli"])' "$CLI_DIR/package.json")"
if [[ -n "${OPENCODE_CLI_VERSION:-}" && "$OPENCODE_CLI_VERSION" != "$pinned_version" ]]; then
    printf 'opencode-cli: OPENCODE_CLI_VERSION=%s conflicts with locked version %s\n' \
        "$OPENCODE_CLI_VERSION" "$pinned_version" >&2
    exit 1
fi

mkdir -p "$STATE_DIR"
lock_hash="$(cat "$CLI_DIR/package.json" "$CLI_DIR/package-lock.json" | sha256sum | cut -d' ' -f1)"
installed_hash=""
if [[ -f "$LOCK_STAMP" ]]; then
    installed_hash="$(tr -d '[:space:]' <"$LOCK_STAMP")"
fi
if [[ "$installed_hash" != "$lock_hash" || ! -d "$CLI_DIR/node_modules" ]]; then
    printf 'opencode-cli: installing locked @opencode/cli@%s with npm ci --ignore-scripts\n' \
        "$pinned_version" >&2
    npm ci --ignore-scripts --no-audit --no-fund --prefix "$CLI_DIR" >&2
    printf '%s\n' "$lock_hash" >"$LOCK_STAMP"
fi

# This prints only the verified executable path; npm output and diagnostics stay on stderr.
PRINT_OPENCODE_BIN=1 node "$ROOT/scripts/verify-opencode-cli.mjs" "$CLI_DIR"
