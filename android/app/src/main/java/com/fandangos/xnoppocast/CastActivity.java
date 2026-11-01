package com.fandangos.xnoppocast;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONObject;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * "Play on OPPO": receives the stream from Nuvio/Stremio, hands it to Xnoppo, shows progress and
 * a small remote, and returns the playback position to the caller (MX Player result convention:
 * position/duration in ms + end_by) so the app keeps tracking watch progress.
 */
public class CastActivity extends Activity {
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private TextView stage, detail;
    private volatile int castId = -1;
    private volatile long positionS = -1, durationS = -1;
    private volatile boolean ended, stopped;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout col = Ui.column(this);
        col.addView(Ui.text(this, "Play on OPPO", 14, false));
        col.addView(Ui.text(this, CastRequest.title(getIntent()), 24, true));
        stage = Ui.text(this, "Sending to Xnoppo…", 20, true);
        detail = Ui.text(this, "", 16, false);
        col.addView(stage);
        col.addView(detail);
        for (LinearLayout r : new Remote(this, io, () -> positionS).build()) col.addView(r);
        col.addView(Ui.button(this, "Close", v -> finish()));
        setContentView(Ui.screen(this, col));
        io.execute(this::startCast);
    }

    private void show(String s, String d) {
        ui.post(() -> {
            stage.setText(s);
            detail.setText(d);
        });
    }

    private void startCast() {
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
        io.shutdownNow();
        super.onDestroy();
    }
}
