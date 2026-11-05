package com.fandangos.xnoppocast;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.Socket;
import java.net.URI;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Play an AIOStreams/AltMount release on the OPPO straight from the server's share, no
 * Xnoppo involved: resolve the link, read ahead what the OPPO opens first (through the
 * server's rclone WebDAV server, so it lands in the server's cache), wake the OPPO, have it
 * mount the share as guest and play the file (or the Blu-ray folder with its menus).
 * The OPPO steps are the ones the Kodi add-on's HTPC service uses (streamd/oppo.py).
 */
final class DirectPlay {
    interface Listener { void stage(String text, String detail); }

    static final class Settings {
        final String oppoIp, smbIp, smbName, share, root, readAhead, amUrl, amKey;
        boolean discsOnly = true;   // the OPPO plays full discs (ISO / Blu-ray folder) only

        Settings(String oppoIp, String smbIp, String smbName, String share, String root, String readAhead,
                 String amUrl, String amKey) {
            this.oppoIp = oppoIp.trim();
            this.smbIp = smbIp.trim();
            this.smbName = smbName.trim();
            this.share = AltMount.strip(share.trim());
            this.root = AltMount.strip(root.trim());
            String r = readAhead.trim();
            while (r.endsWith("/")) r = r.substring(0, r.length() - 1);
            this.readAhead = r;
            String a = amUrl.trim();
            while (a.endsWith("/")) a = a.substring(0, a.length() - 1);
            this.amUrl = a;
            this.amKey = amKey.trim();
        }

        static final String D_OPPO = "10.27.33.191", D_SMB_IP = "10.27.33.198", D_SMB_NAME = "TOWER",
                D_SHARE = "MMW727W100735P1110", D_ROOT = "data/altmount", D_READ_AHEAD = "http://10.27.33.205:8081",
                D_AM_URL = "http://10.27.33.205:8080";

        static Settings load(Context ctx) {
            SharedPreferences p = Api.prefs(ctx);
            Settings s = new Settings(p.getString("oppo_ip", D_OPPO), p.getString("smb_ip", D_SMB_IP),
                    p.getString("smb_name", D_SMB_NAME), p.getString("share", D_SHARE),
                    p.getString("root", D_ROOT), p.getString("read_ahead", D_READ_AHEAD),
                    p.getString("am_url", D_AM_URL), p.getString("am_key", ""));
            s.discsOnly = p.getBoolean("discs_only", true);
            return s;
        }

        String missing() {
            if (oppoIp.isEmpty()) return "the OPPO's IP address";
            if (smbIp.isEmpty() || smbName.isEmpty()) return "the server's IP address and name";
            if (share.isEmpty()) return "the share";
            return null;
        }
    }

    static final class PlayException extends IOException {
        PlayException(String msg) { super(msg); }
    }

    static final String GONE = "The release disappeared from the server while the OPPO was opening it: "
            + "AltMount found it damaged (missing articles) and removed it. Pick another source.";

    private final Settings s;
    private final Oppo o;
    private final Listener out;
    volatile boolean cancelled;
    private String mntRoot = "/mnt/cifs1";
    private String auth = "";          // Basic auth for the read-ahead server (AltMount's WebDAV login)
    AltMount.Target target;

    DirectPlay(Settings s, Listener out) {
        this.s = s;
        this.o = new Oppo(s.oppoIp);
        this.out = out;
    }

    Oppo oppo() { return o; }

    private void stage(String t) {
        out.stage(t, "");
    }

    private void sleep(long ms) throws PlayException {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new PlayException("Cancelled");
        }
        if (cancelled) throw new PlayException("Cancelled");
    }

    /** The whole play. Returns once the OPPO plays (or shows a disc menu); throws with a message for the user. */
    void play(String url, long resumeS) throws IOException {
        String miss = s.missing();
        if (miss != null) throw new PlayException("Set " + miss + " in the Xnoppo Cast app first.");

        stage("Getting the release ready on the server…");
        Thread follow = new Thread(this::followAltMount, "altmount-follow");
        follow.start();
        String location;
        try {
            location = AltMount.resolve(url, 300_000);
        } finally {
            resolving = false;
            follow.interrupt();
        }
        String rel = AltMount.webdavRel(location);
        if (rel == null) {
            throw new PlayException("AIOStreams didn't hand over an AltMount release (" + AltMount.redact(location) + ")");
        }
        target = AltMount.target(rel, s.root);
        boolean disc = target.isDisc() || target.name().toLowerCase(Locale.ROOT).endsWith(".iso");
        if (s.discsOnly && !disc) {
            String[] p = rel.split("/");
            String release = p.length > 2 ? p[2] : target.name();
            throw new PlayException("AIOStreams ended up on " + release + ", a video file, not a disc. "
                    + "Not sent to the OPPO (it plays discs only). Pick another disc release, or play this "
                    + "one in your phone or TV player.");
        }
        auth = basicAuth(location);

        final long[] warmed = {0};
        Thread warm = new Thread(() -> warmed[0] = readAhead(), "read-ahead");
        if (!s.readAhead.isEmpty()) warm.start();   // the OPPO takes seconds to wake and mount

        stage("Waking the OPPO…");
        if (!o.portOpen(Oppo.PORT, 1500)) {
            long end = System.currentTimeMillis() + 30_000;
            while (System.currentTimeMillis() < end) {
                o.wake();
                if (o.portOpen(Oppo.PORT, 1000)) break;
                sleep(1000);
            }
            if (!o.portOpen(Oppo.PORT, 1500)) {
                throw new PlayException("The OPPO did not answer. Is it on, with network standby "
                        + "(Setup > Device Setup > Standby Mode: Quick Start)?");
            }
        }
        o.call("signin", Oppo.obj("appIconType", 1, "appIpAddress", o.localAddress()), 8000);
        stopPlayback();
        stage("The OPPO is looking for " + s.smbName + "…");
        discover();
        stage("The OPPO is opening \\\\" + s.smbName + "\\" + s.share + "…");
        String mount = mount();

        if (warm.isAlive()) {
            stage("Reading the start of the release…");
            try {
                warm.join(50_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        stage("Starting playback on the OPPO…");
        boolean started = target.isDisc() ? playDisc() : playFile();
        if (!started) {
            if (gone()) throw new PlayException(GONE);
            if (!"mounted".equals(mount)) {
                throw new PlayException("The OPPO still has an old share open and won't open it again. "
                        + "Switch the OPPO off at its power button, back on, and play again.");
            }
            throw new PlayException("The OPPO accepted the file but never started playing. "
                    + "If it stays stuck, power-cycle the OPPO.");
        }
        if (!target.isDisc() && resumeS > 5) o.setPlayTime(resumeS);
    }

    // -- what AIOStreams is trying (AltMount's SABnzbd queue + history) -------------------

    private volatile boolean resolving = true;

    private JSONObject sab(String mode, String extra) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(s.amUrl + "/sabnzbd/api?mode=" + mode + "&output=json" + extra
                    + "&apikey=" + URLEncoder.encode(s.amKey, "UTF-8")).openConnection();
            c.setConnectTimeout(3000);
            c.setReadTimeout(8000);
            return new JSONObject(AltMount.readText(c.getInputStream(), 1 << 20));
        } catch (Exception e) {
            return new JSONObject();
        } finally {
            if (c != null) c.disconnect();
        }
    }

    /** A short reason for a failed import, from AltMount's history. */
    static String shortReason(String msg) {
        if (msg == null || msg.isEmpty()) return "failed";
        String m = msg.toLowerCase(Locale.ROOT);
        if (m.contains("fast-fail") || m.contains("no such article") || m.contains("missing")) return "missing articles";
        Matcher v = Pattern.compile("(\\d+) of (\\d+) volumes").matcher(msg);
        if (m.contains("truncated")) return "incomplete RAR" + (v.find() ? " (" + v.group(1) + " of " + v.group(2) + " parts)" : "");
        if (m.contains("password")) return "password-protected";
        return msg.length() > 80 ? msg.substring(0, 80) + "…" : msg;
    }

    /** While AIOStreams works, show the release AltMount is importing and each one that failed. */
    private void followAltMount() {
        if (s.amUrl.isEmpty() || s.amKey.isEmpty()) return;
        long t0 = System.currentTimeMillis();
        java.util.Set<String> seen = new java.util.HashSet<>();
        JSONObject baseHist = sab("history", "&limit=50").optJSONObject("history");
        JSONArray base = baseHist == null ? null : baseHist.optJSONArray("slots");
        for (int i = 0; base != null && i < base.length(); i++) {
            JSONObject h = base.optJSONObject(i);
            if (h != null) seen.add(h.optString("name") + "|" + h.optString("status"));
        }
        List<String> failed = new ArrayList<>();
        String trying = "";
        while (resolving && !cancelled) {
            JSONObject q = sab("queue", "").optJSONObject("queue");
            JSONArray slots = q == null ? null : q.optJSONArray("slots");
            if (slots != null && slots.length() > 0) {
                JSONObject first = slots.optJSONObject(0);
                if (first != null) trying = first.optString("filename").replaceAll("(?i)\\.nzb$", "");
            }
            JSONObject hist = sab("history", "&limit=20").optJSONObject("history");
            JSONArray hs = hist == null ? null : hist.optJSONArray("slots");
            for (int i = hs == null ? 0 : hs.length() - 1; i >= 0; i--) {
                JSONObject h = hs.optJSONObject(i);
                if (h == null) continue;
                String key = h.optString("name") + "|" + h.optString("status");
                if (!seen.add(key)) continue;
                if ("Failed".equalsIgnoreCase(h.optString("status"))) {
                    failed.add("✗ " + h.optString("name") + ": " + shortReason(h.optString("fail_message")));
                    if (h.optString("name").equals(trying)) trying = "";
                }
            }
            StringBuilder d = new StringBuilder();
            if (!trying.isEmpty()) d.append("Trying: ").append(trying);
            for (String f : failed) d.append(d.length() > 0 ? "\n" : "").append(f);
            long secs = (System.currentTimeMillis() - t0) / 1000;
            out.stage("Getting the release ready on the server… " + Ui.clock(secs), d.toString());
            try {
                Thread.sleep(2000);
            } catch (InterruptedException e) {
                return;
            }
        }
    }

    // -- OPPO steps ----------------------------------------------------------------------

    private void stopPlayback() throws PlayException {
        if (!o.playing()) return;
        stage("Stopping what the OPPO is playing…");
        long end = System.currentTimeMillis() + 20_000;
        while (System.currentTimeMillis() < end) {
            o.key("STP");
            sleep(2000);
            if (!o.playing()) return;
        }
        throw new PlayException("The OPPO kept playing. Stop it with its remote and try again.");
    }

    /** The OPPO only logs in to SMB servers its media center has listed; the list fills in once asked. */
    private void discover() throws PlayException {
        if ("scrn_svr".equals(o.globalInfo().optString("activeapp"))) {
            o.key("RET");   // the screensaver pauses the media center
            sleep(1500);
        }
        long end = System.currentTimeMillis() + 25_000;
        while (System.currentTimeMillis() < end) {
            JSONArray list = o.call("getdevicelist", null, 8000).optJSONArray("devicelist");
            if (list != null) {
                for (int i = 0; i < list.length(); i++) {
                    JSONObject d = list.optJSONObject(i);
                    if (d != null && "cifs".equals(d.optString("sub_type"))
                            && s.smbName.equalsIgnoreCase(d.optString("name"))) return;
                }
            }
            sleep(2000);
        }
        // its login falls back to the last server it lists: try anyway, the mount reports it
    }

    private JSONObject loginAndMount() {
        JSONObject login = o.call("loginSambaWithID", Oppo.obj("serverName", s.smbName, "userName", "guest",
                "psssword", "", "bRememberID", 0), 25000);
        JSONObject m = o.call("mountSharedFolder", Oppo.obj("server", s.smbIp, "bWithID", 0, "folder", s.share,
                "userName", "", "password", "", "bRememberID", 0), 20000);
        Oppo.put(m, "_login", login.optBoolean("success"));
        return m;
    }

    /** "mounted" (fresh, mntRoot known) or "existing" (already mounted from before). */
    private String mount() throws PlayException {
        boolean cleared = false;
        String last = "";
        for (int attempt = 1; attempt <= 5; attempt++) {
            if (attempt > 1) {
                stage("The OPPO is opening the share (try " + attempt + " of 5)…");
                sleep(3000);
                discover();
            }
            JSONObject m = loginAndMount();
            if (m.optBoolean("success")) {
                mntRoot = trimSlash(m.optString("cifsMntPath", "/mnt/cifs1"));
                return "mounted";
            }
            last = firstNonEmpty(m.optString("retInfo"), m.optString("msg"), m.optString("raw"), m.toString());
            if ("id_error".equals(last) && !cleared) {
                // a share still mounted from before (this one, stale, or another): clear and mount afresh
                cleared = true;
                Boolean ok = o.clearMounts();
                if (ok != null) {
                    stage("Clearing the OPPO's old share…");
                    sleep(1000);
                    continue;
                }
            }
            if ("id_error".equals(last) && m.optBoolean("_login")) return "existing";
        }
        if ("id_error".equals(last)) return "existing";   // try playing from what it has
        throw new PlayException("The OPPO could not open \\\\" + s.smbName + "\\" + s.share + " (" + last
                + "). Check that the share allows guests and SMB1; if it still fails, switch the OPPO off at "
                + "its power button and try again.");
    }

    private boolean playFile() throws PlayException {
        // it plays only from folders it has listed: the share root first, then down to the file's
        String path = mntRoot;
        String[] parts = target.shareRel.split("/");
        for (int i = -1; i < parts.length - 1; i++) {
            if (i >= 0) path = path + "/" + parts[i];
            o.call("getfilelist", Oppo.obj("path", path, "deviceSubType", 7, "fileType", 1, "mediaType", 3), 30000);
            if (cancelled) throw new PlayException("Cancelled");
        }
        // 7 = a CIFS device: the OPPO picks it from its server list by this name
        JSONObject r = o.call("playnormalfile", Oppo.obj("path", mntRoot + "/" + target.shareRel, "index", 0,
                "type", 1, "appDeviceType", 7, "extraNetPath", s.smbName, "playMode", 0), 60000);
        if (!r.optBoolean("success")) {
            throw new PlayException("The OPPO refused to play it: " + firstNonEmpty(r.optString("retInfo"), r.toString()));
        }
        return waitPlaying(60_000, false);
    }

    private boolean playDisc() throws PlayException {
        JSONObject r = o.call("checkfolderhasBDMV", Oppo.obj("folderpath", mntRoot + "/" + target.shareRel), 60000);
        if (!r.optBoolean("success")) {
            throw new PlayException("The OPPO refused the Blu-ray folder: " + firstNonEmpty(r.optString("retInfo"), r.toString()));
        }
        return waitPlaying(90_000, true);
    }

    private boolean waitPlaying(long ms, boolean disc) throws PlayException {
        long end = System.currentTimeMillis() + ms;
        while (System.currentTimeMillis() < end) {
            if (o.globalInfo().optBoolean("is_video_playing")) return true;
            sleep(1000);
        }
        return disc;   // BD-J menus can sit without "video playing": the OPPO took the disc
    }

    // -- read-ahead through the server's rclone WebDAV server -----------------------------

    private String readAheadUrl(String webdavRel) {
        StringBuilder b = new StringBuilder(s.readAhead).append("/webdav");
        for (String part : webdavRel.split("/")) {
            try {
                b.append('/').append(URLEncoder.encode(part, "UTF-8").replace("+", "%20"));
            } catch (Exception e) {
                b.append('/').append(part);
            }
        }
        return b.toString();
    }

    private static String basicAuth(String location) {
        try {
            String ui = new URI(location).getRawUserInfo();
            if (ui == null || ui.isEmpty()) return "";
            String plain = java.net.URLDecoder.decode(ui.replace("+", "%2B"), "UTF-8");
            return "Basic " + Base64.encodeToString(plain.getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP);
        } catch (Exception e) {
            return "";
        }
    }

    /** The file is gone from the server (AltMount dropped a damaged release). */
    private boolean gone() {
        if (s.readAhead.isEmpty() || target == null) return false;
        return head(readAheadUrl(target.webdavRel)) == 404;
    }

    private HttpURLConnection open(String url, String method) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(5000);
        c.setReadTimeout(60000);
        c.setRequestMethod(method);
        if (!auth.isEmpty()) c.setRequestProperty("Authorization", auth);
        return c;
    }

    /** HTTP status of a HEAD (-1 when unreachable). */
    private int head(String url) {
        HttpURLConnection c = null;
        try {
            c = open(url, "HEAD");
            return c.getResponseCode();
        } catch (IOException e) {
            return -1;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private long length(String url) {
        HttpURLConnection c = null;
        try {
            c = open(url, "HEAD");
            if (c.getResponseCode() != 200) return -1;
            String cl = c.getHeaderField("Content-Length");   // getContentLengthLong() needs API 24
            return cl == null ? -1 : Long.parseLong(cl.trim());
        } catch (IOException | NumberFormatException e) {
            return -1;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private long readRange(String url, long start, long len, long deadline) {
        HttpURLConnection c = null;
        long done = 0;
        try {
            c = open(url, "GET");
            c.setRequestProperty("Range", "bytes=" + start + "-" + (start + len - 1));
            try (InputStream in = c.getInputStream()) {
                byte[] buf = new byte[1 << 16];
                int n;
                while (done < len && System.currentTimeMillis() < deadline && !cancelled
                        && (n = in.read(buf)) > 0) done += n;
            }
        } catch (IOException ignored) {
        } finally {
            if (c != null) c.disconnect();
        }
        return done;
    }

    /**
     * Read what the OPPO opens first into the server's cache: a Blu-ray folder's small BDMV files
     * (index, MovieObject, playlists, clip info; not the streams), an ISO's head (UDF + BDMV
     * structures), or a file's head and tail (MKV cues / MP4 'moov'). Waits for the release to
     * show up there first. Returns the bytes read.
     */
    private long readAhead() {
        long deadline = System.currentTimeMillis() + 45_000;
        String url = readAheadUrl(target.webdavRel);
        // a fresh release takes a few seconds to show up in the server's rclone mount
        while (System.currentTimeMillis() < deadline && !cancelled) {
            int code = head(url + (target.isDisc() ? "/" : ""));
            if (code == 200 || code == 207 || code == 301 || code == 405) break;
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                return 0;
            }
        }
        long done = 0;
        if (target.isDisc()) {
            long total = 0;
            for (String[] f : listBdmv(target.webdavRel + "/BDMV", deadline)) {
                long size = Long.parseLong(f[1]);
                if (size <= 0 || total + size > (96L << 20)) continue;
                total += size;
                done += readRange(readAheadUrl(f[0]), 0, size, deadline);
                if (System.currentTimeMillis() > deadline) break;
            }
            return done;
        }
        long size = length(url);
        if (size <= 0) return 0;
        boolean iso = target.webdavRel.toLowerCase(Locale.ROOT).endsWith(".iso");
        long head = Math.min(size, (iso ? 64L : 16L) << 20);
        done += readRange(url, 0, head, deadline);
        if (!iso && size > head) {
            long start = Math.max(head, size - (8L << 20));
            done += readRange(url, start, size - start, deadline);
        }
        return done;
    }

    private static final Pattern RESPONSE = Pattern.compile("(?is)<[a-z]*:?response>(.*?)</[a-z]*:?response>");
    private static final Pattern HREF = Pattern.compile("(?is)<[a-z]*:?href>(.*?)</[a-z]*:?href>");
    private static final Pattern SIZE = Pattern.compile("(?is)<[a-z]*:?getcontentlength>(\\d+)<");

    /** [webdavRel, size] of every file under a BDMV folder except STREAM and BACKUP. */
    private List<String[]> listBdmv(String folderRel, long deadline) {
        List<String[]> files = new ArrayList<>();
        List<String> todo = new ArrayList<>();
        todo.add(folderRel);
        while (!todo.isEmpty() && System.currentTimeMillis() < deadline && !cancelled) {
            String dir = todo.remove(0);
            String xml = propfind(readAheadUrl(dir) + "/");
            Matcher r = RESPONSE.matcher(xml);
            while (r.find()) {
                String block = r.group(1);
                Matcher h = HREF.matcher(block);
                if (!h.find()) continue;
                String rel = AltMount.webdavRel("http://x" + h.group(1).trim());
                if (rel == null || rel.equals(dir)) continue;
                boolean isDir = block.toLowerCase(Locale.ROOT).contains("collection");
                String name = rel.substring(rel.lastIndexOf('/') + 1).toUpperCase(Locale.ROOT);
                if (isDir) {
                    if (!name.equals("STREAM") && !name.equals("BACKUP")) todo.add(rel);
                } else {
                    Matcher sz = SIZE.matcher(block);
                    files.add(new String[]{rel, sz.find() ? sz.group(1) : "0"});
                }
            }
        }
        return files;
    }

    /** PROPFIND Depth: 1 (HttpURLConnection refuses the method, so by hand; plain HTTP on the LAN). */
    private String propfind(String url) {
        try {
            URL u = new URL(url);
            int port = u.getPort() > 0 ? u.getPort() : 80;
            try (Socket sock = new Socket()) {
                sock.connect(new java.net.InetSocketAddress(u.getHost(), port), 5000);
                sock.setSoTimeout(30000);
                String req = "PROPFIND " + u.getPath() + " HTTP/1.1\r\nHost: " + u.getHost() + ":" + port
                        + "\r\nDepth: 1\r\n" + (auth.isEmpty() ? "" : "Authorization: " + auth + "\r\n")
                        + "Content-Length: 0\r\nConnection: close\r\n\r\n";
                OutputStream os = sock.getOutputStream();
                os.write(req.getBytes(StandardCharsets.UTF_8));
                os.flush();
                return AltMount.readText(sock.getInputStream(), 4 << 20);
            }
        } catch (IOException e) {
            return "";
        }
    }

    // -- small helpers ---------------------------------------------------------------------

    private static String trimSlash(String p) {
        while (p.endsWith("/") && p.length() > 1) p = p.substring(0, p.length() - 1);
        return p.isEmpty() ? "/mnt/cifs1" : p;
    }

    private static String firstNonEmpty(String... v) {
        for (String x : v) if (x != null && !x.isEmpty()) return x;
        return "";
    }
}
