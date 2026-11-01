package com.fandangos.xnoppocast;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/** Tiny client for the Xnoppo cast API (lib/cast.py). Call off the main thread. */
final class Api {
    static final String DEFAULT_SERVER = "http://10.27.33.198:8089";
    private static final String PREFS = "xnoppo";

    static final class ApiException extends IOException {
        ApiException(String msg) { super(msg); }
    }

    final String base;
    private final String token;

    Api(Context ctx) {
        SharedPreferences p = prefs(ctx);
        String s = p.getString("server", DEFAULT_SERVER).trim();
        if (!s.startsWith("http://") && !s.startsWith("https://")) s = "http://" + s;
        while (s.endsWith("/")) s = s.substring(0, s.length() - 1);
        base = s;
        token = p.getString("token", "").trim();
    }

    static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    JSONObject get(String path) throws IOException, JSONException {
        return call("GET", path, null);
    }

    JSONObject post(String path, JSONObject body) throws IOException, JSONException {
        return call("POST", path, body);
    }

    JSONObject control(String action) throws IOException, JSONException {
        return post("/control", new JSONObject().put("action", action));
    }

    private JSONObject call(String method, String path, JSONObject body) throws IOException, JSONException {
        HttpURLConnection c = (HttpURLConnection) new URL(base + path).openConnection();
        try {
            c.setConnectTimeout(5000);
            c.setReadTimeout(30000);
            c.setRequestMethod(method);
            if (!token.isEmpty()) c.setRequestProperty("X-Xnoppo-Token", token);
            if (body != null) {
                byte[] data = body.toString().getBytes(StandardCharsets.UTF_8);
                c.setDoOutput(true);
                c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                c.setFixedLengthStreamingMode(data.length);
                try (OutputStream out = c.getOutputStream()) { out.write(data); }
            }
            int code = c.getResponseCode();
            InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
            String text = in == null ? "" : new String(readAll(in, 1 << 20), StandardCharsets.UTF_8);
            JSONObject o = text.trim().isEmpty() ? new JSONObject() : new JSONObject(text);
            if (code >= 400) throw new ApiException(o.optString("error", "HTTP " + code));
            return o;
        } finally {
            c.disconnect();
        }
    }

    static byte[] readAll(InputStream in, int limit) throws IOException {
        try (InputStream s = in) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int n;
            while ((n = s.read(buf)) > 0) {
                out.write(buf, 0, n);
                if (out.size() > limit) throw new IOException("response too large");
            }
            return out.toByteArray();
        }
    }
}
