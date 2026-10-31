package com.johorstudio.tether;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.DocumentsContract;
import android.text.InputType;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * JS Tether v3 (JS Live Photo): Log masuk -> senarai EVENTS -> tether live.
 * The camera work and uploads run in TetherService so they continue in the background.
 */
public class MainActivity extends Activity implements TetherService.Listener {
    private static final String ACTION_USB_PERMISSION = "com.johorstudio.tether.USB_PERMISSION";
    private static final int REQ_PICK_FOLDER = 42;

    // JohorStudio.com colours
    private static final int BG = Color.rgb(22, 28, 31), CARD = Color.rgb(32, 39, 43), TEAL = Color.rgb(31, 111, 104),
            TEAL_LIGHT = Color.rgb(108, 196, 185), MUTED = Color.rgb(150, 165, 168), RED = Color.rgb(224, 86, 78),
            GREEN = Color.rgb(120, 220, 150), AMBER = Color.rgb(255, 190, 120);

    private UsbManager usb;
    private SharedPreferences prefs;
    private String screen = "";
    // live screen views
    private TextView tvCamera, tvServer, tvStage, tvCounts, tvLog, tvFolder;
    private ScrollView logScroll;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        usb = (UsbManager) getSystemService(Context.USB_SERVICE);
        prefs = App.prefs(this);
        IntentFilter f = new IntentFilter();
        f.addAction(ACTION_USB_PERMISSION);
        f.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        f.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(usbReceiver, f, Context.RECEIVER_EXPORTED);
        else registerReceiver(usbReceiver, f);
        handleIntent(getIntent());
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIntent(intent);
    }

    @Override protected void onResume() {
        super.onResume();
        TetherService.setListener(this);
        applyScreenOptions();
        onChanged();
    }

    @Override protected void onPause() {
        TetherService.setListener(null);
        super.onPause();
    }

    @Override protected void onDestroy() {
        try { unregisterReceiver(usbReceiver); } catch (Exception ignored) {}
        super.onDestroy();
    }

    @Override public void onBackPressed() {
        if ("live".equals(screen)) showEvents();
        else moveTaskToBack(true); // keep the service running
    }

    private void handleIntent(Intent intent) {
        Uri data = intent == null ? null : intent.getData();
        if (data != null && "jstether".equals(data.getScheme()) && "auth".equals(data.getHost())) {
            String token = data.getQueryParameter("token");
            if (token != null && !token.isEmpty()) {
                prefs.edit().putString("token", token).apply();
                TetherService s = TetherService.get();
                if (s != null) s.apiChanged();
                toast("Log masuk berjaya");
            }
        }
        UsbDevice d = intent == null ? null : (UsbDevice) intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
        if (App.token(this) == null) { showLogin(); return; }
        if (d != null) { askUsb(d); showLive(); return; }
        if (screen.isEmpty()) { if (prefs.getString("event_id", "").isEmpty()) showEvents(); else showLive(); }
        else if ("login".equals(screen)) showEvents();
    }

    // ------------------------------------------------------------------ small UI kit

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

    private LinearLayout column() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    private LinearLayout card() {
        LinearLayout l = column();
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(dp(10));
        g.setColor(CARD);
        l.setBackground(g);
        l.setPadding(dp(14), dp(10), dp(14), dp(10));
        return l;
    }

    private LinearLayout row(View... views) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        for (int i = 0; i < views.length; i++) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(48), 1f);
            lp.setMargins(i == 0 ? 0 : dp(4), dp(4), i == views.length - 1 ? 0 : dp(4), dp(4));
            r.addView(views[i], lp);
        }
        return r;
    }

    private LinearLayout page(String title, String sub) {
        LinearLayout root = column();
        root.setBackgroundColor(BG);
        root.setPadding(dp(18), dp(28), dp(18), dp(14));
        TextView brand = text("JOHORSTUDIO.COM · LIVE", 12, TEAL_LIGHT, true);
        brand.setLetterSpacing(0.1f);
        root.addView(brand);
        root.addView(text(title, 24, Color.WHITE, true));
        if (sub != null) root.addView(text(sub, 13, MUTED, false));
        return root;
    }

    private void space(LinearLayout l, int h) { View v = new View(this); l.addView(v, new LinearLayout.LayoutParams(1, dp(h))); }

    private void toast(final String s) {
        runOnUiThread(new Runnable() { @Override public void run() { Toast.makeText(MainActivity.this, s, Toast.LENGTH_LONG).show(); } });
    }

    private interface Bg { Object run() throws Exception; }
    private interface Done { void ok(Object r); }

    /** Run an API call off the UI thread. A 401 means the login expired: back to the login screen. */
    private void bg(final Bg work, final Done done) {
        new Thread(new Runnable() { @Override public void run() {
            try {
                final Object r = work.run();
                runOnUiThread(new Runnable() { @Override public void run() { done.ok(r); } });
            } catch (final LiveApi.ApiException e) {
                runOnUiThread(new Runnable() { @Override public void run() {
                    if (e.status == 401) { prefs.edit().remove("token").apply(); toast("Sila log masuk semula"); showLogin(); }
                    else toast(e.getMessage());
                }});
            } catch (final Exception e) {
                toast("Ralat: " + e.getMessage());
            }
        }}).start();
    }

    // ------------------------------------------------------------------ login

    private void showLogin() {
        screen = "login";
        LinearLayout root = page("Log masuk", "App jurugambar untuk live.johorstudio.com");
        space(root, 16);
        root.addView(text("Log masuk dengan akaun Google atau kod 6 digit yang dihantar ke email. Hanya email yang admin tambah boleh masuk. Log masuk kekal 30 hari.", 15, Color.WHITE, false));
        space(root, 12);
        root.addView(button("Log masuk", true, new View.OnClickListener() { @Override public void onClick(View v) {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(App.serverUrl(MainActivity.this) + "/login?app=1")));
        }}), new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)));
        space(root, 8);
        root.addView(button("Tetapan server", false, new View.OnClickListener() { @Override public void onClick(View v) { serverSettings(); } }),
                new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)));
        space(root, 12);
        root.addView(text("Server: " + App.serverUrl(this), 12, MUTED, false));
        setContentView(root);
    }

    private void serverSettings() {
        LinearLayout f = column();
        f.setPadding(dp(20), dp(8), dp(20), 0);
        f.addView(text("Alamat awam", 12, Color.GRAY, false));
        final EditText eUrl = new EditText(this);
        eUrl.setSingleLine(true);
        eUrl.setText(App.serverUrl(this));
        f.addView(eUrl);
        f.addView(text("Alamat Tailscale (admin sahaja, cuba dulu; kosongkan jika tiada)", 12, Color.GRAY, false));
        final EditText eFast = new EditText(this);
        eFast.setSingleLine(true);
        eFast.setHint("http://m10:8095");
        eFast.setText(App.fastUrl(this));
        f.addView(eFast);
        new AlertDialog.Builder(this).setTitle("Tetapan server").setView(f)
                .setPositiveButton("Simpan", new DialogInterface.OnClickListener() { @Override public void onClick(DialogInterface d, int w) {
                    String u = eUrl.getText().toString().trim();
                    prefs.edit().putString("server_url", u.isEmpty() ? LiveApi.DEFAULT_URL : u)
                            .putString("fast_url", eFast.getText().toString().trim()).apply();
                    TetherService s = TetherService.get();
                    if (s != null) s.apiChanged();
                    if ("login".equals(screen)) showLogin();
                }})
                .setNegativeButton("Batal", null).show();
    }

    // ------------------------------------------------------------------ events

    private void showEvents() {
        screen = "events";
        final LinearLayout root = page("EVENTS", null);
        final TextView who = text("…", 13, MUTED, false);
        root.addView(who);
        root.addView(row(
                button("+ EVENT baru", true, new View.OnClickListener() { @Override public void onClick(View v) { newEvent(); } }),
                button("Muat semula", false, new View.OnClickListener() { @Override public void onClick(View v) { showEvents(); } })));
        final LinearLayout list = column();
        ScrollView sv = new ScrollView(this);
        sv.addView(list);
        root.addView(sv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        root.addView(row(
                button("Tetapan server", false, new View.OnClickListener() { @Override public void onClick(View v) { serverSettings(); } }),
                button("Log keluar", false, new View.OnClickListener() { @Override public void onClick(View v) { logout(); } })));
        setContentView(root);
        list.addView(text("Memuat…", 14, MUTED, false));

        final LiveApi api = App.api(this);
        if (api == null) { showLogin(); return; }
        bg(new Bg() { @Override public Object run() throws Exception {
            JSONObject me = api.me();
            return new Object[]{me, api.events()};
        }}, new Done() { @Override public void ok(Object r) {
            if (!"events".equals(screen)) return;
            Object[] a = (Object[]) r;
            JSONObject me = ((JSONObject) a[0]).optJSONObject("user");
            JSONArray events = (JSONArray) a[1];
            who.setText(me.optString("email") + " · " + ("admin".equals(me.optString("role")) ? "Admin (nampak semua event)" : "Jurugambar")
                    + (((JSONObject) a[0]).optBoolean("storageOnline", true) ? "" : "\n⚠ Storan server offline"));
            list.removeAllViews();
            if (events == null || events.length() == 0) list.addView(text("Tiada event lagi. Tekan + EVENT baru.", 14, MUTED, false));
            else for (int i = 0; i < events.length(); i++) list.addView(eventCard(events.optJSONObject(i)));
        }});
    }

    private View eventCard(final JSONObject e) {
        LinearLayout c = card();
        String cur = prefs.getString("event_id", "");
        boolean selected = cur.equals(e.optString("id"));
        c.addView(text(e.optString("title") + (selected ? "  ●" : ""), 17, selected ? TEAL_LIGHT : Color.WHITE, true));
        String slot = e.isNull("mySlot") ? "" : " · Photographer " + e.optInt("mySlot");
        c.addView(text(prettyDate(e.optString("date")) + " · " + e.optInt("photoCount") + " gambar" + slot, 13, MUTED, false));
        c.addView(text(e.optString("url").replaceFirst("^https?://", ""), 12, TEAL_LIGHT, false));
        c.addView(row(
                button("Pilih", true, new View.OnClickListener() { @Override public void onClick(View v) { selectEvent(e); } }),
                button("Kongsi", false, new View.OnClickListener() { @Override public void onClick(View v) { share(e); } }),
                button("QR", false, new View.OnClickListener() { @Override public void onClick(View v) {
                    startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(App.serverUrl(MainActivity.this) + "/admin#" + e.optString("id"))));
                }})));
        c.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { selectEvent(e); } });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, dp(6), 0, dp(6));
        c.setLayoutParams(lp);
        return c;
    }

    private static String prettyDate(String d) {
        if (d == null || !d.matches("\\d{8}")) return "";
        try { return new SimpleDateFormat("d MMM yyyy", new Locale("ms", "MY")).format(new SimpleDateFormat("yyyyMMdd", Locale.US).parse(d)); }
        catch (Exception x) { return d; }
    }

    private void share(JSONObject e) {
        Intent s = new Intent(Intent.ACTION_SEND).setType("text/plain")
                .putExtra(Intent.EXTRA_TEXT, e.optString("title") + "\nGaleri live: " + e.optString("url"));
        startActivity(Intent.createChooser(s, "Kongsi pautan galeri"));
    }

    private void selectEvent(JSONObject e) {
        String old = prefs.getString("event_id", "");
        TetherService s = TetherService.get();
        if (!old.isEmpty() && !old.equals(e.optString("id")) && s != null && s.pending > 0) {
            toast(s.pending + " gambar dalam giliran masih dihantar ke event sebelum ini");
        }
        prefs.edit().putString("event_id", e.optString("id")).putString("event_title", e.optString("title"))
                .putString("event_folder", e.optString("folder")).putString("event_url", e.optString("url")).apply();
        showLive();
    }

    private void newEvent() {
        LinearLayout f = column();
        f.setPadding(dp(20), dp(8), dp(20), 0);
        f.addView(text("Tarikh (YYYYMMDD)", 12, Color.GRAY, false));
        final EditText eDate = new EditText(this);
        eDate.setInputType(InputType.TYPE_CLASS_NUMBER);
        eDate.setText(new SimpleDateFormat("yyyyMMdd", Locale.US).format(new Date()));
        f.addView(eDate);
        f.addView(text("Nama event", 12, Color.GRAY, false));
        final EditText eName = new EditText(this);
        eName.setSingleLine(true);
        eName.setHint("cth Konvo Tadika Bandar Putra");
        f.addView(eName);
        new AlertDialog.Builder(this).setTitle("EVENT baru").setView(f)
                .setPositiveButton("Cipta", new DialogInterface.OnClickListener() { @Override public void onClick(DialogInterface d, int w) {
                    final String date = eDate.getText().toString().trim(), name = eName.getText().toString().trim();
                    if (!date.matches("\\d{8}") || name.isEmpty()) { toast("Isi tarikh YYYYMMDD dan nama event"); return; }
                    final LiveApi api = App.api(MainActivity.this);
                    bg(new Bg() { @Override public Object run() throws Exception { return api.createEvent(date, name); } },
                        new Done() { @Override public void ok(Object r) { toast("Event dicipta"); selectEvent((JSONObject) r); } });
                }})
                .setNegativeButton("Batal", null).show();
    }

    private void logout() {
        new AlertDialog.Builder(this).setMessage("Log keluar dari app ini? Gambar dalam giliran akan dihantar bila log masuk semula.")
                .setPositiveButton("Log keluar", new DialogInterface.OnClickListener() { @Override public void onClick(DialogInterface d, int w) {
                    final LiveApi api = App.api(MainActivity.this);
                    if (api != null) new Thread(new Runnable() { @Override public void run() { api.logout(); } }).start();
                    prefs.edit().remove("token").apply();
                    TetherService s = TetherService.get();
                    if (s != null) s.apiChanged();
                    showLogin();
                }}).setNegativeButton("Batal", null).show();
    }

    // ------------------------------------------------------------------ live tether

    private void showLive() {
        screen = "live";
        LinearLayout root = page(prefs.getString("event_title", "Tiada event"), prefs.getString("event_url", "").replaceFirst("^https?://", ""));

        LinearLayout st = card();
        tvCamera = text("Kamera: -", 16, RED, true);
        tvServer = text("Server: -", 14, AMBER, false);
        tvStage = text("-", 14, Color.WHITE, false);
        tvCounts = text("Disimpan 0 · WebP/Upload giliran 0 · Dihantar 0", 14, Color.WHITE, true);
        st.addView(tvCamera); st.addView(tvServer); st.addView(tvStage); st.addView(tvCounts);
        root.addView(st);

        final Button quality = button("Format: " + App.qualityLabel(prefs.getInt("quality", 50)), false, null);
        quality.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { chooseQuality(quality); } });
        root.addView(row(quality,
                button("Tukar event", false, new View.OnClickListener() { @Override public void onClick(View v) { showEvents(); } })));
        root.addView(row(
                button("Sambung semula kamera", false, new View.OnClickListener() { @Override public void onClick(View v) {
                    TetherService s = TetherService.get();
                    if (s == null) findCamera(); else s.reconnect();
                }}),
                button("Cuba hantar sekarang", false, new View.OnClickListener() { @Override public void onClick(View v) {
                    TetherService s = TetherService.get();
                    if (s != null) s.retryNow();
                }})));

        root.addView(toggle("Hantar ke server", "upload_on", true));
        root.addView(toggle("Skrin sentiasa ON", "keep_screen", true));
        root.addView(toggle("Kecerahan minimum (bila app dibuka)", "dim", false));

        tvFolder = text(folderLabel(), 12, MUTED, false);
        tvFolder.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { pickFolder(); } });
        root.addView(tvFolder);

        root.addView(text("Log", 12, MUTED, true));
        logScroll = new ScrollView(this);
        tvLog = new TextView(this);
        tvLog.setTextSize(11);
        tvLog.setTypeface(Typeface.MONOSPACE);
        tvLog.setTextColor(Color.rgb(170, 180, 182));
        logScroll.addView(tvLog);
        root.addView(logScroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(root);

        applyScreenOptions();
        if (TetherService.get() == null) findCamera();
        onChanged();
        TetherService s = TetherService.get();
        if (s != null) {
            StringBuilder sb = new StringBuilder();
            synchronized (s.logs) { for (String l : s.logs) sb.append(l).append('\n'); }
            tvLog.setText(sb);
            scrollLog();
        }
    }

    private CheckBox toggle(String label, final String key, boolean def) {
        CheckBox cb = new CheckBox(this);
        cb.setText(label);
        cb.setTextColor(Color.WHITE);
        cb.setChecked(prefs.getBoolean(key, def));
        cb.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(CompoundButton b, boolean on) {
                prefs.edit().putBoolean(key, on).apply();
                applyScreenOptions();
            }
        });
        return cb;
    }

    private void chooseQuality(final Button b) {
        final String[] labels = new String[App.QUALITIES.length];
        int cur = prefs.getInt("quality", 50), sel = 1;
        for (int i = 0; i < labels.length; i++) {
            labels[i] = App.qualityLabel(App.QUALITIES[i]) + (App.QUALITIES[i] == 50 ? " (default)" : App.QUALITIES[i] == 0 ? " (fail asal, besar)" : "");
            if (App.QUALITIES[i] == cur) sel = i;
        }
        new AlertDialog.Builder(this).setTitle("Format hantar ke server")
                .setSingleChoiceItems(labels, sel, new DialogInterface.OnClickListener() { @Override public void onClick(DialogInterface d, int w) {
                    prefs.edit().putInt("quality", App.QUALITIES[w]).apply();
                    b.setText("Format: " + App.qualityLabel(App.QUALITIES[w]));
                    d.dismiss();
                }}).show();
    }

    /** Screen on / minimum brightness. Android only lets an app dim the screen while it is in front. */
    private void applyScreenOptions() {
        WindowManager.LayoutParams lp = getWindow().getAttributes();
        boolean live = "live".equals(screen);
        if (prefs.getBoolean("keep_screen", true)) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        lp.screenBrightness = live && prefs.getBoolean("dim", false) ? 0.01f : WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE;
        getWindow().setAttributes(lp);
    }

    @Override public void onChanged() {
        if (!"live".equals(screen) || tvCamera == null) return;
        TetherService s = TetherService.get();
        if (s == null) {
            tvCamera.setText("Kamera: tidak bersambung");
            tvCamera.setTextColor(RED);
            tvStage.setText("Sambungkan kamera (USB Connection: PC Remote)");
            return;
        }
        tvCamera.setText("Kamera: " + s.camera);
        tvCamera.setTextColor(s.cameraOk ? GREEN : RED);
        tvServer.setText("Server: " + s.server);
        tvServer.setTextColor(s.serverOk ? GREEN : AMBER);
        tvStage.setText(s.stage);
        tvCounts.setText("Disimpan " + s.saved + " · Giliran " + s.pending + " · Dihantar " + s.uploaded);
    }

    @Override public void onLog(String line) {
        if (tvLog == null || !"live".equals(screen)) return;
        CharSequence cur = tvLog.getText();
        String all = cur + line + "\n";
        if (all.length() > 40000) all = all.substring(all.length() - 30000);
        tvLog.setText(all);
        scrollLog();
    }

    private void scrollLog() {
        logScroll.post(new Runnable() { @Override public void run() { logScroll.fullScroll(ScrollView.FOCUS_DOWN); } });
    }

    // ------------------------------------------------------------------ phone folder

    private void pickFolder() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        startActivityForResult(i, REQ_PICK_FOLDER);
    }

    private String folderLabel() {
        String s = prefs.getString("tree", null);
        if (s == null) return "Folder telefon: Pictures/JS Tether/<event> (default) · ketik untuk tukar";
        try {
            String id = DocumentsContract.getTreeDocumentId(Uri.parse(s));
            return "Folder telefon: " + (id.startsWith("primary:") ? "Storan dalaman/" + id.substring(8) : id.replace(':', '/')) + " · ketik untuk tukar";
        } catch (Exception e) { return "Folder telefon: " + s; }
    }

    @Override protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == REQ_PICK_FOLDER && res == RESULT_OK && data != null && data.getData() != null) {
            Uri tree = data.getData();
            try {
                getContentResolver().takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            } catch (Exception e) { toast("Gagal simpan kebenaran folder: " + e.getMessage()); }
            prefs.edit().putString("tree", tree.toString()).apply();
            if (tvFolder != null) tvFolder.setText(folderLabel());
        }
    }

    // ------------------------------------------------------------------ USB

    private void findCamera() {
        for (UsbDevice d : usb.getDeviceList().values()) {
            if (Ptp.findPtpInterface(d) != null) { askUsb(d); return; }
        }
    }

    private void askUsb(UsbDevice d) {
        if (usb.hasPermission(d)) { TetherService.start(this, d); return; }
        Intent i = new Intent(ACTION_USB_PERMISSION).setPackage(getPackageName());
        int flags = Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0;
        usb.requestPermission(d, PendingIntent.getBroadcast(this, 0, i, flags));
    }

    private final BroadcastReceiver usbReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            UsbDevice d = i.getParcelableExtra(UsbManager.EXTRA_DEVICE);
            String a = i.getAction();
            if (ACTION_USB_PERMISSION.equals(a)) {
                if (d != null && i.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) TetherService.start(MainActivity.this, d);
                else toast("Kebenaran USB ditolak");
            } else if (UsbManager.ACTION_USB_DEVICE_DETACHED.equals(a)) {
                TetherService s = TetherService.get();
                if (s != null) s.detached(d);
            } else if (UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(a)) {
                if (d != null && Ptp.findPtpInterface(d) != null) askUsb(d);
            }
        }
    };

}
