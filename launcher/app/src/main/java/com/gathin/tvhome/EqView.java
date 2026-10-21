package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.View;

/** Four little bars that dance while a song plays (widget card and screensaver). */
public class EqView extends View {

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private int color = 0xFFB3121B;
    private boolean active;

    public EqView(Context c) {
        super(c);
    }

    public EqView(Context c, AttributeSet a) {
        super(c, a);
    }

    void setColor(int c) {
        color = c;
        invalidate();
    }

    void setActive(boolean on) {
        active = on;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0) return;
        int n = 4;
        float gap = w * 0.12f;
        float bw = (w - gap * (n - 1)) / n;
        long t = SystemClock.uptimeMillis();
        paint.setColor(color);
        for (int i = 0; i < n; i++) {
            float v = active
                    ? 0.3f + 0.7f * (0.5f + 0.5f * (float) Math.sin(t / (170.0 + i * 61.0) + i * 1.7))
                    : 0.3f;
            float left = i * (bw + gap);
            rect.set(left, h - h * v, left + bw, h);
            canvas.drawRoundRect(rect, bw / 2f, bw / 2f, paint);
        }
        if (active) postInvalidateOnAnimation();
    }
}
