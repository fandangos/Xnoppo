package com.fandangos.xnoppocast;

import android.app.Activity;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.BitmapDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.View;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import io.nayuki.qrcodegen.QrCode;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Launcher screen: the OPPO and the server's share, a connection test, live status and a remote. */
public class MainActivity extends Activity {
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private EditText oppoIp, smbIp, smbName, share, root, readAhead, amUrl, amKey, server, token;
    private TextView health, status, setupText;
    private ImageView qr;
    private PhoneSetup setup;
    private volatile long positionS = -1;
    private volatile boolean visible;
    private final Runnable pollTask = this::poll;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        SharedPreferences p = Api.prefs(this);
        LinearLayout col = Ui.column(this);
        col.addView(Ui.text(this, "Xnoppo Cast", 26, true));
        col.addView(Ui.text(this, "In Nuvio or Stremio, choose an external player and pick “Play on OPPO”. "
                + "AIOStreams Usenet releases (AltMount) play on the OPPO straight from the server's share.", 16, false));
        col.addView(Ui.button(this, "Set up from phone", v -> toggleSetup()));
        setupText = Ui.text(this, "", 18, false);
        setupText.setVisibility(View.GONE);
        col.addView(setupText);
        qr = new ImageView(this);
        qr.setVisibility(View.GONE);
        qr.setAdjustViewBounds(true);
        col.addView(qr, new LinearLayout.LayoutParams(Ui.dp(this, 260), Ui.dp(this, 260)));

        oppoIp = field(col, "OPPO IP address", p.getString("oppo_ip", DirectPlay.Settings.D_OPPO), false);
        smbIp = field(col, "Server IP address (the SMB server with the AltMount share)",
                p.getString("smb_ip", DirectPlay.Settings.D_SMB_IP), false);
        smbName = field(col, "Server name, as the OPPO lists it (NetBIOS, e.g. TOWER)",
                p.getString("smb_name", DirectPlay.Settings.D_SMB_NAME), false);
        share = field(col, "Share", p.getString("share", DirectPlay.Settings.D_SHARE), false);
        root = field(col, "Folder in the share that holds AltMount's files",
                p.getString("root", DirectPlay.Settings.D_ROOT), false);
        readAhead = field(col, "Read-ahead server (rclone WebDAV over the same files; empty = off)",
                p.getString("read_ahead", DirectPlay.Settings.D_READ_AHEAD), false);
        amUrl = field(col, "AltMount address (to show which release AIOStreams is trying)",
                p.getString("am_url", DirectPlay.Settings.D_AM_URL), false);
        amKey = field(col, "AltMount API key (Configuration > System; empty = don't show)", p.getString("am_key", ""), true);
        CheckBox discs = new CheckBox(this);
        discs.setText("OPPO plays full discs only (ISO / Blu-ray folder); a fallback to a video file is refused");
        discs.setTextColor(Color.parseColor("#C8D0D8"));
        discs.setTextSize(16);
        discs.setChecked(p.getBoolean("discs_only", true));
        discs.setOnCheckedChangeListener((b, on) -> Api.prefs(this).edit().putBoolean("discs_only", on).apply());
        col.addView(discs);
        server = field(col, "Xnoppo address, only for streams that aren't AltMount releases (optional)",
                p.getString("server", ""), false);
        token = field(col, "Xnoppo token (only if CAST_TOKEN is set)", p.getString("token", ""), true);
        col.addView(Ui.button(this, "Save & test", v -> saveAndTest()));
        health = Ui.text(this, "", 16, false);
        col.addView(health);

        col.addView(Ui.text(this, "Now", 16, true));
        status = Ui.text(this, "…", 18, false);
        col.addView(status);
        for (LinearLayout r : new Remote(this, io, () -> positionS).build()) col.addView(r);
        setContentView(Ui.screen(this, col));
    }

    private void toggleSetup() {
        if (setup != null) {
            stopSetup();
            return;
        }
        setup = new PhoneSetup(this, () -> ui.post(() -> {
            reloadFields();
            health.setText("Saved from the phone ✓  (Save & test to check)");
        }));
        PhoneSetup s = setup;
        io.execute(() -> {
            try {
                String url = s.start(DirectPlay.Settings.load(this).oppoIp);
                Bitmap bmp = qrBitmap(url);
                ui.post(() -> {
                    if (setup != s) return;
                    setupText.setText("Scan with your phone, or open " + url + "\nPIN " + s.pin
                            + "   (press “Set up from phone” again to close)");
                    setupText.setVisibility(View.VISIBLE);
                    BitmapDrawable d = new BitmapDrawable(getResources(), bmp);
                    d.setFilterBitmap(false);   // keep the modules sharp
                    qr.setImageDrawable(d);
                    qr.setVisibility(View.VISIBLE);
                });
            } catch (Exception e) {
                ui.post(() -> {
                    setupText.setText("Couldn't open the setup page: " + e.getMessage());
                    setupText.setVisibility(View.VISIBLE);
                });
            }
        });
    }

    private void stopSetup() {
        if (setup != null) setup.stop();
        setup = null;
        setupText.setVisibility(View.GONE);
        qr.setVisibility(View.GONE);
    }

    private static Bitmap qrBitmap(String text) {
        QrCode code = QrCode.encodeText(text, QrCode.Ecc.MEDIUM);
        int border = 4, n = code.size + border * 2;
        Bitmap b = Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888);
        for (int y = 0; y < n; y++) {
            for (int x = 0; x < n; x++) {
                boolean dark = code.getModule(x - border, y - border);
                b.setPixel(x, y, dark ? Color.BLACK : Color.WHITE);
            }
        }
        return b;
    }

    private void reloadFields() {
        SharedPreferences p = Api.prefs(this);
        oppoIp.setText(p.getString("oppo_ip", DirectPlay.Settings.D_OPPO));
        smbIp.setText(p.getString("smb_ip", DirectPlay.Settings.D_SMB_IP));
        smbName.setText(p.getString("smb_name", DirectPlay.Settings.D_SMB_NAME));
        share.setText(p.getString("share", DirectPlay.Settings.D_SHARE));
        root.setText(p.getString("root", DirectPlay.Settings.D_ROOT));
        readAhead.setText(p.getString("read_ahead", DirectPlay.Settings.D_READ_AHEAD));
        amUrl.setText(p.getString("am_url", DirectPlay.Settings.D_AM_URL));
        amKey.setText(p.getString("am_key", ""));
        server.setText(p.getString("server", ""));
        token.setText(p.getString("token", ""));
    }

    private EditText field(LinearLayout col, String label, String value, boolean secret) {
        col.addView(Ui.text(this, label, 16, true));
        EditText e = new EditText(this);
        e.setInputType(InputType.TYPE_CLASS_TEXT | (secret ? InputType.TYPE_TEXT_VARIATION_PASSWORD
                : InputType.TYPE_TEXT_VARIATION_URI));
        e.setText(value);
        col.addView(e);
        return e;
    }

    private static String v(EditText e) {
        return e.getText().toString().trim();
    }

    private void saveAndTest() {
        Api.prefs(this).edit()
                .putString("oppo_ip", v(oppoIp)).putString("smb_ip", v(smbIp)).putString("smb_name", v(smbName))
                .putString("share", v(share)).putString("root", v(root)).putString("read_ahead", v(readAhead))
                .putString("am_url", v(amUrl)).putString("am_key", v(amKey))
                .putString("server", v(server)).putString("token", v(token))
                .apply();
        health.setText("Testing…");
        io.execute(() -> {
            DirectPlay.Settings s = DirectPlay.Settings.load(this);
            StringBuilder msg = new StringBuilder();
            Oppo o = new Oppo(s.oppoIp);
            if (!o.portOpen(Oppo.PORT, 1500)) {
                for (int i = 0; i < 15 && !o.portOpen(Oppo.PORT, 1000); i++) o.wake();
            }
            if (!o.portOpen(Oppo.PORT, 1500)) {
                msg.append("OPPO not answering at ").append(s.oppoIp)
                        .append(" (on, with network standby: Setup > Device Setup > Standby Mode: Quick Start?)");
            } else {
                msg.append("OPPO found ✓");
                o.call("signin", Oppo.obj("appIconType", 1, "appIpAddress", o.localAddress()), 8000);
                boolean listed = false;
                for (int i = 0; i < 8 && !listed; i++) {
                    JSONArray list = o.call("getdevicelist", null, 8000).optJSONArray("devicelist");
                    for (int j = 0; list != null && j < list.length(); j++) {
                        JSONObject d = list.optJSONObject(j);
                        if (d != null && s.smbName.equalsIgnoreCase(d.optString("name"))) listed = true;
                    }
                    if (!listed) {
                        try {
                            Thread.sleep(2000);
                        } catch (InterruptedException e) {
                            return;
                        }
                    }
                }
                msg.append(listed ? "\nIt sees " + s.smbName + " ✓"
                        : "\nIt doesn't list " + s.smbName + " (yet): check the server name");
            }
            if (!s.readAhead.isEmpty()) {
                int code = -1;
                try {
                    java.net.HttpURLConnection c = (java.net.HttpURLConnection)
                            new java.net.URL(s.readAhead + "/webdav/").openConnection();
                    c.setConnectTimeout(3000);
                    code = c.getResponseCode();
                    c.disconnect();
                } catch (Exception ignored) {
                }
                msg.append(code > 0 ? "\nRead-ahead server answers ✓" : "\nRead-ahead server not reachable (" + s.readAhead + ")");
            }
            String m = msg.toString();
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
        stopSetup();
        ui.removeCallbacks(pollTask);
        super.onPause();
    }

    private void poll() {
        if (!visible) return;
        io.execute(() -> {
            String text;
            Oppo o = new Oppo(DirectPlay.Settings.load(this).oppoIp);
            if (!o.portOpen(Oppo.PORT, 800)) {
                text = "OPPO is off or asleep";
            } else if (o.globalInfo().optBoolean("is_video_playing")) {
                JSONObject t = o.call("getplayingtime", null, 5000);
                positionS = t.optLong("cur_time", -1);
                text = "Playing  " + Ui.clock(positionS) + " / " + Ui.clock(t.optLong("total_time", -1));
            } else {
                text = "Not playing";
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
