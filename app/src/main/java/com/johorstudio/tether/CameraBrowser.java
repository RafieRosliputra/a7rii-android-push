package com.johorstudio.tether;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.ExifInterface;
import android.os.Handler;
import android.os.Looper;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Delayed tether: browse the camera SD card over PTP on a background thread.
 * Lists JPEG only, newest first. Thumbnails load lazily (GetThumb, EXIF fallback) and cache to cacheDir.
 */
public class CameraBrowser {
    public static final int FORMAT_JPEG = 0x3801;
    public static final int THUMB_FALLBACK_BYTES = 64 * 1024;
    private static final int INFO_PAGE = 50;

    public static class Item {
        public int handle;
        public String name = "";
        public long size;
        public String captureDate = "";
        public int thumbSize;
        public String key() { return name + "|" + size + "|" + captureDate; }
    }

    public interface Listener {
        void onItems(List<Item> items);
        void onThumb(Item item, Bitmap bmp);
        void onEmpty(int storageCount, int handleCount);
        void onError(String msg);
    }

    private final Context ctx;
    private final Ptp ptp;
    private final File cacheDir;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService exec = Executors.newSingleThreadExecutor();
    private final Set<String> inFlight = new HashSet<>();
    private volatile boolean paused;

    public CameraBrowser(Context ctx, Ptp ptp) {
        this.ctx = ctx;
        this.ptp = ptp;
        cacheDir = new File(ctx.getCacheDir(), "delayed-thumbs");
        cacheDir.mkdirs();
    }

    public void setPaused(boolean p) { paused = p; }

    /** Stable filesystem-safe id for a key; also used for the downloaded-JPEG cache filename. */
    public static String digest(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] d = md.digest(s.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder();
            for (byte b : d) sb.append(String.format(Locale.US, "%02x", b & 0xFF));
            return sb.toString();
        } catch (Exception e) { return Integer.toHexString(s.hashCode()); }
    }

    public File cacheFile(Item item) { return new File(cacheDir, digest(item.key()) + ".jpg"); }

    public void browse(final Listener l) {
        exec.execute(new Runnable() { @Override public void run() {
            try {
                int[] sids;
                try { sids = ptp.getStorageIDs(); } catch (IOException e) { sids = new int[0]; }
                final int storageCount = sids.length;
                final List<Integer> handles = new ArrayList<>();
                for (int sid : sids) {
                    for (int h : ptp.getObjectHandles(sid)) handles.add(h);
                }
                if (handles.isEmpty()) {
                    main.post(new Runnable() { @Override public void run() { l.onEmpty(storageCount, 0); } });
                    return;
                }
                final List<Item> acc = new ArrayList<>();
                for (int i = 0; i < handles.size(); i += INFO_PAGE) {
                    for (int k = i; k < Math.min(i + INFO_PAGE, handles.size()); k++) {
                        try {
                            Ptp.ObjectInfo oi = ptp.getObjectInfo(handles.get(k));
                            if (isJpeg(oi)) acc.add(toItem(handles.get(k), oi));
                        } catch (IOException ignored) {}
                    }
                    final List<Item> snap = new ArrayList<>(acc);
                    sortNewest(snap);
                    main.post(new Runnable() { @Override public void run() { l.onItems(snap); } });
                }
            } catch (final IOException e) {
                main.post(new Runnable() { @Override public void run() { l.onError(e.getMessage()); } });
            }
        }});
    }

    public void loadThumb(final Item item, final Listener l) {
        final String key = item.key();
        synchronized (inFlight) {
            if (inFlight.contains(key)) return;
            inFlight.add(key);
        }
        exec.execute(new Runnable() { @Override public void run() {
            Bitmap bmp = null;
            if (!paused) {
                try { bmp = thumbFor(item); } catch (Exception ignored) {}
            }
            if (bmp == null) bmp = placeholder();
            final Bitmap f = bmp;
            main.post(new Runnable() { @Override public void run() {
                l.onThumb(item, f);
                synchronized (inFlight) { inFlight.remove(key); }
            }});
        }});
    }

    private Bitmap thumbFor(Item item) throws IOException {
        File c = cacheFile(item);
        if (c.exists() && c.length() > 0) {
            Bitmap b = BitmapFactory.decodeFile(c.getAbsolutePath());
            if (b != null) return b;
        }
        byte[] bytes = null;
        try { bytes = ptp.getThumb(item.handle); } catch (IOException ignored) {}
        if (bytes == null || bytes.length == 0) bytes = exifThumb(item.handle);
        if (bytes == null || bytes.length == 0) return null;
        FileOutputStream o = new FileOutputStream(c);
        o.write(bytes);
        o.close();
        return BitmapFactory.decodeFile(c.getAbsolutePath());
    }

    private byte[] exifThumb(int handle) {
        try {
            ByteArrayOutputStream sink = new ByteArrayOutputStream();
            ptp.getPartialObject(handle, 0, THUMB_FALLBACK_BYTES, sink, null);
            byte[] partial = sink.toByteArray();
            if (partial.length == 0) return null;
            ExifInterface exif = new ExifInterface(new ByteArrayInputStream(partial));
            byte[] t = exif.getThumbnail();
            if (t != null && t.length > 0) return t;
            // No EXIF thumbnail: use the partial JPEG head itself as a small preview.
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inSampleSize = 8;
            Bitmap b = BitmapFactory.decodeByteArray(partial, 0, partial.length, o);
            if (b != null) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                b.compress(Bitmap.CompressFormat.JPEG, 70, out);
                b.recycle();
                return out.toByteArray();
            }
            return null;
        } catch (Exception e) { return null; }
    }

    private static Bitmap placeholder() {
        int[] px = new int[4];
        java.util.Arrays.fill(px, 0xFF2A3439);
        return Bitmap.createBitmap(px, 2, 2, Bitmap.Config.ARGB_8888);
    }

    private static boolean isJpeg(Ptp.ObjectInfo oi) {
        if (oi.format == FORMAT_JPEG) return true;
        String n = oi.filename.toLowerCase(Locale.US);
        return n.endsWith(".jpg") || n.endsWith(".jpeg");
    }

    private static Item toItem(int handle, Ptp.ObjectInfo oi) {
        Item it = new Item();
        it.handle = handle;
        it.name = oi.filename;
        it.size = oi.size & 0xFFFFFFFFL;
        it.captureDate = oi.captureDate;
        it.thumbSize = oi.thumbSize;
        return it;
    }

    private static void sortNewest(List<Item> items) {
        Collections.sort(items, new Comparator<Item>() {
            @Override public int compare(Item a, Item b) {
                boolean ea = a.captureDate.isEmpty(), eb = b.captureDate.isEmpty();
                if (ea && eb) return Integer.compare(b.handle, a.handle);
                if (ea) return 1;
                if (eb) return -1;
                return b.captureDate.compareTo(a.captureDate);
            }
        });
    }
}
