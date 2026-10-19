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
        frac = Math.max(0f, Math.min(1f, f));
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
        float fw = Math.max(h, w * frac);
        if (shaderDirty || shaderW != w) {
            fill.setShader(new LinearGradient(0, 0, w, 0, c1, c2, Shader.TileMode.CLAMP));
            shaderDirty = false;
            shaderW = w;
        }
        rect.set(0, 0, fw, h);
        canvas.drawRoundRect(rect, r, r, fill);
    }
}
