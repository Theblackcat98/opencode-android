#!/usr/bin/env bash
# e2e-ca-install.sh — places the e2e self-signed CA where the app's user-CA trust reads it.
#
# The app deliberately does not trust user CAs globally (network_security_config trusts
# system CAs only). A server opts in per-server, and then the connection validates against
# the platform store plus the user's CAs, which AndroidUserCertificateSource reads directly
# from /data/misc/keychain/cacerts-added — the on-disk form of what Settings installs.
# This script reproduces exactly that on-disk state on a rooted (userdebug) emulator.
#
# No reboot is needed: the app reads the directory with plain file I/O, not the keystore
# daemon, so the CA is visible to the next connection the app opens.
#
# Usage: CA_INSTALLED=$(./scripts/e2e-ca-install.sh)   # prints CA_INSTALLED=true|false
#   with E2E_HTTPS_CA_CERT pointing at the CA PEM (set by `eval "$(./scripts/e2e-https.sh start)"`).
# Never fails the caller: a false result makes the B5 test skip and the B6 test run.
set -uo pipefail

log() { echo "e2e-ca-install: $*" >&2; }
result() { echo "CA_INSTALLED=$1"; exit 0; }

CA_CERT="${E2E_HTTPS_CA_CERT:-}"
[[ -n "$CA_CERT" && -f "$CA_CERT" ]] || { log "E2E_HTTPS_CA_CERT is not set to an existing file"; result false; }
command -v adb >/dev/null 2>&1 || { log "adb not on PATH"; result false; }
command -v openssl >/dev/null 2>&1 || { log "openssl not on PATH"; result false; }

# Prefer the booted emulator when several devices are attached.
SERIAL="$(adb devices | awk '$1 ~ /^emulator-/ && $2 == "device" { print $1; exit }')"
ADB=(adb)
[[ -n "$SERIAL" ]] && ADB=(adb -s "$SERIAL")

"${ADB[@]}" wait-for-device 2>/dev/null || { log "no device came online"; result false; }
booted=false
for _ in $(seq 1 24); do
    if [[ "$("${ADB[@]}" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" == "1" ]]; then
        booted=true
        break
    fi
    sleep 5
done
$booted || { log "device never finished booting"; result false; }

"${ADB[@]}" root >/dev/null 2>&1 || { log "adb root refused (needs a userdebug image)"; result false; }
sleep 2

HASH="$(openssl x509 -in "$CA_CERT" -noout -hash 2>/dev/null)" || { log "could not hash CA cert"; result false; }
DEST="/data/misc/keychain/cacerts-added/${HASH}.0"

"${ADB[@]}" push "$CA_CERT" "$DEST" >/dev/null 2>&1 || { log "push to $DEST failed"; result false; }
"${ADB[@]}" shell "chmod 644 '$DEST'" >/dev/null 2>&1 || { log "chmod failed"; result false; }

if "${ADB[@]}" shell "test -s '$DEST'" >/dev/null 2>&1; then
    log "CA installed at $DEST"
    result true
else
    log "CA file missing after push"
    result false
fi
