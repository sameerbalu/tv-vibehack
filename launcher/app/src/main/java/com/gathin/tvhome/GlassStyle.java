package com.gathin.tvhome;

import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
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
    private final Paint diag = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint spec = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint lowShade = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path clip = new Path();
    private final int edgeColor;
    private final float density;
    private float lastL, lastT, lastR, lastB, lastRad = -1f;
    private final Matrix matrix = new Matrix();
    private final int veilTop;
    private final int veilBottom;
    private Shader sheenShader;
    private float sheenTop, sheenBottom;
    private Bitmap backdrop;
    private BitmapShader backdropShader;

    GlassStyle(float density, int dimColor, int veilTop, int veilBottom, int edgeColor) {
        this.density = density;
        this.edgeColor = edgeColor;
        this.veilTop = veilTop;
        this.veilBottom = veilBottom;
        base.setColor(0xFF0C0C0E);
        image.setAlpha(209);
        dim.setColor(dimColor);
        edge.setStyle(Paint.Style.STROKE);
        edge.setStrokeWidth(1.2f * density);
        edge.setColor(edgeColor);
        spec.setStyle(Paint.Style.FILL);
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
        if (sheenShader == null || sheenTop != rect.top || sheenBottom != rect.bottom) {
            sheenTop = rect.top;
            sheenBottom = rect.bottom;
            sheenShader = new LinearGradient(0, rect.top, 0, rect.bottom, veilTop, veilBottom, Shader.TileMode.CLAMP);
            sheen.setShader(sheenShader);
        }
        c.drawRoundRect(rect, radius, radius, sheen);
        if (rect.left != lastL || rect.top != lastT || rect.right != lastR || rect.bottom != lastB || radius != lastRad) {
            lastL = rect.left;
            lastT = rect.top;
            lastR = rect.right;
            lastB = rect.bottom;
            lastRad = radius;
            // Light falls from the top-left: a soft diagonal sheen across the glass.
            diag.setShader(new LinearGradient(rect.left, rect.top, rect.right, rect.bottom,
                    new int[]{0x30FFFFFF, 0x00FFFFFF, 0x00FFFFFF, 0x14FFFFFF}, new float[]{0f, 0.38f, 0.7f, 1f},
                    Shader.TileMode.CLAMP));
            // Thin specular line along the top edge that fades out toward the corners.
            spec.setShader(new LinearGradient(rect.left, 0, rect.right, 0,
                    new int[]{0x00FFFFFF, 0x99FFFFFF, 0x99FFFFFF, 0x00FFFFFF}, new float[]{0f, 0.16f, 0.84f, 1f},
                    Shader.TileMode.CLAMP));
            // The lower edge is a little darker, which gives the sheet some thickness.
            lowShade.setShader(new LinearGradient(0, rect.top + (rect.bottom - rect.top) * 0.72f, 0, rect.bottom,
                    0x00000000, 0x2E000000, Shader.TileMode.CLAMP));
            // The rim is brightest at the top and nearly gone at the bottom.
            int a = edgeColor >>> 24;
            edge.setShader(new LinearGradient(0, rect.top, 0, rect.bottom,
                    ((Math.min(255, a * 2)) << 24) | 0xFFFFFF, ((a / 2) << 24) | 0xFFFFFF, Shader.TileMode.CLAMP));
            clip.reset();
            clip.addRoundRect(rect, radius, radius, Path.Direction.CW);
        }
        c.drawRoundRect(rect, radius, radius, diag);
        c.drawRoundRect(rect, radius, radius, lowShade);
        c.save();
        c.clipPath(clip);
        c.drawRect(rect.left, rect.top + 0.6f * density, rect.right, rect.top + 1.8f * density, spec);
        c.restore();
        c.drawRoundRect(rect, radius, radius, edge);
    }

    /** A soft black shadow under a tile or poster (elevation based, so it follows the rounded outline). */
    static void shadow(android.view.View v, float density) {
        v.setElevation(7f * density);
        if (android.os.Build.VERSION.SDK_INT >= 28) {
            v.setOutlineAmbientShadowColor(0xFF000000);
            v.setOutlineSpotShadowColor(0xFF000000);
        }
    }
}
