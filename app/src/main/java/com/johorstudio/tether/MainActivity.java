package com.johorstudio.tether;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
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
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
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
 * JS Tether v3 (JS Live Photo): Sign in -> event list -> live tether.
 * The camera work and uploads run in TetherService so they continue in the background.
 */
public class MainActivity extends Activity implements TetherService.Listener {
    private static final String ACTION_USB_PERMISSION = "com.johorstudio.tether.USB_PERMISSION";
    private static final int REQ_PICK_FOLDER = 42;
    private static final int REQ_PICK_PHOTOS = 43;

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
        // Camera plugged in while Delayed mode is on: go straight to the SD browser (saved folder opens by itself).
        if (intent != null && UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(intent.getAction()) && delayedMode()) {
            if (!DelayedActivity.isOpen()) openDelayed();
            return;
        }
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
            if (announce) toast("Signed in");
            showLive();
        }});
    }

    private void saveShoot(JSONObject r) {
        JSONObject ev = r.optJSONObject("event");
        if (ev == null) ev = new JSONObject();
        String guestUrl = r.optString("guestUrl", "");
        if (guestUrl.isEmpty()) guestUrl = ev.optString("url", "");
        prefs.edit().putString("event_id", r.has("eventId") ? r.optString("eventId") : ev.optString("id"))
                .putString("event_title", ev.optString("title")).putString("event_folder", ev.optString("title"))
                .putString("event_url", ev.optString("url"))
                .putString("guest_url", guestUrl).putString("guest_code", r.optString("guestCode", ""))
                .putLong("ends_at", ev.optLong("endsAt"))
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
        b.setPadding(dp(10), dp(12), dp(10), dp(12));
        b.setMinHeight(dp(48));
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
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            lp.setMargins(i == 0 ? 0 : dp(4), dp(4), i == views.length - 1 ? 0 : dp(4), dp(4));
            r.addView(views[i], lp);
        }
        return r;
    }

    private LinearLayout page(String title, String sub) {
        LinearLayout root = column();
        root.setBackgroundColor(BG);
        root.setPadding(dp(18), dp(28), dp(18), dp(14));
        TextView brand = text("JOHORSTUDIO.COM · LIVE (v" + App.version(MainActivity.this) + ")", 12, TEAL_LIGHT, true);
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
                    if (e.status == 401) { prefs.edit().remove("token").apply(); showLogin("Code expired or event closed. Enter a new photographer code."); }
                    else toast(e.getMessage());
                }});
            } catch (final Exception e) {
                toast("Error: " + e.getMessage());
            }
        }}).start();
    }

    // ------------------------------------------------------------------ login (photographer code)

    private void showLogin(String message) {
        screen = "login";
        LinearLayout root = page("Photographer sign in", "Enter the 6-digit photographer code from your admin, and your name.");
        space(root, 12);
        if (message != null) root.addView(text(message, 14, AMBER, true));
        root.addView(text("Photographer code", 13, MUTED, false));
        final EditText code = new EditText(this);
        code.setInputType(InputType.TYPE_CLASS_NUMBER);
        code.setTextSize(30);
        code.setLetterSpacing(0.3f);
        code.setTextColor(Color.WHITE);
        code.setHint("123456");
        code.setText(prefs.getString("last_code", ""));
        code.setFilters(new android.text.InputFilter[]{ new android.text.InputFilter.LengthFilter(6) });
        root.addView(code);
        root.addView(text("Name (e.g. Aiman)", 13, MUTED, false));
        final EditText name = new EditText(this);
        name.setSingleLine(true);
        name.setTextColor(Color.WHITE);
        name.setText(prefs.getString("last_name", prefs.getString("photog_name", "")));
        root.addView(name);
        space(root, 12);
        final TextView err = text("", 14, RED, false);
        root.addView(button("Sign in", true, new View.OnClickListener() { @Override public void onClick(View v) {
            final String c = code.getText().toString().trim(), n = name.getText().toString().trim();
            if (c.length() != 6) { err.setText("Enter the 6-digit code."); return; }
            if (n.isEmpty()) { err.setText("Type your name."); return; }
            final LiveApi api = new LiveApi(App.serverUrl(MainActivity.this), App.fastUrl(MainActivity.this), null);
            err.setText("Checking…");
            new Thread(new Runnable() { @Override public void run() {
                try {
                    final JSONObject r = api.loginCode(c, n);
                    runOnUiThread(new Runnable() { @Override public void run() {
                        prefs.edit().putString("token", r.optString("token")).putString("photog_name", n)
                                .putString("last_code", c).putString("last_name", n).apply();
                        addServerHistory(App.serverUrl(MainActivity.this));
                        saveShoot(r);
                        TetherService s = TetherService.get();
                        if (s != null) s.apiChanged();
                        toast("Signed in as Photographer " + r.optInt("slot"));
                        showLive();
                        if (TetherService.get() == null) findCamera();
                    }});
                } catch (final Exception e) {
                    runOnUiThread(new Runnable() { @Override public void run() { err.setText(e.getMessage()); } });
                }
            }}).start();
        }}), new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(err);
        root.addView(text("Where do you get the code? From the JohorStudio.com admin or the organiser. The photographer code differs from the guest code on screen.", 13, MUTED, false));
        space(root, 8);
        root.addView(button("Server settings", false, new View.OnClickListener() { @Override public void onClick(View v) { serverSettings(); } }),
                new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(text("Server: " + App.serverUrl(this), 12, MUTED, false));
        java.util.List<String> servers = displayServers();
        if (!servers.isEmpty()) {
            space(root, 4);
            root.addView(text("Previous servers", 12, MUTED, true));
            for (final String s : servers) {
                TextView rowTv = new TextView(this);
                rowTv.setText(s);
                rowTv.setTextSize(12);
                rowTv.setTextColor(TEAL_LIGHT);
                rowTv.setPadding(0, dp(6), 0, dp(6));
                rowTv.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) {
                    prefs.edit().putString("server_url", s).apply();
                    TetherService t = TetherService.get();
                    if (t != null) t.apiChanged();
                    toast("Server set");
                    showLogin(null);
                }});
                root.addView(rowTv);
            }
        }
        setContentView(root);
        code.requestFocus();
    }

    private void serverSettings() {
        LinearLayout f = column();
        f.setPadding(dp(20), dp(8), dp(20), 0);
        f.addView(text("Public address", 12, Color.GRAY, false));
        final EditText eUrl = new EditText(this);
        eUrl.setSingleLine(true);
        eUrl.setText(App.serverUrl(this));
        f.addView(eUrl);
        f.addView(text("Tailscale address (admin only, try first; leave empty if none)", 12, Color.GRAY, false));
        final EditText eFast = new EditText(this);
        eFast.setSingleLine(true);
        eFast.setHint("http://m10:8095");
        eFast.setText(App.fastUrl(this));
        f.addView(eFast);
        new AlertDialog.Builder(this).setTitle("Server settings").setView(f)
                .setPositiveButton("Save", new DialogInterface.OnClickListener() { @Override public void onClick(DialogInterface d, int w) {
                    String u = eUrl.getText().toString().trim();
                    String saved = u.isEmpty() ? LiveApi.DEFAULT_URL : u;
                    prefs.edit().putString("server_url", saved)
                            .putString("fast_url", eFast.getText().toString().trim()).apply();
                    addServerHistory(saved);
                    TetherService s = TetherService.get();
                    if (s != null) s.apiChanged();
                    if ("login".equals(screen)) showLogin(null);
                }})
                .setNegativeButton("Cancel", null).show();
    }

    // ------------------------------------------------------------------ server history

    private java.util.List<String> serverHistory() {
        java.util.List<String> out = new java.util.ArrayList<>();
        String raw = prefs.getString("server_history", null);
        if (raw == null) return out;
        try {
            JSONArray a = new JSONArray(raw);
            for (int i = 0; i < a.length(); i++) {
                String s = a.optString(i);
                if (s != null && !s.isEmpty() && !out.contains(s)) out.add(s);
            }
        } catch (Exception ignored) {}
        return out;
    }

    private void addServerHistory(String url) {
        if (url == null) return;
        String u = url.trim();
        if (u.isEmpty()) return;
        java.util.List<String> list = serverHistory();
        list.remove(u);
        list.add(0, u);
        while (list.size() > 8) list.remove(list.size() - 1);
        JSONArray a = new JSONArray();
        for (String s : list) a.put(s);
        prefs.edit().putString("server_history", a.toString()).apply();
    }

    private java.util.List<String> displayServers() {
        java.util.List<String> list = serverHistory();
        String cur = App.serverUrl(this);
        if (!list.contains(cur)) list.add(0, cur);
        return list;
    }

    private void logout() {
        new AlertDialog.Builder(this)
                .setMessage("Log out? Queued photos will be sent when you sign in again for the same event.")
                .setPositiveButton("Log out", new DialogInterface.OnClickListener() { @Override public void onClick(DialogInterface d, int w) {
                    final LiveApi api = App.api(MainActivity.this);
                    if (api != null) new Thread(new Runnable() { @Override public void run() { api.logout(); } }).start();
                    prefs.edit().remove("token").apply();
                    TetherService s = TetherService.get();
                    if (s != null) s.apiChanged();
                    showLogin(null);
                }}).setNegativeButton("Cancel", null).show();
    }

    private String guestLink() {
        String g = prefs.getString("guest_url", null);
        if (g == null || g.isEmpty()) g = prefs.getString("event_url", "");
        return g;
    }

    private void share() {
        final LiveApi api = App.api(this);
        if (api == null) { showLogin(null); return; }
        new Thread(new Runnable() { @Override public void run() {
            try { final JSONObject r = api.shoot(); runOnUiThread(new Runnable() { @Override public void run() { saveShoot(r); showShareDialog(); } }); }
            catch (Exception e) { runOnUiThread(new Runnable() { @Override public void run() { showShareDialog(); } }); }
        }}).start();
    }

    private LinearLayout.LayoutParams fullBtn() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, dp(4), 0, 0);
        return lp;
    }

    private void showShareDialog() {
        final String link = guestLink();
        final AlertDialog d = new AlertDialog.Builder(this)
                .setTitle(prefs.getString("event_title", "Event"))
                .create();
        LinearLayout content = column();
        content.setPadding(dp(10), dp(6), dp(10), dp(6));

        final ImageView qr = new ImageView(this);
        qr.setImageBitmap(QrCode.toBitmap(link, dp(220)));
        qr.setBackgroundColor(Color.WHITE);
        qr.setPadding(dp(6), dp(6), dp(6), dp(6));
        LinearLayout.LayoutParams qp = new LinearLayout.LayoutParams(dp(232), dp(232));
        qp.gravity = Gravity.CENTER_HORIZONTAL;
        content.addView(qr, qp);
        qr.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { showQrFullscreen(link); } });

        TextView cap = text("Tap QR to enlarge", 12, Color.GRAY, false);
        cap.setGravity(Gravity.CENTER_HORIZONTAL);
        content.addView(cap);

        TextView linkText = new TextView(this);
        linkText.setText(link);
        linkText.setTextSize(14);
        linkText.setTextColor(TEAL);
        linkText.setTextIsSelectable(true);
        linkText.setPadding(0, dp(8), 0, dp(8));
        content.addView(linkText);

        content.addView(button("Copy to clipboard", true, new View.OnClickListener() { @Override public void onClick(View v) {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            cm.setPrimaryClip(ClipData.newPlainText("Guest link", link));
            toast("Link copied");
        }}), fullBtn());

        content.addView(button("Open in browser", false, new View.OnClickListener() { @Override public void onClick(View v) {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(link)));
        }}), fullBtn());

        content.addView(button("Share...", false, new View.OnClickListener() { @Override public void onClick(View v) {
            Intent s = new Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, link);
            startActivity(Intent.createChooser(s, "Share guest link"));
        }}), fullBtn());

        content.addView(button("Close", false, new View.OnClickListener() { @Override public void onClick(View v) { d.dismiss(); } }), fullBtn());

        d.setView(content);
        d.show();
    }

    private void showQrFullscreen(final String link) {
        final int size = getResources().getDisplayMetrics().widthPixels - dp(24);
        final android.app.Dialog d = new android.app.Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
        FrameLayout frame = new FrameLayout(this);
        frame.setBackgroundColor(Color.WHITE);
        ImageView img = new ImageView(this);
        img.setScaleType(ImageView.ScaleType.FIT_CENTER);
        img.setImageBitmap(QrCode.toBitmap(link, size));
        frame.addView(img, new FrameLayout.LayoutParams(size, size, Gravity.CENTER));
        TextView hint = new TextView(this);
        hint.setText("Tap anywhere to close");
        hint.setTextSize(15);
        hint.setTextColor(Color.DKGRAY);
        FrameLayout.LayoutParams hp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        hp.setMargins(0, 0, 0, dp(40));
        frame.addView(hint, hp);
        frame.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { d.dismiss(); } });
        d.setContentView(frame, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        d.show();
    }

    // ------------------------------------------------------------------ live tether

    private void showLive() {
        screen = "live";
        long ends = prefs.getLong("ends_at", 0);
        String sub = "Photographer " + prefs.getInt("slot", 0) + " · " + prefs.getString("photog_name", "")
                + (ends > 0 ? " · live until " + new SimpleDateFormat("HH:mm", Locale.US).format(new Date(ends)) : "");
        LinearLayout root = page(prefs.getString("event_title", "No event"), sub);

        LinearLayout st = card();
        tvCamera = text("Camera: -", 16, RED, true);
        tvServer = text("Server: -", 14, AMBER, false);
        tvStage = text("-", 14, Color.WHITE, false);
        tvCounts = text("Saved 0 · Queue 0 · Sent 0", 14, Color.WHITE, true);
        st.addView(tvCamera); st.addView(tvServer); st.addView(tvStage); st.addView(tvCounts);
        root.addView(st);

        final Button quality = button("Format: " + App.qualityLabel(prefs.getInt("quality", 50)), false, null);
        quality.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { chooseQuality(quality); } });
        root.addView(row(quality,
                button("Share gallery", false, new View.OnClickListener() { @Override public void onClick(View v) { share(); } }),
                button("Log out", false, new View.OnClickListener() { @Override public void onClick(View v) { logout(); } })));
        root.addView(row(
                button("Reconnect camera", false, new View.OnClickListener() { @Override public void onClick(View v) {
                    if (delayedMode()) { openDelayed(); return; }
                    TetherService s = TetherService.get();
                    if (s == null) findCamera(); else s.reconnect();
                }}),
                button("Send now", false, new View.OnClickListener() { @Override public void onClick(View v) {
                    TetherService s = TetherService.get();
                    if (s != null) s.retryNow();
                }})));

        root.addView(row(modeButton()));
        root.addView(row(
                button("Upload from phone", true, new View.OnClickListener() { @Override public void onClick(View v) { pickPhotos(); } }),
                button("Upload queue", false, new View.OnClickListener() { @Override public void onClick(View v) { showUploads(); } })));
        root.addView(row(
                button("Uploaded photos", false, new View.OnClickListener() { @Override public void onClick(View v) {
                    startActivity(new Intent(MainActivity.this, PhotosActivity.class));
                }})));

        root.addView(toggle("Send to server", "upload_on", true));
        root.addView(toggle("Screen always on", "keep_screen", true));
        root.addView(toggle("Minimum brightness (while app is open)", "dim", false));

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
            tvCamera.setText("Mode: Delayed (pull from SD)");
            tvCamera.setTextColor(AMBER);
            tvStage.setText("⚠ Set camera: MENU > Setup > USB Connection > Mass Storage, then plug in USB");
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
            labels[i] = App.qualityLabel(App.QUALITIES[i]) + (App.QUALITIES[i] == 50 ? " (default)" : App.QUALITIES[i] == 0 ? " (original file, large)" : "");
            if (App.QUALITIES[i] == cur) sel = i;
        }
        new AlertDialog.Builder(this).setTitle("Upload format")
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
        if (delayedMode() && s == null) {
            tvCamera.setText("Mode: Delayed (pull from SD)");
            tvCamera.setTextColor(AMBER);
            tvStage.setText("⚠ Set camera: MENU > Setup > USB Connection > Mass Storage, then plug in USB");
            return;
        }
        if (s == null) {
            tvCamera.setText("Camera: not connected");
            tvCamera.setTextColor(RED);
            tvStage.setText("Connect the camera (USB Connection: PC Remote)");
            return;
        }
        tvCamera.setText("Camera: " + s.camera);
        tvCamera.setTextColor(s.cameraOk ? GREEN : RED);
        tvServer.setText("Server: " + s.server);
        tvServer.setTextColor(s.serverOk ? GREEN : AMBER);
        tvStage.setText(s.stage);
        tvCounts.setText("Saved " + s.saved + " · Queue " + s.pending + " · Sent " + s.uploaded);
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

    // ------------------------------------------------------------------ manual upload

    /** Photos copied from the camera to the phone: pick them, they go through the same queue (WebP, retry, skip if sent). */
    private void pickPhotos() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("image/jpeg");
        i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        startActivityForResult(i, REQ_PICK_PHOTOS);
    }

    private void gotPhotos(Intent data) {
        final java.util.List<Uri> uris = new java.util.ArrayList<>();
        if (data.getClipData() != null) for (int k = 0; k < data.getClipData().getItemCount(); k++) uris.add(data.getClipData().getItemAt(k).getUri());
        else if (data.getData() != null) uris.add(data.getData());
        for (Uri u : uris) {
            // keep access after a restart, so the queue can still read the file
            try { getContentResolver().takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION); } catch (Exception ignored) {}
        }
        if (uris.isEmpty()) return;
        toast(uris.size() + " photos selected, checking with server…");
        TetherService s = TetherService.get();
        if (s != null) { s.addManual(uris); return; }
        // no camera yet: start the service for the upload queue, then hand the photos over
        TetherService.start(this, null);
        final android.os.Handler h = new android.os.Handler();
        h.postDelayed(new Runnable() {
            int tries = 0;
            @Override public void run() {
                TetherService x = TetherService.get();
                if (x != null) x.addManual(uris);
                else if (++tries < 20) h.postDelayed(this, 250);
                else toast("Couldn't start the upload service");
            }
        }, 250);
    }

    /** Which photos are sent, waiting or failed (kept across restarts). */
    private void showUploads() {
        TetherService s = TetherService.get();
        if (s == null) { toast("Connect the camera or pick photos first"); return; }
        java.util.List<String[]> rows = s.uploadRows();
        LinearLayout list = column();
        list.setPadding(dp(18), dp(8), dp(18), dp(8));
        list.setBackgroundColor(Color.WHITE);
        if (rows.isEmpty()) list.addView(text("No photos sent yet.", 14, Color.GRAY, false));
        int n = 0;
        for (String[] r : rows) {
            if (++n > 300) break;
            LinearLayout line = new LinearLayout(this);
            line.setOrientation(LinearLayout.HORIZONTAL);
            TextView a = text(r[0], 14, Color.BLACK, false);
            a.setSingleLine(true); a.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
            boolean sent = r[1].startsWith("Sent") || r[1].startsWith("Already");
            boolean failed = r[1].startsWith("Failed");
            TextView b = text(r[1], 13, sent ? Color.rgb(46, 125, 79) : failed ? Color.rgb(163, 48, 42) : Color.rgb(102, 115, 122), false);
            b.setPadding(dp(10), 0, 0, 0);
            line.addView(a, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            line.addView(b);
            line.setPadding(0, dp(5), 0, dp(5));
            list.addView(line);
        }
        ScrollView sv = new ScrollView(this);
        sv.addView(list);
        new AlertDialog.Builder(this).setTitle("Upload queue").setView(sv)
                .setPositiveButton("Send now", new DialogInterface.OnClickListener() { @Override public void onClick(DialogInterface d, int w) {
                    TetherService x = TetherService.get(); if (x != null) x.retryNow();
                }})
                .setNegativeButton("Close", null).show();
    }

    // ------------------------------------------------------------------ phone folder

    private void pickFolder() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        startActivityForResult(i, REQ_PICK_FOLDER);
    }

    private String folderLabel() {
        String s = prefs.getString("tree", null);
        if (s == null) return "Phone folder: Pictures/JS Tether/<event> (default) · tap to change";
        try {
            String id = DocumentsContract.getTreeDocumentId(Uri.parse(s));
            return "Phone folder: " + (id.startsWith("primary:") ? "Internal storage/" + id.substring(8) : id.replace(':', '/')) + " · tap to change";
        } catch (Exception e) { return "Phone folder: " + s; }
    }

    @Override protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == REQ_PICK_PHOTOS && res == RESULT_OK && data != null) { gotPhotos(data); return; }
        if (req == REQ_PICK_FOLDER && res == RESULT_OK && data != null && data.getData() != null) {
            Uri tree = data.getData();
            try {
                getContentResolver().takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            } catch (Exception e) { toast("Failed to save folder permission: " + e.getMessage()); }
            prefs.edit().putString("tree", tree.toString()).apply();
            if (tvFolder != null) tvFolder.setText(folderLabel());
        }
    }

    // ------------------------------------------------------------------ USB

    private void findCamera() {
        if (delayedMode()) return; // Delayed reads the SD card via SAF, not USB/PTP.
        for (UsbDevice d : usb.getDeviceList().values()) {
            if (Ptp.findPtpInterface(d) != null) { askUsb(d); return; }
        }
    }

    private void askUsb(UsbDevice d) {
        if (delayedMode()) return; // Delayed reads the SD card via SAF, not USB/PTP.
        if (usb.hasPermission(d)) { TetherService.start(this, d); return; }
        Intent i = new Intent(ACTION_USB_PERMISSION).setPackage(getPackageName());
        int flags = Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0;
        usb.requestPermission(d, PendingIntent.getBroadcast(this, 0, i, flags));
    }

    private boolean delayedMode() { return "delayed".equals(prefs.getString("mode", "direct")); }
    private String modeLabel() { return delayedMode() ? "Delayed" : "Direct"; }

    private Button modeButton() {
        final Button b = button("Mode: " + modeLabel(), false, null);
        b.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) {
            final boolean delayed = !delayedMode();
            prefs.edit().putString("mode", delayed ? "delayed" : "direct").apply();
            b.setText("Mode: " + modeLabel());
            toast(delayed ? "Delayed mode: set camera MENU > Setup > USB Connection > Mass Storage"
                           : "Direct mode: live tether (PC Remote)");
            if (delayed) {
                if (TetherService.get() != null) {
                    startService(new Intent(MainActivity.this, TetherService.class).setAction(TetherService.ACTION_STOP));
                }
                openDelayed();
            }
            if (!delayed && TetherService.get() == null) findCamera();
        }});
        return b;
    }

    /** Delayed mode: no camera needed. Just open the SAF SD browser (USB Connection = Mass Storage). */
    private void openDelayed() {
        startActivity(new Intent(MainActivity.this, DelayedActivity.class));
    }

    private final BroadcastReceiver usbReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            UsbDevice d = i.getParcelableExtra(UsbManager.EXTRA_DEVICE);
            String a = i.getAction();
            if (ACTION_USB_PERMISSION.equals(a)) {
                if (d != null && i.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                    if (!delayedMode()) TetherService.start(MainActivity.this, d);
                } else toast("USB permission denied");
            } else if (UsbManager.ACTION_USB_DEVICE_DETACHED.equals(a)) {
                TetherService s = TetherService.get();
                if (s != null) s.detached(d);
            } else if (UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(a)) {
                if (delayedMode()) { if (!DelayedActivity.isOpen()) openDelayed(); }
                else if (d != null && Ptp.findPtpInterface(d) != null) askUsb(d);
            }
        }
    };

}
