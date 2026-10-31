#!/bin/bash
# Run ON m10 (192.168.0.100) as root (sudo -i), after setup-tnas.sh. Safe to run again.
#   - datasets ssd/livephoto + ssd/livephoto/data
#   - git clone of this repo (branch v3) into /mnt/ssd/livephoto/app
#   - secrets file /mnt/ssd/livephoto/livephoto.env (fill in once)
#   - NFS mount of tnas photos at /mnt/tnas-livephoto (remounted at boot and every 5 min)
#   - TrueNAS custom app "livephoto" (shows in Apps > Installed), port 8095
# Usage: sudo TNAS_HOST=<tnas tailscale IP or name> bash setup-m10.sh
set -euo pipefail
TNAS_HOST="${TNAS_HOST:-tnas}"
TNAS_PATH="${TNAS_PATH:-/mnt/ssd/client-1month/livephoto}"
REPO="${REPO:-https://github.com/RafieRosliputra/a7rii-android-push.git}"
BRANCH="${BRANCH:-v3}"
BASE=/mnt/ssd/livephoto
MNT=/mnt/tnas-livephoto
OWNER="${SUDO_USER:-truenas_admin}"

say() { printf '\n== %s\n' "$*"; }
[ "$(id -u)" = 0 ] || { echo "Run as root (sudo -i)"; exit 1; }
command -v midclt >/dev/null || { echo "This must run on TrueNAS m10"; exit 1; }

say "Datasets"
for ds in ssd/livephoto ssd/livephoto/data; do
  if midclt call pool.dataset.query "[[\"id\",\"=\",\"$ds\"]]" | grep -q "\"$ds\""; then echo "$ds exists"
  else midclt call pool.dataset.create "{\"name\":\"$ds\",\"share_type\":\"APPS\"}" >/dev/null && echo "$ds created"; fi
done
chown 568:568 "$BASE/data"

say "Code ($REPO, branch $BRANCH)"
if [ -d "$BASE/app/.git" ]; then
  sudo -u "$OWNER" git -C "$BASE/app" fetch -q origin "$BRANCH" && sudo -u "$OWNER" git -C "$BASE/app" checkout -q "$BRANCH" && sudo -u "$OWNER" git -C "$BASE/app" reset -q --hard "origin/$BRANCH"
else
  rm -rf "$BASE/app"
  URL="$REPO"; [ -n "${GITHUB_TOKEN:-}" ] && URL="${REPO/https:\/\//https://x-access-token:$GITHUB_TOKEN@}"
  git clone -q --depth 50 -b "$BRANCH" "$URL" "$BASE/app"
  git -C "$BASE/app" remote set-url origin "$REPO"   # don't keep the token on disk
  chown -R "$OWNER" "$BASE/app"
fi
git -C "$BASE/app" log --oneline -1

say "Secrets file"
ENV="$BASE/livephoto.env"
if [ ! -f "$ENV" ]; then
  cat > "$ENV" <<'EOF'
# JS Live Photo secrets (not in git). Edit, then: midclt call -j app.redeploy livephoto
ADMIN_EMAIL=rafierosliputra@gmail.com
# Google Cloud Console > APIs & Services > Credentials > OAuth client ID (Web), origin https://live.johorstudio.com
GOOGLE_CLIENT_ID=
# email.johorstudio.com > Admin > API keys > app "livephoto"
MAIL_API_KEY=
WHATSAPP=60127983745
CONTACT_URL=https://johorstudio.com/#contact
EOF
  echo "created $ENV  <- fill in GOOGLE_CLIENT_ID and MAIL_API_KEY"
else echo "$ENV exists (kept)"; fi
chown 568:568 "$ENV"; chmod 600 "$ENV"

say "NFS mount $TNAS_HOST:$TNAS_PATH -> $MNT"
MOUNT_SH="$BASE/mount-photos.sh"
cat > "$MOUNT_SH" <<EOF
#!/bin/sh
# Mount tnas photos for livephoto (run at boot and every 5 minutes; does nothing if mounted).
mkdir -p $MNT
if ! mountpoint -q $MNT; then
  mount -t nfs -o soft,timeo=50,retrans=2,noatime,nofail $TNAS_HOST:$TNAS_PATH $MNT && echo "mounted $MNT" || echo "mount failed (tnas or Tailscale down?)"
fi
mountpoint -q $MNT && [ -d $MNT/EVENTS ] && [ ! -f $MNT/.livephoto-storage ] && touch $MNT/.livephoto-storage && chown 568:568 $MNT/.livephoto-storage
exit 0
EOF
chmod 755 "$MOUNT_SH"
sh "$MOUNT_SH"
if ! mountpoint -q "$MNT"; then
  echo "Could not mount. Check: tailscale ping $TNAS_HOST ; showmount -e $TNAS_HOST ; that setup-tnas.sh allowed this machine's Tailscale IP."
  echo "Continuing: the app will report 'storage offline' until the mount works."
fi
# Boot-time mount (TrueNAS Init/Shutdown Scripts) + 5-minute check (Cron Jobs). Both visible in the UI.
midclt call initshutdownscript.query "[[\"command\",\"=\",\"$MOUNT_SH\"]]" | grep -q '"id"' || \
  midclt call initshutdownscript.create "{\"type\":\"COMMAND\",\"command\":\"$MOUNT_SH\",\"when\":\"POSTINIT\",\"enabled\":true,\"timeout\":30,\"comment\":\"livephoto NFS mount\"}" >/dev/null
midclt call cronjob.query "[[\"command\",\"=\",\"$MOUNT_SH\"]]" | grep -q '"id"' || \
  midclt call cronjob.create "{\"user\":\"root\",\"command\":\"$MOUNT_SH\",\"description\":\"livephoto NFS remount\",\"schedule\":{\"minute\":\"*/5\",\"hour\":\"*\",\"dom\":\"*\",\"month\":\"*\",\"dow\":\"*\"},\"stdout\":false,\"stderr\":false,\"enabled\":true}" >/dev/null
echo "boot script + cron job registered"

say "TrueNAS app 'livephoto'"
JSON=$(python3 -c "import json;print(json.dumps({'custom_compose_config_string':open('$BASE/app/deploy/compose.yaml').read()}))")
if midclt call app.query '[["name","=","livephoto"]]' | grep -q '"livephoto"'; then
  midclt call -j app.update livephoto "$JSON" | tail -1
else
  midclt call -j app.create "$(python3 -c "import json,sys;d=json.loads(sys.argv[1]);d.update(app_name='livephoto',custom_app=True);print(json.dumps(d))" "$JSON")" | tail -1
fi

say "Waiting for the app"
for i in $(seq 1 40); do
  sleep 3
  if curl -sf -m3 http://127.0.0.1:8095/auth/config >/dev/null; then
    echo "UP: http://192.168.0.100:8095"
    echo "Next: Cloudflare Zero Trust > Networks > Tunnels > m10 > Public hostname: live.johorstudio.com -> http://192.168.0.100:8095"
    exit 0
  fi
done
echo "App did not answer on :8095. Check Apps > Installed > livephoto > Logs."; exit 1
