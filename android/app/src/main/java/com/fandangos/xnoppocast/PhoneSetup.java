package com.fandangos.xnoppocast;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * "Set up from phone": while the settings screen shows it, a tiny web page on the LAN
 * (http://&lt;this device&gt;:8765) with the same fields, so long values (the AltMount API key)
 * can be pasted on a phone instead of typed with a remote. Saving needs the PIN shown on the TV;
 * five wrong PINs stop the page. Secrets are never sent to the page, only received.
 */
final class PhoneSetup {
    interface OnSaved { void saved(); }

    static final int PORT = 8765;

    /** [pref key, label, default, "secret"?] in screen order. */
    static final String[][] FIELDS = {
            {"oppo_ip", "OPPO IP address", DirectPlay.Settings.D_OPPO, ""},
            {"smb_ip", "Server IP address (the SMB server with the AltMount share)", DirectPlay.Settings.D_SMB_IP, ""},
            {"smb_name", "Server name, as the OPPO lists it (NetBIOS, e.g. TOWER)", DirectPlay.Settings.D_SMB_NAME, ""},
            {"share", "Share", DirectPlay.Settings.D_SHARE, ""},
            {"root", "Folder in the share that holds AltMount's files", DirectPlay.Settings.D_ROOT, ""},
            {"read_ahead", "Read-ahead server (rclone WebDAV over the same files; empty = off)", DirectPlay.Settings.D_READ_AHEAD, ""},
            {"am_url", "AltMount address (to show which release AIOStreams is trying)", DirectPlay.Settings.D_AM_URL, ""},
            {"am_key", "AltMount API key (Configuration > System)", "", "secret"},
            {"server", "Xnoppo address, only for streams that aren't AltMount releases (optional)", "", ""},
            {"token", "Xnoppo token (only if CAST_TOKEN is set)", "", "secret"},
    };

    private final Context ctx;
    private final OnSaved onSaved;
    final String pin;
    private ServerSocket server;
    private volatile boolean running;
    private int wrongPins;

    PhoneSetup(Context ctx, OnSaved onSaved) {
        this.ctx = ctx.getApplicationContext();
        this.onSaved = onSaved;
        this.pin = String.format(Locale.ROOT, "%04d", new SecureRandom().nextInt(10000));
    }

    /** Starts the page; returns its address, e.g. http://10.27.33.60:8765/ */
    String start(String towards) throws IOException {
        ServerSocket ss;
        try {
            ss = new ServerSocket(PORT);
        } catch (IOException e) {
            ss = new ServerSocket(0);   // 8765 taken: any free port
        }
        server = ss;
        running = true;
        Thread t = new Thread(this::serve, "phone-setup");
        t.setDaemon(true);
        t.start();
        String ip = new Oppo(towards).localAddress();
        return "http://" + (ip.isEmpty() ? "?" : ip) + ":" + ss.getLocalPort() + "/";
    }

    void stop() {
        running = false;
        try {
            if (server != null) server.close();
        } catch (IOException ignored) {
        }
    }

    private void serve() {
        while (running) {
            try (Socket s = server.accept()) {
                s.setSoTimeout(10000);
                if (!isLocal(s.getInetAddress())) continue;   // LAN only
                handle(s);
            } catch (IOException e) {
                if (!running) return;
            }
        }
    }

    private void handle(Socket s) throws IOException {
        InputStream in = new BufferedInputStream(s.getInputStream());
        String requestLine = line(in);
        if (requestLine == null) return;
        int length = 0;
        String h;
        while ((h = line(in)) != null && !h.isEmpty()) {
            int i = h.indexOf(':');
            if (i > 0 && h.substring(0, i).trim().equalsIgnoreCase("Content-Length")) {
                try {
                    length = Math.min(64 * 1024, Integer.parseInt(h.substring(i + 1).trim()));
                } catch (NumberFormatException ignored) {
                }
            }
        }
        String[] parts = requestLine.split(" ");
        String method = parts.length > 0 ? parts[0] : "";
        String path = parts.length > 1 ? parts[1] : "/";
        if (method.equals("POST") && path.startsWith("/save")) {
            byte[] body = new byte[length];
            int got = 0;
            while (got < length) {
                int n = in.read(body, got, length - got);
                if (n <= 0) break;
                got += n;
            }
            respond(s, save(form(new String(body, 0, got, StandardCharsets.UTF_8))));
        } else if (method.equals("GET") && (path.equals("/") || path.startsWith("/?"))) {
            respond(s, page(""));
        } else {
            respond(s, page("Not found"));
        }
    }

    private static String line(InputStream in) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        int c;
        while ((c = in.read()) >= 0) {
            if (c == '\n') break;
            if (c != '\r') b.write(c);
            if (b.size() > 8192) break;
        }
        if (c < 0 && b.size() == 0) return null;
        return b.toString("UTF-8");
    }

    private static Map<String, String> form(String body) {
        Map<String, String> m = new HashMap<>();
        for (String kv : body.split("&")) {
            int i = kv.indexOf('=');
            if (i <= 0) continue;
            try {
                m.put(URLDecoder.decode(kv.substring(0, i), "UTF-8"), URLDecoder.decode(kv.substring(i + 1), "UTF-8"));
            } catch (Exception ignored) {
            }
        }
        return m;
    }

    private synchronized String save(Map<String, String> f) {
        if (wrongPins >= 5) return page("Too many wrong PINs. Open “Set up from phone” on the TV again.");
        if (!pin.equals(f.get("pin") == null ? "" : f.get("pin").trim())) {
            if (++wrongPins >= 5) stop();
            return page("Wrong PIN: use the 4 digits shown on the TV.");
        }
        SharedPreferences.Editor e = Api.prefs(ctx).edit();
        for (String[] fd : FIELDS) {
            String v = f.get(fd[0]);
            if (v == null) continue;
            v = v.trim();
            if (!fd[3].isEmpty() && v.isEmpty()) continue;   // empty secret = keep what's saved
            e.putString(fd[0], v);
        }
        e.apply();
        if (onSaved != null) onSaved.saved();
        return page("Saved ✓ The TV has the new settings.");
    }

    private static String esc(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    private String page(String note) {
        SharedPreferences p = Api.prefs(ctx);
        StringBuilder b = new StringBuilder();
        b.append("<!doctype html><html><head><meta charset=utf-8>")
                .append("<meta name=viewport content='width=device-width,initial-scale=1'>")
                .append("<title>Xnoppo Cast</title><style>")
                .append("body{font-family:sans-serif;background:#101418;color:#e8eef4;margin:0;padding:16px;max-width:640px}")
                .append("label{display:block;margin:14px 0 4px;font-weight:600}")
                .append("input{width:100%;box-sizing:border-box;padding:10px;font-size:16px;border-radius:6px;border:1px solid #445;background:#1b222a;color:#fff}")
                .append("button{margin-top:18px;padding:12px 20px;font-size:17px;border:0;border-radius:6px;background:#3b82f6;color:#fff}")
                .append(".note{padding:10px;border-radius:6px;background:#1f3a24;margin-bottom:8px}.hint{color:#9aa7b4;font-size:13px}")
                .append("</style></head><body><h2>Xnoppo Cast settings</h2>");
        if (!note.isEmpty()) b.append("<div class=note>").append(esc(note)).append("</div>");
        b.append("<form method=post action='/save'>");
        for (String[] f : FIELDS) {
            boolean secret = !f[3].isEmpty();
            b.append("<label>").append(esc(f[1])).append("</label><input name='").append(f[0]).append("'");
            if (secret) {
                boolean set = !p.getString(f[0], "").isEmpty();
                b.append(" type=password autocomplete=off placeholder='")
                        .append(set ? "saved (leave empty to keep)" : "not set").append("'>");
            } else {
                b.append(" value=\"").append(esc(p.getString(f[0], f[2]))).append("\">");
            }
        }
        b.append("<label>PIN shown on the TV</label><input name=pin inputmode=numeric autocomplete=off maxlength=4>")
                .append("<button type=submit>Save</button>")
                .append("<p class=hint>This page only works while “Set up from phone” is open on the TV.</p>")
                .append("</form></body></html>");
        return b.toString();
    }

    private static void respond(Socket s, String html) throws IOException {
        byte[] body = html.getBytes(StandardCharsets.UTF_8);
        OutputStream out = s.getOutputStream();
        out.write(("HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: " + body.length
                + "\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
        out.write(body);
        out.flush();
    }

    private static boolean isLocal(InetAddress a) {
        return a.isSiteLocalAddress() || a.isLoopbackAddress() || a.isLinkLocalAddress();
    }
}
