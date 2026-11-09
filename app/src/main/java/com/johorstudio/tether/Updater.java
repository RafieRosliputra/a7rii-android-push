package com.johorstudio.tether;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.PendingIntent;
import android.app.ProgressDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * In-app update. Checks GET /api/shoot/app for a newer APK, colours the header in the three
 * activities via the cached result, and installs the APK with the PackageInstaller session API
 * (no FileProvider). The status broadcast is delivered to UpdateReceiver (manifest, not exported).
 */
public final class Updater {
    private static final String ACTION_STATUS = "com.johorstudio.tether.INSTALL_STATUS";
    private static final String EXTRA_SESSION_ID = "com.johorstudio.tether.SESSION_ID";
    private static final long REFRESH_MS = 10 * 60 * 1000L;

    /** Called on the UI thread with the newer version, or null when up to date / unknown. */
    public interface Callback { void onResult(String newVersion); }
    /** Percent of the APK download (0..100), or -1 when the size is unknown. */
    public interface Progress { void onProgress(int percent); }

    private static final Handler main = new Handler(Looper.getMainLooper());

    private static String newVersion;   // newest version newer than the installed one, else null
    private static JSONObject apkInfo;  // {name,version,size,tag,published} when newer
    private static boolean hasResult;   // a network check finished (successfully)
    private static long lastCheckMs;    // last time the network was actually hit
    private static boolean running;
    private static final List<Callback> pending = new ArrayList<>();

    private Updater() {}

    /** Cached "newer than installed" version, or null when up to date or still unknown. */
    public static String newVersion() { return newVersion; }

    /** Numeric dotted compare: 4.10.0 > 4.9.2, 4.7.0 > 4.6.6. */
    public static boolean isNewer(String remote, String local) {
        if (remote == null || local == null) return false;
        String[] r = remote.trim().split("\\.");
        String[] l = local.trim().split("\\.");
        int n = Math.max(r.length, l.length);
        for (int i = 0; i < n; i++) {
            int a = i < r.length ? num(r[i]) : 0;
            int b = i < l.length ? num(l[i]) : 0;
            if (a > b) return true;
            if (a < b) return false;
        }
        return false;
    }

    private static int num(String s) {
        if (s == null) return 0;
        int i = 0;
        while (i < s.length() && (s.charAt(i) < '0' || s.charAt(i) > '9')) i++;
        int v = 0;
        for (; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < '0' || c > '9') break;
            v = v * 10 + (c - '0');
        }
        return v;
    }

    /**
     * Ask the server for the current APK version. Runs off the UI thread, throttled to once per
     * 10 minutes, and calls back on the UI thread with the newer version or null. Silent on
     * network errors and skipped when not signed in. The result is cached in a static field so
     * all three activities can colour their header.
     */
    public static void check(final Context c, final Callback cb) {
        final String token = App.token(c);
        if (token == null || token.isEmpty()) return; // skip silently when not signed in
        synchronized (Updater.class) {
            long now = System.currentTimeMillis();
            boolean stale = now - lastCheckMs >= REFRESH_MS;
            if (hasResult && !stale) { deliver(cb, newVersion); return; }
            if (cb != null) pending.add(cb);
            if (running) return; // a check is already going; it will flush the pending callback
            running = true;
            lastCheckMs = now;
        }
        new Thread(new Runnable() { @Override public void run() {
            try {
                final LiveApi api = new LiveApi(App.serverUrl(c), App.fastUrl(c), token);
                JSONObject info = api.appInfo();
                JSONObject apk = info == null ? null : info.optJSONObject("apk");
                String remote = apk == null ? null : apk.optString("version", null);
                String local = App.version(c);
                String newer = (remote != null && isNewer(remote, local)) ? remote : null;
                synchronized (Updater.class) {
                    if (newer != null) apkInfo = apk;
                    newVersion = newer;
                    hasResult = true;
                    running = false;
                }
                flush();
            } catch (Exception e) {
                // silent on network errors; a later activity resume may try again
                synchronized (Updater.class) { running = false; }
                flush();
            }
        }}).start();
    }

    private static void flush() {
        List<Callback> copy;
        synchronized (Updater.class) {
            copy = new ArrayList<>(pending);
            pending.clear();
        }
        final String v = newVersion;
        for (Callback cb : copy) deliver(cb, v);
    }

    private static void deliver(final Callback cb, final String v) {
        if (cb == null) return;
        main.post(new Runnable() { @Override public void run() { cb.onResult(v); } });
    }

    /** Red header tapped: confirm, then download with progress and install via PackageInstaller. */
    public static void promptUpdate(final Activity a) {
        final String v = newVersion;
        final JSONObject apk = apkInfo;
        if (v == null || apk == null) return;
        final long size = apk.optLong("size", 0);
        final long mb = Math.round(size / 1048576.0);
        new AlertDialog.Builder(a)
                .setTitle("Update available")
                .setMessage("Update JS Tether to v" + v + "? (" + mb + " MB)")
                .setPositiveButton("Update", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) { doUpdate(a); }
                })
                .setNegativeButton("Later", null)
                .show();
    }

    private static void doUpdate(final Activity a) {
        if (!a.getPackageManager().canRequestPackageInstalls()) {
            try {
                a.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:" + a.getPackageName())));
            } catch (Exception e) {
                try { a.startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:" + a.getPackageName()))); } catch (Exception ignored) {}
            }
            toast(a, "Allow installs from JS Tether, then tap again");
            return;
        }
        final File dest = new File(a.getCacheDir(), "update/JSTether.apk");
        final ProgressDialog pd = new ProgressDialog(a);
        pd.setProgressStyle(ProgressDialog.STYLE_HORIZONTAL);
        pd.setMax(100);
        pd.setProgress(0);
        pd.setCancelable(false);
        pd.setMessage("Downloading update…");
        pd.show();
        new Thread(new Runnable() { @Override public void run() {
            try {
                final LiveApi api = new LiveApi(App.serverUrl(a), App.fastUrl(a), App.token(a));
                api.downloadApk(dest, new Progress() {
                    @Override public void onProgress(final int percent) {
                        a.runOnUiThread(new Runnable() { @Override public void run() {
                            if (!pd.isShowing()) return;
                            if (percent >= 0) {
                                pd.setProgress(percent);
                                pd.setMessage("Downloading update… " + percent + "%");
                            }
                        }});
                    }
                });
                install(a, dest);
                a.runOnUiThread(new Runnable() { @Override public void run() {
                    try { pd.dismiss(); } catch (Exception ignored) {}
                }});
            } catch (final Exception e) {
                a.runOnUiThread(new Runnable() { @Override public void run() {
                    try { pd.dismiss(); } catch (Exception ignored) {}
                }});
                toast(a, "Update download failed: " + e.getMessage());
            }
        }}).start();
    }

    private static void install(final Context c, final File apk) {
        try {
            PackageInstaller pi = c.getPackageManager().getPackageInstaller();
            PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(
                    PackageInstaller.SessionParams.MODE_FULL_INSTALL);
            if (Build.VERSION.SDK_INT >= 31) params.setAppPackageName(c.getPackageName());
            final int sessionId = pi.createSession(params);
            PackageInstaller.Session session = pi.openSession(sessionId);
            try {
                InputStream in = new FileInputStream(apk);
                OutputStream out = session.openWrite("package", 0, apk.length());
                try {
                    byte[] buf = new byte[65536];
                    int n;
                    while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                    session.fsync(out);
                } finally {
                    try { out.close(); } catch (Exception ignored) {}
                    try { in.close(); } catch (Exception ignored) {}
                }
                Intent si = new Intent(c, UpdateReceiver.class).setAction(ACTION_STATUS);
                si.putExtra(EXTRA_SESSION_ID, sessionId);
                int flags = PendingIntent.FLAG_UPDATE_CURRENT
                        | (Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0);
                PendingIntent status = PendingIntent.getBroadcast(c, sessionId, si, flags);
                session.commit(status.getIntentSender());
            } finally {
                try { session.close(); } catch (Exception ignored) {}
            }
        } catch (Exception e) {
            toast(c, "Install failed: " + e.getMessage());
        }
    }

    /** Receives the PackageInstaller result and opens the system confirm screen. */
    public static class UpdateReceiver extends BroadcastReceiver {
        @Override public void onReceive(Context c, Intent i) {
            int status = i.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
            String msg = i.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
            if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
                Intent confirm = (Intent) i.getParcelableExtra(Intent.EXTRA_INTENT);
                if (confirm != null) {
                    confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    try { c.startActivity(confirm); } catch (Exception e) { toast(c, "Could not open the install screen"); }
                }
            } else if (status == PackageInstaller.STATUS_SUCCESS) {
                toast(c, "Update installed");
            } else {
                toast(c, "Update failed" + (msg == null || msg.isEmpty() ? "" : ": " + msg));
            }
        }
    }

    private static void toast(final Context c, final String s) {
        main.post(new Runnable() { @Override public void run() {
            Toast.makeText(c.getApplicationContext(), s, Toast.LENGTH_LONG).show();
        }});
    }
}
