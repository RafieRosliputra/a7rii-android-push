package com.johorstudio.tether;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.DocumentsContract;
import android.provider.MediaStore;

import java.io.IOException;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.Locale;

/**
 * Foreground service: keeps tethering, saving and uploading while the app is in the background
 * or the screen is off (Rafi #36). Pipeline per shot: pull from camera -> save JPEG in the phone
 * folder (always) -> WebP -> upload to the event's Photographer N folder.
 */
public class TetherService extends Service {
    public static final String ACTION_CONNECT = "com.johorstudio.tether.CONNECT";
    public static final String ACTION_STOP = "com.johorstudio.tether.STOP";
    public static final String EXTRA_DEVICE = "device";
    private static final String CHANNEL = "tether";
    private static final int NOTIF_ID = 1;
    private static final int SONY_IN_MEMORY_HANDLE = 0xFFFFC001;

    public interface Listener { void onChanged(); void onLog(String line); }

    private static volatile TetherService instance;
    private static volatile Listener listener;
    public static TetherService get() { return instance; }
    public static void setListener(Listener l) { listener = l; }

    // status (read by the activity)
    public volatile String camera = "disconnected", stage = "-", server = "-";
    public volatile boolean cameraOk, serverOk;
    public volatile int saved, uploaded, pending;
    public final ArrayDeque<String> logs = new ArrayDeque<>();

    private UsbManager usb;
    private SharedPreferences prefs;
    private volatile Worker worker;
    private UploadQueue queue;
    private PowerManager.WakeLock wake;
    private final Handler main = new Handler(Looper.getMainLooper());
    private ConnectivityManager.NetworkCallback netCb;

    public static void start(Context c, UsbDevice d) {
        Intent i = new Intent(c, TetherService.class).setAction(ACTION_CONNECT);
        if (d != null) i.putExtra(EXTRA_DEVICE, d);
        if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i); else c.startService(i);
    }

    @Override public void onCreate() {
        super.onCreate();
        instance = this;
        usb = (UsbManager) getSystemService(Context.USB_SERVICE);
        prefs = getSharedPreferences("tether", MODE_PRIVATE);
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(CHANNEL, "JS Live tether", NotificationManager.IMPORTANCE_LOW);
            ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).createNotificationChannel(ch);
        }
        goForeground();
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "JSTether:tether");
        wake.setReferenceCounted(false);
        wake.acquire();
        queue = new UploadQueue(this, App.api(this), new UploadQueue.Listener() {
            @Override public void onQueue(int p, int up) { pending = p; uploaded = up; changed(); }
            @Override public void onServer(String text, boolean ok) { server = text; serverOk = ok; changed(); }
            @Override public void onStage(String text) { stage = text; changed(); }
            @Override public void onLog(String s) { log(s); }
        });
        queue.start();
        pending = queue.pending();
        server = App.token(this) == null ? "not logged in" : "ready";
        // Wake the queue up as soon as a network comes back.
        if (Build.VERSION.SDK_INT >= 24) {
            netCb = new ConnectivityManager.NetworkCallback() {
                @Override public void onAvailable(Network n) { queue.kick(); }
            };
            try { ((ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE)).registerDefaultNetworkCallback(netCb); }
            catch (Exception ignored) {}
        }
    }

    private void goForeground() {
        Notification n = notification();
        if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE | ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        else startForeground(NOTIF_ID, n);
    }

    private Notification notification() {
        Intent open = new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pi = PendingIntent.getActivity(this, 0, open, Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0);
        Intent stop = new Intent(this, TetherService.class).setAction(ACTION_STOP);
        PendingIntent ps = PendingIntent.getService(this, 1, stop, Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0);
        Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, CHANNEL) : new Notification.Builder(this);
        String ev = prefs.getString("event_title", "");
        b.setSmallIcon(android.R.drawable.ic_menu_camera)
                .setContentTitle("JS Live · " + saved + " saved · " + pending + " queued")
                .setContentText((cameraOk ? "Camera ✓" : "Camera ✗") + " · " + (ev.isEmpty() ? "no event" : ev))
                .setContentIntent(pi)
                .setOngoing(true)
                .addAction(new Notification.Action.Builder(null, "Stop", ps).build());
        return b.build();
    }

    private long lastNotif;
    private void changed() {
        Listener l = listener;
        if (l != null) main.post(new Runnable() { @Override public void run() { Listener x = listener; if (x != null) x.onChanged(); } });
        long now = System.currentTimeMillis();
        if (now - lastNotif > 1000) {
            lastNotif = now;
            ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).notify(NOTIF_ID, notification());
        }
    }

    public void log(String s) {
        final String line = new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date()) + "  " + s;
        synchronized (logs) {
            logs.addLast(line);
            while (logs.size() > 300) logs.removeFirst();
        }
        main.post(new Runnable() { @Override public void run() { Listener x = listener; if (x != null) x.onLog(line); } });
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopWorker();
            stopForeground(true);
            stopSelf();
            return START_NOT_STICKY;
        }
        UsbDevice d = intent == null ? null : (UsbDevice) intent.getParcelableExtra(EXTRA_DEVICE);
        if (d != null) connect(d); else findAndConnect();
        return START_STICKY;
    }

    @Override public IBinder onBind(Intent i) { return null; }

    @Override public void onDestroy() {
        stopWorker();
        if (queue != null) queue.shutdown();
        if (netCb != null) try { ((ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE)).unregisterNetworkCallback(netCb); } catch (Exception ignored) {}
        if (wake != null && wake.isHeld()) wake.release();
        instance = null;
        changed();
        super.onDestroy();
    }

    /** Login changed (token / server URL): give the queue a new client. */
    public void apiChanged() {
        queue.setApi(App.api(this));
        server = App.token(this) == null ? "not logged in" : "ready";
        changed();
    }
    public int clearQueue() { return queue.clear(); }
    public java.util.List<String[]> uploadRows() { return queue.rows(); }

    /**
     * Manual upload: photos copied from the camera to the phone, picked by the photographer.
     * Same path as tethered photos (WebP, then upload). Photos already sent are skipped.
     */
    public void addManual(final java.util.List<Uri> uris) {
        new Thread(new Runnable() { @Override public void run() {
            String eventId = prefs.getString("event_id", "");
            if (eventId.isEmpty()) { log("Log in with the photographer code first"); return; }
            java.util.List<Uri> ok = new java.util.ArrayList<>();
            java.util.List<String> names = new java.util.ArrayList<>(), keys = new java.util.ArrayList<>();
            for (Uri u : uris) {
                String name = "photo.jpg"; long size = -1, mod = 0; String type = getContentResolver().getType(u);
                android.database.Cursor c = null;
                try {
                    c = getContentResolver().query(u, null, null, null, null);
                    if (c != null && c.moveToFirst()) {
                        int i = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME); if (i >= 0 && !c.isNull(i)) name = c.getString(i);
                        i = c.getColumnIndex(android.provider.OpenableColumns.SIZE); if (i >= 0 && !c.isNull(i)) size = c.getLong(i);
                        i = c.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED); if (i >= 0 && !c.isNull(i)) mod = c.getLong(i);
                    }
                } catch (Exception ignored) {
                } finally { if (c != null) c.close(); }
                boolean jpg = (type != null && type.equals("image/jpeg")) || name.toLowerCase(Locale.US).matches(".*\\.jpe?g$");
                if (!jpg) { log(name + " is not JPEG, not sent"); continue; }
                ok.add(u); names.add(name); keys.add(UploadQueue.keyFor(getContentResolver(), u, name, size, mod));
            }
            java.util.Set<String> sent = new java.util.HashSet<>();
            try { if (!keys.isEmpty()) sent = App.api(TetherService.this).sentKeys(keys); }
            catch (Exception e) { log("Cannot check the server now, will check during upload"); }
            int added = 0, skipped = 0;
            for (int i = 0; i < ok.size(); i++) {
                if (sent.contains(keys.get(i))) { queue.markSent(names.get(i), keys.get(i)); skipped++; continue; }
                if (queue.add(ok.get(i), names.get(i), eventId, prefs.getInt("quality", 50), keys.get(i))) added++; else skipped++;
            }
            log("Manual upload: " + added + " queued" + (skipped > 0 ? ", " + skipped + " already there (skipped)" : ""));
            changed();
        }}).start();
    }
    public void retryNow() { queue.kick(); }

    // ------------------------------------------------------------------ camera

    public void findAndConnect() {
        if (worker != null) return;
        for (UsbDevice d : usb.getDeviceList().values()) {
            if (Ptp.findPtpInterface(d) != null) { connect(d); return; }
        }
        setCamera("disconnected", false);
        stage = "Connect the camera with a USB-C/OTG cable";
        changed();
    }

    public void connect(UsbDevice d) {
        if (worker != null) return;
        if (!usb.hasPermission(d)) { setCamera("no USB permission", false); return; }
        worker = new Worker(d);
        worker.start();
    }

    public void reconnect() {
        final Worker old = worker;
        stopWorker();
        new Thread(new Runnable() { @Override public void run() {
            if (old != null) try { old.join(3000); } catch (InterruptedException ignored) {}
            main.post(new Runnable() { @Override public void run() { findAndConnect(); } });
        }}).start();
    }

    public void detached(UsbDevice d) {
        Worker w = worker;
        if (w != null && d != null && d.getDeviceName().equals(w.dev.getDeviceName())) {
            log("Camera unplugged");
            stopWorker();
            setCamera("disconnected", false);
            stage = "-";
            changed();
        }
    }

    private void stopWorker() {
        Worker w = worker;
        worker = null;
        if (w != null) w.shutdown();
    }

    private void setCamera(String text, boolean ok) { camera = text; cameraOk = ok; changed(); }
    private void setStage(String text) { stage = text; changed(); }

    // ------------------------------------------------------------------ phone folder

    private static String mimeFor(String name) {
        String n = name.toLowerCase(Locale.US);
        if (n.endsWith(".jpg") || n.endsWith(".jpeg")) return "image/jpeg";
        if (n.endsWith(".heif") || n.endsWith(".hif")) return "image/heif";
        return "application/octet-stream";
    }

    private static class Target { Uri uri; OutputStream os; }

    private Target openTarget(String name) throws IOException {
        ContentResolver cr = getContentResolver();
        Target t = new Target();
        String s = prefs.getString("tree", null);
        Uri tree = s == null ? null : Uri.parse(s);
        if (tree != null) {
            Uri dir = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree));
            t.uri = DocumentsContract.createDocument(cr, dir, mimeFor(name), name);
            if (t.uri == null) throw new IOException("Failed to create file in folder (choose the folder again?)");
        } else if (Build.VERSION.SDK_INT >= 29) {
            ContentValues v = new ContentValues();
            v.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
            String mime = mimeFor(name);
            v.put(MediaStore.MediaColumns.MIME_TYPE, mime.startsWith("image/") ? mime : "image/x-sony-arw");
            String ev = prefs.getString("event_folder", "");
            v.put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/JS Tether" + (ev.isEmpty() ? "" : "/" + ev.replaceAll("[\\\\/:*?\"<>|]", "-")));
            t.uri = cr.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v);
            if (t.uri == null) throw new IOException("MediaStore insert failed");
        } else {
            throw new IOException("Please choose a save folder first");
        }
        t.os = cr.openOutputStream(t.uri, "w");
        if (t.os == null) throw new IOException("Cannot open file for writing");
        return t;
    }

    private void deleteTarget(Target t) {
        try {
            if (t.uri == null) return;
            if (prefs.getString("tree", null) != null) DocumentsContract.deleteDocument(getContentResolver(), t.uri);
            else getContentResolver().delete(t.uri, null, null);
        } catch (Exception ignored) {}
    }

    /** A new photo is safely in the phone: queue it for the server if an event is chosen. */
    private void saved(Uri uri, String fname) {
        String eventId = prefs.getString("event_id", "");
        boolean jpg = fname.toLowerCase(Locale.US).matches(".*\\.jpe?g$");
        if (eventId.isEmpty()) { log("No event selected: " + fname + " saved on the phone only"); return; }
        if (!prefs.getBoolean("upload_on", true)) return;
        if (!jpg) { log(fname + " is not JPEG, not sent (set the camera to JPEG or RAW+JPEG)"); return; }
        queue.add(uri, fname, eventId, prefs.getInt("quality", 50));
    }

    // ------------------------------------------------------------------ PTP worker (from v1.3)

    private class Worker extends Thread {
        final UsbDevice dev;
        volatile boolean running = true;
        Ptp ptp;
        final LinkedHashSet<String> recent = new LinkedHashSet<>();

        Worker(UsbDevice d) { dev = d; setName("ptp-worker"); }

        void shutdown() { running = false; interrupt(); }

        @Override public void run() {
            try {
                setCamera("connecting…", false);
                ptp = Ptp.open(usb, dev);
                ptp.setLogger(new Ptp.Logger() { @Override public void log(String s) { TetherService.this.log(s); } });
                log(String.format("USB %04X:%04X %s", dev.getVendorId(), dev.getProductId(), ptp.describe()));
                log(String.format("Device status: 0x%04X", ptp.getDeviceStatus()));
                ptp.drain();
                Ptp.DeviceInfo di = null;
                for (int attempt = 1; attempt <= 3 && running; attempt++) {
                    try { di = ptp.getDeviceInfo(); break; }
                    catch (IOException e) {
                        log("Attempt " + attempt + ": " + e.getMessage() + " → reset USB");
                        ptp.resetDevice();
                    }
                }
                if (di == null) throw new IOException("Camera not responding. Turn the camera off and on, then try again");
                String name = (di.manufacturer + " " + di.model).trim();
                if (name.isEmpty()) name = dev.getProductName() != null ? dev.getProductName() : "Camera";
                log("Found: " + name + " (ext 0x" + Integer.toHexString(di.vendorExtId) + " " + di.vendorExtDesc + ")");
                try { ptp.openSession(); }
                catch (IOException e) {
                    log("OpenSession: " + e.getMessage() + " → reset & try again");
                    ptp.resetDevice();
                    ptp.openSession();
                }
                boolean sony = di.supportsOp(Ptp.OC_Sony_SDIOConnect);
                if (sony) {
                    log("PC Remote mode detected, Sony handshake…");
                    try { ptp.sonyHandshake(); }
                    catch (IOException e) { log("Sony handshake: " + e.getMessage()); }
                    setCamera(name + " ✓ (PC Remote)", true);
                } else {
                    setCamera(name + " ✓ (MTP/PTP)", true);
                    if (dev.getVendorId() == 0x054C)
                        log("Camera is in MTP mode. For tethering: Menu → USB Connection → PC Remote");
                }
                setStage("Waiting for photos… (press the shutter)");

                boolean pendingShot = false;
                long pendingSince = 0, lastPoll = 0;
                int errors = 0;
                while (running) {
                    Ptp.Event e = ptp.pollEvent(300);
                    long now = System.currentTimeMillis();
                    if (e != null) {
                        int p0 = e.params.length > 0 ? e.params[0] : 0;
                        if (e.code != 0xC203) log(String.format("Event 0x%04X p=0x%08X", e.code, p0));
                        if (sony && (e.code == Ptp.EC_Sony_ObjectAdded || e.code == Ptp.EC_ObjectAdded)) {
                            pendingShot = true; pendingSince = now;
                            setStage("Camera is processing the photo…");
                        } else if (!sony && e.code == Ptp.EC_ObjectAdded) {
                            try { pull(p0, false); errors = 0; }
                            catch (IOException ex) { errors = onImageError(ex, errors); }
                        }
                    }
                    if (sony && (pendingShot || now - lastPoll > 1500)) {
                        lastPoll = now;
                        try {
                            int v = ptp.sonyObjectInMemory();
                            if (v > 0x8000) {
                                log(String.format("ObjectInMemory 0x%04X → pull", v));
                                drainSony();
                                pendingShot = false;
                            } else if (pendingShot && v < 0 && now - pendingSince > 1500) {
                                log("ObjectInMemory could not be read, trying a direct pull");
                                drainSony();
                                pendingShot = false;
                            } else if (pendingShot && now - pendingSince > 30000) {
                                log("No photo in the camera buffer after 30 s (check Still Img. Save Dest.)");
                                pendingShot = false;
                                setStage("Waiting for photos… (press the shutter)");
                            }
                            errors = 0;
                        } catch (IOException ex) {
                            errors = onImageError(ex, errors);
                        }
                    }
                }
            } catch (Exception ex) {
                if (running) {
                    log("Error: " + ex.getMessage());
                    setCamera("connection error", false);
                    setStage("Unplug and reconnect the camera, or tap 'Reconnect'");
                }
            } finally {
                if (ptp != null) { if (running) ptp.closeSession(); ptp.close(); }
                if (worker == this) worker = null;
            }
        }

        private int onImageError(IOException ex, int errors) throws IOException {
            errors++;
            log("Photo error (" + errors + "): " + ex.getMessage());
            ptp.drain();
            setStage("Error while pulling, waiting for the next photo…");
            if (errors >= 5) throw new IOException("Too many consecutive errors");
            return errors;
        }

        private void drainSony() throws IOException {
            for (int i = 0; i < 20 && running; i++) {
                if (!pull(SONY_IN_MEMORY_HANDLE, true)) break;
                int v = ptp.sonyObjectInMemory();
                if (v >= 0 && v <= 0x8000) break;
            }
            setStage("Waiting for photos… (press the shutter)");
        }

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
            if (oi.format == 0x3001) return false;
            String fname = oi.filename.isEmpty() ? String.format("IMG_%08X.JPG", handle) : oi.filename;
            if (!fname.matches("[A-Za-z0-9._ -]{1,64}"))
                throw new IOException("Corrupt photo info (invalid filename), try again");
            String key = fname + "|" + oi.size;
            if (recent.contains(key)) return false;

            long size = oi.size & 0xFFFFFFFFL;
            setStage("Pulling " + fname + " (" + mb(size) + ")…");
            log("Pulling " + fname + " " + mb(size));
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
                            setStage("Pulling " + fn + " " + (total > 0 ? (int) (done * 100 / total) : 0) + "%");
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
                log(String.format("GetObject failed 0x%04X", r.code));
                return false;
            }
            recent.add(key);
            if (recent.size() > 100) recent.remove(recent.iterator().next());
            double secs = Math.max(0.001, (System.currentTimeMillis() - t0) / 1000.0);
            saved++;
            setStage("Saved: " + fname);
            log(String.format(Locale.US, "Saved %s (%.1f s, %.1f MB/s)", fname, secs, size / 1048576.0 / secs));
            saved(t.uri, fname);
            return true;
        }
    }

    private static String mb(long b) { return String.format(Locale.US, "%.1f MB", b / 1048576.0); }
}
