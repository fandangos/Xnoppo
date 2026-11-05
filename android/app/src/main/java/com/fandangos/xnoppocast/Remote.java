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

/**
 * Remote-control buttons shared by both screens. They talk to the OPPO directly (its :436 API),
 * whatever started the playback. Track buttons cycle the OPPO's own menus.
 */
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
        Button pause = Ui.button(a, "Pause", v -> key("PAU"));
        r1.addView(pause);
        r1.addView(Ui.button(a, "Play", v -> key("PLA")));
        r1.addView(Ui.button(a, "−30s", v -> seek(-30)));
        r1.addView(Ui.button(a, "+30s", v -> seek(+30)));
        r1.addView(Ui.button(a, "Stop", v -> key("STP")));
        r2.addView(Ui.button(a, "Subtitles", v -> cycle(true)));
        r2.addView(Ui.button(a, "Audio", v -> cycle(false)));
        r2.addView(Ui.button(a, "Menu", v -> key("MNU")));
        pause.requestFocus();
        return new LinearLayout[]{r1, r2};
    }

    private Oppo oppo() {
        return new Oppo(DirectPlay.Settings.load(a).oppoIp);
    }

    private void toast(String s) {
        ui.post(() -> Toast.makeText(a, s, Toast.LENGTH_SHORT).show());
    }

    private void key(String code) {
        io.execute(() -> {
            Oppo o = oppo();
            if (!o.portOpen(Oppo.PORT, 1000)) o.wake();
            JSONObject r = o.key(code);
            if (!r.optBoolean("success", true)) toast(code + " failed: " + r.optString("retInfo"));
        });
    }

    private void seek(int delta) {
        long now = pos.seconds();
        if (now < 0) { toast("position unknown yet"); return; }
        io.execute(() -> oppo().setPlayTime(Math.max(0, now + delta)));
    }

    /** Read the OPPO's subtitle or audio list, select the next entry. */
    private void cycle(boolean subtitles) {
        io.execute(() -> {
            try {
                Oppo o = oppo();
                JSONObject res = o.call(subtitles ? "getsubtitlemenulist" : "getaudiomenulist", null, 5000);
                JSONArray list = res.optJSONArray(subtitles ? "subtitle_list" : "audio_list");
                String kind = subtitles ? "subtitle" : "audio";
                if (list == null || list.length() == 0) { toast("no " + kind + " tracks"); return; }
                int cur = 0;
                for (int i = 0; i < list.length(); i++) if (list.getJSONObject(i).optBoolean("selected")) cur = i;
                JSONObject next = list.getJSONObject((cur + 1) % list.length());
                o.call(subtitles ? "setsubttmenulist" : "setaudiomenulist",
                        Oppo.obj("cur_index", next.getInt("index")), 5000);
                toast(next.optString("name", kind + " " + next.getInt("index")).trim());
            } catch (Exception e) {
                toast("failed: " + e.getMessage());
            }
        });
    }
}
