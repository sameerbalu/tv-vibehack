package com.gathin.tvcontrol;

import android.provider.Settings;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** One screen of the app (shown to the right of the tab rail). */
abstract class Page {
    final MainActivity act;
    private View root;
    protected final ScrollView[] sv = new ScrollView[1];
    private LinearLayout perm;

    Page(MainActivity act) {
        this.act = act;
    }

    abstract View build();

    View view() {
        if (root == null) root = build();
        return root;
    }

    void onShow() {
        if (perm != null) perm.setVisibility(Sys.hasUsageAccess(act) ? View.GONE : View.VISIBLE);
    }

    void onHide() {
    }

    /** Card asking for "usage access", which per-app data, cache sizes and recent apps need. */
    protected LinearLayout permCard() {
        LinearLayout c = Ui.card(act);
        c.setBackground(Ui.rect(0x26FF9F0A, 18, 0x66FF9F0A));
        c.addView(Ui.text(act, "One-time permission needed", 17, 0xFFFFFFFF, 600));
        TextView body = Ui.text(act, "Android hides per-app usage, cache sizes and recent apps until you allow "
                + "\"usage access\" for TV Control.", 14, Ui.DIM, 400);
        c.addView(body, Ui.margins(Ui.lp(-1, -2), 0, 6, 0, 0));
        TextView cmd = Ui.text(act, "If the screen doesn't open on your TV, run on a PC / Termux:\n"
                + "adb shell appops set " + Sys.PKG + " GET_USAGE_STATS allow", 13, 0xCCFFD28A, 400);
        c.addView(cmd, Ui.margins(Ui.lp(-1, -2), 0, 8, 0, 0));
        TextView b = Ui.button(act, "Open usage access settings", v -> Sys.openSettings(act, Settings.ACTION_USAGE_ACCESS_SETTINGS));
        c.addView(b, Ui.margins(Ui.lp(-2, Ui.dp(44)), 0, 14, 0, 0));
        c.setVisibility(Sys.hasUsageAccess(act) ? View.GONE : View.VISIBLE);
        perm = c;
        return c;
    }
}
