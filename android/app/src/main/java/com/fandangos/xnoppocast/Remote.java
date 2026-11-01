package com.fandangos.xnoppocast;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.concurrent.ExecutorService;

/** Remote-control buttons shared by both screens. Track buttons cycle the OPPO's own menus. */
final class Remote {
    interface PositionSource { long seconds(); }

    private final Activity a;
    private final ExecutorService io;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final PositionSource pos;

    Remote(Activity a, ExecutorService io, PositionSource pos) {
        this.a = a;
        this.io = io;
        this.pos = pos;
    }

    LinearLayout[] build() {
        LinearLayout r1 = Ui.row(a), r2 = Ui.row(a);
        Button pause = Ui.button(a, "Pause", v -> send("pause", null));
        r1.addView(pause);
        r1.addView(Ui.button(a, "Play", v -> send("play", null)));
        r1.addView(Ui.button(a, "−30s", v -> seek(-30)));
        r1.addView(Ui.button(a, "+30s", v -> seek(+30)));
        r1.addView(Ui.button(a, "Stop", v -> send("stop", null)));
        r2.addView(Ui.button(a, "Subtitles", v -> cycle("subtitle")));
        r2.addView(Ui.button(a, "Audio", v -> cycle("audio")));
        pause.requestFocus();
        return new LinearLayout[]{r1, r2};
    }

    private void toast(String s) {
        ui.post(() -> Toast.makeText(a, s, Toast.LENGTH_SHORT).show());
    }

    private void send(String action, JSONObject extra) {
        io.execute(() -> {
            try {
                JSONObject body = extra != null ? extra : new JSONObject();
                body.put("action", action);
                new Api(a).post("/control", body);
            } catch (Exception e) {
                toast(action + " failed: " + e.getMessage());
            }
        });
    }

    private void seek(int delta) {
        long now = pos.seconds();
        if (now < 0) { toast("position unknown yet"); return; }
        try {
            send("seek", new JSONObject().put("position", Math.max(0, now + delta)));
        } catch (Exception ignored) { }
    }

    /** "subtitle" | "audio": read the OPPO's track list, select the next entry. */
    private void cycle(String kind) {
        io.execute(() -> {
            try {
                Api api = new Api(a);
                JSONObject res = api.control("tracks").optJSONObject("result");
                JSONArray list = res == null ? null : res.optJSONArray(kind.equals("subtitle") ? "subtitles" : "audio");
                if (list == null || list.length() == 0) { toast("no " + kind + " tracks"); return; }
                int cur = 0;
                for (int i = 0; i < list.length(); i++) if (list.getJSONObject(i).optBoolean("selected")) cur = i;
                JSONObject next = list.getJSONObject((cur + 1) % list.length());
                api.post("/control", new JSONObject().put("action", kind).put("index", next.getInt("index")));
                toast(next.optString("name", "track " + next.getInt("index")).trim());
            } catch (Exception e) {
                toast(kind + " failed: " + e.getMessage());
            }
        });
    }
}
