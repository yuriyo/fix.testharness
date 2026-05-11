#!/usr/bin/env bash
# ─────────────────────────────────────────────────────────────────────────────
# play_scenario.sh — run one or more YAML scenarios against the FIX test harness
# ─────────────────────────────────────────────────────────────────────────────
# Usage:
#   ./play_scenario.sh [options] [scenario ...]
#
# Scenarios (positional, one or more):
#   - bare name:  order_flow          → scenarios/order_flow.yml
#   - path:       scenarios/my.yml    → used as-is
#   If none given, all scenarios/*.yml are run in alphabetical order.
#
# Options:
#   --config <file>   FIX config file  (default: config/client.cfg)
#   --host   <host>   Override SocketConnectHost
#   --port   <port>   Override SocketConnectPort
#   --tag    <tag>    Docker image tag (default: fix-testharness:latest)
#   --list            List available scenarios and exit
#   --help            Show this help and exit
#
# The only required dependency is Docker.
# ─────────────────────────────────────────────────────────────────────────────
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$SCRIPT_DIR"

IMAGE_TAG="${IMAGE_TAG:-fix-testharness:latest}"

# ── Defaults ──────────────────────────────────────────────────────────────
CONFIG="config/client.cfg"
OPT_HOST=""
OPT_PORT=""
SCENARIOS=()

# ── Parse arguments ───────────────────────────────────────────────────────
usage() {
    awk 'NR>1 && /^#/{print} NR>1 && !/^#/{exit}' "$0" | sed 's/^# \{0,1\}//'
    exit 0
}

list_scenarios() {
    echo "Functional scenarios (run by default):"
    ls scenarios/*.yml 2>/dev/null | grep -v '/perf_' | sed 's|scenarios/||; s|\.yml||' | sed 's/^/  /' || true
    echo ""
    echo "Performance scenarios (run explicitly — excluded from default suite):"
    ls scenarios/perf_*.yml 2>/dev/null | sed 's|scenarios/||; s|\.yml||' | sed 's/^/  /' || echo "  (none)"
    exit 0
}

while [[ $# -gt 0 ]]; do
    case "$1" in
        --help|-h)    usage ;;
        --list|-l)    list_scenarios ;;
        --config)     CONFIG="$2"; shift 2 ;;
        --host)       OPT_HOST="$2"; shift 2 ;;
        --port)       OPT_PORT="$2"; shift 2 ;;
        --tag)        IMAGE_TAG="$2"; shift 2 ;;
        --*)          echo "ERROR: unknown option: $1" >&2; exit 1 ;;
        *)
            arg="$1"
            if [[ "$arg" != */* && "$arg" != *.yml ]]; then
                arg="scenarios/${arg}.yml"
            fi
            SCENARIOS+=("$arg")
            shift
            ;;
    esac
done

# ── Default: all non-perf scenarios ────────────────────────────────────────
if [[ ${#SCENARIOS[@]} -eq 0 ]]; then
    while IFS= read -r f; do
        [[ "$(basename "$f")" == perf_* ]] && continue
        SCENARIOS+=("$f")
    done < <(ls scenarios/*.yml 2>/dev/null | sort)
    if [[ ${#SCENARIOS[@]} -eq 0 ]]; then
        echo "ERROR: no scenarios found in scenarios/" >&2; exit 1
    fi
fi

# ── Validate config ───────────────────────────────────────────────────────
if [[ ! -f "$CONFIG" ]]; then
    echo "ERROR: config file not found: $CONFIG" >&2; exit 1
fi

# ── Optional host/port override via temp config ───────────────────────────
# Create a modified config on the host; mount it into the container.
CLEANUP_CONFIG=""
CONTAINER_CONFIG="$CONFIG"

if [[ -n "$OPT_HOST" || -n "$OPT_PORT" ]]; then
    TMP_CFG="$(mktemp "$SCRIPT_DIR/config/.effective.cfg.XXXXXX")"
    CLEANUP_CONFIG="$TMP_CFG"
    trap 'rm -f "$CLEANUP_CONFIG"' EXIT
    cp "$CONFIG" "$TMP_CFG"
    [[ -n "$OPT_HOST" ]] && sed -i.bak "s/^SocketConnectHost=.*/SocketConnectHost=${OPT_HOST}/" "$TMP_CFG"
    [[ -n "$OPT_PORT" ]] && sed -i.bak "s/^SocketConnectPort=.*/SocketConnectPort=${OPT_PORT}/" "$TMP_CFG"
    rm -f "${TMP_CFG}.bak"
    # Container sees it as config/<filename> since config/ dir is mounted at /app/config
    CONTAINER_CONFIG="config/$(basename "$TMP_CFG")"
fi

# ── Resolve FIX server hostname for --add-host ────────────────────────────
# Docker build VMs / containers may not inherit the host's VPN/custom DNS.
# Extract SocketConnectHost from the effective config and resolve it on the host.
_EXTRA_HOST_ARGS=()

_fix_host="$(grep '^SocketConnectHost=' "${CLEANUP_CONFIG:-$CONFIG}" | cut -d= -f2 | tr -d '[:space:]')"
if [[ -n "$_fix_host" ]]; then
    _fix_ip="$(python3 -c "import socket; print(socket.gethostbyname('$_fix_host'))" 2>/dev/null || true)"
    if [[ -n "$_fix_ip" && "$_fix_ip" != "$_fix_host" ]]; then
        echo "[INFO] Host resolution: ${_fix_host} -> ${_fix_ip} (--add-host)"
        _EXTRA_HOST_ARGS=(--add-host "${_fix_host}:${_fix_ip}")
    fi
fi

# ── Prepare runtime directories ───────────────────────────────────────────
mkdir -p "$SCRIPT_DIR/store" "$SCRIPT_DIR/logs"

echo "==> Config: $CONTAINER_CONFIG"
echo "==> Image:  $IMAGE_TAG"
echo ""

# ── Run each scenario ─────────────────────────────────────────────────────
FAILED=()
for SCENARIO in "${SCENARIOS[@]}"; do
    if [[ ! -f "$SCENARIO" ]]; then
        echo "ERROR: scenario file not found: $SCENARIO" >&2
        FAILED+=("$SCENARIO")
        continue
    fi

    echo "==> Playing scenario: $SCENARIO"
    if ! docker run --rm \
            -v "$SCRIPT_DIR/config:/app/config:ro" \
            -v "$SCRIPT_DIR/scenarios:/app/scenarios:ro" \
            -v "$SCRIPT_DIR/store:/app/store" \
            -v "$SCRIPT_DIR/logs:/app/logs" \
            "${_EXTRA_HOST_ARGS[@]}" \
            "$IMAGE_TAG" \
            "$CONTAINER_CONFIG" "$SCENARIO"; then
        echo "FAILED: $SCENARIO" >&2
        FAILED+=("$SCENARIO")
    fi
    echo ""
done

if [[ ${#FAILED[@]} -gt 0 ]]; then
    echo "==> ${#FAILED[@]} scenario(s) failed:" >&2
    printf '     %s\n' "${FAILED[@]}" >&2
    exit 1
fi
