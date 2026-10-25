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

    // glyph units: stroke 0.17, digit box 0.415 x 0.83 (centre lines), so a digit is 0.585 x 1.0 overall
    private static final float W = 0.17f, R = 0.2075f, X1 = 0.415f, YB = 0.83f;
    private static final float CX = 0.2075f, CYT = 0.2075f, CYB = 0.6225f;
    private static final float GAP = 0.13f, COLON = 0.22f;
    private static final float HEIGHT_UNITS = YB + W;

    private static float[] L(float x1, float y1, float x2, float y2) { return new float[]{0, x1, y1, x2, y2}; }
    private static float[] A(float cx, float cy, float r, float s, float sw) { return new float[]{1, cx, cy, r, s, sw}; }

    private static float[][] rot180(float[][] src) {
        float[][] o = new float[src.length][];
        for (int i = 0; i < src.length; i++) {
            float[] p = src[i];
            o[i] = p[0] == 0 ? L(X1 - p[1], YB - p[2], X1 - p[3], YB - p[4])
                    : A(X1 - p[1], YB - p[2], p[3], p[4] + 180f, p[5]);
        }
        return o;
    }

    private static final float[][][] DIGITS = new float[10][][];
    static {
        DIGITS[0] = new float[][]{L(0, CYT, 0, CYB), L(X1, CYT, X1, CYB),
                A(CX, CYT, R, 180, 78), A(CX, CYT, R, 282, 78), A(CX, CYB, R, 0, 78), A(CX, CYB, R, 102, 78)};
        DIGITS[1] = new float[][]{L(.30f, 0, .30f, YB), L(0, .19f, .2f, .04f)};
        DIGITS[2] = new float[][]{A(CX, CYT, R, 185, 205), L(.36f, .36f, .075f, .74f), L(.15f, YB, X1, YB)};
        DIGITS[3] = new float[][]{A(CX, CYT, R, 205, 235), A(CX, CYB, R, 280, 240)};
        DIGITS[4] = new float[][]{L(.30f, 0, .30f, YB), L(0, .58f, X1, .58f), L(.17f, .04f, 0, .5f)};
        DIGITS[5] = new float[][]{L(X1 - .03f, 0, .07f, 0), L(.07f, 0, .07f, .36f), A(CX, CYB, R, 238, 262)};
        DIGITS[6] = new float[][]{A(CX, CYT, R, 325, -122), L(0, .275f, 0, CYB), A(CX, CYB, R, 180, -330)};
        DIGITS[7] = new float[][]{L(0, 0, .34f, 0), L(.39f, .1f, .13f, YB)};
        DIGITS[8] = new float[][]{A(CX, CYT, R, 200, 320), A(CX, CYB, R, 20, 320)};
        DIGITS[9] = rot180(DIGITS[6]);
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
        unit = 66f * density;             // digit height in px
        strokeProbe.setStyle(Paint.Style.STROKE);
        strokeProbe.setStrokeCap(Paint.Cap.BUTT);
        base.setStyle(Paint.Style.FILL);
        glass.setStyle(Paint.Style.FILL);
        veil.setStyle(Paint.Style.FILL);
        rim.setStyle(Paint.Style.STROKE);
        rim.setStrokeWidth(1.3f * density);
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
        Path stroke = new Path();
        Path dots = new Path();
        float x = W / 2f;
        float y = W / 2f;
        textWidthUnits = 0;
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (ch == ':') {
                float cx = x + COLON / 2f - W / 2f;
                dots.addCircle(cx, y + .27f, .088f, Path.Direction.CW);
                dots.addCircle(cx, y + .58f, .088f, Path.Direction.CW);
                x += COLON + GAP;
                continue;
            }
            float[][] d = DIGITS[ch - '0'];
            for (float[] p : d) {
                if (p[0] == 0) {
                    stroke.moveTo(x + p[1], y + p[2]);
                    stroke.lineTo(x + p[3], y + p[4]);
                } else {
                    RectF oval = new RectF(x + p[1] - p[3], y + p[2] - p[3], x + p[1] + p[3], y + p[2] + p[3]);
                    stroke.arcTo(oval, p[4], p[5], true);
                }
            }
            x += X1 + W + GAP;
        }
        textWidthUnits = x - GAP;
        // turn the centre lines into filled shapes (so the glass fill and rim follow the real outline)
        Path fill = new Path();
        strokeProbe.setStrokeWidth(W);
        strokeProbe.getFillPath(stroke, fill);
        glyphs.reset();
        glyphs.addPath(fill);
        glyphs.addPath(dots);
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
        base.setColor(backdropShader != null ? 0x30000000 : 0x26FFFFFF);
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
        veil.setShader(new LinearGradient(0, 0, 0, HEIGHT_UNITS, 0x78F3EEFF, 0x30D9D4FF, Shader.TileMode.CLAMP));
        canvas.drawPath(p, veil);
        canvas.restore();

        // 4) thin light rim (drawn in pixel space so its width stays crisp)
        canvas.save();
        canvas.translate(pad, top);
        Path px = new Path();
        Matrix s = new Matrix();
        s.setScale(unit, unit);
        p.transform(s, px);
        rim.setShader(new LinearGradient(0, 0, 0, h, 0xCCFFFFFF, 0x55FFFFFF, Shader.TileMode.CLAMP));
        canvas.drawPath(px, rim);
        canvas.restore();
    }
}
