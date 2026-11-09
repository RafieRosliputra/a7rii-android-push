package com.johorstudio.tether;

import android.content.Context;

import org.json.JSONArray;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.HashSet;
import java.util.Set;

/** Which SD photos were already uploaded, keyed by name|size|captureDate. Survives restart. */
public class UploadedStore {
    private final File store;
    private final Set<String> keys = new HashSet<>();

    public UploadedStore(Context c) {
        store = new File(c.getFilesDir(), "uploaded.json");
        load();
    }

    public synchronized boolean isUploaded(String key) { return keys.contains(key); }

    /** True when any stored key starts with name|size| (used before the EXIF time of a file is read). */
    public synchronized boolean hasNameSize(String name, long size) {
        String p = name + "|" + size + "|";
        for (String k : keys) if (k.startsWith(p)) return true;
        return false;
    }

    public synchronized void markUploaded(String key) {
        if (keys.add(key)) save();
    }

    private void load() {
        try {
            if (!store.exists()) return;
            byte[] b = new byte[(int) store.length()];
            FileInputStream in = new FileInputStream(store);
            int off = 0, n;
            while (off < b.length && (n = in.read(b, off, b.length - off)) > 0) off += n;
            in.close();
            JSONArray a = new JSONArray(new String(b, "UTF-8"));
            for (int i = 0; i < a.length(); i++) keys.add(a.getString(i));
        } catch (Exception ignored) {}
    }

    private void save() {
        try {
            JSONArray a = new JSONArray();
            for (String k : keys) a.put(k);
            File tmp = new File(store.getPath() + ".tmp");
            FileOutputStream out = new FileOutputStream(tmp);
            out.write(a.toString().getBytes("UTF-8"));
            out.getFD().sync();
            out.close();
            if (!tmp.renameTo(store)) throw new java.io.IOException("rename");
        } catch (Exception ignored) {}
    }
}
