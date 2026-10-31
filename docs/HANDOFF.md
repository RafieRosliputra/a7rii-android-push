# Nota serah-tugas (31 Okt 2026, malam)

Baca fail ini dahulu, kemudian `docs/v3-live-photo-pelan.md`. Semua kerja v3 ada di **branch `v3`** (`main` kekal v2.0 sehingga v3 diuji).

## Status
| Bahagian | Status | Lokasi |
|---|---|---|
| App v1.3: tether A7R II → telefon (PTP, PC Remote) | ✅ Diuji, berjaya | branch `v1`, `releases/JSTether-v1.3.apk` |
| App v2.0: upload SMB terus ke TrueNAS | 🧪 | `main`, `releases/JSTether-v2.0.apk` |
| Pelan v3 + mockup | ✅ | `docs/v3-live-photo-pelan.md`, `mockup/` |
| **Server v3** | ✅ Siap, 11 ujian API lulus | `server/` (`npm test`) |
| **Website v3** (English) | ✅ Siap, diuji dalam Chrome headless | `server/public/` |
| **Deploy TrueNAS** | ✅ Skrip siap, **belum dijalankan** | `deploy/setup-tnas.sh`, `deploy/setup-m10.sh`, `deploy/compose.yaml`, `deploy/update.sh` |
| **App Android v3** | ✅ Kod siap & lulus compile (android.jar API 33). **Belum dibina APK / diuji pada telefon** | `app/src/...`, `build-local.sh` |

## Langkah seterusnya (Rafi)
1. **tnas** (SSH, `sudo -i`): `bash deploy/setup-tnas.sh 100.101.43.120` → dataset `ssd/client-1month/livephoto`, folder `EVENTS`, NFS share hanya untuk IP Tailscale m10. Skrip berhenti jika `ssd/client-1month` tiada.
2. **m10** (SSH, `sudo -i`): `TNAS_HOST=<IP Tailscale tnas> bash setup-m10.sh` (muat turun dulu dari repo, atau `GITHUB_TOKEN=... ` jika repo private). Ia cipta dataset, clone branch v3 ke `/mnt/ssd/livephoto/app`, fail rahsia `/mnt/ssd/livephoto/livephoto.env`, mount NFS (skrip boot + cron 5 min), dan app TrueNAS **livephoto** (port 8095).
3. Isi `/mnt/ssd/livephoto/livephoto.env`: `GOOGLE_CLIENT_ID`, `MAIL_API_KEY` → `midclt call -j app.redeploy livephoto`.
4. **Cloudflare**: tunnel `m10` → Public hostname `live.johorstudio.com` → `http://192.168.0.100:8095`.
5. **APK** di Mac: `ANDROID_HOME=~/Library/Android/sdk ./build-local.sh` (release.keystore yang sama dalam folder projek) → install atas v2.
6. Ujian sebenar: login (Google + kod), cipta event, tether A7R II → WebP → galeri live di telefon lain, upload tetamu dari iPhone.
7. Kemas kini kod kemudian: `deploy/update.sh` dari Mac (git pull + redeploy).

## Perlu daripada Rafi
- IP/nama Tailscale **tnas** untuk `TNAS_HOST` (MagicDNS mungkin tak resolve pada hos TrueNAS; IP lebih selamat)
- Google OAuth Client ID (Web, origin `https://live.johorstudio.com`)
- API key JohorStudio Mail untuk app `livephoto`

## Keputusan sesi ini (jawapan Rafi, 31 Okt 23:11)
- Thumbnail gambar yang masuk melalui SMB: **dijana oleh pelayar admin** bila event dibuka di `/admin`.
- Encoder WebP WASM (iPhone): **disimpan dalam repo** (`server/public/vendor/webp`, jSquash 1.5.0, Apache-2.0).
- Deploy: **git clone** ke `/mnt/ssd/livephoto/app`, image `node:24-alpine` rasmi.
- APK: Claude tulis kod, **Rafi build di Mac** dengan keystore sebenar.
- Branch: **v3**. Upload tetamu: **ON** secara default. Pilihan kecil: Claude teruskan dan catat di bawah.

## Anggapan Claude (boleh ditukar)
- Container berjalan sebagai uid **568 (apps)**; NFS di tnas map semua klien ke `apps` supaya kebenaran fail selaras.
- NFS mount `soft,timeo=50,retrans=2`: jika tnas putus, server cepat lapor "storage offline" (bukan tergantung). Penanda `.livephoto-storage` dalam folder livephoto membezakan "mount putus" dari "folder kosong".
- Pengesahan Google melalui endpoint `tokeninfo` Google (server m10 perlu internet keluar).
- Kod login: sah 10 min, 5 cubaan, had 5 permintaan / 15 min per email; email tak berdaftar dapat jawapan sama (tak bocor senarai user).
- Event dicipta oleh user → dia jadi **Photographer 1**. Admin/pemilik yang upload tanpa slot dapat slot kosong seterusnya secara automatik.
- Galeri: 120 gambar pertama, "Load more" untuk lebih; SSE + poll 5 s (30 s bila tab tersembunyi); gambar baru dikumpul 1 s sebelum dipaparkan; lencana NEW 30 s.
- iPhone: gambar > 16 MP dibaca dalam jalur dan dienkod dengan WASM (had kanvas Safari). Gambar 48 MP iPhone mungkin berat pada RAM; perlu diuji.
- App: tukar event semasa ada giliran → giliran lama tetap dihantar ke event asal. Fail bukan JPEG (contoh ARW sahaja) disimpan dalam telefon tetapi **tidak** dihantar. Jika WebP gagal kerana memori, app hantar JPG asal.
- App: "Kecerahan minimum" hanya berkesan bila app di depan (had Android). Butang **QR** buka `/admin#event` dalam pelayar (QR dipapar di sana).
- Folder telefon default: `Pictures/JS Tether/<nama folder event>`.
- Kod SMB v2.0 (`SmbClient`, `NasUploader`, `Md4`) dibuang dari branch v3 (masih ada di `main`).

## Belum diuji / risiko
- APK belum dibina & diuji pada telefon sebenar (foreground service, WebP 42 MP, upload 4G).
- `setup-tnas.sh` / `setup-m10.sh` belum dijalankan pada TrueNAS sebenar (`midclt` API mungkin berbeza versi).
- Email kod melalui JohorStudio Mail sebenar (kontrak API disemak dari repo `johorstudio-mail`).

## Build APK
`build-local.sh`: aapt2 + javac + d8 + apksigner dari Android SDK di Mac (`ANDROID_HOME`). Kunci: `release.keystore` (tidak dalam git, ada dalam folder Mac). Tiada lambda Java (serasi dx lama).

## Projek berkaitan
Lihat §9b dalam pelan. tnas = `192.168.1.131` (Tailscale `tnas`), m10 = `192.168.0.100` (Tailscale `m10`, `100.101.43.120`).
