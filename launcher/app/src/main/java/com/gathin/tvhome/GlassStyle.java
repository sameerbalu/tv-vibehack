package com.gathin.tvhome;

import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;

/**
 * Frosted-glass look shared by the dock band and the all-apps panel: a blurred copy of the wallpaper
 * (mapped exactly like the full-screen background), a dark tint, a white veil that is brighter at the top,
 * and a thin rim. Without a wallpaper it is a lighter glass over black.
 */
final class GlassStyle {

    private final Paint base = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint image = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint dim = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint sheen = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint edge = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Matrix matrix = new Matrix();
    private final int veilTop;
    private final int veilBottom;
    private Bitmap backdrop;
    private BitmapShader backdropShader;

    GlassStyle(float density, int dimColor, int veilTop, int veilBottom, int edgeColor) {
        this.veilTop = veilTop;
        this.veilBottom = veilBottom;
        base.setColor(0xFF0C0C0E);
        image.setAlpha(209);
        dim.setColor(dimColor);
        edge.setStyle(Paint.Style.STROKE);
        edge.setStrokeWidth(1.2f * density);
        edge.setColor(edgeColor);
    }

    /** The blurred wallpaper (the same bitmap the full-screen blur shows), or null for none. */
    void setBackdrop(Bitmap blurred) {
        backdrop = blurred;
        if (blurred == null) {
            backdropShader = null;
            image.setShader(null);
        } else {
            backdropShader = new BitmapShader(blurred, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
            image.setShader(backdropShader);
        }
    }

    /**
     * @param screenW,screenH size of the full-screen background the backdrop is stretched over
     * @param originX,originY position of the canvas origin on that screen
     */
    void draw(Canvas c, RectF rect, float radius, float screenW, float screenH, float originX, float originY) {
        c.drawRoundRect(rect, radius, radius, base);
        if (backdrop != null && backdropShader != null && screenW > 0 && screenH > 0) {
            float scale = Math.max(screenW / backdrop.getWidth(), screenH / backdrop.getHeight());
            float ox = (screenW - backdrop.getWidth() * scale) / 2f;
            float oy = (screenH - backdrop.getHeight() * scale) / 2f;
            matrix.setScale(scale, scale);
            matrix.postTranslate(ox - originX, oy - originY);
            backdropShader.setLocalMatrix(matrix);
            c.drawRoundRect(rect, radius, radius, image);
            c.drawRoundRect(rect, radius, radius, dim);
        }
        sheen.setShader(new LinearGradient(0, rect.top, 0, rect.bottom, veilTop, veilBottom, Shader.TileMode.CLAMP));
        c.drawRoundRect(rect, radius, radius, sheen);
        c.drawRoundRect(rect, radius, radius, edge);
    }
}
