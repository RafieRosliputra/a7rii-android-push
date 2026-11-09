# Delayed Tether (tarik dari SD)

## Kenapa bukan PTP
- A7R II dalam PC Remote: GetObjectHandles 0x1007 balik "USB baca gagal"; kamera tak dedah SD via PTP.
- Keputusan (Rafi): Delayed guna USB Connection = Mass Storage. Telefon baca SD sebagai Android
  storage melalui Storage Access Framework (SAF). Direct kekal PC Remote, tak disentuh.

## Reka bentuk
- DelayedActivity: pemilih ACTION_OPEN_DOCUMENT_TREE (hint: pilih DCIM). Ambil persistable read
  permission, simpan tree URI dalam SharedPreferences (`delayed_tree`, asing dari folder Direct).
- Butang "Tukar kad" buka semula pemilih. URI hilang kebenaran / kad tiada → mesej + pemilih.
- CameraBrowser: recursive `buildChildDocumentsUriUsingTree` (DOCUMENT_ID, DISPLAY_NAME, SIZE,
  LAST_MODIFIED, MIME_TYPE), tanpa DocumentFile. JPEG sahaja (.jpg/.jpeg), skip ARW, newest first,
  incremental/paged UI. Thumbnail EXIF malas (visible sahaja) + fallback inSampleSize ~320px,
  LRU memori + cacheDir, threadpool 3, batal bila scrolled away.

## Key & upload
- Key = `UploadQueue.keyFor` (name|size|EXIF time), satu sumber: badge UploadedStore + queue.add.
  queue.add return false (dah hantar/dalam giliran) → tandai badge.
- Upload: salin SAF → cacheDir/delayed-jpg/<hash>.jpg, queue dari fail cache (bukan SAF terus,
  kad boleh dicabut), padam selepas berjaya.

## Risiko
- exFAT dipasang hanya pada sesetengah telefon; kad NTFS/besar mungkin tak dibaca.
- Membaca EXIF bagi banyak fail di luar UI thread; senarai sangat besar tetap lambat.
