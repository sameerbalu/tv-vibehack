package com.gathin.tvhome;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;

/**
 * The light that catches the edge of a glass tile: a thin outline whose brightness fades from the top-left corner
 * across the tile, so it bends around the rounded corners instead of being one straight line along the top.
 */
final class RimLight extends Drawable {

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final float radius, stroke;

    RimLight(float density, float radiusDp) {
        radius = radiusDp * density;
        stroke = 1.4f * density;
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(stroke);
    }

    @Override
    protected void onBoundsChange(android.graphics.Rect b) {
        // Bright at the top-left, a little glint at the bottom-right, nothing in between.
        paint.setShader(new LinearGradient(b.left, b.top, b.left + b.width() * 0.62f, b.top + b.height() * 1.05f,
                new int[]{0x8CFFFFFF, 0x30FFFFFF, 0x00FFFFFF, 0x12FFFFFF, 0x2AFFFFFF},
                new float[]{0f, 0.28f, 0.6f, 0.85f, 1f}, Shader.TileMode.CLAMP));
    }

    @Override
    public void draw(Canvas c) {
        android.graphics.Rect b = getBounds();
        if (b.isEmpty()) return;
        float h = stroke / 2f;
        rect.set(b.left + h, b.top + h, b.right - h, b.bottom - h);
        c.drawRoundRect(rect, radius - h, radius - h, paint);
    }

    @Override
    public void setAlpha(int alpha) {
        paint.setAlpha(alpha);
    }

    @Override
    public void setColorFilter(ColorFilter cf) {
        paint.setColorFilter(cf);
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }
}
