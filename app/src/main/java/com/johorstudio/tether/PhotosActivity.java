package com.johorstudio.tether;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.util.LruCache;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AbsListView;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.RelativeLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * "Uploaded photos": this photographer's photos already on the server. 3-column grid of
 * thumbnails fetched from the API, tap to select and delete, pull down to refresh, page
 * 60 at a time as you scroll to the end.
 */
public class PhotosActivity extends Activity {
    private static final int PAGE = 60;
    private static final int THUMB = 480;

    private static final int BG = Color.rgb(22, 28, 31), CARD = Color.rgb(32, 39, 43), TEAL = Color.rgb(31, 111, 104),
            TEAL_LIGHT = Color.rgb(108, 196, 185), MUTED = Color.rgb(150, 165, 168), RED = Color.rgb(224, 86, 78);

    private static class Photo {
        String id;
        long seq;
        long t;
    }

    private LiveApi api;
    private final List<Photo> photos = new ArrayList<>();
    private final Set<String> selected = new HashSet<>();

    private GridView grid;
    private Adapter adapter;
    private Button deleteBtn;
    private TextView tvStatus, tvBrand;

    private ExecutorService pool;
    private final LruCache<String, Bitmap> cache = new LruCache<String, Bitmap>(4 * 1024 * 1024) {
        @Override protected int sizeOf(String key, Bitmap value) { return value.getByteCount(); }
    };

    private volatile boolean destroyed = false;
    private volatile boolean loading = false;
    private boolean lastPageFull = false;
    private String statusText = "";

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        api = App.api(this);
        if (api == null) { toast("Not logged in"); finish(); return; }
        pool = Executors.newFixedThreadPool(4);
        buildUi();
        loadFirstPage();
    }

    @Override protected void onDestroy() {
        destroyed = true;
        if (pool != null) pool.shutdownNow();
        super.onDestroy();
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

    private Button secondary(String s, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(s);
        b.setAllCaps(false);
        b.setTextColor(Color.WHITE);
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(dp(8));
        g.setColor(CARD);
        g.setStroke(dp(1), Color.rgb(70, 82, 87));
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
        runOnUiThread(new Runnable() { @Override public void run() {
            Toast.makeText(PhotosActivity.this, s, Toast.LENGTH_LONG).show();
        }});
    }

    private void setStatus(final String s) {
        statusText = s;
        runOnUiThread(new Runnable() { @Override public void run() { tvStatus.setText(s); } });
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);
        root.setPadding(dp(14), dp(20), dp(14), dp(10));

        TextView brand = text("JOHORSTUDIO.COM · LIVE (v" + App.version(PhotosActivity.this) + ")", 12, TEAL_LIGHT, true);
        brand.setLetterSpacing(0.1f);
        tvBrand = brand;
        root.addView(brand);
        root.addView(text("Uploaded photos", 22, Color.WHITE, true));

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        Button selectAll = secondary("Select all", new View.OnClickListener() { @Override public void onClick(View v) {
            selected.clear();
            for (Photo p : photos) selected.add(p.id);
            adapter.notifyDataSetChanged();
            updateDeleteBtn();
        }});
        Button refresh = secondary("Refresh", new View.OnClickListener() { @Override public void onClick(View v) { loadFirstPage(); } });
        deleteBtn = new Button(this);
        deleteBtn.setText("Delete (0)");
        deleteBtn.setAllCaps(false);
        deleteBtn.setTextColor(Color.WHITE);
        GradientDrawable dg = new GradientDrawable();
        dg.setCornerRadius(dp(8));
        dg.setColor(RED);
        deleteBtn.setBackground(dg);
        deleteBtn.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { confirmDelete(); } });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(46), 1f);
        lp.setMargins(0, dp(4), dp(4), dp(4));
        bar.addView(selectAll, lp);
        LinearLayout.LayoutParams lp2 = new LinearLayout.LayoutParams(0, dp(46), 1f);
        lp2.setMargins(dp(4), dp(4), dp(4), dp(4));
        bar.addView(refresh, lp2);
        LinearLayout.LayoutParams lp3 = new LinearLayout.LayoutParams(0, dp(46), 1f);
        lp3.setMargins(dp(4), dp(4), 0, dp(4));
        bar.addView(deleteBtn, lp3);
        root.addView(bar);
        updateDeleteBtn();

        tvStatus = text("", 14, Color.WHITE, false);
        root.addView(tvStatus);

        grid = new GridView(this);
        grid.setNumColumns(3);
        grid.setHorizontalSpacing(1);
        grid.setVerticalSpacing(1);
        grid.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);
        grid.setPadding(0, dp(4), 0, dp(4));
        adapter = new Adapter();
        grid.setAdapter(adapter);
        grid.setOnItemClickListener(new android.widget.AdapterView.OnItemClickListener() {
            @Override public void onItemClick(android.widget.AdapterView<?> p, View v, int pos, long id) { toggleSelect(photos.get(pos)); }
        });
        grid.setOnScrollListener(new AbsListView.OnScrollListener() {
            @Override public void onScrollStateChanged(AbsListView v, int s) {}
            @Override public void onScroll(AbsListView v, int first, int cnt, int total) {
                if (!loading && lastPageFull && total > 0 && first + cnt >= total - 6) loadNextPage();
            }
        });
        grid.setOnTouchListener(new PullToRefresh());
        root.addView(grid, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        setContentView(root);
        applyUpdate();
    }

    @Override protected void onResume() {
        super.onResume();
        applyUpdate();
    }

    /** Colour the brand header when the server has a newer APK; otherwise leave it teal. */
    private void applyUpdate() {
        Updater.check(this, new Updater.Callback() {
            @Override public void onResult(final String v) {
                if (tvBrand == null) return;
                if (v != null) {
                    tvBrand.setText("JOHORSTUDIO.COM · LIVE (v" + App.version(PhotosActivity.this) + " → v" + v + " tap to update)");
                    tvBrand.setTextColor(RED);
                    tvBrand.setOnClickListener(new View.OnClickListener() {
                        @Override public void onClick(View view) { Updater.promptUpdate(PhotosActivity.this); }
                    });
                } else {
                    tvBrand.setText("JOHORSTUDIO.COM · LIVE (v" + App.version(PhotosActivity.this) + ")");
                    tvBrand.setTextColor(TEAL_LIGHT);
                    tvBrand.setOnClickListener(null);
                }
            }
        });
    }

    // ------------------------------------------------------------------ loading

    private List<Photo> parse(JSONArray arr) {
        List<Photo> out = new ArrayList<>();
        if (arr == null) return out;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) continue;
            Photo p = new Photo();
            p.id = o.optString("id");
            p.seq = o.optLong("seq");
            p.t = o.optLong("t");
            if (p.id.isEmpty()) continue;
            out.add(p);
        }
        return out;
    }

    private void loadFirstPage() {
        loading = true;
        setStatus("Refreshing...");
        pool.execute(new Runnable() { @Override public void run() {
            try {
                final List<Photo> list = parse(api.listPhotos(null).optJSONArray("photos"));
                runOnUiThread(new Runnable() { @Override public void run() {
                    photos.clear();
                    photos.addAll(list);
                    lastPageFull = list.size() >= PAGE;
                    loading = false;
                    adapter.notifyDataSetChanged();
                    setStatus(list.isEmpty() ? "No photos yet" : photos.size() + " photos");
                }});
            } catch (final LiveApi.ApiException e) {
                loading = false;
                handleError(e);
            } catch (final Exception e) {
                loading = false;
                runOnUiThread(new Runnable() { @Override public void run() {
                    setStatus(photos.isEmpty() ? "No photos yet" : photos.size() + " photos");
                    toast("Error: " + e.getMessage());
                }});
            }
        }});
    }

    private void loadNextPage() {
        if (photos.isEmpty()) return;
        final long before = photos.get(photos.size() - 1).seq;
        loading = true;
        pool.execute(new Runnable() { @Override public void run() {
            try {
                final List<Photo> list = parse(api.listPhotos(before).optJSONArray("photos"));
                runOnUiThread(new Runnable() { @Override public void run() {
                    photos.addAll(list);
                    lastPageFull = list.size() >= PAGE;
                    loading = false;
                    adapter.notifyDataSetChanged();
                }});
            } catch (final LiveApi.ApiException e) {
                loading = false;
                handleError(e);
            } catch (final Exception e) {
                loading = false;
                toast("Error: " + e.getMessage());
            }
        }});
    }

    private void handleError(final LiveApi.ApiException e) {
        runOnUiThread(new Runnable() { @Override public void run() {
            if (e.status == 401) { toast(e.getMessage()); finish(); return; }
            setStatus(photos.isEmpty() ? "No photos yet" : photos.size() + " photos");
            toast(e.getMessage());
        }});
    }

    // ------------------------------------------------------------------ thumbs

    private void loadThumb(final String id) {
        pool.execute(new Runnable() { @Override public void run() {
            try {
                byte[] bytes = api.photoBytes(id, THUMB);
                final Bitmap bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
                if (bmp == null) return;
                cache.put(id, bmp);
                runOnUiThread(new Runnable() { @Override public void run() { updateVisible(id, bmp); } });
            } catch (Exception ignored) { /* stale or network error: leave the placeholder */ }
        }});
    }

    private void updateVisible(String id, Bitmap bmp) {
        if (destroyed) return;
        for (int i = 0; i < grid.getChildCount(); i++) {
            View v = grid.getChildAt(i);
            if (v == null) continue;
            Object t = v.getTag();
            if (t instanceof Adapter.Holder && id.equals(((Adapter.Holder) t).img.getTag()))
                ((Adapter.Holder) t).img.setImageBitmap(bmp);
        }
    }

    // ------------------------------------------------------------------ selection + delete

    private void toggleSelect(Photo p) {
        if (selected.contains(p.id)) selected.remove(p.id); else selected.add(p.id);
        adapter.notifyDataSetChanged();
        updateDeleteBtn();
    }

    private void updateDeleteBtn() {
        int n = selected.size();
        deleteBtn.setText("Delete (" + n + ")");
        deleteBtn.setEnabled(n > 0);
        deleteBtn.setAlpha(n > 0 ? 1f : 0.45f);
    }

    private void confirmDelete() {
        final List<Photo> toDelete = new ArrayList<>();
        for (Photo p : photos) if (selected.contains(p.id)) toDelete.add(p);
        final int n = toDelete.size();
        if (n == 0) return;
        new AlertDialog.Builder(this)
                .setMessage("Delete " + n + " photos from the event? This cannot be undone.")
                .setPositiveButton("Delete", new DialogInterface.OnClickListener() { @Override public void onClick(DialogInterface d, int w) { doDelete(toDelete); } })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void doDelete(final List<Photo> toDelete) {
        deleteBtn.setEnabled(false);
        new Thread(new Runnable() { @Override public void run() {
            int ok = 0, failed = 0;
            for (final Photo p : toDelete) {
                if (destroyed) break;
                try {
                    api.deletePhoto(p.id);
                    ok++;
                    runOnUiThread(new Runnable() { @Override public void run() {
                        photos.remove(p);
                        selected.remove(p.id);
                        adapter.notifyDataSetChanged();
                        updateDeleteBtn();
                    }});
                } catch (Exception e) {
                    failed++;
                }
            }
            final int o = ok, f = failed;
            runOnUiThread(new Runnable() { @Override public void run() {
                toast(f == 0 ? "Deleted " + o + " photos" : "Deleted " + o + " photos, " + f + " failed");
                updateDeleteBtn();
                if (photos.isEmpty()) setStatus("No photos yet");
            }});
        }}).start();
    }

    // ------------------------------------------------------------------ pull to refresh

    private class PullToRefresh implements View.OnTouchListener {
        float startY = -1;
        boolean pulling = false;

        @Override public boolean onTouch(View v, MotionEvent ev) {
            switch (ev.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    startY = grid.getFirstVisiblePosition() == 0 && grid.getChildCount() > 0 && grid.getChildAt(0).getTop() >= 0 ? ev.getY() : -1;
                    pulling = false;
                    return false;
                case MotionEvent.ACTION_MOVE:
                    if (startY < 0) return false;
                    float dy = ev.getY() - startY;
                    boolean nowPulling = dy > dp(120);
                    if (nowPulling != pulling) {
                        pulling = nowPulling;
                        tvStatus.setText(pulling ? "Release to refresh" : statusText);
                    }
                    return dy > 0;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    boolean triggered = pulling;
                    startY = -1;
                    pulling = false;
                    tvStatus.setText(statusText);
                    if (triggered) loadFirstPage();
                    return triggered;
            }
            return false;
        }
    }

    // ------------------------------------------------------------------ grid adapter

    private class Adapter extends BaseAdapter {
        class Holder { ImageView img; View ring; TextView badge; TextView time; }

        private String timeLabel(Photo p) {
            long t = p.t;
            if (t <= 0) return "";
            if (t < 1_000_000_000_000L) t *= 1000; // seconds -> ms
            return new SimpleDateFormat("h:mm a", Locale.ENGLISH).format(new Date(t));
        }

        @Override public int getCount() { return photos.size(); }
        @Override public Object getItem(int i) { return photos.get(i); }
        @Override public long getItemId(int i) { return i; }

        @Override public View getView(int position, View convert, ViewGroup parent) {
            int gw = parent.getWidth() - parent.getPaddingLeft() - parent.getPaddingRight();
            final int cell = (gw > 0 ? gw : getResources().getDisplayMetrics().widthPixels - dp(32)) - 2;
            final int cellSize = cell / 3;
            Holder h;
            if (convert == null) {
                RelativeLayout r = new RelativeLayout(PhotosActivity.this);
                ImageView img = new ImageView(PhotosActivity.this);
                img.setScaleType(ImageView.ScaleType.CENTER_CROP);
                r.addView(img, new RelativeLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
                View ring = new View(PhotosActivity.this);
                r.addView(ring, new RelativeLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
                TextView badge = new TextView(PhotosActivity.this);
                badge.setTextSize(11);
                badge.setTextColor(Color.WHITE);
                badge.setTypeface(Typeface.DEFAULT_BOLD);
                badge.setGravity(Gravity.CENTER);
                RelativeLayout.LayoutParams bp = new RelativeLayout.LayoutParams(dp(22), dp(22));
                bp.addRule(RelativeLayout.ALIGN_PARENT_TOP);
                bp.addRule(RelativeLayout.ALIGN_PARENT_RIGHT);
                bp.setMargins(0, dp(4), dp(4), 0);
                r.addView(badge, bp);
                TextView tm = new TextView(PhotosActivity.this);
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
                h = new Holder();
                h.img = img;
                h.badge = badge;
                h.time = tm;
                h.ring = ring;
                r.setTag(h);
                convert = r;
            } else {
                h = (Holder) convert.getTag();
            }
            convert.setLayoutParams(new AbsListView.LayoutParams(cellSize, cellSize));
            final Photo p = photos.get(position);
            boolean sel = selected.contains(p.id);
            if (sel) { h.badge.setText("✓"); h.badge.setBackground(circle(TEAL)); }
            else { h.badge.setText(""); h.badge.setBackground(null); }

            GradientDrawable bg = new GradientDrawable();
            bg.setColor(CARD);
            h.img.setBackground(bg);
            GradientDrawable ring = new GradientDrawable();
            ring.setColor(Color.TRANSPARENT);
            if (sel) ring.setStroke(dp(2), TEAL_LIGHT);
            h.ring.setBackground(ring);

            h.time.setText(timeLabel(p));
            h.img.setTag(p.id);
            Bitmap cached = cache.get(p.id);
            if (cached != null) h.img.setImageBitmap(cached);
            else { h.img.setImageBitmap(null); loadThumb(p.id); }
            return convert;
        }
    }
}
