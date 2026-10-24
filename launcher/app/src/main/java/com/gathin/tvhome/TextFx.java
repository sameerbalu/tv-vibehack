package com.gathin.tvhome;

import android.animation.ValueAnimator;
import android.graphics.LinearGradient;
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
            hsv[0] = ((System.currentTimeMillis() - t0) / 45f) % 360f;   // a full turn about every 16 seconds
            int shine = android.graphics.Color.HSVToColor(hsv);
            tv.getPaint().setShader(new LinearGradient(x, 0, x + band, 0,
                    new int[]{base, shine, base}, new float[]{0f, 0.5f, 1f}, Shader.TileMode.CLAMP));
            tv.invalidate();
        });
        a.start();
        return a;
    }
}
