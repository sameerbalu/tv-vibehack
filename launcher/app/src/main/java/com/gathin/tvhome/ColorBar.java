package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.View;

/** A thin rounded progress bar whose fill is a left-to-right gradient (colours are set by the caller). */
public class ColorBar extends View {
    private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private float frac;
    private float shown = -1f;          // fill actually drawn (eases towards frac)
    private android.animation.ValueAnimator grow;
    private android.animation.ValueAnimator shine;
    private float shineT = -1f;
    private int c1 = 0xFFFFFFFF, c2 = 0xFFFFFFFF;
    private boolean shaderDirty = true;
    private int shaderW = -1;

    public ColorBar(Context c) {
        this(c, null);
    }

    public ColorBar(Context c, AttributeSet a) {
        super(c, a);
        track.setColor(0x26FFFFFF);
    }

    void set(float f, int startColor, int endColor) {
        float target = Math.max(0f, Math.min(1f, f));
        if (shown < 0f || !isAttachedToWindow()) {
            shown = target;
        } else if (Math.abs(target - frac) > 0.002f) {
            if (grow != null) grow.cancel();
            grow = android.animation.ValueAnimator.ofFloat(shown, target);
            grow.setDuration(700);
            grow.setInterpolator(new android.view.animation.DecelerateInterpolator(2f));
            grow.addUpdateListener(a -> {
                shown = (Float) a.getAnimatedValue();
                invalidate();
            });
            grow.start();
        }
        frac = target;
        if (startColor != c1 || endColor != c2) {
            c1 = startColor;
            c2 = endColor;
            shaderDirty = true;
        }
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0) return;
        float r = h / 2f;
        rect.set(0, 0, w, h);
        canvas.drawRoundRect(rect, r, r, track);
        float fw = Math.max(h, w * (shown < 0f ? frac : shown));
        if (shaderDirty || shaderW != w) {
            fill.setShader(new LinearGradient(0, 0, w, 0, c1, c2, Shader.TileMode.CLAMP));
            shaderDirty = false;
            shaderW = w;
        }
        rect.set(0, 0, fw, h);
        canvas.drawRoundRect(rect, r, r, fill);
        if (shineT >= 0f && shineT <= 1f) {                    // a soft light that runs along the fill now and then
            int save = canvas.save();
            canvas.clipRect(0, 0, fw, h);
            float x = -h * 3 + (fw + h * 6) * shineT;
            shinePaint.setShader(new LinearGradient(x - h * 3, 0, x + h * 3, 0,
                    new int[]{0x00FFFFFF, 0x66FFFFFF, 0x00FFFFFF}, null, Shader.TileMode.CLAMP));
            canvas.drawRoundRect(rect, r, r, shinePaint);
            canvas.restoreToCount(save);
        }
    }

    private final Paint shinePaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private boolean shineOn;

    /** Turns on the light that runs along the fill (off by default). */
    void setShine(boolean on) {
        shineOn = on;
        if (isAttachedToWindow()) {
            if (shine != null) shine.cancel();
            shineT = -1f;
            if (on) startShine();
            invalidate();
        }
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (shineOn) startShine();
    }

    private void startShine() {
        shine = android.animation.ValueAnimator.ofFloat(0f, 1f);
        shine.setDuration(4200);
        shine.setRepeatCount(android.animation.ValueAnimator.INFINITE);
        shine.setInterpolator(new android.view.animation.LinearInterpolator());
        final float offset = (System.identityHashCode(this) % 100) / 100f * 0.4f;
        shine.addUpdateListener(a -> {
            float v = ((Float) a.getAnimatedValue() + offset) % 1f;
            float n = v < 0.45f ? v / 0.45f : -1f;               // shines for the first part of each cycle only
            if (n != shineT) {
                shineT = n;
                if (n >= 0f || shineT < 0f) invalidate();
            }
        });
        shine.start();
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        if (shine != null) shine.cancel();
        if (grow != null) grow.cancel();
    }
}
