package com.gathin.tvhome;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;

/**
 * The light that catches the top of a glass tile: a thin outline that is brightest across the top edge and around
 * both upper corners, then fades down the sides and is gone before the bottom. Optionally a soft sheen inside.
 */
final class RimLight extends Drawable {

    private final Paint rim = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint rimBottom = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint sheen = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final float radius, stroke;
    private final boolean withSheen;
    private final boolean withBottom;

    /** Posters: top light plus the faint light along the bottom. */
    RimLight(float density, float radiusDp, boolean withSheen) {
        this(density, radiusDp, withSheen, true);
    }

    /** withBottom = false: the light only fades from the top down to dark and never comes back at the bottom. */
    RimLight(float density, float radiusDp, boolean withSheen, boolean withBottom) {
        this.withBottom = withBottom;
        this.radius = radiusDp * density;
        this.stroke = 1.6f * density;
        this.withSheen = withSheen;
        rim.setStyle(Paint.Style.STROKE);
        rim.setStrokeWidth(stroke);
        rimBottom.setStyle(Paint.Style.STROKE);
        rimBottom.setStrokeWidth(stroke);
        sheen.setStyle(Paint.Style.FILL);
    }

    @Override
    protected void onBoundsChange(Rect b) {
        if (withBottom) {
            rim.setShader(new LinearGradient(0, b.top, 0, b.top + b.height() * 0.72f,
                    new int[]{0x7AFFFFFF, 0x30FFFFFF, 0x00FFFFFF},
                    new float[]{0f, 0.32f, 1f}, Shader.TileMode.CLAMP));
        } else {
            // Apps: bright at the top, getting dimmer all the way down but keeping a little light at the bottom.
            rim.setShader(new LinearGradient(0, b.top, 0, b.bottom,
                    new int[]{0x7AFFFFFF, 0x34FFFFFF, 0x18FFFFFF},
                    new float[]{0f, 0.40f, 1f}, Shader.TileMode.CLAMP));
        }
        // The faint light from below: only the last part of the sides and the bottom edge, dim.
        rimBottom.setShader(new LinearGradient(0, b.bottom - b.height() * 0.42f, 0, b.bottom,
                new int[]{0x00FFFFFF, 0x1CFFFFFF}, null, Shader.TileMode.CLAMP));
        sheen.setShader(new LinearGradient(0, b.top, 0, b.top + b.height() * 0.5f,
                new int[]{0x16FFFFFF, 0x00FFFFFF}, null, Shader.TileMode.CLAMP));
    }

    @Override
    public void draw(Canvas c) {
        Rect b = getBounds();
        if (b.isEmpty()) return;
        if (withSheen) {
            rect.set(b.left, b.top, b.right, b.bottom);
            c.drawRoundRect(rect, radius, radius, sheen);
        }
        float h = stroke / 2f;
        rect.set(b.left + h, b.top + h, b.right - h, b.bottom - h);
        c.drawRoundRect(rect, radius - h, radius - h, rim);
        if (withBottom) c.drawRoundRect(rect, radius - h, radius - h, rimBottom);
    }

    @Override
    public void setAlpha(int alpha) {
        rim.setAlpha(alpha);
        rimBottom.setAlpha(alpha);
    }

    @Override
    public void setColorFilter(ColorFilter cf) {
        rim.setColorFilter(cf);
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }
}
