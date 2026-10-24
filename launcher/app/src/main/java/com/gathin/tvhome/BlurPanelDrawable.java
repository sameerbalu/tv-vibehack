package com.gathin.tvhome;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.view.View;

/** Frosted glass for pop-up menus: the blurred wallpaper, mapped exactly like the full-screen background. */
final class BlurPanelDrawable extends Drawable {

    private final GlassStyle style;
    private final float density;
    private final View screen, host;
    private final RectF rect = new RectF();
    private final int[] a = new int[2], b = new int[2];

    BlurPanelDrawable(float density, Bitmap backdrop, View screen, View host) {
        this.density = density;
        this.screen = screen;
        this.host = host;
        style = new GlassStyle(density, 0x5C000000, 0x30FFFFFF, 0x0DFFFFFF, 0x3DFFFFFF);
        style.setBackdrop(backdrop);
    }

    @Override
    public void draw(Canvas c) {
        Rect bnd = getBounds();
        float inset = density;
        rect.set(bnd.left + inset, bnd.top + inset, bnd.right - inset, bnd.bottom - inset);
        screen.getLocationOnScreen(a);
        host.getLocationOnScreen(b);
        style.draw(c, rect, 15 * density, screen.getWidth(), screen.getHeight(), b[0] - a[0], b[1] - a[1]);
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
