package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.View;

/**
 * Screensaver background: a deep night gradient, big soft colour clouds drifting slowly, wavy aurora
 * ribbons and twinkling stars. The colours travel through a palette (the album colours while Spotify plays).
 * Cheap on purpose: a handful of gradients, redrawn about 30 times a second.
 */
public class AuroraView extends View {

    static final int[] DEFAULT_PALETTE = {0xFF2F6BFF, 0xFF12C2B0, 0xFF8B45FF, 0xFFE5559A, 0xFFF08A24};

    private static final int BLOBS = 4;
    private static final int RIBBONS = 3;
    private static final int STARS = 64;
    private static final int STEPS = 28;

    private final Paint bg = new Paint();
    private final Paint blob = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ribbon = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint star = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final RadialGradient[] blobShader = new RadialGradient[BLOBS];
    private final LinearGradient[] ribbonShader = new LinearGradient[RIBBONS];
    private final float[] sx = new float[STARS], sy = new float[STARS], sr = new float[STARS],
            sp = new float[STARS], sw = new float[STARS];
    private final long start = SystemClock.uptimeMillis();

    private int[] palette = DEFAULT_PALETTE;
    private int[] prevPalette = DEFAULT_PALETTE;
    private long paletteChangedAt = -100000;
    private int frame;
    private int lastW, lastH;

    public AuroraView(Context c) {
        this(c, null);
    }

    public AuroraView(Context c, AttributeSet a) {
        super(c, a);
        blob.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.SCREEN));
        ribbon.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.SCREEN));
        java.util.Random r = new java.util.Random(7);
        for (int i = 0; i < STARS; i++) {
            sx[i] = r.nextFloat();
            sy[i] = r.nextFloat();
            sr[i] = 0.6f + r.nextFloat() * 1.4f;
            sp[i] = 0.4f + r.nextFloat() * 1.2f;
            sw[i] = r.nextFloat() * 6.28f;
        }
    }

    /** Colours to drift through; the change fades in over a few seconds. */
    void setPalette(int[] colors) {
        if (colors == null || colors.length == 0) colors = DEFAULT_PALETTE;
        if (java.util.Arrays.equals(colors, palette)) return;
        prevPalette = palette;
        palette = colors;
        paletteChangedAt = SystemClock.uptimeMillis();
    }

    private static int lerp(int a, int b, float t) {
        return Color.rgb(
                (int) (Color.red(a) + (Color.red(b) - Color.red(a)) * t),
                (int) (Color.green(a) + (Color.green(b) - Color.green(a)) * t),
                (int) (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t));
    }

    /** Position along the palette, 0..1 wraps around. */
    private static int along(int[] p, float pos) {
        pos -= (float) Math.floor(pos);
        float f = pos * p.length;
        int i = (int) f;
        float t = f - i;
        t = t * t * (3f - 2f * t);
        return lerp(p[i % p.length], p[(i + 1) % p.length], t);
    }

    private int colorAt(float pos, float mix) {
        int now = along(palette, pos);
        return mix >= 1f ? now : lerp(along(prevPalette, pos), now, mix);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0) return;
        float t = (SystemClock.uptimeMillis() - start) / 1000f;
        float mix = Math.min(1f, (SystemClock.uptimeMillis() - paletteChangedAt) / 4000f);

        if (w != lastW || h != lastH) {
            lastW = w;
            lastH = h;
            bg.setShader(new LinearGradient(0, 0, 0, h, 0xFF04050B, 0xFF0A0D1C, Shader.TileMode.CLAMP));
            frame = 0;
        }
        canvas.drawRect(0, 0, w, h, bg);

        boolean rebuild = frame % 4 == 0;   // shaders are rebuilt a few times a second, enough for slow colour drift
        float big = Math.max(w, h);

        for (int i = 0; i < BLOBS; i++) {
            if (rebuild || blobShader[i] == null) {
                int c = colorAt(t * 0.012f + i / (float) BLOBS, mix);
                int rgb = c & 0x00FFFFFF;
                blobShader[i] = new RadialGradient(0, 0, 1f,
                        new int[]{0x8C000000 | rgb, 0x3A000000 | rgb, rgb},
                        new float[]{0f, 0.55f, 1f}, Shader.TileMode.CLAMP);
            }
            float cx = w * (0.5f + 0.40f * (float) Math.sin(t * 0.065f * (1f + i * 0.31f) + i * 1.7f));
            float cy = h * (0.5f + 0.36f * (float) Math.cos(t * 0.050f * (1f + i * 0.23f) + i * 2.3f));
            float r = big * (0.36f + 0.08f * (float) Math.sin(t * 0.09f + i));
            blob.setShader(blobShader[i]);
            canvas.save();
            canvas.translate(cx, cy);
            canvas.scale(r, r);
            canvas.drawRect(-1f, -1f, 1f, 1f, blob);
            canvas.restore();
        }

        for (int i = 0; i < RIBBONS; i++) {
            float baseY = h * (0.58f + 0.12f * i);
            float amp = h * (0.05f + 0.015f * i);
            float band = h * 0.20f;
            if (rebuild || ribbonShader[i] == null) {
                int c = colorAt(t * 0.015f + 0.18f + i * 0.27f, mix);
                int rgb = c & 0x00FFFFFF;
                ribbonShader[i] = new LinearGradient(0, baseY - amp * 2f, 0, baseY + band + amp * 2f,
                        new int[]{rgb, 0x40000000 | rgb, rgb},
                        new float[]{0f, 0.4f, 1f}, Shader.TileMode.CLAMP);
            }
            ribbon.setShader(ribbonShader[i]);
            path.reset();
            for (int s = 0; s <= STEPS; s++) {
                float x = w * s / (float) STEPS;
                float y = baseY + amp * (float) Math.sin(s * 0.42f + t * (0.22f + 0.07f * i) + i * 2f)
                        + amp * 0.5f * (float) Math.sin(s * 0.9f - t * 0.17f + i);
                if (s == 0) path.moveTo(x, y);
                else path.lineTo(x, y);
            }
            for (int s = STEPS; s >= 0; s--) {
                float x = w * s / (float) STEPS;
                float y = baseY + band + amp * (float) Math.sin(s * 0.36f + t * (0.19f + 0.05f * i) + i * 1.3f);
                path.lineTo(x, y);
            }
            path.close();
            canvas.drawPath(path, ribbon);
        }

        for (int i = 0; i < STARS; i++) {
            float a = 0.5f + 0.5f * (float) Math.sin(t * sp[i] + sw[i]);
            star.setColor(((int) (40 + 150 * a) << 24) | 0x00FFFFFF);
            float y = (sy[i] - t * 0.004f * sp[i]) % 1f;
            if (y < 0) y += 1f;
            canvas.drawCircle(sx[i] * w, y * h, sr[i] * getResources().getDisplayMetrics().density * 0.9f, star);
        }

        frame++;
        postInvalidateDelayed(33);
    }
}
