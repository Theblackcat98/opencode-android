#!/usr/bin/env bash
# e2e-https.sh — self-signed HTTPS front door for the on-device E2E suite.
#
# `opencode serve` speaks plain HTTP; the B5/B6 matrix rows need the same server over HTTPS
# with a self-signed certificate. This script mints a throwaway CA plus a server certificate
# for 10.0.2.2 (the emulator's alias for the host loopback) and runs a Node TLS relay in
# front of the plain-HTTP backend from scripts/dev-server.sh.
#
#   ./scripts/e2e-https.sh start [listen-port] [backend-port]   # defaults: 4443, 4096
#   ./scripts/e2e-https.sh stop
#   ./scripts/e2e-https.sh status
#   ./scripts/e2e-https.sh env          # prints export lines for eval
#
# `start` prints `export` lines on success; eval them like the dev-server script:
#   eval "$(./scripts/e2e-https.sh start)"
# That sets E2E_HTTPS_URL (https://10.0.2.2:<port>) and E2E_HTTPS_CA_CERT (path to the CA
# PEM, for scripts/e2e-ca-install.sh). The material is throwaway test scaffolding: the CA
# key never leaves this machine and the certs live 30 days.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
STATE_DIR="$ROOT/.e2e-https"
PID_FILE="$STATE_DIR/relay.pid"
CA_PEM="$STATE_DIR/ca.pem"
CA_KEY="$STATE_DIR/ca.key"
SRV_CRT="$STATE_DIR/server.crt"
SRV_KEY="$STATE_DIR/server.key"

LISTEN_PORT="${2:-4443}"
BACKEND_PORT="${3:-4096}"

need() { command -v "$1" >/dev/null 2>&1 || { echo "e2e-https: missing required tool: $1" >&2; exit 1; }; }

mint_certs() {
    mkdir -p "$STATE_DIR"
    if [[ ! -f "$CA_PEM" ]]; then
        openssl req -x509 -newkey rsa:2048 -nodes \
            -keyout "$CA_KEY" -out "$CA_PEM" -days 30 \
            -subj "/CN=opencode-android e2e test CA" \
            -addext "basicConstraints=critical,CA:true" \
            -addext "keyUsage=critical,keyCertSign,cRLSign" 2>/dev/null
        chmod 600 "$CA_KEY"
    fi
    # Server cert for the emulator's host alias, backdated a day so clock skew can't
    # invalidate it, with the EKU Android requires for server certificates.
    local extfile="$STATE_DIR/server.ext"
    cat >"$extfile" <<'EOF'
basicConstraints=critical,CA:false
keyUsage=critical,digitalSignature,keyEncipherment
extendedKeyUsage=serverAuth
subjectAltName=IP:10.0.2.2,DNS:localhost,IP:127.0.0.1
EOF
    openssl req -newkey rsa:2048 -nodes -keyout "$SRV_KEY" -out "$STATE_DIR/server.csr" \
        -subj "/CN=10.0.2.2" 2>/dev/null
    openssl x509 -req -in "$STATE_DIR/server.csr" -CA "$CA_PEM" -CAkey "$CA_KEY" \
        -CAcreateserial -days 30 -out "$SRV_CRT" -extfile "$extfile" 2>/dev/null
    rm -f "$STATE_DIR/server.csr"
}

relay_running() { [[ -f "$PID_FILE" ]] && kill -0 "$(cat "$PID_FILE")" 2>/dev/null; }

cmd_start() {
    need node
    need openssl
    need curl
    if relay_running; then
        print_exports
        return 0
    fi
    mint_certs
    # The backend must already be up (scripts/dev-server.sh start).
    if ! curl -s -m 5 -o /dev/null "http://127.0.0.1:${BACKEND_PORT}/api/info"; then
        echo "e2e-https: backend http://127.0.0.1:${BACKEND_PORT} is not reachable." >&2
        echo "e2e-https: start it first: eval \"\$(./scripts/dev-server.sh start)\"" >&2
        exit 1
    fi
    node "$ROOT/scripts/e2e-https-relay.mjs" "$SRV_CRT" "$SRV_KEY" "$LISTEN_PORT" "$BACKEND_PORT" \
        >"$STATE_DIR/relay.log" 2>&1 &
    echo $! >"$PID_FILE"
    # End-to-end proof: TLS handshake plus a proxied request. The backend demands auth,
    # so 401 means the relay, the cert, and the backend all work.
    local code=""
    for _ in $(seq 1 30); do
        code="$(curl -sk -m 3 -o /dev/null -w "%{http_code}" "https://127.0.0.1:${LISTEN_PORT}/api/info" || true)"
        [[ "$code" == "401" ]] && break
        sleep 1
    done
    if [[ "$code" != "401" ]]; then
        echo "e2e-https: relay did not come up (last HTTP code: '${code:-none}')" >&2
        tail -5 "$STATE_DIR/relay.log" >&2 || true
        cmd_stop >/dev/null 2>&1 || true
        exit 1
    fi
    print_exports
}

print_exports() {
    printf 'export E2E_HTTPS_URL="https://10.0.2.2:%s"\n' "$LISTEN_PORT"
    printf 'export E2E_HTTPS_CA_CERT="%s"\n' "$CA_PEM"
    printf 'export E2E_HTTPS_PID="%s"\n' "$(cat "$PID_FILE" 2>/dev/null || echo unknown)"
}

cmd_stop() {
    if [[ -f "$PID_FILE" ]]; then
        kill "$(cat "$PID_FILE")" 2>/dev/null || true
        rm -f "$PID_FILE"
    fi
    pkill -f "e2e-https-relay.mjs" 2>/dev/null || true
    echo "e2e-https: stopped"
}

cmd_status() {
    if relay_running; then
        echo "e2e-https: relay running (pid $(cat "$PID_FILE"))"
    else
        echo "e2e-https: relay not running"
    fi
}

case "${1:-}" in
    start) cmd_start ;;
    stop) cmd_stop ;;
    status) cmd_status ;;
    env) print_exports ;;
    *) echo "usage: $0 {start [listen-port] [backend-port]|stop|status|env}" >&2; exit 2 ;;
esac
