package com.johorstudio.tether;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.ExifInterface;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.DocumentsContract;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Delayed tether: browse the camera SD card exposed as USB Mass Storage through the
 * Storage Access Framework (a persisted tree URI). Lists JPEG only, newest first,
 * loads EXIF thumbnails lazily (visible items only) and computes UploadQueue keys on demand.
 */
public class CameraBrowser {
    private static final int THREADS = 3;

    public static class Item {
        public Uri uri;
        public String documentId = "";
        public String name = "";
        public long size;
        public long lastModified;
        public String key; // lazily set via UploadQueue.keyFor
    }

    public interface Listener {
        void onItems(List<Item> items);
        void onKey(Item item);
        void onThumb(Item item, Bitmap bmp);
        void onEmpty();
        void onError(String msg);
    }

    private final Context ctx;
    private final ContentResolver cr;
    private final Uri tree;
    private final File cacheDir;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService exec = Executors.newFixedThreadPool(THREADS);
    private final Set<String> thumbInFlight = new HashSet<>();
    private final Set<String> keyInFlight = new HashSet<>();
    private final Set<String> wanted = new HashSet<>();
    private volatile boolean cancelled;

    public CameraBrowser(Context ctx, Uri tree) {
        this.ctx = ctx;
        this.cr = ctx.getContentResolver();
        this.tree = tree;
        cacheDir = new File(ctx.getCacheDir(), "delayed-thumbs");
        cacheDir.mkdirs();
    }

    /** Stable filesystem-safe id for a string; also used for the downloaded-JPEG cache filename. */
    public static String digest(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] d = md.digest(s.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder();
            for (byte b : d) sb.append(String.format(Locale.US, "%02x", b & 0xFF));
            return sb.toString();
        } catch (Exception e) { return Integer.toHexString(s.hashCode()); }
    }

    public File cacheFile(Item item) { return new File(cacheDir, digest(item.documentId) + ".jpg"); }

    public void cancel() { cancelled = true; exec.shutdownNow(); }

    /** Only these document ids are on screen now; thumbnails scrolled away are skipped. */
    public void retainVisible(List<String> ids) {
        synchronized (wanted) { wanted.clear(); wanted.addAll(ids); }
    }

    public void browse(final Listener l) {
        exec.execute(new Runnable() { @Override public void run() {
            final List<Item> acc = new ArrayList<>();
            try {
                String rootId = DocumentsContract.getTreeDocumentId(tree);
                ArrayDeque<Uri> stack = new ArrayDeque<>();
                stack.push(DocumentsContract.buildChildDocumentsUriUsingTree(tree, rootId));
                long lastPost = 0;
                while (!stack.isEmpty() && !cancelled) {
                    Uri dir = stack.pop();
                    Cursor c = null;
                    try {
                        c = cr.query(dir, new String[] {
                                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                                DocumentsContract.Document.COLUMN_SIZE,
                                DocumentsContract.Document.COLUMN_LAST_MODIFIED,
                                DocumentsContract.Document.COLUMN_MIME_TYPE
                        }, null, null, null);
                        if (c == null) continue;
                        while (c.moveToNext() && !cancelled) {
                            String docId = c.getString(0);
                            if (docId == null) continue;
                            String name = c.getString(1);
                            String mime = c.getString(4);
                            if (DocumentsContract.Document.MIME_TYPE_DIR.equals(mime)) {
                                stack.push(DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId));
                            } else if (isJpeg(name, mime)) {
                                Item it = new Item();
                                it.uri = DocumentsContract.buildDocumentUriUsingTree(tree, docId);
                                it.documentId = docId;
                                it.name = name == null ? "" : name;
                                it.size = c.isNull(2) ? 0 : c.getLong(2);
                                it.lastModified = c.isNull(3) ? 0 : c.getLong(3);
                                acc.add(it);
                            }
                        }
                    } catch (Exception ignored) {
                    } finally { if (c != null) c.close(); }
                    long now = System.currentTimeMillis();
                    if (now - lastPost > 250 || stack.isEmpty()) {
                        lastPost = now;
                        final List<Item> snap = new ArrayList<>(acc);
                        sortNewest(snap);
                        main.post(new Runnable() { @Override public void run() {
                            if (!cancelled) l.onItems(snap);
                        }});
                    }
                }
                if (acc.isEmpty() && !cancelled) {
                    main.post(new Runnable() { @Override public void run() {
                        if (!cancelled) l.onEmpty();
                    }});
                }
            } catch (final Exception e) {
                main.post(new Runnable() { @Override public void run() {
                    if (!cancelled) l.onError(e == null ? "?" : e.getMessage());
                }});
            }
        }});
    }

    public void loadKey(final Item item, final Listener l) {
        if (item.key != null) return;
        final String id = item.documentId;
        synchronized (keyInFlight) {
            if (keyInFlight.contains(id)) return;
            keyInFlight.add(id);
        }
        exec.execute(new Runnable() { @Override public void run() {
            if (cancelled || item.key != null) {
                synchronized (keyInFlight) { keyInFlight.remove(id); }
                return;
            }
            item.key = UploadQueue.keyFor(cr, item.uri, item.name, item.size, item.lastModified);
            main.post(new Runnable() { @Override public void run() {
                l.onKey(item);
                synchronized (keyInFlight) { keyInFlight.remove(id); }
            }});
        }});
    }

    public void loadThumb(final Item item, final Listener l) {
        final String id = item.documentId;
        synchronized (wanted) { wanted.add(id); }
        synchronized (thumbInFlight) {
            if (thumbInFlight.contains(id)) return;
            thumbInFlight.add(id);
        }
        exec.execute(new Runnable() { @Override public void run() {
            synchronized (wanted) {
                if (!wanted.contains(id)) {
                    synchronized (thumbInFlight) { thumbInFlight.remove(id); }
                    return;
                }
            }
            Bitmap bmp = null;
            if (!cancelled) { try { bmp = thumbFor(item); } catch (Exception ignored) {} }
            if (bmp == null) bmp = placeholder();
            synchronized (wanted) {
                if (!wanted.contains(id)) {
                    synchronized (thumbInFlight) { thumbInFlight.remove(id); }
                    return;
                }
            }
            final Bitmap f = bmp;
            main.post(new Runnable() { @Override public void run() {
                l.onThumb(item, f);
                synchronized (thumbInFlight) { thumbInFlight.remove(id); }
            }});
        }});
    }

    private Bitmap thumbFor(Item item) {
        File c = cacheFile(item);
        if (c.exists() && c.length() > 0) {
            Bitmap b = BitmapFactory.decodeFile(c.getAbsolutePath());
            if (b != null) return b;
        }
        InputStream in = null;
        try {
            in = cr.openInputStream(item.uri);
            if (in == null) return null;
            byte[] t = new ExifInterface(in).getThumbnailBytes();
            if (t != null && t.length > 0) {
                close(in); in = null;
                FileOutputStream o = new FileOutputStream(c);
                o.write(t);
                o.close();
                Bitmap b = BitmapFactory.decodeFile(c.getAbsolutePath());
                if (b != null) return b;
            }
        } catch (Exception e) {
            return null;
        } finally { close(in); }
        // No embedded thumbnail: bounded subsampled decode of the JPEG itself (~320 px).
        return fallbackDecode(item, c);
    }

    private Bitmap fallbackDecode(Item item, File c) {
        InputStream in = null;
        try {
            in = cr.openInputStream(item.uri);
            if (in == null) return null;
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeStream(in, null, o);
            close(in); in = null;
            int sample = 1;
            int side = Math.max(o.outWidth, o.outHeight);
            while (side > 0 && side / (sample * 2) >= 320) sample *= 2;
            BitmapFactory.Options d = new BitmapFactory.Options();
            d.inSampleSize = sample;
            in = cr.openInputStream(item.uri);
            Bitmap b = BitmapFactory.decodeStream(in, null, d);
            if (b == null) return null;
            FileOutputStream fo = new FileOutputStream(c);
            b.compress(Bitmap.CompressFormat.JPEG, 80, fo);
            fo.close();
            return b;
        } catch (Exception e) {
            return null;
        } finally { close(in); }
    }

    private static Bitmap placeholder() {
        int[] px = new int[4];
        java.util.Arrays.fill(px, 0xFF2A3439);
        return Bitmap.createBitmap(px, 2, 2, Bitmap.Config.ARGB_8888);
    }

    private static boolean isJpeg(String name, String mime) {
        if ("image/jpeg".equals(mime)) return true;
        String n = name == null ? "" : name.toLowerCase(Locale.US);
        return n.endsWith(".jpg") || n.endsWith(".jpeg");
    }

    private static void sortNewest(List<Item> items) {
        Collections.sort(items, new java.util.Comparator<Item>() {
            @Override public int compare(Item a, Item b) { return Long.compare(b.lastModified, a.lastModified); }
        });
    }

    private static void close(InputStream in) { try { if (in != null) in.close(); } catch (IOException ignored) {} }
}
