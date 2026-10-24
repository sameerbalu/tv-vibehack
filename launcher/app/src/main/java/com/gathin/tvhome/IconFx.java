package com.gathin.tvhome;

import android.animation.ValueAnimator;
import android.os.SystemClock;
import android.view.View;
import android.view.animation.LinearInterpolator;
import android.widget.ImageView;

/** Menu icons come alive while their row is focused (like the Inputs / Bluetooth icons in the top bar). */
final class IconFx {

    static final int WIGGLE = 0, SPIN = 1, PULSE = 2, NUDGE = 3;

    private IconFx() {
    }

    static int styleFor(int iconRes) {
        if (iconRes == R.drawable.ic_settings || iconRes == R.drawable.ic_reset
                || iconRes == R.drawable.ic_stopapps || iconRes == R.drawable.ic_memory) return SPIN;
        if (iconRes == R.drawable.ic_wifi || iconRes == R.drawable.ic_bluetooth || iconRes == R.drawable.ic_input
                || iconRes == R.drawable.ic_av || iconRes == R.drawable.ic_livetv || iconRes == R.drawable.ic_saver
                || iconRes == R.drawable.ic_tvcontrol) return PULSE;
        if (iconRes == R.drawable.ic_play_circle) return NUDGE;
        return WIGGLE;
    }

    /** Animates {@code icon} whenever {@code row} has focus. App icons (no tint) stay still. */
    static void attach(final View row, final View iconView) {
        if (!(iconView instanceof ImageView)) return;
        final ImageView icon = (ImageView) iconView;
        if (icon.getImageTintList() == null) return;
        Object tag = icon.getTag();
        final int style = tag instanceof Integer ? (Integer) tag : WIGGLE;
        final View.OnFocusChangeListener prev = row.getOnFocusChangeListener();
        row.setOnFocusChangeListener((v, focused) -> {
            if (prev != null) prev.onFocusChange(v, focused);
            toggle(icon, style, focused);
        });
    }

    private static void toggle(final ImageView icon, final int style, boolean on) {
        Object old = icon.getTag(R.id.icon);
        if (old instanceof ValueAnimator) ((ValueAnimator) old).cancel();
        icon.setTag(R.id.icon, null);
        if (!on) {
            icon.animate().rotation(0f).scaleX(1f).scaleY(1f).translationX(0f).translationY(0f)
                    .setDuration(160).start();
            return;
        }
        icon.animate().cancel();
        final float d = icon.getResources().getDisplayMetrics().density;
        final long t0 = SystemClock.uptimeMillis();
        ValueAnimator va = ValueAnimator.ofFloat(0f, 1f);
        va.setDuration(style == SPIN ? 1600 : 1100);
        va.setRepeatCount(ValueAnimator.INFINITE);
        va.setInterpolator(new LinearInterpolator());
        va.addUpdateListener(a -> {
            float t = (Float) a.getAnimatedValue();
            float ramp = Math.min(1f, (SystemClock.uptimeMillis() - t0) / 200f);   // settle in gently
            float sin = (float) Math.sin(t * 2 * Math.PI);
            float scale = 1f, rot = 0f, tx = 0f;
            switch (style) {
                case SPIN: {
                    float k = t < 0.7f ? t / 0.7f : 1f;
                    k = k * k * (3f - 2f * k);                      // ease in and out, then rest
                    rot = 360f * k;
                    scale = 1.12f;
                    break;
                }
                case PULSE:
                    scale = 1.14f + 0.10f * sin;
                    break;
                case NUDGE:
                    scale = 1.12f;
                    tx = 2.5f * d * sin;
                    break;
                default:
                    scale = 1.14f;
                    rot = 10f * (float) Math.sin(t * 4 * Math.PI) * (t < 0.6f ? 1f : 0.25f);
                    break;
            }
            icon.setScaleX(1f + (scale - 1f) * ramp);
            icon.setScaleY(1f + (scale - 1f) * ramp);
            icon.setRotation(style == SPIN ? rot : rot * ramp);
            icon.setTranslationX(tx * ramp);
        });
        icon.setTag(R.id.icon, va);
        va.start();
    }
}
