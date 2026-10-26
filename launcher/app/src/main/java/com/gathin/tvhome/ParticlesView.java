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

    static final int OFF = 0, STARS = 1, SNOW = 2, FIREFLIES = 3, RAIN = 4, BUBBLES = 5, EMBERS = 6;
    static final int COUNT = 7;

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
}
