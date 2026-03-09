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
#   --list            List available scenarios and exit
#   --help            Show this help and exit
# ─────────────────────────────────────────────────────────────────────────────
set -euo pipefail

cd "$(dirname "$0")"

JAR="target/fix-testharness-1.0.0-SNAPSHOT.jar"

# ── Defaults ──────────────────────────────────────────────────────────────
CONFIG="config/client.cfg"
OPT_HOST=""
OPT_PORT=""
SCENARIOS=()

# ── Parse arguments ───────────────────────────────────────────────────────
usage() {
    # Print the header comment block (lines 2..first blank-after-shebang block)
    awk 'NR>1 && /^#/{print} NR>1 && !/^#/{exit}' "$0" | sed 's/^# \{0,1\}//'
    exit 0
}

list_scenarios() {
    echo "Available scenarios:"
    ls scenarios/*.yml 2>/dev/null | sed 's|scenarios/||; s|\.yml||' | sed 's/^/  /' || echo "  (none)"
    exit 0
}

while [[ $# -gt 0 ]]; do
    case "$1" in
        --help|-h)    usage ;;
        --list|-l)    list_scenarios ;;
        --config)     CONFIG="$2"; shift 2 ;;
        --host)       OPT_HOST="$2"; shift 2 ;;
        --port)       OPT_PORT="$2"; shift 2 ;;
        --*)          echo "ERROR: unknown option: $1" >&2; exit 1 ;;
        *)
            # Bare name or path
            arg="$1"
            if [[ "$arg" != */* && "$arg" != *.yml ]]; then
                arg="scenarios/${arg}.yml"
            fi
            SCENARIOS+=("$arg")
            shift
            ;;
    esac
done

# ── Default: all scenarios ─────────────────────────────────────────────────
if [[ ${#SCENARIOS[@]} -eq 0 ]]; then
    while IFS= read -r f; do SCENARIOS+=("$f"); done < <(ls scenarios/*.yml 2>/dev/null | sort)
    if [[ ${#SCENARIOS[@]} -eq 0 ]]; then
        echo "ERROR: no scenarios found in scenarios/" >&2; exit 1
    fi
fi

# ── Build if jar is missing ───────────────────────────────────────────────
if [[ ! -f "$JAR" ]]; then
    echo "==> JAR not found, building first..."
    ./build.sh
fi

# ── Validate config ───────────────────────────────────────────────────────
if [[ ! -f "$CONFIG" ]]; then
    echo "ERROR: config file not found: $CONFIG" >&2; exit 1
fi

# ── Optional host/port override via temp config ───────────────────────────
EFFECTIVE_CONFIG="$CONFIG"
if [[ -n "$OPT_HOST" || -n "$OPT_PORT" ]]; then
    EFFECTIVE_CONFIG="$(mktemp /tmp/client.cfg.XXXXXX)"
    trap 'rm -f "$EFFECTIVE_CONFIG"' EXIT
    cp "$CONFIG" "$EFFECTIVE_CONFIG"
    [[ -n "$OPT_HOST" ]] && sed -i.bak "s/^SocketConnectHost=.*/SocketConnectHost=${OPT_HOST}/" "$EFFECTIVE_CONFIG"
    [[ -n "$OPT_PORT" ]] && sed -i.bak "s/^SocketConnectPort=.*/SocketConnectPort=${OPT_PORT}/" "$EFFECTIVE_CONFIG"
    rm -f "${EFFECTIVE_CONFIG}.bak"
fi

echo "==> Config: $EFFECTIVE_CONFIG"
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
    if ! java -jar "$JAR" "$EFFECTIVE_CONFIG" "$SCENARIO"; then
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
