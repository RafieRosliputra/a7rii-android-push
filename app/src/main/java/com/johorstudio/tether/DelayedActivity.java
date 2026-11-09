package com.johorstudio.tether;

import android.app.Activity;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.PorterDuff;
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
import android.widget.ProgressBar;
import android.widget.RelativeLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Locale;
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
    private static UploadQueue sharedQueue; // one queue per process: a second instance would re-read the same upload-queue.json and race on the cache files
    private static volatile DelayedActivity current;

    private final List<CameraBrowser.Item> items = new ArrayList<>();
    private final List<CameraBrowser.Item> visible = new ArrayList<>();
    private final LinkedHashSet<String> selected = new LinkedHashSet<>(); // documentIds
    private final Set<String> downloading = new HashSet<>();
    private final Map<String, Integer> copyPct = new java.util.concurrent.ConcurrentHashMap<>();

    /** Already on the server: stored key, queue history, or (key not read yet) name+size match. */
    private boolean isUp(CameraBrowser.Item it) {
        if (it.key != null) {
            if (store.isUploaded(it.key)) return true;
            String st = queue != null ? queue.statusOf(it.key) : null;
            return "ok".equals(st) || "dup".equals(st);
        }
        return store.hasNameSize(it.name, it.size);
    }

    /** Being copied from the card, or waiting/uploading in the queue. */
    private boolean isWorking(CameraBrowser.Item it) {
        if (downloading.contains(it.documentId)) return true;
        return it.key != null && queue != null && "queued".equals(queue.statusOf(it.key));
    }

    private void refreshGrid() {
        runOnUiThread(new Runnable() { @Override public void run() { if (!destroyed) rebuildVisible(); } });
    }
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
        synchronized (DelayedActivity.class) {
            if (sharedQueue == null) {
                final android.content.Context appCtx = getApplicationContext();
                sharedQueue = new UploadQueue(appCtx, App.api(this), new UploadQueue.Listener() {
                    @Override public void onQueue(int p, int up) { DelayedActivity a = current; if (a != null) { a.setStatus("Queue " + p + " · sent " + up); a.refreshGrid(); } }
                    @Override public void onServer(String text, boolean ok) { DelayedActivity a = current; if (a != null) a.setStatus(text); }
                    @Override public void onStage(String text) { DelayedActivity a = current; if (a != null) a.setStatus(text); }
                    @Override public void onLog(String s) { DelayedActivity a = current; if (a != null) a.log(s); }
                });
                sharedQueue.setSuccessListener(new UploadQueue.Success() {
                    @Override public void onSuccess(String name, String ref) {
                        if (ref != null) {
                            new UploadedStore(appCtx).markUploaded(ref);
                            new File(appCtx.getCacheDir(), "delayed-jpg/" + CameraBrowser.digest(ref) + ".jpg").delete();
                        }
                        final DelayedActivity a = current;
                        if (a != null) a.runOnUiThread(new Runnable() { @Override public void run() { a.rebuildVisible(); } });
                    }
                });
                sharedQueue.start();
            } else {
                sharedQueue.setApi(App.api(this));
            }
            queue = sharedQueue;
            current = this;
        }
        android.content.IntentFilter uf = new android.content.IntentFilter(android.hardware.usb.UsbManager.ACTION_USB_DEVICE_ATTACHED);
        if (android.os.Build.VERSION.SDK_INT >= 33) registerReceiver(usbRx, uf, android.content.Context.RECEIVER_EXPORTED);
        else registerReceiver(usbRx, uf);
        resolveTree();
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        destroyed = true;
        if (browser != null) browser.cancel();
        // The upload queue keeps running so uploads finish; it owns the copied cache files.
        if (current == this) current = null;
        waitHandler.removeCallbacksAndMessages(null);
        try { unregisterReceiver(usbRx); } catch (Exception ignored) {}
    }

    // ------------------------------------------------------------------ SAF tree

    private final android.os.Handler waitHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private int waitTries;
    private final android.content.BroadcastReceiver usbRx = new android.content.BroadcastReceiver() {
        @Override public void onReceive(android.content.Context c, Intent i) { resolveTree(); }
    };

    static boolean isOpen() { return current != null; }

    /** Open the folder chosen before. The card needs a few seconds to mount after plugging, so wait for it
     *  instead of asking for the folder again. The picker only opens when no folder was ever chosen. */
    private void resolveTree() {
        waitHandler.removeCallbacksAndMessages(null);
        String saved = App.prefs(this).getString("delayed_tree", null);
        if (saved == null) { openPicker(); return; }
        waitTries = 0;
        tryOpen(Uri.parse(saved));
    }

    private void tryOpen(final Uri tree) {
        if (destroyed) return;
        if (treeUsable(tree)) { startBrowse(tree); return; }
        if (waitTries++ >= 20) {
            setStatus("Camera card not detected. Plug in the camera (Mass Storage) or tap Change card.");
            return;
        }
        setStatus("Waiting for camera card… (" + waitTries + ")");
        waitHandler.postDelayed(new Runnable() { @Override public void run() { tryOpen(tree); } }, 1500);
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
        setStatus("Select camera card (DCIM)");
        try {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
            startActivityForResult(i, REQ_PICK_TREE);
        } catch (Exception e) {
            toast("No folder picker on this device");
        }
    }

    @Override protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == REQ_PICK_TREE && res == RESULT_OK && data != null && data.getData() != null) {
            Uri tree = data.getData();
            try {
                getContentResolver().takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } catch (Exception e) {
                toast("Failed to save permission: " + e.getMessage());
            }
            App.prefs(this).edit().putString("delayed_tree", tree.toString()).apply();
            startBrowse(tree);
        }
    }

    private void startBrowse(Uri tree) {
        if (browser != null) browser.cancel();
        browser = new CameraBrowser(this, tree);
        setStatus("Reading SD card…");
        log("Reading SD card (SAF)…");
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
        b.setTextSize(13);
        b.setSingleLine(false);
        b.setMaxLines(2);
        b.setMinHeight(dp(48));
        b.setMinimumHeight(dp(48));
        b.setGravity(Gravity.CENTER);
        b.setPadding(dp(4), dp(2), dp(4), dp(2));
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

        TextView brand = text("JOHORSTUDIO.COM · LIVE (v" + App.version(DelayedActivity.this) + ")", 12, TEAL_LIGHT, true);
        brand.setLetterSpacing(0.1f);
        root.addView(brand);
        root.addView(text("Import from SD (Delayed)", 22, Color.WHITE, true));
        root.addView(text("Camera setup: MENU > Setup > USB Connection > Mass Storage, then plug in the USB cable", 14, AMBER, true));
        root.addView(text("Pick JPEGs from the camera card; the phone sends only the selected ones.", 13, MUTED, false));

        tvStatus = text("Reading card…", 14, Color.WHITE, false);
        root.addView(tvStatus);

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setBaselineAligned(false);
        Button swap = button("Change card", false, new View.OnClickListener() { @Override public void onClick(View v) { openPicker(); } });
        filterBtn = button("Filter: all", false, new View.OnClickListener() { @Override public void onClick(View v) {
            filterNewOnly = !filterNewOnly;
            filterBtn.setText(filterNewOnly ? "Filter: new" : "Filter: all");
            rebuildVisible();
        }});
        Button selAll = button("Select all new", false, new View.OnClickListener() { @Override public void onClick(View v) { selectAllNew(); } });
        LinearLayout.LayoutParams lp1 = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f); lp1.setMargins(0, dp(4), dp(4), dp(4));
        LinearLayout.LayoutParams lp2 = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f); lp2.setMargins(dp(4), dp(4), dp(4), dp(4));
        LinearLayout.LayoutParams lp3 = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f); lp3.setMargins(dp(4), dp(4), 0, dp(4));
        bar.addView(swap, lp1);
        bar.addView(filterBtn, lp2);
        bar.addView(selAll, lp3);
        root.addView(bar);

        uploadBtn = button("Upload (0)", true, new View.OnClickListener() { @Override public void onClick(View v) { uploadSelected(); } });
        LinearLayout.LayoutParams uplp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        uplp.setMargins(0, dp(4), 0, dp(4));
        root.addView(uploadBtn, uplp);

        grid = new GridView(this);
        grid.setNumColumns(3);
        grid.setHorizontalSpacing(1);
        grid.setVerticalSpacing(1);
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
            setStatus(items.size() + " JPEGs found · select to upload");
        }
        @Override public void onKey(CameraBrowser.Item item) {
            rebuildVisible(); // keys change the "new" filter and badges
        }
        @Override public void onThumb(CameraBrowser.Item item, Bitmap bmp) {
            thumbCache.put(item.documentId, bmp);
            updateVisible(item.documentId);
        }
        @Override public void onEmpty() {
            setStatus("No JPEGs found. Select the DCIM/camera card folder");
            toast("No JPEGs found. Select the DCIM/camera card folder");
        }
        @Override public void onError(String msg) {
            setStatus("Error: " + msg);
            log("Browse error: " + msg);
        }
    };

    private void rebuildVisible() {
        visible.clear();
        for (CameraBrowser.Item it : items) {
            if (!filterNewOnly || !isUp(it)) visible.add(it);
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
        setStatus("Selected " + selected.size() + " new photos");
    }

    // ------------------------------------------------------------------ upload

    private void uploadSelected() {
        if (busy) return;
        if (selected.isEmpty()) { toast("Select photos first"); return; }
        final String eventId = App.prefs(this).getString("event_id", "");
        if (eventId.isEmpty()) { toast("No event selected"); return; }
        if (App.api(this) == null) { toast("Not logged in"); return; }
        final int quality = App.prefs(this).getInt("quality", 50);
        final List<CameraBrowser.Item> list = new ArrayList<>();
        for (String id : selected) for (CameraBrowser.Item it : items) if (it.documentId.equals(id)) { list.add(it); break; }
        busy = true;
        setStatus("Copying " + list.size() + " photos…");
        new Thread(new Runnable() { @Override public void run() {
            File dir = new File(getCacheDir(), "delayed-jpg");
            dir.mkdirs();
            for (final CameraBrowser.Item it : list) {
                if (destroyed) break;
                File out = null;
                try {
                    markState(it, true);
                    if (it.key == null) it.key = UploadQueue.keyFor(getContentResolver(), it.uri, it.name, it.size, it.lastModified);
                    String st = queue.statusOf(it.key);
                    if ("queued".equals(st)) { markState(it, false); setStatus("Already queued: " + it.name); continue; }
                    if ("ok".equals(st) || "dup".equals(st)) {
                        final String k0 = it.key;
                        runOnUiThread(new Runnable() { @Override public void run() { store.markUploaded(k0); rebuildVisible(); } });
                        markState(it, false); setStatus("Already sent: " + it.name); continue;
                    }
                    out = new File(dir, CameraBrowser.digest(it.key) + ".jpg");
                    copyToCache(it, out);
                    final File f = out;
                    boolean added = queue.add(Uri.fromFile(f), it.name, eventId, quality, it.key);
                    markState(it, false);
                    if (!added) {
                        setStatus("Already queued: " + it.name);
                    } else {
                        setStatus("Queued: " + it.name);
                    }
                } catch (final Exception e) {
                    if (out != null) out.delete();
                    log("Copy error " + it.name + ": " + e.getMessage());
                    setStatus("Error " + it.name + ": " + e.getMessage());
                    markState(it, false);
                }
            }
            busy = false;
            runOnUiThread(new Runnable() { @Override public void run() { selected.clear(); updateCount(); rebuildVisible(); } });
            setStatus(list.size() + " photos queued");
        }}).start();
    }

    private void copyToCache(CameraBrowser.Item it, File out) throws IOException {
        InputStream in = getContentResolver().openInputStream(it.uri);
        if (in == null) throw new IOException("Cannot read " + it.name);
        final File part = new File(out.getPath() + ".part");
        FileOutputStream fo = new FileOutputStream(part);
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
                    setStatus("Copying " + it.name + (total > 0 ? " " + (int) (d * 100 / total) + "%" : ""));
                    if (total > 0) { copyPct.put(it.documentId, (int) (d * 100 / total)); runOnUiThread(new Runnable() { @Override public void run() { adapter.notifyDataSetChanged(); } }); }
                }
            }
        } finally {
            try { in.close(); } catch (IOException ignored) {}
            fo.close();
        }
        if (!part.renameTo(out)) throw new IOException("Failed to save copy " + it.name);
    }

    private void markState(final CameraBrowser.Item it, final boolean on) {
        runOnUiThread(new Runnable() { @Override public void run() {
            if (on) downloading.add(it.documentId); else { downloading.remove(it.documentId); copyPct.remove(it.documentId); }
            adapter.notifyDataSetChanged();
        }});
    }

    // ------------------------------------------------------------------ grid adapter

    private class Adapter extends BaseAdapter {
        class Holder { ImageView img; View ring; TextView badge; TextView time; ProgressBar pb; }

        /** Time only, 12-hour with AM/PM (e.g. 4:20 PM). EXIF time from the key when known, else file time. */
        private String timeLabel(CameraBrowser.Item it) {
            try {
                if (it.key != null) {
                    String[] p = it.key.split("\\|");
                    if (p.length >= 3 && p[2].matches("\\d{4}:\\d{2}:\\d{2} \\d{2}:\\d{2}:\\d{2}")) {
                        int hh = Integer.parseInt(p[2].substring(11, 13));
                        String mm = p[2].substring(14, 16);
                        return ((hh % 12 == 0) ? 12 : hh % 12) + ":" + mm + (hh < 12 ? " AM" : " PM");
                    }
                }
            } catch (Exception ignored) {}
            if (it.lastModified <= 0) return "";
            return new SimpleDateFormat("h:mm a", Locale.ENGLISH).format(new Date(it.lastModified));
        }

        @Override public int getCount() { return visible.size(); }
        @Override public Object getItem(int i) { return visible.get(i); }
        @Override public long getItemId(int i) { return i; }

        @Override public View getView(int position, View convert, ViewGroup parent) {
            // square cells that exactly fill the grid width: 3 columns, 1 px gaps
            int gw = parent.getWidth() - parent.getPaddingLeft() - parent.getPaddingRight();
            final int cell = (gw > 0 ? gw : getResources().getDisplayMetrics().widthPixels - dp(32)) - 2;
            final int cellSize = cell / 3;
            Holder h;
            if (convert == null) {
                RelativeLayout r = new RelativeLayout(DelayedActivity.this);
                
                ImageView img = new ImageView(DelayedActivity.this);
                img.setScaleType(ImageView.ScaleType.CENTER_CROP);
                img.setId(View.generateViewId());
                r.addView(img, new RelativeLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
                View ring = new View(DelayedActivity.this);
                r.addView(ring, new RelativeLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
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
                TextView tm = new TextView(DelayedActivity.this);
                tm.setTextSize(10);
                tm.setTextColor(Color.WHITE);
                tm.setTypeface(Typeface.DEFAULT_BOLD);
                tm.setBackgroundColor(0xAA000000);
                tm.setPadding(dp(4), dp(1), dp(4), dp(1));
                RelativeLayout.LayoutParams tp = new RelativeLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                tp.addRule(RelativeLayout.ALIGN_PARENT_BOTTOM);
                tp.addRule(RelativeLayout.ALIGN_PARENT_LEFT);
                tp.setMargins(0, 0, 0, dp(5));
                r.addView(tm, tp);
                ProgressBar pb = new ProgressBar(DelayedActivity.this, null, android.R.attr.progressBarStyleHorizontal);
                pb.setMax(100);
                android.graphics.drawable.GradientDrawable pbg = new android.graphics.drawable.GradientDrawable();
                pbg.setColor(Color.rgb(230, 230, 230)); pbg.setCornerRadius(dp(8));
                android.graphics.drawable.GradientDrawable pfg = new android.graphics.drawable.GradientDrawable();
                pfg.setColor(RED); pfg.setCornerRadius(dp(8));
                android.graphics.drawable.ClipDrawable pcl = new android.graphics.drawable.ClipDrawable(pfg, Gravity.LEFT, android.graphics.drawable.ClipDrawable.HORIZONTAL);
                android.graphics.drawable.LayerDrawable pld = new android.graphics.drawable.LayerDrawable(new android.graphics.drawable.Drawable[]{pbg, pcl});
                pld.setId(0, android.R.id.background);
                pld.setId(1, android.R.id.progress);
                pb.setProgressDrawable(pld);
                // white pill with padding around the bar
                android.graphics.drawable.GradientDrawable pill = new android.graphics.drawable.GradientDrawable();
                pill.setColor(Color.WHITE); pill.setCornerRadius(dp(14));
                pb.setBackground(pill);
                pb.setPadding(dp(5), dp(5), dp(5), dp(5));
                pb.setElevation(dp(3));
                RelativeLayout.LayoutParams pp = new RelativeLayout.LayoutParams(dp(130), dp(26));
                pp.addRule(RelativeLayout.CENTER_IN_PARENT);
                r.addView(pb, pp);
                h = new Holder();
                h.img = img;
                h.badge = badge;
                h.time = tm;
                h.ring = ring;
                h.pb = pb;
                r.setTag(h);
                convert = r;
            } else {
                h = (Holder) convert.getTag();
            }
            convert.setLayoutParams(new AbsListView.LayoutParams(cellSize, cellSize));
            final CameraBrowser.Item it = visible.get(position);
            String id = it.documentId;
            boolean up = isUp(it);
            boolean working = !up && isWorking(it);
            boolean sel = selected.contains(id);
            if (up) { h.badge.setText("✓"); h.badge.setBackground(circle(GREEN)); }
            else if (working) { h.badge.setText("↻"); h.badge.setBackground(circle(RED)); }
            else if (sel) { h.badge.setText("✓"); h.badge.setBackground(circle(TEAL)); }
            else { h.badge.setText(""); h.badge.setBackground(null); }

            GradientDrawable bg = new GradientDrawable();
            bg.setColor(CARD);
            h.img.setBackground(bg);
            // highlight ring on top of the picture: red while copying/uploading, teal when selected
            GradientDrawable ring = new GradientDrawable();
            ring.setColor(Color.TRANSPARENT);
            if (working) ring.setStroke(dp(3), RED);
            else if (sel && !up) ring.setStroke(dp(2), TEAL_LIGHT);
            h.ring.setBackground(ring);
            // progress bar: real percent while copying from the card, endless while queued/uploading
            if (working) {
                Integer pct = copyPct.get(id);
                h.pb.setVisibility(View.VISIBLE);
                if (downloading.contains(id) && pct != null) { h.pb.setIndeterminate(false); h.pb.setProgress(pct); }
                else { h.pb.setIndeterminate(true); h.pb.setIndeterminateTintList(android.content.res.ColorStateList.valueOf(RED)); }
            } else h.pb.setVisibility(View.GONE);

            h.time.setText(timeLabel(it));
            h.img.setTag(id);
            if (browser != null && it.key == null) browser.loadKey(it, listener);
            Bitmap b = thumbCache.get(id);
            if (b != null) h.img.setImageBitmap(b);
            else { h.img.setImageBitmap(null); if (browser != null) browser.loadThumb(it, listener); }
            return convert;
        }
    }
}
