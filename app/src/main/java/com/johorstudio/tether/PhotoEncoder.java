package com.johorstudio.tether;

import android.content.ContentResolver;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.media.ExifInterface;
import android.net.Uri;
import android.os.Build;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * JPEG from the camera -> full-resolution WebP (quality 40..90) + 1600 px / 480 px thumbnails.
 * 42 MP needs ~170 MB for one bitmap, so photos are done one at a time (largeHeap in the manifest).
 */
public class PhotoEncoder {
    private final ContentResolver cr;

    public PhotoEncoder(ContentResolver cr) { this.cr = cr; }

    public static class Result {
        public File file;           // null when the original JPEG is sent as is
        public byte[] thumb1600, thumb480;
        public boolean usedOriginal; // WebP failed (e.g. out of memory): send the JPEG instead
    }

    private int orientation(Uri uri) {
        InputStream in = null;
        try {
            in = cr.openInputStream(uri);
            if (in == null) return 1;
            return new ExifInterface(in).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
        } catch (Exception e) {
            return 1;
        } finally { close(in); }
    }

    private static Matrix matrixFor(int o) {
        Matrix m = new Matrix();
        switch (o) {
            case ExifInterface.ORIENTATION_ROTATE_90: m.postRotate(90); break;
            case ExifInterface.ORIENTATION_ROTATE_180: m.postRotate(180); break;
            case ExifInterface.ORIENTATION_ROTATE_270: m.postRotate(270); break;
            case ExifInterface.ORIENTATION_FLIP_HORIZONTAL: m.postScale(-1, 1); break;
            case ExifInterface.ORIENTATION_FLIP_VERTICAL: m.postScale(1, -1); break;
            case ExifInterface.ORIENTATION_TRANSPOSE: m.postRotate(90); m.postScale(-1, 1); break;
            case ExifInterface.ORIENTATION_TRANSVERSE: m.postRotate(270); m.postScale(-1, 1); break;
            default: return null;
        }
        return m;
    }

    private static Bitmap.CompressFormat webp() {
        if (Build.VERSION.SDK_INT >= 30) return Bitmap.CompressFormat.WEBP_LOSSY;
        @SuppressWarnings("deprecation") Bitmap.CompressFormat f = Bitmap.CompressFormat.WEBP;
        return f;
    }

    private static Bitmap applyOrientation(Bitmap b, int o) {
        Matrix m = matrixFor(o);
        if (m == null) return b;
        Bitmap r = Bitmap.createBitmap(b, 0, 0, b.getWidth(), b.getHeight(), m, true);
        if (r != b) b.recycle();
        return r;
    }

    private static Bitmap scale(Bitmap src, int maxSide) {
        int w = src.getWidth(), h = src.getHeight();
        float s = Math.min(1f, maxSide / (float) Math.max(w, h));
        if (s >= 1f) return src;
        return Bitmap.createScaledBitmap(src, Math.max(1, Math.round(w * s)), Math.max(1, Math.round(h * s)), true);
    }

    private static byte[] encode(Bitmap b, int q) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        b.compress(webp(), q, out);
        return out.toByteArray();
    }

    /** Thumbnails only (for "Full JPG" mode or when WebP failed): cheap subsampled decode. */
    public void thumbs(Uri uri, Result r) throws IOException {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        InputStream in = cr.openInputStream(uri);
        BitmapFactory.decodeStream(in, null, o);
        close(in);
        int sample = 1;
        while (Math.max(o.outWidth, o.outHeight) / (sample * 2) >= 1600) sample *= 2;
        BitmapFactory.Options d = new BitmapFactory.Options();
        d.inSampleSize = sample;
        in = cr.openInputStream(uri);
        Bitmap b = BitmapFactory.decodeStream(in, null, d);
        close(in);
        if (b == null) throw new IOException("Photo cannot be read");
        b = applyOrientation(b, orientation(uri));
        makeThumbs(b, r);
        b.recycle();
    }

    private void makeThumbs(Bitmap full, Result r) {
        Bitmap t1600 = scale(full, 1600);
        r.thumb1600 = encode(t1600, 72);
        Bitmap t480 = scale(t1600, 480);
        r.thumb480 = encode(t480, 72);
        if (t480 != t1600) t480.recycle();
        if (t1600 != full) t1600.recycle();
    }

    /** Full-resolution WebP written to outFile. Falls back to the original JPEG on out-of-memory. */
    public Result toWebp(Uri uri, int quality, File outFile) throws IOException {
        Result r = new Result();
        Bitmap b = null;
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inPreferredConfig = Bitmap.Config.ARGB_8888;
            InputStream in = cr.openInputStream(uri);
            try { b = BitmapFactory.decodeStream(in, null, o); } finally { close(in); }
            if (b == null) throw new IOException("Photo cannot be read (not a JPEG?)");
            b = applyOrientation(b, orientation(uri));
            OutputStream os = new FileOutputStream(outFile);
            try {
                if (!b.compress(webp(), quality, os)) throw new IOException("WebP failed");
            } finally { os.close(); }
            makeThumbs(b, r);
            r.file = outFile;
            return r;
        } catch (OutOfMemoryError oom) {
            if (b != null) b.recycle();
            b = null;
            System.gc();
            outFile.delete();
            r.usedOriginal = true;
            thumbs(uri, r);
            return r;
        } finally {
            if (b != null) b.recycle();
        }
    }

    private static void close(InputStream in) { try { if (in != null) in.close(); } catch (IOException ignored) {} }
}
