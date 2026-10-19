package com.gathin.tvhome;

import android.animation.ValueAnimator;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.DrawableWrapper;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.view.animation.OvershootInterpolator;

/** Wraps a pill's icon so it can move and grow while the pill has focus (like the settings gear turning). */
final class IconAnim extends DrawableWrapper {

    private float dx;
    private float scale = 1f;
    private ValueAnimator anim;

    IconAnim(Drawable icon) {
        super(icon);
    }

    @Override
    public void draw(Canvas canvas) {
        Rect b = getBounds();
        canvas.save();
        canvas.translate(dx, 0f);
        canvas.scale(scale, scale, b.exactCenterX(), b.exactCenterY());
        super.draw(canvas);
        canvas.restore();
    }

    /** Plug-in: the icon slides in from the left and grows a little. */
    void plugIn(boolean focused, float distancePx) {
        cancel();
        final float fromDx = dx, fromScale = scale;
        final float toDx = focused ? 0f : 0f, toScale = focused ? 1.18f : 1f;
        if (focused) {
            dx = -distancePx;       // start from the left and slide into place
        }
        final float startDx = focused ? -distancePx : fromDx;
        anim = ValueAnimator.ofFloat(0f, 1f);
        anim.setDuration(focused ? 380 : 160);
        anim.setInterpolator(focused ? new OvershootInterpolator(1.8f) : new AccelerateDecelerateInterpolator());
        anim.addUpdateListener(a -> {
            float t = (Float) a.getAnimatedValue();
            dx = startDx + (toDx - startDx) * t;
            scale = fromScale + (toScale - fromScale) * t;
            invalidateSelf();
        });
        anim.start();
    }

    /** Pulse: the icon swells and relaxes again and again while the pill has focus. */
    void pulse(boolean focused) {
        cancel();
        if (focused) {
            anim = ValueAnimator.ofFloat(0f, 1f);
            anim.setDuration(700);
            anim.setRepeatCount(ValueAnimator.INFINITE);
            anim.setRepeatMode(ValueAnimator.REVERSE);
            anim.setInterpolator(new AccelerateDecelerateInterpolator());
            anim.addUpdateListener(a -> {
                scale = 1f + 0.26f * (Float) a.getAnimatedValue();
                invalidateSelf();
            });
            anim.start();
        } else {
            final float from = scale;
            anim = ValueAnimator.ofFloat(0f, 1f);
            anim.setDuration(160);
            anim.addUpdateListener(a -> {
                scale = from + (1f - from) * (Float) a.getAnimatedValue();
                invalidateSelf();
            });
            anim.start();
        }
    }

    /** Stops any animation and puts the icon back at rest. */
    void rest() {
        cancel();
        dx = 0f;
        scale = 1f;
        invalidateSelf();
    }

    private void cancel() {
        if (anim != null) {
            anim.cancel();
            anim = null;
        }
    }
}
