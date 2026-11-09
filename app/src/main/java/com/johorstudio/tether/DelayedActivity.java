package com.johorstudio.tether;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
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
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Delayed tether: browse the SD card over PTP, mark uploaded vs not, select JPEGs,
 * download only the selected ones and push them through the normal PhotoEncoder + UploadQueue path.
 */
public class DelayedActivity extends Activity {
    static volatile Ptp sharedPtp;
    static volatile android.hardware.usb.UsbDevice sharedDevice;

    private static final int BG = Color.rgb(22, 28, 31), CARD = Color.rgb(32, 39, 43), TEAL = Color.rgb(31, 111, 104),
            TEAL_LIGHT = Color.rgb(108, 196, 185), MUTED = Color.rgb(150, 165, 168), RED = Color.rgb(224, 86, 78),
            GREEN = Color.rgb(120, 220, 150), AMBER = Color.rgb(255, 190, 120);

    private Ptp ptp;
    private UploadedStore store;
    private CameraBrowser browser;
    private UploadQueue queue;

    private final List<CameraBrowser.Item> items = new ArrayList<>();
    private final List<CameraBrowser.Item> visible = new ArrayList<>();
    private final LinkedHashSet<String> selected = new LinkedHashSet<>();
    private final Set<String> downloading = new HashSet<>();
    private final LinkedHashMap<String, Bitmap> thumbCache = new LinkedHashMap<String, Bitmap>(64, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, Bitmap> e) { return size() > 300; }
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
        ptp = sharedPtp;
        if (ptp == null) { toast("Tiada kamera Delayed dibuka"); finish(); return; }
        store = new UploadedStore(this);
        browser = new CameraBrowser(this, ptp);
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
        log("Membaca kad SD…");
        browser.browse(listener);
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        destroyed = true;
        // Keep the queue running so uploads finish; only release the camera when idle.
        if (ptp != null && !busy) {
            try { ptp.closeSession(); } catch (Exception ignored) {}
            try { ptp.close(); } catch (Exception ignored) {}
        }
        if (sharedPtp == ptp) sharedPtp = null;
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
        root.addView(text("Pilih JPEG dari kad kamera; telefon hantar yang dipilih sahaja.", 13, MUTED, false));

        tvStatus = text("Membaca kad…", 14, Color.WHITE, false);
        root.addView(tvStatus);

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        filterBtn = button("Tapis: semua", false, new View.OnClickListener() { @Override public void onClick(View v) {
            filterNewOnly = !filterNewOnly;
            filterBtn.setText(filterNewOnly ? "Tapis: belum" : "Tapis: semua");
            rebuildVisible();
        }});
        Button selAll = button("Pilih semua baru", false, new View.OnClickListener() { @Override public void onClick(View v) { selectAllNew(); } });
        uploadBtn = button("Upload (0)", true, new View.OnClickListener() { @Override public void onClick(View v) { uploadSelected(); } });
        LinearLayout.LayoutParams lp1 = new LinearLayout.LayoutParams(0, dp(46), 1f); lp1.setMargins(0, dp(4), dp(4), dp(4));
        LinearLayout.LayoutParams lp2 = new LinearLayout.LayoutParams(0, dp(46), 1f); lp2.setMargins(dp(4), dp(4), dp(4), dp(4));
        LinearLayout.LayoutParams lp3 = new LinearLayout.LayoutParams(0, dp(46), 1f); lp3.setMargins(dp(4), dp(4), 0, dp(4));
        bar.addView(filterBtn, lp1);
        bar.addView(selAll, lp2);
        bar.addView(uploadBtn, lp3);
        root.addView(bar);

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
        @Override public void onThumb(CameraBrowser.Item item, Bitmap bmp) {
            thumbCache.put(item.key(), bmp);
            updateVisible(item.key());
        }
        @Override public void onEmpty(int storages, int handles) {
            setStatus("Kamera tak dedah kad SD dalam PC Remote");
            toast("Kamera tak dedah kad SD dalam PC Remote");
            dumpEmpty(storages, handles);
        }
        @Override public void onError(String msg) {
            setStatus("Ralat: " + msg);
            log("Ralat browse: " + msg);
        }
    };

    private void rebuildVisible() {
        visible.clear();
        for (CameraBrowser.Item it : items) {
            if (!filterNewOnly || !store.isUploaded(it.key())) visible.add(it);
        }
        adapter.notifyDataSetChanged();
        updateCount();
    }

    private void updateCount() {
        uploadBtn.setText("Upload (" + selected.size() + ")");
    }

    private void updateVisible(String key) {
        final Bitmap b = thumbCache.get(key);
        if (b == null) return;
        for (int i = 0; i < grid.getChildCount(); i++) {
            View v = grid.getChildAt(i);
            if (v == null) continue;
            Object t = v.getTag();
            if (t instanceof Adapter.Holder && key.equals(((Adapter.Holder) t).img.getTag())) {
                ((Adapter.Holder) t).img.setImageBitmap(b);
            }
        }
    }

    private void toggleSelect(CameraBrowser.Item it) {
        String k = it.key();
        if (selected.contains(k)) selected.remove(k); else selected.add(k);
        adapter.notifyDataSetChanged();
        updateCount();
    }

    private void selectAllNew() {
        selected.clear();
        for (CameraBrowser.Item it : visible) if (!store.isUploaded(it.key())) selected.add(it.key());
        adapter.notifyDataSetChanged();
        updateCount();
        setStatus("Dipilih " + selected.size() + " gambar baru");
    }

    private void dumpEmpty(final int storages, final int handles) {
        new Thread(new Runnable() { @Override public void run() {
            StringBuilder sb = new StringBuilder();
            sb.append("DELAYED dump: storageIDs=").append(storages)
              .append(" handleCount=").append(handles).append(" ops=[");
            try {
                Ptp.DeviceInfo d = ptp.getDeviceInfo();
                for (int op : d.operations) sb.append(String.format("0x%04X ", op));
            } catch (Exception e) { sb.append("tiada"); }
            sb.append("]");
            log(sb.toString());
        }}).start();
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
        for (String k : selected) for (CameraBrowser.Item it : items) if (it.key().equals(k)) { list.add(it); break; }
        busy = true;
        browser.setPaused(true);
        setStatus("Menarik " + list.size() + " gambar…");
        new Thread(new Runnable() { @Override public void run() {
            File dir = new File(getCacheDir(), "delayed-jpg");
            dir.mkdirs();
            for (final CameraBrowser.Item it : list) {
                try {
                    markState(it, true);
                    final File out = new File(dir, CameraBrowser.digest(it.key()) + ".jpg");
                    final long[] lastUi = {0};
                    Ptp.Response r = ptp.getObject(it.handle, new FileOutputStream(out), new Ptp.Progress() {
                        @Override public void onProgress(final long done, final long total) {
                            long now = System.currentTimeMillis();
                            if (now - lastUi[0] > 200) {
                                lastUi[0] = now;
                                setStatus("Menarik " + it.name + " " + (total > 0 ? (int) (done * 100 / total) : 0) + "%");
                            }
                        }
                    });
                    if (!r.ok()) throw new java.io.IOException("GetObject gagal 0x" + Integer.toHexString(r.code));
                    queue.add(Uri.fromFile(out), it.name, eventId, quality, it.key());
                    markState(it, false);
                    setStatus("Giliran: " + it.name);
                } catch (final Exception e) {
                    log("Ralat tarik " + it.name + ": " + e.getMessage());
                    setStatus("Ralat " + it.name + ": " + e.getMessage());
                    markState(it, false);
                }
            }
            busy = false;
            if (destroyed && ptp != null) {
                try { ptp.closeSession(); } catch (Exception ignored) {}
                try { ptp.close(); } catch (Exception ignored) {}
            }
            browser.setPaused(false);
            runOnUiThread(new Runnable() { @Override public void run() { adapter.notifyDataSetChanged(); } });
            setStatus("Siap giliran " + list.size() + " gambar");
        }}).start();
    }

    private void markState(final CameraBrowser.Item it, final boolean on) {
        runOnUiThread(new Runnable() { @Override public void run() {
            if (on) downloading.add(it.key()); else downloading.remove(it.key());
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
            String key = it.key();
            boolean up = store.isUploaded(key);
            boolean sel = selected.contains(key);
            if (downloading.contains(key)) { h.badge.setText("↻"); h.badge.setBackground(circle(AMBER)); }
            else if (up) { h.badge.setText("✓"); h.badge.setBackground(circle(GREEN)); }
            else if (sel) { h.badge.setText("✓"); h.badge.setBackground(circle(TEAL)); }
            else { h.badge.setText(""); h.badge.setBackground(null); }

            GradientDrawable bg = new GradientDrawable();
            bg.setColor(CARD);
            if (sel && !up) bg.setStroke(dp(2), TEAL_LIGHT);
            h.img.setBackground(bg);

            h.img.setTag(key);
            Bitmap b = thumbCache.get(key);
            if (b != null) h.img.setImageBitmap(b);
            else { h.img.setImageBitmap(null); browser.loadThumb(it, listener); }
            return convert;
        }
    }
}
