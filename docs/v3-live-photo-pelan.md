# JS Live Photo (v3): pelan reka bentuk

> Status: **REKA BENTUK SAHAJA, tiada kod lagi.** Dokumen ini merekod arahan Rafi (31 Okt 2026) dan cara Claude berfikir tentang seni bina.
> Mockup visual: kanvas "Live Photo v3 — Mockup" (landing page, admin, app user ×2, galeri client, upload client).
>
> Nota nama: branch `main` sekarang ialah **v2.0** (upload SMB terus ke TrueNAS). Reka bentuk baru ini dipanggil **v3 / JS Live Photo** supaya tak keliru. Nama boleh ditukar.

---

## 1. Arahan Rafi (direkod sepenuhnya)

### 1.1 Server side
1. Dibuka dari app. Login guna **Google**. Itulah "user". Admin boleh tambah user lain bila perlu.
2. **Admin nampak semua folder.**
3. Domain: **live.johorstudio.com**.
4. Website dijalankan di **TrueNAS (tnas)**. Sekarang ada **Tailscale**.
5. **App guna TrueNAS secara terus** (bukan melalui domain awam).
6. Data website disimpan di **`/mnt/ssd/livephoto`**.
7. Gambar yang diupload disimpan di **`/mnt/ssd/client-1month/livephoto`**.
8. Kalau path tak jumpa, beritahu semula.

### 1.2 Folder dan kebenaran
9. User buka folder, boleh **cipta folder** dan **padam folder**. Admin pun sama.
10. Folder ialah "project". **Admin nampak semua**, **user nampak yang dia cipta sahaja**.
11. Admin boleh **tambah user baru**. User login guna Google. **Kalau akaun Google tak sepadan, tolak.**
12. Ada juga pilihan **username + kod**: email dihantar dari **noreply@gmail.com** dengan **kod 6 digit**. **Tiada password.** **Login disimpan 30 hari.**
13. Admin mesti masukkan **email user** bila tambah user baru.
14. Admin boleh **buang** user, tapi **folder tak hilang** dan tak berubah. Kalau user ditambah semula, dia nampak semula folder yang dia cipta dulu.
15. Admin boleh ubah fail dan folder. **Kalau folder tiada, maksudnya fail pun tiada** (folder di disk ialah sumber kebenaran).
16. Format nama folder: **`YYYYMMDD Nama Event`**, contoh `20261031 event name`.
17. Admin yang akses melalui **SMB** nampak semua folder di situ sekaligus.

### 1.3 EVENTS, jurugambar, public
18. Bila folder siap, user dapat **pautan** (dan QR). Galeri **auto-tile**.
19. **EVENTS** ialah folder utama untuk event.
20. Admin boleh **tambah user ke event**, dan pilih siapa **Photographer 1**, siapa **Photographer 2** (dan seterusnya). Gambar dari user 1 masuk ke `Photographer 1`, dari user 2 ke `Photographer 2`.
21. Ada folder **PUBLIC**: popup upload. Orang awam boleh upload gambar sendiri dari telefon. **Public guna web sahaja, user guna app.** Gambar public masuk ke `EVENTS/<event>/public upload`.

### 1.4 Website
22. **Landing page** yang terangkan kegunaan website ini untuk bakal client, dengan contoh gambar event secara rawak. **Tema ikut johorstudio.com.**
23. **Public tak boleh akses EVENTS.** Admin boleh jadikan satu event sebagai **contoh** dalam satu seksyen di landing page. Kalau tiada, public hanya nampak landing page.
24. Butang **WhatsApp** dan butang **contact**.
25. Upload client: **resize dalam telefon** (bukan server). **Kekalkan resolusi**, tukar format ke **WebP 50%**. Fail yang diupload **hanya WebP**.
26. Butang upload **di atas**. Tunjuk **progress bar** dan beritahu **jangan tutup** sehingga selesai.
27. Susunan galeri public **tiled seperti Google Photos**. Gambar menegak dan melintang boleh bersebelahan. **Grid tak rigid.**
28. Galeri public **sentiasa dikemas kini**, nampak macam gambar baru masuk serta-merta. Claude tentukan kekerapan refresh.

### 1.5 Upload dari app (user/admin)
29. Wajib upload sebagai **WebP**: pilih kualiti **40, 50, 60, 70, 80, 90%**. **Default 50%.**
30. Pilihan **JPG kualiti penuh** juga ada. (Asal: 42 MP, ~24 MB, terlalu besar.)

### 1.6 Aliran app user
31. Buka app → **minta login** → tunjuk **senarai project** (tarikh, pautan atau QR).
32. Pilih event, atau **cipta EVENT baru**.
33. User boleh set folder simpanan di `/sdcard/`, atau guna default. **Yang pasti, setiap gambar yang masuk mesti disimpan.**
34. Ambil gambar di kamera → auto masuk folder → **tukar ke WebP** → **hantar ke server**.
35. Ada **status** dan **log**.
36. Pilihan **skrin sentiasa ON**, **kecerahan minimum**, **telefon tak boleh sleep**, dan **app jalan di latar belakang** bila tak difokus.

### 1.7 Permintaan untuk sesi ini
37. **Fikir dulu, jangan kod.** Cari kaedah paling kemas untuk teruskan.
38. Mockup: **paparan admin, paparan user, website, client view, client upload.**
39. **Rekod semua arahan dan cara Claude berfikir** dalam `.md` (fail ini).

---

## 2. Apa yang Claude jumpa (konteks sedia ada)

Dari repo `johorstudio-website`:
- TrueNAS di `192.168.0.100`, pool **`ssd`**. Website berjalan sebagai **TrueNAS Custom App** (`Apps > Installed`, dipasang dengan YAML).
- Domain awam keluar melalui **Cloudflare Tunnel `m10`** (Zero Trust › Networks › Tunnels). Ada juga Cloudflare Access untuk `webmaster.` dan `editor.`.
- Skrip `deploy/setup-truenas.sh` yang dijalankan melalui SSH sebagai root, dan selamat diulang.
- Tema: font **Archivo** (variable width), teal **`#1f6f68`**, graphite **`#20272b`**, paper **`#f5f7f7`**, mist **`#e2e8e9`**, steel **`#66737a`**, merah "tally" **`#d6453d`**.
- Kenalan: WhatsApp **012-798 3745**, studio di 3038-A Jln Merbau Satu, Bandar Putra, Kulai.

**Tentang "cuba cari path":** workspace Claude berjalan di cloud dan **tidak dapat capai TrueNAS atau Tailscale awak**, jadi Claude **tidak dapat sahkan** sama ada `/mnt/ssd/livephoto` dan `/mnt/ssd/client-1month/livephoto` wujud. Cadangan: skrip pasang (seperti website) akan **semak dan cipta** dataset/folder itu, dan **berhenti dengan mesej jelas** kalau pool atau dataset induk `client-1month` tiada. Lihat §9 untuk maklumat yang diperlukan.

---

## 3. Keputusan seni bina (dan sebabnya)

### 3.1 Satu server, satu pintu masuk
**Keputusan:** satu servis backend (API + website) sebagai TrueNAS Custom App `livephoto`, sama corak dengan website sedia ada.

```
           Internet                                   Tailscale / LAN
 Tetamu ──► live.johorstudio.com ──► Cloudflare Tunnel m10 ─┐
 Admin  ──► live.johorstudio.com ───────────────────────────┤
                                                            ▼
 App user ──► http://<tnas-tailscale>:8095 ────────► [ livephoto app di TrueNAS ]
     (fallback: live.johorstudio.com)                 ├─ API + website + SSE live
                                                      ├─ SQLite: /mnt/ssd/livephoto/data
                                                      └─ Gambar: /mnt/ssd/client-1month/livephoto/EVENTS/…
 Admin (SMB) ──► \\tnas\livephoto-events ─────────────────────► (folder yang sama)
```

**Kenapa app tidak upload melalui SMB (seperti v2.0)?**
- Arahan #5 kata app "direct guna tnas". Claude tafsirkan sebagai **sambung terus ke TrueNAS melalui Tailscale** (laju, tidak melalui internet awam). Tapi app tetap bercakap dengan **API servis yang sama**, bukan SMB, kerana:
  - Kebenaran per user (hanya event sendiri, slot Photographer N) mesti dikuatkuasa di **satu tempat**. Dengan SMB, setiap jurugambar perlu akaun TrueNAS sendiri dan ACL per folder, yang leceh dan mudah bocor.
  - Server perlu **tahu serta-merta** gambar baru masuk (untuk live refresh, thumbnail, kiraan). Upload melalui API memberi isyarat itu terus.
  - Login Google/OTP hanya wujud di server. SMB tak faham Google.
- App cuba **alamat Tailscale dulu**. Kalau tak dapat (contohnya Tailscale OFF), ia guna `live.johorstudio.com` melalui Cloudflare. Upload tetap jalan.
- **SMB kekal untuk admin** (arahan #17): share `livephoto-events` → folder `EVENTS` yang sama.

### 3.2 Folder di disk = sumber kebenaran
Arahan #15 ("kalau takda folder, maksudnya tiada fail") dan #17 (admin ubah melalui SMB) bermaksud **filesystem ialah kebenaran**, manakala database hanya metadata.
- Database simpan: siapa pemilik event, ahli dan slot Photographer N, token pautan, tetapan public upload, event contoh.
- **Pengimbas (watcher)**: inotify + imbasan penuh berkala (contohnya setiap 60 s) menyelaras DB dengan disk.
  - Folder dipadam melalui SMB → event jadi "tiada", pautan mati.
  - Folder dinamakan semula → dikesan melalui ID tersembunyi (`.livephoto.json` dalam folder) supaya pemilik dan pautan kekal.
  - Fail diletak terus melalui SMB → muncul dalam galeri (thumbnail dijana).
- Kenapa ada `.livephoto.json` dalam folder: supaya metadata **ikut folder**. Kalau DB hilang, ia boleh dibina semula dari disk.

### 3.3 Struktur folder
```
/mnt/ssd/client-1month/livephoto/
└── EVENTS/
    └── 20261031 Konvo Tadika Bandar Putra/
        ├── .livephoto.json          # id event, pemilik, slot (tersembunyi)
        ├── Photographer 1/          # webp (atau jpg penuh) dari user slot 1
        ├── Photographer 2/
        └── public upload/           # dari tetamu (webp sahaja)
/mnt/ssd/livephoto/
├── app/                             # kod (git clone)
├── data/livephoto.db                # SQLite: user, event, ahli, sesi, token
└── cache/thumbs/<event-id>/…        # thumbnail 480px & 1600px (boleh dipadam & dijana semula)
```
- Thumbnail **di luar** folder EVENTS, supaya admin melalui SMB hanya nampak gambar sebenar.
- Nama `client-1month` mencadangkan **simpanan 1 bulan**. Claude **tidak akan memadam apa-apa secara automatik** tanpa pengesahan (lihat soalan §9).

### 3.4 Pengguna, peranan, login
- **Peranan:** `admin`, `user`. Admin pertama: `rafierosliputra@gmail.com`.
- **Senarai dibenarkan (allowlist):** hanya email yang admin masukkan boleh login. Login Google dengan email lain ditolak (arahan #11).
- **Dua cara login, satu identiti (email):**
  1. **Google Sign-In.** Server sahkan ID token Google, kemudian email mesti ada dalam allowlist dan status aktif.
  2. **Kod email 6 digit.** Kod sah 10 minit, maksimum 5 cubaan, had kadar per email/IP. Dihantar melalui SMTP Gmail (perlukan App Password, lihat §9).
- **Sesi 30 hari** (arahan #12): cookie `HttpOnly; Secure` untuk web, dan token bearer untuk app (disimpan dalam app).
- **Buang user = nyahaktif (soft-delete)**: sesi dibatalkan, tetapi `owner_id` pada event kekal. Tambah semula email yang sama → akaun yang sama diaktifkan semula, dan event lamanya kelihatan semula (arahan #14).
- **Login app tanpa library Google:** app buka `live.johorstudio.com/login?app=1` dalam Custom Tab/browser, kemudian selepas berjaya server redirect ke `jstether://auth?token=…`. Kenapa: satu implementasi login sahaja (di server), dan app tidak perlu Google Play Services SDK. Ini penting kerana toolchain build kita sekarang tanpa Gradle/Maven.

### 3.5 Kebenaran (siapa nampak apa)
| | Admin | User | Tetamu (web) |
|---|---|---|---|
| Senarai semua EVENTS | ✅ | ❌ (hanya event dia cipta atau ditugaskan) | ❌ |
| Cipta / padam event | ✅ semua | ✅ event sendiri | ❌ |
| Upload ke Photographer N | ✅ | ✅ slot dia sahaja | ❌ |
| Lihat galeri melalui pautan | ✅ | ✅ | ✅ (token pautan sahaja) |
| Upload ke `public upload` | ✅ | ✅ | ✅ jika diaktifkan |
| Urus user, slot, event contoh | ✅ | ❌ | ❌ |

- Pautan galeri: `live.johorstudio.com/e/<token rawak 8-10 aksara>`. **Tiada senarai awam** (arahan #23). Admin boleh "Tukar pautan" untuk batalkan pautan lama.
- **Padam event:** padam terus folder di disk (arahan #9). Cadangan keselamatan: pindah ke `EVENTS/.trash/` dulu selama 7 hari. Perlu pengesahan (§9).

### 3.6 Live refresh galeri (arahan #28)
**Keputusan: Server-Sent Events (SSE), bukan polling.**
- Bila gambar baru siap (fail + thumbnail), server tolak event `photo` ke semua pelayar yang membuka galeri itu. Gambar muncul dalam **~1 s**, dengan animasi fade-in dan lencana "BARU".
- SSE ialah HTTP biasa: lalu Cloudflare Tunnel tanpa setting khas, sambung semula automatik, dan jimat bateri berbanding polling 2 s.
- Heartbeat setiap 20 s (Cloudflare memutuskan sambungan senyap sekitar 100 s).
- **Fallback:** kalau SSE gagal, polling `GET /api/e/<token>/since/<cursor>` setiap **5 s**. Bila tab disembunyikan (`visibilitychange`), poll diperlahankan ke 30 s, dan disegerakan semula bila dibuka.
- Bila masuk banyak serentak (burst), gambar baru dikumpul ke dalam satu kemas kini setiap 1 s supaya layout tak melompat-lompat.

### 3.7 Susun atur tiled (arahan #27)
- Algoritma **justified rows** (gaya Google Photos/Flickr): setiap baris ada ketinggian sasaran (~140 px telefon, ~240 px desktop). Lebar setiap gambar = nisbah aspek × tinggi, dan baris diskala supaya tepat penuh.
- Server simpan **lebar × tinggi** setiap gambar, jadi layout dikira **sebelum** gambar dimuat, tanpa lompatan.
- Gambar baru masuk di **atas** (terbaru dahulu). Penapis: Semua / Jurugambar / Tetamu.
- Muat malas (lazy-load) thumbnail 480 px. Ketik untuk lihat 1600 px, dan boleh muat turun asal.

### 3.8 Paip gambar
**App (Android):**
1. Tarik dari kamera (PTP, v1.3) → **simpan JPG asal** dalam folder telefon (wajib, arahan #33).
2. Tukar: decode → `Bitmap.compress(WEBP_LOSSY, q)` dengan q ∈ {40…90}, default 50. **Resolusi asal dikekalkan.** Atau pilih **JPG penuh**, yang hantar fail asal tanpa tukar.
   - Nota: 42 MP perlukan ~170 MB RAM untuk satu bitmap. Proses **satu demi satu**, guna `largeHeap`, dan gunakan semula buffer. Anggaran 1.5 hingga 3 s per gambar pada telefon moden.
3. Masuk **giliran upload yang kekal** (disimpan dalam fail, jadi tahan app ditutup atau telefon restart, membaiki had v2.0).
4. Upload ke API (`PUT /api/events/<id>/photos/<nama>`) dengan cubaan semula. Upload disambung semula jika putus (chunked, contohnya 4 MB/chunk) supaya tahan rangkaian lemah dan had 100 MB Cloudflare.

**Web tetamu:**
1. `<input type=file multiple accept="image/*">` → setiap gambar: decode → canvas saiz asal → encode **WebP 50%**.
2. ⚠️ **Safari iPhone tidak boleh encode WebP dengan canvas** (ia hasilkan PNG). Penyelesaian: encoder WebP **WebAssembly** (libwebp, contohnya jSquash). Ia dimuat hanya bila perlu, jadi semua telefon menghasilkan WebP yang sama.
3. Proses **satu demi satu** (gambar 48 MP iPhone besar), upload dengan progress bar, dan amaran "Jangan tutup halaman ini…" (arahan #26). `beforeunload` memberi amaran jika cuba tutup semasa upload.
4. **Server tetap sahkan:** hanya terima `image/webp` (semak bait sebenar, bukan sambungan fail), had saiz, buang metadata GPS/EXIF dari gambar tetamu, dan had kadar per IP.

**Server:** bila fail masuk → baca dimensi → jana thumbnail (libvips/sharp, WebP 480 dan 1600 px) → rekod → tolak SSE.

### 3.9 App Android (v3)
- **Foreground Service** (notifikasi kekal "JS Live · 298 disimpan · 2 giliran") supaya tether dan upload **terus berjalan di latar belakang** (arahan #36). Perlukan `FOREGROUND_SERVICE_CONNECTED_DEVICE` (Android 14+).
- **WakeLock** dan **skrin sentiasa ON** (pilihan).
- **Kecerahan minimum**: `screenBrightness = 0.01` bila app di depan. ⚠️ Android **tidak benarkan** app ubah kecerahan bila app tidak di depan. Jadi pilihan ini terpakai semasa skrin app terbuka. Bila di latar belakang, skrin ikut sistem tetapi servis tetap jalan.
- Skrin: Login → Senarai EVENTS (tarikh, pautan, QR, Kongsi) → Cipta EVENT (`YYYYMMDD` auto + nama) → Tether live (status kamera/server, paip Tarik→Simpan→WebP→Upload, kiraan, kualiti, togol, log).
- Tetapan SMB v2.0 dibuang daripada aliran user. Server yang uruskan.

### 3.10 Website
- Landing page (tema johorstudio.com): hero, cara ia berfungsi (3 langkah), **seksyen contoh event** (hanya jika admin tandakan), ciri, butang WhatsApp `wa.me/60127983745` dan pautan ke borang hubungi johorstudio.com, serta footer.
- Admin panel di web yang sama (`/admin`): EVENTS, jurugambar per event, pengguna (allowlist), event contoh, tetapan.
- **Bahasa:** mockup dalam Bahasa Melayu. Johorstudio.com dalam English, jadi perlu keputusan sama ada BM, EN atau dwibahasa (§9).

### 3.11 Teknologi dicadangkan
| Bahagian | Pilihan | Sebab |
|---|---|---|
| Backend | **Node.js (Fastify) + SQLite** dalam satu container | Ringan, satu proses, SSE mudah, `sharp` (libvips) laju untuk thumbnail |
| Frontend | HTML/CSS/JS statik (tanpa framework), seperti johorstudio.com | Laju di telefon, mudah diselenggara |
| Encoder web | jSquash WebP (WASM) | Safari tiada WebP encoder canvas |
| Deploy | TrueNAS Custom App YAML + skrip `setup-truenas.sh` | Corak sama seperti website sedia ada |
| Domain | Tambah hostname `live.johorstudio.com` pada tunnel `m10` | Tiada port dibuka di router |
| App | Java Android (sambung dari v1.3/v2.0) | Kod PTP yang sudah terbukti |

---

## 4. Model data (ringkas)
```
users(id, email UNIQUE, name, role[admin|user], status[active|removed], google_sub, created_at)
sessions(id, user_id, token_hash, kind[web|app], expires_at /*+30 hari*/)
login_codes(email, code_hash, expires_at, attempts)
events(id UUID, folder_name, date, title, owner_id, public_token, public_upload_on, showcase, created_at, deleted_at)
event_members(event_id, user_id, slot /*1,2,3…*/)          -- "Photographer N"
photos(id, event_id, rel_path, source[photographer|public], slot, width, height, bytes, taken_at, created_at)
```

## 5. API (lakaran)
```
POST /auth/google              POST /auth/code/request   POST /auth/code/verify   POST /auth/logout
GET  /api/me
GET  /api/events               POST /api/events {date,title}      DELETE /api/events/:id
GET  /api/events/:id           PATCH /api/events/:id {public_upload_on, showcase}
POST /api/events/:id/members {email, slot}   DELETE /api/events/:id/members/:userId
PUT  /api/events/:id/photos/:name   (chunked, app)
GET  /e/:token                 (halaman galeri)
GET  /api/e/:token/photos?cursor=   GET /api/e/:token/stream (SSE)
POST /api/e/:token/upload      (tetamu, webp sahaja)
GET  /api/admin/users          POST /api/admin/users {email, role}   PATCH /api/admin/users/:id {status}
```

## 6. Aliran utama
**Jurugambar di event:** Buka app → login (sekali, 30 hari) → pilih/cipta event → cucuk kamera → setiap shot: simpan JPG → WebP 50% → upload melalui Tailscale → galeri tetamu kemas kini ~1 s.

**Tetamu:** Imbas QR → galeri tiled live → butang Upload (atas) → pilih gambar → WebP dalam telefon → progress → masuk `public upload` → muncul dalam galeri.

**Admin:** web `/admin` → tambah email user → cipta event atau beri slot Photographer 1/2 → kongsi QR → (pilihan) tandakan sebagai contoh landing page.

## 7. Risiko & perkara yang perlu diuji
- Encode WebP 42 MP di telefon: masa dan RAM, terutama telefon lama. Perlu ujian sebenar.
- Upload melalui Cloudflare jika Tailscale OFF: had 100 MB per request (selamat dengan chunk 4 MB).
- SSE melalui Cloudflare Tunnel: perlu heartbeat. Uji dengan ~100 tetamu serentak.
- Folder dinamakan semula atau dipadam melalui SMB semasa event live.
- Ruang disk `client-1month` dan dasar simpanan.
- Gmail SMTP: had ~500 email sehari, cukup untuk kod login.

## 8. Fasa pelaksanaan (cadangan)
1. **Server teras:** auth (Google + kod email), allowlist, EVENTS, folder, pengimbas, upload API, thumbnail.
2. **Galeri tetamu:** tiled justified, SSE live, upload tetamu dengan WebP WASM, landing page.
3. **Admin panel:** user, slot jurugambar, event contoh, tukar pautan.
4. **App v3:** login melalui browser, senarai event, foreground service, WebP, giliran kekal, Tailscale + fallback.
5. **Deploy:** skrip TrueNAS dan hostname tunnel. Ujian event sebenar.

## 9. Soalan / maklumat yang Claude perlukan daripada Rafi
1. **Path:** sahkan `/mnt/ssd/livephoto` dan `/mnt/ssd/client-1month/livephoto` (atau benarkan skrip pasang menciptanya). Adakah `client-1month` dataset sedia ada dengan dasar padam automatik 1 bulan?
2. **Simpanan:** padam event automatik selepas 1 bulan? Atau hanya tutup galeri awam? Event dipadam pergi ke tong sampah 7 hari dulu?
3. **TrueNAS melalui Tailscale:** IP Tailscale atau nama MagicDNS TrueNAS, dan port yang boleh digunakan (cadangan 8095).
4. **Google Sign-In:** projek Google Cloud + OAuth Client ID (Web) dengan origin `https://live.johorstudio.com`. Claude boleh tulis langkah demi langkah.
5. **Email kod login:** `noreply@gmail.com` bukan alamat milik awak. Perlu satu akaun Gmail sebenar (contohnya `johorstudio.noreply@gmail.com`) + **App Password** untuk SMTP. Atau guna domain `noreply@johorstudio.com` melalui servis email.
6. **Cloudflare:** tambah public hostname `live.johorstudio.com` → `http://<truenas>:8095` pada tunnel `m10` (atau Claude sediakan langkah).
7. **Bahasa website:** BM, English atau dwibahasa?
8. **Bilangan jurugambar maksimum per event** (2, 3, …)?
9. **Gambar tetamu:** perlukan kelulusan admin sebelum muncul (moderasi), atau terus muncul?
10. **Nama versi:** v3 / "JS Live Photo" OK?

---

## 10. Log keputusan (ringkas)
| # | Keputusan | Alternatif yang ditolak | Sebab |
|---|---|---|---|
| D1 | App upload melalui API (Tailscale dahulu) | SMB terus dari app | Kebenaran per user, isyarat live, login Google |
| D2 | Filesystem = kebenaran + `.livephoto.json` | DB sahaja | Admin ubah melalui SMB, arahan #15 |
| D3 | SSE + fallback poll 5 s | Poll 2 s / WebSocket | Serta-merta, mesra Cloudflare, jimat bateri |
| D4 | Justified rows dengan dimensi dari server | Masonry kolum / grid tetap | Rupa Google Photos, tiada lompatan layout |
| D5 | WebP WASM di web | Canvas `toBlob('image/webp')` | Safari iPhone tak sokong encode WebP |
| D6 | Login app melalui browser + deep link | Google SDK dalam app | Satu sistem login, tiada Play Services/Gradle |
| D7 | Soft-delete user | Padam user | Arahan #14: folder kekal, tambah semula pulih |
| D8 | Thumbnail di luar EVENTS | Thumbnail dalam folder event | SMB admin bersih, cache boleh dijana semula |
| D9 | Foreground Service | Activity sahaja (v1/v2) | Jalan di latar belakang, arahan #36 |
| D10 | Satu Custom App + tunnel `m10` | VPS / port dibuka | Corak sama dengan johorstudio.com, percuma |
