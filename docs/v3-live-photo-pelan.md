# JS Live Photo (v3): pelan reka bentuk

> Status: **Kod v3 siap di branch `v3` (31 Okt 2026)**, lihat `docs/HANDOFF.md`. Nota: server akhirnya tanpa Fastify/sharp (Node 24 `node:http` + `node:sqlite`, thumbnail dijana klien). Dokumen ini merekod arahan Rafi (31 Okt 2026) dan cara Claude berfikir tentang seni bina.
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

### 1.7 Pembetulan & jawapan Rafi (pusingan 2, 31 Okt 2026 22:36)
40. **Ada dua TrueNAS.** Claude tersilap anggap hanya satu.
    - **m10** (`192.168.0.100`, nama Tailscale `m10`): hos johorstudio.com, Nextcloud `cloud.johorstudio.cc`.
    - **tnas** (nama Tailscale `tnas`): Nextcloud `cloud.johorstudio.com`. **Fail disimpan di tnas.**
41. Server live photo (website + API) dijalankan di **m10**.
42. Domain dibuka **cara sama seperti cloud.johorstudio.com**, iaitu **Cloudflare Tunnel**.
43. m10 akses gambar di tnas melalui **NFS mount melalui Tailscale**.
44. `/mnt/ssd/livephoto` (DB, thumbnail, kod) di **m10**.
45. **User (jurugambar) dan public TIADA Tailscale. Hanya admin ada Tailscale.** Upload user dan public mesti berfungsi tanpa Tailscale.
46. Simpanan: **tiada had**. Galeri dan fail kekal sehingga dipadam manual (nama `client-1month` tidak bermaksud padam automatik).
47. Bahasa website: **English**.
48. Email kod login dari **noreply@johorstudio.com**, dihantar melalui **Mailjet** (`email.johorstudio.com`).
49. Gambar tetamu **terus muncul** dalam galeri (tiada kelulusan). Admin/user boleh sorok atau padam.
50. Padam event = **padam terus** (tiada tong sampah).
51. Jurugambar per event: **tiada had** (Photographer 1, 2, 3…).
52. Soalan kepada Rafi mesti dalam bentuk **pilihan yang boleh diklik**.

### 1.8 Permintaan untuk sesi ini
37. **Fikir dulu, jangan kod.** Cari kaedah paling kemas untuk teruskan.
38. Mockup: **paparan admin, paparan user, website, client view, client upload.**
39. **Rekod semua arahan dan cara Claude berfikir** dalam `.md` (fail ini).

---

## 2. Apa yang Claude jumpa (konteks sedia ada)

> **Pembetulan (pusingan 2):** ada **dua** TrueNAS, iaitu **m10** (`192.168.0.100`, website + `cloud.johorstudio.cc`) dan **tnas** (`cloud.johorstudio.com`, tempat fail disimpan). Lihat §1.7 dan §3.1 yang dikemas kini.

Dari repo `johorstudio-website`:
- TrueNAS **m10** di `192.168.0.100`, pool **`ssd`**. Website berjalan sebagai **TrueNAS Custom App** (`Apps > Installed`, dipasang dengan YAML).
- Domain awam keluar melalui **Cloudflare Tunnel `m10`** (Zero Trust › Networks › Tunnels). Ada juga Cloudflare Access untuk `webmaster.` dan `editor.`.
- Skrip `deploy/setup-truenas.sh` yang dijalankan melalui SSH sebagai root, dan selamat diulang.
- Tema: font **Archivo** (variable width), teal **`#1f6f68`**, graphite **`#20272b`**, paper **`#f5f7f7`**, mist **`#e2e8e9`**, steel **`#66737a`**, merah "tally" **`#d6453d`**.
- Kenalan: WhatsApp **012-798 3745**, studio di 3038-A Jln Merbau Satu, Bandar Putra, Kulai.

**Tentang "cuba cari path":** workspace Claude berjalan di cloud dan **tidak dapat capai TrueNAS atau Tailscale awak**, jadi Claude **tidak dapat sahkan** sama ada `/mnt/ssd/livephoto` dan `/mnt/ssd/client-1month/livephoto` wujud. Cadangan: skrip pasang (seperti website) akan **semak dan cipta** dataset/folder itu, dan **berhenti dengan mesej jelas** kalau pool atau dataset induk `client-1month` tiada. Lihat §9 untuk maklumat yang diperlukan.

---

## 3. Keputusan seni bina (dan sebabnya)

### 3.1 Satu server di m10, fail di tnas (dikemas kini pusingan 2)
**Keputusan:** satu servis backend (API + website) sebagai TrueNAS Custom App `livephoto` di **m10**, sama corak dengan website johorstudio.com. Gambar disimpan di **tnas** dan di-mount ke m10 melalui **NFS melalui Tailscale**.

```
                    INTERNET (user & tetamu tiada Tailscale)
 Tetamu (web) ─────┐
 Jurugambar (app) ─┼──► https://live.johorstudio.com ──► Cloudflare Tunnel (m10)
 Admin (web/app) ──┘                                              │
                                                                  ▼
 ┌──────────────────────── m10 (192.168.0.100) ──────────────────────────┐
 │  Custom App "livephoto": API + website + SSE live                      │
 │  /mnt/ssd/livephoto/{app,data/livephoto.db,cache/thumbs}               │
 │  /mnt/tnas-livephoto  ◄── NFS mount melalui Tailscale (m10 → tnas)     │
 └────────────────────────────────────────┬──────────────────────────────┘
                                          │ Tailscale (NFS)
 ┌──────────────────────── tnas ──────────▼──────────────────────────────┐
 │  /mnt/ssd/client-1month/livephoto/EVENTS/…   (gambar sebenar)          │
 │  Share SMB untuk admin ──► admin nampak semua folder (arahan #17)      │
 └───────────────────────────────────────────────────────────────────────┘
 Admin dengan Tailscale: boleh juga terus ke http://m10:8095 (lebih laju), dan SMB ke tnas.
```

**Kenapa begini:**
- **User dan tetamu tiada Tailscale** (arahan #45), jadi **semua upload mereka melalui `live.johorstudio.com`** (Cloudflare Tunnel di m10). Ini cara yang sama seperti `cloud.johorstudio.com` dibuka (arahan #42). Tunnel `m10` yang sedia ada hanya perlu hostname baru.
- **Upload dipecah (chunked, ~4 MB)**: tahan rangkaian 4G yang lemah, boleh sambung semula, dan selamat dengan had 100 MB per request Cloudflare (JPG penuh 24 MB pun lepas).
- **App tetap bercakap dengan API, bukan SMB:** kebenaran per user dan slot Photographer N dikuatkuasa di satu tempat. Server juga tahu serta-merta gambar baru masuk (untuk live), dan login Google/kod hanya wujud di server.
- **Admin** (ada Tailscale) boleh guna alamat terus `m10` untuk upload lebih laju. App cuba alamat Tailscale dahulu jika ditetapkan, dan guna domain awam jika gagal.
- **NFS** (bukan SMB) untuk m10 ↔ tnas: lebih stabil untuk servis yang banyak baca/tulis fail kecil, dan kebenaran fail mudah diselaraskan (UID app).
- **Risiko:** kalau Tailscale m10 ↔ tnas terputus, server tak boleh simpan gambar. Rancangan: server semak mount setiap kali sebelum terima upload. Kalau putus, upload ditolak dengan mesej "storage offline" supaya app mencuba semula kemudian (gambar tetap selamat dalam telefon). Tiada gambar disimpan separuh di m10.

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
# di tnas (di-mount ke m10 sebagai /mnt/tnas-livephoto)
/mnt/ssd/client-1month/livephoto/
└── EVENTS/
    └── 20261031 Konvo Tadika Bandar Putra/
        ├── .livephoto.json          # id event, pemilik, slot (tersembunyi)
        ├── Photographer 1/          # webp (atau jpg penuh) dari user slot 1
        ├── Photographer 2/
        └── public upload/           # dari tetamu (webp sahaja)
# di m10
/mnt/ssd/livephoto/
├── app/                             # kod (git clone)
├── data/livephoto.db                # SQLite: user, event, ahli, sesi, token
└── cache/thumbs/<event-id>/…        # thumbnail 480px & 1600px (boleh dipadam & dijana semula)
```
- Thumbnail **di luar** folder EVENTS, supaya admin melalui SMB hanya nampak gambar sebenar.
- **Simpanan tiada had** (arahan #46). Tiada padam automatik. Galeri kekal sehingga dipadam manual.

### 3.4 Pengguna, peranan, login
- **Peranan:** `admin`, `user`. Admin pertama: `rafierosliputra@gmail.com`.
- **Senarai dibenarkan (allowlist):** hanya email yang admin masukkan boleh login. Login Google dengan email lain ditolak (arahan #11).
- **Dua cara login, satu identiti (email):**
  1. **Google Sign-In.** Server sahkan ID token Google, kemudian email mesti ada dalam allowlist dan status aktif.
  2. **Kod email 6 digit.** Kod sah 10 minit, maksimum 5 cubaan, had kadar per email/IP. Dihantar melalui servis **JohorStudio Mail** sedia ada (repo `johorstudio-mail`, di m10): `POST http://192.168.0.100:8140/api/v1/send` dengan Bearer key app `livephoto` dan template terbina **`login_code`** (`{code, minutes, name}`). Servis itu yang hantar melalui Mailjet (fallback Gmail) dari `noreply@johorstudio.com`. Live photo **tidak** bercakap terus dengan Mailjet.
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
- **Padam event:** **padam terus** folder di disk (arahan #9, #50). Tiada tong sampah. Sebab itu UI minta pengesahan dengan **taip nama event** sebelum padam.
- **Gambar tetamu terus muncul** (arahan #49). Admin dan pemilik event boleh **sorok** (disembunyikan dari galeri, fail kekal) atau **padam** satu gambar.
- **Slot jurugambar tiada had** (arahan #51): Photographer 1, 2, 3…

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
- **Bahasa: English** (arahan #47), selari dengan johorstudio.com. Mockup awal dalam BM akan ditukar ke English. App jurugambar kekal BM (untuk pasukan sendiri), dan ini boleh diubah.

### 3.11 Teknologi dicadangkan
| Bahagian | Pilihan | Sebab |
|---|---|---|
| Backend | **Node.js (Fastify) + SQLite** dalam satu container | Ringan, satu proses, SSE mudah, `sharp` (libvips) laju untuk thumbnail |
| Frontend | HTML/CSS/JS statik (tanpa framework), seperti johorstudio.com | Laju di telefon, mudah diselenggara |
| Encoder web | jSquash WebP (WASM) | Safari tiada WebP encoder canvas |
| Deploy | TrueNAS Custom App YAML di **m10** + skrip `setup-truenas.sh` | Corak sama seperti website sedia ada |
| Storan | NFS export di **tnas** → mount di **m10** melalui Tailscale | Fail kekal di tnas (arahan #40, #43) |
| Domain | Tambah hostname `live.johorstudio.com` pada Cloudflare Tunnel `m10` | Cara sama seperti cloud.johorstudio.com, tiada port dibuka |
| Email | API JohorStudio Mail (m10:8140) → Mailjet | Servis sedia ada, template `login_code` sudah wujud (arahan #48) |
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

## 9. Jawapan Rafi & perkara yang masih perlu
**Sudah dijawab (pusingan 2):** server di m10 · Cloudflare Tunnel · NFS melalui Tailscale · data web di m10 · user/public tanpa Tailscale · simpanan tiada had · English · Mailjet noreply@johorstudio.com · tetamu terus muncul · padam terus · jurugambar tiada had. (Lihat §1.7.)

**Masih diperlukan semasa setup** (Claude akan beri langkah demi langkah):
1. **Path di tnas:** sahkan `/mnt/ssd/client-1month/livephoto` wujud (pool `ssd` di tnas?). Skrip akan semak dan cipta `livephoto/EVENTS` jika perlu.
2. **NFS export di tnas:** share `/mnt/ssd/client-1month/livephoto`, hanya dibenarkan dari IP Tailscale m10.
3. **Google Sign-In:** OAuth Client ID (Web) dengan origin `https://live.johorstudio.com`.
4. **JohorStudio Mail:** cipta API key untuk app `livephoto` di email.johorstudio.com (Admin → API keys).
5. **Cloudflare:** hostname `live.johorstudio.com` → `http://localhost:8095` pada tunnel `m10`.
6. **Nama versi:** v3 / "JS Live Photo" (boleh ditukar).

---

## 9b. Projek sedia ada yang berkaitan (guna semula, jangan bina semula)
| Repo | Kaitan |
|---|---|
| `johorstudio-mail` (m10, `email.johorstudio.com`, API `:8140`) | Hantar kod login 6 digit (template `login_code`), Mailjet + fallback Gmail |
| `invoicev2` | Contoh corak auth yang sama: Google OAuth + allowlist email, OTP 6 digit (`lib/otp.ts`, `lib/auth.ts`, `app/auth/google`), tema johorstudio.com + font Archivo |
| `teleprompter-mac-windows` (`tele.johorstudio.com`) | Contoh lain kod 6 digit melalui email, remote telefon tanpa VPN |
| `johorstudio-website` (m10) | Tema, corak deploy Custom App + Cloudflare Tunnel `m10` |
| `image-to-webp` | Pratetap kualiti WebP (40/50/65) yang Rafi biasa guna |
| `johorstudio-mediamtx` (tnas, `192.168.1.131`, SSH `truenas_admin`) | Corak app Node.js di TrueNAS + Telegram alert |
| `order-class-photo` | Jualan gambar konvo, boleh disambung dengan event live kemudian |

## 10. Log keputusan (ringkas)
| # | Keputusan | Alternatif yang ditolak | Sebab |
|---|---|---|---|
| D1 | App upload melalui API di `live.johorstudio.com` (admin boleh guna Tailscale) | SMB terus dari app; Tailscale untuk semua | User/public tiada Tailscale (#45); kebenaran per user, isyarat live, login Google |
| D11 | Server di m10, fail di tnas melalui NFS/Tailscale | Server di tnas | Pilihan Rafi (#41, #43); m10 sudah hos website + tunnel |
| D12 | Padam event terus, dengan pengesahan taip nama | Tong sampah 7 hari | Pilihan Rafi (#50) |
| D13 | Kod login melalui JohorStudio Mail API | Panggil Mailjet terus | Servis sedia ada: template, retry, log, fallback Gmail |
| D2 | Filesystem = kebenaran + `.livephoto.json` | DB sahaja | Admin ubah melalui SMB, arahan #15 |
| D3 | SSE + fallback poll 5 s | Poll 2 s / WebSocket | Serta-merta, mesra Cloudflare, jimat bateri |
| D4 | Justified rows dengan dimensi dari server | Masonry kolum / grid tetap | Rupa Google Photos, tiada lompatan layout |
| D5 | WebP WASM di web | Canvas `toBlob('image/webp')` | Safari iPhone tak sokong encode WebP |
| D6 | Login app melalui browser + deep link | Google SDK dalam app | Satu sistem login, tiada Play Services/Gradle |
| D7 | Soft-delete user | Padam user | Arahan #14: folder kekal, tambah semula pulih |
| D8 | Thumbnail di luar EVENTS | Thumbnail dalam folder event | SMB admin bersih, cache boleh dijana semula |
| D9 | Foreground Service | Activity sahaja (v1/v2) | Jalan di latar belakang, arahan #36 |
| D10 | Satu Custom App di m10 + tunnel `m10` | VPS / port dibuka | Corak sama dengan johorstudio.com, percuma |
