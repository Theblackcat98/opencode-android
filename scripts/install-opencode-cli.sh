#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CLI_DIR="$ROOT/tools/opencode-cli"
STAMP="$CLI_DIR/node_modules/.opencode-lock"

log() { printf 'opencode-cli: %s\n' "$*" >&2; }
fail() { printf 'opencode-cli: error: %s\n' "$*" >&2; exit 1; }

command -v node >/dev/null || fail "node is required"
command -v npm >/dev/null || fail "npm is required"
node "$ROOT/tools/verify-opencode-cli.mjs" >&2 || fail "CLI integrity metadata did not pass verification"

lock_hash="$(sha256sum "$CLI_DIR/package.json" "$CLI_DIR/package-lock.json" "$CLI_DIR/checksums.json" | sha256sum | cut -d' ' -f1)"
needs_install=0
if [[ ! -f "$STAMP" ]] || [[ "$(cat "$STAMP")" != "$lock_hash" ]]; then
    needs_install=1
fi

if ((needs_install)); then
    log "installing the integrity-locked CLI with lifecycle scripts disabled"
    npm ci --ignore-scripts --no-audit --no-fund --prefix "$CLI_DIR" >&2
fi

case "$(uname -s):$(uname -m)" in
    Linux:x86_64|Linux:amd64)
        if [[ -f /etc/alpine-release ]] || ldd --version 2>&1 | grep -qi musl; then
            candidates=(cli-linux-x64-baseline-musl cli-linux-x64-musl)
        else
            candidates=(cli-linux-x64-baseline cli-linux-x64)
        fi
        ;;
    *)
        fail "the committed binary checksums support Linux x64; set OPENCODE_BIN for another platform"
        ;;
esac

bin=""
for package in "${candidates[@]}"; do
    candidate="$CLI_DIR/node_modules/@opencode/$package/bin/opencode"
    if [[ -x "$candidate" ]]; then
        bin="$candidate"
        break
    fi
done
[[ -n "$bin" ]] || fail "no locked Linux x64 OpenCode binary was installed"

node "$ROOT/tools/verify-opencode-cli.mjs" --binary "$bin" >&2 || fail "CLI binary checksum verification failed"
if [[ "$needs_install" == 1 ]]; then
    printf '%s' "$lock_hash" >"$STAMP"
fi
printf '%s\n' "$bin"
