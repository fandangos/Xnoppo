package com.fandangos.xnoppocast;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONObject;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Launcher screen: Xnoppo address, connection test, live status and a remote. */
public class MainActivity extends Activity {
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private EditText server, token;
    private TextView health, status;
    private volatile long positionS = -1;
    private volatile boolean visible;
    private final Runnable pollTask = this::poll;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout col = Ui.column(this);
        col.addView(Ui.text(this, "Xnoppo Cast", 26, true));
        col.addView(Ui.text(this, "In Nuvio or Stremio, choose an external player and pick "
                + "“Play on OPPO”. The stream plays on the OPPO through Xnoppo, "
                + "with subtitles.", 16, false));

        col.addView(Ui.text(this, "Xnoppo address", 16, true));
        server = new EditText(this);
        server.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        server.setText(Api.prefs(this).getString("server", Api.DEFAULT_SERVER));
        col.addView(server);
        col.addView(Ui.text(this, "Token (only if CAST_TOKEN is set)", 16, true));
        token = new EditText(this);
        token.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        token.setText(Api.prefs(this).getString("token", ""));
        col.addView(token);
        col.addView(Ui.button(this, "Save & test", v -> saveAndTest()));
        health = Ui.text(this, "", 16, false);
        col.addView(health);

        col.addView(Ui.text(this, "Now", 16, true));
        status = Ui.text(this, "…", 18, false);
        col.addView(status);
        for (LinearLayout r : new Remote(this, io, () -> positionS).build()) col.addView(r);
        setContentView(Ui.screen(this, col));
    }

    private void saveAndTest() {
        Api.prefs(this).edit()
                .putString("server", server.getText().toString().trim())
                .putString("token", token.getText().toString().trim())
                .apply();
        health.setText("Testing…");
        io.execute(() -> {
            String msg;
            try {
                JSONObject h = new Api(this).get("/health");
                msg = h.optBoolean("ok") ? "Connected to Xnoppo ✓" : "Xnoppo is up, but: " + h.optString("problem");
            } catch (Exception e) {
                msg = "Can't reach Xnoppo: " + e.getMessage();
            }
            String m = msg;
            ui.post(() -> health.setText(m));
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        visible = true;
        ui.removeCallbacks(pollTask);
        poll();
    }

    @Override
    protected void onPause() {
        visible = false;
        ui.removeCallbacks(pollTask);
        super.onPause();
    }

    private void poll() {
        if (!visible) return;
        io.execute(() -> {
            String text;
            try {
                JSONObject s = new Api(this).get("/status");
                String st = s.optString("stage", "idle");
                if (s.has("position") && !s.isNull("position")) positionS = (long) s.optDouble("position", -1);
                text = st.equals("idle") ? "Nothing cast yet"
                        : s.optString("title") + "\n" + st
                        + (st.equals("playing") ? "  " + Ui.clock(positionS) + " / " + Ui.clock(s.optLong("duration", -1)) : "")
                        + (st.equals("error") ? "\n" + s.optString("error") : "");
            } catch (Exception e) {
                text = "Xnoppo not reachable (" + e.getMessage() + ")";
            }
            String t = text;
            ui.post(() -> status.setText(t));
            ui.postDelayed(pollTask, 3000);
        });
    }

    @Override
    protected void onDestroy() {
        io.shutdownNow();
        super.onDestroy();
    }
}
