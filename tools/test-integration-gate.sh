#!/usr/bin/env bash
# Verify that an explicitly requested integration task fails before running tests
# when its live-server configuration is missing.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
LOG="$(mktemp)"
trap 'rm -f "$LOG"' EXIT

if env \
  -u OPENCODE_URL \
  -u OPENCODE_PASSWORD \
  -u OPENCODE_DIRECTORY \
  -u OPENCODE_VERSION \
  -u FAKE_PROVIDER_URL \
  "$ROOT/gradlew" :core:testing:integrationTest --no-daemon --console=plain >"$LOG" 2>&1; then
  printf 'integrationTest unexpectedly succeeded without live-server configuration\n' >&2
  cat "$LOG" >&2
  exit 1
fi

for expected in \
  'Integration tests require live-server configuration.' \
  'OPENCODE_URL' \
  'OPENCODE_PASSWORD' \
  'OPENCODE_DIRECTORY'; do
  if ! grep -Fq -- "$expected" "$LOG"; then
    printf 'integrationTest failed for an unexpected reason; missing diagnostic: %s\n' "$expected" >&2
    cat "$LOG" >&2
    exit 1
  fi
done

printf 'Integration gate correctly rejects missing URL, password, and directory.\n'
