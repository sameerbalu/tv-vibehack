package com.gathin.tvhome;

import android.app.Dialog;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Build;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * The settings screen: a full-screen frosted overlay with one big glass sheet in the middle, like the iOS
 * Control Center. Two live modules on top (Storage: tap to clear the cache, Memory: tap to stop background
 * apps) and a grid of glass tiles below. Everything eases in; tiles pop one after another.
 */
final class ControlCenter {

    static final class Tile {
        final int icon;
        final String label;
        final Runnable action;

        Tile(int icon, String label, Runnable action) {
            this.icon = icon;
            this.label = label;
            this.action = action;
        }
    }

    private final Context ctx;
    private final float d;
    private final Bitmap backdrop;
    private final List<PillIcon> icons = new ArrayList<>();
    private Dialog dialog;

    ControlCenter(Context ctx, Bitmap backdrop) {
        this.ctx = ctx;
        this.d = ctx.getResources().getDisplayMetrics().density;
        this.backdrop = backdrop;
    }

    boolean isShowing() {
        return dialog != null && dialog.isShowing();
    }

    void dismiss() {
        if (dialog != null && dialog.isShowing()) dialog.dismiss();
    }

    /**
     * @param storage  {total, free} bytes, or null
     * @param memory   {total, available} bytes, or null
     */
    void show(String title, List<Tile> tiles, String storageLabel, long[] storage, Runnable onStorage,
              String memoryLabel, long[] memory, Runnable onMemory) {
        dialog = new Dialog(ctx, android.R.style.Theme_Translucent_NoTitleBar_Fullscreen);
        Window w = dialog.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
            w.setWindowAnimations(0);
            w.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        }
        FrameLayout root = new FrameLayout(ctx);
        root.setClipChildren(false);

        // Backdrop: the blurred wallpaper, dimmed.
        final ImageView bg = new ImageView(ctx);
        bg.setScaleType(ImageView.ScaleType.CENTER_CROP);
        if (backdrop != null) bg.setImageBitmap(backdrop);
        root.addView(bg, new FrameLayout.LayoutParams(-1, -1));
        final View scrim = new View(ctx);
        scrim.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{backdrop != null ? 0x99000000 : 0xF2101114, backdrop != null ? 0xCC000000 : 0xF2050506}));
        root.addView(scrim, new FrameLayout.LayoutParams(-1, -1));
        bg.setAlpha(0f);
        scrim.setAlpha(0f);
        bg.animate().alpha(1f).setDuration(320).start();
        scrim.animate().alpha(1f).setDuration(320).start();

        // The glass sheet.
        final LinearLayout sheet = new LinearLayout(ctx);
        sheet.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (26 * d);
        sheet.setPadding(pad, (int) (22 * d), pad, pad);
        sheet.setBackground(sheetBackground());
        sheet.setClipChildren(false);
        sheet.setClipToPadding(false);
        FrameLayout.LayoutParams slp = new FrameLayout.LayoutParams((int) (760 * d), ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER);
        root.addView(sheet, slp);

        TextView head = text(title, 20f, 600, 0xFFFFFFFF);
        head.setPadding((int) (6 * d), 0, 0, (int) (14 * d));
        sheet.addView(head);

        final List<View> animate = new ArrayList<>();
        View firstFocus = null;

        // Live modules.
        LinearLayout mods = new LinearLayout(ctx);
        mods.setOrientation(LinearLayout.HORIZONTAL);
        sheet.addView(mods, new LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT));
        View m1 = module(storageLabel, storage, true, MenuIcons.LEVELS, onStorage);
        View m2 = module(memoryLabel, memory, false, MenuIcons.TV, onMemory);
        LinearLayout.LayoutParams ml = new LinearLayout.LayoutParams(0, (int) (96 * d), 1f);
        ml.setMarginEnd((int) (7 * d));
        mods.addView(m1, ml);
        LinearLayout.LayoutParams mr = new LinearLayout.LayoutParams(0, (int) (96 * d), 1f);
        mr.setMarginStart((int) (7 * d));
        mods.addView(m2, mr);
        animate.add(m1);
        animate.add(m2);
        firstFocus = m1;

        // Tiles, four per row.
        LinearLayout row = null;
        int cols = 4;
        for (int i = 0; i < tiles.size(); i++) {
            if (i % cols == 0) {
                row = new LinearLayout(ctx);
                row.setOrientation(LinearLayout.HORIZONTAL);
                LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT);
                rp.topMargin = (int) (14 * d);
                sheet.addView(row, rp);
            }
            View t = tile(tiles.get(i));
            LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(0, (int) (104 * d), 1f);
            tp.setMargins((int) (7 * d), 0, (int) (7 * d), 0);
            row.addView(t, tp);
            animate.add(t);
        }
        // Keep the last row's tiles the same width as the others.
        if (row != null) {
            for (int k = row.getChildCount(); k < cols; k++) {
                View pad2 = new View(ctx);
                LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(0, (int) (104 * d), 1f);
                tp.setMargins((int) (7 * d), 0, (int) (7 * d), 0);
                row.addView(pad2, tp);
            }
        }

        dialog.setContentView(root);
        dialog.setCanceledOnTouchOutside(true);
        dialog.setOnDismissListener(di -> {
            for (PillIcon p : icons) p.rest();
            icons.clear();
        });
        dialog.show();

        // Entrance: the sheet rises and settles, then the tiles pop in one by one.
        sheet.setAlpha(0f);
        sheet.setScaleX(0.93f);
        sheet.setScaleY(0.93f);
        sheet.setTranslationY(28 * d);
        sheet.animate().alpha(1f).scaleX(1f).scaleY(1f).translationY(0f).setDuration(380)
                .setInterpolator(new DecelerateInterpolator(1.8f)).start();
        for (int i = 0; i < animate.size(); i++) {
            View v = animate.get(i);
            v.setAlpha(0f);
            v.setScaleX(0.85f);
            v.setScaleY(0.85f);
            v.animate().alpha(1f).scaleX(1f).scaleY(1f).setStartDelay(120L + i * 38L).setDuration(330)
                    .setInterpolator(new OvershootInterpolator(1.3f)).start();
        }
        if (firstFocus != null) firstFocus.requestFocus();
    }

    // ---- pieces ---------------------------------------------------------------------------------------

    private Drawable_ sheetBackground() {
        return new Drawable_(ctx, backdrop, 34f);
    }

    /** The glass sheet: the blurred wallpaper again under a tint, with the rim and top highlight of GlassStyle. */
    private static final class Drawable_ extends android.graphics.drawable.Drawable {
        private final GlassStyle style;
        private final float density, radius;
        private final android.graphics.RectF rect = new android.graphics.RectF();
        private final Bitmap bd;

        Drawable_(Context c, Bitmap backdrop, float radiusDp) {
            density = c.getResources().getDisplayMetrics().density;
            radius = radiusDp * density;
            bd = backdrop;
            style = new GlassStyle(density, 0x66000000, 0x26FFFFFF, 0x0CFFFFFF, 0x40FFFFFF);
            style.setBackdrop(backdrop);
        }

        @Override
        public void draw(android.graphics.Canvas c) {
            android.graphics.Rect b = getBounds();
            rect.set(b.left + density, b.top + density, b.right - density, b.bottom - density);
            android.util.DisplayMetrics dm = Resources_.dm();
            style.draw(c, rect, radius, dm.widthPixels, dm.heightPixels,
                    (dm.widthPixels - b.width()) / 2f, (dm.heightPixels - b.height()) / 2f);
        }

        @Override
        public void setAlpha(int alpha) {
        }

        @Override
        public void setColorFilter(android.graphics.ColorFilter colorFilter) {
        }

        @Override
        public int getOpacity() {
            return android.graphics.PixelFormat.TRANSLUCENT;
        }
    }

    private static final class Resources_ {
        static android.util.DisplayMetrics dm() {
            return android.content.res.Resources.getSystem().getDisplayMetrics();
        }
    }

    private TextView text(String s, float sp, int weight, int color) {
        TextView tv = new TextView(ctx);
        tv.setText(s);
        tv.setTextSize(sp);
        tv.setTextColor(color);
        tv.setSingleLine(true);
        if (Build.VERSION.SDK_INT >= 26) {
            try {
                tv.setTypeface(ctx.getResources().getFont(R.font.outfit));
                tv.setFontVariationSettings("'wght' " + weight);
            } catch (RuntimeException ignored) {
                tv.setTypeface(Typeface.DEFAULT_BOLD);
            }
        }
        return tv;
    }

    private StateListDrawable glassTile(float radiusDp) {
        StateListDrawable sl = new StateListDrawable();
        sl.addState(new int[]{android.R.attr.state_focused}, shape(radiusDp, 0x8CFFFFFF, 0x4AFFFFFF, 0xE0FFFFFF, 1.6f));
        sl.addState(new int[0], shape(radiusDp, 0x3AFFFFFF, 0x14FFFFFF, 0x4DFFFFFF, 1f));
        return sl;
    }

    private GradientDrawable shape(float radiusDp, int top, int bottom, int stroke, float strokeDp) {
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{top, bottom});
        g.setCornerRadius(radiusDp * d);
        g.setStroke(Math.max(1, Math.round(strokeDp * d)), stroke);
        return g;
    }

    private void focusFx(final View v, final PillIcon icon) {
        v.setOnFocusChangeListener((view, f) -> {
            view.animate().scaleX(f ? 1.06f : 1f).scaleY(f ? 1.06f : 1f).translationZ(f ? 10 * d : 0f)
                    .setDuration(f ? 240 : 160).setInterpolator(new OvershootInterpolator(f ? 1.5f : 0f)).start();
            if (icon != null) icon.setFocused(f);
        });
    }

    private View tile(final Tile t) {
        LinearLayout v = new LinearLayout(ctx);
        v.setOrientation(LinearLayout.VERTICAL);
        v.setGravity(Gravity.CENTER);
        v.setBackground(glassTile(24f));
        v.setFocusable(true);
        v.setClickable(true);
        v.setElevation(4 * d);
        ImageView iv = new ImageView(ctx);
        PillIcon icon = MenuIcons.make(t.icon, d);
        icons.add(icon);
        iv.setImageDrawable(icon);
        v.addView(iv, new LinearLayout.LayoutParams((int) (34 * d), (int) (34 * d)));
        TextView tv = text(t.label, 13f, 500, 0xFFFFFFFF);
        tv.setGravity(Gravity.CENTER);
        tv.setEllipsize(android.text.TextUtils.TruncateAt.END);
        tv.setPadding((int) (6 * d), (int) (9 * d), (int) (6 * d), 0);
        v.addView(tv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        focusFx(v, icon);
        v.setOnClickListener(view -> {
            dismiss();
            if (t.action != null) view.postDelayed(t.action, 140);
        });
        return v;
    }

    private View module(String label, long[] stat, boolean storage, int iconKind, final Runnable action) {
        LinearLayout v = new LinearLayout(ctx);
        v.setOrientation(LinearLayout.VERTICAL);
        v.setGravity(Gravity.CENTER_VERTICAL);
        v.setPadding((int) (18 * d), 0, (int) (18 * d), 0);
        v.setBackground(glassTile(26f));
        v.setFocusable(true);
        v.setClickable(true);
        v.setElevation(4 * d);

        LinearLayout top = new LinearLayout(ctx);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        ImageView iv = new ImageView(ctx);
        PillIcon icon = MenuIcons.make(iconKind, d);
        icons.add(icon);
        iv.setImageDrawable(icon);
        top.addView(iv, new LinearLayout.LayoutParams((int) (26 * d), (int) (26 * d)));
        TextView name = text(label, 14f, 600, 0xFFFFFFFF);
        name.setPadding((int) (10 * d), 0, 0, 0);
        top.addView(name, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        String value = "";
        float used = 0f;
        if (stat != null && stat[0] > 0) {
            long free = stat[1];
            used = 1f - free / (float) stat[0];
            value = ctx.getString(R.string.tc_free, TvTools.fmt(free));
        }
        TextView val = text(value, 12f, 500, 0xB3FFFFFF);
        top.addView(val);
        v.addView(top, new LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT));

        ColorBar bar = new ColorBar(ctx);
        bar.set(Math.max(0.02f, Math.min(1f, used)), 0xFFFFFFFF, 0xFFBFD9FF);
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(-1, (int) (6 * d));
        bp.topMargin = (int) (14 * d);
        v.addView(bar, bp);

        focusFx(v, icon);
        v.setOnClickListener(view -> {
            dismiss();
            if (action != null) view.postDelayed(action, 140);
        });
        return v;
    }
}
