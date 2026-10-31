# Nota serah-tugas (31 Okt 2026)

Baca fail ini dahulu, kemudian `docs/v3-live-photo-pelan.md`.

## Status
| Bahagian | Status | Lokasi |
|---|---|---|
| App v1.3: tether A7R II → telefon (PTP, PC Remote) | ✅ Diuji, berjaya | branch `v1`, `releases/JSTether-v1.3.apk` |
| App v2.0: upload SMB terus ke TrueNAS | 🧪 Diuji dengan test vector + pelayan olok-olok sahaja | `main`, `releases/JSTether-v2.0.apk`, `app/src/...` |
| Pelan v3 (JS Live Photo) | ✅ Siap, dijawab Rafi | `docs/v3-live-photo-pelan.md` (§1.7 = jawapan terkini) |
| Mockup v3 (web English, app BM) | ✅ Siap | `mockup/` + kanvas "Live Photo v3 — Mockup" |
| Server v3 | ⏳ Baru bermula | `server/package.json`, `server/src/config.js` |

## Langkah seterusnya (server v3)
1. `server/src/db.js`: belum wujud (versi sebelum ini rosak dan dibuang). Tulis dari awal ikut §4 dalam pelan.
2. Login: Google ID token + kod 6 digit melalui **JohorStudio Mail API** (`192.168.0.100:8140`, template `login_code`), sesi 30 hari, allowlist email oleh admin. Rujuk corak dalam repo `invoicev2` (`lib/otp.ts`, `lib/auth.ts`, `app/auth/google`).
3. API events/users/upload (chunked), galeri awam melalui token, SSE live (§3.5–§3.8, §5).
4. Frontend web (English, tema johorstudio.com): landing, login, galeri tiled + upload tetamu, admin.
5. Deploy di **m10** (TrueNAS Custom App, port 8095). Gambar di **tnas** `/mnt/ssd/client-1month/livephoto`, di-mount ke m10 melalui NFS + Tailscale. `live.johorstudio.com` melalui Cloudflare Tunnel `m10`.
6. App Android v3 (§3.9).

## Keputusan penting (ringkas)
- Server tanpa pakej npm: Node 24 (`node:http`, `node:sqlite`). Thumbnail dijana oleh klien (app/pelayar), jadi server tak perlukan library imej.
- User/tetamu **tiada Tailscale**, jadi semua upload melalui `live.johorstudio.com`. Hanya admin ada Tailscale.
- Folder di disk = sumber kebenaran. Padam event = padam terus. Simpanan tiada had. Jurugambar tiada had. Gambar tetamu terus muncul.
- Website dalam English. Soalan kepada Rafi dalam BM, dengan pilihan boleh klik.

## Perlu daripada Rafi semasa setup
- Service URL Cloudflare untuk `live.johorstudio.com` (cadangan `http://192.168.0.100:8095`)
- Google OAuth Client ID (Web, origin `https://live.johorstudio.com`)
- API key JohorStudio Mail untuk app `livephoto`
- NFS export di tnas untuk IP Tailscale m10

## Projek berkaitan
Lihat §9b dalam pelan: `johorstudio-mail`, `invoicev2`, `teleprompter-mac-windows`, `johorstudio-website`, `image-to-webp`, `johorstudio-mediamtx`. tnas = `192.168.1.131` (Tailscale `tnas`), m10 = `192.168.0.100` (Tailscale `m10`).

## Build APK
`build-local.sh` (aapt2 + javac + dx + jarsigner, targetSdk 29). Tiada lambda Java (dx). Kunci: `release.keystore` (tidak dalam git, ada dalam folder Mac).
