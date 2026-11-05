package com.fandangos.xnoppocast;

import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Parcelable;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Turns an external-player intent into a POST /cast body.
 *
 * Understands what Nuvio (phone + TV) sends, which follows the MX Player / VLC / Just Player
 * conventions: data = stream URL; "title"; "headers" (String[] "K: V"); "position" (int ms) or
 * "extra_position" (long ms); subtitles as "subs" (Uri[]) + "subs.name" + "subs.filename"
 * ("<lang>_<name>.srt"), or "subtitle_uri"/"subtitle_name", or VLC's single "subtitles_location".
 */
final class CastRequest {
    private CastRequest() {}

    static String title(Intent in) {
        for (String k : new String[]{"title", Intent.EXTRA_TITLE, "forcename"}) {
            String v = in.getStringExtra(k);
            if (v != null && !v.trim().isEmpty()) return v.trim();
        }
        Uri u = in.getData();
        return u != null && u.getLastPathSegment() != null ? u.getLastPathSegment() : "Video";
    }

    /** Where the caller wants playback to start, in ms (0 = from the start). */
    static long positionMs(Intent in) {
        Bundle ex = in.getExtras() != null ? in.getExtras() : new Bundle();
        long pos = number(ex.get("position"));
        if (pos <= 0) pos = number(ex.get("extra_position"));
        return Math.max(0, pos);
    }

    /** Builds the request. Does network/content I/O for local subtitles: call off the main thread. */
    static JSONObject build(Context ctx, Intent in) throws JSONException, IOException {
        Uri data = in.getData();
        if (data == null) throw new IOException("no video URL was handed over");
        Bundle ex = in.getExtras() != null ? in.getExtras() : new Bundle();

        JSONObject o = new JSONObject();
        o.put("url", data.toString());
        o.put("title", title(in));

        long pos = number(ex.get("position"));
        if (pos <= 0) pos = number(ex.get("extra_position"));
        if (pos > 0) o.put("position_ms", pos);

        String[] hs = ex.getStringArray("headers");
        if (hs != null && hs.length > 0) o.put("headers", headers(hs));

        JSONArray subs = subtitles(ctx, ex);
        if (subs.length() > 0) o.put("subtitles", subs);
        return o;
    }

    private static long number(Object v) {
        return v instanceof Number ? ((Number) v).longValue() : -1;
    }

    /** Nuvio sends ["K: V", ...]; MX Player's own format is alternating [K, V, K, V]. */
    private static JSONObject headers(String[] hs) throws JSONException {
        JSONObject h = new JSONObject();
        boolean colonStyle = true;
        for (String s : hs) if (s == null || !s.contains(":")) { colonStyle = false; break; }
        if (colonStyle) {
            for (String s : hs) {
                int i = s.indexOf(':');
                h.put(s.substring(0, i).trim(), s.substring(i + 1).trim());
            }
        } else {
            for (int i = 0; i + 1 < hs.length; i += 2) h.put(hs[i], hs[i + 1]);
        }
        return h;
    }

    private static List<Uri> uris(Bundle ex, String key) {
        List<Uri> out = new ArrayList<>();
        Object v = ex.get(key);
        if (v instanceof Parcelable[]) {
            for (Parcelable p : (Parcelable[]) v) if (p instanceof Uri) out.add((Uri) p);
        } else if (v instanceof String[]) {
            for (String s : (String[]) v) if (s != null) out.add(Uri.parse(s));
        } else if (v instanceof Uri) {
            out.add((Uri) v);
        } else if (v instanceof String) {
            out.add(Uri.parse((String) v));
        }
        return out;
    }

    private static JSONArray subtitles(Context ctx, Bundle ex) throws JSONException {
        List<Uri> list = uris(ex, "subs");
        String[] names = ex.getStringArray("subs.name");
        String[] files = ex.getStringArray("subs.filename");
        if (list.isEmpty()) {
            list = uris(ex, "subtitle_uri");
            names = ex.getStringArray("subtitle_name");
            files = null;
        }
        if (list.isEmpty()) list = uris(ex, "subtitles_location");

        JSONArray arr = new JSONArray();
        for (int i = 0; i < list.size(); i++) {
            Uri u = list.get(i);
            String name = names != null && i < names.length ? names[i] : null;
            String file = files != null && i < files.length ? files[i] : null;
            JSONObject s = new JSONObject();
            String lang = langOf(file, name);
            if (lang != null) s.put("lang", lang);
            if (name != null) s.put("name", name);
            String scheme = u.getScheme() == null ? "" : u.getScheme().toLowerCase();
            boolean remote = (scheme.equals("http") || scheme.equals("https")) && !isLocalHost(u.getHost());
            if (remote) {
                s.put("url", u.toString());   // Xnoppo downloads it
            } else {
                try {                          // content://, file://, or a server on this device
                    s.put("data", readText(ctx, u));
                } catch (IOException | SecurityException e) {
                    continue;
                }
            }
            arr.put(s);
        }
        return arr;
    }

    /** Nuvio's subs.filename is "<lang>_<name>.srt". */
    private static String langOf(String file, String name) {
        if (file != null) {
            int i = file.indexOf('_');
            if (i > 0) return file.substring(0, i).trim().toLowerCase();
        }
        return null;
    }

    private static boolean isLocalHost(String host) {
        return host == null || host.equals("localhost") || host.startsWith("127.") || host.equals("::1");
    }

    private static String readText(Context ctx, Uri u) throws IOException {
        InputStream in;
        String scheme = u.getScheme() == null ? "" : u.getScheme().toLowerCase();
        if (scheme.startsWith("http")) {
            HttpURLConnection c = (HttpURLConnection) new URL(u.toString()).openConnection();
            c.setConnectTimeout(5000);
            c.setReadTimeout(15000);
            in = c.getInputStream();
        } else {
            ContentResolver cr = ctx.getContentResolver();
            in = cr.openInputStream(u);
            if (in == null) throw new IOException("can't open " + u);
        }
        byte[] raw = Api.readAll(in, 4 << 20);
        // Xnoppo re-detects the encoding; send bytes losslessly as latin-1-in-a-string only if not UTF-8
        String utf8 = new String(raw, StandardCharsets.UTF_8);
        return utf8.contains("�") ? new String(raw, StandardCharsets.ISO_8859_1) : utf8;
    }
}
