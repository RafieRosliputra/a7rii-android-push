# Delayed Tether (tarik dari SD)

## Reka bentuk
- Photog ambil gambar tanpa telefon (kamera simpan ke SD). Kemudian USB PC Remote (PTP).
- Mod Direct kekal (TetherService + live.johorstudio.com tak berubah). Mod Delayed: MainActivity buka
  PTP + handshake Sony, terus buka DelayedActivity. Pilihan mod disimpan dalam SharedPreferences (`mode`).
- DelayedActivity: grid thumbnail SD, badge "uploaded", multi-pilih, "Pilih semua baru", tapis
  semua|belum, Upload. Hanya JPEG dipilih dimuat turun penuh, WebP (PhotoEncoder) + UploadQueue seperti Direct.

## Operasi PTP
- GetStorageIDs 0x1004 → GetObjectHandles 0x1007 (semua format, parent 0xFFFFFFFF) → GetObjectInfo 0x1008
  (nama, saiz, capture date, format, thumb size). Hanya JPEG (format 0x3801 atau .JPG/.jpeg).
- GetThumb 0x100A (fallback: GetPartialObject 0x101B 64KB pertama + EXIF thumbnail, else placeholder).
- GetObject 0x1009 (penuh) untuk muat turun dipilih. `Ptp.transact` synchronized → satu transaksi pada satu masa;
  thumb dihenti sementara semasa muat turun.

## Format key (UploadedStore)
- `name|size|captureDate` (cth `DSC00001.JPG|24150000|2026-11-09T10:30:00`). Disimpan dalam `uploaded.json` (filesDir).

## Fail baharu
- Ptp (ops + browse helper), CameraBrowser, UploadedStore, DelayedActivity; UploadQueue + success hook `ref` (additive).

## Risiko / andaian
- ANDAIAN (belum disahkan): A7R II dedahkan SD dalam mod PC Remote PTP. Kalau 0 handle → mesej
  "Kamera tak dedah kad SD dalam PC Remote" + dump storageIDs/handleCount/ops ke log.
- GetThumb mungkin tiada dalam PC Remote; fallback EXIF/placeholder.
- Delayed guna UploadQueue sendiri (fail giliran sama dengan Direct). Jangan jalankan Direct serentak.
