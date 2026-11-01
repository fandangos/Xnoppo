package com.fandangos.xnoppocast;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** Code-built views: large, focusable, readable from the couch (D-pad) and on a phone. */
final class Ui {
    private Ui() {}

    static int dp(Activity a, int v) {
        return Math.round(v * a.getResources().getDisplayMetrics().density);
    }

    static LinearLayout column(Activity a) {
        LinearLayout l = new LinearLayout(a);
        l.setOrientation(LinearLayout.VERTICAL);
        int p = dp(a, 24);
        l.setPadding(p, p, p, p);
        return l;
    }

    static LinearLayout row(Activity a) {
        LinearLayout l = new LinearLayout(a);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(Gravity.CENTER_VERTICAL);
        return l;
    }

    static View screen(Activity a, LinearLayout content) {
        ScrollView sv = new ScrollView(a);
        sv.setFitsSystemWindows(true);
        sv.setBackgroundColor(Color.parseColor("#101418"));
        sv.addView(content);
        return sv;
    }

    static TextView text(Activity a, String s, int sp, boolean bold) {
        TextView t = new TextView(a);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(Color.parseColor(bold ? "#FFFFFF" : "#C8D0D8"));
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setPadding(0, dp(a, 4), 0, dp(a, 4));
        return t;
    }

    static Button button(Activity a, String label, View.OnClickListener l) {
        Button b = new Button(a);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(18);
        b.setOnClickListener(l);
        b.setFocusable(true);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, dp(a, 4), dp(a, 8), dp(a, 4));
        b.setLayoutParams(lp);
        return b;
    }

    static String clock(long seconds) {
        if (seconds < 0) return "--:--";
        long h = seconds / 3600, m = (seconds % 3600) / 60, s = seconds % 60;
        return h > 0 ? String.format("%d:%02d:%02d", h, m, s) : String.format("%d:%02d", m, s);
    }
}
