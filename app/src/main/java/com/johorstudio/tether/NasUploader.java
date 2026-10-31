package com.johorstudio.tether;

import android.content.ContentResolver;
import android.net.Uri;

import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.TimeUnit;

/** Background queue that uploads saved photos to the NAS over SMB, retrying until it works. */
public class NasUploader extends Thread {

    public static class Config {
        public String host = "", share = "", user = "", pass = "", domain = "", dir = "";
        public boolean valid() { return !host.isEmpty() && !share.isEmpty() && !user.isEmpty(); }
    }

    public interface Listener {
        void onNasStatus(String text, boolean ok);
        void onNasUploaded(String name, int totalUploaded);
        void onNasQueue(int pending);
        void onNasLog(String s);
    }

    private static class Job {
        Uri uri; String name; long size; int attempts;
    }

    private final ContentResolver cr;
    private final Listener listener;
    private final LinkedBlockingDeque<Job> queue = new LinkedBlockingDeque<>();
    private volatile Config cfg;
    private volatile boolean running = true;
    private SmbClient smb;
    private int uploaded = 0;

    public NasUploader(ContentResolver cr, Config cfg, Listener l) {
        this.cr = cr; this.cfg = cfg; this.listener = l;
        setName("nas-uploader");
        setDaemon(true);
    }

    /** New settings: drop the current connection, next job reconnects with them. */
    public synchronized void setConfig(Config c) {
        cfg = c;
        if (smb != null) { smb.close(); smb = null; }
    }

    public void enqueue(Uri uri, String name, long size) {
        Job j = new Job();
        j.uri = uri; j.name = name; j.size = size;
        queue.addLast(j);
        listener.onNasQueue(queue.size());
    }

    public int pending() { return queue.size(); }

    public void shutdown() {
        running = false;
        interrupt();
        synchronized (this) { if (smb != null) smb.close(); }
    }

    private synchronized SmbClient client() throws IOException {
        if (smb != null && smb.isConnected()) return smb;
        Config c = cfg;
        if (!c.valid()) throw new IOException("Tetapan TrueNAS belum lengkap");
        listener.onNasStatus("menyambung ke " + c.host + "…", false);
        SmbClient s = new SmbClient(c.host, c.share, c.user, c.pass, c.domain);
        s.setLogger(new SmbClient.Logger() { @Override public void log(String m) { listener.onNasLog(m); } });
        s.connect();
        if (!c.dir.isEmpty()) s.mkdirs(c.dir);
        smb = s;
        listener.onNasStatus("bersambung ✓ \\\\" + c.host + "\\" + c.share, true);
        return s;
    }

    @Override public void run() {
        while (running) {
            Job j;
            try { j = queue.pollFirst(1, TimeUnit.SECONDS); }
            catch (InterruptedException e) { if (!running) break; else continue; }
            if (j == null) continue;
            final String name = j.name;
            try {
                SmbClient s = client();
                listener.onNasStatus("memuat naik " + name + "…", true);
                InputStream in = cr.openInputStream(j.uri);
                if (in == null) throw new IOException("Fail tempatan tidak dapat dibaca");
                String finalName;
                final long[] last = {0};
                try {
                    finalName = s.upload(cfg.dir, name, in, j.size, new SmbClient.Progress() {
                        @Override public void onProgress(long done, long total) {
                            long now = System.currentTimeMillis();
                            if (now - last[0] > 300) {
                                last[0] = now;
                                int pct = total > 0 ? (int) (done * 100 / total) : 0;
                                listener.onNasStatus("memuat naik " + name + " " + pct + "%", true);
                            }
                        }
                    });
                } finally {
                    try { in.close(); } catch (Exception ignored) {}
                }
                uploaded++;
                listener.onNasUploaded(finalName, uploaded);
                listener.onNasQueue(queue.size());
                listener.onNasStatus(queue.isEmpty() ? "siap ✓ (semua dihantar)" : "seterusnya…", true);
            } catch (Exception e) {
                j.attempts++;
                listener.onNasLog("NAS ralat (" + name + ", cubaan " + j.attempts + "): " + e.getMessage());
                synchronized (this) { if (smb != null) { smb.close(); smb = null; } }
                if (j.attempts < 30) {
                    queue.addFirst(j);
                    listener.onNasStatus("ralat, cuba lagi dalam 10 s (" + queue.size() + " dalam giliran)", false);
                    try { Thread.sleep(10000); } catch (InterruptedException ie) { if (!running) break; }
                } else {
                    listener.onNasStatus("gagal hantar " + name + " (fail masih ada dalam telefon)", false);
                }
                listener.onNasQueue(queue.size());
            }
        }
    }
}
