package com.johorstudio.tether;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.DocumentsContract;
import android.provider.MediaStore;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.IOException;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.Locale;

public class MainActivity extends Activity {
    private static final String ACTION_USB_PERMISSION = "com.johorstudio.tether.USB_PERMISSION";
    private static final int REQ_PICK_FOLDER = 42;
    private static final int SONY_IN_MEMORY_HANDLE = 0xFFFFC001;

    private UsbManager usb;
    private SharedPreferences prefs;
    private TextView tvCamera, tvPull, tvFolder, tvCount, tvLog;
    private ScrollView logScroll;
    private final ArrayDeque<String> logLines = new ArrayDeque<>();
    private volatile Worker worker;
    private int savedCount = 0;

    // ------------------------------------------------------------------ UI

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        usb = (UsbManager) getSystemService(Context.USB_SERVICE);
        prefs = getSharedPreferences("tether", MODE_PRIVATE);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        buildUi();

        IntentFilter f = new IntentFilter();
        f.addAction(ACTION_USB_PERMISSION);
        f.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        f.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(usbReceiver, f, Context.RECEIVER_EXPORTED);
        else registerReceiver(usbReceiver, f);

        refreshFolderLabel();
        handleIntent(getIntent());
    }

    private TextView label(String text, float size, boolean bold) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(size);
        t.setTextColor(Color.WHITE);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setPadding(0, dp(6), 0, dp(6));
        return t;
    }

    private int dp(int v) { return (int) (v * getResources().getDisplayMetrics().density + 0.5f); }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.rgb(18, 18, 20));
        root.setPadding(dp(20), dp(32), dp(20), dp(20));

        root.addView(label("JS Tether", 26, true));
        TextView sub = label("v1.3 · Sony A7R II → telefon (USB, mod PC Remote)", 13, false);
        sub.setTextColor(Color.GRAY);
        root.addView(sub);

        tvCamera = label("Kamera: tidak bersambung", 18, true);
        tvCamera.setTextColor(Color.rgb(255, 120, 120));
        tvPull = label("Status: -", 18, false);
        tvCount = label("Gambar disimpan: 0", 16, false);
        tvFolder = label("Folder: -", 14, false);
        root.addView(tvCamera);
        root.addView(tvPull);
        root.addView(tvCount);
        root.addView(tvFolder);

        Button pick = new Button(this);
        pick.setText("Pilih folder simpan");
        pick.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
                i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                        | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
                startActivityForResult(i, REQ_PICK_FOLDER);
            }
        });
        root.addView(pick, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56)));

        Button reconnect = new Button(this);
        reconnect.setText("Sambung semula kamera");
        reconnect.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                final Worker old = worker;
                stopWorker();
                new Thread(new Runnable() {
                    @Override public void run() {
                        if (old != null) try { old.join(3000); } catch (InterruptedException ignored) {}
                        ui(new Runnable() { @Override public void run() { findAndConnect(); } });
                    }
                }).start();
            }
        });
        root.addView(reconnect, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56)));

        TextView logTitle = label("Log", 13, true);
        logTitle.setTextColor(Color.GRAY);
        root.addView(logTitle);

        logScroll = new ScrollView(this);
        tvLog = new TextView(this);
        tvLog.setTextSize(11);
        tvLog.setTypeface(Typeface.MONOSPACE);
        tvLog.setTextColor(Color.rgb(170, 170, 170));
        logScroll.addView(tvLog);
        root.addView(logScroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        setContentView(root);
    }

    private void ui(Runnable r) { runOnUiThread(r); }

    private void setCamera(final String text, final boolean ok) {
        ui(new Runnable() { @Override public void run() {
            tvCamera.setText("Kamera: " + text);
            tvCamera.setTextColor(ok ? Color.rgb(120, 230, 140) : Color.rgb(255, 120, 120));
        }});
    }

    private void setPull(final String text) {
        ui(new Runnable() { @Override public void run() { tvPull.setText("Status: " + text); } });
    }

    private void log(String s) {
        final String line = new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date()) + "  " + s;
        ui(new Runnable() { @Override public void run() {
            logLines.addLast(line);
            while (logLines.size() > 200) logLines.removeFirst();
            StringBuilder sb = new StringBuilder();
            for (String l : logLines) sb.append(l).append('\n');
            tvLog.setText(sb);
            logScroll.post(new Runnable() { @Override public void run() { logScroll.fullScroll(ScrollView.FOCUS_DOWN); } });
        }});
    }

    // ------------------------------------------------------------------ folder

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == REQ_PICK_FOLDER && res == RESULT_OK && data != null && data.getData() != null) {
            Uri tree = data.getData();
            try {
                getContentResolver().takePersistableUriPermission(tree,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            } catch (Exception e) { log("Gagal simpan kebenaran folder: " + e.getMessage()); }
            prefs.edit().putString("tree", tree.toString()).apply();
            refreshFolderLabel();
            log("Folder dipilih: " + prettyTree(tree));
        }
    }

    private Uri treeUri() {
        String s = prefs.getString("tree", null);
        return s == null ? null : Uri.parse(s);
    }

    private String prettyTree(Uri tree) {
        try {
            String id = DocumentsContract.getTreeDocumentId(tree);
            if (id.startsWith("primary:")) return "Storan dalaman/" + id.substring(8);
            return id.replace(':', '/');
        } catch (Exception e) { return tree.toString(); }
    }

    private void refreshFolderLabel() {
        Uri t = treeUri();
        tvFolder.setText("Folder: " + (t != null ? prettyTree(t)
                : (Build.VERSION.SDK_INT >= 29 ? "Pictures/JS Tether (default)" : "belum dipilih")));
    }

    private static String mimeFor(String name) {
        String n = name.toLowerCase(Locale.US);
        if (n.endsWith(".jpg") || n.endsWith(".jpeg")) return "image/jpeg";
        if (n.endsWith(".heif") || n.endsWith(".hif")) return "image/heif";
        return "application/octet-stream"; // ARW etc: keep exact file name
    }

    private static class Target { Uri uri; OutputStream os; }

    private Target openTarget(String name) throws IOException {
        ContentResolver cr = getContentResolver();
        Target t = new Target();
        Uri tree = treeUri();
        if (tree != null) {
            Uri dir = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree));
            t.uri = DocumentsContract.createDocument(cr, dir, mimeFor(name), name);
            if (t.uri == null) throw new IOException("Gagal cipta fail dalam folder (pilih folder semula?)");
        } else if (Build.VERSION.SDK_INT >= 29) {
            ContentValues v = new ContentValues();
            v.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
            String mime = mimeFor(name);
            v.put(MediaStore.MediaColumns.MIME_TYPE, mime.startsWith("image/") ? mime : "image/x-sony-arw");
            v.put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/JS Tether");
            t.uri = cr.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v);
            if (t.uri == null) throw new IOException("MediaStore insert gagal");
        } else {
            throw new IOException("Sila pilih folder simpan dulu");
        }
        t.os = cr.openOutputStream(t.uri, "w");
        if (t.os == null) throw new IOException("Tidak dapat buka fail untuk ditulis");
        return t;
    }

    private void deleteTarget(Target t) {
        try {
            if (t.uri == null) return;
            if (treeUri() != null) DocumentsContract.deleteDocument(getContentResolver(), t.uri);
            else getContentResolver().delete(t.uri, null, null);
        } catch (Exception ignored) {}
    }

    // ------------------------------------------------------------------ USB

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handleIntent(intent);
    }

    private void handleIntent(Intent intent) {
        UsbDevice d = intent == null ? null : intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
        if (d != null) connect(d); else findAndConnect();
    }

    private void findAndConnect() {
        if (worker != null) return;
        for (UsbDevice d : usb.getDeviceList().values()) {
            if (Ptp.findPtpInterface(d) != null) { connect(d); return; }
        }
        setCamera("tidak bersambung", false);
        setPull("Sambungkan kamera dengan kabel USB-C/OTG");
    }

    private void connect(UsbDevice d) {
        if (worker != null) return;
        if (!usb.hasPermission(d)) {
            setCamera("meminta kebenaran USB…", false);
            Intent i = new Intent(ACTION_USB_PERMISSION).setPackage(getPackageName());
            int flags = Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0;
            usb.requestPermission(d, PendingIntent.getBroadcast(this, 0, i, flags));
            return;
        }
        worker = new Worker(d);
        worker.start();
    }

    private void stopWorker() {
        Worker w = worker;
        worker = null;
        if (w != null) w.shutdown();
    }

    private final BroadcastReceiver usbReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            UsbDevice d = i.getParcelableExtra(UsbManager.EXTRA_DEVICE);
            String a = i.getAction();
            if (ACTION_USB_PERMISSION.equals(a)) {
                if (d != null && i.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) connect(d);
                else { setCamera("kebenaran USB ditolak", false); log("Kebenaran USB ditolak"); }
            } else if (UsbManager.ACTION_USB_DEVICE_DETACHED.equals(a)) {
                Worker w = worker;
                if (w != null && d != null && d.getDeviceName().equals(w.dev.getDeviceName())) {
                    log("Kamera dicabut");
                    stopWorker();
                    setCamera("tidak bersambung", false);
                    setPull("-");
                }
            } else if (UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(a)) {
                if (d != null && worker == null) connect(d);
            }
        }
    };

    @Override
    protected void onDestroy() {
        stopWorker();
        try { unregisterReceiver(usbReceiver); } catch (Exception ignored) {}
        super.onDestroy();
    }

    // ------------------------------------------------------------------ worker

    private class Worker extends Thread {
        final UsbDevice dev;
        volatile boolean running = true;
        Ptp ptp;
        final LinkedHashSet<String> recent = new LinkedHashSet<>();

        Worker(UsbDevice d) { dev = d; setName("ptp-worker"); }

        void shutdown() {
            running = false;
            interrupt();
        }

        @Override public void run() {
            try {
                setCamera("menyambung…", false);
                ptp = Ptp.open(usb, dev);
                ptp.setLogger(new Ptp.Logger() { @Override public void log(String s) { MainActivity.this.log(s); } });
                log(String.format("USB %04X:%04X %s", dev.getVendorId(), dev.getProductId(), ptp.describe()));
                log(String.format("Device status: 0x%04X", ptp.getDeviceStatus()));
                ptp.drain();
                Ptp.DeviceInfo di = null;
                for (int attempt = 1; attempt <= 3 && running; attempt++) {
                    try { di = ptp.getDeviceInfo(); break; }
                    catch (IOException e) {
                        log("Cubaan " + attempt + ": " + e.getMessage() + " → reset USB");
                        ptp.resetDevice();
                    }
                }
                if (di == null) throw new IOException("Kamera tidak membalas. Matikan & hidupkan kamera, kemudian cuba lagi");
                String name = (di.manufacturer + " " + di.model).trim();
                if (name.isEmpty()) name = dev.getProductName() != null ? dev.getProductName() : "Kamera";
                log("Dijumpai: " + name + " (ext 0x" + Integer.toHexString(di.vendorExtId) + " " + di.vendorExtDesc + ")");
                try { ptp.openSession(); }
                catch (IOException e) {
                    log("OpenSession: " + e.getMessage() + " → reset & cuba lagi");
                    ptp.resetDevice();
                    ptp.openSession();
                }
                boolean sony = di.supportsOp(Ptp.OC_Sony_SDIOConnect);
                if (sony) {
                    log("Mod PC Remote dikesan, handshake Sony…");
                    try { ptp.sonyHandshake(); }
                    catch (IOException e) { log("Handshake Sony: " + e.getMessage()); }
                    setCamera(name + " ✓ (PC Remote)", true);
                } else {
                    setCamera(name + " ✓ (MTP/PTP)", true);
                    if (dev.getVendorId() == 0x054C)
                        log("Kamera dalam mod MTP. Untuk tether: Menu → USB Connection → PC Remote");
                }
                setPull("Menunggu gambar… (tekan shutter)");

                boolean pending = false;
                long pendingSince = 0, lastPoll = 0;
                int errors = 0;
                while (running) {
                    Ptp.Event e = ptp.pollEvent(300);
                    long now = System.currentTimeMillis();
                    if (e != null) {
                        int p0 = e.params.length > 0 ? e.params[0] : 0;
                        if (e.code != 0xC203) log(String.format("Event 0x%04X p=0x%08X", e.code, p0));
                        if (sony && (e.code == Ptp.EC_Sony_ObjectAdded || e.code == Ptp.EC_ObjectAdded)) {
                            pending = true; pendingSince = now;
                            setPull("Kamera sedang proses gambar…");
                        } else if (!sony && e.code == Ptp.EC_ObjectAdded) {
                            try { pull(p0, false); errors = 0; }
                            catch (IOException ex) { errors = onImageError(ex, errors); }
                        }
                    }
                    if (sony && (pending || now - lastPoll > 1500)) {
                        lastPoll = now;
                        try {
                            int v = ptp.sonyObjectInMemory();
                            if (v > 0x8000) {
                                log(String.format("ObjectInMemory 0x%04X → tarik", v));
                                drainSony();
                                pending = false;
                            } else if (pending && v < 0 && now - pendingSince > 1500) {
                                log("ObjectInMemory tak dapat dibaca, cuba tarik terus");
                                drainSony();
                                pending = false;
                            } else if (pending && now - pendingSince > 30000) {
                                log("Tiada gambar dalam buffer kamera selepas 30 s (semak Still Img. Save Dest.)");
                                pending = false;
                                setPull("Menunggu gambar… (tekan shutter)");
                            }
                            errors = 0;
                        } catch (IOException ex) {
                            errors = onImageError(ex, errors);
                        }
                    }
                }
            } catch (Exception ex) {
                if (running) {
                    log("Ralat: " + ex.getMessage());
                    setCamera("ralat sambungan", false);
                    setPull("Cabut & sambung semula kamera, atau tekan 'Sambung semula'");
                }
            } finally {
                if (ptp != null) { if (running) ptp.closeSession(); ptp.close(); }
                if (worker == this) worker = null;
            }
        }

        /** One failed image must not kill the session; only give up after repeated failures. */
        private int onImageError(IOException ex, int errors) throws IOException {
            errors++;
            log("Ralat gambar (" + errors + "): " + ex.getMessage());
            ptp.drain();
            setPull("Ralat semasa tarik, menunggu gambar seterusnya…");
            if (errors >= 5) throw new IOException("Terlalu banyak ralat berturut-turut");
            return errors;
        }

        /** Sony keeps captured images in a queue at handle 0xFFFFC001; pull until it is empty. */
        private void drainSony() throws IOException {
            for (int i = 0; i < 20 && running; i++) {
                if (!pull(SONY_IN_MEMORY_HANDLE, true)) break;
                int v = ptp.sonyObjectInMemory();
                if (v >= 0 && v <= 0x8000) break;
            }
            setPull("Menunggu gambar… (tekan shutter)");
        }

        /** @return true if a new file was saved */
        private boolean pull(int handle, boolean sonyQueue) throws IOException {
            Ptp.ObjectInfo oi = null;
            for (int attempt = 0; attempt < (sonyQueue ? 5 : 2); attempt++) {
                try { oi = ptp.getObjectInfo(handle); break; }
                catch (IOException e) {
                    if (!sonyQueue) { log(e.getMessage()); return false; }
                    try { Thread.sleep(150); } catch (InterruptedException ie) { return false; }
                }
            }
            if (oi == null) return false;
            if (oi.format == 0x3001) return false; // folder
            String fname = oi.filename.isEmpty() ? String.format("IMG_%08X.JPG", handle) : oi.filename;
            if (!fname.matches("[A-Za-z0-9._ -]{1,64}"))
                throw new IOException("Maklumat gambar rosak (nama fail tidak sah), cuba lagi");
            String key = fname + "|" + oi.size;
            if (recent.contains(key)) return false;

            long size = oi.size & 0xFFFFFFFFL;
            setPull("Menarik " + fname + " (" + mb(size) + ")…");
            log("Menarik " + fname + " " + mb(size));
            long t0 = System.currentTimeMillis();
            Target t = openTarget(fname);
            final String fn = fname;
            final long[] lastUi = {0};
            Ptp.Response r;
            try {
                r = ptp.getObject(handle, t.os, new Ptp.Progress() {
                    @Override public void onProgress(long done, long total) {
                        long now = System.currentTimeMillis();
                        if (now - lastUi[0] > 200) {
                            lastUi[0] = now;
                            int pct = total > 0 ? (int) (done * 100 / total) : 0;
                            setPull("Menarik " + fn + " " + pct + "%");
                        }
                    }
                });
                t.os.close();
            } catch (IOException ex) {
                try { t.os.close(); } catch (Exception ignored) {}
                deleteTarget(t);
                throw ex;
            }
            if (!r.ok()) {
                deleteTarget(t);
                log(String.format("GetObject gagal 0x%04X", r.code));
                return false;
            }
            recent.add(key);
            if (recent.size() > 100) recent.remove(recent.iterator().next());
            double secs = Math.max(0.001, (System.currentTimeMillis() - t0) / 1000.0);
            savedCount++;
            final int c = savedCount;
            ui(new Runnable() { @Override public void run() { tvCount.setText("Gambar disimpan: " + c); } });
            setPull("Siap: " + fname);
            log(String.format(Locale.US, "Disimpan %s (%.1f s, %.1f MB/s)", fname, secs, size / 1048576.0 / secs));
            return true;
        }
    }

    private static String mb(long b) { return String.format(Locale.US, "%.1f MB", b / 1048576.0); }
}
