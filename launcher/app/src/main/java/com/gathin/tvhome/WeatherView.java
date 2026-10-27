package com.gathin.tvhome;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.LinearInterpolator;

/**
 * Animated weather picture for the weather card: a turning sun by day, a rocking moon with twinkling stars at
 * night, drifting clouds, falling rain and snow, sliding fog and a flashing storm.
 */
public class WeatherView extends View {

    static final int CLEAR = 0, PARTLY = 1, CLOUDY = 2, FOG = 3, RAIN = 4, SNOW = 5, STORM = 6,
            MAINLY = 7, DRIZZLE = 8, HEAVY = 9, SHOWERS = 10, FREEZING = 11, HEAVY_SNOW = 12, GRAINS = 13, HAIL = 14;

    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final Path tmp = new Path();
    private final RectF rf = new RectF();
    private ValueAnimator anim;
    private float t;
    private int kind = CLOUDY;
    private boolean day = true;

    public WeatherView(Context c) {
        this(c, null);
    }

    public WeatherView(Context c, AttributeSet a) {
        super(c, a);
    }

    /** Sets what is drawn from the WMO weather code (Open-Meteo) and whether it is daytime. */
    void setCondition(int wmo, boolean isDay) {
        int k;
        if (wmo == 0) k = CLEAR;
        else if (wmo == 1) k = MAINLY;
        else if (wmo == 2) k = PARTLY;
        else if (wmo == 3) k = CLOUDY;
        else if (wmo == 45 || wmo == 48) k = FOG;
        else if (wmo >= 51 && wmo <= 55) k = DRIZZLE;
        else if (wmo == 56 || wmo == 57 || wmo == 66 || wmo == 67) k = FREEZING;
        else if (wmo == 65 || wmo == 82) k = HEAVY;
        else if (wmo == 80 || wmo == 81) k = SHOWERS;
        else if (wmo >= 60 && wmo < 70) k = RAIN;
        else if (wmo == 75 || wmo == 86) k = HEAVY_SNOW;
        else if (wmo == 77) k = GRAINS;
        else if (wmo == 71 || wmo == 73 || wmo == 85) k = SNOW;
        else if (wmo == 96 || wmo == 99) k = HAIL;
        else if (wmo >= 95) k = STORM;
        else k = CLOUDY;
        kind = k;
        day = isDay;
        invalidate();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (anim == null) {
            anim = ValueAnimator.ofFloat(0f, 1f);
            anim.setDuration(6000);
            anim.setRepeatCount(ValueAnimator.INFINITE);
            anim.setInterpolator(new LinearInterpolator());
            anim.addUpdateListener(a -> {
                if (Perf.paused || !isShown()) return;
                t = (Float) a.getAnimatedValue();
                invalidate();
            });
        }
        anim.start();
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        if (anim != null) {
            anim.cancel();
            anim = null;
        }
    }

    @Override
    protected void onDraw(Canvas c) {
        float s = Math.min(getWidth(), getHeight());
        if (s <= 0) return;
        c.save();
        c.translate((getWidth() - s) / 2f, (getHeight() - s) / 2f);
        final double TAU = Math.PI * 2;
        switch (kind) {
            case CLEAR:
                if (day) sun(c, s * .5f, s * .5f, s * .21f, s);
                else moon(c, s * .5f, s * .5f, s * .22f, s, true);
                break;
            case MAINLY:
                if (day) sun(c, s * .44f, s * .4f, s * .19f, s);
                else moon(c, s * .44f, s * .4f, s * .2f, s, true);
                cloud(c, s * (-.5f + 1.3f * t), s * .5f, s * .46f, day ? 0xF2FFFFFF : 0xE6C9D2E3);     // one small cloud drifts across
                break;
            case PARTLY:
                if (day) sun(c, s * .38f, s * .36f, s * .16f, s);
                else moon(c, s * .38f, s * .36f, s * .17f, s, false);
                cloud(c, s * (.26f + .03f * (float) Math.sin(t * TAU)), s * .36f, s * .68f, day ? 0xFFFFFFFF : 0xFFC9D2E3);
                break;
            case CLOUDY:
                cloud(c, s * (.34f - .04f * (float) Math.sin(t * TAU)), s * .12f, s * .56f, day ? 0xFFB6C0CF : 0xFF7F8AA0);
                cloud(c, s * (.08f + .04f * (float) Math.sin(t * TAU)), s * .34f, s * .76f, day ? 0xFFFFFFFF : 0xFFC9D2E3);
                break;
            case FOG:
                cloud(c, s * .14f, s * .08f, s * .72f, day ? 0xFFD3DAE6 : 0xFF8E98AC);
                for (int i = 0; i < 3; i++) {
                    float x = s * (.12f + .08f * (float) Math.sin(t * TAU + i * 1.7));
                    float y = s * (.58f + i * .14f);
                    p.setColor(0xB3E2E8F2);
                    rf.set(x, y, x + s * (.76f - i * .12f), y + s * .07f);
                    c.drawRoundRect(rf, s * .035f, s * .035f, p);
                }
                break;
            case RAIN:
                cloud(c, s * .1f, s * .08f, s * .8f, day ? 0xFFC4CDDA : 0xFF8590A6);
                rain(c, s, 5, false);
                break;
            case DRIZZLE:
                cloud(c, s * (.1f + .02f * (float) Math.sin(t * TAU)), s * .1f, s * .8f, day ? 0xFFD0D8E4 : 0xFF8F9AAF);
                drops(c, s, 8, 0.9f, .06f, .022f, 0xB38AD7FF, .02f);
                break;
            case HEAVY:
                cloud(c, s * .1f, s * .04f, s * .82f, day ? 0xFF9AA6B8 : 0xFF6C778C);
                drops(c, s, 9, 2.8f, .14f, .045f, 0xFF63C3F5, .07f);
                splashes(c, s);
                break;
            case SHOWERS:
                if (day) {
                    rainbow(c, s);
                    sun(c, s * .3f, s * .3f, s * .13f, s);
                } else {
                    moon(c, s * .3f, s * .3f, s * .14f, s, false);
                }
                cloud(c, s * (.2f + .03f * (float) Math.sin(t * TAU)), s * .26f, s * .72f, day ? 0xFFD3DAE6 : 0xFF8590A6);
                drops(c, s, 5, 1.7f, .1f, .04f, 0xFF7DD3FC, .04f, .62f);
                break;
            case FREEZING:
                cloud(c, s * .1f, s * .08f, s * .8f, day ? 0xFFC3D6EA : 0xFF7D93AF);
                drops(c, s, 4, 1.2f, .09f, .035f, 0xFFA5E1FF, .04f);
                crystals(c, s);
                break;
            case HEAVY_SNOW:
                cloud(c, s * .08f, s * .06f, s * .84f, day ? 0xFFC9D2E0 : 0xFF8590A6);
                flakes(c, s, 12, 1.5f, .036f, .06f);
                break;
            case GRAINS:
                cloud(c, s * .1f, s * .08f, s * .8f, day ? 0xFFDCE3EE : 0xFF9AA5BA);
                flakes(c, s, 16, 2.4f, .016f, .015f);
                break;
            case HAIL:
                cloud(c, s * .1f, s * .05f, s * .8f, 0xFF6F7A8F);
                hail(c, s);
                storm(c, s);
                break;
            case SNOW:
                cloud(c, s * .1f, s * .08f, s * .8f, day ? 0xFFDCE3EE : 0xFF9AA5BA);
                flakes(c, s, 7, 1.0f, .03f, .035f);
                break;
            default:
                cloud(c, s * .1f, s * .06f, s * .8f, 0xFF7C8699);
                rain(c, s, 3, true);
                storm(c, s);
                break;
        }
        c.restore();
    }

    private void sun(Canvas c, float cx, float cy, float r, float s) {
        double TAU = Math.PI * 2;
        float pulse = 0.5f + 0.5f * (float) Math.sin(t * TAU * 2);
        p.setShader(new RadialGradient(cx, cy, r * (2.1f + .25f * pulse), 0x66FFC83D, 0x00FFC83D, Shader.TileMode.CLAMP));
        c.drawCircle(cx, cy, r * 2.4f, p);
        p.setShader(null);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setStrokeWidth(s * .045f);
        p.setColor(0xFFFFD25E);
        float rot = t * 360f;
        for (int i = 0; i < 8; i++) {
            double a = Math.toRadians(rot + i * 45);
            float in = r * 1.38f, out = r * (1.62f + .22f * (i % 2 == 0 ? pulse : 1f - pulse));
            c.drawLine(cx + (float) Math.cos(a) * in, cy + (float) Math.sin(a) * in,
                    cx + (float) Math.cos(a) * out, cy + (float) Math.sin(a) * out, p);
        }
        p.setStyle(Paint.Style.FILL);
        p.setShader(new LinearGradient(cx, cy - r, cx, cy + r, 0xFFFFE27A, 0xFFFF9F1C, Shader.TileMode.CLAMP));
        c.drawCircle(cx, cy, r, p);
        p.setShader(null);
    }

    private void moon(Canvas c, float cx, float cy, float r, float s, boolean stars) {
        double TAU = Math.PI * 2;
        float pulse = 0.5f + 0.5f * (float) Math.sin(t * TAU);
        p.setShader(new RadialGradient(cx, cy, r * (1.9f + .2f * pulse), 0x55BFD4FF, 0x00BFD4FF, Shader.TileMode.CLAMP));
        c.drawCircle(cx, cy, r * 2.1f, p);
        p.setShader(null);
        c.save();
        c.rotate(-14f + 5f * (float) Math.sin(t * TAU), cx, cy);
        path.reset();
        path.addCircle(cx, cy, r, Path.Direction.CW);
        tmp.reset();
        tmp.addCircle(cx + r * .52f, cy - r * .16f, r * .86f, Path.Direction.CW);
        path.op(tmp, Path.Op.DIFFERENCE);
        p.setStyle(Paint.Style.FILL);
        p.setShader(new LinearGradient(cx - r, cy - r, cx + r, cy + r, 0xFFFFF6D6, 0xFFD9E2FF, Shader.TileMode.CLAMP));
        c.drawPath(path, p);
        p.setShader(null);
        c.restore();
        if (stars) {
            float[][] st = {{.14f, .2f, 0f}, {.8f, .16f, .3f}, {.84f, .6f, .6f}, {.2f, .78f, .8f}, {.62f, .86f, .45f}};
            for (float[] q : st) {
                float a = 0.35f + 0.65f * (0.5f + 0.5f * (float) Math.sin((t * 2 + q[2]) * TAU));
                sparkle(c, q[0] * s, q[1] * s, s * (.045f + .02f * a), a);
            }
        }
    }

    private void sparkle(Canvas c, float x, float y, float r, float alpha) {
        p.setColor(0xFFFFFFFF);
        p.setAlpha((int) (255 * alpha));
        path.reset();
        path.moveTo(x, y - r);
        path.quadTo(x, y, x + r, y);
        path.quadTo(x, y, x, y + r);
        path.quadTo(x, y, x - r, y);
        path.quadTo(x, y, x, y - r);
        c.drawPath(path, p);
        p.setAlpha(255);
    }

    private void cloud(Canvas c, float x, float y, float w, int color) {
        float h = w * .62f;
        p.setStyle(Paint.Style.FILL);
        p.setColor(color);
        rf.set(x, y + h * .42f, x + w, y + h);
        c.drawRoundRect(rf, h * .29f, h * .29f, p);
        c.drawCircle(x + w * .32f, y + h * .5f, h * .3f, p);
        c.drawCircle(x + w * .6f, y + h * .42f, h * .4f, p);
    }

    private void rain(Canvas c, float s, int n, boolean heavy) {
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setStrokeWidth(s * .04f);
        float speed = heavy ? 2.2f : 1.5f;
        for (int i = 0; i < n + 1; i++) {
            float ph = (t * speed + i * (1f / (n + 1)) * 1.3f + (i % 2) * .21f) % 1f;
            float x = s * (.24f + i * (.52f / n)) - ph * s * .06f;
            float y = s * (.5f + ph * .44f);
            p.setColor(0xFF7DD3FC);
            p.setAlpha((int) (255 * (1f - ph * ph)));
            c.drawLine(x, y, x - s * .04f, y + s * .1f, p);
        }
        p.setAlpha(255);
        p.setStyle(Paint.Style.FILL);
    }

    private void snow(Canvas c, float s) {
        p.setStyle(Paint.Style.FILL);
        for (int i = 0; i < 6; i++) {
            float ph = (t + i * .17f) % 1f;
            float x = s * (.2f + i * .12f) + s * .035f * (float) Math.sin((t * 2 + i * .4f) * Math.PI * 2);
            float y = s * (.5f + ph * .44f);
            p.setColor(0xFFFFFFFF);
            p.setAlpha((int) (255 * (1f - ph * ph)));
            c.drawCircle(x, y, s * (.03f + .01f * (i % 2)), p);
        }
        p.setAlpha(255);
    }

    private void storm(Canvas c, float s) {
        float ph = (t * 2f) % 1f;
        float flash = Math.max(Math.max(0f, 1f - Math.abs(ph - .12f) / .05f), .7f * Math.max(0f, 1f - Math.abs(ph - .24f) / .04f));
        if (flash > 0) {
            p.setShader(new RadialGradient(s * .5f, s * .45f, s * .6f, (int) (110 * flash) << 24 | 0xFFE680, 0x00FFE680, Shader.TileMode.CLAMP));
            c.drawCircle(s * .5f, s * .45f, s * .6f, p);
            p.setShader(null);
        }
        path.reset();
        path.moveTo(s * .54f, s * .42f);
        path.lineTo(s * .38f, s * .66f);
        path.lineTo(s * .5f, s * .66f);
        path.lineTo(s * .43f, s * .92f);
        path.lineTo(s * .66f, s * .58f);
        path.lineTo(s * .53f, s * .58f);
        path.close();
        p.setStyle(Paint.Style.FILL);
        p.setColor(0xFFFFD23F);
        p.setAlpha((int) (140 + 115 * flash));
        c.drawPath(path, p);
        p.setAlpha(255);
    }

    /** Slanted raindrops of any kind. top = where they start (share of the height). */
    private void drops(Canvas c, float s, int n, float speed, float len, float width, int color, float slant) {
        drops(c, s, n, speed, len, width, color, slant, .5f);
    }

    private void drops(Canvas c, float s, int n, float speed, float len, float width, int color, float slant, float top) {
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setStrokeWidth(s * width);
        p.setColor(color);
        int base = color >>> 24;
        float span = .96f - top;
        for (int i = 0; i < n; i++) {
            float ph = (t * speed + i * .6180339f) % 1f;                 // golden-ratio spacing: no two drops in step
            float x = s * (.2f + .6f * ((i * .37f + .11f) % 1f)) - ph * s * slant;
            float y = s * (top + ph * span);
            p.setAlpha((int) (base * (1f - ph * ph)));
            c.drawLine(x, y, x - s * slant * len / .1f * .35f, y + s * len, p);
        }
        p.setAlpha(255);
        p.setStyle(Paint.Style.FILL);
    }

    /** Little rings spreading where heavy rain hits the ground. */
    private void splashes(Canvas c, float s) {
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(s * .02f);
        for (int i = 0; i < 4; i++) {
            float ph = (t * 2.8f + i * .27f) % 1f;
            float x = s * (.22f + i * .18f);
            float w = s * (.02f + .07f * ph);
            p.setColor(0xFF9BDDFB);
            p.setAlpha((int) (200 * (1f - ph)));
            rf.set(x - w, s * .955f - w * .3f, x + w, s * .955f + w * .3f);
            c.drawOval(rf, p);
        }
        p.setAlpha(255);
        p.setStyle(Paint.Style.FILL);
    }

    /** Snowflakes (or grains) falling and swaying. */
    private void flakes(Canvas c, float s, int n, float speed, float size, float sway) {
        double TAU = Math.PI * 2;
        p.setStyle(Paint.Style.FILL);
        p.setColor(0xFFFFFFFF);
        for (int i = 0; i < n; i++) {
            float ph = (t * speed + i * .6180339f) % 1f;
            float x = s * (.16f + .68f * ((i * .41f + .07f) % 1f)) + s * sway * (float) Math.sin((t * 2 + i * .37f) * TAU);
            float y = s * (.5f + ph * .46f);
            float r = s * (size + size * .35f * (i % 3) / 2f);
            p.setAlpha((int) (255 * (1f - ph * ph)));
            c.drawCircle(x, y, r, p);
        }
        p.setAlpha(255);
    }

    /** Ice crystals: small diamonds that turn while they fall. */
    private void crystals(Canvas c, float s) {
        p.setStyle(Paint.Style.FILL);
        for (int i = 0; i < 4; i++) {
            float ph = (t * .9f + i * .31f) % 1f;
            float x = s * (.28f + i * .15f) + s * .02f * (float) Math.sin((t * 2 + i) * Math.PI * 2);
            float y = s * (.5f + ph * .44f);
            float r = s * .045f;
            c.save();
            c.rotate(t * 360f + i * 50f, x, y);
            path.reset();
            path.moveTo(x, y - r);
            path.lineTo(x + r * .6f, y);
            path.lineTo(x, y + r);
            path.lineTo(x - r * .6f, y);
            path.close();
            p.setColor(0xFFE8F6FF);
            p.setAlpha((int) (255 * (1f - ph * ph)));
            c.drawPath(path, p);
            c.restore();
        }
        p.setAlpha(255);
    }

    /** Hailstones: they fall fast, hit the ground and bounce. */
    private void hail(Canvas c, float s) {
        p.setStyle(Paint.Style.FILL);
        for (int i = 0; i < 6; i++) {
            float ph = (t * 2.2f + i * .19f) % 1f;
            float x = s * (.2f + i * .12f) - ph * s * .03f;
            float y;
            float a = 1f;
            if (ph < .78f) {
                y = s * (.5f + ph / .78f * .42f);
            } else {
                float b = (ph - .78f) / .22f;
                y = s * (.92f - .12f * (float) Math.sin(b * Math.PI));
                a = 1f - b * .6f;
            }
            p.setColor(0xFFEAF6FF);
            p.setAlpha((int) (255 * a));
            c.drawCircle(x, y, s * (.03f + .008f * (i % 2)), p);
        }
        p.setAlpha(255);
    }

    /** A rainbow that fades in and out behind the sun-and-rain scene. */
    private void rainbow(Canvas c, float s) {
        float a = .28f + .22f * (0.5f + 0.5f * (float) Math.sin(t * Math.PI * 2));
        int[] cols = {0xFFFF6B6B, 0xFFFFC857, 0xFF7BE495, 0xFF5BB8FF};
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(s * .035f);
        for (int i = 0; i < cols.length; i++) {
            float r = s * (.40f - i * .04f);
            rf.set(s * .6f - r, s * .78f - r, s * .6f + r, s * .78f + r);
            p.setColor(cols[i]);
            p.setAlpha((int) (255 * a));
            c.drawArc(rf, 200f, 140f, false, p);
        }
        p.setAlpha(255);
        p.setStyle(Paint.Style.FILL);
    }
}
