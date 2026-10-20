package com.gathin.tvhome;

import android.animation.ArgbEvaluator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Region;
import android.os.Build;
import android.util.AttributeSet;
import android.view.View;

/**
 * Focus cursor: a thin border and a minimal soft glow in the focused app's colour, drawn only outside the
 * icon (the icon itself is never tinted). One instance floats over the grid and springs between tiles;
 * its colour cross-fades from one app's colour to the next.
 */
public class GlassView extends View {

    /** Room around the tile for the glow, in dp. MainActivity uses the same value. */
    static final float PAD_DP = 16f;
    /** Small empty space between the tile edge and the cursor border, in dp. */
    private static final float GAP_DP = 3f;
    private static final int GLOW_LAYERS = 4;

    private final Paint rim = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final Path inner = new Path();
    private final float density;
    private float radius;
    private int color = 0xFFFFFFFF;
    private ValueAnimator colorAnim;

    public GlassView(Context context, AttributeSet attrs) {
        super(context, attrs);
        density = getResources().getDisplayMetrics().density;
        radius = 16f * density;
        rim.setStyle(Paint.Style.STROKE);
        rim.setStrokeWidth(1.8f * density);
        glow.setStyle(Paint.Style.STROKE);
        glow.setStrokeWidth(1.7f * density);
    }

    void setRadius(float px) {
        radius = px;
        invalidate();
    }

    /** Changes the cursor colour; with animate the old colour fades into the new one. */
    void setColor(int target, boolean animate) {
        if (colorAnim != null) colorAnim.cancel();
        if (!animate || target == color) {
            color = target;
            invalidate();
            return;
        }
        colorAnim = ValueAnimator.ofObject(new ArgbEvaluator(), color, target);
        colorAnim.setDuration(260);
        colorAnim.addUpdateListener(a -> {
            color = (Integer) a.getAnimatedValue();
            invalidate();
        });
        colorAnim.start();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float inset = PAD_DP * density;
        rect.set(inset, inset, getWidth() - inset, getHeight() - inset);
        int rgb = color & 0x00FFFFFF;

        // Glow: thin rings that fade out away from the tile, clipped so nothing lands on the icon.
        canvas.save();
        inner.reset();
        inner.addRoundRect(rect, radius, radius, Path.Direction.CW);
        if (Build.VERSION.SDK_INT >= 26) canvas.clipOutPath(inner);
        else canvas.clipPath(inner, Region.Op.DIFFERENCE);
        float gap = GAP_DP * density;
        float step = 1.5f * density;
        for (int i = 0; i < GLOW_LAYERS; i++) {
            float t = 1f - i / (float) GLOW_LAYERS;
            int a = (int) (0x40 * t * t);
            glow.setColor((a << 24) | rgb);
            float o = gap + rim.getStrokeWidth() + (i + 0.5f) * step;
            canvas.drawRoundRect(rect.left - o, rect.top - o, rect.right + o, rect.bottom + o,
                    radius + o, radius + o, glow);
        }
        canvas.restore();

        // Border in the app colour, just outside the icon edge.
        rim.setColor(0xF2000000 | rgb);
        float h = gap + rim.getStrokeWidth() / 2f;
        canvas.drawRoundRect(rect.left - h, rect.top - h, rect.right + h, rect.bottom + h,
                radius + h, radius + h, rim);
    }
}
