#!/usr/bin/env bash
# Sync FIX store seqnums to match the proxy's reported state.
#
# Usage: ./sync_seqnums.sh [<proxy_local> <proxy_remote>]
#   No args: reset to 1/1 (use after proxy+simulator restart)
#   proxy_local  = "local" value from proxy (what proxy expects to receive from client)
#                  → written to senderseqnums
#   proxy_remote = "remote" value from proxy (proxy's own outgoing seqnum)
#                  → written to targetseqnums
#
# QuickFIX/J FileStore format: Java DataOutputStream.writeUTF
#   2-byte big-endian length prefix followed by ASCII digits.
#
# The files are written by a shell script running inside the fix-testharness image.
# Only Docker is needed on the host.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
STORE_DIR="$SCRIPT_DIR/store"
SESSION="FIX.4.2-YOYO-SIM"
IMAGE_TAG="${IMAGE_TAG:-fix-testharness:latest}"

if [[ $# -eq 0 ]]; then
    PROXY_LOCAL=1
    PROXY_REMOTE=1
elif [[ $# -eq 2 ]]; then
    PROXY_LOCAL="$1"
    PROXY_REMOTE="$2"
else
    echo "Usage: $0 [<proxy_local> <proxy_remote>]" >&2
    echo "  No args: reset to 1/1" >&2
    exit 1
fi

for v in "$PROXY_LOCAL" "$PROXY_REMOTE"; do
    if [[ ! "$v" =~ ^[0-9]+$ ]]; then
        echo "ERROR: sequence numbers must be whole numbers, got: $v" >&2
        exit 1
    fi
done

if ! docker image inspect "$IMAGE_TAG" &>/dev/null; then
    echo "ERROR: image $IMAGE_TAG not found. Run ./build.sh first." >&2
    exit 1
fi

mkdir -p "$STORE_DIR"

# The script below runs inside the container as POSIX sh (stdin), with the session,
# sender and target sequence numbers as arguments.
docker run --rm -i \
    --user "$(id -u):$(id -g)" \
    --entrypoint sh \
    -v "$STORE_DIR:/store" \
    "$IMAGE_TAG" -s "$SESSION" "$PROXY_LOCAL" "$PROXY_REMOTE" <<'SH'
set -eu
session="$1"
sender="$2"
target="$3"
store=/store

# QuickFIX/J writeUTF: a 2-byte big-endian length, then the ASCII digits.
# The length is at most 255 here, so the high byte is always 0.
write_utf() {
    {
        printf '\000'
        printf "\\$(printf '%03o' "${#2}")"
        printf '%s' "$2"
    } > "$1"
}

# Wipe body/header index files: they reference old seqnums and would make
# QuickFIX/J attempt gap fills for stale ranges.
for ext in body header; do
    if [ -e "$store/$session.$ext" ]; then
        : > "$store/$session.$ext"
    fi
done

write_utf "$store/$session.senderseqnums" "$sender"
write_utf "$store/$session.targetseqnums" "$target"

echo "[sync_seqnums] senderseqnums = $sender"
echo "[sync_seqnums] targetseqnums = $target"
echo "[sync_seqnums] body/header index cleared"
SH
