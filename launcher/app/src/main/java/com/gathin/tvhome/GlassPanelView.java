package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

/** Rounded frosted-glass panel behind the all-apps list. Sits directly in the full-screen root. */
public class GlassPanelView extends View {

    private final float density;
    private final GlassStyle style;
    private final RectF rect = new RectF();

    public GlassPanelView(Context context, AttributeSet attrs) {
        super(context, attrs);
        density = context.getResources().getDisplayMetrics().density;
        style = new GlassStyle(density, 0x2A000000, 0x22FFFFFF, 0x08FFFFFF, 0x38FFFFFF);
    }

    void setBackdrop(Bitmap blurred) {
        style.setBackdrop(blurred);
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float inset = 1f * density;
        rect.set(inset, inset, getWidth() - inset, getHeight() - inset);
        View parent = (View) getParent();
        style.draw(canvas, rect, 26f * density, parent.getWidth(), parent.getHeight(), getLeft(), getTop());
    }
}
