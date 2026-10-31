package com.johorstudio.tether;

import android.content.Context;
import android.content.SharedPreferences;

/** Settings shared by the activity and the service. */
public final class App {
    private App() {}

    public static SharedPreferences prefs(Context c) { return c.getSharedPreferences("tether", Context.MODE_PRIVATE); }

    public static String token(Context c) { return prefs(c).getString("token", null); }

    public static String serverUrl(Context c) { return prefs(c).getString("server_url", LiveApi.DEFAULT_URL); }

    /** Optional Tailscale address (admins), e.g. http://m10:8095 */
    public static String fastUrl(Context c) { return prefs(c).getString("fast_url", ""); }

    public static LiveApi api(Context c) {
        String t = token(c);
        return t == null ? null : new LiveApi(serverUrl(c), fastUrl(c), t);
    }

    public static final int[] QUALITIES = {40, 50, 60, 70, 80, 90, UploadQueue.QUALITY_JPG};

    public static String qualityLabel(int q) { return q == UploadQueue.QUALITY_JPG ? "JPG penuh" : "WebP " + q + "%"; }
}
