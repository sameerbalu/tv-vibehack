package com.gathin.tvhome;

import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

/**
 * A second, darker blurred panel with rounded corners behind the apps of the All apps section (the rows after
 * the dock). It is one rectangle from the top of the first app row to the bottom of the last app row, always
 * whole rows and always the full width, so a partly filled last row still gets a complete rectangle. It is drawn
 * by the grid itself, so it scrolls with the apps, is cut by the grid's clip (inside the glass panel), and never
 * touches the dock or the Continue watching row, which sit above the first app row.
 */
final class AppsPanelDecoration extends RecyclerView.ItemDecoration {

    /** Card bottom -> bottom of the app name; the same amount is added above so the panel is even all round. */
    static final int TITLE_DP = 0;
    private final int columns;
    private final float d;
    private final Paint base = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint image = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint dim = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint edge = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Matrix matrix = new Matrix();
    private final RectF rect = new RectF();
    private Bitmap backdrop;
    private BitmapShader shader;
    private float alpha;                 // 0 = hidden, 1 = shown (eased by the activity)
    private int lastRows = -1;           // rows at the last drawn frame; -1 while hidden
    private float rowPitch;              // distance from one row to the next, measured when it can be
    private float extra;                 // eased offset of the bottom edge after rows were added or removed
    private final RectF glass = new RectF();         // the visible glass panel behind everything
    private float margin;                // distance of the dark panel from the glass edges, on every side
    private float innerGap;              // distance from the apps to the dark panel's top and bottom edge

    AppsPanelDecoration(int columns, float density) {
        this.columns = columns;
        this.d = density;
        edge.setStyle(Paint.Style.STROKE);
        edge.setStrokeWidth(1.2f * density);
        rowPitch = 153f * density;
    }

    void setBackdrop(Bitmap blurred) {
        backdrop = blurred;
        if (blurred == null) {
            shader = null;
            image.setShader(null);
        } else {
            shader = new BitmapShader(blurred, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
            image.setShader(shader);
        }
    }

    void setAlpha(float a) {
        alpha = a;
    }

    /** The visible glass panel (grid coordinates), the gap to keep to its edges, and the gap around the apps. */
    void setGlass(float l, float t, float r, float b, float marginPx, float innerGapPx) {
        glass.set(l, t, r, b);
        margin = marginPx;
        innerGap = innerGapPx;
    }

    @Override
    public void onDraw(@NonNull Canvas c, @NonNull RecyclerView parent, @NonNull RecyclerView.State state) {
        if (alpha <= 0.01f) {
            lastRows = -1;
            extra = 0f;
            return;
        }
        RecyclerView.Adapter<?> adapter = parent.getAdapter();
        if (adapter == null) return;
        int count = adapter.getItemCount();
        if (count <= columns) {                       // nothing after the dock: no panel
            lastRows = -1;
            extra = 0f;
            return;
        }
        int rows = (count - columns + columns - 1) / columns;          // full rows, the last one may be partly filled
        int lastStart = columns + (rows - 1) * columns;

        float firstTop = Float.MAX_VALUE, lastTop = Float.MAX_VALUE, lastBottom = -Float.MAX_VALUE;
        float firstCardTop = Float.MAX_VALUE, lastCardBottom = -Float.MAX_VALUE, cardW = 0f, cardH = 0f;
        float cardsLeft = Float.NaN, cardsRight = Float.NaN;       // outer edges of the first and last column of tiles
        boolean haveFirst = false, haveLast = false;
        for (int i = 0; i < parent.getChildCount(); i++) {
            View child = parent.getChildAt(i);
            int pos = parent.getChildAdapterPosition(child);
            if (pos < columns) continue;                                // the dock row (and unbound views)
            if (Float.isNaN(cardsLeft)) {
                View card = child.findViewById(R.id.card);
                if (card != null && child.getWidth() > 0) {
                    float cell = child.getWidth();
                    float col0 = child.getLeft() + card.getLeft() - ((pos - columns) % columns) * cell;
                    cardsLeft = col0;
                    cardsRight = col0 + (columns - 1) * cell + card.getWidth();
                }
            }
            float t = child.getTop() + child.getTranslationY();
            float b = child.getBottom() + child.getTranslationY();
            if (pos < columns * 2) {
                haveFirst = true;
                firstTop = Math.min(firstTop, t);
                firstCardTop = Math.min(firstCardTop, t + child.getPaddingTop());      // where the tiles really begin
            }
            if (pos >= lastStart) {
                haveLast = true;
                lastTop = Math.min(lastTop, t);
                View card = child.findViewById(R.id.card);
                View name = child.findViewById(R.id.name);
                float cardBottom = card != null ? t + card.getBottom() : b - child.getPaddingBottom();
                if (card != null) {
                    cardW = card.getWidth();
                    cardH = card.getHeight();
                }
                // Down to the app name only when the name really takes room (it is gone in All apps).
                float bottomOfTile = name != null && name.getVisibility() == View.VISIBLE ? t + name.getBottom() : cardBottom;
                lastCardBottom = Math.max(lastCardBottom, cardBottom);
                lastBottom = Math.max(lastBottom, bottomOfTile);
            }
        }

        // Row spacing, measured from the real layout whenever both ends are on screen.
        if (haveFirst && haveLast && rows > 1) {
            rowPitch = (lastTop - firstTop) / (rows - 1);
        } else if (haveFirst && haveLast) {
            rowPitch = Math.max(rowPitch, lastBottom - firstTop);
        }
        // Apps were added, removed or hidden: start from where the bottom edge was and glide to the new place.
        if (lastRows >= 0 && rows != lastRows && haveLast) extra += (lastRows - rows) * rowPitch;
        lastRows = rows;
        if (!haveLast) {
            extra = 0f;
        } else if (Math.abs(extra) > 0.5f) {
            extra *= 0.80f;
            parent.postInvalidateOnAnimation();
        } else {
            extra = 0f;
        }

        float radius = 22 * d;
        float beyond = 3 * radius;                                      // an end that is off screen is pushed out of view
        // The focused tile grows (FOCUS_SCALE) and its cursor reaches a little further (glow). The panel keeps one
        // even gap G from that reach on all four sides, so the cursor never leaves the dark panel.
        float cl = glass.left + margin, ct = glass.top + margin, cr = glass.right - margin, cb = glass.bottom - margin;
        float grow = (AppAdapter.FOCUS_SCALE - 1f) / 2f;
        float reachX = grow * (cardW > 0 ? cardW : 180 * d) + 6 * d;             // scaled tile + the cursor's border (3dp gap + stroke)
        float reachY = grow * (cardH > 0 ? cardH : 101 * d) + 6 * d;
        float gap = 8 * d;
        if (!Float.isNaN(cardsLeft)) gap = Math.max(4 * d, Math.min(10 * d, cardsLeft - cl - reachX));
        float top = haveFirst ? firstCardTop - reachY - gap : -beyond;
        float bottom = haveLast ? lastBottom + reachY + gap + extra : parent.getHeight() + beyond;
        top = Math.min(Math.max(top, ct), cb - 2 * radius);
        bottom = Math.max(Math.min(bottom, cb), top + 2 * radius);
        float left = Float.isNaN(cardsLeft) ? cl : Math.max(cl, cardsLeft - reachX - gap);
        float right = Float.isNaN(cardsRight) ? cr : Math.min(cr, cardsRight + reachX + gap);
        rect.set(left, top, right, bottom);

        int a = Math.round(alpha * 255);
        base.setColor(0xFF0C0C0E);
        base.setAlpha(Math.round(a * (backdrop != null ? 1f : 0.88f)));
        c.drawRoundRect(rect, radius, radius, base);
        if (backdrop != null && shader != null && parent.getWidth() > 0 && parent.getHeight() > 0) {
            float sw = parent.getWidth(), sh = parent.getHeight();
            float scale = Math.max(sw / backdrop.getWidth(), sh / backdrop.getHeight());
            matrix.setScale(scale, scale);
            matrix.postTranslate((sw - backdrop.getWidth() * scale) / 2f, (sh - backdrop.getHeight() * scale) / 2f);
            shader.setLocalMatrix(matrix);
            image.setAlpha(Math.round(a * 0.75f));
            c.drawRoundRect(rect, radius, radius, image);
        }
        dim.setColor(0xB8000000);                                       // the dark that makes it read as a second layer
        dim.setAlpha(Math.round(a * 0.72f));
        c.drawRoundRect(rect, radius, radius, dim);
        edge.setColor(0x26FFFFFF);
        edge.setAlpha(Math.round(a * 0.15f));
        c.drawRoundRect(rect, radius, radius, edge);
    }
}
