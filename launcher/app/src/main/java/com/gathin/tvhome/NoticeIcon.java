package com.gathin.tvhome;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.os.SystemClock;
import android.view.View;
import android.view.animation.OvershootInterpolator;

/** The small status icon of the bottom notice: a spinner while working, then a tick, a warning or an info mark that pops in. */
final class NoticeIcon extends View {

    static final int WORKING = 0, OK = 1, WARN = 2, INFO = 3;

    private final float d;
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();
    private final Path path = new Path();
    private int kind = WORKING;
    private float pop = 1f;
    private ValueAnimator anim;

    NoticeIcon(Context c) {
        super(c);
        d = c.getResources().getDisplayMetrics().density;
    }

    void setKind(int k) {
        if (k == kind && getTag() != null) return;
        setTag(Boolean.TRUE);
        kind = k;
        if (anim != null) anim.cancel();
        if (k == WORKING) {
            pop = 1f;
        } else {
            pop = 0f;
            anim = ValueAnimator.ofFloat(0f, 1f);
            anim.setDuration(320);
            anim.setInterpolator(new OvershootInterpolator(2.2f));
            anim.addUpdateListener(a -> {
                pop = (float) a.getAnimatedValue();
                invalidate();
            });
            anim.start();
        }
        invalidate();
    }

    @Override
    protected void onDetachedFromWindow() {
        if (anim != null) anim.cancel();
        super.onDetachedFromWindow();
    }

    @Override
    protected void onMeasure(int w, int h) {
        int s = (int) (22 * d);
        setMeasuredDimension(s, s);
    }

    @Override
    protected void onDraw(Canvas c) {
        float cx = getWidth() / 2f, cy = getHeight() / 2f, rad = getWidth() / 2f - d;
        if (kind == WORKING) {
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(2.2f * d);
            p.setStrokeCap(Paint.Cap.ROUND);
            p.setColor(0x33FFFFFF);
            c.drawCircle(cx, cy, rad - d, p);
            p.setColor(0xFFFFFFFF);
            r.set(cx - rad + d, cy - rad + d, cx + rad - d, cy + rad - d);
            float t = (SystemClock.uptimeMillis() % 900) / 900f;
            c.drawArc(r, t * 360f - 90f, 95f, false, p);
            postInvalidateOnAnimation();
            return;
        }
        int col = kind == OK ? 0xFF30D158 : kind == WARN ? 0xFFFF9F0A : 0xFF0A84FF;
        float s = Math.max(0f, pop);
        p.setStyle(Paint.Style.FILL);
        p.setColor(col);
        c.drawCircle(cx, cy, rad * s, p);
        c.save();
        c.scale(s, s, cx, cy);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(2.2f * d);
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setStrokeJoin(Paint.Join.ROUND);
        p.setColor(0xFFFFFFFF);
        path.reset();
        if (kind == OK) {
            path.moveTo(cx - rad * 0.42f, cy + rad * 0.02f);
            path.lineTo(cx - rad * 0.1f, cy + rad * 0.34f);
            path.lineTo(cx + rad * 0.46f, cy - rad * 0.32f);
            c.drawPath(path, p);
        } else {
            float top = kind == WARN ? cy - rad * 0.46f : cy - rad * 0.06f;
            float bot = kind == WARN ? cy + rad * 0.08f : cy + rad * 0.46f;
            c.drawLine(cx, top, cx, bot, p);
            p.setStyle(Paint.Style.FILL);
            c.drawCircle(cx, kind == WARN ? cy + rad * 0.44f : cy - rad * 0.42f, 1.3f * d, p);
        }
        c.restore();
    }
}
