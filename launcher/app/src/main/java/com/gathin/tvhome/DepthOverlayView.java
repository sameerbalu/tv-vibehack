package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.Rect;
import android.util.AttributeSet;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

/**
 * The depth PNG drawn again, but only inside the given rectangles (the widget columns), so the widgets sit
 * behind the "character" of the wallpaper. Same crop and dimming as the full-screen depth PNG.
 */
public class DepthOverlayView extends View {

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Matrix matrix = new Matrix();
    private final List<Rect> rects = new ArrayList<>();
    private Bitmap bitmap;
    private float stageZ = 1f, stageX, stageY;

    public DepthOverlayView(Context c, AttributeSet a) {
        super(c, a);
        paint.setColorFilter(new PorterDuffColorFilter(0x73000000, PorterDuff.Mode.SRC_ATOP));
        setWillNotDraw(false);
    }

    void setBitmap(Bitmap b) {
        bitmap = b;
        invalidate();
    }

    boolean hasBitmap() {
        return bitmap != null;
    }

    /** Same zoom/shift as the other picture layers; the cut-out rectangles themselves do not move. */
    void setStage(float z, float tx, float ty) {
        if (z == stageZ && tx == stageX && ty == stageY) return;
        stageZ = z;
        stageX = tx;
        stageY = ty;
        if (getVisibility() == VISIBLE && bitmap != null && !rects.isEmpty()) invalidate();
    }

    void setRects(List<Rect> list) {
        rects.clear();
        rects.addAll(list);
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (bitmap == null || rects.isEmpty() || getWidth() == 0 || getHeight() == 0) return;
        float s = Math.max(getWidth() / (float) bitmap.getWidth(), getHeight() / (float) bitmap.getHeight());
        float dx = (getWidth() - bitmap.getWidth() * s) / 2f;
        float dy = (getHeight() - bitmap.getHeight() * s) / 2f;
        matrix.setScale(s, s);
        matrix.postTranslate(dx, dy);
        matrix.postScale(stageZ, stageZ, getWidth() / 2f, getHeight() / 2f);
        matrix.postTranslate(stageX, stageY);
        for (Rect r : rects) {
            canvas.save();
            canvas.clipRect(r);
            canvas.drawBitmap(bitmap, matrix, paint);
            canvas.restore();
        }
    }
}
