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
import android.view.View;

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
        base.setColor(0x660C0C0E);        // see-through: the wallpaper behind the glass stays visible
        image.setAlpha(150);          // more of the sharp wallpaper shows through: less blur
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
            // A faint specular line along the top edge (no white haze): only a hint of glass.
            spec.setShader(new LinearGradient(rect.left, 0, rect.right, 0,
                    new int[]{0x00FFFFFF, 0x4DFFFFFF, 0x4DFFFFFF, 0x00FFFFFF}, new float[]{0f, 0.18f, 0.82f, 1f},
                    Shader.TileMode.CLAMP));
            // The rim is a little brighter at the top and fades toward the bottom.
            int a = edgeColor >>> 24;
            edge.setShader(new LinearGradient(0, rect.top, 0, rect.bottom,
                    ((Math.min(255, a * 3 / 2)) << 24) | 0xFFFFFF, ((a / 2) << 24) | 0xFFFFFF, Shader.TileMode.CLAMP));
            clip.reset();
            clip.addRoundRect(rect, radius, radius, Path.Direction.CW);
        }
        c.save();
        c.clipPath(clip);
        c.drawRect(rect.left, rect.top + 0.6f * density, rect.right, rect.top + 1.6f * density, spec);
        c.restore();
        c.drawRoundRect(rect, radius, radius, edge);
    }

    /** Space the soft shadow reaches beyond a tile on every side. */
    static final float SHADOW_SPREAD_DP = 12f;

    /** A soft black shadow, the same on all four sides: layered rounded rects with a falling opacity. */
    static final class SoftShadow extends android.graphics.drawable.Drawable {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF r = new RectF();
        private final float d, cornerDp;
        private static final int LAYERS = 12;

        SoftShadow(float density, float cornerDp) {
            this.d = density;
            this.cornerDp = cornerDp;
            p.setColor(0xFF000000);
        }

        @Override
        public void draw(Canvas c) {
            android.graphics.Rect b = getBounds();
            float spread = SHADOW_SPREAD_DP * d;
            for (int i = 0; i < LAYERS; i++) {
                float inset = spread * (i + 1) / (LAYERS + 1f);      // outermost first
                r.set(b.left + inset, b.top + inset, b.right - inset, b.bottom - inset);
                float rad = cornerDp * d + (spread - inset);
                p.setAlpha(9);
                c.drawRoundRect(r, rad, rad, p);
            }
        }

        @Override
        public void setAlpha(int alpha) {
        }

        @Override
        public void setColorFilter(android.graphics.ColorFilter colorFilter) {
        }

        @Override
        public int getOpacity() {
            return android.graphics.PixelFormat.TRANSLUCENT;
        }
    }

    /** Puts the soft shadow view behind a card and keeps it sized to the card. Scale is synced by the caller. */
    static void attachShadow(final View card, final View shadow, final float density, float cornerDp) {
        shadow.setBackground(new SoftShadow(density, cornerDp));
        shadow.setFocusable(false);
        shadow.setClickable(false);
        shadow.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        card.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> shadow.post(() -> {
            int s = Math.round(SHADOW_SPREAD_DP * density);
            android.widget.FrameLayout.LayoutParams lp = (android.widget.FrameLayout.LayoutParams) shadow.getLayoutParams();
            android.widget.FrameLayout.LayoutParams cp = (android.widget.FrameLayout.LayoutParams) card.getLayoutParams();
            int w = card.getWidth() + 2 * s, h = card.getHeight() + 2 * s;
            boolean centred = (cp.gravity & android.view.Gravity.HORIZONTAL_GRAVITY_MASK) == android.view.Gravity.CENTER_HORIZONTAL;
            int left = centred ? cp.leftMargin : cp.leftMargin - s;
            int top = cp.topMargin - s;
            if (lp.width == w && lp.height == h && lp.leftMargin == left && lp.topMargin == top && lp.gravity == cp.gravity) return;
            lp.width = w;
            lp.height = h;
            lp.leftMargin = left;
            lp.topMargin = top;
            lp.gravity = cp.gravity;
            shadow.setLayoutParams(lp);
        }));
    }
}
