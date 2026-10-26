package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.View;

import java.util.Random;

/** Slow ambient particles over the wallpaper (stars, snow, fireflies, rain, bubbles, embers), tinted by the glow. */
public class ParticlesView extends View {

    static final int OFF = 0, STARS = 1, SNOW = 2, FIREFLIES = 3, RAIN = 4, BUBBLES = 5, EMBERS = 6, BATS = 7, WEB = 8;
    static final int COUNT = 9;

    private static final int N = 90;
    private final float[] x = new float[N], y = new float[N], v = new float[N], sz = new float[N], ph = new float[N];
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float d;
    private int mode;
    private int tint = 0xFFFFFFFF;
    private long last;
    private float time;

    public ParticlesView(Context c, AttributeSet a) {
        super(c, a);
        d = c.getResources().getDisplayMetrics().density;
        seed();
    }

    private void seed() {
        Random r = new Random(7);
        for (int i = 0; i < N; i++) {
            x[i] = r.nextFloat();
            y[i] = r.nextFloat();
            v[i] = 0.4f + r.nextFloat() * 0.9f;
            sz[i] = 0.5f + r.nextFloat();
            ph[i] = r.nextFloat() * 6.2832f;
        }
    }

    void setMode(int m) {
        mode = m;
        setVisibility(m == OFF ? GONE : VISIBLE);
        last = 0;
        invalidate();
    }

    void setTint(int color) {
        // a lighter version of the glow colour keeps the particles visible on dark wallpapers
        int r = (Color_r(color) + 255 * 2) / 3, g = (Color_g(color) + 255 * 2) / 3, b = (Color_b(color) + 255 * 2) / 3;
        tint = 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    private static int Color_r(int c) { return (c >> 16) & 255; }
    private static int Color_g(int c) { return (c >> 8) & 255; }
    private static int Color_b(int c) { return c & 255; }

    private int count() {
        switch (mode) {
            case STARS: return 80;
            case SNOW: return 60;
            case FIREFLIES: return 26;
            case RAIN: return 80;
            case BUBBLES: return 26;
            case BATS: return 7;
            case WEB: return 1;
            default: return 48;
        }
    }

    @Override
    protected void onDraw(Canvas c) {
        int w = getWidth(), h = getHeight();
        if (mode == OFF || w == 0 || h == 0) return;
        long now = SystemClock.uptimeMillis();
        float dt = last == 0 ? 0.016f : Math.min(0.05f, (now - last) / 1000f);
        last = now;
        time += dt;
        int n = count();
        paint.setShader(null);
        for (int i = 0; i < n; i++) {
            switch (mode) {
                case STARS: {
                    x[i] -= dt * 0.004f * v[i];
                    if (x[i] < 0) x[i] += 1f;
                    float a = 0.25f + 0.75f * (0.5f + 0.5f * (float) Math.sin(time * (0.8f + v[i]) + ph[i]));
                    paint.setStyle(Paint.Style.FILL);
                    paint.setColor(tint);
                    paint.setAlpha((int) (200 * a));
                    c.drawCircle(x[i] * w, y[i] * h * 0.85f, d * (0.8f + 1.4f * sz[i] * a), paint);
                    break;
                }
                case SNOW: {
                    y[i] += dt * 0.05f * v[i];
                    x[i] += dt * 0.008f * (float) Math.sin(time * 0.7f + ph[i]);
                    if (y[i] > 1.02f) { y[i] = -0.02f; x[i] = (x[i] + 1f) % 1f; }
                    paint.setStyle(Paint.Style.FILL);
                    paint.setColor(0xFFFFFFFF);
                    paint.setAlpha(190);
                    c.drawCircle(x[i] * w, y[i] * h, d * (1.4f + 2.2f * sz[i]), paint);
                    break;
                }
                case FIREFLIES: {
                    float px = x[i] + 0.04f * (float) Math.sin(time * 0.25f * v[i] + ph[i]);
                    float py = y[i] + 0.05f * (float) Math.cos(time * 0.2f * v[i] + ph[i] * 1.3f);
                    float a = 0.5f + 0.5f * (float) Math.sin(time * 1.1f * v[i] + ph[i]);
                    float r = d * (14f + 10f * sz[i]);
                    paint.setStyle(Paint.Style.FILL);
                    paint.setShader(new RadialGradient(px * w, py * h, r,
                            ((int) (170 * a) << 24) | (tint & 0xFFFFFF), tint & 0x00FFFFFF, Shader.TileMode.CLAMP));
                    c.drawCircle(px * w, py * h, r, paint);
                    paint.setShader(null);
                    paint.setColor(0xFFFFFFFF);
                    paint.setAlpha((int) (230 * a));
                    c.drawCircle(px * w, py * h, d * 1.6f, paint);
                    break;
                }
                case RAIN: {
                    y[i] += dt * 0.9f * v[i];
                    x[i] -= dt * 0.12f * v[i];
                    if (y[i] > 1.05f) { y[i] = -0.1f; x[i] = (x[i] + 1.1f) % 1.1f; }
                    if (x[i] < -0.1f) x[i] += 1.2f;
                    paint.setStyle(Paint.Style.STROKE);
                    paint.setStrokeWidth(d * 1.3f);
                    paint.setStrokeCap(Paint.Cap.ROUND);
                    paint.setColor(0xFFBFDBFE);
                    paint.setAlpha(110);
                    float len = d * (14f + 18f * sz[i]);
                    c.drawLine(x[i] * w, y[i] * h, x[i] * w - len * 0.18f, y[i] * h + len, paint);
                    break;
                }
                case BUBBLES: {
                    y[i] -= dt * 0.05f * v[i];
                    if (y[i] < -0.05f) { y[i] = 1.05f; }
                    float px = x[i] + 0.012f * (float) Math.sin(time * 0.6f + ph[i]);
                    paint.setStyle(Paint.Style.STROKE);
                    paint.setStrokeWidth(d * 1.4f);
                    paint.setColor(tint);
                    paint.setAlpha(120);
                    float r = d * (6f + 16f * sz[i]);
                    c.drawCircle(px * w, y[i] * h, r, paint);
                    paint.setStyle(Paint.Style.FILL);
                    paint.setAlpha(40);
                    c.drawCircle(px * w, y[i] * h, r, paint);
                    break;
                }
                case BATS: {
                    float speed = 0.018f + 0.02f * v[i];
                    float prog = (time * speed + y[i] * 3f) % 1f;            // 0..1 across the screen
                    boolean right = i % 2 == 0;
                    float px = (right ? prog : 1f - prog) * (w + 160 * d) - 80 * d;
                    float py = h * (0.08f + 0.5f * x[i]) + h * 0.04f * (float) Math.sin(time * 1.1f * v[i] + ph[i]);
                    float size = d * (11f + 15f * sz[i]);
                    float flap = (float) Math.sin(time * (7f + 3f * v[i]) + ph[i]);
                    drawBat(c, px, py, size, flap, right);
                    break;
                }
                case WEB: {
                    drawWeb(c, w, h);
                    break;
                }
                default: {   // embers
                    y[i] -= dt * 0.09f * v[i];
                    x[i] += dt * 0.012f * (float) Math.sin(time * 1.2f + ph[i]);
                    if (y[i] < -0.05f) { y[i] = 1.05f; x[i] = (x[i] * 7.31f) % 1f; }
                    float life = Math.max(0f, Math.min(1f, y[i]));
                    paint.setStyle(Paint.Style.FILL);
                    paint.setColor(0xFFFFA43A);
                    paint.setAlpha((int) (230 * life));
                    c.drawCircle(x[i] * w, y[i] * h, d * (1f + 1.6f * sz[i]) * (0.4f + 0.6f * life), paint);
                    break;
                }
            }
        }
        postInvalidateOnAnimation();
    }

    private final android.graphics.Path bat = new android.graphics.Path();

    /** A flapping bat silhouette; flap is -1..1 (wings up .. down). */
    private void drawBat(Canvas c, float x, float y, float s, float flap, boolean facingRight) {
        paint.setShader(null);
        c.save();
        c.translate(x, y);
        if (!facingRight) c.scale(-1f, 1f);
        float tip = -0.75f * s * flap;
        for (int side = 0; side < 2; side++) {
            c.save();
            if (side == 1) c.scale(-1f, 1f);
            bat.reset();
            bat.moveTo(0f, -0.1f * s);
            bat.cubicTo(0.5f * s, -0.9f * s + tip * 0.3f, 1.4f * s, -0.8f * s + tip * 0.8f, 2.2f * s, -0.2f * s + tip);
            bat.quadTo(1.85f * s, 0.05f * s + tip * 0.7f, 1.55f * s, 0.42f * s + tip * 0.5f);
            bat.quadTo(1.25f * s, 0.1f * s + tip * 0.3f, 0.95f * s, 0.5f * s + tip * 0.15f);
            bat.quadTo(0.6f * s, 0.18f * s, 0.3f * s, 0.55f * s);
            bat.lineTo(0f, 0.45f * s);
            bat.close();
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(0xFF0B0A12);
            paint.setAlpha(235);
            c.drawPath(bat, paint);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(d * 0.9f);
            paint.setColor(tint);
            paint.setAlpha(70);
            c.drawPath(bat, paint);
            c.restore();
        }
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0xFF0B0A12);
        paint.setAlpha(240);
        c.drawOval(-0.28f * s, -0.38f * s, 0.28f * s, 0.62f * s, paint);
        bat.reset();                                                   // ears
        bat.moveTo(-0.22f * s, -0.28f * s);
        bat.lineTo(-0.2f * s, -0.7f * s);
        bat.lineTo(-0.04f * s, -0.36f * s);
        bat.moveTo(0.22f * s, -0.28f * s);
        bat.lineTo(0.2f * s, -0.7f * s);
        bat.lineTo(0.04f * s, -0.36f * s);
        c.drawPath(bat, paint);
        paint.setColor(0xFFFF4D4D);                                    // eyes
        paint.setAlpha(230);
        c.drawCircle(-0.1f * s, -0.12f * s, s * 0.05f, paint);
        c.drawCircle(0.1f * s, -0.12f * s, s * 0.05f, paint);
        c.restore();
    }

    /** Spider webs in the top corners, a little spider that lowers itself on a thread, and a web shot now and then. */
    private void drawWeb(Canvas c, int w, int h) {
        float len = Math.min(w, h) * 0.34f;
        paint.setShader(null);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(d * 1.1f);
        paint.setColor(0xFFFFFFFF);
        for (int side = 0; side < 2; side++) {
            c.save();
            if (side == 1) {
                c.translate(w, 0f);
                c.scale(-1f, 1f);
            }
            int spokes = 6;
            float[] ax = new float[spokes], ay = new float[spokes];
            for (int k = 0; k < spokes; k++) {
                double a = Math.toRadians(4 + k * (82f / (spokes - 1)) + 1.2f * Math.sin(time * 0.6 + k));
                ax[k] = (float) Math.cos(a);
                ay[k] = (float) Math.sin(a);
                paint.setAlpha(70);
                c.drawLine(0f, 0f, ax[k] * len, ay[k] * len, paint);
            }
            for (int ring = 1; ring <= 5; ring++) {
                float r = len * ring / 5f;
                android.graphics.Path pth = bat;
                pth.reset();
                pth.moveTo(ax[0] * r, ay[0] * r);
                for (int k = 1; k < spokes; k++) {
                    float mx = (ax[k - 1] + ax[k]) * 0.5f * r * 0.9f, my = (ay[k - 1] + ay[k]) * 0.5f * r * 0.9f;   // sags to the corner
                    pth.quadTo(mx, my, ax[k] * r, ay[k] * r);
                }
                paint.setAlpha(55);
                c.drawPath(pth, paint);
            }
            c.restore();
        }
        // spider on a thread
        float sx = w * 0.14f;
        float drop = h * (0.14f + 0.07f * (float) Math.sin(time * 0.45f));
        paint.setAlpha(110);
        paint.setStrokeWidth(d);
        c.drawLine(sx, 0f, sx, drop, paint);
        float r = d * 7f;
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(d * 1.4f);
        paint.setColor(0xFF0B0A12);
        paint.setAlpha(255);
        for (int k = 0; k < 4; k++) {                                   // eight legs, wiggling
            float wob = (float) Math.sin(time * 5f + k) * d * 1.5f;
            for (int side = -1; side <= 1; side += 2) {
                float ly = drop - r * 0.4f + k * r * 0.45f;
                c.drawLine(sx, ly, sx + side * r * 1.1f, ly - r * 0.8f + wob, paint);
                c.drawLine(sx + side * r * 1.1f, ly - r * 0.8f + wob, sx + side * r * 1.9f, ly + r * 0.6f, paint);
            }
        }
        paint.setStyle(Paint.Style.FILL);
        c.drawCircle(sx, drop + r * 0.6f, r, paint);
        c.drawCircle(sx, drop - r * 0.5f, r * 0.62f, paint);
        paint.setColor(0xFFFF4D4D);
        c.drawCircle(sx - r * 0.2f, drop - r * 0.6f, d * 1.2f, paint);
        c.drawCircle(sx + r * 0.2f, drop - r * 0.6f, d * 1.2f, paint);
        // a web shot across the screen every few seconds
        float cycle = time % 7f;
        if (cycle < 1.1f) {
            float p = cycle / 1.1f;
            float x0 = w * 0.88f, y0 = 0f, x1 = w * 0.52f, y1 = h * 0.46f;
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(d * 1.6f);
            paint.setColor(0xFFFFFFFF);
            paint.setAlpha((int) (170 * (1f - p * p)));
            c.drawLine(x0, y0, x0 + (x1 - x0) * p, y0 + (y1 - y0) * p, paint);
            paint.setStyle(Paint.Style.FILL);
            c.drawCircle(x0 + (x1 - x0) * p, y0 + (y1 - y0) * p, d * 3f, paint);
        }
    }
}
