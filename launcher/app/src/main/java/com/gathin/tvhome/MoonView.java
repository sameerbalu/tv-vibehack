package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

/** The moon as it looks tonight (computed from the date, no network). Used by a widget and the screensaver. */
public class MoonView extends View {

    private static final double SYNODIC_DAYS = 29.530588853;
    private static final long NEW_MOON_MS = 947182440000L;      // 6 Jan 2000, 18:14 UTC

    private final Paint dark = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint lit = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint rim = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final RectF circle = new RectF();
    private final RectF ellipse = new RectF();
    private float phase;

    public MoonView(Context context, AttributeSet attrs) {
        super(context, attrs);
        dark.setColor(0xFF2B3040);
        lit.setColor(0xFFF3E9C6);
        rim.setStyle(Paint.Style.STROKE);
        rim.setColor(0x33FFFFFF);
        rim.setStrokeWidth(1.2f * getResources().getDisplayMetrics().density);
        phase = phaseNow();
    }

    /** 0 = new moon, 0.5 = full moon, back to 1 = new moon. */
    static float phaseNow() {
        double days = (System.currentTimeMillis() - NEW_MOON_MS) / 86400000.0;
        double p = (days / SYNODIC_DAYS) % 1.0;
        if (p < 0) p += 1.0;
        return (float) p;
    }

    static int litPercent(float p) {
        return Math.round((1f - (float) Math.cos(2 * Math.PI * p)) / 2f * 100f);
    }

    static String phaseName(float p) {
        if (p < 0.03f || p >= 0.97f) return "New moon";
        if (p < 0.22f) return "Waxing crescent";
        if (p < 0.28f) return "First quarter";
        if (p < 0.47f) return "Waxing gibbous";
        if (p < 0.53f) return "Full moon";
        if (p < 0.72f) return "Waning gibbous";
        if (p < 0.78f) return "Last quarter";
        return "Waning crescent";
    }

    void setPhase(float p) {
        phase = p;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float w = getWidth(), h = getHeight();
        float r = Math.min(w, h) / 2f - rim.getStrokeWidth();
        if (r <= 0) return;
        float cx = w / 2f, cy = h / 2f;
        canvas.drawCircle(cx, cy, r, dark);

        float c = (float) Math.cos(2 * Math.PI * phase);        // 1 at new moon, -1 at full moon
        canvas.save();
        if (phase >= 0.5f) canvas.scale(-1f, 1f, cx, cy);       // waning: the lit side is on the left
        circle.set(cx - r, cy - r, cx + r, cy + r);
        float ex = Math.abs(c) * r;
        ellipse.set(cx - ex, cy - r, cx + ex, cy + r);
        path.reset();
        path.arcTo(circle, -90f, 180f);                         // right half of the disc, top to bottom
        if (c > 0) path.arcTo(ellipse, 90f, -180f);             // crescent: terminator bulges right
        else path.arcTo(ellipse, 90f, 180f);                    // gibbous: terminator bulges left
        path.close();
        canvas.drawPath(path, lit);
        canvas.restore();
        canvas.drawCircle(cx, cy, r, rim);
    }
}
