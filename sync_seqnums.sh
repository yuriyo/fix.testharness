#!/usr/bin/env bash
# Sync FIX store seqnums to match the proxy's reported state.
#
# Usage: ./sync_seqnums.sh <proxy_local> <proxy_remote>
#   proxy_local  = "local" value from proxy (what proxy expects to receive from client)
#                  → written to senderseqnums
#   proxy_remote = "remote" value from proxy (proxy's own outgoing seqnum)
#                  → written to targetseqnums
#
# QuickFIX/J FileStore format: Java DataOutputStream.writeUTF
#   2-byte big-endian length prefix followed by ASCII digits.

set -euo pipefail

STORE_DIR="$(cd "$(dirname "$0")" && pwd)/store"
SESSION="FIX.4.2-YOYO-SIM"

if [[ $# -ne 2 ]]; then
    echo "Usage: $0 <proxy_local> <proxy_remote>" >&2
    exit 1
fi

PROXY_LOCAL="$1"    # → senderseqnums
PROXY_REMOTE="$2"   # → targetseqnums

python3 - <<PYEOF
import struct, os, sys

store = '${STORE_DIR}'
session = '${SESSION}'

def write_java_utf(path, n):
    s = str(n)
    b = s.encode('utf-8')
    with open(path, 'wb') as f:
        f.write(struct.pack('>H', len(b)))
        f.write(b)

sender = int('${PROXY_LOCAL}')
target = int('${PROXY_REMOTE}')

# Wipe body/header index files — they reference old seqnums and will
# cause QuickFIX/J to attempt gap fills for stale ranges.
for ext in ('body', 'header'):
    p = f'{store}/{session}.{ext}'
    if os.path.exists(p):
        open(p, 'wb').close()

write_java_utf(f'{store}/{session}.senderseqnums', sender)
write_java_utf(f'{store}/{session}.targetseqnums', target)

print(f'[sync_seqnums] senderseqnums = {sender}')
print(f'[sync_seqnums] targetseqnums = {target}')
print(f'[sync_seqnums] body/header index cleared')
PYEOF
