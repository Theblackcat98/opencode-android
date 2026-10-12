#!/usr/bin/env bash
# Verify that the explicitly publishable release task fails closed when CI has no signing material.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
LOG="$(mktemp)"
trap 'rm -f "$LOG"' EXIT

if [[ -f "$ROOT/keystore.properties" ]]; then
  printf 'cannot test missing-key behavior while keystore.properties is present\n' >&2
  exit 1
fi

if env \
  -u OPENCODE_KEYSTORE \
  -u OPENCODE_STORE_PASSWORD \
  -u OPENCODE_KEY_ALIAS \
  -u OPENCODE_KEY_PASSWORD \
  "$ROOT/gradlew" :app:verifyPublishableRelease --no-daemon --console=plain >"$LOG" 2>&1; then
  printf 'verifyPublishableRelease unexpectedly succeeded without signing material\n' >&2
  cat "$LOG" >&2
  exit 1
fi

expected='verifyPublishableRelease requires complete release signing material.'
if ! grep -Fq -- "$expected" "$LOG"; then
  printf 'release verification failed for an unexpected reason; expected diagnostic: %s\n' "$expected" >&2
  cat "$LOG" >&2
  exit 1
fi

printf 'Publishable release gate correctly rejects missing signing material.\n'
