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

/** Light falling on a tile from above: a soft gloss over the top part and a thin bright rim that fades downward. */
final class TileSheen extends Drawable {
    private final Paint gloss = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint rim = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();
    private final float radius, stroke;

    TileSheen(float density, float radiusDp) {
        radius = radiusDp * density;
        stroke = 1.1f * density;
        rim.setStyle(Paint.Style.STROKE);
        rim.setStrokeWidth(stroke);
    }

    @Override
    protected void onBoundsChange(Rect b) {
        gloss.setShader(new LinearGradient(0, b.top, 0, b.top + b.height() * 0.6f,
                0x2EFFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP));
        rim.setShader(new LinearGradient(0, b.top, 0, b.bottom,
                new int[]{0x8CFFFFFF, 0x26FFFFFF, 0x0DFFFFFF}, new float[]{0f, 0.45f, 1f}, Shader.TileMode.CLAMP));
    }

    @Override
    public void draw(Canvas c) {
        Rect b = getBounds();
        r.set(b.left + stroke / 2f, b.top + stroke / 2f, b.right - stroke / 2f, b.bottom - stroke / 2f);
        c.drawRoundRect(r, radius, radius, gloss);
        c.drawRoundRect(r, radius, radius, rim);
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
}
