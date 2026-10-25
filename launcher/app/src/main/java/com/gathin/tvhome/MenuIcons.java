package com.gathin.tvhome;

import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;

/**
 * Animated icons for the menu rows (settings tab, hold-OK menu). Same hand-drawn stroke style as the
 * Continue watching / All apps pill icons: the icon stays exactly where it is and only its parts come alive
 * while the row is focused.
 */
final class MenuIcons {

    static final int WALLPAPER = 1, DEPTH = 2, EYE = 3, WIDGETS = 4, SPARKLE = 5, MOON = 6, BELL = 7, GEAR = 8,
            TRASH = 9, PLAY = 10, OPEN = 11, CLEAR = 12, GLOW = 13, BRUSH = 14, LEVELS = 15, TV = 16,
            BLUETOOTH = 17, SOURCE = 18;

    private MenuIcons() {
    }

    static PillIcon make(int kind, float density) {
        return make(kind, density, ColorStateList.valueOf(0xFFFFFFFF));
    }

    static PillIcon make(int kind, float density, ColorStateList white) {
        switch (kind) {
            case WALLPAPER: return new Wallpaper(density, white);
            case DEPTH: return new Depth(density, white);
            case EYE: return new Eye(density, white);
            case WIDGETS: return new Widgets(density, white);
            case SPARKLE: return new Sparkle(density, white);
            case MOON: return new Moon(density, white);
            case BELL: return new Bell(density, white);
            case GEAR: return new Gear(density, white);
            case TRASH: return new Trash(density, white);
            case PLAY: return new Play(density, white);
            case OPEN: return new Open(density, white);
            case CLEAR: return new Clear(density, white);
            case GLOW: return new Glow(density, white);
            case BRUSH: return new Brush(density, white);
            case LEVELS: return new Levels(density, white);
            case TV: return new Tv(density, white);
            case BLUETOOTH: return new PillIcon.Bluetooth(density, white);
            default: return new PillIcon.Input(density, white);
        }
    }

    /** Base: helpers for "something happens between start and start + len of the loop". */
    abstract static class Menu extends PillIcon.Looping {
        Menu(float density, ColorStateList colors, long period) {
            super(density, colors, period);
        }

        /** 0 -> 1 -> 0 while the loop is inside [start, start + len]. */
        float bump(float start, float len) {
            float q = (t - start) / len;
            return q > 0f && q < 1f ? (float) Math.sin(Math.PI * q) : 0f;
        }

        /** 0..1 progress inside [start, start + len]. */
        float prog(float start, float len) {
            return Math.max(0f, Math.min(1f, (t - start) / len));
        }

        static float ease(float x) {
            return x * x * (3f - 2f * x);
        }

        void fill() {
            paint.setStyle(Paint.Style.FILL);
        }

        void line() {
            paint.setStyle(Paint.Style.STROKE);
        }
    }

    // ---- picture: the sun glows --------------------------------------------------------------------
    static final class Wallpaper extends Menu {
        private final Path hills = new Path();

        Wallpaper(float d, ColorStateList c) {
            super(d, c, 2600);
            hills.moveTo(5.5f, 17f);
            hills.lineTo(10f, 11.5f);
            hills.lineTo(13.2f, 15.2f);
            hills.lineTo(15.4f, 12.8f);
            hills.lineTo(18.8f, 17f);
        }

        @Override
        void drawIcon(Canvas c) {
            ink(1f);
            c.drawRoundRect(3f, 4.5f, 21f, 19.5f, 3.2f, 3.2f, paint);
            c.drawPath(hills, paint);
            float k = bump(0.05f, 0.45f);
            float s = 1f + 0.28f * k;
            c.save();
            c.scale(s, s, 16.2f, 8.8f);
            ink(1f);
            c.drawCircle(16.2f, 8.8f, 1.7f, paint);
            c.restore();
            if (k > 0f) {
                fill();
                ink(0.5f * k);
                c.drawCircle(16.2f, 8.8f, 1.7f, paint);
                line();
            }
        }
    }

    // ---- layers: they breathe one after the other ----------------------------------------------------
    static final class Depth extends Menu {
        private final Path top = new Path();

        Depth(float d, ColorStateList c) {
            super(d, c, 2400);
            top.moveTo(12f, 3.5f);
            top.lineTo(21f, 8f);
            top.lineTo(12f, 12.5f);
            top.lineTo(3f, 8f);
            top.close();
        }

        @Override
        void drawIcon(Canvas c) {
            float k0 = bump(0.02f, 0.34f), k1 = bump(0.16f, 0.34f), k2 = bump(0.30f, 0.34f);
            c.save();
            float s = 1f + 0.12f * k0;
            c.scale(s, s, 12f, 8f);
            ink(1f);
            c.drawPath(top, paint);
            c.restore();
            ink(0.55f + 0.45f * k1);
            c.drawLine(3f, 12f, 12f, 16.5f, paint);
            c.drawLine(12f, 16.5f, 21f, 12f, paint);
            ink(0.40f + 0.60f * k2);
            c.drawLine(3f, 16f, 12f, 20.5f, paint);
            c.drawLine(12f, 20.5f, 21f, 16f, paint);
        }
    }

    // ---- eye: a slash draws across it, then lifts ----------------------------------------------------
    static final class Eye extends Menu {
        private final Path eye = new Path();

        Eye(float d, ColorStateList c) {
            super(d, c, 2800);
            eye.moveTo(2.5f, 12f);
            eye.quadTo(12f, 3f, 21.5f, 12f);
            eye.quadTo(12f, 21f, 2.5f, 12f);
            eye.close();
        }

        @Override
        void drawIcon(Canvas c) {
            float slash = ease(prog(0.10f, 0.28f)) - ease(prog(0.62f, 0.28f));
            ink(1f);
            c.drawPath(eye, paint);
            ink(1f - 0.55f * slash);
            c.drawCircle(12f, 12f, 2.9f, paint);
            if (slash > 0f) {
                ink(1f);
                c.drawLine(4.5f, 4.5f, 4.5f + 15f * slash, 4.5f + 15f * slash, paint);
            }
        }
    }

    // ---- widgets: the three blocks pop in turn -------------------------------------------------------
    static final class Widgets extends Menu {
        Widgets(float d, ColorStateList c) {
            super(d, c, 2800);
        }

        private void block(Canvas c, float l, float t0, float r, float b, float k) {
            float cx = (l + r) / 2f, cy = (t0 + b) / 2f, s = 1f + 0.16f * k;
            c.save();
            c.scale(s, s, cx, cy);
            ink(1f);
            c.drawRoundRect(l, t0, r, b, 2.2f, 2.2f, paint);
            if (k > 0f) {
                fill();
                ink(0.5f * k);
                c.drawRoundRect(l, t0, r, b, 2.2f, 2.2f, paint);
                line();
            }
            c.restore();
        }

        @Override
        void drawIcon(Canvas c) {
            block(c, 3.5f, 3.5f, 10.5f, 20.5f, bump(0.03f, 0.28f));
            block(c, 13.5f, 3.5f, 20.5f, 10.5f, bump(0.17f, 0.28f));
            block(c, 13.5f, 13.5f, 20.5f, 20.5f, bump(0.31f, 0.28f));
        }
    }

    // ---- sparkle: the big star swells, the small ones twinkle ------------------------------------------
    static final class Sparkle extends Menu {
        private final Path star = new Path();

        Sparkle(float d, ColorStateList c) {
            super(d, c, 2400);
            star.moveTo(11f, 4f);
            star.quadTo(11.8f, 11.2f, 19f, 12.5f);
            star.quadTo(11.8f, 13.8f, 11f, 21f);
            star.quadTo(10.2f, 13.8f, 3f, 12.5f);
            star.quadTo(10.2f, 11.2f, 11f, 4f);
            star.close();
        }

        @Override
        void drawIcon(Canvas c) {
            float s = 1f + 0.16f * bump(0f, 0.5f);
            c.save();
            c.scale(s, s, 11f, 12.5f);
            ink(1f);
            c.drawPath(star, paint);
            c.restore();
            float a = 0.35f + 0.65f * bump(0.12f, 0.4f);
            ink(a);
            c.drawLine(19.5f, 3f, 19.5f, 7f, paint);
            c.drawLine(17.5f, 5f, 21.5f, 5f, paint);
            float b = 0.35f + 0.65f * bump(0.42f, 0.4f);
            ink(b);
            c.drawLine(19f, 17.5f, 19f, 20.5f, paint);
            c.drawLine(17.5f, 19f, 20.5f, 19f, paint);
        }
    }

    // ---- moon and stars: the stars twinkle --------------------------------------------------------------
    static final class Moon extends Menu {
        private final Path crescent = new Path();

        Moon(float d, ColorStateList c) {
            super(d, c, 2600);
            Path outer = new Path();
            outer.addCircle(11.5f, 12.5f, 8.2f, Path.Direction.CW);
            Path inner = new Path();
            inner.addCircle(15.6f, 9.4f, 7f, Path.Direction.CW);
            crescent.op(outer, inner, Path.Op.DIFFERENCE);
        }

        @Override
        void drawIcon(Canvas c) {
            ink(1f);
            c.drawPath(crescent, paint);
            float a = 0.3f + 0.7f * bump(0.05f, 0.45f);
            ink(a);
            c.drawLine(18.5f, 3.5f, 18.5f, 6.5f, paint);
            c.drawLine(17f, 5f, 20f, 5f, paint);
            float b = 0.3f + 0.7f * bump(0.40f, 0.45f);
            ink(b);
            c.drawLine(20.5f, 10.5f, 20.5f, 12.5f, paint);
            c.drawLine(19.5f, 11.5f, 21.5f, 11.5f, paint);
        }
    }

    // ---- bell: the clapper swings, ripples leave on both sides ------------------------------------------
    static final class Bell extends Menu {
        private final Path bell = new Path();
        private final RectF oval = new RectF();

        Bell(float d, ColorStateList c) {
            super(d, c, 2200);
            bell.moveTo(6f, 16.5f);
            bell.lineTo(6f, 11f);
            bell.arcTo(new RectF(6f, 4.5f, 18f, 17.5f), 180f, 180f);
            bell.lineTo(18f, 16.5f);
            bell.lineTo(19.8f, 18.2f);
            bell.lineTo(4.2f, 18.2f);
            bell.close();
        }

        @Override
        void drawIcon(Canvas c) {
            ink(1f);
            c.drawPath(bell, paint);
            float swing = (float) Math.sin(t * 4 * Math.PI) * (t < 0.7f ? 1f : 0f);
            fill();
            ink(1f);
            c.drawCircle(12f + 1.9f * swing, 21f, 1.2f, paint);
            line();
            float q = prog(0.05f, 0.6f);
            if (q > 0f && q < 1f) {
                ink(1f - q);
                float r = 8.2f + 2.4f * q;
                oval.set(12f - r, 11f - r, 12f + r, 11f + r);
                c.drawArc(oval, 205f, 28f, false, paint);
                c.drawArc(oval, 307f, 28f, false, paint);
            }
        }
    }

    // ---- gear: turns one tooth, rests, turns again --------------------------------------------------------
    static final class Gear extends Menu {
        Gear(float d, ColorStateList c) {
            super(d, c, 2400);
        }

        @Override
        void drawIcon(Canvas c) {
            float angle = 45f * ease(prog(0.05f, 0.5f));      // 8 teeth: a 45 degree turn looks like the start
            c.save();
            c.rotate(angle, 12f, 12f);
            ink(1f);
            c.drawCircle(12f, 12f, 6f, paint);
            for (int i = 0; i < 8; i++) {
                c.save();
                c.rotate(i * 45f, 12f, 12f);
                c.drawLine(12f, 3.2f, 12f, 6f, paint);
                c.restore();
            }
            c.restore();
            ink(1f);
            c.drawCircle(12f, 12f, 2.3f, paint);
        }
    }

    // ---- bin: the lid lifts ---------------------------------------------------------------------------------
    static final class Trash extends Menu {
        private final Path body = new Path();

        Trash(float d, ColorStateList c) {
            super(d, c, 2400);
            body.moveTo(6.5f, 9f);
            body.lineTo(7.5f, 19.8f);
            body.quadTo(7.6f, 21f, 8.8f, 21f);
            body.lineTo(15.2f, 21f);
            body.quadTo(16.4f, 21f, 16.5f, 19.8f);
            body.lineTo(17.5f, 9f);
        }

        @Override
        void drawIcon(Canvas c) {
            ink(1f);
            c.drawPath(body, paint);
            ink(0.7f);
            c.drawLine(10.2f, 12f, 10.2f, 17.5f, paint);
            c.drawLine(13.8f, 12f, 13.8f, 17.5f, paint);
            float lift = bump(0.05f, 0.5f);
            c.save();
            c.rotate(-24f * lift, 18.5f, 7f);
            ink(1f);
            c.drawLine(4.5f, 7f, 19.5f, 7f, paint);
            c.drawLine(9.8f, 7f, 9.8f, 4.6f, paint);
            c.drawLine(9.8f, 4.6f, 14.2f, 4.6f, paint);
            c.drawLine(14.2f, 4.6f, 14.2f, 7f, paint);
            c.restore();
        }
    }

    // ---- play: a ring spreads from the button ---------------------------------------------------------------
    static final class Play extends Menu {
        private final Path tri = new Path();

        Play(float d, ColorStateList c) {
            super(d, c, 2200);
            tri.moveTo(10f, 8.2f);
            tri.lineTo(10f, 15.8f);
            tri.lineTo(16f, 12f);
            tri.close();
        }

        @Override
        void drawIcon(Canvas c) {
            ink(1f);
            c.drawCircle(12f, 12f, 8.6f, paint);
            float k = bump(0f, 0.3f);
            float s = 1f + 0.16f * k;
            c.save();
            c.scale(s, s, 13f, 12f);
            ink(1f);
            c.drawPath(tri, paint);
            c.restore();
            float q = prog(0.06f, 0.6f);
            if (q > 0f && q < 1f) {
                ink(0.7f * (1f - q));
                c.drawCircle(12f, 12f, 8.6f + 2.8f * q, paint);
            }
        }
    }

    // ---- open: the arrow pops out of the box -------------------------------------------------------------------
    static final class Open extends Menu {
        private final Path box = new Path();

        Open(float d, ColorStateList c) {
            super(d, c, 2400);
            box.moveTo(18f, 13.5f);
            box.lineTo(18f, 18.8f);
            box.quadTo(18f, 20.5f, 16.3f, 20.5f);
            box.lineTo(5.2f, 20.5f);
            box.quadTo(3.5f, 20.5f, 3.5f, 18.8f);
            box.lineTo(3.5f, 7.7f);
            box.quadTo(3.5f, 6f, 5.2f, 6f);
            box.lineTo(10.5f, 6f);
        }

        @Override
        void drawIcon(Canvas c) {
            ink(1f);
            c.drawPath(box, paint);
            float k = bump(0.05f, 0.4f);
            float s = 1f + 0.2f * k;
            c.save();
            c.scale(s, s, 15.5f, 8.5f);
            ink(1f);
            c.drawLine(11f, 13f, 20.2f, 3.8f, paint);
            c.drawLine(14.6f, 3.6f, 20.4f, 3.6f, paint);
            c.drawLine(20.4f, 3.6f, 20.4f, 9.4f, paint);
            c.restore();
        }
    }

    // ---- list: the lines wipe away one after the other, then come back -----------------------------------------
    static final class Clear extends Menu {
        Clear(float d, ColorStateList c) {
            super(d, c, 2800);
        }

        @Override
        void drawIcon(Canvas c) {
            float back = ease(prog(0.78f, 0.18f));
            float[] ys = {7f, 12f, 17f};
            float[] ends = {20f, 20f, 14f};
            for (int i = 0; i < 3; i++) {
                float gone = ease(prog(0.08f + i * 0.14f, 0.22f));
                float f = Math.max(0f, Math.min(1f, 1f - gone + back));
                float len = (ends[i] - 4f) * f;
                if (len > 0.4f) {
                    ink(1f);
                    c.drawLine(4f, ys[i], 4f + len, ys[i], paint);
                }
            }
        }
    }

    // ---- glow: rays breathe ----------------------------------------------------------------------------------
    static final class Glow extends Menu {
        Glow(float d, ColorStateList c) {
            super(d, c, 2200);
        }

        @Override
        void drawIcon(Canvas c) {
            float s = 1f + 0.12f * bump(0f, 0.5f);
            c.save();
            c.scale(s, s, 12f, 12f);
            ink(1f);
            c.drawCircle(12f, 12f, 4.2f, paint);
            c.restore();
            for (int i = 0; i < 8; i++) {
                float k = 0.5f + 0.5f * (float) Math.sin((t - i * 0.05f) * 2 * Math.PI);
                float from = 7.4f, to = 8.6f + 2.4f * k;
                c.save();
                c.rotate(i * 45f, 12f, 12f);
                ink(0.45f + 0.55f * k);
                c.drawLine(12f, 12f - from, 12f, 12f - to, paint);
                c.restore();
            }
        }
    }

    // ---- palette: the colour dots pop one after the other ---------------------------------------------------------
    static final class Brush extends Menu {
        private static final float[][] DOTS = {{8f, 10.2f}, {12f, 7.6f}, {16f, 10.2f}, {15.2f, 15f}};

        Brush(float d, ColorStateList c) {
            super(d, c, 2800);
        }

        @Override
        void drawIcon(Canvas c) {
            ink(1f);
            c.drawCircle(12f, 12f, 9.2f, paint);
            for (int i = 0; i < DOTS.length; i++) {
                float k = bump(0.03f + i * 0.15f, 0.3f);
                float s = 1f + 0.55f * k;
                c.save();
                c.scale(s, s, DOTS[i][0], DOTS[i][1]);
                fill();
                ink(0.7f + 0.3f * k);
                c.drawCircle(DOTS[i][0], DOTS[i][1], 1.4f, paint);
                line();
                c.restore();
            }
        }
    }

    // ---- sliders: the knobs slide along their tracks -----------------------------------------------------------------
    static final class Levels extends Menu {
        Levels(float d, ColorStateList c) {
            super(d, c, 2600);
        }

        @Override
        void drawIcon(Canvas c) {
            float[] xs = {6.5f, 12f, 17.5f};
            float[] base = {15f, 8.5f, 13f};
            for (int i = 0; i < 3; i++) {
                ink(0.5f);
                c.drawLine(xs[i], 4f, xs[i], 20f, paint);
                float y = base[i] + 3.2f * (float) Math.sin((t + i * 0.28f) * 2 * Math.PI);
                fill();
                ink(1f);
                c.drawCircle(xs[i], y, 2.3f, paint);
                line();
            }
        }
    }

    // ---- TV: the live dot pulses and the antenna sends a signal ----------------------------------------------------------
    static final class Tv extends Menu {
        Tv(float d, ColorStateList c) {
            super(d, c, 2200);
        }

        @Override
        void drawIcon(Canvas c) {
            ink(1f);
            c.drawRoundRect(3f, 8f, 21f, 20.5f, 2.8f, 2.8f, paint);
            c.drawLine(12f, 8f, 8.2f, 3.8f, paint);
            c.drawLine(12f, 8f, 15.8f, 3.8f, paint);
            float k = bump(0f, 0.5f);
            float s = 1f + 0.35f * k;
            c.save();
            c.scale(s, s, 12f, 14.2f);
            fill();
            ink(1f);
            c.drawCircle(12f, 14.2f, 2f, paint);
            line();
            c.restore();
            float q = prog(0.1f, 0.6f);
            if (q > 0f && q < 1f) {
                ink(0.6f * (1f - q));
                c.drawCircle(12f, 14.2f, 2f + 3.4f * q, paint);
            }
        }
    }
}
