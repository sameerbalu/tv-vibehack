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

    static final int OFF = 0, STARS = 1, SNOW = 2, FIREFLIES = 3, RAIN = 4, BUBBLES = 5, EMBERS = 6, CODE = 7, WARP = 8, SAKURA = 9, NEON = 10, STORM = 11,
            LANTERNS = 12, MAGIC = 13, SPORES = 14;
    static final int COUNT = 15;

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
        boltAge = 99f;
        nextBolt = time + 2f;
        for (int i = 0; i < N; i++) if (m == CODE) { y[i] = -x[i] * 40f; }
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
            case CODE: return Math.min(N, Math.max(8, (int) (getWidth() / (18 * d))));
            case WARP: return 90;
            case SAKURA: return 42;
            case NEON: return 70;
            case STORM: return 80;
            case LANTERNS: return 14;
            case MAGIC: return 60;
            case SPORES: return 55;
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
        if (Perf.paused) { last = 0; return; }
        beginFrame(c, w, h);
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
                case CODE: {
                    float rowH = d * 17f;
                    y[i] += dt * (7f + 9f * v[i]);                        // head position, in rows
                    float colX = (i + 0.5f) * (w / (float) n);
                    if ((y[i] - 14) * rowH > h) { y[i] = -(float) Math.random() * 18f; v[i] = 0.4f + (float) Math.random() * 0.9f; }
                    paint.setStyle(Paint.Style.FILL);
                    paint.setTextSize(d * 14f);
                    paint.setTextAlign(Paint.Align.CENTER);
                    int head = (int) Math.floor(y[i]);
                    for (int k = 0; k < 13; k++) {
                        int row = head - k;
                        float yy = row * rowH;
                        if (yy < -rowH || yy > h) continue;
                        float fade = 1f - k / 13f;
                        int idx = Math.abs(i * 31 + row * 17 + (int) (time * (k == 0 ? 9 : 2) + row % 3)) % CODE_CHARS.length;
                        if (k == 0) { paint.setColor(0xFFE6FFEF); paint.setAlpha(235); }
                        else { paint.setColor(0xFF2BFF7A); paint.setAlpha((int) (200 * fade * fade)); }
                        c.drawText(CODE_CHARS, idx, 1, colX, yy, paint);
                    }
                    break;
                }
                case WARP: {
                    float cx = w * 0.5f, cy = h * 0.46f;
                    float z = (time * (0.18f + 0.12f * v[i]) + ph[i] / 6.2832f) % 1f;       // 0 far .. 1 near
                    float rr = z * z * Math.max(w, h) * 0.75f;
                    float rr0 = Math.max(0f, (z - 0.06f)) * Math.max(0f, (z - 0.06f)) * Math.max(w, h) * 0.75f;
                    float ca = (float) Math.cos(ph[i] * 7.3f + x[i] * 40f), sa = (float) Math.sin(ph[i] * 7.3f + x[i] * 40f);
                    paint.setStyle(Paint.Style.STROKE);
                    paint.setStrokeCap(Paint.Cap.ROUND);
                    paint.setStrokeWidth(d * (0.6f + 1.8f * z));
                    paint.setColor(i % 4 == 0 ? tint : 0xFFDCE8FF);
                    paint.setAlpha((int) (40 + 215 * z));
                    c.drawLine(cx + ca * rr0, cy + sa * rr0, cx + ca * rr, cy + sa * rr, paint);
                    break;
                }
                case SAKURA: {
                    y[i] += dt * (0.045f + 0.03f * v[i]);
                    x[i] += dt * (0.012f + 0.01f * v[i]) + dt * 0.006f * (float) Math.sin(time * 0.9f + ph[i]);
                    if (y[i] > 1.05f) { y[i] = -0.05f; x[i] = (float) Math.random() * 1.1f - 0.2f; }
                    if (x[i] > 1.1f) x[i] -= 1.25f;
                    float px = x[i] * w, py = y[i] * h;
                    float size = d * (5f + 5f * sz[i]);
                    float rot = (time * 40f * v[i] + ph[i] * 57f);
                    float flip = 0.55f + 0.45f * (float) Math.sin(time * 1.6f * v[i] + ph[i]);   // tumbling
                    c.save();
                    c.translate(px, py);
                    c.rotate(rot);
                    c.scale(1f, flip);
                    paint.setStyle(Paint.Style.FILL);
                    paint.setColor(i % 3 == 0 ? 0xFFFFD1E3 : 0xFFFFA9C8);
                    paint.setAlpha(225);
                    petal.reset();
                    petal.moveTo(0f, -size);
                    petal.cubicTo(size * 0.95f, -size * 0.9f, size * 0.8f, size * 0.7f, 0f, size);
                    petal.cubicTo(-size * 0.8f, size * 0.7f, -size * 0.95f, -size * 0.9f, 0f, -size);
                    c.drawPath(petal, paint);
                    paint.setColor(0xFFFF7FA8);
                    paint.setAlpha(120);
                    c.drawCircle(0f, size * 0.55f, size * 0.12f, paint);
                    c.restore();
                    break;
                }
                case NEON: {
                    y[i] += dt * (1.1f + 0.9f * v[i]);
                    x[i] -= dt * 0.16f * v[i];
                    if (y[i] > 1.08f) { y[i] = -0.15f; x[i] = (float) Math.random() * 1.3f; }
                    int col = i % 3 == 0 ? 0xFF22E5FF : i % 3 == 1 ? 0xFFFF2BD6 : 0xFF7A5CFF;
                    float len = d * (22f + 30f * sz[i]);
                    float px = x[i] * w, py = y[i] * h;
                    paint.setStyle(Paint.Style.STROKE);
                    paint.setStrokeCap(Paint.Cap.ROUND);
                    paint.setColor(col);
                    paint.setStrokeWidth(d * 4.5f);
                    paint.setAlpha(34);                                             // soft glow under the line
                    c.drawLine(px, py, px - len * 0.2f, py + len, paint);
                    paint.setStrokeWidth(d * 1.5f);
                    paint.setAlpha(190);
                    c.drawLine(px, py, px - len * 0.2f, py + len, paint);
                    break;
                }
                case STORM: {
                    y[i] += dt * (1.5f + 0.9f * v[i]);
                    x[i] -= dt * 0.2f * v[i];
                    if (y[i] > 1.06f) { y[i] = -0.12f; x[i] = (float) Math.random() * 1.3f; }
                    float len = d * (18f + 22f * sz[i]);
                    paint.setStyle(Paint.Style.STROKE);
                    paint.setStrokeCap(Paint.Cap.ROUND);
                    paint.setStrokeWidth(d * 1.2f);
                    paint.setColor(0xFFB5CDEB);
                    paint.setAlpha(120);
                    c.drawLine(x[i] * w, y[i] * h, x[i] * w - len * 0.22f, y[i] * h + len, paint);
                    break;
                }
                case LANTERNS: {
                    y[i] -= dt * (0.018f + 0.02f * v[i]);
                    if (y[i] < -0.12f) { y[i] = 1.1f; x[i] = 0.05f + 0.9f * (float) Math.random(); }
                    float px = x[i] * w + w * 0.02f * (float) Math.sin(time * 0.35f * v[i] + ph[i]);
                    float py = y[i] * h;
                    float s = d * (11f + 12f * sz[i]);
                    float flick = 0.85f + 0.15f * (float) Math.sin(time * 5f * v[i] + ph[i] * 3f);
                    float depth = 0.55f + 0.45f * sz[i] / 1.5f;
                    paint.setStyle(Paint.Style.FILL);
                    paint.setShader(new RadialGradient(px, py, s * 3.2f, ((int) (110 * flick * depth) << 24) | 0xFFA030,
                            0x00FFA030, Shader.TileMode.CLAMP));
                    c.drawCircle(px, py, s * 3.2f, paint);
                    paint.setShader(null);
                    rect.set(px - s * 0.62f, py - s * 0.8f, px + s * 0.62f, py + s * 0.8f);
                    paint.setColor(0xFFFFB347);
                    paint.setAlpha((int) (235 * depth));
                    c.drawRoundRect(rect, s * 0.3f, s * 0.3f, paint);
                    rect.inset(s * 0.18f, s * 0.2f);
                    paint.setColor(0xFFFFE9A8);
                    paint.setAlpha((int) (230 * flick * depth));
                    c.drawRoundRect(rect, s * 0.2f, s * 0.2f, paint);
                    paint.setColor(0xFF6B3A12);
                    paint.setAlpha((int) (200 * depth));
                    c.drawRect(px - s * 0.4f, py - s * 0.88f, px + s * 0.4f, py - s * 0.78f, paint);
                    break;
                }
                case MAGIC: {
                    float life = (time * (0.07f + 0.05f * v[i]) + ph[i] / 6.2832f) % 1f;
                    float px = (x[i] + 0.06f * (float) Math.sin(life * 12f + ph[i])) * w;
                    float py = (1.05f - life * 1.15f) * h;
                    float tw = 0.5f + 0.5f * (float) Math.sin(time * 4f * v[i] + ph[i]);
                    float a = (float) Math.sin(Math.PI * life) * (0.45f + 0.55f * tw);
                    float s = d * (3f + 5f * sz[i]) * (0.6f + 0.6f * tw);
                    paint.setStyle(Paint.Style.FILL);
                    paint.setShader(new RadialGradient(px, py, s * 3f, ((int) (90 * a) << 24) | 0xFFD76A,
                            0x00FFD76A, Shader.TileMode.CLAMP));
                    c.drawCircle(px, py, s * 3f, paint);
                    paint.setShader(null);
                    paint.setColor(i % 5 == 0 ? tint : 0xFFFFE9A8);
                    paint.setAlpha((int) (255 * a));
                    petal.reset();
                    petal.moveTo(px, py - s);
                    petal.quadTo(px, py, px + s, py);
                    petal.quadTo(px, py, px, py + s);
                    petal.quadTo(px, py, px - s, py);
                    petal.quadTo(px, py, px, py - s);
                    c.drawPath(petal, paint);
                    break;
                }
                case SPORES: {
                    y[i] -= dt * (0.01f + 0.018f * v[i]);
                    x[i] += dt * 0.006f * (float) Math.sin(time * 0.4f + ph[i]);
                    if (y[i] < -0.05f) { y[i] = 1.05f; x[i] = (float) Math.random(); }
                    float a = 0.4f + 0.6f * (0.5f + 0.5f * (float) Math.sin(time * 0.8f * v[i] + ph[i]));
                    float s = d * (2f + 4f * sz[i]);
                    paint.setStyle(Paint.Style.FILL);
                    paint.setShader(new RadialGradient(x[i] * w, y[i] * h, s * 4f, ((int) (75 * a) << 24) | 0xFF3B30,
                            0x00FF3B30, Shader.TileMode.CLAMP));
                    c.drawCircle(x[i] * w, y[i] * h, s * 4f, paint);
                    paint.setShader(null);
                    paint.setColor(0xFFFFB0A8);
                    paint.setAlpha((int) (170 * a));
                    c.drawCircle(x[i] * w, y[i] * h, s * 0.5f, paint);
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
        endFrame(c, w, h);
        postInvalidateOnAnimation();
    }

    private static final char[] CODE_CHARS = ("0123456789ABCDEFXYZ<>+=*:|\u30A2\u30AB\u30B5\u30BF\u30CA\u30CF\u30DE\u30E4\u30E9\u30EF").toCharArray();
    private final android.graphics.Path petal = new android.graphics.Path();
    private final android.graphics.RectF rect = new android.graphics.RectF();

    // lightning (Thunderstorm): a flash of the whole screen and a jagged bolt every few seconds
    private float nextBolt = 3f, boltAge = 99f;
    private final float[] boltX = new float[16], boltY = new float[16];
    private final float[] branchX = new float[6], branchY = new float[6];

    private void beginFrame(Canvas c, int w, int h) {
        if (mode == STORM) {
            boltAge += 0.016f;
            if (time >= nextBolt) {
                nextBolt = time + 4f + (float) Math.random() * 6f;
                boltAge = 0f;
                float bx = w * (0.15f + 0.7f * (float) Math.random());
                float by = 0f;
                for (int k = 0; k < boltX.length; k++) {
                    boltX[k] = bx;
                    boltY[k] = by;
                    bx += (float) (Math.random() - 0.5) * w * 0.07f;
                    by += h * (0.55f / boltX.length) * (0.7f + 0.6f * (float) Math.random());
                }
                for (int k = 0; k < branchX.length; k += 2) {
                    int from = 4 + k;
                    branchX[k] = boltX[from];
                    branchY[k] = boltY[from];
                    branchX[k + 1] = boltX[from] + (k % 4 == 0 ? 1 : -1) * w * (0.04f + 0.05f * (float) Math.random());
                    branchY[k + 1] = boltY[from] + h * 0.1f;
                }
            }
        }
    }

    private void endFrame(Canvas c, int w, int h) {
        if (mode == STORM && boltAge < 1.2f) {
            float flash = Math.max(Math.max(0f, 1f - boltAge / 0.14f), 0.6f * Math.max(0f, 1f - Math.abs(boltAge - 0.22f) / 0.09f));
            paint.setShader(null);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(0xFFDCE8FF);
            paint.setAlpha((int) (70 * flash));
            c.drawRect(0, 0, w, h, paint);
            if (boltAge < 0.5f) {
                float fade = Math.min(1f, Math.max(0f, 1f - (boltAge - 0.12f) / 0.35f));
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeJoin(Paint.Join.ROUND);
                paint.setStrokeCap(Paint.Cap.ROUND);
                petal.reset();
                petal.moveTo(boltX[0], boltY[0]);
                for (int k = 1; k < boltX.length; k++) petal.lineTo(boltX[k], boltY[k]);
                for (int k = 0; k < branchX.length; k += 2) {
                    petal.moveTo(branchX[k], branchY[k]);
                    petal.lineTo(branchX[k + 1], branchY[k + 1]);
                }
                paint.setColor(0xFFB9D2FF);
                paint.setStrokeWidth(d * 9f);
                paint.setAlpha((int) (60 * fade));
                c.drawPath(petal, paint);
                paint.setColor(0xFFFFFFFF);
                paint.setStrokeWidth(d * 2.2f);
                paint.setAlpha((int) (240 * fade));
                c.drawPath(petal, paint);
            }
        }
        if (mode == SPORES) {                      // the whole room breathes a faint red now and then
            float pulse = Math.max(0f, (float) Math.sin(time * 0.5f) - 0.6f) / 0.4f;
            if (pulse > 0f) {
                paint.setShader(null);
                paint.setStyle(Paint.Style.FILL);
                paint.setColor(0xFFFF2A1A);
                paint.setAlpha((int) (22 * pulse));
                c.drawRect(0, 0, w, h, paint);
            }
        }
    }

}
