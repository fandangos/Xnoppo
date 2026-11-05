package com.fandangos.xnoppocast;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * AIOStreams + AltMount releases, played straight from the server's share.
 *
 * AIOStreams lists Usenet releases that AltMount handles as its own playback links
 * (https://&lt;aiostreams&gt;/api/v1/debrid/playback/...). Opening one makes AIOStreams hand
 * the NZB to AltMount and wait for its import; it then answers 307 with the file on
 * AltMount's WebDAV:  http://user:pass@&lt;altmount&gt;:8080/webdav/complete/Movies/&lt;release&gt;/&lt;file&gt;
 * On the server, rclone mounts AltMount's WebDAV root into a folder of a Samba share, so the
 * OPPO can play the same file from \\SERVER\share\root\complete\... through the server's rclone.
 *
 * Plain Java (no Android classes): tested with tools/AltMountTest.java.
 */
final class AltMount {
    private AltMount() {}

    static final String PLAYBACK_MARK = "/api/v1/debrid/playback/";
    static final String WEBDAV_MARK = "/webdav/";
    static final Set<String> FILE_EXTS = new HashSet<>(Arrays.asList(
            "mkv", "mp4", "m4v", "iso", "m2ts", "ts", "mov", "avi", "wmv", "mpg", "mpeg", "webm"));

    static final class AltMountException extends IOException {
        AltMountException(String msg) { super(msg); }
    }

    /** Where to play: kind "bdmv" (the folder holding BDMV, with menus) or "file". */
    static final class Target {
        final String kind;       // "bdmv" | "file"
        final String webdavRel;  // path under AltMount's WebDAV root (the file or the disc folder)
        final String shareRel;   // the same inside the SMB share (root + webdavRel)
        Target(String kind, String webdavRel, String shareRel) {
            this.kind = kind;
            this.webdavRel = webdavRel;
            this.shareRel = shareRel;
        }
        boolean isDisc() { return "bdmv".equals(kind); }
        String name() {
            int i = shareRel.lastIndexOf('/');
            return i < 0 ? shareRel : shareRel.substring(i + 1);
        }
    }

    static boolean isLink(String url) {
        if (url == null) return false;
        if (url.contains(PLAYBACK_MARK)) return true;
        try {
            String p = new URI(url).getRawPath();
            return p != null && p.contains(WEBDAV_MARK);
        } catch (Exception e) {
            return false;
        }
    }

    /** The path after /webdav/ (decoded), or null. */
    static String webdavRel(String location) {
        if (location == null) return null;
        String path;
        try {
            path = new URI(location).getRawPath();
        } catch (Exception e) {
            return null;
        }
        if (path == null) return null;
        path = decode(path);
        int i = path.indexOf(WEBDAV_MARK);
        if (i < 0) return null;
        String rel = strip(path.substring(i + WEBDAV_MARK.length()));
        if (rel.isEmpty()) return null;
        for (String part : rel.split("/")) if (part.equals("..")) return null;
        return rel;
    }

    private static String decode(String s) {
        try {
            return URLDecoder.decode(s.replace("+", "%2B"), "UTF-8");
        } catch (Exception e) {
            return s;
        }
    }

    static String strip(String s) {
        int a = 0, b = s.length();
        while (a < b && (s.charAt(a) == '/' || s.charAt(a) == '\\')) a++;
        while (b > a && (s.charAt(b - 1) == '/' || s.charAt(b - 1) == '\\')) b--;
        return s.substring(a, b);
    }

    static Target target(String rel, String root) throws AltMountException {
        List<String> parts = new ArrayList<>(Arrays.asList(rel.split("/")));
        String kind = "file";
        int bdmv = -1;
        for (int i = 0; i < parts.size() - 1; i++) {
            if (parts.get(i).equalsIgnoreCase("BDMV")) bdmv = i;
        }
        if (bdmv >= 0) {
            parts = parts.subList(0, bdmv);
            kind = "bdmv";
        } else {
            String name = parts.get(parts.size() - 1);
            int dot = name.lastIndexOf('.');
            String ext = dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
            if (!FILE_EXTS.contains(ext)) {
                throw new AltMountException(ext.isEmpty() ? "the OPPO can't play this file"
                        : "the OPPO can't play ." + ext + " files");
            }
        }
        String webdavRel = join(parts);
        String r = strip(root == null ? "" : root);
        return new Target(kind, webdavRel, r.isEmpty() ? webdavRel : r + "/" + webdavRel);
    }

    static String join(List<String> parts) {
        StringBuilder b = new StringBuilder();
        for (String p : parts) {
            if (b.length() > 0) b.append('/');
            b.append(p);
        }
        return b.toString();
    }

    static String readText(InputStream in, int limit) throws IOException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        try (InputStream s = in) {
            byte[] buf = new byte[16384];
            int n;
            while (out.size() < limit && (n = s.read(buf, 0, Math.min(buf.length, limit - out.size()))) > 0) {
                out.write(buf, 0, n);
            }
        }
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }

    /** scheme://host:port/path, without login or query: safe to show and log. */
    static String redact(String url) {
        try {
            URI u = new URI(url);
            return u.getScheme() + "://" + u.getHost() + (u.getPort() > 0 ? ":" + u.getPort() : "") + u.getRawPath();
        } catch (Exception e) {
            return "(address)";
        }
    }

    private static final Pattern MESSAGE = Pattern.compile(
            "\"(?:message|error|detail|description)\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"");

    /** AIOStreams' reason in an error answer; "" for a proxy's HTML page or an empty body. */
    static String errorText(String body) {
        if (body == null) return "";
        String t = body.trim();
        if (t.isEmpty() || t.regionMatches(true, 0, "<!doctype", 0, 9) || t.regionMatches(true, 0, "<html", 0, 5)) {
            return "";
        }
        if (t.startsWith("{") || t.startsWith("[")) {
            Matcher m = MESSAGE.matcher(t);
            String last = "";
            while (m.find()) {
                last = m.group(1).replace("\\\"", "\"").replace("\\n", " ").trim();
                if (!last.isEmpty()) {
                    // prefer a nested message ("error": {"message": ...}) over a bare code
                    if (m.group(0).startsWith("\"message\"")) break;
                }
            }
            return clip(last);
        }
        return clip(t.replaceAll("\\s+", " "));
    }

    private static String clip(String s) {
        return s.length() > 300 ? s.substring(0, 300) : s;
    }

    /**
     * The AltMount WebDAV address an AIOStreams playback link redirects to (one request,
     * redirect not followed). AIOStreams answers only once AltMount has imported the release,
     * possibly after trying other releases, so this can take minutes.
     */
    static String resolve(String url, int timeoutMs) throws IOException {
        if (!url.contains(PLAYBACK_MARK)) return url;
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        try {
            c.setInstanceFollowRedirects(false);
            c.setConnectTimeout(10000);
            c.setReadTimeout(timeoutMs);
            c.setRequestProperty("User-Agent", "XnoppoCast/2");
            int code = c.getResponseCode();
            String loc = c.getHeaderField("Location");
            if (code >= 300 && code < 400 && loc != null) {
                return new URL(new URL(url), loc).toString();
            }
            InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
            String body = in == null ? "" : readText(in, 64 * 1024);
            String why = errorText(body);
            throw new AltMountException("AIOStreams answered HTTP " + code + (why.isEmpty() ? "" : ": " + why));
        } finally {
            c.disconnect();
        }
    }
}
