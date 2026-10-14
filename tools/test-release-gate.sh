#!/usr/bin/env bash
# Verify that the explicitly publishable release task fails closed when CI has no signing material.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
LOG="$(mktemp)"
KEYSTORE_DIR="$(mktemp -d)"
trap 'rm -f "$LOG"; rm -rf "$KEYSTORE_DIR"' EXIT

if [[ -f "$ROOT/keystore.properties" ]]; then
  printf 'cannot test missing-key behavior while keystore.properties is present\n' >&2
  exit 1
fi

if env \
  -u OPENCODE_KEYSTORE \
  -u OPENCODE_STORE_PASSWORD \
  -u OPENCODE_KEY_ALIAS \
  -u OPENCODE_KEY_PASSWORD \
  "$ROOT/gradlew" :app:verifyPublishableRelease --no-daemon --no-configuration-cache --console=plain >"$LOG" 2>&1; then
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

KEYSTORE="$KEYSTORE_DIR/test-release.jks"
KEY_PASSWORD='test-key-password'
STORE_PASSWORD='test-store-password'
keytool -genkeypair \
  -alias test-release \
  -keyalg RSA \
  -keysize 2048 \
  -validity 1 \
  -storetype JKS \
  -keystore "$KEYSTORE" \
  -storepass "$STORE_PASSWORD" \
  -keypass "$KEY_PASSWORD" \
  -dname 'CN=OpenCode CI Release Test' \
  -noprompt >/dev/null

export OPENCODE_KEYSTORE="$KEYSTORE"
export OPENCODE_STORE_PASSWORD="$STORE_PASSWORD"
export OPENCODE_KEY_ALIAS='test-release'
export OPENCODE_KEY_PASSWORD="$KEY_PASSWORD"

if ! "$ROOT/gradlew" :app:verifyPublishableRelease --no-daemon --no-configuration-cache --console=plain >>"$LOG" 2>&1; then
  printf 'verifyPublishableRelease unexpectedly failed with ephemeral signing material\n' >&2
  printf 'Keystore file: %s (exists: ' "$KEYSTORE" >&2
  [[ -f "$KEYSTORE" ]] && echo "yes)" >&2 || echo "no)" >&2
  cat "$LOG" >&2
  exit 1
fi

# Unset so they don't leak into later steps
unset OPENCODE_KEYSTORE OPENCODE_STORE_PASSWORD OPENCODE_KEY_ALIAS OPENCODE_KEY_PASSWORD

printf 'Publishable release gate verified signed APKs with an ephemeral test key.\n'
