package com.gathin.tvhome;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;

/**
 * The on/off indicator used in menus and the control center: a small pill switch for dark glass. Off it is a faint
 * glass track with a grey knob. On the track fills with the accent colour and glows softly, the knob slides across
 * (with a little overshoot) and turns dark, so it never reads as a white blob on the bright track.
 *
 * Rows drive it the same way they drove the old dot: setVisibility(VISIBLE) = on, anything else = off. The view
 * itself stays visible. Use setIndicatorHidden(true) for rows that are not on/off at all, and setCheckStyle(true)
 * for "selected" markers (a round accent badge with a tick that pops in).
 */
public final class SwitchView extends View {

    static final int ACCENT = 0xFF0A84FF;

    private final float d;
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();
    private final Path tick = new Path();
    private float t;                       // knob position, 0 = off, 1 = on (the overshoot may pass 1 briefly)
    private boolean on, indicatorHidden, check;
    private int accent = ACCENT;
    private ValueAnimator anim;
    private LinearGradient shader;
    private float shaderLeft = Float.NaN;

    public SwitchView(Context c) {
        this(c, null);
    }

    public SwitchView(Context c, AttributeSet a) {
        super(c, a);
        d = c.getResources().getDisplayMetrics().density;
        setFocusable(false);
        setClickable(false);
    }

    void setAccent(int color) {
        accent = color;
        shader = null;
        invalidate();
    }

    /** For rows that only show a value (not on/off): the indicator is gone for good. */
    void setIndicatorHidden(boolean hidden) {
        indicatorHidden = hidden;
        if (hidden) super.setVisibility(GONE);
    }

    /** "Selected" marker instead of a switch. VISIBLE shows it (with a pop), GONE hides it. */
    void setCheckStyle(boolean checkStyle) {
        check = checkStyle;
    }

    boolean isOn() {
        return on;
    }

    /** Starts off, then slides on after a short delay (used when the control center opens). */
    void revealOn(long delayMs) {
        cancelAnim();
        on = false;
        t = 0f;
        invalidate();
        postDelayed(() -> setOn(true, true), delayMs);
    }

    @Override
    public void setVisibility(int v) {
        if (indicatorHidden) {
            super.setVisibility(GONE);
            return;
        }
        if (check) {
            boolean show = v == VISIBLE;
            super.setVisibility(show ? VISIBLE : GONE);
            if (show) {
                setOn(true, true);
            } else {
                cancelAnim();
                on = false;
                t = 0f;
            }
            return;
        }
        super.setVisibility(VISIBLE);       // an "off" switch is still drawn
        setOn(v == VISIBLE, true);
    }

    void setOn(boolean next, boolean animate) {
        if (on == next && anim == null) return;
        on = next;
        float target = next ? 1f : 0f;
        cancelAnim();
        if (!animate || !isAttachedToWindow() || getWindowVisibility() != VISIBLE) {
            t = target;
            invalidate();
            return;
        }
        anim = ValueAnimator.ofFloat(t, target);
        anim.setDuration(next ? 300 : 220);
        anim.setInterpolator(next ? new OvershootInterpolator(1.5f) : new DecelerateInterpolator(1.6f));
        anim.addUpdateListener(an -> {
            t = (Float) an.getAnimatedValue();
            invalidate();
        });
        anim.start();
    }

    private void cancelAnim() {
        if (anim != null) {
            anim.cancel();
            anim = null;
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        cancelAnim();
        t = on ? 1f : 0f;
    }

    @Override
    protected void onMeasure(int w, int h) {
        setMeasuredDimension(resolveSize((int) (40 * d), w), resolveSize((int) (26 * d), h));
    }

    private static int lighten(int c, float f) {
        return Color.rgb(Math.round(Color.red(c) + (255 - Color.red(c)) * f),
                Math.round(Color.green(c) + (255 - Color.green(c)) * f),
                Math.round(Color.blue(c) + (255 - Color.blue(c)) * f));
    }

    private static int lerp(int a, int b, float f) {
        return Color.argb(Math.round(Color.alpha(a) + (Color.alpha(b) - Color.alpha(a)) * f),
                Math.round(Color.red(a) + (Color.red(b) - Color.red(a)) * f),
                Math.round(Color.green(a) + (Color.green(b) - Color.green(a)) * f),
                Math.round(Color.blue(a) + (Color.blue(b) - Color.blue(a)) * f));
    }

    private static int withAlpha(int c, int a) {
        return (Math.max(0, Math.min(255, a)) << 24) | (c & 0xFFFFFF);
    }

    @Override
    protected void onDraw(Canvas c) {
        float cx = getWidth() / 2f, cy = getHeight() / 2f;
        float ct = Math.max(0f, Math.min(1f, t));
        if (check) {
            drawCheck(c, cx, cy, ct);
            return;
        }
        float tw = 34 * d, th = 20 * d;
        float left = cx - tw / 2f, top = cy - th / 2f;

        // soft glow of the accent colour around an "on" track
        p.setShader(null);
        p.setStyle(Paint.Style.FILL);
        if (ct > 0.02f) {
            for (int i = 3; i >= 1; i--) {
                p.setColor(withAlpha(accent, Math.round(0x2E * ct / i)));
                r.set(left - i * d, top - i * d, left + tw + i * d, top + th + i * d);
                c.drawRoundRect(r, th / 2f + i * d, th / 2f + i * d, p);
            }
        }
        // glass track (fades out as the accent fill fades in)
        r.set(left, top, left + tw, top + th);
        p.setColor(withAlpha(0xFFFFFF, Math.round(0x2A * (1f - ct))));
        c.drawRoundRect(r, th / 2f, th / 2f, p);
        if (ct > 0.01f) {
            if (shader == null || shaderLeft != left) {
                shader = new LinearGradient(left, 0, left + tw, 0, accent, lighten(accent, 0.38f), Shader.TileMode.CLAMP);
                shaderLeft = left;
            }
            p.setShader(shader);
            p.setAlpha(Math.round(255 * ct));
            c.drawRoundRect(r, th / 2f, th / 2f, p);
            p.setShader(null);
        }
        // hairline edge
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(Math.max(1f, d));
        p.setColor(lerp(0x45FFFFFF, withAlpha(lighten(accent, 0.55f), 0xB0), ct));
        r.inset(d / 2f, d / 2f);
        c.drawRoundRect(r, th / 2f, th / 2f, p);

        // knob: grey when off, dark with an accent core when on
        float kr = (6.4f + 1.1f * ct) * d;
        float x0 = left + th / 2f, x1 = left + tw - th / 2f;
        float kx = x0 + (x1 - x0) * t;
        p.setStyle(Paint.Style.FILL);
        p.setColor(lerp(0xFF8D8D97, 0xFF0C0C10, ct));
        c.drawCircle(kx, cy, kr, p);
        if (ct > 0.05f) {
            p.setColor(withAlpha(lighten(accent, 0.25f), Math.round(255 * ct)));
            c.drawCircle(kx, cy, 2.3f * d * ct, p);
        }
    }

    private void drawCheck(Canvas c, float cx, float cy, float ct) {
        if (ct <= 0.01f) return;
        float s = 0.55f + 0.45f * t;                      // pops in
        float rad = 9.5f * d * s;
        p.setShader(null);
        p.setStyle(Paint.Style.FILL);
        p.setColor(withAlpha(accent, Math.round(0x38 * ct)));
        c.drawCircle(cx, cy, rad + 2.5f * d * s, p);
        p.setShader(new LinearGradient(cx - rad, cy - rad, cx + rad, cy + rad, accent, lighten(accent, 0.38f),
                Shader.TileMode.CLAMP));
        p.setAlpha(Math.round(255 * ct));
        c.drawCircle(cx, cy, rad, p);
        p.setShader(null);
        tick.reset();
        tick.moveTo(cx - 4f * d * s, cy + 0.4f * d * s);
        tick.lineTo(cx - 1.2f * d * s, cy + 3.2f * d * s);
        tick.lineTo(cx + 4.2f * d * s, cy - 3.2f * d * s);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(2.1f * d * s);
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setStrokeJoin(Paint.Join.ROUND);
        p.setColor(withAlpha(0x0C0C10, Math.round(255 * ct)));
        c.drawPath(tick, p);
        p.setStrokeCap(Paint.Cap.BUTT);
        p.setStrokeJoin(Paint.Join.MITER);
    }
}
