#!/usr/bin/env bash
# ─────────────────────────────────────────────────────────────────────────────
# build.sh — build the FIX Test Harness Docker image
#
# Usage:
#   ./build.sh [options]
#
# Options:
#   --tag <tag>       Image tag  (default: fix-testharness:latest)
#   --no-cache        Pass --no-cache to docker build
#   --help            Show this help and exit
#
# The only required dependency is Docker.
# ─────────────────────────────────────────────────────────────────────────────
set -euo pipefail

cd "$(dirname "$0")"

# ── Defaults ──────────────────────────────────────────────────────────────
IMAGE_TAG="${IMAGE_TAG:-fix-testharness:latest}"
NO_CACHE=""

# ── Parse arguments ───────────────────────────────────────────────────────
usage() {
    awk 'NR>1 && /^#/{print} NR>1 && !/^#/{exit}' "$0" | sed 's/^# \{0,1\}//'
    exit 0
}

while [[ $# -gt 0 ]]; do
    case "$1" in
        --help|-h)      usage ;;
        --tag)          IMAGE_TAG="$2"; shift 2 ;;
        --no-cache)     NO_CACHE="--no-cache"; shift ;;
        *)              echo "ERROR: unknown option: $1" >&2; exit 1 ;;
    esac
done

# ── Build ─────────────────────────────────────────────────────────────────
echo "==> Building Docker image: $IMAGE_TAG"
# shellcheck disable=SC2086
docker build $NO_CACHE -t "$IMAGE_TAG" .

echo ""
echo "==> Build complete: $IMAGE_TAG"
echo ""
echo "Run scenarios with:"
echo "  ./play_scenario.sh [options] [scenario ...]"
