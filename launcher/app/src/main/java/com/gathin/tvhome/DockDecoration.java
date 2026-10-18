package com.gathin.tvhome;

import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

/**
 * Draws a frosted-glass band behind the first (dock) row and adds a gap below it.
 * With a wallpaper set, the band shows a blurred copy of it (same crop as the full screen background),
 * topped with a white veil and a thin rim, like the tvOS dock. Without one it is a lighter glass over black.
 */
public class DockDecoration extends RecyclerView.ItemDecoration {

    private final int dockSize;
    private final float density;
    private final Paint base = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint image = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint dim = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint sheen = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint edge = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final Matrix matrix = new Matrix();
    private Bitmap backdrop;
    private BitmapShader backdropShader;

    DockDecoration(int dockSize, float density) {
        this.dockSize = dockSize;
        this.density = density;
        base.setColor(0xFF0C0C0E);
        image.setAlpha(209);                 // same 0.82 as the wallpaper view
        dim.setColor(0x55000000);            // same dim as the wallpaper view
        edge.setStyle(Paint.Style.STROKE);
        edge.setStrokeWidth(1.2f * density);
        edge.setColor(0x4DFFFFFF);
    }

    /** Blurred wallpaper (or null for none). Must be the same bitmap the full-screen blur uses. */
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

    @Override
    public void getItemOffsets(@NonNull Rect outRect, @NonNull View view, @NonNull RecyclerView parent,
                               @NonNull RecyclerView.State state) {
        int pos = parent.getChildAdapterPosition(view);
        if (pos >= 0 && pos < dockSize) {
            outRect.top = (int) (6 * density);
            outRect.bottom = (int) (12 * density);
        }
    }

    @Override
    public void onDraw(@NonNull Canvas c, @NonNull RecyclerView parent, @NonNull RecyclerView.State state) {
        boolean any = false;
        for (int i = 0; i < parent.getChildCount(); i++) {
            View child = parent.getChildAt(i);
            int pos = parent.getChildAdapterPosition(child);
            if (pos < 0 || pos >= dockSize) continue;
            View card = child.findViewById(R.id.card);
            if (card == null) continue;
            float l = child.getLeft() + card.getLeft();
            float t = child.getTop() + card.getTop() + child.getTranslationY();
            float r = l + card.getWidth();
            float b = t + card.getHeight();
            if (!any) {
                rect.set(l, t, r, b);
                any = true;
            } else {
                rect.union(l, t, r, b);
            }
        }
        if (!any) return;
        float pad = 14 * density;
        rect.inset(-pad, -pad);
        float radius = 26 * density;

        c.drawRoundRect(rect, radius, radius, base);
        if (backdrop != null && backdropShader != null) {
            // Map the blurred bitmap onto the screen exactly like the full-screen ImageView (centerCrop).
            float w = parent.getWidth(), h = parent.getHeight();
            float scale = Math.max(w / backdrop.getWidth(), h / backdrop.getHeight());
            float ox = (w - backdrop.getWidth() * scale) / 2f;
            float oy = (h - backdrop.getHeight() * scale) / 2f;
            matrix.setScale(scale, scale);
            matrix.postTranslate(ox, oy);
            backdropShader.setLocalMatrix(matrix);
            c.drawRoundRect(rect, radius, radius, image);
            c.drawRoundRect(rect, radius, radius, dim);
        }
        // Frosted veil: brighter at the top edge, fading down.
        sheen.setShader(new LinearGradient(0, rect.top, 0, rect.bottom, 0x40FFFFFF, 0x14FFFFFF, Shader.TileMode.CLAMP));
        c.drawRoundRect(rect, radius, radius, sheen);
        c.drawRoundRect(rect, radius, radius, edge);
    }
}
