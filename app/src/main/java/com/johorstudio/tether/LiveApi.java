package com.johorstudio.tether;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.Charset;

/**
 * Client for live.johorstudio.com (JS Live Photo server v3).
 * Talks to the admin's Tailscale address first when one is set, and falls back to the public domain.
 */
public class LiveApi {
    public static final String DEFAULT_URL = "https://live.johorstudio.com";
    private static final Charset UTF8 = Charset.forName("UTF-8");

    public static class ApiException extends IOException {
        public final int status;
        public final JSONObject body;
        ApiException(int status, String msg, JSONObject body) { super(msg); this.status = status; this.body = body; }
        /** Network problems, server errors and "storage offline" are worth retrying later. */
        public boolean retryable() { return status == 0 || status >= 500 || status == 429 || status == 409 || status == 401; }
    }

    private final String publicUrl;
    private final String fastUrl;   // optional, e.g. http://m10:8095 (admins with Tailscale)
    private final String token;
    private volatile String working; // last base URL that answered

    public LiveApi(String publicUrl, String fastUrl, String token) {
        this.publicUrl = trim(publicUrl == null || publicUrl.isEmpty() ? DEFAULT_URL : publicUrl);
        this.fastUrl = fastUrl == null || fastUrl.trim().isEmpty() ? null : trim(fastUrl.trim());
        this.token = token;
    }
    private static String trim(String u) { return u.endsWith("/") ? u.substring(0, u.length() - 1) : u; }
    public String publicUrl() { return publicUrl; }
    public String via() { return working == null ? publicUrl : working; }

    // ------------------------------------------------------------ low level

    private JSONObject request(String base, String method, String path, byte[] body, String type, int timeoutMs) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(base + path).openConnection();
        c.setConnectTimeout(base.equals(fastUrl) ? 2500 : 10000);
        c.setReadTimeout(timeoutMs);
        c.setRequestMethod(method);
        c.setRequestProperty("Accept", "application/json");
        c.setRequestProperty("User-Agent", "JSTether/4");
        if (token != null) c.setRequestProperty("Authorization", "Bearer " + token);
        try {
            if (body != null) {
                c.setDoOutput(true);
                c.setRequestProperty("Content-Type", type);
                c.setFixedLengthStreamingMode(body.length);
                OutputStream os = c.getOutputStream();
                os.write(body);
                os.close();
            }
            int code = c.getResponseCode();
            InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
            String text = in == null ? "" : readAll(in);
            JSONObject json;
            try { json = text.isEmpty() ? new JSONObject() : new JSONObject(text); }
            catch (JSONException e) { json = new JSONObject(); }
            if (code >= 400) throw new ApiException(code, json.optString("error", "HTTP " + code), json);
            return json;
        } finally {
            c.disconnect();
        }
    }

    private JSONObject call(String method, String path, byte[] body, String type, int timeoutMs) throws IOException {
        if (fastUrl != null) {
            try {
                JSONObject r = request(fastUrl, method, path, body, type, timeoutMs);
                working = fastUrl;
                return r;
            } catch (ApiException e) {
                working = fastUrl;
                throw e;            // the server answered: a real error, not a network problem
            } catch (IOException ignored) {
                // Tailscale off or unreachable: use the public domain
            }
        }
        try {
            JSONObject r = request(publicUrl, method, path, body, type, timeoutMs);
            working = publicUrl;
            return r;
        } catch (ApiException e) {
            throw e;
        } catch (IOException e) {
            throw new ApiException(0, "Tiada sambungan ke server (" + e.getMessage() + ")", null);
        }
    }

    private JSONObject json(String method, String path, JSONObject body) throws IOException {
        return call(method, path, body == null ? null : body.toString().getBytes(UTF8), "application/json", 20000);
    }

    private static String readAll(InputStream in) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
        in.close();
        return new String(b.toByteArray(), UTF8);
    }

    private static JSONObject obj(Object... kv) {
        JSONObject o = new JSONObject();
        try { for (int i = 0; i + 1 < kv.length; i += 2) o.put((String) kv[i], kv[i + 1]); }
        catch (JSONException ignored) {}
        return o;
    }

    // ------------------------------------------------------------ API

    /** Photographer code + name -> {token, slot, name, eventId, event{title,url,endsAt}} */
    public JSONObject loginCode(String code, String name) throws IOException {
        return json("POST", "/auth/code", obj("code", code, "name", name, "want", "photographer", "app", true));
    }

    /** Which event / Photographer N this session is for. 401 = code expired or event closed. */
    public JSONObject shoot() throws IOException { return json("GET", "/api/shoot", null); }

    public void logout() {
        try { json("POST", "/auth/logout", obj("role", "photographer")); } catch (IOException ignored) {}
    }

    /** Start a chunked upload into this photographer's "Photographer N" folder (event comes from the session). */
    public JSONObject startUpload(String eventId, String name, long size, String key) throws IOException {
        JSONObject b = obj("name", name, "size", size);
        if (key != null) try { b.put("key", key); } catch (JSONException ignored) {}
        return json("POST", "/api/shoot/uploads", b);
    }

    /** Which of these keys (name|size|time taken) are already in the event. */
    public java.util.Set<String> sentKeys(java.util.List<String> keys) throws IOException {
        JSONArray a = new JSONArray();
        for (String k : keys) a.put(k);
        JSONArray r = json("POST", "/api/shoot/check", obj("keys", a)).optJSONArray("sent");
        java.util.Set<String> out = new java.util.HashSet<>();
        if (r != null) for (int i = 0; i < r.length(); i++) out.add(r.optString(i));
        return out;
    }

    /** How many bytes the server already has (to resume). */
    public long uploadReceived(String uploadId) throws IOException {
        return json("GET", "/api/uploads/" + uploadId, null).optLong("received", 0);
    }

    public long putChunk(String uploadId, long offset, byte[] data, int len) throws IOException {
        byte[] b = data;
        if (len != data.length) { b = new byte[len]; System.arraycopy(data, 0, b, 0, len); }
        try {
            return call("PUT", "/api/uploads/" + uploadId + "?offset=" + offset, b, "application/octet-stream", 120000).optLong("received", -1);
        } catch (ApiException e) {
            if (e.status == 409 && e.body != null) return e.body.optLong("received", offset);
            throw e;
        }
    }

    public void putThumb(String uploadId, int size, byte[] data) throws IOException {
        call("PUT", "/api/uploads/" + uploadId + "/thumb/" + size, data, "application/octet-stream", 60000);
    }

    public JSONObject complete(String uploadId) throws IOException {
        return json("POST", "/api/uploads/" + uploadId + "/complete", null).optJSONObject("photo");
    }
}
