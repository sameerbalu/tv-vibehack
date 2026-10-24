package com.gathin.tvhome;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PathMeasure;
import android.graphics.PixelFormat;
import android.graphics.PorterDuff;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.LinearInterpolator;
import android.view.animation.OvershootInterpolator;

/**
 * Hand-drawn, animated icons for the Inputs and Bluetooth pills (24 x 24 design grid, stroke style).
 * They take their colour from the pill's text colour, so they flip to dark when the pill is focused.
 */
abstract class PillIcon extends Drawable {

    private final float density;
    private final ColorStateList colors;
    final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private int color;
    boolean focused;

    PillIcon(float density, ColorStateList colors) {
        this.density = density;
        this.colors = colors;
        color = colors.getColorForState(new int[0], colors.getDefaultColor());
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
        paint.setStrokeWidth(1.9f);
    }

    /** Sets the stroke colour with an extra opacity (0..1). */
    final void ink(float opacity) {
        paint.setColor(color);
        paint.setAlpha(Math.round(Color.alpha(color) * Math.max(0f, Math.min(1f, opacity))));
    }

    abstract void drawIcon(Canvas c);

    /** Pill gained or lost focus. */
    abstract void setFocused(boolean focused);

    /** Stops everything and shows the resting icon. */
    abstract void rest();

    @Override
    public void draw(Canvas canvas) {
        Rect b = getBounds();
        if (b.isEmpty()) return;
        canvas.save();
        canvas.translate(b.left, b.top);
        float s = b.width() / 24f;
        canvas.scale(s, s);
        drawIcon(canvas);
        canvas.restore();
    }

    @Override
    public int getIntrinsicWidth() {
        return Math.round(22 * density);
    }

    @Override
    public int getIntrinsicHeight() {
        return Math.round(22 * density);
    }

    @Override
    public boolean isStateful() {
        return true;
    }

    @Override
    protected boolean onStateChange(int[] state) {
        int c = colors.getColorForState(state, colors.getDefaultColor());
        if (c == color) return false;
        color = c;
        invalidateSelf();
        return true;
    }

    // The colour comes from the pill's text colour; ignore the tint the layout applies.
    @Override
    public void setTintList(ColorStateList tint) {
    }

    @Override
    public void setTintMode(PorterDuff.Mode tintMode) {
    }

    @Override
    public void setAlpha(int alpha) {
    }

    @Override
    public void setColorFilter(ColorFilter colorFilter) {
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }

    static float lerp(float a, float b, float t) {
        return a + (b - a) * t;
    }

    // ================================================================================================
    // Inputs: a screen with a signal arrow that glides in from the side, settles with a little
    // overshoot and makes the screen "pop". Loops softly while the pill has focus.
    // ================================================================================================
    static final class Input extends PillIcon {
        private float dx = 0f, alpha = 1f, pop = 1f;
        private ValueAnimator loop, settle;
        private final OvershootInterpolator over = new OvershootInterpolator(1.25f);

        Input(float density, ColorStateList colors) {
            super(density, colors);
        }

        @Override
        void drawIcon(Canvas c) {
            // screen
            c.save();
            c.scale(pop, pop, 12f, 10.25f);
            ink(1f);
            c.drawRoundRect(2.5f, 4f, 21.5f, 16.5f, 3.2f, 3.2f, paint);
            c.restore();
            // stand
            ink(1f);
            c.drawLine(12f, 16.5f, 12f, 20f, paint);
            c.drawLine(8.5f, 20f, 15.5f, 20f, paint);
            // signal arrow, only visible inside the screen
            if (alpha > 0.01f) {
                c.save();
                c.clipRect(4.2f, 5.7f, 19.8f, 14.8f);
                c.translate(dx, 0f);
                ink(alpha);
                paint.setStrokeWidth(2.1f);
                c.drawLine(6.5f, 10.25f, 16f, 10.25f, paint);
                c.drawLine(12.6f, 7f, 16f, 10.25f, paint);
                c.drawLine(12.6f, 13.5f, 16f, 10.25f, paint);
                paint.setStrokeWidth(1.9f);
                c.restore();
            }
        }

        @Override
        void setFocused(boolean f) {
            if (f == focused) return;
            focused = f;
            cancelAll();
            if (f) {
                loop = ValueAnimator.ofFloat(0f, 1f);
                loop.setDuration(2700);
                loop.setInterpolator(new LinearInterpolator());
                loop.setRepeatCount(ValueAnimator.INFINITE);
                loop.addUpdateListener(a -> {
                    step((Float) a.getAnimatedValue());
                    invalidateSelf();
                });
                loop.start();
            } else {
                final float d0 = dx, a0 = alpha, p0 = pop;
                settle = ValueAnimator.ofFloat(0f, 1f);
                settle.setDuration(180);
                settle.addUpdateListener(a -> {
                    float t = (Float) a.getAnimatedValue();
                    dx = lerp(d0, 0f, t);
                    alpha = lerp(a0, 1f, t);
                    pop = lerp(p0, 1f, t);
                    invalidateSelf();
                });
                settle.start();
            }
        }

        private void step(float t) {
            if (t < 0.24f) {                       // glide in
                float q = t / 0.24f;
                dx = -11f * (1f - over.getInterpolation(q));
                alpha = Math.min(1f, q * 5f);
            } else if (t < 0.80f) {                // hold
                dx = 0f;
                alpha = 1f;
            } else if (t < 0.92f) {                // drift out and fade
                float q = (t - 0.80f) / 0.12f;
                dx = 3f * q;
                alpha = 1f - q;
            } else {
                dx = 3f;
                alpha = 0f;
            }
            pop = (t >= 0.20f && t < 0.40f) ? 1f + 0.05f * (float) Math.sin(Math.PI * (t - 0.20f) / 0.20f) : 1f;
        }

        private void cancelAll() {
            if (loop != null) loop.cancel();
            if (settle != null) settle.cancel();
            loop = settle = null;
        }

        @Override
        void rest() {
            focused = false;
            cancelAll();
            dx = 0f;
            alpha = 1f;
            pop = 1f;
            invalidateSelf();
        }
    }

    // ================================================================================================
    // Bluetooth: the rune draws itself stroke by stroke, then radio waves ripple out on both sides.
    // ================================================================================================
    static final class Bluetooth extends PillIcon {
        private final Path rune = new Path();
        private final Path seg = new Path();
        private final PathMeasure measure;
        private final RectF oval = new RectF();
        private float draw = 1f;      // how much of the rune is drawn
        private float wave = 0f;      // ripple phase 0..1
        private float gain = 0f;      // ripple strength 0..1
        private ValueAnimator drawAnim, waveAnim, gainAnim;

        Bluetooth(float density, ColorStateList colors) {
            super(density, colors);
            rune.moveTo(6.5f, 7.5f);
            rune.lineTo(17.5f, 16.5f);
            rune.lineTo(12f, 21f);
            rune.lineTo(12f, 3f);
            rune.lineTo(17.5f, 7.5f);
            rune.lineTo(6.5f, 16.5f);
            measure = new PathMeasure(rune, false);
        }

        @Override
        void drawIcon(Canvas c) {
            if (gain > 0.01f) {
                paint.setStrokeWidth(1.5f);
                for (int k = 0; k < 2; k++) {
                    float q = (wave + k * 0.5f) % 1f;
                    float r = 8.6f + 3.2f * q;
                    float fade = (q < 0.18f ? q / 0.18f : 1f) * (1f - q);
                    ink(0.9f * fade * gain);
                    oval.set(12f - r, 12f - r, 12f + r, 12f + r);
                    c.drawArc(oval, -36f, 72f, false, paint);
                    c.drawArc(oval, 144f, 72f, false, paint);
                }
                paint.setStrokeWidth(1.9f);
            }
            ink(1f);
            if (draw >= 0.999f) {
                c.drawPath(rune, paint);
            } else if (draw > 0f) {
                seg.reset();
                measure.getSegment(0f, measure.getLength() * draw, seg, true);
                c.drawPath(seg, paint);
            }
        }

        @Override
        void setFocused(boolean f) {
            if (f == focused) return;
            focused = f;
            if (drawAnim != null) drawAnim.cancel();
            if (gainAnim != null) gainAnim.cancel();
            if (f) {
                drawAnim = ValueAnimator.ofFloat(0f, 1f);
                drawAnim.setDuration(520);
                drawAnim.setInterpolator(new DecelerateInterpolator(1.4f));
                drawAnim.addUpdateListener(a -> {
                    draw = (Float) a.getAnimatedValue();
                    invalidateSelf();
                });
                drawAnim.start();
                if (waveAnim == null) {
                    waveAnim = ValueAnimator.ofFloat(0f, 1f);
                    waveAnim.setDuration(1800);
                    waveAnim.setInterpolator(new LinearInterpolator());
                    waveAnim.setRepeatCount(ValueAnimator.INFINITE);
                    waveAnim.addUpdateListener(a -> {
                        wave = (Float) a.getAnimatedValue();
                        invalidateSelf();
                    });
                    waveAnim.start();
                }
                fadeGain(1f);
            } else {
                draw = 1f;
                fadeGain(0f);
            }
        }

        private void fadeGain(final float target) {
            gainAnim = ValueAnimator.ofFloat(gain, target);
            gainAnim.setDuration(target > 0f ? 260 : 200);
            gainAnim.addUpdateListener(a -> {
                gain = (Float) a.getAnimatedValue();
                invalidateSelf();
            });
            if (target == 0f) {
                gainAnim.addListener(new AnimatorListenerAdapter() {
                    private boolean cancelled;

                    @Override
                    public void onAnimationCancel(Animator animation) {
                        cancelled = true;
                    }

                    @Override
                    public void onAnimationEnd(Animator animation) {
                        if (!cancelled && !focused) stopWaves();
                    }
                });
            }
            gainAnim.start();
        }

        private void stopWaves() {
            if (waveAnim != null) waveAnim.cancel();
            waveAnim = null;
        }

        @Override
        void rest() {
            focused = false;
            if (drawAnim != null) drawAnim.cancel();
            if (gainAnim != null) gainAnim.cancel();
            stopWaves();
            draw = 1f;
            gain = 0f;
            wave = 0f;
            invalidateSelf();
        }
    }

    // ================================================================================================
    // Section pills (small, looping while visible): a screen with a play button and a progress bar that
    // fills, and a 2 x 2 grid whose tiles pop one after another.
    // ================================================================================================
    abstract static class Looping extends PillIcon {
        float t;                       // 0..1 position in the loop
        private ValueAnimator loop;
        private final long period;

        Looping(float density, ColorStateList colors, long period) {
            super(density, colors);
            this.period = period;
        }

        @Override
        void setFocused(boolean on) {          // "on" = the pill is on screen: run the loop
            if (on == focused) return;
            focused = on;
            if (loop != null) loop.cancel();
            loop = null;
            if (on) {
                loop = ValueAnimator.ofFloat(0f, 1f);
                loop.setDuration(period);
                loop.setInterpolator(new LinearInterpolator());
                loop.setRepeatCount(ValueAnimator.INFINITE);
                loop.addUpdateListener(a -> {
                    t = (Float) a.getAnimatedValue();
                    invalidateSelf();
                });
                loop.start();
            } else {
                t = 0f;
                invalidateSelf();
            }
        }

        @Override
        void rest() {
            focused = false;
            if (loop != null) loop.cancel();
            loop = null;
            t = 0f;
            invalidateSelf();
        }
    }

    static final class Film extends Looping {
        private final Path tri = new Path();

        Film(float density, ColorStateList colors) {
            super(density, colors, 3200);
        }

        @Override
        void drawIcon(Canvas c) {
            ink(1f);
            c.drawRoundRect(2.5f, 3f, 21.5f, 16f, 3.2f, 3.2f, paint);
            // play triangle with a small pop at the start of every loop
            float pop = t < 0.18f ? 1f + 0.16f * (float) Math.sin(Math.PI * t / 0.18f) : 1f;
            c.save();
            c.scale(pop, pop, 12.4f, 9.5f);
            tri.reset();
            tri.moveTo(10.4f, 6.4f);
            tri.lineTo(10.4f, 12.6f);
            tri.lineTo(15.4f, 9.5f);
            tri.close();
            ink(1f);
            c.drawPath(tri, paint);
            c.restore();
            // progress: faint track, bright part fills left to right
            ink(0.30f);
            c.drawLine(4f, 20f, 20f, 20f, paint);
            float q = Math.max(0f, Math.min(1f, (t - 0.12f) / 0.62f));
            float fade = t > 0.88f ? 1f - (t - 0.88f) / 0.12f : 1f;
            if (q > 0f) {
                ink(fade);
                c.drawLine(4f, 20f, 4f + 16f * q, 20f, paint);
            }
        }
    }

    static final class Apps extends Looping {
        private static final float[][] CELL = {{3.5f, 3.5f}, {13.5f, 3.5f}, {3.5f, 13.5f}, {13.5f, 13.5f}};
        private static final int[] ORDER = {0, 1, 3, 2};          // clockwise

        Apps(float density, ColorStateList colors) {
            super(density, colors, 3400);
        }

        @Override
        void drawIcon(Canvas c) {
            for (int n = 0; n < 4; n++) {
                int i = ORDER[n];
                float start = 0.04f + n * 0.12f;
                float q = (t - start) / 0.30f;
                float k = q > 0f && q < 1f ? (float) Math.sin(Math.PI * q) : 0f;
                float s = 1f + 0.20f * k;
                float cx = CELL[i][0] + 3.5f, cy = CELL[i][1] + 3.5f;
                c.save();
                c.scale(s, s, cx, cy);
                ink(1f);
                c.drawRoundRect(CELL[i][0], CELL[i][1], CELL[i][0] + 7f, CELL[i][1] + 7f, 2.2f, 2.2f, paint);
                if (k > 0f) {
                    paint.setStyle(Paint.Style.FILL);
                    ink(0.55f * k);
                    c.drawRoundRect(CELL[i][0], CELL[i][1], CELL[i][0] + 7f, CELL[i][1] + 7f, 2.2f, 2.2f, paint);
                    paint.setStyle(Paint.Style.STROKE);
                }
                c.restore();
            }
        }
    }
}
