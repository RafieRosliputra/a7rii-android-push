package com.johorstudio.tether;

import android.content.ContentResolver;
import android.content.Context;
import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Persistent queue: each saved photo -> (WebP) -> chunked upload to the server.
 * Saved to a file after every change, so it survives the app closing or the phone restarting
 * (fixes the v2.0 limit). The photo always stays in the phone folder whatever happens here.
 */
public class UploadQueue extends Thread {
    public static final int QUALITY_JPG = 0; // send the original JPEG at full quality

    public interface Listener {
        void onQueue(int pending, int uploaded);
        void onServer(String text, boolean ok);
        void onStage(String text);
        void onLog(String s);
    }

    static class Job {
        String id, uri, name, eventId, uploadId, file, t480, t1600;
        int quality, attempts;
        boolean original;

        JSONObject toJson() throws Exception {
            JSONObject o = new JSONObject();
            o.put("id", id); o.put("uri", uri); o.put("name", name); o.put("eventId", eventId);
            o.put("quality", quality); o.put("attempts", attempts); o.put("original", original);
            if (uploadId != null) o.put("uploadId", uploadId);
            if (file != null) o.put("file", file);
            if (t480 != null) o.put("t480", t480);
            if (t1600 != null) o.put("t1600", t1600);
            return o;
        }
        static Job of(JSONObject o) {
            Job j = new Job();
            j.id = o.optString("id"); j.uri = o.optString("uri"); j.name = o.optString("name");
            j.eventId = o.optString("eventId"); j.quality = o.optInt("quality", 50); j.attempts = o.optInt("attempts");
            j.original = o.optBoolean("original");
            j.uploadId = o.has("uploadId") ? o.optString("uploadId") : null;
            j.file = o.has("file") ? o.optString("file") : null;
            j.t480 = o.has("t480") ? o.optString("t480") : null;
            j.t1600 = o.has("t1600") ? o.optString("t1600") : null;
            return j;
        }
    }

    private final Context ctx;
    private final ContentResolver cr;
    private final Listener listener;
    private final File store, work;
    private final List<Job> jobs = new ArrayList<>();
    private volatile LiveApi api;
    private volatile boolean running = true;
    private int uploaded;

    public UploadQueue(Context ctx, LiveApi api, Listener l) {
        this.ctx = ctx; this.cr = ctx.getContentResolver(); this.api = api; this.listener = l;
        store = new File(ctx.getFilesDir(), "upload-queue.json");
        work = new File(ctx.getFilesDir(), "upload-work");
        work.mkdirs();
        load();
        setName("upload-queue");
        setDaemon(true);
    }

    public void setApi(LiveApi a) { api = a; synchronized (this) { notifyAll(); } }
    public synchronized int pending() { return jobs.size(); }
    public int uploaded() { return uploaded; }
    public void shutdown() { running = false; interrupt(); }
    /** Network came back etc.: try now instead of waiting for the backoff. */
    public synchronized void kick() { notifyAll(); }

    public synchronized void add(Uri uri, String name, String eventId, int quality) {
        Job j = new Job();
        j.id = Long.toString(System.currentTimeMillis(), 36) + Integer.toString((int) (Math.random() * 1296), 36);
        j.uri = uri.toString(); j.name = name; j.eventId = eventId; j.quality = quality;
        jobs.add(j);
        save();
        notifyAll();
        listener.onQueue(jobs.size(), uploaded);
    }

    /** Drop jobs of another event (e.g. the event was deleted). */
    public synchronized int clear() {
        int n = jobs.size();
        for (Job j : jobs) cleanup(j);
        jobs.clear();
        save();
        listener.onQueue(0, uploaded);
        return n;
    }

    private synchronized void load() {
        try {
            if (!store.exists()) return;
            byte[] b = new byte[(int) store.length()];
            FileInputStream in = new FileInputStream(store);
            int off = 0, n;
            while (off < b.length && (n = in.read(b, off, b.length - off)) > 0) off += n;
            in.close();
            JSONArray a = new JSONArray(new String(b, "UTF-8"));
            for (int i = 0; i < a.length(); i++) jobs.add(Job.of(a.getJSONObject(i)));
        } catch (Exception e) {
            listener.onLog("Giliran lama tidak dapat dibaca: " + e.getMessage());
        }
    }

    private synchronized void save() {
        try {
            JSONArray a = new JSONArray();
            for (Job j : jobs) a.put(j.toJson());
            File tmp = new File(store.getPath() + ".tmp");
            FileOutputStream out = new FileOutputStream(tmp);
            out.write(a.toString().getBytes("UTF-8"));
            out.getFD().sync();
            out.close();
            if (!tmp.renameTo(store)) throw new IOException("rename");
        } catch (Exception e) {
            listener.onLog("Gagal simpan giliran: " + e.getMessage());
        }
    }

    private synchronized Job head() { return jobs.isEmpty() ? null : jobs.get(0); }

    private synchronized void finish(Job j) {
        jobs.remove(j);
        cleanup(j);
        save();
    }

    private void cleanup(Job j) {
        if (j.file != null) new File(j.file).delete();
        if (j.t480 != null) new File(j.t480).delete();
        if (j.t1600 != null) new File(j.t1600).delete();
    }

    @Override public void run() {
        long backoff = 3000;
        listener.onQueue(pending(), uploaded);
        while (running) {
            Job j = head();
            LiveApi a = api;
            if (j == null || a == null) {
                synchronized (this) { try { wait(30000); } catch (InterruptedException e) { return; } }
                continue;
            }
            try {
                process(a, j);
                uploaded++;
                finish(j);
                backoff = 3000;
                listener.onServer("bersambung ✓ " + hostOf(a.via()), true);
                listener.onLog("Server ✓ " + j.name);
                listener.onQueue(pending(), uploaded);
                listener.onStage("Siap: " + j.name);
            } catch (LiveApi.ApiException e) {
                if (e.retryable()) {
                    j.attempts++;
                    save();
                    listener.onServer(e.status == 0 ? "tiada rangkaian, cuba semula…" : e.status == 503 ? "storan server offline, cuba semula…" : "ralat " + e.status + ", cuba semula…", false);
                    listener.onLog("Upload " + j.name + ": " + e.getMessage() + " (cuba semula " + (backoff / 1000) + " s)");
                    sleepWait(backoff);
                    backoff = Math.min(backoff * 2, 60000);
                } else if (e.status == 404 && e.getMessage() != null && e.getMessage().contains("Upload")) {
                    j.uploadId = null;      // server forgot the upload (expired): start it again
                    save();
                } else {
                    listener.onLog("Upload " + j.name + " ditolak: " + e.getMessage() + " (gambar kekal dalam telefon)");
                    listener.onServer("ralat: " + e.getMessage(), false);
                    finish(j);
                    listener.onQueue(pending(), uploaded);
                }
            } catch (OutOfMemoryError oom) {
                j.quality = QUALITY_JPG;
                save();
                listener.onLog("Memori tidak cukup untuk WebP, hantar JPG asal: " + j.name);
            } catch (Exception e) {
                j.attempts++;
                save();
                listener.onLog("Upload " + j.name + ": " + e.getMessage());
                if (j.attempts >= 8 && e instanceof java.io.FileNotFoundException) {
                    listener.onLog("Fail tiada dalam telefon lagi, dibuang dari giliran: " + j.name);
                    finish(j);
                    listener.onQueue(pending(), uploaded);
                } else sleepWait(backoff);
                backoff = Math.min(backoff * 2, 60000);
            }
        }
    }

    private void sleepWait(long ms) {
        synchronized (this) { try { wait(ms); } catch (InterruptedException ignored) {} }
    }

    private static String hostOf(String url) {
        return url.replaceFirst("^https?://", "");
    }

    private void process(LiveApi a, Job j) throws Exception {
        Uri uri = Uri.parse(j.uri);
        // 1) convert (once; result kept on disk so a restart doesn't redo it)
        if (j.t480 == null) {
            PhotoEncoder enc = new PhotoEncoder(cr);
            PhotoEncoder.Result r;
            long t0 = System.currentTimeMillis();
            if (j.quality == QUALITY_JPG) {
                listener.onStage("Thumbnail " + j.name + "…");
                r = new PhotoEncoder.Result();
                enc.thumbs(uri, r);
                r.usedOriginal = true;
            } else {
                listener.onStage("Tukar ke WebP " + j.quality + "%: " + j.name + "…");
                r = enc.toWebp(uri, j.quality, new File(work, j.id + ".webp"));
                if (r.usedOriginal) listener.onLog("Memori tidak cukup untuk WebP, hantar JPG asal: " + j.name);
                else listener.onLog(String.format(java.util.Locale.US, "WebP %d%%: %s (%.1f MB, %.1f s)", j.quality, j.name,
                        r.file.length() / 1048576.0, (System.currentTimeMillis() - t0) / 1000.0));
            }
            j.original = r.usedOriginal;
            j.file = r.file == null ? null : r.file.getPath();
            j.t1600 = writeTmp(j.id + "-1600.webp", r.thumb1600);
            j.t480 = writeTmp(j.id + "-480.webp", r.thumb480);
            save();
        }
        String sendName = j.original ? j.name : j.name.replaceFirst("\\.[^.]*$", "") + ".webp";
        long size = j.original ? sizeOf(uri) : new File(j.file).length();

        // 2) start or resume the upload
        long offset = 0;
        if (j.uploadId != null) {
            try { offset = a.uploadReceived(j.uploadId); }
            catch (LiveApi.ApiException e) { if (e.status == 404) j.uploadId = null; else throw e; }
        }
        if (j.uploadId == null) {
            JSONObject s = a.startUpload(j.eventId, sendName, size);
            j.uploadId = s.getString("id");
            save();
            offset = 0;
        }
        // 3) chunks of 4 MB
        byte[] buf = new byte[4 * 1024 * 1024];
        InputStream in = j.original ? cr.openInputStream(uri) : new FileInputStream(j.file);
        if (in == null) throw new java.io.FileNotFoundException(j.name);
        try {
            skipFully(in, offset);
            while (offset < size) {
                int len = readFully(in, buf);
                if (len <= 0) throw new IOException("Fail lebih pendek daripada dijangka");
                listener.onStage(String.format(java.util.Locale.US, "Upload %s %d%%", sendName, (int) (offset * 100 / size)));
                long got = a.putChunk(j.uploadId, offset, buf, len);
                if (got != offset + len) { // server has a different amount: realign
                    in.close();
                    in = j.original ? cr.openInputStream(uri) : new FileInputStream(j.file);
                    offset = got;
                    skipFully(in, offset);
                    continue;
                }
                offset = got;
            }
        } finally { in.close(); }
        // 4) thumbnails + complete
        if (j.t1600 != null) a.putThumb(j.uploadId, 1600, readFile(j.t1600));
        if (j.t480 != null) a.putThumb(j.uploadId, 480, readFile(j.t480));
        a.complete(j.uploadId);
    }

    private long sizeOf(Uri uri) throws IOException {
        android.content.res.AssetFileDescriptor fd = cr.openAssetFileDescriptor(uri, "r");
        if (fd == null) throw new java.io.FileNotFoundException(uri.toString());
        long n = fd.getLength();
        fd.close();
        if (n >= 0) return n;
        InputStream in = cr.openInputStream(uri);
        long total = 0; byte[] b = new byte[65536]; int r;
        while ((r = in.read(b)) > 0) total += r;
        in.close();
        return total;
    }

    private String writeTmp(String name, byte[] data) throws IOException {
        if (data == null) return null;
        File f = new File(work, name);
        FileOutputStream o = new FileOutputStream(f);
        o.write(data);
        o.close();
        return f.getPath();
    }

    private static byte[] readFile(String path) throws IOException {
        File f = new File(path);
        byte[] b = new byte[(int) f.length()];
        FileInputStream in = new FileInputStream(f);
        int off = 0, n;
        while (off < b.length && (n = in.read(b, off, b.length - off)) > 0) off += n;
        in.close();
        return b;
    }

    private static int readFully(InputStream in, byte[] b) throws IOException {
        int off = 0, n;
        while (off < b.length && (n = in.read(b, off, b.length - off)) > 0) off += n;
        return off;
    }

    private static void skipFully(InputStream in, long n) throws IOException {
        while (n > 0) {
            long s = in.skip(n);
            if (s <= 0) { if (in.read() < 0) throw new IOException("EOF"); s = 1; }
            n -= s;
        }
    }
}
