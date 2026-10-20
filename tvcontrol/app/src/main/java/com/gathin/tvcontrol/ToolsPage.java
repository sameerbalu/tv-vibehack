package com.gathin.tvcontrol;

import android.content.ContentResolver;
import android.provider.Settings;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Animation speed (after a one-time adb grant) and shortcuts into the TV's settings. */
final class ToolsPage extends Page {
    private TextView animState;

    ToolsPage(MainActivity a) {
        super(a);
    }

    private static final String[][] TILES = {
            {"Wi-Fi", Settings.ACTION_WIFI_SETTINGS},
            {"Bluetooth", Settings.ACTION_BLUETOOTH_SETTINGS},
            {"All apps", Settings.ACTION_MANAGE_ALL_APPLICATIONS_SETTINGS},
            {"Storage", Settings.ACTION_INTERNAL_STORAGE_SETTINGS},
            {"Developer options", Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS},
            {"Display", Settings.ACTION_DISPLAY_SETTINGS},
            {"Sound", Settings.ACTION_SOUND_SETTINGS},
            {"Date & time", Settings.ACTION_DATE_SETTINGS},
            {"Accessibility", Settings.ACTION_ACCESSIBILITY_SETTINGS},
            {"Default apps", Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS},
            {"Screen saver", Settings.ACTION_DREAM_SETTINGS},
            {"Notification access", "android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"},
            {"Usage access", Settings.ACTION_USAGE_ACCESS_SETTINGS},
            {"About this TV", Settings.ACTION_DEVICE_INFO_SETTINGS},
            {"All settings", Settings.ACTION_SETTINGS},
    };

    @Override
    View build() {
        LinearLayout col = Ui.page(act, sv, "Tools", "Speed tweaks and shortcuts to the settings you reach for most.");

        LinearLayout anim = Ui.card(act);
        anim.addView(Ui.text(act, "Animation speed", 17, 0xFFFFFFFF, 600));
        anim.addView(Ui.text(act, "Shorter animations make a slow TV feel much quicker.", 13, Ui.DIM, 400),
                Ui.margins(Ui.lp(-1, -2), 0, 4, 0, 0));
        LinearLayout ab = Ui.hbox(act);
        ab.addView(Ui.button(act, "Normal 1×", v -> setAnim(1f)), Ui.lp(-2, Ui.dp(44)));
        ab.addView(Ui.button(act, "Fast 0.5×", v -> setAnim(0.5f)), Ui.margins(Ui.lp(-2, Ui.dp(44)), 10, 0, 0, 0));
        ab.addView(Ui.button(act, "Off", v -> setAnim(0f)), Ui.margins(Ui.lp(-2, Ui.dp(44)), 10, 0, 0, 0));
        anim.addView(ab, Ui.margins(Ui.lp(-1, -2), 0, 14, 0, 0));
        animState = Ui.text(act, "", 13, Ui.DIM, 400);
        anim.addView(animState, Ui.margins(Ui.lp(-1, -2), 0, 12, 0, 0));
        col.addView(anim, Ui.margins(Ui.lp(-1, -2), 0, 20, 0, 0));

        col.addView(Ui.sectionLabel(act, "Shortcuts"), Ui.margins(Ui.lp(-2, -2), 4, 28, 0, 10));
        LinearLayout row = null;
        for (int i = 0; i < TILES.length; i++) {
            if (i % 3 == 0) {
                row = Ui.hbox(act);
                col.addView(row, Ui.margins(Ui.lp(-1, -2), 0, 0, 0, 10));
            }
            final String action = TILES[i][1];
            TextView b = Ui.button(act, TILES[i][0], v -> Sys.openSettings(act, action));
            row.addView(b, Ui.margins(new LinearLayout.LayoutParams(0, Ui.dp(56), 1f), 0, 0, i % 3 == 2 ? 0 : 10, 0));
        }
        refreshAnim();
        return sv[0];
    }

    @Override
    void onShow() {
        super.onShow();
        refreshAnim();
    }

    private float get(String key) {
        try {
            return Settings.Global.getFloat(act.getContentResolver(), key, 1f);
        } catch (Exception e) {
            return 1f;
        }
    }

    private void refreshAnim() {
        if (animState == null) return;
        float v = get("window_animation_scale");
        String cur = "Current: " + (v == 0f ? "off" : v + "×");
        if (Sys.canWriteSecure(act)) {
            animState.setTextColor(Ui.DIM);
            animState.setText(cur);
        } else {
            animState.setTextColor(Ui.WARN);
            animState.setText(cur + ". One-time setup, run on a PC / Termux:\nadb shell pm grant " + Sys.PKG
                    + " android.permission.WRITE_SECURE_SETTINGS");
        }
    }

    private void setAnim(float v) {
        try {
            ContentResolver cr = act.getContentResolver();
            Settings.Global.putFloat(cr, "window_animation_scale", v);
            Settings.Global.putFloat(cr, "transition_animation_scale", v);
            Settings.Global.putFloat(cr, "animator_duration_scale", v);
            Sys.toast(act, v == 0f ? "Animations off" : "Animation speed " + v + "×");
        } catch (Exception e) {
            Sys.toast(act, "Needs the one-time adb grant shown below");
        }
        refreshAnim();
    }
}
