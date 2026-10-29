package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PathMeasure;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.View;

import java.util.Random;

/**
 * Spider-web effects drawn over the (standing still) wallpaper and its depth foreground (that PNG covers nearly the
 * whole picture, so anything under it would be hidden), under the app tiles and the cursor:
 * - moving along the dock: one of the three heroes shoots a web to the focused app (thread flies, sticks, wobbles,
 *   a small web bursts around the app) and a "spider-sense" tingle flashes at the hero's head;
 * - going into / out of All apps: a web wave runs over the screen and cobwebs grow into the corners;
 * - always: a cobweb in the top-left corner with a light running along it, a pulsing moon and twinkling stars.
 * Everything is time based (same speed at any frame rate), allocation free while drawing, and frames are only
 * requested while something moves.
 */
public class WebFxView extends View {

    // Where things are in the default wallpaper (fractions of the 2576 x 1449 picture).
    private static final float ART_W = 2576f, ART_H = 1449f;
    private static final float[][] EMIT = {{0.455f, 0.175f}, {0.563f, 0.178f}, {0.497f, 0.787f}};   // Spider-Man, Gwen, Miles: hand
    private static final float[][] HEAD = {{0.419f, 0.190f}, {0.568f, 0.235f}, {0.415f, 0.540f}};   // and head
    private static final float MOON_X = 0.579f, MOON_Y = 0.083f, MOON_R = 0.0179f;

    private static final long SHOOT_MS = 260L, SPLAT_MS = 520L, SENSE_MS = 420L, WAVE_MS = 650L, FADE_MS = 220L;
    private static final long SETTLE_MS = 1700L;                      // the thread wobbles this long after it lands
    private static final int STARS = 40;
    private static final int SPOKES = 8, WAVE_SPOKES = 14, SENSE_N = 7, RINGS = 5;
    private static final float[] STRAND_DEG = {4f, 26f, 48f, 68f, 86f};

    private static final class Tether {
        boolean alive, fading;
        float ex, ey, tx, ty, radius;
        long t0, fadeAt;
    }

    private final float d;
    private final Paint glow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint core = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint thin = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint web = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint star = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint halo = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final Path path = new Path(), seg = new Path(), ring = new Path();
    private final PathMeasure pm = new PathMeasure();
    private final float[] pos = new float[2];

    private final Tether cur = new Tether(), old = new Tether();
    private final float[] cos8 = new float[SPOKES], sin8 = new float[SPOKES], cosB = new float[SPOKES], sinB = new float[SPOKES];
    private final float[] cosW = new float[WAVE_SPOKES], sinW = new float[WAVE_SPOKES], cosWB = new float[WAVE_SPOKES], sinWB = new float[WAVE_SPOKES];
    private final float[] cosS = new float[SENSE_N], sinS = new float[SENSE_N];

    // cobweb (one corner, drawn mirrored into the others)
    private final Path strands = new Path();
    private final Path[] rings = new Path[RINGS];

    // sky
    private final float[] starNX = new float[STARS], starNY = new float[STARS], starSize = new float[STARS],
            starSpeed = new float[STARS], starPhase = new float[STARS];
    private final float[] starX = new float[STARS], starY = new float[STARS];
    private float moonX, moonY, haloR;

    private boolean enabled = true, heroArt;
    private boolean windowVisible = true;
    private float allTarget, allNow;
    private int nextHero;
    private long lastDraw;
    private long senseT0, waveT0;
    private float senseX, senseY;
    private float waveX, waveY;
    private boolean waveEnter;

    public WebFxView(Context c, AttributeSet a) {
        super(c, a);
        d = c.getResources().getDisplayMetrics().density;
        setWillNotDraw(false);
        setFocusable(false);
        setFocusableInTouchMode(false);
        setClickable(false);

        glow.setStyle(Paint.Style.STROKE);
        glow.setStrokeCap(Paint.Cap.ROUND);
        glow.setStrokeWidth(5f * d);
        glow.setColor(0xFFA9C7FF);
        core.setStyle(Paint.Style.STROKE);
        core.setStrokeCap(Paint.Cap.ROUND);
        core.setStrokeWidth(1.5f * d);
        core.setColor(0xFFFFFFFF);
        thin.setStyle(Paint.Style.STROKE);
        thin.setStrokeCap(Paint.Cap.ROUND);
        thin.setStrokeWidth(1.3f * d);
        thin.setColor(0xFFFFFFFF);
        dot.setStyle(Paint.Style.FILL);
        dot.setColor(0xFFFFFFFF);
        web.setStyle(Paint.Style.STROKE);
        web.setStrokeCap(Paint.Cap.ROUND);
        web.setStrokeWidth(1.1f * d);
        web.setColor(0xFFFFFFFF);
        star.setStyle(Paint.Style.FILL);
        star.setColor(0xFFFFFFFF);
        halo.setStyle(Paint.Style.FILL);

        for (int i = 0; i < SPOKES; i++) {
            double a1 = Math.toRadians(22.5 + i * 45.0);
            double a2 = Math.toRadians(22.5 + (i + 0.5) * 45.0);
            cos8[i] = (float) Math.cos(a1);
            sin8[i] = (float) Math.sin(a1);
            cosB[i] = (float) Math.cos(a2);
            sinB[i] = (float) Math.sin(a2);
        }
        for (int i = 0; i < WAVE_SPOKES; i++) {
            double a1 = Math.toRadians(8.0 + i * 360.0 / WAVE_SPOKES);
            double a2 = Math.toRadians(8.0 + (i + 0.5) * 360.0 / WAVE_SPOKES);
            cosW[i] = (float) Math.cos(a1);
            sinW[i] = (float) Math.sin(a1);
            cosWB[i] = (float) Math.cos(a2);
            sinWB[i] = (float) Math.sin(a2);
        }
        for (int i = 0; i < SENSE_N; i++) {
            double a1 = Math.toRadians(-170.0 + i * (160.0 / (SENSE_N - 1)));
            cosS[i] = (float) Math.cos(a1);
            sinS[i] = (float) Math.sin(a1);
        }
        for (int i = 0; i < RINGS; i++) rings[i] = new Path();

        Random rnd = new Random(7);
        for (int i = 0; i < STARS; i++) {
            float x, y;
            if (i < 28) {                                              // the sky left of the tall tower
                y = 0.03f + rnd.nextFloat() * 0.55f;
                float xmax = Math.max(0.04f, 0.28f * (1f - Math.max(0f, y - 0.155f) / 0.575f) - 0.02f);
                x = 0.01f + rnd.nextFloat() * (xmax - 0.01f);
            } else {                                                   // the sky right of the moon
                x = 0.60f + rnd.nextFloat() * 0.14f;
                y = 0.015f + rnd.nextFloat() * 0.11f;
            }
            starNX[i] = x;
            starNY[i] = y;
            starSize[i] = 0.7f + 1.1f * rnd.nextFloat();
            starSpeed[i] = 0.0011f + 0.0022f * rnd.nextFloat();
            starPhase[i] = rnd.nextFloat() * 6.28f;
        }
    }

    // ---- control from the launcher ----------------------------------------------------------------------------

    /** Master switch (Settings > Glow & effects). */
    void setFxEnabled(boolean on) {
        if (enabled == on) return;
        enabled = on;
        if (!on) clearAll();
        invalidate();
    }

    /** True while the built-in picture is the wallpaper: only then the heroes, moon and stars are at known places. */
    void setHeroArt(boolean on) {
        if (heroArt == on) return;
        heroArt = on;
        layoutArt();
        invalidate();
    }

    /** Dock state or All apps state. A change plays the web wave from (cx, cy); the dock threads let go. */
    void setAllMode(boolean all, float cx, float cy) {
        if (all == (allTarget >= 0.5f)) return;
        allTarget = all ? 1f : 0f;
        if (!enabled) return;
        long now = SystemClock.uptimeMillis();
        if (all) releaseTethers(now);
        waveT0 = now;
        waveX = cx;
        waveY = cy;
        waveEnter = all;
        invalidate();
    }

    /** After coming back to the home screen: no leftovers, the state is set without any animation. */
    void reset(boolean all) {
        clearAll();
        allTarget = allNow = all ? 1f : 0f;
        lastDraw = 0L;
        invalidate();
    }

    /** A hero shoots a web to the app at (tx, ty); radius is about half the size of the app tile. */
    void shoot(float tx, float ty, float radius) {
        if (!enabled || getWidth() == 0) return;
        long now = SystemClock.uptimeMillis();
        if (cur.alive) {
            old.alive = true;
            old.fading = true;
            old.fadeAt = now;
            old.ex = cur.ex;
            old.ey = cur.ey;
            old.tx = cur.tx;
            old.ty = cur.ty;
            old.radius = cur.radius;
            old.t0 = cur.t0;
        }
        if (heroArt) {
            int h = nextHero;
            nextHero = (nextHero + 1) % EMIT.length;
            cur.ex = artX(EMIT[h][0]);
            cur.ey = artY(EMIT[h][1]);
            senseX = artX(HEAD[h][0]);
            senseY = artY(HEAD[h][1]);
            senseT0 = now;
        } else {                                                       // no known picture: a thread drops from the top edge
            cur.ex = tx;
            cur.ey = -10f * d;
            senseT0 = 0L;
        }
        cur.tx = tx;
        cur.ty = ty;
        cur.radius = Math.max(8f * d, radius);
        cur.t0 = now;
        cur.alive = true;
        cur.fading = false;
        invalidate();
    }

    private void releaseTethers(long now) {
        if (cur.alive && !cur.fading) {
            cur.fading = true;
            cur.fadeAt = now;
        }
    }

    private void clearAll() {
        cur.alive = false;
        old.alive = false;
        senseT0 = 0L;
        waveT0 = 0L;
    }

    // ---- geometry ---------------------------------------------------------------------------------------------

    private float artScale() {
        return Math.max(getWidth() / ART_W, getHeight() / ART_H);
    }

    private float artX(float nx) {
        float s = artScale();
        return (getWidth() - ART_W * s) / 2f + nx * ART_W * s;
    }

    private float artY(float ny) {
        float s = artScale();
        return (getHeight() - ART_H * s) / 2f + ny * ART_H * s;
    }

    private void layoutArt() {
        if (getWidth() == 0 || getHeight() == 0) return;
        for (int i = 0; i < STARS; i++) {
            starX[i] = artX(starNX[i]);
            starY[i] = artY(starNY[i]);
        }
        moonX = artX(MOON_X);
        moonY = artY(MOON_Y);
        haloR = MOON_R * ART_W * artScale() * 4.4f;
        halo.setShader(new RadialGradient(0f, 0f, haloR,
                new int[]{0x66FFFFFF, 0x26CFD8FF, 0x00FFFFFF}, new float[]{0f, 0.35f, 1f}, Shader.TileMode.CLAMP));
    }

    private void buildWeb(float r) {
        strands.reset();
        for (int j = 0; j < STRAND_DEG.length; j++) {
            double a = Math.toRadians(STRAND_DEG[j]);
            strands.moveTo(0f, 0f);
            strands.lineTo((float) (r * Math.cos(a)), (float) (r * Math.sin(a)));
        }
        for (int k = 0; k < RINGS; k++) {
            float rr = r * (k + 1) / RINGS;
            Path p = rings[k];
            p.reset();
            for (int j = 0; j + 1 < STRAND_DEG.length; j++) {
                double a1 = Math.toRadians(STRAND_DEG[j]), a2 = Math.toRadians(STRAND_DEG[j + 1]);
                double am = (a1 + a2) / 2.0;
                p.moveTo((float) (rr * Math.cos(a1)), (float) (rr * Math.sin(a1)));
                p.quadTo((float) (rr * 0.90 * Math.cos(am)), (float) (rr * 0.90 * Math.sin(am)),
                        (float) (rr * Math.cos(a2)), (float) (rr * Math.sin(a2)));
            }
        }
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        buildWeb(0.16f * w);
        layoutArt();
    }

    @Override
    protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        windowVisible = visibility == VISIBLE;
        if (windowVisible) {
            lastDraw = 0L;
            invalidate();
        }
    }

    // ---- drawing ----------------------------------------------------------------------------------------------

    @Override
    protected void onDraw(Canvas canvas) {
        int w = getWidth(), h = getHeight();
        if (!enabled || w <= 0 || h <= 0) return;
        long now = SystemClock.uptimeMillis();
        float dt = lastDraw == 0L ? 0.016f : Math.min(0.1f, (now - lastDraw) / 1000f);
        lastDraw = now;
        allNow += (allTarget - allNow) * (1f - (float) Math.exp(-dt / 0.12f));
        boolean easing = Math.abs(allTarget - allNow) > 0.004f;
        if (!easing) allNow = allTarget;
        float dockVis = 1f - allNow;
        boolean busy = easing;

        if (heroArt && dockVis > 0.01f) drawSky(canvas, now, dockVis);
        drawCobwebs(canvas, now, w, h);

        if (old.alive) busy |= drawTether(canvas, old, now, 1f);
        if (cur.alive) busy |= drawTether(canvas, cur, now, 1f);
        if (senseT0 != 0L) busy |= drawSense(canvas, now);
        if (waveT0 != 0L) busy |= drawWave(canvas, now, w, h);

        if (!windowVisible) return;
        if (busy) {
            postInvalidateOnAnimation();                                // 60 fps while something moves
        } else if (allNow < 0.02f) {
            postInvalidateDelayed(Perf.paused ? 300L : 33L);            // slow shimmer of the sky and the cobweb
        }
    }

    private void drawSky(Canvas c, long now, float vis) {
        float pulse = 0.92f + 0.08f * (float) Math.sin(now / 1100.0);
        halo.setAlpha((int) (255 * vis * (0.75f + 0.25f * pulse)));
        c.save();
        c.translate(moonX, moonY);
        c.scale(pulse, pulse);
        c.drawCircle(0f, 0f, haloR, halo);
        c.restore();
        for (int i = 0; i < STARS; i++) {
            float s = (float) Math.sin(now * starSpeed[i] + starPhase[i]);
            float a = 0.12f + 0.88f * s * s;
            star.setAlpha((int) (235 * a * vis));
            c.drawCircle(starX[i], starY[i], starSize[i] * d, star);
        }
    }

    private void drawCobwebs(Canvas c, long now, int w, int h) {
        float dockVis = 1f - allNow;
        drawCobweb(c, 0f, 0f, 1f, 1f, 0.30f + 0.15f * allNow, now, dockVis > 0.5f);
        if (allNow > 0.01f) {
            float a = 0.45f * allNow;
            drawCobweb(c, w, 0f, -1f, 1f, a, now, false);
            drawCobweb(c, 0f, h, 1f, -1f, a, now, false);
            drawCobweb(c, w, h, -1f, -1f, a, now, false);
        }
    }

    private void drawCobweb(Canvas c, float ox, float oy, float sx, float sy, float alpha, long now, boolean shimmer) {
        c.save();
        c.translate(ox, oy);
        c.scale(sx, sy);
        web.setAlpha((int) (255 * alpha * 0.8f));
        c.drawPath(strands, web);
        for (int k = 0; k < RINGS; k++) {
            float f = 0.85f;
            if (shimmer) {                                             // a light that runs out along the rings
                float s = 0.5f + 0.5f * (float) Math.sin(now / 750.0 - k * 0.9);
                f = 0.35f + 0.65f * s * s;
            }
            web.setAlpha((int) (255 * alpha * f));
            c.drawPath(rings[k], web);
        }
        c.restore();
    }

    /** One flying / hanging thread. Returns true while it still needs frames (flying, wobbling or fading). */
    private boolean drawTether(Canvas c, Tether t, long now, float mul) {
        float dtMs = now - t.t0;
        float p = Math.min(1f, dtMs / SHOOT_MS);
        float e = 1f - (1f - p) * (1f - p) * (1f - p);
        float since = Math.max(0f, dtMs - SHOOT_MS);
        if (t.fading) {
            mul *= 1f - (now - t.fadeAt) / (float) FADE_MS;
            if (mul <= 0f) {
                t.alive = false;
                return false;
            }
        }
        float dx = t.tx - t.ex, dy = t.ty - t.ey;
        float len = (float) Math.hypot(dx, dy);
        if (len < 1f) {
            t.alive = false;
            return false;
        }
        float nx = -dy / len, ny = dx / len;
        if (ny < 0f) {                                                 // the thread always sags (gravity)
            nx = -nx;
            ny = -ny;
        }
        float wob = (float) (Math.exp(-since / 340.0) * Math.sin(since / 55.0)) * 12f * d * (p >= 1f ? 1f : 0f);
        float sag = len * 0.09f + wob;
        float cx = (t.ex + t.tx) / 2f + nx * sag, cy = (t.ey + t.ty) / 2f + ny * sag;
        path.reset();
        path.moveTo(t.ex, t.ey);
        path.quadTo(cx, cy, t.tx, t.ty);
        Path drawn = path;
        boolean flying = e < 1f;
        if (flying) {
            pm.setPath(path, false);
            float total = pm.getLength();
            seg.reset();
            pm.getSegment(0f, total * e, seg, true);
            pm.getPosTan(total * e, pos, null);
            drawn = seg;
        }
        glow.setAlpha((int) (70 * mul));
        c.drawPath(drawn, glow);
        core.setAlpha((int) (235 * mul));
        c.drawPath(drawn, core);
        if (flying) {
            dot.setAlpha((int) (255 * mul));
            c.drawCircle(pos[0], pos[1], 3.2f * d, dot);
        } else {
            drawSplat(c, t, (long) since, mul);
        }
        return flying || t.fading || since < SETTLE_MS;
    }

    /** The small web that bursts around the app when the thread lands. */
    private void drawSplat(Canvas c, Tether t, long sinceArrive, float mul) {
        float p = sinceArrive / (float) SPLAT_MS;
        if (p >= 1f) return;
        float e = 1f - (1f - p) * (1f - p);
        float big = t.radius * 1.15f + 14f * d;
        float r0 = t.radius * 0.45f;
        float rMax = r0 + (big - r0) * e;
        thin.setAlpha((int) (230 * (1f - p) * mul));
        for (int i = 0; i < SPOKES; i++) {
            c.drawLine(t.tx + cos8[i] * r0, t.ty + sin8[i] * r0, t.tx + cos8[i] * rMax, t.ty + sin8[i] * rMax, thin);
        }
        for (int k = 0; k < 2; k++) {
            float rr = (0.55f + 0.45f * k) * rMax;
            ring.reset();
            for (int i = 0; i < SPOKES; i++) {
                int n = (i + 1) % SPOKES;
                if (i == 0) ring.moveTo(t.tx + cos8[0] * rr, t.ty + sin8[0] * rr);
                ring.quadTo(t.tx + cosB[i] * rr * 0.88f, t.ty + sinB[i] * rr * 0.88f,
                        t.tx + cos8[n] * rr, t.ty + sin8[n] * rr);
            }
            c.drawPath(ring, thin);
        }
    }

    /** The "spider-sense" tingle: short lines fan out above the hero's head and a soft flash on the mask. */
    private boolean drawSense(Canvas c, long now) {
        float p = (now - senseT0) / (float) SENSE_MS;
        if (p >= 1f) {
            senseT0 = 0L;
            return false;
        }
        float e = 1f - (1f - p) * (1f - p);
        float fade = 1f - p;
        dot.setAlpha((int) (90 * fade));
        c.drawCircle(senseX, senseY, (10f + 8f * e) * d, dot);
        thin.setAlpha((int) (255 * fade));
        float r0 = (9f + 5f * e) * d, r1 = (18f + 12f * e) * d;
        for (int i = 0; i < SENSE_N; i++) {
            c.drawLine(senseX + cosS[i] * r0, senseY + sinS[i] * r0, senseX + cosS[i] * r1, senseY + sinS[i] * r1, thin);
        }
        return true;
    }

    /** A web wave over the whole screen: expands from the app when All apps opens, shrinks back when it closes. */
    private boolean drawWave(Canvas c, long now, int w, int h) {
        float p = (now - waveT0) / (float) WAVE_MS;
        if (p >= 1f) {
            waveT0 = 0L;
            return false;
        }
        float e = 1f - (1f - p) * (1f - p) * (1f - p);
        float maxR = (float) Math.hypot(w, h) * 0.9f;
        float rad = waveEnter ? maxR * e : maxR * (1f - e);
        float a = waveEnter ? (1f - p) : (float) Math.sin(Math.PI * p);
        if (rad < 2f) return true;
        thin.setAlpha((int) (150 * a));
        float inner = rad * 0.08f;
        for (int i = 0; i < WAVE_SPOKES; i++) {
            c.drawLine(waveX + cosW[i] * inner, waveY + sinW[i] * inner, waveX + cosW[i] * rad, waveY + sinW[i] * rad, thin);
        }
        for (int k = 0; k < 3; k++) {
            float rr = rad * (0.38f + 0.31f * k);
            ring.reset();
            for (int i = 0; i < WAVE_SPOKES; i++) {
                int n = (i + 1) % WAVE_SPOKES;
                if (i == 0) ring.moveTo(waveX + cosW[0] * rr, waveY + sinW[0] * rr);
                ring.quadTo(waveX + cosWB[i] * rr * 0.93f, waveY + sinWB[i] * rr * 0.93f,
                        waveX + cosW[n] * rr, waveY + sinW[n] * rr);
            }
            c.drawPath(ring, thin);
        }
        return true;
    }
}
