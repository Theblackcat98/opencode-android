#!/usr/bin/env bash
# Development server harness for the OpenCode Android app (Phase 0).
#
# Starts a pinned `opencode serve` with an isolated HOME, a generated password, and a
# generated opencode.jsonc that points at the scripted fake provider in
# tools/fake-provider. Prints `export` lines (URL + credentials) for the caller to eval,
# which is also the environment the Gradle integration tests consume
# (see build-logic/.../TestConventions.kt).
#
#   scripts/dev-server.sh start    # idempotent: reuses the running server when healthy
#   scripts/dev-server.sh stop     # tears down only what this script started
#   scripts/dev-server.sh status
#
# Environment overrides:
#   OPENCODE_CLI_VERSION   pinned CLI release (default 2.0.18)
#   OPENCODE_BIN           use this opencode binary instead of installing the pinned CLI
#   OPENCODE_DEV_PORT      server port (default 4096)
#   FAKE_PROVIDER_PORT     fake provider port (default 4097)
#
# Process safety: this script is the ONLY place that may signal these servers. It owns
# its children by construction -- the supervisor runs in its own session (setsid), every
# PID is recorded under .dev-server/, and before any signal the target's /proc cmdline
# is checked against the expected marker. Anything that is not provably ours is left
# alone and reported. Never signal servers by name, pattern, or port.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
STATE_DIR="$ROOT/.dev-server"
CLI_VERSION="${OPENCODE_CLI_VERSION:-2.0.18}"
SERVER_PORT="${OPENCODE_DEV_PORT:-4096}"
FAKE_PORT="${FAKE_PROVIDER_PORT:-4097}"
HOST="127.0.0.1"

SUPERVISOR_PID_FILE="$STATE_DIR/supervisor.pid"
SERVER_PID_FILE="$STATE_DIR/server.pid"
FAKE_PID_FILE="$STATE_DIR/fake-provider.pid"
ENV_FILE="$STATE_DIR/env"

log() {
    printf 'dev-server: %s\n' "$*" >&2
}

fail() {
    printf 'dev-server: error: %s\n' "$*" >&2
    exit 1
}

# True when /proc/<pid> exists and its cmdline contains the expected marker.
is_ours() {
    local pid="$1" marker="$2"
    [[ "$pid" =~ ^[0-9]+$ ]] || return 1
    [[ -r "/proc/$pid/cmdline" ]] || return 1
    tr '\0' ' ' <"/proc/$pid/cmdline" | grep -qF "$marker"
}

is_alive() {
    local pid="$1"
    [[ "$pid" =~ ^[0-9]+$ ]] && [[ -d "/proc/$pid" ]]
}

read_pid() {
    local file="$1"
    [[ -f "$file" ]] && tr -d '[:space:]' <"$file" || true
}

# True when something answers HTTP on host:port (any status counts).
port_occupied() {
    local port="$1"
    curl -s -m 2 -o /dev/null "http://$HOST:$port/" 2>/dev/null
}

load_env() {
    if [[ -f "$ENV_FILE" ]]; then
        # shellcheck disable=SC1090
        source "$ENV_FILE"
    fi
}

print_exports() {
    load_env
    printf "export OPENCODE_URL='%s'\n" "${OPENCODE_URL:-}"
    printf "export OPENCODE_PASSWORD='%s'\n" "${OPENCODE_PASSWORD:-}"
    printf "export OPENCODE_DIRECTORY='%s'\n" "${OPENCODE_DIRECTORY:-}"
    printf "export OPENCODE_VERSION='%s'\n" "${OPENCODE_VERSION:-}"
    printf "export FAKE_PROVIDER_URL='%s'\n" "${FAKE_PROVIDER_URL:-}"
}

server_healthy() {
    load_env
    [[ -n "${OPENCODE_URL:-}" && -n "${OPENCODE_PASSWORD:-}" ]] || return 1
    local info
    info="$(curl -s -m 5 -u "opencode:$OPENCODE_PASSWORD" "$OPENCODE_URL/api/info" 2>/dev/null)" || return 1
    printf '%s' "$info" | grep -q '"version":"'
}

resolve_bin() {
    if [[ -n "${OPENCODE_BIN:-}" ]]; then
        [[ -x "$OPENCODE_BIN" ]] || fail "OPENCODE_BIN is not executable: $OPENCODE_BIN"
        printf '%s\n' "$OPENCODE_BIN"
        return 0
    fi
    local cli_dir="$STATE_DIR/cli"
    local stamp="$cli_dir/VERSION"
    if [[ ! -x "$cli_dir/node_modules/.bin/opencode" ]] || \
        [[ ! -f "$stamp" ]] || [[ "$(tr -d '[:space:]' <"$stamp")" != "$CLI_VERSION" ]]; then
        log "installing @opencode/cli@$CLI_VERSION into $cli_dir (one-time download)"
        rm -rf "$cli_dir"
        mkdir -p "$cli_dir"
        npm install --no-audit --no-fund --prefix "$cli_dir" "@opencode/cli@$CLI_VERSION" >&2
        printf '%s' "$CLI_VERSION" >"$stamp"
    fi
    local bin
    bin="$(find "$cli_dir/node_modules/@opencode" -name opencode -type f -perm -111 2>/dev/null | head -n 1)"
    [[ -n "$bin" ]] || fail "no opencode binary found under $cli_dir/node_modules/@opencode"
    printf '%s\n' "$bin"
}

write_config() {
    local home="$1"
    mkdir -p "$home/.config/opencode" "$STATE_DIR/work"
    cat >"$home/.config/opencode/opencode.jsonc" <<EOF
{
  "\$schema": "https://opencode.ai/config.json",
  "theme": "opencode",
  "model": "fake/text",
  "small_model": "fake/text",
  "provider": {
    "fake": {
      "npm": "@ai-sdk/openai-compatible",
      "name": "Fake",
      "options": {
        "baseURL": "http://$HOST:$FAKE_PORT/v1",
        "apiKey": "fake-key"
      },
      "models": {
        "probe": { "name": "Fake probe", "tool_call": true, "reasoning": true, "attachment": true, "temperature": true, "limit": { "context": 200000, "output": 4096 }, "modalities": { "input": ["text"], "output": ["text"] }, "cost": { "input": 0, "output": 0 } },
        "text": { "name": "Fake text", "tool_call": true, "reasoning": true, "attachment": true, "temperature": true, "limit": { "context": 200000, "output": 4096 }, "modalities": { "input": ["text"], "output": ["text"] }, "cost": { "input": 0, "output": 0 } },
        "reasoning": { "name": "Fake reasoning", "tool_call": true, "reasoning": true, "attachment": true, "temperature": true, "limit": { "context": 200000, "output": 4096 }, "modalities": { "input": ["text"], "output": ["text"] }, "cost": { "input": 0, "output": 0 } },
        "shell": { "name": "Fake shell", "tool_call": true, "reasoning": true, "attachment": true, "temperature": true, "limit": { "context": 200000, "output": 4096 }, "modalities": { "input": ["text"], "output": ["text"] }, "cost": { "input": 0, "output": 0 } },
        "edit": { "name": "Fake edit", "tool_call": true, "reasoning": true, "attachment": true, "temperature": true, "limit": { "context": 200000, "output": 4096 }, "modalities": { "input": ["text"], "output": ["text"] }, "cost": { "input": 0, "output": 0 } },
        "question": { "name": "Fake question", "tool_call": true, "reasoning": true, "attachment": true, "temperature": true, "limit": { "context": 200000, "output": 4096 }, "modalities": { "input": ["text"], "output": ["text"] }, "cost": { "input": 0, "output": 0 } },
        "subagent": { "name": "Fake subagent", "tool_call": true, "reasoning": true, "attachment": true, "temperature": true, "limit": { "context": 200000, "output": 4096 }, "modalities": { "input": ["text"], "output": ["text"] }, "cost": { "input": 0, "output": 0 } },
        "error": { "name": "Fake error", "tool_call": true, "reasoning": true, "attachment": true, "temperature": true, "limit": { "context": 200000, "output": 4096 }, "modalities": { "input": ["text"], "output": ["text"] }, "cost": { "input": 0, "output": 0 } },
        "long": { "name": "Fake long", "tool_call": true, "reasoning": true, "attachment": true, "temperature": true, "limit": { "context": 200000, "output": 4096 }, "modalities": { "input": ["text"], "output": ["text"] }, "cost": { "input": 0, "output": 0 } }
      }
    }
  }
}
EOF
}

# Signal a recorded child only after proving it is ours. Usage: signal_pid <file> <marker> <signal>.
signal_pid() {
    local file="$1" marker="$2" signal="$3"
    local pid
    pid="$(read_pid "$file")"
    [[ -n "$pid" ]] || return 0
    if ! is_alive "$pid"; then
        return 0
    fi
    if ! is_ours "$pid" "$marker"; then
        log "PID $pid in $file is not '$marker'; leaving it alone"
        return 0
    fi
    kill "-$signal" "$pid" 2>/dev/null || true
}

wait_gone() {
    local pid="$1" seconds="$2"
    local i
    for ((i = 0; i < seconds; i++)); do
        is_alive "$pid" || return 0
        sleep 1
    done
    return 1
}

# The supervisor owns the two servers. It runs detached (setsid) so it outlives the
# `start` invocation, and its exit trap tears down exactly its own children.
# Configuration reaches it through supervise.env (chmod 600), never the cmdline.
cmd_supervise() {
    # shellcheck disable=SC1090
    source "$STATE_DIR/supervise.env"
    local fake_pid="" server_pid=""
    torn_down=""

    teardown() {
        [[ -n "$torn_down" ]] && return 0
        torn_down="1"
        if [[ -n "$server_pid" ]] && is_alive "$server_pid" && is_ours "$server_pid" "opencode"; then
            kill -TERM "$server_pid" 2>/dev/null || true
        fi
        if [[ -n "$fake_pid" ]] && is_alive "$fake_pid" && is_ours "$fake_pid" "fake-provider"; then
            kill -TERM "$fake_pid" 2>/dev/null || true
        fi
        if [[ -n "$server_pid" ]] && is_alive "$server_pid"; then
            sleep 3
        fi
        if [[ -n "$server_pid" ]] && is_alive "$server_pid" && is_ours "$server_pid" "opencode"; then
            kill -KILL "$server_pid" 2>/dev/null || true
        fi
        if [[ -n "$fake_pid" ]] && is_alive "$fake_pid" && is_ours "$fake_pid" "fake-provider"; then
            kill -KILL "$fake_pid" 2>/dev/null || true
        fi
    }
    trap teardown TERM INT EXIT

    FAKE_PROVIDER_QUIET=1 node "$ROOT/tools/fake-provider/server.mjs" \
        --host "$HOST" --port "$FAKE_PORT" >>"$STATE_DIR/fake-provider.log" 2>&1 &
    fake_pid="$!"
    printf '%s' "$fake_pid" >"$FAKE_PID_FILE"

    HOME="$home" OPENCODE_PASSWORD="$password" OPENCODE_DISABLE_AUTOUPDATE=1 \
        "$bin" serve --hostname "$HOST" --port "$SERVER_PORT" --print-logs \
        >>"$STATE_DIR/serve.log" 2>&1 &
    server_pid="$!"
    printf '%s' "$server_pid" >"$SERVER_PID_FILE"

    wait "$server_pid" || true
}

cmd_start() {
    mkdir -p "$STATE_DIR"
    command -v node >/dev/null || fail "node is required (fake provider)"
    command -v npm >/dev/null || fail "npm is required (pinned CLI install)"
    command -v curl >/dev/null || fail "curl is required (readiness probes)"

    local supervisor_pid
    supervisor_pid="$(read_pid "$SUPERVISOR_PID_FILE")"
    if [[ -n "$supervisor_pid" ]] && is_alive "$supervisor_pid" && is_ours "$supervisor_pid" "dev-server.sh"; then
        if server_healthy; then
            log "reusing healthy server (supervisor $supervisor_pid)"
            print_exports
            return 0
        fi
        log "supervisor $supervisor_pid is alive but the server is not healthy; restarting"
        cmd_stop
    elif [[ -n "$supervisor_pid" ]] && is_alive "$supervisor_pid"; then
        fail "PID $supervisor_pid in $SUPERVISOR_PID_FILE is not dev-server.sh; leaving it alone"
    fi
    rm -f "$SUPERVISOR_PID_FILE" "$SERVER_PID_FILE" "$FAKE_PID_FILE"

    if port_occupied "$SERVER_PORT"; then
        fail "port $SERVER_PORT already answers; a server is running that this script did not start. Leaving it alone. Set OPENCODE_DEV_PORT to use another port, or stop that server yourself."
    fi
    if port_occupied "$FAKE_PORT"; then
        fail "port $FAKE_PORT already answers; something this script did not start is listening. Leaving it alone. Set FAKE_PROVIDER_PORT to use another port."
    fi

    local bin
    bin="$(resolve_bin)"
    log "opencode binary: $bin"
    log "opencode version: $("$bin" --version 2>/dev/null || echo unknown)"

    local home="$STATE_DIR/home"
    rm -rf "$home"
    local password
    # The `; true` keeps `set -o pipefail` from treating tr's SIGPIPE (when head
    # exits after 32 bytes) as a failure.
    password="$(LC_ALL=C tr -dc 'A-Za-z0-9' </dev/urandom | head -c 32; true)"
    write_config "$home"

    rm -f "$STATE_DIR/serve.log" "$STATE_DIR/fake-provider.log" "$STATE_DIR/supervisor.log"
    {
        printf "bin=%s\n" "$bin"
        printf "home=%s\n" "$home"
        printf "password=%s\n" "$password"
    } >"$STATE_DIR/supervise.env"
    chmod 600 "$STATE_DIR/supervise.env"
    setsid nohup "$0" _supervise >>"$STATE_DIR/supervisor.log" 2>&1 &
    printf '%s' "$!" >"$SUPERVISOR_PID_FILE"
    log "supervisor started as PID $(read_pid "$SUPERVISOR_PID_FILE")"

    local url="http://$HOST:$SERVER_PORT"
    local version=""
    local deadline=$((SECONDS + 300))
    while ((SECONDS < deadline)); do
        local info
        if info="$(curl -s -m 5 -u "opencode:$password" "$url/api/info" 2>/dev/null)" &&
            [[ "$info" == *'"version":"'* ]]; then
            version="$(printf '%s' "$info" | grep -o '"version":"[^"]*"' | head -n 1 | cut -d'"' -f4)"
            break
        fi
        sleep 2
    done
    [[ -n "$version" ]] || fail "server did not become ready; see $STATE_DIR/serve.log"

    # The custom provider package installs asynchronously; wait until fake/text is listed.
    local models_ok=""
    deadline=$((SECONDS + 240))
    while ((SECONDS < deadline)); do
        local models
        if models="$(curl -s -m 10 --get -u "opencode:$password" "$url/api/model" \
                --data-urlencode "location[directory]=$STATE_DIR/work" 2>/dev/null)" &&
            [[ "$models" == *'"id":"text"'* && "$models" == *'"providerID":"fake"'* ]]; then
            models_ok="1"
            break
        fi
        sleep 3
    done
    [[ -n "$models_ok" ]] || fail "fake provider did not appear in /api/model; see $STATE_DIR/serve.log"

    {
        printf "OPENCODE_URL=%s\n" "$url"
        printf "OPENCODE_PASSWORD=%s\n" "$password"
        printf "OPENCODE_DIRECTORY=%s\n" "$STATE_DIR/work"
        printf "OPENCODE_VERSION=%s\n" "$version"
        printf "FAKE_PROVIDER_URL=http://%s:%s\n" "$HOST" "$FAKE_PORT"
    } >"$ENV_FILE"
    chmod 600 "$ENV_FILE"
    log "ready: $url (server $version, fake provider present)"
    print_exports
}

cmd_stop() {
    local supervisor_pid server_pid fake_pid
    supervisor_pid="$(read_pid "$SUPERVISOR_PID_FILE")"
    server_pid="$(read_pid "$SERVER_PID_FILE")"
    fake_pid="$(read_pid "$FAKE_PID_FILE")"

    if [[ -n "$supervisor_pid" ]] && is_alive "$supervisor_pid"; then
        if is_ours "$supervisor_pid" "dev-server.sh"; then
            log "stopping supervisor $supervisor_pid (its exit trap stops the servers)"
            kill -TERM "$supervisor_pid" 2>/dev/null || true
            wait_gone "$supervisor_pid" 15 || log "supervisor $supervisor_pid did not exit; leaving it alone"
        else
            log "PID $supervisor_pid in $SUPERVISOR_PID_FILE is not dev-server.sh; leaving it alone"
        fi
    fi

    # Belt and braces for recorded children the trap may have missed.
    if [[ -n "$server_pid" ]] && is_alive "$server_pid"; then
        if is_ours "$server_pid" "opencode"; then
            log "stopping leftover server $server_pid"
            kill -TERM "$server_pid" 2>/dev/null || true
            wait_gone "$server_pid" 10 || true
            if is_alive "$server_pid" && is_ours "$server_pid" "opencode"; then
                kill -KILL "$server_pid" 2>/dev/null || true
            fi
        else
            log "PID $server_pid is not our server; leaving it alone"
        fi
    fi
    if [[ -n "$fake_pid" ]] && is_alive "$fake_pid"; then
        if is_ours "$fake_pid" "fake-provider"; then
            log "stopping leftover fake provider $fake_pid"
            kill -TERM "$fake_pid" 2>/dev/null || true
            wait_gone "$fake_pid" 10 || true
            if is_alive "$fake_pid" && is_ours "$fake_pid" "fake-provider"; then
                kill -KILL "$fake_pid" 2>/dev/null || true
            fi
        else
            log "PID $fake_pid is not our fake provider; leaving it alone"
        fi
    fi

    rm -f "$SUPERVISOR_PID_FILE" "$SERVER_PID_FILE" "$FAKE_PID_FILE"
    log "stopped"
}

cmd_status() {
    load_env
    local supervisor_pid
    supervisor_pid="$(read_pid "$SUPERVISOR_PID_FILE")"
    if [[ -n "$supervisor_pid" ]] && is_alive "$supervisor_pid" && is_ours "$supervisor_pid" "dev-server.sh"; then
        printf 'supervisor: running (PID %s)\n' "$supervisor_pid"
    elif [[ -n "$supervisor_pid" ]] && is_alive "$supervisor_pid"; then
        printf 'supervisor: PID %s is not dev-server.sh; leaving it alone\n' "$supervisor_pid"
    else
        printf 'supervisor: not running\n'
    fi
    if server_healthy; then
        printf 'server: healthy at %s (version %s)\n' "$OPENCODE_URL" "$OPENCODE_VERSION"
    else
        printf 'server: not healthy\n'
    fi
}

usage() {
    printf 'usage: %s {start|stop|status}\n' "$0" >&2
    exit 2
}

case "${1:-}" in
    start) cmd_start ;;
    stop) cmd_stop ;;
    status) cmd_status ;;
    _supervise) cmd_supervise ;;
    *) usage ;;
esac
