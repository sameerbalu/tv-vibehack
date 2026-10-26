package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

/**
 * A transparent PNG drawn over the dock: as wide as the dock's glass band, bottom edge on the band's bottom edge,
 * so the dock's blurred background and the dock apps sit behind it. It never takes focus or touches.
 */
public class DockPngView extends View {

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Matrix matrix = new Matrix();
    private final RectF band = new RectF();
    private Bitmap bitmap;

    public DockPngView(Context c, AttributeSet a) {
        super(c, a);
        setFocusable(false);
        setFocusableInTouchMode(false);
        setClickable(false);
        setWillNotDraw(false);
    }

    void setBitmap(Bitmap b) {
        bitmap = b;
        invalidate();
    }

    boolean hasBitmap() {
        return bitmap != null;
    }

    /** The dock band in this view's coordinates. Only redraws when it really moved. */
    void setBand(RectF r) {
        if (Math.abs(band.left - r.left) < 0.5f && Math.abs(band.top - r.top) < 0.5f
                && Math.abs(band.right - r.right) < 0.5f && Math.abs(band.bottom - r.bottom) < 0.5f) return;
        band.set(r);
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (bitmap == null || band.width() <= 1f) return;
        float s = band.width() / bitmap.getWidth();
        float h = bitmap.getHeight() * s;
        matrix.setScale(s, s);
        matrix.postTranslate(band.left, band.bottom - h);
        canvas.drawBitmap(bitmap, matrix, paint);
    }
}
