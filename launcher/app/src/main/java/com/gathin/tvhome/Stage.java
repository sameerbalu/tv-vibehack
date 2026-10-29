package com.gathin.tvhome;

import android.animation.ValueAnimator;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.Interpolator;

/**
 * Moves every picture layer of the home screen (wallpaper, blurred wallpaper, depth foreground, PNG over the dock)
 * with exactly the same zoom and shift, so the characters always stay lined up with the wallpaper behind them.
 * One animator drives all layers: no allocation per frame, only view transforms (the GPU just moves the pictures).
 */
final class Stage {

    private final View[] layers;
    private final Runnable afterFrame;
    private final ValueAnimator anim = ValueAnimator.ofFloat(0f, 1f);
    private float z = 1f, x = 0f, y = 0f;          // what is on screen now
    private float fz = 1f, fx = 0f, fy = 0f;       // where the running animation started
    private float tz = 1f, tx = 0f, ty = 0f;       // where it is going
    private final Interpolator decel = new DecelerateInterpolator(1.8f);

    Stage(View[] layers, Runnable afterFrame) {
        this.layers = layers;
        this.afterFrame = afterFrame;
        anim.addUpdateListener(a -> {
            float t = (Float) a.getAnimatedValue();
            z = fz + (tz - fz) * t;
            x = fx + (tx - fx) * t;
            y = fy + (ty - fy) * t;
            apply();
        });
    }

    float zoom() {
        return z;
    }

    float tx() {
        return x;
    }

    float ty() {
        return y;
    }

    /** Glides to a new zoom and shift, starting from wherever the pictures are right now. */
    void to(float zoom, float shiftX, float shiftY, long ms) {
        to(zoom, shiftX, shiftY, ms, decel);
    }

    void to(float zoom, float shiftX, float shiftY, long ms, Interpolator ip) {
        if (anim.isRunning() && zoom == tz && shiftX == tx && shiftY == ty) return;   // already on its way there
        anim.cancel();
        fz = z;
        fx = x;
        fy = y;
        tz = zoom;
        tx = shiftX;
        ty = shiftY;
        if (fz == tz && fx == tx && fy == ty) return;
        anim.setDuration(Math.max(1L, ms));
        anim.setInterpolator(ip);
        anim.start();
    }

    /** Puts the pictures there at once (no animation). */
    void jump(float zoom, float shiftX, float shiftY) {
        anim.cancel();
        z = tz = fz = zoom;
        x = tx = fx = shiftX;
        y = ty = fy = shiftY;
        apply();
    }

    private void apply() {
        for (View v : layers) {
            if (v == null) continue;
            v.setScaleX(z);
            v.setScaleY(z);
            v.setTranslationX(x);
            v.setTranslationY(y);
        }
        if (afterFrame != null) afterFrame.run();
    }
}
