package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;

/**
 * A transparent PNG laid over the whole screen the same way the depth foreground is (scaled to cover the screen,
 * centred), but above the dock, its apps and the cursor, so they all sit behind it. It never takes focus or touches.
 */
public class DockPngView extends View {

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Matrix matrix = new Matrix();
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

    @Override
    protected void onDraw(Canvas canvas) {
        if (bitmap == null || getWidth() == 0 || getHeight() == 0) return;
        float s = Math.max(getWidth() / (float) bitmap.getWidth(), getHeight() / (float) bitmap.getHeight());
        matrix.setScale(s, s);
        matrix.postTranslate((getWidth() - bitmap.getWidth() * s) / 2f, (getHeight() - bitmap.getHeight() * s) / 2f);
        canvas.drawBitmap(bitmap, matrix, paint);
    }
}
