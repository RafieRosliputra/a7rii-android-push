package com.johorstudio.tether;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ContentValues;
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
import android.provider.MediaStore;
import android.text.InputType;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
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
    private static final int REQ_TAKE_PHOTO = 44;

    // JohorStudio.com colours
    private static final int BG = Color.rgb(22, 28, 31), CARD = Color.rgb(32, 39, 43), TEAL = Color.rgb(31, 111, 104),
            TEAL_LIGHT = Color.rgb(108, 196, 185), MUTED = Color.rgb(150, 165, 168), RED = Color.rgb(224, 86, 78),
            GREEN = Color.rgb(120, 220, 150), AMBER = Color.rgb(255, 190, 120);

    private UsbManager usb;
    private SharedPreferences prefs;
    private String screen = "";
    private Uri pendingCameraUri; // EXTRA_OUTPUT MediaStore row for an in-flight "Take photo" capture
    // live screen views
    private TextView tvCamera, tvServer, tvStage, tvCounts, tvLog, tvFolder, tvBrand;
    private ScrollView logScroll;
    // lock mode
    private boolean locked = false;
    private static boolean sLocked = false; // survives activity recreation
    private View lockOverlay;
    private int lockTaps = 0;
    private long lastLockTap = 0;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        locked = sLocked;
        usb = (UsbManager) getSystemService(Context.USB_SERVICE);
        prefs = App.prefs(this);
        IntentFilter f = new IntentFilter();
        f.addAction(ACTION_USB_PERMISSION);
        f.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        f.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(usbReceiver, f, Context.RECEIVER_EXPORTED);
        else registerReceiver(usbReceiver, f);
        if (b != null) {
            String cu = b.getString("camera_uri");
            if (cu != null) pendingCameraUri = Uri.parse(cu);
        }
        handleIntent(getIntent());
    }

    @Override protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        if (pendingCameraUri != null) out.putString("camera_uri", pendingCameraUri.toString());
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIntent(intent);
    }

    @Override protected void onResume() {
        super.onResume();
        TetherService.setListener(this);
        if (locked) { showLockOverlay(); applyLockBrightness(); }
        else applyScreenOptions();
        onChanged();
        applyUpdate();
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
        if (locked) return; // swallow back while locked
        moveTaskToBack(true); // keep the service running
    }

    @Override public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (locked && isLockKey(keyCode)) return true;
        return super.onKeyDown(keyCode, event);
    }

    @Override public boolean onKeyUp(int keyCode, KeyEvent event) {
        if (locked && isLockKey(keyCode)) return true;
        return super.onKeyUp(keyCode, event);
    }

    private boolean isLockKey(int keyCode) {
        return keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_MENU || keyCode == KeyEvent.KEYCODE_APP_SWITCH;
    }

    @Override public boolean dispatchTouchEvent(MotionEvent ev) {
        if (locked) {
            if (ev.getAction() == MotionEvent.ACTION_UP) registerLockTap();
            return true; // swallow all touches while locked
        }
        return super.dispatchTouchEvent(ev);
    }

    // ------------------------------------------------------------------ lock mode

    private void lock() {
        locked = true;
        sLocked = true;
        showLockOverlay();
        applyLockBrightness();
        try { startLockTask(); } catch (Exception ignored) {}
    }

    private void unlock() {
        locked = false;
        sLocked = false;
        if (lockOverlay != null && lockOverlay.getParent() != null) {
            ((ViewGroup) lockOverlay.getParent()).removeView(lockOverlay);
        }
        lockOverlay = null;
        lockTaps = 0;
        lastLockTap = 0;
        try { stopLockTask(); } catch (Exception ignored) {}
        applyScreenOptions();
    }

    private void showLockOverlay() {
        if (lockOverlay != null && lockOverlay.getParent() != null) return;
        FrameLayout frame = new FrameLayout(this);
        frame.setBackgroundColor(Color.BLACK);
        frame.setClickable(true);
        frame.setFocusable(true);
        TextView hint = new TextView(this);
        hint.setText("Locked · tap 3 times to unlock");
        hint.setTextSize(12);
        hint.setTextColor(Color.rgb(48, 48, 48));
        hint.setGravity(Gravity.CENTER);
        frame.addView(hint, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
        ((ViewGroup) getWindow().getDecorView()).addView(frame, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        lockOverlay = frame;
    }

    private void applyLockBrightness() {
        WindowManager.LayoutParams lp = getWindow().getAttributes();
        lp.screenBrightness = 0.05f;
        getWindow().setAttributes(lp);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    private void registerLockTap() {
        long now = System.currentTimeMillis();
        if (now - lastLockTap > 700) lockTaps = 0;
        lockTaps++;
        lastLockTap = now;
        if (lockTaps >= 3) unlock();
    }

    private void handleIntent(Intent intent) {
        Uri data = intent == null ? null : intent.getData();
        if (data != null && isJoinUri(data)) {
            if (handleJoin(joinCode(data), joinName(data))) return;
        }
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

    // ------------------------------------------------------------------ deep links

    private boolean isJoinUri(Uri u) {
        String s = u.getScheme(), h = u.getHost();
        return ("https".equals(s) && "live.johorstudio.com".equals(h) && u.getPath() != null && u.getPath().startsWith("/join/"))
                || ("jstether".equals(s) && "login".equals(h));
    }

    private String joinCode(Uri u) {
        if ("jstether".equals(u.getScheme())) return u.getQueryParameter("code");
        String p = u.getPath();
        if (p != null && p.startsWith("/join/")) {
            String c = p.substring("/join/".length());
            if (c.endsWith("/")) c = c.substring(0, c.length() - 1);
            return c;
        }
        return null;
    }

    private String joinName(Uri u) { return u.getQueryParameter("name"); }

    /** https://live.johorstudio.com/join/<code> or jstether://login?code=… — sign in without typing. */
    private boolean handleJoin(String code, String providedName) {
        if (code == null || !code.matches("[0-9]{6}")) {
            toast("Invalid photographer code");
            showLogin(null);
            return true;
        }
        // Same event already signed in: leave the current session alone.
        if (App.token(this) != null && code.equals(prefs.getString("last_code", ""))) return false;
        String name = (providedName != null && !providedName.trim().isEmpty())
                ? providedName.trim() : prefs.getString("last_name", "");
        if (name == null || name.isEmpty()) {
            showLogin("Enter your name to continue", code);
            return true;
        }
        toast("Signing in…");
        doLogin(code, name, null);
        return true;
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
        b.setSingleLine(false);
        b.setGravity(Gravity.CENTER);
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
        brand.setPadding(0, dp(4), dp(76), dp(4)); // leave room for the Lock button
        tvBrand = brand;
        FrameLayout header = new FrameLayout(this);
        header.addView(brand, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        header.addView(lockButton(), new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.END | Gravity.CENTER_VERTICAL));
        root.addView(header);
        root.addView(text(title, 24, Color.WHITE, true));
        if (sub != null) root.addView(text(sub, 13, MUTED, false));
        applyUpdate();
        return root;
    }

    /** Small rounded Lock button, top-right of the page header. */
    private TextView lockButton() {
        TextView b = new TextView(this);
        b.setText("🔒 Lock");
        b.setTextSize(12);
        b.setTextColor(Color.WHITE);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setGravity(Gravity.CENTER);
        b.setPadding(dp(10), dp(5), dp(10), dp(5));
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(dp(14));
        g.setColor(CARD);
        g.setStroke(dp(1), Color.rgb(70, 82, 87));
        b.setBackground(g);
        b.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { lock(); } });
        return b;
    }

    /** Colour the brand header when the server has a newer APK; otherwise leave it teal. */
    private void applyUpdate() {
        Updater.check(this, new Updater.Callback() {
            @Override public void onResult(final String v) {
                if (tvBrand == null) return;
                if (v != null) {
                    tvBrand.setText("JOHORSTUDIO.COM · LIVE (v" + App.version(MainActivity.this) + " → v" + v + " tap to update)");
                    tvBrand.setTextColor(RED);
                    tvBrand.setOnClickListener(new View.OnClickListener() {
                        @Override public void onClick(View view) { Updater.promptUpdate(MainActivity.this); }
                    });
                } else {
                    tvBrand.setText("JOHORSTUDIO.COM · LIVE (v" + App.version(MainActivity.this) + ")");
                    tvBrand.setTextColor(TEAL_LIGHT);
                    tvBrand.setOnClickListener(null);
                }
            }
        });
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

    private void showLogin(String message) { showLogin(message, null); }

    private void showLogin(String message, final String prefillCode) {
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
        code.setText(prefillCode != null ? prefillCode : prefs.getString("last_code", ""));
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
            doLogin(c, n, err);
        }}), new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(err);
        root.addView(text("Where do you get the code? From the JohorStudio.com admin or the organiser. The photographer code differs from the guest code on screen.", 13, MUTED, false));
        space(root, 8);
        root.addView(button("Server settings", false, new View.OnClickListener() { @Override public void onClick(View v) { serverSettings(); } }),
                new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(text("Server: " + App.serverUrl(this), 12, MUTED, false));

        java.util.List<JSONObject> events = eventHistory();
        if (!events.isEmpty()) {
            space(root, 6);
            root.addView(text("Previous events", 12, MUTED, true));
            for (final JSONObject ev : events) {
                final String ecode = ev.optString("code"), ename = ev.optString("name"), etitle = ev.optString("title");
                LinearLayout rowCard = card();
                TextView t1 = new TextView(this);
                t1.setText(etitle == null || etitle.isEmpty() ? "Event " + ecode : etitle);
                t1.setTextSize(14);
                t1.setTextColor(Color.WHITE);
                t1.setTypeface(Typeface.DEFAULT_BOLD);
                rowCard.addView(t1);
                TextView t2 = new TextView(this);
                t2.setText("as " + ename);
                t2.setTextSize(12);
                t2.setTextColor(MUTED);
                rowCard.addView(t2);
                rowCard.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) {
                    toast("Signing in…");
                    doLogin(ecode, ename, null);
                }});
                rowCard.setOnLongClickListener(new View.OnLongClickListener() { @Override public boolean onLongClick(View v) {
                    new AlertDialog.Builder(MainActivity.this)
                            .setMessage("Remove from list?")
                            .setPositiveButton("Remove", new DialogInterface.OnClickListener() { @Override public void onClick(DialogInterface d, int w) {
                                removeEventHistory(ecode);
                                showLogin(null);
                            }})
                            .setNegativeButton("Cancel", null).show();
                    return true;
                }});
                LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                rlp.setMargins(0, 0, 0, dp(6));
                root.addView(rowCard, rlp);
            }
        }

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(BG);
        scroll.addView(root);
        setContentView(scroll);
        if (prefillCode != null) name.requestFocus(); else code.requestFocus();
    }

    /** Sign in with a photographer code+name (Sign in button, previous-event tap, or deep link). */
    private void doLogin(final String c, final String n, final TextView err) {
        final LiveApi api = new LiveApi(App.serverUrl(this), App.fastUrl(this), null);
        if (err != null) err.setText("Checking…");
        new Thread(new Runnable() { @Override public void run() {
            try {
                final JSONObject r = api.loginCode(c, n);
                runOnUiThread(new Runnable() { @Override public void run() {
                    prefs.edit().putString("token", r.optString("token")).putString("photog_name", n)
                            .putString("last_code", c).putString("last_name", n).apply();
                    addEventHistory(c, n, eventTitle(r));
                    saveShoot(r);
                    TetherService s = TetherService.get();
                    if (s != null) s.apiChanged();
                    toast("Signed in as Photographer " + r.optInt("slot"));
                    showLive();
                    if (TetherService.get() == null) findCamera();
                }});
            } catch (final Exception e) {
                final String msg = e.getMessage() == null ? "Login failed" : e.getMessage();
                runOnUiThread(new Runnable() { @Override public void run() {
                    if (err != null) err.setText(msg); else toast(msg);
                }});
            }
        }}).start();
    }

    private String eventTitle(JSONObject r) {
        JSONObject ev = r == null ? null : r.optJSONObject("event");
        return ev == null ? "" : ev.optString("title", "");
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
                    TetherService s = TetherService.get();
                    if (s != null) s.apiChanged();
                    if ("login".equals(screen)) showLogin(null);
                }})
                .setNegativeButton("Cancel", null).show();
    }

    // ------------------------------------------------------------------ previous events

    private java.util.List<JSONObject> eventHistory() {
        java.util.List<JSONObject> out = new java.util.ArrayList<>();
        String raw = prefs.getString("event_history", null);
        if (raw == null) return out;
        try {
            JSONArray a = new JSONArray(raw);
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.optJSONObject(i);
                if (o != null) out.add(o);
            }
        } catch (Exception ignored) {}
        return out;
    }

    private void addEventHistory(String code, String name, String title) {
        if (code == null || code.isEmpty()) return;
        java.util.List<JSONObject> list = eventHistory();
        for (int i = list.size() - 1; i >= 0; i--) {
            if (code.equals(list.get(i).optString("code"))) list.remove(i);
        }
        JSONObject o = new JSONObject();
        try {
            o.put("title", title == null ? "" : title);
            o.put("code", code);
            o.put("name", name == null ? "" : name);
            o.put("ts", System.currentTimeMillis());
        } catch (Exception ignored) {}
        list.add(0, o);
        while (list.size() > 10) list.remove(list.size() - 1);
        JSONArray a = new JSONArray();
        for (JSONObject x : list) a.put(x);
        prefs.edit().putString("event_history", a.toString()).apply();
    }

    private void removeEventHistory(String code) {
        java.util.List<JSONObject> list = eventHistory();
        for (int i = list.size() - 1; i >= 0; i--) {
            if (code.equals(list.get(i).optString("code"))) list.remove(i);
        }
        JSONArray a = new JSONArray();
        for (JSONObject x : list) a.put(x);
        prefs.edit().putString("event_history", a.toString()).apply();
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
                button("Take photo", true, new View.OnClickListener() { @Override public void onClick(View v) { takePhoto(); } }),
                button("Choose from gallery", false, new View.OnClickListener() { @Override public void onClick(View v) { pickPhotos(); } })));
        root.addView(row(
                button("Upload queue", false, new View.OnClickListener() { @Override public void onClick(View v) { showUploads(); } }),
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
        if (locked) { applyLockBrightness(); return; }
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

    /** Camera capture straight into MediaStore, then the same upload pipeline as gallery picks. */
    private void takePhoto() {
        try {
            String title = prefs.getString("event_title", "").replaceAll("[\\\\/:*?\"<>|]", "-").trim();
            String rel = "Pictures/JS Tether" + (title.isEmpty() ? "" : "/" + title);
            String fname = "JST_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date()) + ".jpg";
            ContentValues cv = new ContentValues();
            cv.put(MediaStore.MediaColumns.DISPLAY_NAME, fname);
            cv.put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg");
            if (Build.VERSION.SDK_INT >= 29) cv.put(MediaStore.MediaColumns.RELATIVE_PATH, rel);
            Uri uri = getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv);
            if (uri == null) { toast("Couldn't create photo file"); return; }
            pendingCameraUri = uri;
            Intent i = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
            i.putExtra(MediaStore.EXTRA_OUTPUT, uri);
            startActivityForResult(i, REQ_TAKE_PHOTO);
        } catch (android.content.ActivityNotFoundException e) {
            toast("No camera app found");
        } catch (Exception e) {
            toast("Couldn't open camera: " + e.getMessage());
        }
    }

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
        if (req == REQ_TAKE_PHOTO) {
            Uri uri = pendingCameraUri;
            pendingCameraUri = null;
            if (res == RESULT_OK && uri != null) {
                gotPhotos(new Intent().setData(uri));
            } else if (uri != null) {
                try { getContentResolver().delete(uri, null, null); } catch (Exception ignored) {}
            }
            return;
        }
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
