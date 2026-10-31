#!/bin/bash
# From the Mac: pull the latest v3 code on m10 and restart the livephoto app.
# Usage: deploy/update.sh            (HOST defaults to m10 over Tailscale)
set -euo pipefail
HOST="${HOST:-truenas_admin@100.101.43.120}"
BRANCH="${BRANCH:-v4}"
SSH=(ssh -i "$HOME/.ssh/truenas_claude" -o IdentitiesOnly=yes "$HOST")
"${SSH[@]}" "git -C /mnt/ssd/livephoto/app fetch -q origin +refs/heads/$BRANCH:refs/remotes/origin/$BRANCH && git -C /mnt/ssd/livephoto/app checkout -q -B $BRANCH origin/$BRANCH && git -C /mnt/ssd/livephoto/app log --oneline -1 && midclt call -j app.redeploy livephoto | tail -1"
echo "waiting for app..."
for i in $(seq 1 30); do sleep 3; "${SSH[@]}" 'curl -sf -m3 http://127.0.0.1:8095/auth/config >/dev/null' && { echo "UP"; exit 0; }; done
echo "app did not come up (Apps > Installed > livephoto > Logs)"; exit 1
