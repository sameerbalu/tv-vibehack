package com.gathin.tvhome;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.util.SparseArray;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

import java.util.List;

/**
 * The current line of the song in large letters, the lines before and after it smaller and dimmer.
 * When the line changes the whole block glides up. Sits under the depth PNG in the home layout, so the
 * "character" of the wallpaper stands in front of the words.
 */
public class LyricsView extends View {

    private final float d;
    private final TextPaint paint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final SparseArray<StaticLayout> layouts = new SparseArray<>();
    private Lyrics lyrics;
    private boolean left = true;
    private int accent = 0xFFFFFFFF;
    private int cur = -1;
    private float animIdx;
    private ValueAnimator idxAnim;
    private int layoutW;
    private float textPx;

    public LyricsView(Context context, AttributeSet attrs) {
        super(context, attrs);
        d = context.getResources().getDisplayMetrics().density;
        Typeface base;
        try {
            base = getResources().getFont(R.font.outfit);
        } catch (Exception e) {
            base = Typeface.DEFAULT;
        }
        paint.setTypeface(Typeface.create(base, Typeface.BOLD));
    }

    void setSide(boolean left) {
        if (this.left == left) return;
        this.left = left;
        layouts.clear();
        invalidate();
    }

    void setAccent(int color) {
        // a lighter version of the song colour, so the words stay easy to read
        accent = Color.rgb(Color.red(color) + (255 - Color.red(color)) * 5 / 10,
                Color.green(color) + (255 - Color.green(color)) * 5 / 10,
                Color.blue(color) + (255 - Color.blue(color)) * 5 / 10);
        invalidate();
    }

    void setLyrics(Lyrics l) {
        lyrics = l;
        cur = -1;
        animIdx = 0f;
        if (idxAnim != null) idxAnim.cancel();
        layouts.clear();
        invalidate();
    }

    boolean hasLyrics() {
        return lyrics != null && !lyrics.lines.isEmpty();
    }

    /** Where the song is. Called about once a second. */
    void setPosition(long posMs, long durMs) {
        if (!hasLyrics()) return;
        List<Lyrics.Line> lines = lyrics.lines;
        int idx = 0;
        if (lyrics.synced) {
            long p = posMs + 350;                       // the words should arrive a touch before they are sung
            for (int i = 0; i < lines.size(); i++) {
                if (lines.get(i).time <= p) idx = i;
                else break;
            }
        } else if (durMs > 0) {
            idx = (int) (lines.size() * Math.min(0.999f, posMs / (float) durMs));
        }
        if (idx != cur) goTo(idx);
    }

    private void goTo(int idx) {
        boolean first = cur < 0;
        cur = idx;
        if (idxAnim != null) idxAnim.cancel();
        if (first) {
            animIdx = idx;
            invalidate();
            return;
        }
        idxAnim = ValueAnimator.ofFloat(animIdx, idx);
        idxAnim.setDuration(520);
        idxAnim.setInterpolator(new DecelerateInterpolator(2f));
        idxAnim.addUpdateListener(a -> {
            animIdx = (float) a.getAnimatedValue();
            invalidate();
        });
        idxAnim.start();
    }

    private StaticLayout layout(int i) {
        StaticLayout l = layouts.get(i);
        if (l == null) {
            String text = lyrics.lines.get(i).text;
            int w = Math.max(1, layoutW - (int) (6 * d));
            l = StaticLayout.Builder.obtain(text, 0, text.length(), paint, w)
                    .setAlignment(left ? Layout.Alignment.ALIGN_NORMAL : Layout.Alignment.ALIGN_OPPOSITE)
                    .setMaxLines(3)
                    .setEllipsize(TextUtils.TruncateAt.END)
                    .setIncludePad(false)
                    .build();
            layouts.put(i, l);
        }
        return l;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (!hasLyrics() || cur < 0) return;
        int w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0) return;
        float px = Math.max(22f, Math.min(40f, h / d / 3.1f)) * d;     // the text follows the room it has
        if (w != layoutW || Math.abs(px - textPx) > 0.5f) {
            layoutW = w;
            textPx = px;
            layouts.clear();
        }
        paint.setTextSize(textPx);
        int n = lyrics.lines.size();
        int base = (int) Math.floor(animIdx);
        float frac = animIdx - base;
        int lo = Math.max(0, base - 3), hi = Math.min(n - 1, base + 4);
        if (hi < lo) return;
        float gap = 12 * d;
        float[] centre = new float[hi - lo + 1];
        float acc = 0f;
        for (int i = lo; i <= hi; i++) {
            float lh = layout(i).getHeight();
            centre[i - lo] = acc + lh / 2f;
            acc += lh + gap;
        }
        int bi = Math.max(lo, Math.min(hi, base));
        int bj = Math.max(lo, Math.min(hi, base + 1));
        float cam = centre[bi - lo] + (centre[bj - lo] - centre[bi - lo]) * frac;
        float cy = h / 2f;
        for (int i = lo; i <= hi; i++) {
            float dist = Math.abs(i - animIdx);
            float s = 1f - 0.20f * Math.min(1f, dist);
            float a = dist < 1f ? 1f - 0.58f * dist : Math.max(0.10f, 0.42f - 0.16f * (dist - 1f));
            StaticLayout lay = layout(i);
            float lh = lay.getHeight();
            float yc = cy + centre[i - lo] - cam;
            if (yc + lh * s / 2f < 0 || yc - lh * s / 2f > h) continue;
            float mix = Math.max(0f, 1f - Math.min(1f, dist));
            paint.setColor(blend(0xFFFFFFFF, accent, mix));
            paint.setAlpha(Math.round(255 * a));
            paint.setShadowLayer(14 * d, 0f, 2 * d, 0x66000000);
            canvas.save();
            canvas.translate(0f, yc - lh / 2f);
            canvas.scale(s, s, left ? 0f : w, lh / 2f);
            lay.draw(canvas);
            canvas.restore();
        }
    }

    private static int blend(int a, int b, float t) {
        return Color.rgb((int) (Color.red(a) + (Color.red(b) - Color.red(a)) * t),
                (int) (Color.green(a) + (Color.green(b) - Color.green(a)) * t),
                (int) (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t));
    }
}
