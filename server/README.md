# JS Live Photo server (v3)

Server untuk **live.johorstudio.com**: API + website + galeri live. Node 24, **tiada pakej npm** (`node:http`, `node:sqlite`).

## Jalankan di komputer (dev)
```bash
cd server
DATA_DIR=./data PHOTOS_DIR=./photos DEV_LOG_CODES=1 COOKIE_SECURE=0 PUBLIC_URL=http://localhost:8095 node src/server.js
# buka http://localhost:8095/login  -> kod 6 digit dicetak dalam log (DEV_LOG_CODES=1)
npm test        # ujian API hujung-ke-hujung (11 ujian)
```

## Struktur
| Fail | Kerja |
|---|---|
| `src/config.js` | Semua tetapan dari env (lihat `deploy/compose.yaml` + `livephoto.env`) |
| `src/db.js` | SQLite: users, sessions, login_codes, events, event_members (Photographer N), photos, uploads |
| `src/auth.js` | Google ID token + kod 6 digit melalui JohorStudio Mail (`template: login_code`), sesi 30 hari |
| `src/storage.js` | Folder EVENTS (sumber kebenaran), `.livephoto.json`, pengimbas 60 s, thumbnail cache |
| `src/api.js` | Semua route API (lihat bawah) |
| `src/live.js` | SSE per event, heartbeat 20 s |
| `src/imageinfo.js` | Saiz WebP/JPEG dari bait fail, buang EXIF/XMP (GPS) dari WebP tetamu |
| `public/` | Website (English): `index.html` landing, `login.html`, `gallery.html` (/e/:token), `admin.html` |
| `public/vendor/webp` | libwebp WASM (jSquash, Apache-2.0) untuk iPhone Safari |
| `public/vendor/qrcode` | Penjana QR (MIT) |

## API ringkas
```
GET  /auth/config                       POST /auth/google {credential, app?}
POST /auth/code/request {email}         POST /auth/code/verify {email, code, app?}
POST /auth/app-token (cookie)           POST /auth/logout          GET /api/me
GET  /api/events                        POST /api/events {date: YYYYMMDD, title}
GET  /api/events/:id                    PATCH /api/events/:id {publicUpload, showcase, newLink}
DELETE /api/events/:id {confirm: "<nama folder>"}                  POST /api/events/:id/rescan
POST /api/events/:id/members {email, slot}   DELETE /api/events/:id/members/:userId
GET  /api/events/:id/photos?before=&after=   GET /api/events/:id/missing-thumbs
PATCH /api/photos/:id {hidden}          DELETE /api/photos/:id
GET  /api/photos/:id/img/480|1600|orig  PUT /api/photos/:id/thumb/480|1600
# upload bercebis (app + tetamu), boleh disambung semula:
POST /api/events/:id/uploads {name,size}     POST /api/e/:token/uploads {size}   (tetamu, WebP sahaja)
GET  /api/uploads/:id  -> {received}         PUT /api/uploads/:id?offset=N  (badan = cebisan <= 4 MB)
PUT  /api/uploads/:id/thumb/480|1600         POST /api/uploads/:id/complete
# awam (melalui token pautan sahaja)
GET /api/e/:token   GET /api/e/:token/photos?before=&after=   GET /api/e/:token/stream (SSE)
GET /p/:token/:photoId/480|1600|orig[?dl=1]   GET /api/showcase   GET /s/:photoId/480|1600
GET /api/admin/users  POST /api/admin/users {email,name,role}  PATCH /api/admin/users/:id {status,role,name}
```

## Peraturan penting
- **Folder = kebenaran.** Folder baru melalui SMB diambil sebagai event (pemilik = admin). Folder dinamakan semula dikesan melalui `.livephoto.json`. Folder dipadam = event hilang.
- **Storan offline** (NFS putus): server balas 503 "Storage offline"; app simpan gambar dalam telefon dan cuba semula. Dikesan dengan fail penanda `.livephoto-storage` (`REQUIRE_STORAGE_MARKER=1`).
- Thumbnail dijana oleh klien (app / pelayar tetamu). Gambar yang masuk melalui SMB dibuatkan thumbnail oleh **pelayar admin** bila event dibuka di `/admin`. Sebelum itu galeri guna fail asal.
- Tetamu: WebP sahaja (semak bait sebenar), EXIF/GPS dibuang, had 300 upload/jam per IP, maks 40 MB.
