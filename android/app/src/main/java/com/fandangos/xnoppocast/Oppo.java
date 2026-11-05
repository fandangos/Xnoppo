package com.fandangos.xnoppocast;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.io.InputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * The OPPO UDP-20x control API (HTTP :436; one JSON object as the query string), woken by
 * "NOTIFY OREMOTE LOGIN" on UDP 7624. Same calls as the Kodi add-on's HTPC service
 * (streamd/oppo.py). Every call returns a JSONObject and never throws: failures come back as
 * {"success": false, "retInfo": ...}. Call off the main thread.
 */
final class Oppo {
    static final int PORT = 436, WAKE_PORT = 7624, CLEAR_PORT = 7437;

    final String ip;

    Oppo(String ip) { this.ip = ip; }

    static String query(JSONObject payload) {
        try {
            // the OPPO wants the JSON percent-encoded, with ':' ',' '/' left as they are
            return URLEncoder.encode(payload.toString(), "UTF-8").replace("+", "%20")
                    .replace("%3A", ":").replace("%2C", ",").replace("%2F", "/");
        } catch (Exception e) {
            return "";
        }
    }

    JSONObject call(String endpoint, JSONObject payload, int timeoutMs) {
        String u = "http://" + ip + ":" + PORT + "/" + endpoint + (payload == null ? "" : "?" + query(payload));
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(u).openConnection();
            c.setConnectTimeout(Math.min(timeoutMs, 5000));
            c.setReadTimeout(timeoutMs);
            InputStream in = c.getResponseCode() >= 400 ? c.getErrorStream() : c.getInputStream();
            String text = in == null ? "" : AltMount.readText(in, 1 << 20);
            try {
                return new JSONObject(text);
            } catch (JSONException e) {
                return fail("raw", text.length() > 300 ? text.substring(0, 300) : text);
            }
        } catch (SocketTimeoutException e) {
            JSONObject o = fail("retInfo", "no answer in " + timeoutMs / 1000 + "s");
            put(o, "timeout", true);
            return o;
        } catch (IOException e) {
            return fail("retInfo", String.valueOf(e.getMessage()));
        } finally {
            if (c != null) c.disconnect();
        }
    }

    JSONObject call(String endpoint, JSONObject payload) {
        return call(endpoint, payload, 10000);
    }

    private static JSONObject fail(String key, String value) {
        JSONObject o = new JSONObject();
        put(o, "success", false);
        put(o, key, value);
        return o;
    }

    static void put(JSONObject o, String k, Object v) {
        try {
            o.put(k, v);
        } catch (JSONException ignored) {
        }
    }

    static JSONObject obj(Object... kv) {
        JSONObject o = new JSONObject();
        for (int i = 0; i + 1 < kv.length; i += 2) put(o, (String) kv[i], kv[i + 1]);
        return o;
    }

    void wake() {
        try (DatagramSocket s = new DatagramSocket()) {
            byte[] m = "NOTIFY OREMOTE LOGIN".getBytes(StandardCharsets.US_ASCII);
            s.send(new DatagramPacket(m, m.length, InetAddress.getByName(ip), WAKE_PORT));
        } catch (IOException ignored) {
        }
    }

    boolean portOpen(int port, int timeoutMs) {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(ip, port), timeoutMs);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /** This phone's address as the OPPO sees it (signin wants it). */
    String localAddress() {
        try (DatagramSocket s = new DatagramSocket()) {
            s.connect(InetAddress.getByName(ip), 9);   // UDP connect only picks the route
            return s.getLocalAddress().getHostAddress();
        } catch (IOException e) {
            return "";
        }
    }

    JSONObject globalInfo() {
        return call("getglobalinfo", null, 5000);
    }

    boolean playing() {
        JSONObject g = globalInfo();
        return g.optBoolean("is_video_playing") || g.optBoolean("is_bdmv_playing");
    }

    JSONObject key(String code) {
        return call("sendremotekey", obj("key", code), 5000);
    }

    JSONObject setPlayTime(long seconds) {
        return call("setplaytime", obj("h", seconds / 3600, "m", seconds % 3600 / 60, "s", seconds % 60), 10000);
    }

    /**
     * Unmount the OPPO's /mnt/cifs* through the oppo-nuvio-helper endpoint (jailbroken players;
     * the OPPO's own API can't unmount). null when it isn't there, else true = all gone.
     */
    Boolean clearMounts() {
        if (!portOpen(CLEAR_PORT, 1000)) return null;
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL("http://" + ip + ":" + CLEAR_PORT + "/cgi-bin/clearcifs").openConnection();
            c.setConnectTimeout(3000);
            c.setReadTimeout(25000);
            JSONObject o = new JSONObject(AltMount.readText(c.getInputStream(), 1 << 16));
            org.json.JSONArray rem = o.optJSONArray("remaining");
            return o.optBoolean("ok") && (rem == null || rem.length() == 0);
        } catch (Exception e) {
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }
}
