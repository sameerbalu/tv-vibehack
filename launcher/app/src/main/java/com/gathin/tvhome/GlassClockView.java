package com.gathin.tvhome;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.text.format.DateFormat;
import android.util.AttributeSet;
import android.view.View;

import java.util.Calendar;

/**
 * The corner clock drawn as big stencil digits made of frosted glass: the blurred wallpaper shows through the
 * glyphs, a soft white veil brightens them from the top and a thin light rim outlines every shape.
 * The digits are drawn from simple strokes (no font file), so they scale cleanly.
 */
public class GlassClockView extends View {

    // glyph units: digit height = 1, digit box 0.64 wide, stroke 0.19
    private static final float S = 0.19f, DW = 0.64f, GAP = 0.09f, COLON = 0.20f;
    private static final float XL = 0.095f, XR = 0.545f, YT = 0.095f, YB = 0.905f, CX = 0.32f, CUT = 0.034f;
    private static final float HEIGHT_UNITS = 1f;

    private final Paint probe = new Paint(Paint.ANTI_ALIAS_FLAG);

    private Path stroked(Path centre) {
        Path out = new Path();
        probe.setStyle(Paint.Style.STROKE);
        probe.setStrokeWidth(S);
        probe.setStrokeCap(Paint.Cap.BUTT);
        probe.setStrokeJoin(Paint.Join.MITER);
        probe.getFillPath(centre, out);
        return out;
    }

    private static Path rect(float l, float t, float r, float b) {
        Path p = new Path();
        p.addRect(l, t, r, b, Path.Direction.CW);
        return p;
    }

    private static Path cut(Path glyph, Path... cuts) {
        for (Path c : cuts) glyph.op(c, Path.Op.DIFFERENCE);
        return glyph;
    }

    private static Path union(Path a, Path b) {
        a.op(b, Path.Op.UNION);
        return a;
    }

    private static void rotate180(Path p) {
        Matrix m = new Matrix();
        m.setRotate(180f, DW / 2f, 0.5f);
        p.transform(m);
    }

    private Path six() {
        Path hook = new Path();
        hook.arcTo(new RectF(XL, YT, XR, YT + 1.01f), 180f, 125f, true);
        Path bowl = new Path();
        bowl.addOval(new RectF(XL, 0.45f, XR, YB), Path.Direction.CW);
        Path g = union(stroked(hook), stroked(bowl));
        return cut(g, rect(0f, 0.43f, 0.2f, 0.43f + CUT));
    }

    private Path glyph(char ch) {
        Path c = new Path();
        switch (ch) {
            case '0': {
                c.moveTo(XL, YT + 0.225f);
                c.arcTo(new RectF(XL, YT, XR, YT + 0.45f), 180f, 180f);
                c.lineTo(XR, YB - 0.225f);
                c.arcTo(new RectF(XL, YB - 0.45f, XR, YB), 0f, 180f);
                c.close();
                return cut(stroked(c), rect(CX - CUT / 2, 0f, CX + CUT / 2, 0.2f),
                        rect(CX - CUT / 2, 0.8f, CX + CUT / 2, 1f));
            }
            case '1': {
                Path stem = rect(0.375f, 0f, 0.565f, 1f);
                Path flag = new Path();
                flag.moveTo(0f, 0.27f);
                flag.lineTo(0.25f, 0f);
                flag.lineTo(0.32f, 0f);
                flag.lineTo(0.32f, 0.30f);
                flag.close();
                return union(stem, flag);
            }
            case '2': {
                c.arcTo(new RectF(XL, YT, XR, 0.53f), 180f, 215f, true);
                c.lineTo(XL + 0.01f, YB);
                c.lineTo(DW, YB);
                return cut(stroked(c), rect(0.18f, 0.55f, DW, 0.55f + CUT));
            }
            case '3': {
                c.arcTo(new RectF(XL, YT, XR, 0.5f), 200f, 250f, true);
                c.arcTo(new RectF(XL - 0.01f, 0.5f, XR + 0.01f, YB), 270f, 250f, true);
                return cut(stroked(c), rect(0f, 0.5f - CUT / 2, DW, 0.5f + CUT / 2));
            }
            case '4': {
                c.moveTo(0.46f, 0f);
                c.lineTo(0.46f, 1f);
                Path g = stroked(c);
                Path bar = new Path();
                bar.moveTo(0f, 0.70f);
                bar.lineTo(DW, 0.70f);
                union(g, stroked(bar));
                Path diag = new Path();
                diag.moveTo(0.30f, 0.0f);
                diag.lineTo(0.05f, 0.70f);
                union(g, stroked(diag));
                return cut(g, rect(0.31f, 0f, 0.31f + CUT, 0.62f));
            }
            case '5': {
                c.moveTo(0.60f, YT);
                c.lineTo(XL, YT);
                c.lineTo(XL, 0.46f);
                Path g = stroked(c);
                Path bowl = new Path();
                bowl.arcTo(new RectF(XL, 0.40f, XR, YB), 230f, 270f, true);
                union(g, stroked(bowl));
                return cut(g, rect(0f, 0.41f, 0.32f, 0.41f + CUT));
            }
            case '6':
                return six();
            case '7': {
                Path g = rect(0f, 0f, DW, S);
                Path d = new Path();
                d.moveTo(0.52f, 0.1f);
                d.lineTo(0.24f, 1f);
                union(g, stroked(d));
                return cut(g, rect(0f, S, DW, S + CUT));
            }
            case '8': {
                c.addOval(new RectF(0.12f, YT, 0.52f, 0.52f), Path.Direction.CW);
                Path b = new Path();
                b.addOval(new RectF(XL, 0.47f, XR, YB), Path.Direction.CW);
                Path g = union(stroked(c), stroked(b));
                return cut(g, rect(0f, 0.475f, 0.17f, 0.475f + CUT));
            }
            case '9': {
                Path g = six();
                rotate180(g);
                return g;
            }
            default:
                return c;
        }
    }

    private final float density;
    private final Paint strokeProbe = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint base = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glass = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint veil = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint rim = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Matrix matrix = new Matrix();
    private final Path glyphs = new Path();
    private final int[] loc = new int[2];

    private Bitmap backdrop;
    private BitmapShader backdropShader;
    private String shown = "";
    private float unit;
    private float textWidthUnits;
    private boolean registered;

    private final BroadcastReceiver tick = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) { refresh(); }
    };

    public GlassClockView(Context context, AttributeSet attrs) {
        super(context, attrs);
        density = context.getResources().getDisplayMetrics().density;
        unit = 46f * density;             // digit height in px
        strokeProbe.setStyle(Paint.Style.STROKE);
        strokeProbe.setStrokeCap(Paint.Cap.BUTT);
        base.setStyle(Paint.Style.FILL);
        glass.setStyle(Paint.Style.FILL);
        veil.setStyle(Paint.Style.FILL);
        rim.setStyle(Paint.Style.STROKE);
        rim.setStrokeWidth(0.9f * density);
        rim.setStrokeJoin(Paint.Join.ROUND);
        build(currentText());
    }

    /** The blurred wallpaper (same bitmap the dock band and menus use), or null for none. */
    void setBackdrop(Bitmap blurred) {
        backdrop = blurred;
        backdropShader = blurred == null ? null : new BitmapShader(blurred, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
        glass.setShader(backdropShader);
        invalidate();
    }

    private String currentText() {
        Calendar c = Calendar.getInstance();
        int h = c.get(Calendar.HOUR_OF_DAY);
        int m = c.get(Calendar.MINUTE);
        if (DateFormat.is24HourFormat(getContext())) {
            return (h < 10 ? "0" : "") + h + ":" + (m < 10 ? "0" : "") + m;
        }
        h = h % 12;
        if (h == 0) h = 12;
        return h + ":" + (m < 10 ? "0" : "") + m;
    }

    private void refresh() {
        String t = currentText();
        if (!t.equals(shown)) {
            build(t);
            requestLayout();
            invalidate();
        }
    }

    /** Builds the filled outline of all glyphs in unit space (digit height = 1). */
    private void build(String text) {
        shown = text;
        glyphs.reset();
        float x = 0f;
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            Path g;
            float adv;
            if (ch == ':') {
                g = new Path();
                g.addCircle(COLON / 2f, 0.31f, 0.1f, Path.Direction.CW);
                g.addCircle(COLON / 2f, 0.69f, 0.1f, Path.Direction.CW);
                adv = COLON;
            } else {
                g = glyph(ch);
                adv = ch == '1' ? 0.565f : DW;
            }
            g.offset(x, 0f);
            glyphs.addPath(g);
            x += adv + GAP;
        }
        textWidthUnits = x - GAP;
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int pad = (int) (3 * density);
        int w = (int) Math.ceil(textWidthUnits * unit) + pad * 2;
        int h = (int) Math.ceil(HEIGHT_UNITS * unit) + pad * 2;
        setMeasuredDimension(resolveSize(w, widthMeasureSpec), resolveSize(h, heightMeasureSpec));
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (!registered) {
            IntentFilter f = new IntentFilter();
            f.addAction(Intent.ACTION_TIME_TICK);
            f.addAction(Intent.ACTION_TIME_CHANGED);
            f.addAction(Intent.ACTION_TIMEZONE_CHANGED);
            getContext().registerReceiver(tick, f);
            registered = true;
        }
        refresh();
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        if (registered) {
            try { getContext().unregisterReceiver(tick); } catch (Exception ignored) { }
            registered = false;
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float pad = 3 * density;
        float top = pad;
        float h = HEIGHT_UNITS * unit;
        View root = getRootView();
        float sw = root.getWidth(), sh = root.getHeight();
        getLocationInWindow(loc);

        canvas.save();
        canvas.translate(pad, top);
        canvas.scale(unit, unit);
        Path p = glyphs;

        // 1) soft body so the glass reads on any wallpaper
        base.setColor(backdropShader != null ? 0x28000000 : 0x30FFFFFF);
        canvas.drawPath(p, base);

        // 2) blurred wallpaper showing through the glyphs
        if (backdropShader != null && sw > 0 && sh > 0) {
            float scale = Math.max(sw / backdrop.getWidth(), sh / backdrop.getHeight());
            float ox = (sw - backdrop.getWidth() * scale) / 2f;
            float oy = (sh - backdrop.getHeight() * scale) / 2f;
            matrix.setScale(scale, scale);
            matrix.postTranslate(ox - loc[0], oy - loc[1]);
            // the shader works in the canvas' current (unit) space, so undo that scale/translation for it
            Matrix inv = new Matrix();
            inv.setScale(1f / unit, 1f / unit);
            inv.preTranslate(-pad, -top);
            Matrix m = new Matrix(matrix);
            m.postConcat(inv);
            backdropShader.setLocalMatrix(m);
            canvas.drawPath(p, glass);
        }

        // 3) lavender-white veil, brighter at the top like light on glass
        veil.setShader(new LinearGradient(0, 0, 0, HEIGHT_UNITS, 0x80D4D0FF, 0x5CB8B4F0, Shader.TileMode.CLAMP));
        canvas.drawPath(p, veil);
        canvas.restore();

        // 4) thin light rim (drawn in pixel space so its width stays crisp)
        canvas.save();
        canvas.translate(pad, top);
        Path px = new Path();
        Matrix s = new Matrix();
        s.setScale(unit, unit);
        p.transform(s, px);
        rim.setShader(new LinearGradient(0, 0, 0, h, 0x88FFFFFF, 0x33FFFFFF, Shader.TileMode.CLAMP));
        canvas.drawPath(px, rim);
        canvas.restore();
    }
}
