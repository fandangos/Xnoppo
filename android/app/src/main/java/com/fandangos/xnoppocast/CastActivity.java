package com.fandangos.xnoppocast;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONObject;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * "Play on OPPO": what Nuvio/Stremio open as an external player.
 *
 * AIOStreams/AltMount releases (the usual case) play directly: the app resolves the link and
 * has the OPPO mount the server's share and play the file (DirectPlay); nothing else is
 * involved. Any other stream is handed to Xnoppo's cast service, if an Xnoppo address is set.
 *
 * Shows progress and a small remote, and returns the playback position to the caller (MX Player
 * result convention: position/duration in ms + end_by) so the app keeps tracking watch progress.
 */
public class CastActivity extends Activity {
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final ExecutorService watch = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private TextView stage, detail;
    private volatile int castId = -1;
    private volatile long positionS = -1, durationS = -1;
    private volatile boolean ended, stopped, direct;
    private volatile DirectPlay play;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout col = Ui.column(this);
        col.addView(Ui.text(this, "Play on OPPO", 14, false));
        col.addView(Ui.text(this, CastRequest.title(getIntent()), 24, true));
        stage = Ui.text(this, "Starting…", 20, true);
        detail = Ui.text(this, "", 16, false);
        col.addView(stage);
        col.addView(detail);
        for (LinearLayout r : new Remote(this, io, () -> positionS).build()) col.addView(r);
        col.addView(Ui.button(this, "Close", v -> finish()));
        setContentView(Ui.screen(this, col));

        Uri data = getIntent().getData();
        String url = data == null ? "" : data.toString();
        direct = AltMount.isLink(url);
        if (state == null) {
            // only on the first creation: Android recreates the activity on rotation etc.,
            // and a second play request made the OPPO start the same file twice (crash)
            if (direct) watch.execute(() -> startDirect(url));
            else io.execute(this::startXnoppo);
        } else {
            castId = state.getInt("castId", -1);
            if (direct) watch.execute(this::watchOppo);
            else if (castId >= 0) ui.post(this::poll);
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        out.putInt("castId", castId);
    }

    private void show(String s, String d) {
        ui.post(() -> {
            stage.setText(s);
            detail.setText(d);
        });
    }

    // -- direct: AIOStreams/AltMount release from the server's share ---------------------

    private void startDirect(String url) {
        DirectPlay.Settings s = DirectPlay.Settings.load(this);
        play = new DirectPlay(s, this::show);
        try {
            play.play(url, CastRequest.positionMs(getIntent()) / 1000);
            AltMount.Target t = play.target;
            show(t.isDisc() ? "Playing on OPPO (disc menu)" : "Playing on OPPO", t.name());
            watchOppo();
        } catch (Exception e) {
            if (!stopped) show("Couldn't play", String.valueOf(e.getMessage()));
        }
    }

    /** Follow the OPPO's position until playback ends (3 checks in a row without video). */
    private void watchOppo() {
        Oppo o = new Oppo(DirectPlay.Settings.load(this).oppoIp);
        String name = play != null && play.target != null ? play.target.name() : "";
        int misses = 0;
        while (!stopped) {
            try {
                Thread.sleep(2000);
            } catch (InterruptedException e) {
                return;
            }
            if (o.globalInfo().optBoolean("is_video_playing")) {
                misses = 0;
                JSONObject t = o.call("getplayingtime", null, 5000);
                if (t.has("cur_time")) positionS = t.optLong("cur_time", positionS);
                if (t.has("total_time")) durationS = t.optLong("total_time", durationS);
                show("Playing on OPPO", Ui.clock(positionS) + " / " + Ui.clock(durationS) + "\n" + name);
            } else if (++misses >= 3 && positionS >= 0) {
                ended = true;
                show("Finished", Ui.clock(positionS) + " / " + Ui.clock(durationS));
                ui.post(this::finish);
                return;
            }
        }
    }

    // -- anything else: Xnoppo's cast service, when configured --------------------------

    private void startXnoppo() {
        if (Api.prefs(this).getString("server", "").trim().isEmpty()) {
            show("Can't play this stream", "Only AIOStreams Usenet releases (AltMount) play directly on the OPPO. "
                    + "Set an Xnoppo address in the Xnoppo Cast app to cast other streams through Xnoppo.");
            return;
        }
        try {
            JSONObject req = CastRequest.build(this, getIntent());
            int subs = req.has("subtitles") ? req.getJSONArray("subtitles").length() : 0;
            show("Sending to Xnoppo…", subs + " subtitle(s) offered");
            JSONObject r = new Api(this).post("/cast", req);
            castId = r.optInt("id", -1);
            ui.post(this::poll);
        } catch (Exception e) {
            show("Couldn't start", e.getMessage() + "\n\nCheck the Xnoppo address in the Xnoppo Cast app.");
        }
    }

    private void poll() {
        if (stopped) return;
        io.execute(() -> {
            try {
                JSONObject s = new Api(this).get("/status");
                if (s.optInt("id", -1) != castId) {
                    show("Replaced by another cast", "");
                    return;
                }
                String st = s.optString("stage");
                if (s.has("position") && !s.isNull("position")) positionS = (long) s.optDouble("position", -1);
                if (s.has("duration") && !s.isNull("duration")) durationS = s.optLong("duration", -1);
                switch (st) {
                    case "error":
                        show("Failed", s.optString("error"));
                        return;
                    case "ended":
                        ended = true;
                        show("Finished", Ui.clock(positionS) + " / " + Ui.clock(durationS));
                        ui.post(this::finish);
                        return;
                    case "playing":
                        show("Playing on OPPO", Ui.clock(positionS) + " / " + Ui.clock(durationS)
                                + "\n" + s.optString("file"));
                        break;
                    default:
                        show(capitalize(st) + "…", s.optString("file", ""));
                }
            } catch (Exception e) {
                show("Lost contact with Xnoppo", e.getMessage());
            }
            ui.postDelayed(this::poll, 2000);
        });
    }

    private static String capitalize(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    @Override
    public void finish() {
        Intent res = new Intent();
        if (positionS >= 0) res.putExtra("position", (int) Math.min(Integer.MAX_VALUE, positionS * 1000));
        if (durationS > 0) res.putExtra("duration", (int) Math.min(Integer.MAX_VALUE, durationS * 1000));
        boolean nearEnd = durationS > 0 && positionS >= durationS * 0.95;
        res.putExtra("end_by", ended && (nearEnd || positionS < 0) ? "playback_completion" : "user");
        setResult(RESULT_OK, res);
        super.finish();
    }

    @Override
    protected void onDestroy() {
        stopped = true;
        DirectPlay p = play;
        if (p != null) p.cancelled = true;
        io.shutdownNow();
        watch.shutdownNow();
        super.onDestroy();
    }
}
