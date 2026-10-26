package com.gathin.tvhome;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.widget.Toast;

/** What the launcher's power menu does: the TV's own power menu is opened and the chosen item is pressed. */
final class PowerActions {

    static final int SLEEP = 0, RESTART = 1, OFF = 2;

    private PowerActions() {
    }

    static void perform(final Context ctx, final int which) {
        final String[] texts;
        Runnable miss;
        if (which == RESTART) {
            texts = new String[]{"restart", "reboot"};
            miss = () -> Toast.makeText(ctx, R.string.power_pick_system, Toast.LENGTH_LONG).show();
        } else if (which == OFF) {
            texts = new String[]{"power off", "shut down", "turn off", "switch off"};
            miss = () -> Toast.makeText(ctx, R.string.power_pick_system, Toast.LENGTH_LONG).show();
        } else {
            texts = new String[]{"sleep", "standby", "screen off"};
            // No "Sleep" in this TV's menu: the clock screensaver is the next best thing.
            miss = () -> ctx.startActivity(new Intent(ctx, ScreensaverActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        }
        final Runnable fMiss = miss;
        if (PowerAccessibilityService.ready()) {
            PowerAccessibilityService.run(texts, fMiss);
            return;
        }
        if (!enable(ctx)) {                                     // no permission to switch it on by itself
            Toast.makeText(ctx, R.string.power_need_access, Toast.LENGTH_LONG).show();
            try {
                ctx.startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (RuntimeException ignored) {
            }
            return;
        }
        // Just switched on: it takes a moment for the system to start the service.
        final Handler h = new Handler(Looper.getMainLooper());
        final long stop = SystemClock.uptimeMillis() + 4000;
        h.post(new Runnable() {
            @Override
            public void run() {
                if (PowerAccessibilityService.ready()) {
                    PowerAccessibilityService.run(texts, fMiss);
                } else if (SystemClock.uptimeMillis() < stop) {
                    h.postDelayed(this, 200);
                } else {
                    Toast.makeText(ctx, R.string.power_need_access, Toast.LENGTH_LONG).show();
                }
            }
        });
    }

    /** Turns the helper on in Accessibility settings (needs WRITE_SECURE_SETTINGS, granted once over adb). */
    private static boolean enable(Context ctx) {
        try {
            ComponentName cn = new ComponentName(ctx, PowerAccessibilityService.class);
            String full = cn.flattenToString(), brief = cn.flattenToShortString();
            String cur = Settings.Secure.getString(ctx.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            if (cur == null) cur = "";
            if (!cur.contains(full) && !cur.contains(brief)) {
                Settings.Secure.putString(ctx.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                        cur.isEmpty() ? full : cur + ":" + full);
            }
            Settings.Secure.putInt(ctx.getContentResolver(), Settings.Secure.ACCESSIBILITY_ENABLED, 1);
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }
}
