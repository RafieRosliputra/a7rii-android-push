#!/bin/bash
# Run ON tnas as root (sudo -i), once. Safe to run again.
#   - makes sure /mnt/ssd/client-1month/livephoto/EVENTS exists (owned by "apps", uid 568)
#   - shares it over NFS, only to m10's Tailscale IP, every client mapped to "apps"
# Usage: sudo bash setup-tnas.sh [M10_TAILSCALE_IP]
set -euo pipefail
M10_IP="${1:-100.101.43.120}"
POOL=ssd
PARENT_DS="$POOL/client-1month"
DS="$PARENT_DS/livephoto"
DIR="/mnt/$DS"

say() { printf '\n== %s\n' "$*"; }
[ "$(id -u)" = 0 ] || { echo "Run as root (sudo -i)"; exit 1; }
command -v midclt >/dev/null || { echo "This must run on TrueNAS (midclt not found)"; exit 1; }

say "Checking pool and parent dataset"
midclt call pool.dataset.query "[[\"id\",\"=\",\"$POOL\"]]" | grep -q "\"$POOL\"" || { echo "Pool '$POOL' not found on this TrueNAS. Tell Claude the pool name."; exit 1; }
if ! midclt call pool.dataset.query "[[\"id\",\"=\",\"$PARENT_DS\"]]" | grep -q "\"$PARENT_DS\""; then
  echo "Dataset $PARENT_DS not found. Stopping so nothing is created in the wrong place."
  echo "If it should be created, run:  midclt call pool.dataset.create '{\"name\":\"$PARENT_DS\"}'  and run this script again."
  exit 1
fi

say "Dataset $DS"
if midclt call pool.dataset.query "[[\"id\",\"=\",\"$DS\"]]" | grep -q "\"$DS\""; then echo "exists"
else midclt call pool.dataset.create "{\"name\":\"$DS\",\"share_type\":\"GENERIC\"}" >/dev/null && echo "created"; fi
mkdir -p "$DIR/EVENTS"
chown -R 568:568 "$DIR"
chmod 775 "$DIR" "$DIR/EVENTS"

say "NFS share for m10 ($M10_IP)"
EXISTING=$(midclt call sharing.nfs.query "[[\"path\",\"=\",\"$DIR\"]]")
if echo "$EXISTING" | grep -q '"id"'; then
  ID=$(echo "$EXISTING" | python3 -c 'import json,sys;print(json.load(sys.stdin)[0]["id"])')
  midclt call sharing.nfs.update "$ID" "{\"hosts\":[\"$M10_IP\"],\"mapall_user\":\"apps\",\"mapall_group\":\"apps\",\"enabled\":true}" >/dev/null
  echo "updated share #$ID"
else
  midclt call sharing.nfs.create "{\"path\":\"$DIR\",\"comment\":\"livephoto for m10 (live.johorstudio.com)\",\"hosts\":[\"$M10_IP\"],\"mapall_user\":\"apps\",\"mapall_group\":\"apps\"}" >/dev/null
  echo "share created"
fi
midclt call service.update nfs '{"enable":true}' >/dev/null
midclt call service.start nfs >/dev/null || true
midclt call service.reload nfs >/dev/null 2>&1 || true

say "Done"
echo "Photos folder: $DIR/EVENTS"
echo "Admin SMB: add an SMB share for $DIR (or its parent) to see every event folder at once."
echo "Next: on m10 run deploy/setup-m10.sh"
