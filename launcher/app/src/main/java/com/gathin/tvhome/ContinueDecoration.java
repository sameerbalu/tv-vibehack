package com.gathin.tvhome;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

/** Its own dark panel behind the Continue watching posters, so the section reads apart from All apps. */
final class ContinueDecoration extends RecyclerView.ItemDecoration {

    interface Info {
        /** Absolute adapter position of the section's first item, or -1 when there is no section. */
        int firstPosition();
    }

    private final Info info;
    private final float d;
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint edge = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();

    ContinueDecoration(Info info, float density) {
        this.info = info;
        this.d = density;
        fill.setColor(0xF2000000);
        edge.setStyle(Paint.Style.STROKE);
        edge.setStrokeWidth(density);
        edge.setColor(0x24FFFFFF);
    }

    @Override
    public void onDraw(@NonNull Canvas c, @NonNull RecyclerView parent, @NonNull RecyclerView.State state) {
        int first = info.firstPosition();
        if (first < 0) return;
        int last = state.getItemCount() - 1;
        int top = Integer.MAX_VALUE, bottom = Integer.MIN_VALUE;
        boolean hasFirst = false, hasLast = false;
        for (int i = 0; i < parent.getChildCount(); i++) {
            View ch = parent.getChildAt(i);
            int pos = parent.getChildAdapterPosition(ch);
            if (pos < first) continue;
            top = Math.min(top, ch.getTop());
            bottom = Math.max(bottom, ch.getBottom());
            if (pos == first) hasFirst = true;
            if (pos == last) hasLast = true;
        }
        if (top == Integer.MAX_VALUE) return;
        float t = hasFirst ? top : -parent.getHeight();
        float b = hasLast ? bottom + 12 * d : parent.getHeight() * 2f;
        r.set(parent.getPaddingLeft() - 8 * d, t, parent.getWidth() - parent.getPaddingRight() + 8 * d, b);
        c.drawRoundRect(r, 26 * d, 26 * d, fill);
        c.drawRoundRect(r, 26 * d, 26 * d, edge);
    }
}
