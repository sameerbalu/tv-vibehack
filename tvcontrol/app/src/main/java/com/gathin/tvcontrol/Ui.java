package com.gathin.tvcontrol;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.Locale;

/** Small UI toolkit: dark glass look, D-pad friendly focus states. */
final class Ui {
    private Ui() {
    }

    static float density = 1f;
    static Typeface font;

    static final int BG = 0xFF0A0C11;
    static final int DIM = 0x99FFFFFF;
    static final int FAINT = 0x59FFFFFF;
    static final int ACCENT = 0xFF4DA3FF;
    static final int GOOD = 0xFF34C759;
    static final int WARN = 0xFFFF9F0A;
    static final int BAD = 0xFFFF453A;

    private static final int FOCUSED = android.R.attr.state_focused;
    private static final int SELECTED = android.R.attr.state_selected;

    static final ColorStateList TEXT = states(0xFF111111, 0xFFFFFFFF, 0xFFFFFFFF);
    static final ColorStateList TEXT_DIM = states(0xFF555555, 0xCCFFFFFF, 0x99FFFFFF);

    static void init(Context c) {
        density = c.getResources().getDisplayMetrics().density;
        try {
            font = c.getResources().getFont(R.font.outfit);
        } catch (Exception e) {
            font = Typeface.DEFAULT;
        }
    }

    static int dp(float v) {
        return Math.round(v * density);
    }

    /** Colours for: focused, selected, normal. */
    static ColorStateList states(int focused, int selected, int normal) {
        return new ColorStateList(new int[][]{{FOCUSED}, {SELECTED}, {}}, new int[]{focused, selected, normal});
    }

    static GradientDrawable rect(int fill, float radiusDp, int stroke) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(dp(radiusDp));
        if (stroke != 0) g.setStroke(Math.max(1, dp(1)), stroke);
        return g;
    }

    /** White when focused, accent-tinted when selected, soft glass otherwise. */
    static Drawable pillBg(float radiusDp) {
        StateListDrawable s = new StateListDrawable();
        s.addState(new int[]{FOCUSED}, rect(0xF5FFFFFF, radiusDp, 0));
        s.addState(new int[]{SELECTED}, rect(0x664DA3FF, radiusDp, 0x884DA3FF));
        s.addState(new int[]{}, rect(0x1FFFFFFF, radiusDp, 0x2EFFFFFF));
        return s;
    }

    static TextView text(Context c, CharSequence s, float sp, int color, int weight) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        if (font != null) t.setTypeface(font);
        try {
            t.setFontVariationSettings("'wght' " + weight);
        } catch (Exception ignored) {
        }
        t.setIncludeFontPadding(false);
        return t;
    }

    /** Makes a view focusable with a gentle scale-up on focus. */
    static void focusable(View v, float scale) {
        v.setFocusable(true);
        v.setClickable(true);
        v.setOnFocusChangeListener((view, f) ->
                view.animate().scaleX(f ? scale : 1f).scaleY(f ? scale : 1f).setDuration(140).start());
    }

    static TextView button(Context c, String label, View.OnClickListener l) {
        TextView t = text(c, label, 15, 0xFFFFFFFF, 500);
        t.setTextColor(TEXT);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(22), 0, dp(22), 0);
        t.setBackground(pillBg(22));
        t.setOnClickListener(l);
        focusable(t, 1.05f);
        return t;
    }

    static LinearLayout.LayoutParams lp(int w, int h) {
        return new LinearLayout.LayoutParams(w, h);
    }

    static LinearLayout.LayoutParams lpWeight(float weight) {
        return new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, weight);
    }

    static LinearLayout.LayoutParams margins(LinearLayout.LayoutParams p, int l, int t, int r, int b) {
        p.setMargins(dp(l), dp(t), dp(r), dp(b));
        return p;
    }

    static LinearLayout hbox(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(Gravity.CENTER_VERTICAL);
        return l;
    }

    static LinearLayout vbox(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    static LinearLayout card(Context c) {
        LinearLayout l = vbox(c);
        l.setPadding(dp(22), dp(18), dp(22), dp(20));
        l.setBackground(rect(0x14FFFFFF, 18, 0x1FFFFFFF));
        return l;
    }

    /** A scrolling page: returns the column to add content to; the ScrollView is stored in sv[0]. */
    static LinearLayout page(Context c, ScrollView[] sv, String title, String subtitle) {
        ScrollView s = new ScrollView(c);
        s.setOverScrollMode(View.OVER_SCROLL_NEVER);
        s.setVerticalScrollBarEnabled(false);
        s.setClipToPadding(false);
        s.setFillViewport(false);
        LinearLayout col = vbox(c);
        col.setPadding(dp(40), dp(34), dp(44), dp(48));
        s.addView(col, new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        sv[0] = s;
        TextView t = text(c, title, 34, 0xFFFFFFFF, 300);
        col.addView(t);
        if (subtitle != null) {
            TextView st = text(c, subtitle, 14, DIM, 400);
            col.addView(st, margins(lp(-2, -2), 0, 8, 0, 0));
        }
        return col;
    }

    static TextView sectionLabel(Context c, String s) {
        TextView t = text(c, s.toUpperCase(Locale.ROOT), 12, FAINT, 500);
        t.setLetterSpacing(0.16f);
        return t;
    }

    // ---- formatting ----------------------------------------------------------------------------

    static String bytes(long b) {
        if (b < 0) return "-";
        if (b < 1024) return b + " B";
        double v = b / 1024.0;
        String[] u = {"KB", "MB", "GB", "TB"};
        int i = 0;
        while (v >= 1024 && i < 3) {
            v /= 1024;
            i++;
        }
        return String.format(Locale.US, v >= 100 ? "%.0f %s" : v >= 10 ? "%.1f %s" : "%.2f %s", v, u[i]);
    }

    static String mbps(double bytesPerSec) {
        double m = bytesPerSec * 8 / 1_000_000.0;
        return String.format(Locale.US, m >= 10 ? "%.0f Mbps" : "%.1f Mbps", m);
    }

    static String duration(long ms) {
        long m = ms / 60000;
        if (m < 1) return "<1 min";
        if (m < 60) return m + " min";
        return (m / 60) + " h " + (m % 60) + " min";
    }

    static String ago(long t) {
        long d = System.currentTimeMillis() - t;
        long m = d / 60000;
        if (m < 1) return "just now";
        if (m < 60) return m + " min ago";
        if (m < 1440) return (m / 60) + " h ago";
        return (m / 1440) + " d ago";
    }

    // ---- widgets -------------------------------------------------------------------------------

    /** Rounded progress bar. */
    static final class Bar extends View {
        private float frac;
        private int color = ACCENT;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF r = new RectF();

        Bar(Context c) {
            super(c);
        }

        void set(float f, int col) {
            frac = Math.max(0f, Math.min(1f, f));
            color = col;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas c) {
            float h = getHeight(), w = getWidth(), rad = h / 2f;
            paint.setColor(0x26FFFFFF);
            r.set(0, 0, w, h);
            c.drawRoundRect(r, rad, rad, paint);
            if (frac <= 0f) return;
            paint.setColor(color);
            r.set(0, 0, Math.max(h, w * frac), h);
            c.drawRoundRect(r, rad, rad, paint);
        }
    }

    /** Colour for a "how full" fraction. */
    static int heat(float f) {
        return f > 0.9f ? BAD : f > 0.75f ? WARN : ACCENT;
    }

    /** A list row for an app: icon, two lines, optional bar and right-hand value. Focusable. */
    static final class AppRow extends LinearLayout {
        final ImageView icon;
        final TextView title, sub, right;
        final Bar bar;

        AppRow(Context c) {
            super(c);
            setOrientation(HORIZONTAL);
            setGravity(Gravity.CENTER_VERTICAL);
            setPadding(dp(16), dp(10), dp(18), dp(10));
            setBackground(pillBg(16));
            focusable(this, 1.02f);
            icon = new ImageView(c);
            icon.setDuplicateParentStateEnabled(true);
            addView(icon, new LayoutParams(dp(40), dp(40)));
            LinearLayout mid = vbox(c);
            mid.setDuplicateParentStateEnabled(true);
            title = text(c, "", 16, 0xFFFFFFFF, 500);
            title.setTextColor(TEXT);
            title.setSingleLine(true);
            title.setDuplicateParentStateEnabled(true);
            sub = text(c, "", 12, DIM, 400);
            sub.setTextColor(TEXT_DIM);
            sub.setSingleLine(true);
            sub.setDuplicateParentStateEnabled(true);
            bar = new Bar(c);
            bar.setVisibility(View.GONE);
            mid.addView(title);
            mid.addView(sub, margins(lp(-2, -2), 0, 3, 0, 0));
            mid.addView(bar, margins(lp(-1, dp(5)), 0, 7, 0, 0));
            LayoutParams mp = new LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            mp.setMargins(dp(14), 0, dp(14), 0);
            addView(mid, mp);
            right = text(c, "", 15, 0xFFFFFFFF, 500);
            right.setTextColor(TEXT_DIM);
            right.setDuplicateParentStateEnabled(true);
            addView(right);
        }

        AppRow bind(Sys.App a, String subtitle, String rightText, float barFrac, int barColor) {
            icon.setImageDrawable(a.icon);
            title.setText(a.label);
            sub.setText(subtitle);
            right.setText(rightText);
            if (barFrac >= 0) {
                bar.setVisibility(View.VISIBLE);
                bar.set(barFrac, barColor);
            }
            return this;
        }
    }
}
