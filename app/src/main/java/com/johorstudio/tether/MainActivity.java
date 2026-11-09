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
import android.util.Log;
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

import java.io.IOException;
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
        moveTaskToBack(true); // keep the service running
    }

    private void handleIntent(Intent intent) {
        Uri data = intent == null ? null : intent.getData();
        if (data != null && "jstether".equals(data.getScheme()) && "auth".equals(data.getHost())) {
            // "Return to app" on live.johorstudio.com/shoot?app=1 hands over the photographer session.
            String token = data.getQueryParameter("token");
            if (token != null && !token.isEmpty()) {
                prefs.edit().putString("token", token).apply();
                refreshShoot(true);
                return;
            }
        }
        UsbDevice d = intent == null ? null : (UsbDevice) intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
        if (App.token(this) == null) { showLogin(null); return; }
        if (d != null) askUsb(d);
        if (!"live".equals(screen)) { showLive(); refreshShoot(false); }
    }

    /** Ask the server which event / Photographer N this session belongs to. 401 = code expired. */
    private void refreshShoot(final boolean announce) {
        final LiveApi api = App.api(this);
        if (api == null) { showLogin(null); return; }
        bg(new Bg() { @Override public Object run() throws Exception { return api.shoot(); } }, new Done() { @Override public void ok(Object r) {
            saveShoot((JSONObject) r);
            TetherService s = TetherService.get();
            if (s != null) s.apiChanged();
            if (announce) toast("Log masuk berjaya");
            showLive();
        }});
    }

    private void saveShoot(JSONObject r) {
        JSONObject ev = r.optJSONObject("event");
        if (ev == null) ev = new JSONObject();
        prefs.edit().putString("event_id", r.has("eventId") ? r.optString("eventId") : ev.optString("id"))
                .putString("event_title", ev.optString("title")).putString("event_folder", ev.optString("title"))
                .putString("event_url", ev.optString("url")).putLong("ends_at", ev.optLong("endsAt"))
                .putInt("slot", r.optInt("slot")).putString("photog_name", r.optString("name")).apply();
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
                    if (e.status == 401) { prefs.edit().remove("token").apply(); showLogin("Kod tamat atau event ditutup. Masukkan kod photographer baru."); }
                    else toast(e.getMessage());
                }});
            } catch (final Exception e) {
                toast("Ralat: " + e.getMessage());
            }
        }}).start();
    }

    // ------------------------------------------------------------------ login (photographer code)

    private void showLogin(String message) {
        screen = "login";
        LinearLayout root = page("Log masuk jurugambar", "Masukkan kod photographer 6 digit dari admin, dan nama awak.");
        space(root, 12);
        if (message != null) root.addView(text(message, 14, AMBER, true));
        root.addView(text("Kod photographer", 13, MUTED, false));
        final EditText code = new EditText(this);
        code.setInputType(InputType.TYPE_CLASS_NUMBER);
        code.setTextSize(30);
        code.setLetterSpacing(0.3f);
        code.setTextColor(Color.WHITE);
        code.setHint("123456");
        code.setFilters(new android.text.InputFilter[]{ new android.text.InputFilter.LengthFilter(6) });
        root.addView(code);
        root.addView(text("Nama (cth Aiman)", 13, MUTED, false));
        final EditText name = new EditText(this);
        name.setSingleLine(true);
        name.setTextColor(Color.WHITE);
        name.setText(prefs.getString("photog_name", ""));
        root.addView(name);
        space(root, 12);
        final TextView err = text("", 14, RED, false);
        root.addView(button("Masuk", true, new View.OnClickListener() { @Override public void onClick(View v) {
            final String c = code.getText().toString().trim(), n = name.getText().toString().trim();
            if (c.length() != 6) { err.setText("Masukkan 6 digit kod."); return; }
            if (n.isEmpty()) { err.setText("Taip nama awak."); return; }
            final LiveApi api = new LiveApi(App.serverUrl(MainActivity.this), App.fastUrl(MainActivity.this), null);
            err.setText("Menyemak…");
            new Thread(new Runnable() { @Override public void run() {
                try {
                    final JSONObject r = api.loginCode(c, n);
                    runOnUiThread(new Runnable() { @Override public void run() {
                        prefs.edit().putString("token", r.optString("token")).putString("photog_name", n).apply();
                        saveShoot(r);
                        TetherService s = TetherService.get();
                        if (s != null) s.apiChanged();
                        toast("Masuk sebagai Photographer " + r.optInt("slot"));
                        showLive();
                        if (TetherService.get() == null) findCamera();
                    }});
                } catch (final Exception e) {
                    runOnUiThread(new Runnable() { @Override public void run() { err.setText(e.getMessage()); } });
                }
            }}).start();
        }}), new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)));
        root.addView(err);
        root.addView(text("Mana nak dapat kod? Dari admin JohorStudio.com atau penganjur. Kod photographer berbeza dengan kod tetamu di skrin.", 13, MUTED, false));
        space(root, 8);
        root.addView(button("Tetapan server", false, new View.OnClickListener() { @Override public void onClick(View v) { serverSettings(); } }),
                new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44)));
        root.addView(text("Server: " + App.serverUrl(this), 12, MUTED, false));
        setContentView(root);
        code.requestFocus();
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
                    if ("login".equals(screen)) showLogin(null);
                }})
                .setNegativeButton("Batal", null).show();
    }

    private void logout() {
        new AlertDialog.Builder(this).setMessage("Log keluar? Gambar dalam giliran akan dihantar bila awak masuk semula dengan kod baru untuk event yang sama.")
                .setPositiveButton("Log keluar", new DialogInterface.OnClickListener() { @Override public void onClick(DialogInterface d, int w) {
                    final LiveApi api = App.api(MainActivity.this);
                    if (api != null) new Thread(new Runnable() { @Override public void run() { api.logout(); } }).start();
                    prefs.edit().remove("token").apply();
                    TetherService s = TetherService.get();
                    if (s != null) s.apiChanged();
                    showLogin(null);
                }}).setNegativeButton("Batal", null).show();
    }

    private void share() {
        Intent s = new Intent(Intent.ACTION_SEND).setType("text/plain")
                .putExtra(Intent.EXTRA_TEXT, prefs.getString("event_title", "") + "\nGaleri live: " + prefs.getString("event_url", ""));
        startActivity(Intent.createChooser(s, "Kongsi pautan galeri"));
    }

    // ------------------------------------------------------------------ live tether

    private void showLive() {
        screen = "live";
        long ends = prefs.getLong("ends_at", 0);
        String sub = "Photographer " + prefs.getInt("slot", 0) + " · " + prefs.getString("photog_name", "")
                + (ends > 0 ? " · live hingga " + new SimpleDateFormat("HH:mm", Locale.US).format(new Date(ends)) : "");
        LinearLayout root = page(prefs.getString("event_title", "Tiada event"), sub);

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
                button("Kongsi galeri", false, new View.OnClickListener() { @Override public void onClick(View v) { share(); } }),
                button("Log keluar", false, new View.OnClickListener() { @Override public void onClick(View v) { logout(); } })));
        root.addView(row(
                button("Sambung semula kamera", false, new View.OnClickListener() { @Override public void onClick(View v) {
                    TetherService s = TetherService.get();
                    if (s == null) findCamera(); else s.reconnect();
                }}),
                button("Cuba hantar sekarang", false, new View.OnClickListener() { @Override public void onClick(View v) {
                    TetherService s = TetherService.get();
                    if (s != null) s.retryNow();
                }})));

        root.addView(row(modeButton()));

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
        if (TetherService.get() == null && !delayedMode()) findCamera();
        onChanged();
        if (delayedMode() && TetherService.get() == null) {
            tvCamera.setText("Mod: Delayed (tarik dari SD)");
            tvCamera.setTextColor(AMBER);
            tvStage.setText("Sambungkan kamera (USB Connection: PC Remote) — ia buka skrin SD");
        }
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
        if (usb.hasPermission(d)) {
            if (delayedMode()) openDelayed(d); else TetherService.start(this, d);
            return;
        }
        Intent i = new Intent(ACTION_USB_PERMISSION).setPackage(getPackageName());
        int flags = Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0;
        usb.requestPermission(d, PendingIntent.getBroadcast(this, 0, i, flags));
    }

    private boolean delayedMode() { return "delayed".equals(prefs.getString("mode", "direct")); }
    private String modeLabel() { return delayedMode() ? "Delayed" : "Direct"; }

    private Button modeButton() {
        final Button b = button("Mod: " + modeLabel(), false, null);
        b.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) {
            final boolean delayed = !delayedMode();
            prefs.edit().putString("mode", delayed ? "delayed" : "direct").apply();
            b.setText("Mod: " + modeLabel());
            toast(delayed ? "Mod Delayed: kamera dibaca dari SD (PTP), bukan tether langsung"
                           : "Mod Direct: tether langsung (PC Remote)");
            if (delayed && TetherService.get() != null) {
                startService(new Intent(MainActivity.this, TetherService.class).setAction(TetherService.ACTION_STOP));
            }
            if (!delayed && TetherService.get() == null) findCamera();
        }});
        return b;
    }

    /** Delayed mode: open PTP + Sony handshake, then hand the session to DelayedActivity. */
    private void openDelayed(final UsbDevice d) {
        toast("Membuka kamera (Delayed)…");
        new Thread(new Runnable() { @Override public void run() {
            Ptp ptp = null;
            try {
                ptp = Ptp.open(usb, d);
                ptp.setLogger(new Ptp.Logger() { @Override public void log(String s) { Log.d("Delayed", s); } });
                ptp.drain();
                Ptp.DeviceInfo di = null;
                for (int attempt = 1; attempt <= 3; attempt++) {
                    try { di = ptp.getDeviceInfo(); break; }
                    catch (IOException e) { ptp.resetDevice(); }
                }
                if (di == null) throw new IOException("Kamera tidak membalas");
                ptp.openSession();
                if (di.supportsOp(Ptp.OC_Sony_SDIOConnect)) {
                    try { ptp.sonyHandshake(); } catch (IOException e) { Log.d("Delayed", "Handshake Sony: " + e.getMessage()); }
                }
                DelayedActivity.sharedPtp = ptp;
                DelayedActivity.sharedDevice = d;
                runOnUiThread(new Runnable() { @Override public void run() {
                    startActivity(new Intent(MainActivity.this, DelayedActivity.class));
                }});
            } catch (final Exception e) {
                final String msg = e.getMessage();
                if (ptp != null) try { ptp.close(); } catch (Exception ignored) {}
                runOnUiThread(new Runnable() { @Override public void run() { toast("Gagal buka kamera (Delayed): " + msg); } });
            }
        }}).start();
    }

    private final BroadcastReceiver usbReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            UsbDevice d = i.getParcelableExtra(UsbManager.EXTRA_DEVICE);
            String a = i.getAction();
            if (ACTION_USB_PERMISSION.equals(a)) {
                if (d != null && i.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                    if (delayedMode()) openDelayed(d); else TetherService.start(MainActivity.this, d);
                } else toast("Kebenaran USB ditolak");
            } else if (UsbManager.ACTION_USB_DEVICE_DETACHED.equals(a)) {
                TetherService s = TetherService.get();
                if (s != null) s.detached(d);
            } else if (UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(a)) {
                if (d != null && Ptp.findPtpInterface(d) != null) askUsb(d);
            }
        }
    };

}
