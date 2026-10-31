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
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.text.InputType;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.IOException;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
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
    private CheckBox cbNas;
    private TextView tvNas, tvNasFolder, tvNasCount;
    private NasUploader uploader;
    private int nasUploadedCount = 0;

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
        startUploader();
        refreshNasLabels();
        if (cbNas.isChecked()) setNas(nasConfig().valid() ? "sedia (sambung bila ada gambar)" : "belum disetkan", false);
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

        root.addView(label("JS Tether", 24, true));
        TextView sub = label("v2.0 · Sony A7R II → telefon → TrueNAS", 12, false);
        sub.setTextColor(Color.GRAY);
        root.addView(sub);

        tvCamera = label("Kamera: tidak bersambung", 17, true);
        tvCamera.setTextColor(Color.rgb(255, 120, 120));
        tvPull = label("Status: -", 16, false);
        tvCount = label("Gambar disimpan: 0", 15, false);
        tvFolder = label("Folder telefon: -", 13, false);
        root.addView(tvCamera);
        root.addView(tvPull);
        root.addView(tvCount);
        root.addView(tvFolder);

        Button pick = new Button(this);
        pick.setText("Folder telefon");
        pick.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
                i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                        | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
                startActivityForResult(i, REQ_PICK_FOLDER);
            }
        });
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
        root.addView(row(pick, reconnect));

        // ---- TrueNAS section
        TextView nasTitle = label("TrueNAS (SMB melalui Tailscale)", 14, true);
        nasTitle.setTextColor(Color.rgb(120, 180, 255));
        nasTitle.setPadding(0, dp(14), 0, dp(2));
        root.addView(nasTitle);

        cbNas = new CheckBox(this);
        cbNas.setText("Hantar setiap gambar ke TrueNAS");
        cbNas.setTextColor(Color.WHITE);
        cbNas.setChecked(prefs.getBoolean("nas_on", false));
        cbNas.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(CompoundButton b, boolean on) {
                prefs.edit().putBoolean("nas_on", on).apply();
                if (on && !nasConfig().valid()) showNasSettings();
                refreshNasLabels();
            }
        });
        root.addView(cbNas);

        tvNas = label("NAS: -", 15, false);
        tvNasFolder = label("Folder NAS: -", 13, false);
        tvNasCount = label("Dihantar ke NAS: 0 · Giliran: 0", 13, false);
        root.addView(tvNas);
        root.addView(tvNasFolder);
        root.addView(tvNasCount);

        Button nasSet = new Button(this);
        nasSet.setText("Tetapan & sambung");
        nasSet.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { showNasSettings(); }
        });
        Button nasDir = new Button(this);
        nasDir.setText("Folder NAS");
        nasDir.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (!nasConfig().valid()) { showNasSettings(); return; }
                browseNas(nasConfig().dir);
            }
        });
        root.addView(row(nasSet, nasDir));

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

    private LinearLayout row(View a, View b) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(52), 1f);
        lp.setMargins(0, 0, dp(4), 0);
        r.addView(a, lp);
        LinearLayout.LayoutParams lp2 = new LinearLayout.LayoutParams(0, dp(52), 1f);
        lp2.setMargins(dp(4), 0, 0, 0);
        r.addView(b, lp2);
        return r;
    }

    private void ui(Runnable r) { runOnUiThread(r); }

    // ------------------------------------------------------------------ TrueNAS

    private NasUploader.Config nasConfig() {
        NasUploader.Config c = new NasUploader.Config();
        c.host = prefs.getString("nas_host", "");
        c.share = prefs.getString("nas_share", "");
        c.user = prefs.getString("nas_user", "");
        c.pass = prefs.getString("nas_pass", "");
        c.domain = prefs.getString("nas_domain", "");
        c.dir = prefs.getString("nas_dir", "");
        return c;
    }

    private void refreshNasLabels() {
        NasUploader.Config c = nasConfig();
        tvNasFolder.setText("Folder NAS: " + (c.valid()
                ? "\\\\" + c.host + "\\" + c.share + (c.dir.isEmpty() ? "" : "\\" + c.dir.replace('/', '\\'))
                : "belum disetkan"));
        if (!cbNas.isChecked()) setNas("dimatikan", false);
    }

    private void setNas(final String text, final boolean ok) {
        ui(new Runnable() { @Override public void run() {
            tvNas.setText("NAS: " + text);
            tvNas.setTextColor(ok ? Color.rgb(120, 230, 140) : Color.rgb(255, 190, 120));
        }});
    }

    private void updateNasCount() {
        ui(new Runnable() { @Override public void run() {
            tvNasCount.setText("Dihantar ke NAS: " + nasUploadedCount + " · Giliran: " + (uploader == null ? 0 : uploader.pending()));
        }});
    }

    private void startUploader() {
        uploader = new NasUploader(getContentResolver(), nasConfig(), new NasUploader.Listener() {
            @Override public void onNasStatus(String text, boolean ok) { setNas(text, ok); }
            @Override public void onNasUploaded(String name, int total) {
                nasUploadedCount = total;
                log("NAS ✓ " + name);
                updateNasCount();
            }
            @Override public void onNasQueue(int pending) { updateNasCount(); }
            @Override public void onNasLog(String s) { log(s); }
        });
        uploader.start();
    }

    private EditText field(LinearLayout parent, String hint, String value, boolean password) {
        TextView l = new TextView(this);
        l.setText(hint);
        l.setTextSize(12);
        parent.addView(l);
        EditText e = new EditText(this);
        e.setSingleLine(true);
        e.setText(value);
        if (password) e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        parent.addView(e);
        return e;
    }

    private void showNasSettings() {
        NasUploader.Config c = nasConfig();
        ScrollView sv = new ScrollView(this);
        LinearLayout f = new LinearLayout(this);
        f.setOrientation(LinearLayout.VERTICAL);
        f.setPadding(dp(20), dp(8), dp(20), dp(8));
        sv.addView(f);
        final EditText eHost = field(f, "IP Tailscale / nama TrueNAS (cth 100.101.102.103 atau truenas)", c.host, false);
        final EditText eShare = field(f, "Nama share SMB (cth photos)", c.share, false);
        final EditText eUser = field(f, "Username TrueNAS", c.user, false);
        final EditText ePass = field(f, "Password", c.pass, true);
        final EditText eDom = field(f, "Domain / workgroup (biasanya kosong)", c.domain, false);

        new AlertDialog.Builder(this)
                .setTitle("Tetapan TrueNAS")
                .setView(sv)
                .setPositiveButton("Simpan & sambung", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        prefs.edit()
                                .putString("nas_host", eHost.getText().toString().trim())
                                .putString("nas_share", eShare.getText().toString().trim())
                                .putString("nas_user", eUser.getText().toString().trim())
                                .putString("nas_pass", ePass.getText().toString())
                                .putString("nas_domain", eDom.getText().toString().trim())
                                .apply();
                        refreshNasLabels();
                        uploader.setConfig(nasConfig());
                        testNas();
                    }
                })
                .setNegativeButton("Batal", null)
                .show();
    }

    private interface SmbTask { Object run(SmbClient s) throws Exception; }
    private interface SmbDone { void done(Object result); }

    /** Run something with a fresh SMB connection on a background thread; result delivered on UI thread. */
    private void withNas(final String what, final SmbTask task, final SmbDone done) {
        final NasUploader.Config c = nasConfig();
        if (!c.valid()) { toast("Isi tetapan TrueNAS dulu"); return; }
        setNas(what + "…", true);
        new Thread(new Runnable() {
            @Override public void run() {
                SmbClient s = new SmbClient(c.host, c.share, c.user, c.pass, c.domain);
                s.setLogger(new SmbClient.Logger() { @Override public void log(String m) { MainActivity.this.log(m); } });
                try {
                    s.connect();
                    final Object res = task.run(s);
                    ui(new Runnable() { @Override public void run() { done.done(res); } });
                } catch (final Exception e) {
                    log("NAS: " + e.getMessage());
                    setNas("ralat: " + e.getMessage(), false);
                    toast("TrueNAS: " + e.getMessage());
                } finally {
                    s.close();
                }
            }
        }).start();
    }

    private void testNas() {
        withNas("menguji sambungan", new SmbTask() {
            @Override public Object run(SmbClient s) throws Exception { return s.list("").size(); }
        }, new SmbDone() {
            @Override public void done(Object r) {
                setNas("bersambung ✓ (" + r + " item dalam share)", true);
                toast("Berjaya sambung ke TrueNAS");
                if (prefs.getString("nas_dir", "").isEmpty()) browseNas("");
            }
        });
    }

    private static String parentOf(String dir) {
        String d = dir.replace('\\', '/');
        int i = d.lastIndexOf('/');
        return i <= 0 ? "" : d.substring(0, i);
    }

    private static String childOf(String dir, String name) {
        return dir.isEmpty() ? name : dir + "/" + name;
    }

    /** Folder browser on the NAS: open sub-folders, go up, create a new folder, choose. */
    private void browseNas(final String dir) {
        withNas("membaca folder", new SmbTask() {
            @Override public Object run(SmbClient s) throws Exception { return s.list(dir); }
        }, new SmbDone() {
            @SuppressWarnings("unchecked")
            @Override public void done(Object r) {
                setNas("bersambung ✓", true);
                final List<String> folders = new ArrayList<>();
                for (SmbClient.Entry e : (List<SmbClient.Entry>) r) if (e.dir && !e.name.startsWith(".")) folders.add(e.name);
                Collections.sort(folders, String.CASE_INSENSITIVE_ORDER);
                final boolean hasUp = !dir.isEmpty();
                final List<String> items = new ArrayList<>();
                if (hasUp) items.add("⬆  ..");
                for (String n : folders) items.add("📁  " + n);
                if (folders.isEmpty()) items.add("(tiada sub-folder)");

                new AlertDialog.Builder(MainActivity.this)
                        .setTitle("\\" + nasConfig().share + (dir.isEmpty() ? "" : "\\" + dir.replace('/', '\\')))
                        .setItems(items.toArray(new String[0]), new DialogInterface.OnClickListener() {
                            @Override public void onClick(DialogInterface d, int which) {
                                if (hasUp && which == 0) { browseNas(parentOf(dir)); return; }
                                int idx = which - (hasUp ? 1 : 0);
                                if (idx >= 0 && idx < folders.size()) browseNas(childOf(dir, folders.get(idx)));
                                else browseNas(dir);
                            }
                        })
                        .setPositiveButton("Pilih folder ini", new DialogInterface.OnClickListener() {
                            @Override public void onClick(DialogInterface d, int w) {
                                prefs.edit().putString("nas_dir", dir).apply();
                                refreshNasLabels();
                                uploader.setConfig(nasConfig());
                                log("Folder NAS dipilih: \\" + nasConfig().share + "\\" + dir.replace('/', '\\'));
                                if (!cbNas.isChecked()) cbNas.setChecked(true);
                            }
                        })
                        .setNeutralButton("Folder baru", new DialogInterface.OnClickListener() {
                            @Override public void onClick(DialogInterface d, int w) { newNasFolder(dir); }
                        })
                        .setNegativeButton("Batal", null)
                        .show();
            }
        });
    }

    private void newNasFolder(final String dir) {
        final EditText e = new EditText(this);
        e.setSingleLine(true);
        e.setHint("cth Konvo Tadika 2026");
        e.setText(new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date()) + " ");
        e.setSelection(e.getText().length());
        LinearLayout box = new LinearLayout(this);
        box.setPadding(dp(20), dp(8), dp(20), 0);
        box.addView(e, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        new AlertDialog.Builder(this)
                .setTitle("Folder baru dalam \\" + (dir.isEmpty() ? nasConfig().share : dir.replace('/', '\\')))
                .setView(box)
                .setPositiveButton("Cipta", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        final String name = e.getText().toString().trim().replaceAll("[\\\\/:*?\"<>|]", "-");
                        if (name.isEmpty()) { browseNas(dir); return; }
                        final String path = childOf(dir, name);
                        withNas("mencipta folder", new SmbTask() {
                            @Override public Object run(SmbClient s) throws Exception { return s.mkdir(path); }
                        }, new SmbDone() {
                            @Override public void done(Object r) {
                                log(Boolean.TRUE.equals(r) ? "Folder NAS dicipta: " + name : "Folder sudah wujud: " + name);
                                browseNas(path);
                            }
                        });
                    }
                })
                .setNegativeButton("Batal", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) { browseNas(dir); }
                })
                .show();
    }

    private void toast(final String s) {
        ui(new Runnable() { @Override public void run() { Toast.makeText(MainActivity.this, s, Toast.LENGTH_LONG).show(); } });
    }

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
        tvFolder.setText("Folder telefon: " + (t != null ? prettyTree(t)
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
        if (uploader != null) uploader.shutdown();
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
            if (cbNas.isChecked() && uploader != null) {
                uploader.enqueue(t.uri, fname, size);
                updateNasCount();
            }
            return true;
        }
    }

    private static String mb(long b) { return String.format(Locale.US, "%.1f MB", b / 1048576.0); }
}
