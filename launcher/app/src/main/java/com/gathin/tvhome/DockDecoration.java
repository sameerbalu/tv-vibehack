package com.gathin.tvhome;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.graphics.RectF;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

/** Draws a frosted-glass band behind the first (dock) row and adds a gap below it. */
public class DockDecoration extends RecyclerView.ItemDecoration {

    private final int dockSize;
    private final float density;
    private final GlassStyle style;
    private final RectF rect = new RectF();
    private float alpha = 1f;

    /** 1 = dock band shown, 0 = hidden (the All apps section must never show the dock). */
    void setAlpha(float a) {
        alpha = a;
    }

    DockDecoration(int dockSize, float density) {
        this.dockSize = dockSize;
        this.density = density;
        style = new GlassStyle(density, 0x4D000000, 0x10FFFFFF, 0x05FFFFFF, 0x2EFFFFFF);
    }

    /** Blurred wallpaper (or null for none). Must be the same bitmap the full-screen blur uses. */
    void setBackdrop(Bitmap blurred) {
        style.setBackdrop(blurred);
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
        if (!any || alpha <= 0.01f) return;
        float pad = 18 * density;
        rect.inset(-pad, -pad);
        int save = -1;
        if (alpha < 0.99f) {
            float m = 10 * density;
            save = c.saveLayerAlpha(rect.left - m, rect.top - m, rect.right + m, rect.bottom + m, Math.round(alpha * 255));
        }
        style.draw(c, rect, 26 * density, parent.getWidth(), parent.getHeight(), 0f, 0f);
        if (save >= 0) c.restoreToCount(save);
    }
}
