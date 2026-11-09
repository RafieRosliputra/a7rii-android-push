package com.johorstudio.tether;

import android.app.Activity;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.content.ContentResolver;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.provider.DocumentsContract;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AbsListView;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.RelativeLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Delayed tether: browse the camera SD card as Android storage (USB Connection = Mass Storage)
 * via the Storage Access Framework. Mark uploaded vs not, select JPEGs, copy the selected ones
 * to the phone cache and push them through the normal PhotoEncoder + UploadQueue path.
 */
public class DelayedActivity extends Activity {
    private static final int REQ_PICK_TREE = 100;

    private static final int BG = Color.rgb(22, 28, 31), CARD = Color.rgb(32, 39, 43), TEAL = Color.rgb(31, 111, 104),
            TEAL_LIGHT = Color.rgb(108, 196, 185), MUTED = Color.rgb(150, 165, 168), RED = Color.rgb(224, 86, 78),
            GREEN = Color.rgb(120, 220, 150), AMBER = Color.rgb(255, 190, 120);

    private UploadedStore store;
    private CameraBrowser browser;
    private UploadQueue queue;

    private final List<CameraBrowser.Item> items = new ArrayList<>();
    private final List<CameraBrowser.Item> visible = new ArrayList<>();
    private final LinkedHashSet<String> selected = new LinkedHashSet<>(); // documentIds
    private final Set<String> downloading = new HashSet<>();
    private final LinkedHashMap<String, Bitmap> thumbCache = new LinkedHashMap<String, Bitmap>(64, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, Bitmap> e) { return size() > 120; }
    };

    private boolean filterNewOnly = false;
    private boolean busy = false;
    private volatile boolean destroyed = false;
    private Adapter adapter;
    private TextView tvStatus, logView;
    private ScrollView logScroll;
    private Button uploadBtn, filterBtn;
    private GridView grid;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        store = new UploadedStore(this);
        buildUi();
        queue = new UploadQueue(this, App.api(this), new UploadQueue.Listener() {
            @Override public void onQueue(int p, int up) { setStatus("Giliran " + p + " · dihantar " + up); }
            @Override public void onServer(String text, boolean ok) { setStatus(text); }
            @Override public void onStage(String text) { setStatus(text); }
            @Override public void onLog(String s) { log(s); }
        });
        queue.setSuccessListener(new UploadQueue.Success() {
            @Override public void onSuccess(String name, String ref) {
                if (ref != null) {
                    store.markUploaded(ref);
                    new File(getCacheDir(), "delayed-jpg/" + CameraBrowser.digest(ref) + ".jpg").delete();
                }
                runOnUiThread(new Runnable() { @Override public void run() { rebuildVisible(); } });
            }
        });
        queue.start();
        resolveTree();
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        destroyed = true;
        if (browser != null) browser.cancel();
        // The upload queue keeps running so uploads finish; it owns the copied cache files.
        if (queue != null && queue.pending() == 0) queue.shutdown();
    }

    // ------------------------------------------------------------------ SAF tree

    private void resolveTree() {
        String saved = App.prefs(this).getString("delayed_tree", null);
        if (saved == null) { openPicker(); return; }
        Uri tree = Uri.parse(saved);
        if (treeUsable(tree)) startBrowse(tree);
        else {
            App.prefs(this).edit().remove("delayed_tree").apply();
            toast("Kebenaran kad hilang atau kad tiada. Pilih folder DCIM semula.");
            openPicker();
        }
    }

    private boolean treeUsable(Uri tree) {
        Cursor c = null;
        try {
            String id = DocumentsContract.getTreeDocumentId(tree);
            Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, id);
            c = getContentResolver().query(children, new String[] { DocumentsContract.Document.COLUMN_DOCUMENT_ID }, null, null, null);
            return c != null;
        } catch (Exception e) {
            return false;
        } finally { if (c != null) c.close(); }
    }

    private void openPicker() {
        setStatus("Pilih kad kamera (DCIM)");
        try {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
            startActivityForResult(i, REQ_PICK_TREE);
        } catch (Exception e) {
            toast("Tiada pemilih folder pada peranti ini");
        }
    }

    @Override protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == REQ_PICK_TREE && res == RESULT_OK && data != null && data.getData() != null) {
            Uri tree = data.getData();
            try {
                getContentResolver().takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } catch (Exception e) {
                toast("Gagal simpan kebenaran: " + e.getMessage());
            }
            App.prefs(this).edit().putString("delayed_tree", tree.toString()).apply();
            startBrowse(tree);
        }
    }

    private void startBrowse(Uri tree) {
        if (browser != null) browser.cancel();
        browser = new CameraBrowser(this, tree);
        setStatus("Membaca kad SD…");
        log("Membaca kad SD (SAF)…");
        browser.browse(listener);
    }

    // ------------------------------------------------------------------ UI kit

    private int dp(int v) { return (int) (v * getResources().getDisplayMetrics().density + 0.5f); }

    private TextView text(String s, float size, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(size);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setPadding(0, dp(4), 0, dp(4));
        return t;
    }

    private Button button(String s, boolean primary, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(s);
        b.setAllCaps(false);
        b.setTextColor(Color.WHITE);
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(dp(8));
        if (primary) g.setColor(TEAL); else { g.setColor(CARD); g.setStroke(dp(1), Color.rgb(70, 82, 87)); }
        b.setBackground(g);
        b.setOnClickListener(l);
        return b;
    }

    private GradientDrawable circle(int color) {
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(color);
        return g;
    }

    private void toast(final String s) {
        runOnUiThread(new Runnable() { @Override public void run() { Toast.makeText(DelayedActivity.this, s, Toast.LENGTH_LONG).show(); } });
    }

    private void setStatus(final String s) {
        runOnUiThread(new Runnable() { @Override public void run() { tvStatus.setText(s); } });
    }

    private void log(final String s) {
        Log.d("Delayed", s);
        runOnUiThread(new Runnable() { @Override public void run() {
            CharSequence cur = logView.getText();
            String all = cur + s + "\n";
            if (all.length() > 20000) all = all.substring(all.length() - 15000);
            logView.setText(all);
            logScroll.post(new Runnable() { @Override public void run() { logScroll.fullScroll(ScrollView.FOCUS_DOWN); } });
        }});
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);
        root.setPadding(dp(14), dp(20), dp(14), dp(10));

        TextView brand = text("JOHORSTUDIO.COM · LIVE", 12, TEAL_LIGHT, true);
        brand.setLetterSpacing(0.1f);
        root.addView(brand);
        root.addView(text("Tarik dari SD (Delayed)", 22, Color.WHITE, true));
        root.addView(text("Set kamera: MENU > Setup > USB Connection > Mass Storage, kemudian cucuk USB", 14, AMBER, true));
        root.addView(text("Pilih JPEG dari kad kamera; telefon hantar yang dipilih sahaja.", 13, MUTED, false));

        tvStatus = text("Membaca kad…", 14, Color.WHITE, false);
        root.addView(tvStatus);

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        Button swap = button("Tukar kad", false, new View.OnClickListener() { @Override public void onClick(View v) { openPicker(); } });
        filterBtn = button("Tapis: semua", false, new View.OnClickListener() { @Override public void onClick(View v) {
            filterNewOnly = !filterNewOnly;
            filterBtn.setText(filterNewOnly ? "Tapis: belum" : "Tapis: semua");
            rebuildVisible();
        }});
        Button selAll = button("Pilih semua baru", false, new View.OnClickListener() { @Override public void onClick(View v) { selectAllNew(); } });
        LinearLayout.LayoutParams lp1 = new LinearLayout.LayoutParams(0, dp(46), 1f); lp1.setMargins(0, dp(4), dp(4), dp(4));
        LinearLayout.LayoutParams lp2 = new LinearLayout.LayoutParams(0, dp(46), 1f); lp2.setMargins(dp(4), dp(4), dp(4), dp(4));
        LinearLayout.LayoutParams lp3 = new LinearLayout.LayoutParams(0, dp(46), 1f); lp3.setMargins(dp(4), dp(4), 0, dp(4));
        bar.addView(swap, lp1);
        bar.addView(filterBtn, lp2);
        bar.addView(selAll, lp3);
        root.addView(bar);

        uploadBtn = button("Upload (0)", true, new View.OnClickListener() { @Override public void onClick(View v) { uploadSelected(); } });
        LinearLayout.LayoutParams uplp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48));
        uplp.setMargins(0, dp(4), 0, dp(4));
        root.addView(uploadBtn, uplp);

        grid = new GridView(this);
        grid.setNumColumns(GridView.AUTO_FIT);
        grid.setColumnWidth(dp(96));
        grid.setHorizontalSpacing(dp(4));
        grid.setVerticalSpacing(dp(4));
        grid.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);
        grid.setPadding(0, dp(4), 0, dp(4));
        adapter = new Adapter();
        grid.setAdapter(adapter);
        grid.setOnItemClickListener(new android.widget.AdapterView.OnItemClickListener() {
            @Override public void onItemClick(android.widget.AdapterView<?> p, View v, int pos, long id) { toggleSelect(visible.get(pos)); }
        });
        grid.setOnItemLongClickListener(new android.widget.AdapterView.OnItemLongClickListener() {
            @Override public boolean onItemLongClick(android.widget.AdapterView<?> p, View v, int pos, long id) { toggleSelect(visible.get(pos)); return true; }
        });
        grid.setOnScrollListener(new AbsListView.OnScrollListener() {
            @Override public void onScrollStateChanged(AbsListView v, int s) {}
            @Override public void onScroll(AbsListView v, int first, int cnt, int total) { retainWanted(); }
        });
        root.addView(grid, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        root.addView(text("Log", 12, MUTED, true));
        logScroll = new ScrollView(this);
        logView = new TextView(this);
        logView.setTextSize(11);
        logView.setTypeface(Typeface.MONOSPACE);
        logView.setTextColor(Color.rgb(170, 180, 182));
        logScroll.addView(logView);
        root.addView(logScroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(120)));

        setContentView(root);
    }

    // ------------------------------------------------------------------ browse callbacks (main thread)

    private final CameraBrowser.Listener listener = new CameraBrowser.Listener() {
        @Override public void onItems(List<CameraBrowser.Item> list) {
            items.clear();
            items.addAll(list);
            rebuildVisible();
            setStatus(items.size() + " JPEG dijumpai · pilih untuk upload");
        }
        @Override public void onKey(CameraBrowser.Item item) {
            rebuildVisible(); // keys change the "belum" filter and badges
        }
        @Override public void onThumb(CameraBrowser.Item item, Bitmap bmp) {
            thumbCache.put(item.documentId, bmp);
            updateVisible(item.documentId);
        }
        @Override public void onEmpty() {
            setStatus("Tiada JPEG dijumpai. Pilih folder DCIM/kad kamera");
            toast("Tiada JPEG dijumpai. Pilih folder DCIM/kad kamera");
        }
        @Override public void onError(String msg) {
            setStatus("Ralat: " + msg);
            log("Ralat browse: " + msg);
        }
    };

    private void rebuildVisible() {
        visible.clear();
        for (CameraBrowser.Item it : items) {
            if (!filterNewOnly || it.key == null || !store.isUploaded(it.key)) visible.add(it);
        }
        adapter.notifyDataSetChanged();
        updateCount();
    }

    private void updateCount() {
        uploadBtn.setText("Upload (" + selected.size() + ")");
    }

    private void retainWanted() {
        if (browser == null) return;
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < grid.getChildCount(); i++) {
            View v = grid.getChildAt(i);
            if (v == null) continue;
            Object t = v.getTag();
            if (t instanceof Adapter.Holder && ((Adapter.Holder) t).img.getTag() instanceof String)
                ids.add((String) ((Adapter.Holder) t).img.getTag());
        }
        browser.retainVisible(ids);
    }

    private void updateVisible(String id) {
        final Bitmap b = thumbCache.get(id);
        if (b == null) return;
        for (int i = 0; i < grid.getChildCount(); i++) {
            View v = grid.getChildAt(i);
            if (v == null) continue;
            Object t = v.getTag();
            if (t instanceof Adapter.Holder && id.equals(((Adapter.Holder) t).img.getTag())) {
                ((Adapter.Holder) t).img.setImageBitmap(b);
            }
        }
    }

    private void toggleSelect(CameraBrowser.Item it) {
        String id = it.documentId;
        if (selected.contains(id)) selected.remove(id); else selected.add(id);
        adapter.notifyDataSetChanged();
        updateCount();
    }

    private void selectAllNew() {
        selected.clear();
        for (CameraBrowser.Item it : visible) if (it.key == null || !store.isUploaded(it.key)) selected.add(it.documentId);
        adapter.notifyDataSetChanged();
        updateCount();
        setStatus("Dipilih " + selected.size() + " gambar baru");
    }

    // ------------------------------------------------------------------ upload

    private void uploadSelected() {
        if (busy) return;
        if (selected.isEmpty()) { toast("Pilih gambar dulu"); return; }
        final String eventId = App.prefs(this).getString("event_id", "");
        if (eventId.isEmpty()) { toast("Tiada event dipilih"); return; }
        if (App.api(this) == null) { toast("Belum log masuk"); return; }
        final int quality = App.prefs(this).getInt("quality", 50);
        final List<CameraBrowser.Item> list = new ArrayList<>();
        for (String id : selected) for (CameraBrowser.Item it : items) if (it.documentId.equals(id)) { list.add(it); break; }
        busy = true;
        setStatus("Menyalin " + list.size() + " gambar…");
        new Thread(new Runnable() { @Override public void run() {
            File dir = new File(getCacheDir(), "delayed-jpg");
            dir.mkdirs();
            for (final CameraBrowser.Item it : list) {
                if (destroyed) break;
                File out = null;
                try {
                    markState(it, true);
                    if (it.key == null) it.key = UploadQueue.keyFor(getContentResolver(), it.uri, it.name, it.size, it.lastModified);
                    out = new File(dir, CameraBrowser.digest(it.key) + ".jpg");
                    copyToCache(it, out);
                    final File f = out;
                    boolean added = queue.add(Uri.fromFile(f), it.name, eventId, quality, it.key);
                    markState(it, false);
                    if (!added) {
                        f.delete();
                        final String k = it.key;
                        runOnUiThread(new Runnable() { @Override public void run() {
                            store.markUploaded(k);
                            rebuildVisible();
                        }});
                        setStatus("Sudah dihantar: " + it.name);
                    } else {
                        setStatus("Giliran: " + it.name);
                    }
                } catch (final Exception e) {
                    if (out != null) out.delete();
                    log("Ralat salin " + it.name + ": " + e.getMessage());
                    setStatus("Ralat " + it.name + ": " + e.getMessage());
                    markState(it, false);
                }
            }
            busy = false;
            runOnUiThread(new Runnable() { @Override public void run() { adapter.notifyDataSetChanged(); } });
            setStatus("Siap giliran " + list.size() + " gambar");
        }}).start();
    }

    private void copyToCache(CameraBrowser.Item it, File out) throws IOException {
        InputStream in = getContentResolver().openInputStream(it.uri);
        if (in == null) throw new IOException("Tidak dapat baca " + it.name);
        FileOutputStream fo = new FileOutputStream(out);
        final long[] lastUi = {0};
        byte[] buf = new byte[64 * 1024];
        long done = 0;
        try {
            int n;
            while ((n = in.read(buf)) > 0) {
                fo.write(buf, 0, n);
                done += n;
                long now = System.currentTimeMillis();
                if (now - lastUi[0] > 200) {
                    lastUi[0] = now;
                    final long d = done, total = it.size;
                    setStatus("Menyalin " + it.name + (total > 0 ? " " + (int) (d * 100 / total) + "%" : ""));
                }
            }
        } finally {
            try { in.close(); } catch (IOException ignored) {}
            fo.close();
        }
    }

    private void markState(final CameraBrowser.Item it, final boolean on) {
        runOnUiThread(new Runnable() { @Override public void run() {
            if (on) downloading.add(it.documentId); else downloading.remove(it.documentId);
            adapter.notifyDataSetChanged();
        }});
    }

    // ------------------------------------------------------------------ grid adapter

    private class Adapter extends BaseAdapter {
        class Holder { ImageView img; TextView badge; }

        @Override public int getCount() { return visible.size(); }
        @Override public Object getItem(int i) { return visible.get(i); }
        @Override public long getItemId(int i) { return i; }

        @Override public View getView(int position, View convert, ViewGroup parent) {
            Holder h;
            if (convert == null) {
                RelativeLayout r = new RelativeLayout(DelayedActivity.this);
                r.setLayoutParams(new AbsListView.LayoutParams(dp(96), dp(96)));
                ImageView img = new ImageView(DelayedActivity.this);
                img.setScaleType(ImageView.ScaleType.CENTER_CROP);
                img.setId(View.generateViewId());
                r.addView(img, new RelativeLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
                TextView badge = new TextView(DelayedActivity.this);
                badge.setTextSize(11);
                badge.setTextColor(Color.WHITE);
                badge.setTypeface(Typeface.DEFAULT_BOLD);
                badge.setGravity(Gravity.CENTER);
                RelativeLayout.LayoutParams bp = new RelativeLayout.LayoutParams(dp(22), dp(22));
                bp.addRule(RelativeLayout.ALIGN_PARENT_TOP);
                bp.addRule(RelativeLayout.ALIGN_PARENT_RIGHT);
                bp.setMargins(0, dp(4), dp(4), 0);
                r.addView(badge, bp);
                h = new Holder();
                h.img = img;
                h.badge = badge;
                r.setTag(h);
                convert = r;
            } else {
                h = (Holder) convert.getTag();
            }
            final CameraBrowser.Item it = visible.get(position);
            String id = it.documentId;
            boolean up = it.key != null && store.isUploaded(it.key);
            boolean sel = selected.contains(id);
            if (downloading.contains(id)) { h.badge.setText("↻"); h.badge.setBackground(circle(AMBER)); }
            else if (up) { h.badge.setText("✓"); h.badge.setBackground(circle(GREEN)); }
            else if (sel) { h.badge.setText("✓"); h.badge.setBackground(circle(TEAL)); }
            else { h.badge.setText(""); h.badge.setBackground(null); }

            GradientDrawable bg = new GradientDrawable();
            bg.setColor(CARD);
            if (sel && !up) bg.setStroke(dp(2), TEAL_LIGHT);
            h.img.setBackground(bg);

            h.img.setTag(id);
            if (browser != null && it.key == null) browser.loadKey(it, listener);
            Bitmap b = thumbCache.get(id);
            if (b != null) h.img.setImageBitmap(b);
            else { h.img.setImageBitmap(null); if (browser != null) browser.loadThumb(it, listener); }
            return convert;
        }
    }
}
