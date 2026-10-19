package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.RectF;
import android.graphics.drawable.AdaptiveIconDrawable;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;

/**
 * Re-masks app icons into one shape. Adaptive icons (background + foreground layers) fill the shape properly;
 * older flat icons are placed on a dark backing so they look the same as the rest.
 */
final class IconShapes {
    static final int AUTO = 0;       // leave the icon as the app supplied it
    static final int CIRCLE = 1;
    static final int SQUIRCLE = 2;
    static final int ROUNDED = 3;
    static final int SQUARE = 4;

    private IconShapes() {}

    static Drawable shape(Context ctx, Drawable icon, int shape, int size) {
        if (icon == null || shape == AUTO) return icon;
        try {
            Bitmap content = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(content);
            if (icon instanceof AdaptiveIconDrawable) {
                AdaptiveIconDrawable ai = (AdaptiveIconDrawable) icon;
                // Adaptive layers are 108dp with the visible part 72dp in the middle: draw them 1.5x as large.
                int q = size / 4;
                drawLayer(ai.getBackground(), c, -q, -q, size + q, size + q);
                drawLayer(ai.getForeground(), c, -q, -q, size + q, size + q);
            } else {
                c.drawColor(0xFF26262B);
                int inset = (int) (size * 0.18f);
                drawLayer(icon, c, inset, inset, size - inset, size - inset);
            }
            Bitmap out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
            Canvas oc = new Canvas(out);
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            oc.drawPath(path(shape, size), p);
            p.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.SRC_IN));
            oc.drawBitmap(content, 0, 0, p);
            content.recycle();
            return new BitmapDrawable(ctx.getResources(), out);
        } catch (Throwable t) {
            return icon;
        }
    }

    private static void drawLayer(Drawable layer, Canvas c, int l, int t, int r, int b) {
        if (layer == null) return;
        Drawable d = layer.getConstantState() != null ? layer.getConstantState().newDrawable().mutate() : layer;
        d.setBounds(l, t, r, b);
        d.draw(c);
    }

    private static Path path(int shape, int size) {
        Path path = new Path();
        float s = size;
        switch (shape) {
            case CIRCLE:
                path.addCircle(s / 2f, s / 2f, s / 2f, Path.Direction.CW);
                break;
            case SQUIRCLE:
                int steps = 96;
                for (int i = 0; i <= steps; i++) {
                    double t = 2 * Math.PI * i / steps;
                    double cos = Math.cos(t), sin = Math.sin(t);
                    float x = (float) (s / 2f + s / 2f * Math.signum(cos) * Math.pow(Math.abs(cos), 0.5));
                    float y = (float) (s / 2f + s / 2f * Math.signum(sin) * Math.pow(Math.abs(sin), 0.5));
                    if (i == 0) path.moveTo(x, y);
                    else path.lineTo(x, y);
                }
                path.close();
                break;
            case SQUARE:
                path.addRoundRect(new RectF(0, 0, s, s), s * 0.08f, s * 0.08f, Path.Direction.CW);
                break;
            default:
                path.addRoundRect(new RectF(0, 0, s, s), s * 0.28f, s * 0.28f, Path.Direction.CW);
                break;
        }
        return path;
    }
}
