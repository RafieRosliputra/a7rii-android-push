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

    /** Optional per-job success hook (Delayed tether). Direct mode never sets it. */
    public interface Success {
        void onSuccess(String name, String ref);
    }

    static class Job {
        String id, uri, name, eventId, uploadId, file, t480, t1600, key;
        int quality, attempts;
        boolean original;

        JSONObject toJson() throws Exception {
            JSONObject o = new JSONObject();
            o.put("id", id); o.put("uri", uri); o.put("name", name); o.put("eventId", eventId);
            o.put("quality", quality); o.put("attempts", attempts); o.put("original", original);
            if (uploadId != null) o.put("uploadId", uploadId);
            if (key != null) o.put("key", key);
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
            j.key = o.has("key") ? o.optString("key") : null;
            j.file = o.has("file") ? o.optString("file") : null;
            j.t480 = o.has("t480") ? o.optString("t480") : null;
            j.t1600 = o.has("t1600") ? o.optString("t1600") : null;
            return j;
        }
    }

    private final Context ctx;
    private final ContentResolver cr;
    private final Listener listener;
    private final File store, work, logFile;
    /** Upload history (newest last): {name, key, state, at}. state: ok | dup | bad */
    private final JSONArray history = new JSONArray();
    private static final int HISTORY_MAX = 400;
    private final List<Job> jobs = new ArrayList<>();
    private volatile LiveApi api;
    private volatile Success success;
    private volatile boolean running = true;
    private int uploaded;

    public UploadQueue(Context ctx, LiveApi api, Listener l) {
        this.ctx = ctx; this.cr = ctx.getContentResolver(); this.api = api; this.listener = l;
        store = new File(ctx.getFilesDir(), "upload-queue.json");
        logFile = new File(ctx.getFilesDir(), "upload-log.json");
        work = new File(ctx.getFilesDir(), "upload-work");
        work.mkdirs();
        load();
        setName("upload-queue");
        setDaemon(true);
    }

    public void setApi(LiveApi a) { api = a; synchronized (this) { notifyAll(); } }
    public void setSuccessListener(Success s) { success = s; }
    public synchronized int pending() { return jobs.size(); }
    public int uploaded() { return uploaded; }
    public void shutdown() { running = false; interrupt(); }
    /** Network came back etc.: try now instead of waiting for the backoff. */
    public synchronized void kick() { notifyAll(); }

    public synchronized void add(Uri uri, String name, String eventId, int quality) { add(uri, name, eventId, quality, null); }

    /** Queue a photo. With a key (name|size|time taken), photos already queued or sent are skipped. Returns false when skipped. */
    public synchronized boolean add(Uri uri, String name, String eventId, int quality, String key) {
        if (key != null) {
            for (Job x : jobs) if (key.equals(x.key)) { listener.onLog("Already queued: " + name); return false; }
            String st = stateOf(key);
            if ("ok".equals(st) || "dup".equals(st)) { listener.onLog("Already sent before: " + name); return false; }
        }
        Job j = new Job();
        j.id = Long.toString(System.currentTimeMillis(), 36) + Integer.toString((int) (Math.random() * 1296), 36);
        j.uri = uri.toString(); j.name = name; j.eventId = eventId; j.quality = quality; j.key = key;
        jobs.add(j);
        save();
        notifyAll();
        listener.onQueue(jobs.size(), uploaded);
        return true;
    }

    /** Mark files the server already has (from /api/shoot/check) so they are not queued. */
    public synchronized void markSent(String name, String key) { record(name, key, "dup"); }

    private synchronized String stateOf(String key) {
        for (int i = history.length() - 1; i >= 0; i--) {
            JSONObject o = history.optJSONObject(i);
            if (o != null && key.equals(o.optString("key"))) return o.optString("state");
        }
        return null;
    }

    /** "queued" | "ok" | "dup" | "bad" | null (never seen). */
    public synchronized String statusOf(String key) {
        if (key == null) return null;
        for (Job x : jobs) if (key.equals(x.key)) return "queued";
        return stateOf(key);
    }

    private synchronized void record(String name, String key, String state) {
        try {
            JSONObject o = new JSONObject();
            o.put("name", name); o.put("state", state); o.put("at", System.currentTimeMillis());
            if (key != null) o.put("key", key);
            history.put(o);
            while (history.length() > HISTORY_MAX) history.remove(0);
            File tmp = new File(logFile.getPath() + ".tmp");
            FileOutputStream out = new FileOutputStream(tmp);
            out.write(history.toString().getBytes("UTF-8"));
            out.close();
            tmp.renameTo(logFile);
        } catch (Exception ignored) {}
    }

    /** Rows for the "Upload list" screen, newest first: {name, status text}. */
    public synchronized List<String[]> rows() {
        List<String[]> out = new ArrayList<>();
        for (int i = jobs.size() - 1; i >= 0; i--) {
            Job j = jobs.get(i);
            out.add(new String[] { j.name, i == 0 ? (j.attempts > 0 ? "Retry (" + j.attempts + ")" : "Sending") : "Waiting" });
        }
        java.text.SimpleDateFormat f = new java.text.SimpleDateFormat("HH:mm", java.util.Locale.US);
        for (int i = history.length() - 1; i >= 0; i--) {
            JSONObject o = history.optJSONObject(i);
            if (o == null) continue;
            String st = o.optString("state");
            out.add(new String[] { o.optString("name"), ("ok".equals(st) ? "Sent ✓" : "dup".equals(st) ? "Already in gallery" : "Failed") + " · " + f.format(new java.util.Date(o.optLong("at"))) });
        }
        return out;
    }

    /** name|size|EXIF time taken (or m<last modified>): the same key the web page makes, so a photo is never sent twice. */
    public static String keyFor(ContentResolver cr, Uri uri, String name, long size, long lastModified) {
        String t = null;
        InputStream in = null;
        try {
            in = cr.openInputStream(uri);
            if (in != null) t = new android.media.ExifInterface(in).getAttribute(android.media.ExifInterface.TAG_DATETIME_ORIGINAL);
        } catch (Exception ignored) {
        } finally { try { if (in != null) in.close(); } catch (IOException ignored) {} }
        if (t == null || t.trim().isEmpty() || t.startsWith("0000")) t = "m" + lastModified;
        return name + "|" + size + "|" + t.trim();
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
            listener.onLog("Could not read old queue: " + e.getMessage());
        }
        try {
            if (logFile.exists()) {
                byte[] b = readFile(logFile.getPath());
                JSONArray a = new JSONArray(new String(b, "UTF-8"));
                for (int i = 0; i < a.length(); i++) history.put(a.get(i));
            }
        } catch (Exception ignored) {}
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
            listener.onLog("Failed to save queue: " + e.getMessage());
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
                boolean dup = process(a, j);
                if (!dup) uploaded++;
                record(j.name, j.key, dup ? "dup" : "ok");
                final String doneName = j.name, doneKey = j.key;
                finish(j);
                backoff = 3000;
                listener.onServer("connected ✓ " + hostOf(a.via()), true);
                listener.onLog(dup ? "Already in gallery, not sent again: " + j.name : "Server ✓ " + j.name);
                listener.onQueue(pending(), uploaded);
                listener.onStage("Done: " + j.name);
                Success sc = success;
                if (sc != null) sc.onSuccess(doneName, doneKey);
            } catch (LiveApi.ApiException e) {
                if (e.retryable()) {
                    j.attempts++;
                    save();
                    listener.onServer(e.status == 0 ? "no network, retrying…" : e.status == 503 ? "server storage offline, retrying…"
                            : e.status == 401 ? "code expired / event closed: enter a new code" : "error " + e.status + ", retrying…", false);
                    listener.onLog("Upload " + j.name + ": " + e.getMessage() + " (retry in " + (backoff / 1000) + " s)");
                    sleepWait(backoff);
                    backoff = Math.min(backoff * 2, 60000);
                } else if (e.status == 404 && e.getMessage() != null && e.getMessage().contains("Upload")) {
                    j.uploadId = null;      // server forgot the upload (expired): start it again
                    save();
                } else {
                    listener.onLog("Upload " + j.name + " rejected: " + e.getMessage() + " (photo stays on the phone)");
                    record(j.name, j.key, "bad");
                    listener.onServer("error: " + e.getMessage(), false);
                    finish(j);
                    listener.onQueue(pending(), uploaded);
                }
            } catch (OutOfMemoryError oom) {
                j.quality = QUALITY_JPG;
                save();
                listener.onLog("Not enough memory for WebP, sending the original JPG: " + j.name);
            } catch (Exception e) {
                j.attempts++;
                save();
                listener.onLog("Upload " + j.name + ": " + e.getMessage());
                if (e instanceof java.io.FileNotFoundException && (j.attempts >= 8 || "file".equals(Uri.parse(j.uri).getScheme()))) { // our own cache copy is gone: no point retrying, and it must not block the queue head
                    listener.onLog("File no longer on the phone, removed from the queue: " + j.name);
                    record(j.name, j.key, "bad");
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

    /** Returns true when the server already had this photo (nothing sent). */
    private boolean process(LiveApi a, Job j) throws Exception {
        Uri uri = Uri.parse(j.uri);
        if (j.key == null) { j.key = keyFor(cr, uri, j.name, sizeOf(uri), 0); save(); }
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
                listener.onStage("Converting to WebP " + j.quality + "%: " + j.name + "…");
                r = enc.toWebp(uri, j.quality, new File(work, j.id + ".webp"));
                if (r.usedOriginal) listener.onLog("Not enough memory for WebP, sending the original JPG: " + j.name);
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
            JSONObject s = a.startUpload(j.eventId, sendName, size, j.key);
            if (s.optBoolean("duplicate")) return true;
            j.uploadId = s.getString("id");
            save();
            offset = s.optLong("received", 0);
        }
        // 3) chunks of 4 MB
        byte[] buf = new byte[4 * 1024 * 1024];
        InputStream in = j.original ? cr.openInputStream(uri) : new FileInputStream(j.file);
        if (in == null) throw new java.io.FileNotFoundException(j.name);
        try {
            skipFully(in, offset);
            while (offset < size) {
                int len = readFully(in, buf);
                if (len <= 0) throw new IOException("File shorter than expected");
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
        return false;
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
