package com.fandangos.xnoppocast;

/**
 * Plain-Java checks for AltMount (no Android needed):
 *   cd android && javac -d /tmp/amt app/src/main/java/com/fandangos/xnoppocast/AltMount.java tools/AltMountTest.java \
 *     && java -cp /tmp/amt com.fandangos.xnoppocast.AltMountTest
 */
public class AltMountTest {
    static int failures = 0;

    static void eq(Object got, Object want, String what) {
        if (want == null ? got != null : !want.equals(got)) {
            failures++;
            System.out.println("FAIL " + what + ": got <" + got + "> want <" + want + ">");
        }
    }

    public static void main(String[] a) throws Exception {
        String link = "https://aiostreams.example/api/v1/debrid/playback/a/b/c";
        String loc = "http://rclone:pw@10.27.33.205:8080/webdav/complete/Movies/Green.Book.REMUX/Green%20Book%20REMUX.mkv";
        eq(AltMount.isLink(link), true, "playback link");
        eq(AltMount.isLink(loc), true, "webdav link");
        eq(AltMount.isLink("https://cdn.example/x.mkv"), false, "other link");
        eq(AltMount.isLink(null), false, "null");

        eq(AltMount.webdavRel(loc), "complete/Movies/Green.Book.REMUX/Green Book REMUX.mkv", "rel");
        eq(AltMount.webdavRel("http://h/webdav/complete/../../etc/passwd"), null, "dotdot");
        eq(AltMount.webdavRel("http://h/webdav/"), null, "empty");
        eq(AltMount.webdavRel("http://h/webdav/complete/TV/A+B/x.mkv"), "complete/TV/A+B/x.mkv", "plus kept");
        eq(AltMount.redact(loc), "http://10.27.33.205:8080/webdav/complete/Movies/Green.Book.REMUX/Green%20Book%20REMUX.mkv", "redact");

        AltMount.Target t = AltMount.target("complete/Movies/R/R.mkv", "data/altmount");
        eq(t.kind, "file", "file kind");
        eq(t.shareRel, "data/altmount/complete/Movies/R/R.mkv", "file shareRel");
        eq(t.name(), "R.mkv", "name");
        t = AltMount.target("complete/Movies/R.COMPLETE/R/BDMV/STREAM/00800.m2ts", "/data/altmount/");
        eq(t.kind, "bdmv", "disc kind");
        eq(t.webdavRel, "complete/Movies/R.COMPLETE/R", "disc webdavRel");
        eq(t.shareRel, "data/altmount/complete/Movies/R.COMPLETE/R", "disc shareRel");
        t = AltMount.target("complete/Movies/R/R.iso", "");
        eq(t.shareRel, "complete/Movies/R/R.iso", "no root");
        try {
            AltMount.target("complete/Movies/R/R.nfo", "x");
            eq("no exception", "exception", "nfo refused");
        } catch (AltMount.AltMountException e) {
            eq(e.getMessage(), "the OPPO can't play .nfo files", "nfo message");
        }

        eq(AltMount.errorText("{\"success\":false,\"error\":{\"code\":\"DEBRID_ERROR\",\"message\":\"NZB failed: fast-fail\"}}"),
                "NZB failed: fast-fail", "nested message");
        eq(AltMount.errorText("{\"error\":\"bad token\"}"), "bad token", "flat error");
        eq(AltMount.errorText("<html><body>504 Gateway Time-out</body></html>"), "", "proxy html");
        eq(AltMount.errorText("upstream   timed out"), "upstream timed out", "plain text");
        eq(AltMount.errorText(""), "", "empty");

        System.out.println(failures == 0 ? "OK" : failures + " FAILED");
        System.exit(failures == 0 ? 0 : 1);
    }
}
