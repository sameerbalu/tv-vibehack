package com.gathin.tvhome;

import android.animation.ValueAnimator;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Shader;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.view.animation.DecelerateInterpolator;
import android.widget.TextView;

/** Small text effects: a light that sweeps across the letters, and letters that settle into place. */
final class TextFx {
    private TextFx() {
    }

    /** A soft band of light travels once from left to right over the text. Returns the animator (cancel it to stop). */
    static ValueAnimator sheen(final TextView tv, final int shine, long durationMs, long delayMs) {
        final int base = tv.getCurrentTextColor() | 0xFF000000;
        final ValueAnimator a = ValueAnimator.ofFloat(0f, 1f);
        a.setDuration(durationMs);
        a.setStartDelay(delayMs);
        a.setInterpolator(new AccelerateDecelerateInterpolator());
        a.addUpdateListener(v -> {
            float w = tv.getWidth();
            if (w <= 0) return;
            float p = (Float) v.getAnimatedValue();
            float band = Math.max(w * 0.5f, 40f);
            float x = -band + p * (w + band * 2f);
            tv.getPaint().setShader(new LinearGradient(x, 0, x + band, 0,
                    new int[]{base, shine, base}, new float[]{0f, 0.5f, 1f}, Shader.TileMode.CLAMP));
            tv.invalidate();
        });
        a.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(android.animation.Animator an) {
                tv.getPaint().setShader(null);
                tv.invalidate();
            }

            @Override
            public void onAnimationCancel(android.animation.Animator an) {
                tv.getPaint().setShader(null);
                tv.invalidate();
            }
        });
        a.start();
        return a;
    }

    /** Letters start wide apart and drift together while the text rises into place. */
    static ValueAnimator settle(final TextView tv, final float fromSpacing, final float toSpacing,
                                final float riseDp, long durationMs) {
        final float d = tv.getResources().getDisplayMetrics().density;
        final float baseY = tv.getTranslationY();      // the caller positions the view with setY: keep that
        final boolean[] cancelled = {false};
        final ValueAnimator a = ValueAnimator.ofFloat(0f, 1f);
        a.setDuration(durationMs);
        a.setInterpolator(new DecelerateInterpolator(2.2f));
        a.addUpdateListener(v -> {
            float p = (Float) v.getAnimatedValue();
            tv.setLetterSpacing(fromSpacing + (toSpacing - fromSpacing) * p);
            tv.setTranslationY(baseY + riseDp * d * (1f - p));
            tv.setAlpha(Math.min(1f, p * 1.6f));
            float s = 0.94f + 0.06f * p;
            tv.setScaleX(s);
            tv.setScaleY(s);
        });
        a.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override
            public void onAnimationCancel(android.animation.Animator an) {
                cancelled[0] = true;
            }

            @Override
            public void onAnimationEnd(android.animation.Animator an) {
                tv.setLetterSpacing(toSpacing);
                tv.setTranslationY(baseY);
                if (!cancelled[0]) tv.setAlpha(1f);
                tv.setScaleX(1f);
                tv.setScaleY(1f);
            }
        });
        a.start();
        return a;
    }

    /**
     * Never-ending shimmer: a soft band of light crosses the text, rests a moment, and crosses again. The light's
     * colour slowly travels around the colour wheel in pale tones, so it always looks a little different.
     */
    static ValueAnimator shimmerLoop(final TextView tv, long periodMs) {
        final int base = tv.getCurrentTextColor() | 0xFF000000;
        final long t0 = System.currentTimeMillis();
        final float[] hsv = {0f, 0.38f, 1f};
        final LinearGradient[] cache = new LinearGradient[1];
        final float[] hueSeen = {-100f}, bandSeen = {-1f};
        final int[] cols = {base, base, base};
        final float[] pos = {0f, 0.5f, 1f};
        final Matrix mx = new Matrix();
        final ValueAnimator a = ValueAnimator.ofFloat(0f, 1f);
        a.setDuration(periodMs);
        a.setRepeatCount(ValueAnimator.INFINITE);
        a.setInterpolator(new android.view.animation.LinearInterpolator());
        a.addUpdateListener(v -> {
            float w = tv.getWidth();
            if (w <= 0) return;
            float p = (Float) v.getAnimatedValue();
            float sweep = Math.min(1f, p / 0.62f);                    // the last part of each cycle is a rest
            float eased = sweep * sweep * (3f - 2f * sweep);
            float band = Math.max(w * 0.55f, 40f);
            float x = -band + eased * (w + band * 2f);
            float hue = ((System.currentTimeMillis() - t0) / 45f) % 360f;   // a full turn about every 16 seconds
            if (cache[0] == null || band != bandSeen[0] || Math.abs(hue - hueSeen[0]) >= 3f) {
                hsv[0] = hue;
                int shine = android.graphics.Color.HSVToColor(hsv);
                cols[1] = shine;
                cache[0] = new LinearGradient(0, 0, band, 0, cols, pos, Shader.TileMode.CLAMP);
                hueSeen[0] = hue;
                bandSeen[0] = band;
            }
            mx.setTranslate(x, 0);
            cache[0].setLocalMatrix(mx);
            tv.getPaint().setShader(cache[0]);
            tv.invalidate();
        });
        a.start();
        return a;
    }

    /**
     * Lines inside the letters: the digits are drawn as thin horizontal lines that grow thicker until the
     * letters are solid, rest a moment, then shrink back to thin lines. Only the text itself changes; nothing
     * is drawn behind it. The lines also drift slowly upward so it never looks frozen.
     */
    static ValueAnimator linesLoop(final TextView tv, long periodMs) {
        final int base = tv.getCurrentTextColor() | 0xFF000000;
        final int clear = base & 0x00FFFFFF;
        final float d = tv.getResources().getDisplayMetrics().density;
        final float period = Math.max(4f, 4.6f * d);         // one line plus its gap, in pixels
        final Matrix drift = new Matrix();
        final LinearGradient[] cached = new LinearGradient[48];
        final ValueAnimator a = ValueAnimator.ofFloat(0f, 1f);
        a.setDuration(periodMs);
        a.setRepeatCount(ValueAnimator.INFINITE);
        a.setRepeatMode(ValueAnimator.RESTART);
        a.setInterpolator(new android.view.animation.LinearInterpolator());
        a.addUpdateListener(v -> {
            float p = (Float) v.getAnimatedValue();
            float grow;                                       // 0 = thin lines, 1 = solid letters
            if (p < 0.42f) grow = smooth(p / 0.42f);
            else if (p < 0.60f) grow = 1f;
            else grow = 1f - smooth((p - 0.60f) / 0.40f);
            float duty = 0.24f + 0.76f * grow;               // share of each line period that is drawn
            if (duty >= 0.995f) {
                tv.getPaint().setShader(null);
            } else {
                int q = Math.max(0, Math.min(47, (int) ((duty - 0.24f) / 0.76f * 48f)));   // 48 steps look smooth and can be cached
                LinearGradient lines = cached[q];
                if (lines == null) {
                    float dq = 0.24f + 0.76f * q / 48f;
                    lines = new LinearGradient(0, 0, 0, period,
                            new int[]{base, base, clear, clear},
                            new float[]{0f, dq, Math.min(1f, dq + 0.002f), 1f}, Shader.TileMode.REPEAT);
                    cached[q] = lines;
                }
                drift.setTranslate(0f, -p * period);
                lines.setLocalMatrix(drift);
                tv.getPaint().setShader(lines);
            }
            tv.invalidate();
        });
        a.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override
            public void onAnimationCancel(android.animation.Animator an) {
                tv.getPaint().setShader(null);
                tv.invalidate();
            }
        });
        a.start();
        return a;
    }

    private static float smooth(float t) {
        t = Math.max(0f, Math.min(1f, t));
        return t * t * (3f - 2f * t);
    }
}
