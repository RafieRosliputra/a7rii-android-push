# a7rii-android-push — JS Tether

App Android ringkas yang **tarik setiap gambar daripada Sony A7R II terus ke telefon melalui kabel USB** sebaik saja shutter ditekan (tether kamera → Android), dan **(v2) hantar setiap gambar ke TrueNAS melalui SMB** (contohnya melalui Tailscale). Dibina oleh Johor Studio sebagai pengganti ringan untuk fungsi tether dalam app Piufoto.

- **APK terkini:** [`releases/JSTether-v2.0.apk`](releases/JSTether-v2.0.apk) (tether + TrueNAS)
- **Version 1 (stabil, telefon sahaja):** [`releases/JSTether-v1.3.apk`](releases/JSTether-v1.3.apk). Kod v1 juga dibekukan dalam branch [`v1`](../../tree/v1).
- **Package:** `com.johorstudio.tether` · minSdk 26 (Android 8) · targetSdk 29
- **Status:**
  - ✅ v1.3: diuji dan berjaya dengan Sony A7R II (ILCE-7RM2, USB `054C:0A6B`) dalam mod PC Remote, JPEG.
  - 🧪 v2.0: SMB diuji dengan test vector rasmi NTLMv2 dan pelayan SMB2 olok-olok (`tests/smb`). **Belum diuji dengan TrueNAS sebenar.**

---

## 1. Cara guna

### Tetapan kamera (A7R II)
1. **Menu → Setup → USB Connection → `PC Remote`**
2. **Menu → PC Remote Settings → Still Img. Save Dest. → `PC+Camera`** (atau `PC Only`).
   Kalau `Camera Only`, gambar tidak dihantar ke telefon.
3. Sambungkan kamera ke telefon dengan kabel USB (USB-C ↔ Micro-USB / adapter OTG).

### Dalam app
1. Pasang `JSTether-v1.3.apk` (benarkan *Install unknown apps*).
2. Buka **JS Tether**, tekan **Pilih folder simpan** dan pilih folder (contoh `Storan dalaman/Cam`).
   Kalau tidak dipilih, gambar disimpan ke `Pictures/JS Tether`.
3. Cucuk kabel. Bila Android tanya, benarkan akses USB / buka dengan JS Tether.
4. Status hijau `Kamera: Sony Corporation ILCE-7RM2 ✓ (PC Remote)` = sedia. Tekan shutter, gambar terus masuk folder.

### Paparan
| Bahagian | Maksud |
|---|---|
| **Kamera** | tidak bersambung / meminta kebenaran USB / bersambung (model + mod) / ralat |
| **Status** | Menunggu gambar → Kamera sedang proses gambar → Menarik `DSCxxxxx.JPG` xx% → Siap |
| **Gambar disimpan** | Kiraan gambar sesi ini |
| **Folder** | Folder simpanan semasa |
| **Log** | Setiap arahan PTP, event kamera dan ralat (untuk debug) |

### Hantar ke TrueNAS (v2)
Aliran: kamera → telefon (disimpan dulu dalam folder telefon) → giliran upload → TrueNAS (SMB).

**Sediakan di TrueNAS (sekali sahaja):**
1. **Shares → SMB → Add**: pilih dataset (contoh `/mnt/ssd/photos`), beri nama share (contoh `photos`), dan pastikan servis SMB **Running**.
2. **Credentials → Users**: guna user yang ada **Samba Authentication** (SMB user) dan ada kebenaran tulis ke dataset tersebut.
3. Telefon mesti ada **Tailscale** yang aktif (VPN ON) dan berada dalam tailnet yang sama dengan TrueNAS.

**Dalam app:**
1. Tekan **Tetapan & sambung**, kemudian isi:
   - **Host**: IP Tailscale TrueNAS (contoh `100.x.y.z`) atau nama MagicDNS (contoh `truenas`). Boleh tambah port, contoh `100.x.y.z:445`.
   - **Share**: nama share SMB (contoh `photos`).
   - **Username / Password**: user TrueNAS.
   - **Domain**: biasanya biarkan kosong.
2. Tekan **Simpan & sambung**. App akan uji sambungan, kemudian buka pelayar folder.
3. Dalam pelayar folder:
   - tekan folder untuk masuk, atau `..` untuk naik
   - **Folder baru**: cipta folder dalam folder semasa (nama dicadang dengan tarikh hari ini, contoh `2026-10-31 Konvo Tadika`)
   - **Pilih folder ini**: jadikan folder sasaran upload
4. Tandakan **Hantar setiap gambar ke TrueNAS**.

Tingkah laku upload:
- Setiap gambar **disimpan dalam telefon dahulu**, kemudian dimasukkan ke giliran upload. Kalau NAS/Tailscale terputus, gambar tetap selamat dalam telefon.
- Kalau gagal, app cuba semula setiap 10 s, sampai 30 kali bagi setiap gambar.
- **Tidak pernah tulis-ganti** fail di NAS: kalau `DSC00001.JPG` sudah ada, fail baru dinamakan `DSC00001 (1).JPG`.
- Fail separuh siap di NAS dipadam kalau upload gagal di tengah jalan.
- Paparan: `NAS: memuat naik DSC… 45%`, `Dihantar ke NAS: n · Giliran: m`.
- Password disimpan dalam storan peribadi app (SharedPreferences), tidak disulitkan.

### Petua
- **Force stop / disable Piufoto** (atau app tether lain) semasa guna JS Tether. App lain yang juga mendaftar untuk `USB_DEVICE_ATTACHED` boleh "rampas" kamera, dan kamera akan berhenti membalas.
- App mesti **kekal terbuka** (skrin dikekalkan menyala secara automatik). Kalau app ditutup, gambar berhenti ditarik.
- Kalau tersangkut: tekan **Sambung semula kamera**, atau matikan dan hidupkan kamera.

---

## 2. Bagaimana Piufoto melakukannya (hasil bongkar APK)

APK Piufoto `com.alltuu.android.piufoto` v3.62.3 (oleh Alltuu) dianalisis untuk mengetahui cara ia tarik gambar dari kamera. Ringkasan:

| Lapisan | Apa yang dijumpai |
|---|---|
| **UI / logik** | Flutter (`libapp.so`). Plugin peribadi `alltuu_transmission_plugin`: `ptp_channel_manager.dart`, `alltuu_camera_protocol_handler.dart`, `camera_method_channel.dart`, `camera_event_channel.dart`. Fungsi: `getObjectInfo`, `getObjectInfoAndThumbnailByte`, `getObject`, `_handlePtpEventObjectAdded`, `checkPTPTransmissionMode`, `checkConnectedCameraSonyOrFuji`. |
| **Android (Java)** | Android USB Host API (`USB_DEVICE_ATTACHED/DETACHED`, kebenaran USB). Kod Java disulitkan dengan pelindung **NetEase Yidun** (`com.xaqalea…`, `nedig.properties`), jadi tak boleh dibaca terus. |
| **Native** | `libportal0.1.so` ialah pembalut kecil untuk `libusb1.0.so`. Ia guna `libusb_wrap_sys_device` (ambil file descriptor USB dari Android), `claim_interface`, `bulk_transfer_in/out`, dan endpoint interrupt untuk event. **PTP dilaksanakan sendiri** di atasnya. Tiada libgphoto2. |
| **Lain-lain** | `libaums` (USB mass storage, untuk card reader, bukan untuk kamera). |

Mod Sony dalam Piufoto:
- **PC Remote (PTP):** tether sebenar. Kamera hantar event bila gambar baru diambil, dan app auto-tarik.
- **MTP:** hanya untuk melihat atau upload gambar yang sudah ada dalam kad ("Only Tap to Upload is available in MTP mode").
- Mesej amaran dalam app sendiri menyarankan *Still Image Save Destination* = **Camera + Phone / PC + Camera**.

JS Tether mengambil pendekatan yang sama (PTP terus melalui USB, tanpa gphoto2), tetapi **ditulis 100% dalam Java** guna `UsbDeviceConnection.bulkTransfer()`. Tiada kod native.

---

## 3. Protokol: PTP over USB untuk Sony PC Remote

### 3.1 Transport (PTP / USB Still Image Class)
- Interface USB class `6` (Still Image). Untuk A7R II: `if#0 cls=6`, bulk **IN `0x81`**, bulk **OUT `0x02`** (max packet 512), interrupt **IN `0x83`** (event).
- Setiap mesej ialah *container*: `length u32 | type u16 | code u16 | transactionId u32 | params…` (little-endian).
  - type `1` Command, `2` Data, `3` Response, `4` Event.
- Satu transaksi: hantar Command → (pilihan) fasa Data → baca Response (`0x2001` = OK).
- `OpenSession` guna transaction ID **0**, selepas itu tid bermula dari 1 dan bertambah.

### 3.2 Urutan sambungan (dari log sebenar)
```
GetDeviceStatus (class req 0x67)           → 0x2001
0x1001 GetDeviceInfo                        → 0x2001   "Sony Corporation ILCE-7RM2", VendorExt 0x11 (Sony PTP Extensions)
0x1002 OpenSession(1)          tid 0        → 0x2001
0x9201 SDIOConnect(1,0,0)                   → 0x2001   ┐
0x9201 SDIOConnect(2,0,0)                   → 0x2001   │ Handshake Sony PC Remote
0x9202 GetSDIOGetExtDeviceInfo(0xC8)        → 0x2001   │ (urutan sama seperti libgphoto2)
0x9201 SDIOConnect(3,0,0)                   → 0x2001   ┘
```
Mod PC Remote dikesan bila `0x9201` ada dalam senarai OperationsSupported dalam DeviceInfo.

### 3.3 Bila shutter ditekan
```
Event 0xC201 (Sony ObjectAdded)  p=0xFFFFC001
0x9209 GetAllDevicePropData → prop 0xD215 ObjectInMemory = 0x8001   (> 0x8000 = ada gambar dalam buffer)
0x1008 GetObjectInfo(0xFFFFC001)  → nama fail (DSC00513.JPG), saiz
0x1009 GetObject(0xFFFFC001)      → data penuh fail → tulis ke folder
Event 0xC202 p=0xFFFFC001                                              (objek dilepaskan dari buffer)
```
- Sony **tidak** guna handle objek biasa. Semua gambar yang baru diambil berada di **handle tetap `0xFFFFC001`** (barisan dalam memori kamera).
- **Jangan** tarik terus sebaik dapat `0xC201`. Tunggu `0xD215 ObjectInMemory > 0x8000`, sama seperti cara gphoto2. App polling `0x9209` setiap ~1.5 s, dan segera selepas event.
- Selepas setiap gambar, `0xD215` disemak semula. Kalau masih > `0x8000` (contohnya burst atau RAW+JPEG), app tarik lagi.
- `0xC203` (property changed) datang banyak kali. Ia diabaikan dalam log.

### 3.4 Format `0x9209` (GetAllDevicePropData, Sony)
```
u64 count
repeat count:
  u16 propCode, u16 dataType, u8 getSet, u8 isEnabled (khas Sony)
  factoryDefault (ikut dataType), currentValue (ikut dataType)
  u8 formFlag: 0 tiada | 1 range (min,max,step) | 2 enum (u16 n + n nilai)
```
dataType: `1/2` 8-bit, `3/4` 16-bit, `5/6` 32-bit, `7/8` 64-bit, `9/10` 128-bit, `0x4xxx` array (`u32 n` + elemen), `0xFFFF` string PTP.

### 3.5 ObjectInfo (`0x1008`), offset yang digunakan
`0` StorageID · `4` ObjectFormat (u16) · `8` CompressedSize (u32) · `38` ParentObject · `52` Filename (string PTP: `u8 n` + UTF-16LE).

---

## 3b. SMB ke TrueNAS (v2): `SmbClient.java`

Klien SMB minimum ditulis sendiri dalam Java (tiada library luar seperti jcifs/smbj), supaya APK kekal kecil dan boleh dibina tanpa Gradle/Maven.

| Perkara | Pelaksanaan |
|---|---|
| Transport | TCP terus ke port 445 (header NetBIOS 4 bait: `0x00` + panjang 24-bit) |
| Dialek | Negotiate **SMB 2.0.2 dan 2.1** (`0x0202`, `0x0210`). Samba/TrueNAS menyokong kedua-duanya secara default. SMB3 (encryption) tidak disokong. |
| Login | **NTLMv2** dalam SPNEGO (`NegTokenInit` / `NegTokenResp`). MD4 ditulis sendiri (`Md4.java`) kerana Android tiada MD4. |
| Signing | Kalau server mewajibkan signing: HMAC-SHA256 dengan SessionBaseKey (tanpa KEY_EXCH) |
| Credits | CreditRequest 256. WRITE sehingga 1 MB dengan CreditCharge = ⌈len/64KB⌉ (SMB 2.1) |
| Operasi | `TREE_CONNECT \\host\share`, `CREATE` (buka folder / cipta folder / cipta fail `FILE_CREATE`), `QUERY_DIRECTORY` (FileDirectoryInformation), `WRITE`, `CLOSE`, padam fail separa (`DELETE_ON_CLOSE`) |
| Ujian | `tests/smb/NtlmVectorTest.java` (test vector MS-NLMP §4.2.4: NTOWFv2, LMv2, NTProofStr, SessionBaseKey ✅) dan `tests/smb/mock_smb.py` + `SmbMockTest.java` (aliran penuh terhadap pelayan olok-olok) |

Kalau sambungan TrueNAS gagal, log akan tunjuk sebabnya, contohnya:
- `username/password salah`: user tiada Samba Authentication, atau password salah
- `nama share tidak wujud`: nama share salah
- `akses ditolak`: user tiada kebenaran tulis (ACL dataset)
- `Server mahu SMB 3 sahaja`: TrueNAS disetkan SMB3 minimum
- `connect timed out`: Tailscale tidak aktif, atau IP/host salah

---

## 4. Pengajaran daripada debug (penting kalau ubah kod)

| Versi | Masalah | Punca | Penyelesaian |
|---|---|---|---|
| v1.0 | `USB baca gagal (op 0x1001)`, `USB tulis gagal` | Kamera tidak membalas, kemungkinan masih dipegang sesi lain (Piufoto / MTP Android) | v1.1: PTP **Device Reset** (class req `0x66`), clear halt, buang data lama, cuba 3 kali, log terperinci |
| v1.1 | Boleh sambung dan shoot, tapi terputus selepas setiap shot, tiada fail | Tarik gambar terlalu awal dan timeout 5 s, lalu satu ralat mematikan sesi | v1.2: tunggu `0xD215 ObjectInMemory`, timeout 20 s untuk GetObject/GetObjectInfo, ralat satu gambar tidak putuskan sesi |
| v1.2 | `USB baca gagal (op 0x1009, 1 ms, bufsz 262144)`, fail pelik ~0.1 MB dengan nama "rosak" | **Telefon ini menolak `bulkTransfer` dengan buffer > 16 KB** serta-merta. App kemudian "buang data lama", yang sebenarnya gambar itu sendiri, dan transaksi seterusnya tersasar lalu membaca baki JPEG sebagai ObjectInfo | v1.3: **sentiasa baca 16 KB**, **semak transaction ID** setiap container (paket lama dibuang), tolak nama fail tidak sah |
| v1.3 | ✅ Berjaya | | |
| v2.0 | (baru) | | Tambah upload ke TrueNAS melalui SMB (tetapan, pelayar folder, cipta folder, giliran upload) |

Peraturan utama:
1. **Bacaan bulk maksimum 16384 bait setiap panggilan.** Walaupun Android 9+ secara rasmi benarkan lebih, sesetengah telefon gagal.
2. **Sentiasa semak `transactionId`** pada container Data/Response. Jangan percaya paket pertama yang dibaca.
3. Satu ralat gambar ≠ kamera terputus. Hanya anggap putus selepas beberapa ralat berturut-turut atau bila dapat `USB_DEVICE_DETACHED`.
4. Pastikan tiada app lain memegang kamera.

---

## 5. Struktur projek
```
app/src/main/
├── AndroidManifest.xml            # USB host, intent-filter USB_DEVICE_ATTACHED, singleTask
├── res/xml/device_filter.xml      # class 6 (PTP) + vendor Sony 0x054C (1356)
└── java/com/johorstudio/tether/
    ├── Ptp.java                   # Implementasi PTP-over-USB + Sony handshake + 0x9209 parser
    ├── SmbClient.java             # Klien SMB2 + NTLMv2 + SPNEGO (v2)
    ├── Md4.java                   # MD4 untuk NT hash (v2)
    ├── NasUploader.java           # Giliran upload ke NAS dengan cubaan semula (v2)
    └── MainActivity.java          # UI (dibina dalam kod, tanpa layout XML), kebenaran USB,
                                   # worker thread, simpan fail (SAF / MediaStore), dialog TrueNAS
releases/JSTether-v2.0.apk          # APK terkini (tether + TrueNAS)
releases/JSTether-v1.3.apk          # Version 1 (telefon sahaja)
tests/smb/                          # Ujian NTLM + pelayan SMB2 olok-olok
build-local.sh                      # Build tanpa Gradle (aapt2 + javac + dx + jarsigner)
build.gradle, app/build.gradle      # Build Gradle standard (Android Studio)
.github/workflows/build.yml         # Build APK automatik di GitHub Actions
```

### Simpan fail
- Folder dipilih guna `ACTION_OPEN_DOCUMENT_TREE` (Storage Access Framework). Kebenaran disimpan kekal (`takePersistableUriPermission`), dan fail dicipta dengan `DocumentsContract.createDocument`.
- JPEG disimpan sebagai `image/jpeg`. Fail lain (ARW dll.) sebagai `application/octet-stream` supaya nama fail asal tidak diubah oleh Android.
- Default (tiada folder dipilih, Android 10+): MediaStore → `Pictures/JS Tether`.
- Fail separuh siap dipadam kalau pemindahan gagal.

---

## 6. Build

### Pilihan A: Android Studio / Gradle (disyorkan)
Buka folder repo dalam Android Studio → **Build → Build APK(s)**. Atau:
```bash
gradle :app:assembleRelease
```
(AGP 8.5, compileSdk/targetSdk 34, Java 17. Release ditandatangan dengan debug key untuk sideload.)

### Pilihan B: GitHub Actions
Setiap push ke repo akan menjalankan `.github/workflows/build.yml`, dan APK boleh dimuat turun dari tab **Actions → Artifacts**.

### Pilihan C: `build-local.sh` (cara APK v1.3 dibina)
Tanpa Android SDK/Gradle, untuk persekitaran yang tidak dapat capai `dl.google.com`:
- `aapt2` (prebuilt dari repo Apktool) untuk manifest + resource
- `android.jar` API 33 (repo `Sable/android-platforms`)
- `javac -source 8 -target 8` → `dx` (dibina dari sumber AOSP `platform_dalvik`) → `classes.dex`
- `jarsigner` (v1 signature sahaja, sebab itu **targetSdk 29**)
- Sebab guna `dx`: **tiada lambda Java** dalam kod (dx tidak menyokong `invokedynamic`). Guna kelas anonymous.

> Keystore `release.keystore` **tidak** dimasukkan ke repo. APK baharu yang ditandatangan dengan kunci lain perlu **uninstall versi lama dulu** sebelum dipasang.

---

## 7. Had & idea seterusnya
- App mesti di skrin depan. Idea: tukar ke **Foreground Service** supaya terus jalan bila skrin dikunci.
- Hanya diuji dengan **JPEG**. RAW (ARW ~42 MB) sepatutnya jalan (data distrim terus ke fail), tetapi belum diuji.
- Kelajuan terhad oleh bacaan 16 KB. Boleh dipercepatkan guna `UsbRequest` (async, beberapa request serentak).
- Mod **MTP** (lihat gambar lama dalam kad) belum disokong. Hanya tether gambar baru.
- Belum ada pratonton (thumbnail) gambar terakhir.
- Giliran upload NAS hanya dalam memori: kalau app ditutup sebelum semua dihantar, baki gambar perlu dihantar manual (fail masih ada dalam telefon).
- SMB3 / encryption belum disokong. Password NAS tidak disulitkan dalam telefon.
- Kamera lain: Sony lain dalam PC Remote sepatutnya serasi (model baru mungkin perlu `0x9202` dengan `0x12C`). Canon/Nikon perlukan protokol event vendor sendiri.

## Rujukan
- libgphoto2 `camlibs/ptp2` (`ptp.c`, `library.c`): kod Sony SDIO dan `0xD215`
- PIMA 15740 / USB Still Image Capture Device Definition
- Analisis APK Piufoto v3.62.3 (`com.alltuu.android.piufoto`)
