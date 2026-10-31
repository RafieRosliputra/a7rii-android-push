#!/bin/bash
# One command from the Mac: installs JS Live Photo v3 on tnas (photos + NFS) and m10 (app).
#   cd <this repo, branch v3> && ./deploy/install.sh
# Asks for sudo passwords on the TrueNAS boxes, the Google Client ID and the JohorStudio Mail API key.
set -euo pipefail
cd "$(dirname "$0")/.."
KEY="${SSH_KEY:-$HOME/.ssh/truenas_claude}"
M10="${M10:-truenas_admin@100.101.43.120}"
TNAS="${TNAS:-truenas_admin@tnas}"
M10_TS_IP="${M10_TS_IP:-100.101.43.120}"
SSHO=(-o IdentitiesOnly=yes -o ConnectTimeout=10)
[ -f "$KEY" ] && SSHO+=(-i "$KEY")
say() { printf '\n\033[1;36m== %s\033[0m\n' "$*"; }

say "1/5 Semak sambungan SSH"
ssh "${SSHO[@]}" "$M10" true && echo "m10 OK ($M10)" || { echo "Tak dapat SSH ke m10 ($M10). Set M10=user@host"; exit 1; }
ssh "${SSHO[@]}" "$TNAS" true && echo "tnas OK ($TNAS)" || { echo "Tak dapat SSH ke tnas ($TNAS). Cuba: TNAS=truenas_admin@192.168.1.131 ./deploy/install.sh"; exit 1; }

say "2/5 IP Tailscale tnas"
TS=/Applications/Tailscale.app/Contents/MacOS/Tailscale
TNAS_IP="${TNAS_IP:-}"
[ -z "$TNAS_IP" ] && [ -x "$TS" ] && TNAS_IP=$("$TS" ip -4 tnas 2>/dev/null | head -1 || true)
[ -z "$TNAS_IP" ] && command -v tailscale >/dev/null && TNAS_IP=$(tailscale ip -4 tnas 2>/dev/null | head -1 || true)
[ -z "$TNAS_IP" ] && TNAS_IP=$(ssh "${SSHO[@]}" "$TNAS" 'tailscale ip -4 2>/dev/null | head -1' || true)
if [ -z "$TNAS_IP" ]; then read -rp "IP Tailscale tnas (100.x.x.x): " TNAS_IP; fi
echo "tnas = $TNAS_IP"

say "3/5 tnas: dataset livephoto + NFS share untuk m10 ($M10_TS_IP)"
scp -q "${SSHO[@]}" deploy/setup-tnas.sh "$TNAS:/tmp/setup-tnas.sh"
ssh -t "${SSHO[@]}" "$TNAS" "sudo bash /tmp/setup-tnas.sh $M10_TS_IP; rm -f /tmp/setup-tnas.sh"

say "4/5 Rahsia untuk m10 (tekan Enter untuk langkau, boleh isi kemudian)"
read -rp "Google OAuth Client ID (...apps.googleusercontent.com): " GCID
read -rsp "JohorStudio Mail API key (app livephoto, tak dipapar): " MKEY; echo
GH=""
command -v gh >/dev/null && GH=$(gh auth token 2>/dev/null || true)
[ -z "$GH" ] && read -rsp "GitHub token (Enter jika repo public): " GH && echo
SEC=$(mktemp); chmod 600 "$SEC"
[ -n "$GCID" ] && echo "GOOGLE_CLIENT_ID=$GCID" >> "$SEC"
[ -n "$MKEY" ] && echo "MAIL_API_KEY=$MKEY" >> "$SEC"
scp -q "${SSHO[@]}" "$SEC" "$M10:/tmp/livephoto.secrets"; rm -f "$SEC"
scp -q "${SSHO[@]}" deploy/setup-m10.sh "$M10:/tmp/setup-m10.sh"

say "5/5 m10: kod, mount NFS, app TrueNAS livephoto"
ssh -t "${SSHO[@]}" "$M10" "sudo SECRETS_FILE=/tmp/livephoto.secrets TNAS_HOST=$TNAS_IP GITHUB_TOKEN='$GH' bash /tmp/setup-m10.sh; rm -f /tmp/setup-m10.sh /tmp/livephoto.secrets"

say "Semak dari internet"
if curl -sf -m 10 https://live.johorstudio.com/auth/config >/dev/null; then echo "https://live.johorstudio.com OK"
else echo "live.johorstudio.com belum jawab: tambah Public hostname pada tunnel m10 -> http://192.168.0.100:8095"; fi
