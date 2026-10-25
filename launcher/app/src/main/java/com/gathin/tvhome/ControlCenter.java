package com.gathin.tvhome;

import android.animation.ArgbEvaluator;
import android.animation.ValueAnimator;
import android.app.Dialog;
import android.content.ComponentName;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Shader;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.graphics.drawable.StateListDrawable;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.view.animation.AccelerateInterpolator;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;

/**
 * The settings screen: one big frosted-glass card in the top-right corner (over the blurred, dimmed home screen).
 * A header (the song that is playing, or a greeting), six rounded tiles with a coloured badge, a System block with
 * live Memory / Storage bars, and a row of round buttons. Every control does something when OK is pressed.
 */
final class ControlCenter {

    /** What the controls do (each may be null = hidden). */
    static final class Actions {
        Runnable wallpaper, hideApps, effects, widgets, saver, bluetooth, wifi, system, about, sound, musicAccess,
                clearCache, stopApps;
    }

    /** Values shown on the tiles. */
    static final class Info {
        String effectsValue = "", wallpaperValue = "";
        boolean effectsOn, wallpaperOn;
        long[] storage, memory;
    }

    private final Context ctx;
    private final float d;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final List<PillIcon> icons = new ArrayList<>();
    private Dialog dialog;
    private MediaController controller;
    private ImageView playBtn;

    ControlCenter(Context ctx) {
        this.ctx = ctx;
        this.d = ctx.getResources().getDisplayMetrics().density;
    }

    private LinearLayout cardView;
    private boolean closing;

    boolean isShowing() {
        return dialog != null && dialog.isShowing();
    }

    /** Closes with a short fade-and-shrink toward the top-right corner it grew from. */
    void dismiss() {
        if (dialog == null || !dialog.isShowing() || closing) return;
        closing = true;
        if (cardView == null) {
            dialog.dismiss();
            return;
        }
        cardView.animate().cancel();
        cardView.animate().alpha(0f).scaleX(0.94f).scaleY(0.94f).translationY(-8 * d)
                .setStartDelay(0).setDuration(190).setInterpolator(new AccelerateInterpolator(1.4f))
                .withEndAction(() -> {
                    if (dialog != null && dialog.isShowing()) dialog.dismiss();
                }).start();
    }

    /** Closes at once (the app is going to the background). */
    void dismissNow() {
        closing = true;
        if (dialog != null && dialog.isShowing()) dialog.dismiss();
    }

    void setOnDismiss(final Runnable r) {
        onDismiss = r;
    }

    private Runnable onDismiss;

    void show(Actions a, Info info) {
        closing = false;
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
        root.setClipToPadding(false);
        root.setOnClickListener(v -> dismiss());

        final LinearLayout card = new LinearLayout(ctx);
        cardView = card;
        card.setOrientation(LinearLayout.VERTICAL);
        card.setClipChildren(false);
        card.setClipToPadding(false);
        int pad = (int) (18 * d);
        card.setPadding(pad, pad, pad, pad);
        card.setBackground(cardBackground());
        card.setElevation(18 * d);
        // Never wider than the screen allows (small or scaled-up displays).
        int screenW = ctx.getResources().getDisplayMetrics().widthPixels;
        int cardW = Math.min((int) (452 * d), screenW - (int) (48 * d));
        FrameLayout.LayoutParams clp = new FrameLayout.LayoutParams(cardW, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.END);
        clp.topMargin = (int) (26 * d);
        clp.setMarginEnd((int) Math.min(40 * d, Math.max(12 * d, (screenW - cardW) / 2f)));
        root.addView(card, clp);
        final int pivotW = cardW;
        final List<View> pop = new ArrayList<>();

        // ---- header ---------------------------------------------------------------------------------
        controller = pickController();
        View header = controller != null ? nowPlayingHeader() : greetingHeader();
        card.addView(header, new LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT));
        pop.add(header);

        // ---- six tiles, three per row -------------------------------------------------------------------
        boolean wifiOn = isWifiUp();
        boolean btOn = isBluetoothOn();
        View[] tiles = {
                tile(R.drawable.ic_wifi, -1, 0xFF0A84FF, wifiOn, ctx.getString(R.string.cc_wifi), wifiState(), a.wifi),
                tile(0, MenuIcons.BLUETOOTH, 0xFF0A84FF, btOn, ctx.getString(R.string.bluetooth), bluetoothState(), a.bluetooth),
                tile(0, MenuIcons.SPARKLE, 0xFFAF52DE, info.effectsOn, ctx.getString(R.string.fx_title), info.effectsValue, a.effects),
                tile(0, MenuIcons.WALLPAPER, 0xFFFF2D55, info.wallpaperOn, ctx.getString(R.string.wallpaper_tab), info.wallpaperValue, a.wallpaper),
                tile(0, MenuIcons.WIDGETS, 0xFFFF9500, true, ctx.getString(R.string.widgets), ctx.getString(R.string.cc_manage), a.widgets),
                tile(0, MenuIcons.EYE, 0xFF34C759, true, ctx.getString(R.string.hide_apps), ctx.getString(R.string.cc_manage), a.hideApps)};
        View first = null;
        for (int i = 0; i < tiles.length; i++) {
            if (i % 3 == 0) {
                LinearLayout row = new LinearLayout(ctx);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setClipChildren(false);
                row.setClipToPadding(false);
                LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT);
                rp.topMargin = (int) (10 * d);
                card.addView(row, rp);
            }
            LinearLayout row = (LinearLayout) card.getChildAt(card.getChildCount() - 1);
            // Tall enough for badge + title + value + padding, and it stretches to the tallest tile in its row.
            LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f);
            tp.setMarginStart(i % 3 == 0 ? 0 : (int) (10 * d));
            row.addView(tiles[i], tp);
            pop.add(tiles[i]);
            if (first == null) first = tiles[i];
        }

        // ---- system block -----------------------------------------------------------------------------
        LinearLayout sys = new LinearLayout(ctx);
        sys.setOrientation(LinearLayout.VERTICAL);
        sys.setBackground(glass(26f, false, false, false, 0));
        sys.setClipChildren(false);
        sys.setClipToPadding(false);
        sys.setPadding((int) (10 * d), (int) (12 * d), (int) (10 * d), (int) (8 * d));
        TextView st = text(ctx.getString(R.string.cc_system), 14f, 600, 0xFFFFFFFF);
        st.setPadding((int) (6 * d), 0, 0, (int) (2 * d));
        sys.addView(st);
        sys.addView(meter(ctx.getString(R.string.tc_memory), info.memory, a.stopApps), meterLp());
        sys.addView(meter(ctx.getString(R.string.tc_storage), info.storage, a.clearCache), meterLp());
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT);
        sp.topMargin = (int) (10 * d);
        card.addView(sys, sp);
        pop.add(sys);

        // ---- round buttons ----------------------------------------------------------------------------
        LinearLayout rr = new LinearLayout(ctx);
        rr.setOrientation(LinearLayout.HORIZONTAL);
        rr.setGravity(Gravity.CENTER);
        rr.setClipChildren(false);
        rr.setClipToPadding(false);
        List<View> rb = new ArrayList<>();
        rb.add(round(MenuIcons.MOON, a.saver));
        rb.add(round(a.musicAccess != null ? MenuIcons.BELL : MenuIcons.LEVELS, a.musicAccess != null ? a.musicAccess : a.sound));
        rb.add(round(MenuIcons.TV, a.about));
        rb.add(round(MenuIcons.GEAR, a.system));
        for (View v : rb) {
            FrameLayout cell = new FrameLayout(ctx);
            cell.setClipChildren(false);
            cell.setClipToPadding(false);
            cell.addView(v, new FrameLayout.LayoutParams((int) (48 * d), (int) (48 * d), Gravity.CENTER));
            rr.addView(cell, new LinearLayout.LayoutParams(0, (int) (52 * d), 1f));
            pop.add(v);
        }
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT);
        rlp.topMargin = (int) (12 * d);
        card.addView(rr, rlp);

        dialog.setContentView(root);
        dialog.setCanceledOnTouchOutside(true);
        dialog.setOnKeyListener((di, keyCode, event) -> {
            if (keyCode != KeyEvent.KEYCODE_BACK) return false;
            if (event.getAction() == KeyEvent.ACTION_UP) dismiss();
            return true;                                   // Back closes with the animation, not instantly
        });
        dialog.setOnDismissListener(di -> {
            cardView = null;
            for (PillIcon p : icons) p.rest();
            icons.clear();
            ui.removeCallbacksAndMessages(null);
            if (onDismiss != null) onDismiss.run();
        });
        dialog.show();

        card.setPivotX(pivotW);
        card.setPivotY(0f);
        card.setAlpha(0f);
        card.setScaleX(0.9f);
        card.setScaleY(0.9f);
        card.setTranslationY(-10 * d);
        card.animate().alpha(1f).scaleX(1f).scaleY(1f).translationY(0f).setStartDelay(0).setDuration(380)
                .setInterpolator(new DecelerateInterpolator(2f)).start();
        for (int i = 0; i < pop.size(); i++) {
            View v = pop.get(i);
            v.setAlpha(0f);
            v.setTranslationY(14 * d);
            final View pv = v;
            v.animate().alpha(1f).translationY(0f).setStartDelay(90L + i * 34L).setDuration(340)
                    .setInterpolator(new DecelerateInterpolator(1.8f))
                    .withEndAction(() -> pv.animate().setStartDelay(0)).start();
        }
        if (first != null) first.requestFocus();
    }

    // ---- live values ----------------------------------------------------------------------------------

    private NetworkInfo net() {
        try {
            ConnectivityManager cm = (ConnectivityManager) ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
            return cm == null ? null : cm.getActiveNetworkInfo();
        } catch (RuntimeException e) {
            return null;
        }
    }

    private boolean isWifiUp() {
        NetworkInfo ni = net();
        return ni != null && ni.isConnected();
    }

    private String wifiState() {
        NetworkInfo ni = net();
        if (ni == null || !ni.isConnected()) return ctx.getString(R.string.cc_offline);
        if (ni.getType() == ConnectivityManager.TYPE_ETHERNET) return ctx.getString(R.string.cc_ethernet);
        return ctx.getString(R.string.cc_connected);
    }

    private boolean isBluetoothOn() {
        try {
            android.bluetooth.BluetoothAdapter ad = android.bluetooth.BluetoothAdapter.getDefaultAdapter();
            return ad != null && ad.isEnabled();
        } catch (RuntimeException e) {
            return false;
        }
    }

    private String bluetoothState() {
        try {
            if (android.bluetooth.BluetoothAdapter.getDefaultAdapter() == null) return "";
        } catch (RuntimeException e) {
            return "";
        }
        return ctx.getString(isBluetoothOn() ? R.string.cc_on : R.string.cc_off);
    }

    // ---- drawing helpers ---------------------------------------------------------------------------------

    private TextView text(String s, float sp, int weight, int color) {
        TextView tv = new TextView(ctx);
        tv.setText(s);
        tv.setTextSize(sp);
        tv.setTextColor(color);
        tv.setSingleLine(true);
        tv.setIncludeFontPadding(false);
        tv.setEllipsize(android.text.TextUtils.TruncateAt.END);
        if (Build.VERSION.SDK_INT >= 26) {
            try {
                tv.setTypeface(ctx.getResources().getFont(R.font.outfit));
                tv.setFontVariationSettings("'wght' " + weight);
                return tv;
            } catch (RuntimeException ignored) {
            }
        }
        tv.setTypeface(Typeface.DEFAULT_BOLD);
        return tv;
    }

    private static final int INK = 0xFF16161A;          // text and icons on a light (focused / "on") fill

    /** Dark frosted glass: white text stays readable on any wallpaper. */
    private GradientDrawable cardBackground() {
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0xEB202026, 0xEB111114});
        g.setCornerRadius(38 * d);
        g.setStroke(Math.max(1, Math.round(1.1f * d)), 0x38FFFFFF);
        return g;
    }

    /**
     * Fill for a control. Normal: faint glass. "On" (Wi-Fi connected, effects running...): smoked black glass with a
     * soft glow of the control's own colour in the top-left corner. Focused: brighter, with a ring. Never white.
     */
    private Drawable glass(float radiusDp, boolean oval, boolean focused, boolean on, int accent) {
        int ringW = Math.max(1, Math.round((focused ? (on && accent != 0 ? 3f : 2f) : 1f) * d));
        if (on && accent != 0 && !oval) {
            GradientDrawable base = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                    new int[]{focused ? 0xE6000000 : 0xCC000000, focused ? 0xB8000000 : 0x99000000});
            base.setCornerRadius(radiusDp * d);
            GradientDrawable glow = new GradientDrawable();
            glow.setShape(GradientDrawable.RECTANGLE);
            glow.setGradientType(GradientDrawable.RADIAL_GRADIENT);
            glow.setGradientCenter(0.16f, 0.14f);
            glow.setGradientRadius(170 * d);
            int a1 = focused ? 0xB3 : 0x7A;
            glow.setColors(new int[]{(a1 << 24) | (accent & 0xFFFFFF), 0x00000000});
            glow.setCornerRadius(radiusDp * d);
            GradientDrawable edge = new GradientDrawable();
            edge.setShape(GradientDrawable.RECTANGLE);
            edge.setCornerRadius(radiusDp * d);
            edge.setStroke(ringW, focused ? accent : ((0x80 << 24) | (accent & 0xFFFFFF)));
            return new LayerDrawable(new Drawable[]{base, glow, edge});
        }
        int top, bottom, stroke;
        if (focused) {
            top = 0x52FFFFFF;
            bottom = 0x33FFFFFF;
            stroke = 0xE6FFFFFF;
        } else {
            top = 0x26FFFFFF;
            bottom = 0x14FFFFFF;
            stroke = 0x24FFFFFF;
        }
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{top, bottom});
        g.setShape(oval ? GradientDrawable.OVAL : GradientDrawable.RECTANGLE);
        if (!oval) g.setCornerRadius(radiusDp * d);
        // Strokes are drawn inside the bounds, so nothing is ever cut off by a parent.
        g.setStroke(ringW, stroke);
        return g;
    }

    private StateListDrawable states(float radiusDp, boolean oval, boolean on, int ring) {
        StateListDrawable sl = new StateListDrawable();
        sl.addState(new int[]{android.R.attr.state_focused}, glass(radiusDp, oval, true, on, ring));
        sl.addState(new int[0], glass(radiusDp, oval, false, on, ring));
        sl.setEnterFadeDuration(130);
        sl.setExitFadeDuration(200);
        return sl;
    }

    /** Smoothly changes a text colour (cancels a change that is still running). */
    private void tint(final TextView tv, final int to) {
        Object old = tv.getTag();
        if (old instanceof ValueAnimator) ((ValueAnimator) old).cancel();
        final int from = tv.getCurrentTextColor();
        if (from == to) return;
        ValueAnimator a = ValueAnimator.ofObject(new ArgbEvaluator(), from, to);
        a.setDuration(150);
        a.addUpdateListener(an -> tv.setTextColor((Integer) an.getAnimatedValue()));
        tv.setTag(a);
        a.start();
    }

    private void tintBadge(final GradientDrawable bg, final FrameLayout holder, final int to, final int from) {
        Object old = holder.getTag();
        if (old instanceof ValueAnimator) ((ValueAnimator) old).cancel();
        ValueAnimator a = ValueAnimator.ofObject(new ArgbEvaluator(), from, to);
        a.setDuration(170);
        a.addUpdateListener(an -> bg.setColor((Integer) an.getAnimatedValue()));
        holder.setTag(a);
        a.start();
    }

    /** Lift on focus, a quick dip while OK is held. Never changes size enough to leave the card. */
    private void scaleTo(View v, float s, boolean pop, int ms) {
        v.animate().scaleX(s).scaleY(s).setStartDelay(0).setDuration(ms)
                .setInterpolator(pop ? new OvershootInterpolator(1.3f) : new DecelerateInterpolator(1.6f)).start();
    }

    private void focusFx(final View v, final PillIcon icon, final float focusScale) {
        v.setOnFocusChangeListener((view, f) -> {
            scaleTo(view, f ? focusScale : 1f, f, f ? 240 : 170);
            if (icon != null) icon.setFocused(f);
        });
        pressFx(v, focusScale);
    }

    private void pressFx(final View v, final float focusScale) {
        v.setOnKeyListener((view, keyCode, event) -> {
            if (keyCode != KeyEvent.KEYCODE_DPAD_CENTER && keyCode != KeyEvent.KEYCODE_ENTER
                    && keyCode != KeyEvent.KEYCODE_NUMPAD_ENTER) return false;
            if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
                scaleTo(view, focusScale * 0.95f, false, 90);
            } else if (event.getAction() == KeyEvent.ACTION_UP) {
                scaleTo(view, focusScale, false, 140);
            }
            return false;                                  // the click still goes through
        });
    }

    /** Runs after the card has closed (a detached view cannot post, so a Handler is used). */
    private void run(final Runnable action) {
        dismiss();
        if (action != null) new Handler(Looper.getMainLooper()).postDelayed(action, 240);
    }

    private View round(int iconKind, final Runnable action) {
        FrameLayout v = new FrameLayout(ctx);
        v.setBackground(states(0, true, false, 0));
        v.setClipChildren(false);
        v.setFocusable(true);
        v.setClickable(true);
        ImageView iv = new ImageView(ctx);
        PillIcon icon = MenuIcons.make(iconKind, d);
        icons.add(icon);
        iv.setImageDrawable(icon);
        int s = (int) (22 * d);
        v.addView(iv, new FrameLayout.LayoutParams(s, s, Gravity.CENTER));
        focusFx(v, icon, 1.1f);
        v.setOnClickListener(view -> run(action));
        return v;
    }

    /** A rounded tile: coloured badge top-left, title and value underneath. "On" tiles are light with dark text. */
    private View tile(int vectorRes, int iconKind, final int badgeColor, final boolean on, String title, String value,
                      final Runnable action) {
        LinearLayout v = new LinearLayout(ctx);
        v.setOrientation(LinearLayout.VERTICAL);
        v.setGravity(Gravity.CENTER_VERTICAL);
        v.setClipChildren(false);
        v.setClipToPadding(false);
        v.setMinimumHeight((int) (96 * d));
        v.setPadding((int) (14 * d), (int) (12 * d), (int) (12 * d), (int) (12 * d));
        v.setBackground(states(26f, false, on, badgeColor));
        v.setFocusable(true);
        v.setClickable(true);

        final FrameLayout badge = new FrameLayout(ctx);
        final GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        bg.setColor(on ? badgeColor : 0x40FFFFFF);
        badge.setBackground(bg);
        ImageView iv = new ImageView(ctx);
        PillIcon icon = null;
        if (iconKind > 0) {
            icon = MenuIcons.make(iconKind, d);            // white on the coloured badge
            icons.add(icon);
            iv.setImageDrawable(icon);
        } else {
            iv.setImageResource(vectorRes);
        }
        int is = (int) (16 * d);
        badge.addView(iv, new FrameLayout.LayoutParams(is, is, Gravity.CENTER));
        v.addView(badge, new LinearLayout.LayoutParams((int) (30 * d), (int) (30 * d)));

        final int titleRest = 0xFFFFFFFF;
        final int valueRest = on ? 0xCCFFFFFF : 0xB3FFFFFF;
        final TextView t = text(title, 13.5f, 600, titleRest);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT);
        tp.topMargin = (int) (10 * d);
        v.addView(t, tp);
        final TextView val = text(value, 11.5f, 400, valueRest);
        LinearLayout.LayoutParams vp = new LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT);
        vp.topMargin = (int) (3 * d);
        v.addView(val, vp);

        final PillIcon fi = icon;
        v.setOnFocusChangeListener((view, f) -> {
            scaleTo(view, f ? 1.045f : 1f, f, f ? 240 : 170);
            if (fi != null) fi.setFocused(f);
            tint(t, titleRest);
            tint(val, f ? 0xE6FFFFFF : valueRest);
            // An "off" tile shows its colour while focused.
            int to = (f || on) ? badgeColor : 0x40FFFFFF;
            int from = (f || on) ? (on ? badgeColor : 0x40FFFFFF) : badgeColor;
            tintBadge(bg, badge, to, from);
        });
        pressFx(v, 1.045f);
        v.setOnClickListener(view -> run(action));
        return v;
    }

    // ---- System block rows --------------------------------------------------------------------------------

    private LinearLayout.LayoutParams meterLp() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, (int) (32 * d));
        lp.topMargin = (int) (4 * d);
        return lp;
    }

    private View meter(String label, long[] stat, final Runnable action) {
        float used = 0f;
        String value = "";
        if (stat != null && stat[0] > 0) {
            used = 1f - stat[1] / (float) stat[0];
            value = ctx.getString(R.string.tc_free, TvTools.fmt(stat[1]));
        }
        final MeterRow m = new MeterRow(ctx, label, value, used);
        m.setFocusable(true);
        m.setClickable(true);
        m.setOnClickListener(view -> run(action));
        return m;
    }

    /** One System row: label, a dark-red "used" bar, and what is free. The focus fill stays inside its own bounds. */
    private final class MeterRow extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF r = new RectF();
        private final String label, value;
        private final float frac;
        private float shown, focusAmt;
        private LinearGradient shader;
        private float shaderX = -1, shaderW = -1;

        MeterRow(Context c, String label, String value, float frac) {
            super(c);
            this.label = label;
            this.value = value;
            this.frac = Math.max(0.03f, Math.min(1f, frac));
            if (Build.VERSION.SDK_INT >= 26) {
                try {
                    p.setTypeface(c.getResources().getFont(R.font.outfit));
                } catch (RuntimeException ignored) {
                }
            }
            setOnKeyListener((view, keyCode, event) -> {
                if (keyCode != KeyEvent.KEYCODE_DPAD_CENTER && keyCode != KeyEvent.KEYCODE_ENTER) return false;
                if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
                    animate().scaleX(0.985f).scaleY(0.985f).setStartDelay(0).setDuration(90).start();
                } else if (event.getAction() == KeyEvent.ACTION_UP) {
                    animate().scaleX(1f).scaleY(1f).setStartDelay(0).setDuration(140).start();
                }
                return false;
            });
        }

        @Override
        protected void onAttachedToWindow() {
            super.onAttachedToWindow();
            ValueAnimator a = ValueAnimator.ofFloat(0f, 1f);
            a.setStartDelay(380);
            a.setDuration(850);
            a.setInterpolator(new DecelerateInterpolator(2f));
            a.addUpdateListener(an -> {
                shown = (Float) an.getAnimatedValue();
                invalidate();
            });
            a.start();
        }

        @Override
        protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight();
            float inset = 1.2f * d;
            if (focusAmt > 0.01f) {
                r.set(inset, inset, w - inset, h - inset);
                p.setShader(null);
                p.setStyle(Paint.Style.FILL);
                p.setColor(Color.argb((int) (0x33 * focusAmt), 255, 255, 255));
                c.drawRoundRect(r, 14 * d, 14 * d, p);
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(1.6f * d);
                p.setColor(Color.argb((int) (0xCC * focusAmt), 255, 255, 255));
                c.drawRoundRect(r, 14 * d, 14 * d, p);
            }
            p.setShader(null);
            p.setStyle(Paint.Style.FILL);
            float padL = 12 * d, padR = 12 * d;
            Paint.FontMetrics fm = new Paint.FontMetrics();

            // label (fixed column so both bars line up)
            p.setTextSize(12.5f * d);
            p.setTextAlign(Paint.Align.LEFT);
            p.setColor(Color.argb(0xCC + (int) (0x33 * focusAmt), 255, 255, 255));
            p.getFontMetrics(fm);
            float baseline = h / 2f - (fm.ascent + fm.descent) / 2f;
            float labelW = 62 * d;
            String lbl = android.text.TextUtils.ellipsize(label, new android.text.TextPaint(p), labelW,
                    android.text.TextUtils.TruncateAt.END).toString();
            c.drawText(lbl, padL, baseline, p);

            // value on the right
            p.setTextSize(11.5f * d);
            p.setTextAlign(Paint.Align.RIGHT);
            p.setColor(Color.argb(0xB3 + (int) (0x2A * focusAmt), 255, 255, 255));
            p.getFontMetrics(fm);
            float vBase = h / 2f - (fm.ascent + fm.descent) / 2f;
            float valueW = Math.max(p.measureText(value), 60 * d);
            c.drawText(value, w - padR, vBase, p);

            // bar
            float bx = padL + labelW + 8 * d;
            float bw = Math.max(20 * d, w - bx - padR - valueW - 12 * d);
            float bh = 7 * d, by = h / 2f - bh / 2f;
            p.setTextAlign(Paint.Align.LEFT);
            p.setColor(0x33FFFFFF);
            r.set(bx, by, bx + bw, by + bh);
            c.drawRoundRect(r, bh / 2f, bh / 2f, p);
            if (shader == null || shaderX != bx || shaderW != bw) {
                shader = new LinearGradient(bx, 0, bx + bw, 0, 0xFF7A0C14, 0xFFC8202C, Shader.TileMode.CLAMP);
                shaderX = bx;
                shaderW = bw;
            }
            p.setShader(shader);
            r.set(bx, by, bx + Math.max(bh, bw * frac * shown), by + bh);
            c.drawRoundRect(r, bh / 2f, bh / 2f, p);
            p.setShader(null);
        }

        @Override
        protected void onFocusChanged(boolean gained, int direction, android.graphics.Rect prev) {
            super.onFocusChanged(gained, direction, prev);
            ValueAnimator a = ValueAnimator.ofFloat(focusAmt, gained ? 1f : 0f);
            a.setDuration(gained ? 170 : 220);
            a.addUpdateListener(an -> {
                focusAmt = (Float) an.getAnimatedValue();
                invalidate();
            });
            a.start();
        }
    }

    // ---- header: greeting or the song that is playing ---------------------------------------------------------

    private View greetingHeader() {
        LinearLayout h = new LinearLayout(ctx);
        h.setOrientation(LinearLayout.VERTICAL);
        h.setPadding((int) (6 * d), (int) (2 * d), 0, (int) (4 * d));
        int hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        int res = hour < 5 ? R.string.good_night : hour < 12 ? R.string.good_morning : hour < 17 ? R.string.good_afternoon
                : hour < 22 ? R.string.good_evening : R.string.good_night;
        h.addView(text(ctx.getString(res), 30f, 700, 0xFFFFFFFF));
        TextView sub = text(ctx.getString(R.string.cc_title), 14f, 400, 0xB3FFFFFF);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.topMargin = (int) (4 * d);
        h.addView(sub, lp);
        return h;
    }

    private MediaController pickController() {
        try {
            MediaSessionManager sm = (MediaSessionManager) ctx.getSystemService(Context.MEDIA_SESSION_SERVICE);
            List<MediaController> list = sm.getActiveSessions(new ComponentName(ctx, MediaListener.class));
            MediaController paused = null;
            for (MediaController c : list) {
                PlaybackState st = c.getPlaybackState();
                if (st == null) continue;
                if (st.getState() == PlaybackState.STATE_PLAYING) return c;
                if (paused == null && st.getState() == PlaybackState.STATE_PAUSED) paused = c;
            }
            return paused;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private boolean isPlaying() {
        if (controller == null) return false;
        PlaybackState st = controller.getPlaybackState();
        return st != null && st.getState() == PlaybackState.STATE_PLAYING;
    }

    private View nowPlayingHeader() {
        MediaMetadata m = controller.getMetadata();
        LinearLayout h = new LinearLayout(ctx);
        h.setOrientation(LinearLayout.HORIZONTAL);
        h.setGravity(Gravity.CENTER_VERTICAL);
        h.setClipChildren(false);
        h.setClipToPadding(false);
        h.setPadding((int) (2 * d), (int) (4 * d), (int) (6 * d), (int) (6 * d));

        ImageView art = new ImageView(ctx);
        art.setScaleType(ImageView.ScaleType.CENTER_CROP);
        art.setOutlineProvider(new android.view.ViewOutlineProvider() {
            @Override
            public void getOutline(View view, android.graphics.Outline o) {
                o.setRoundRect(0, 0, view.getWidth(), view.getHeight(), 12 * d);
            }
        });
        art.setClipToOutline(true);
        Bitmap bmp = m == null ? null : SongColors.artOf(m);
        if (bmp != null) art.setImageBitmap(bmp);
        else art.setBackground(glass(12f, false, false, false, 0));
        h.addView(art, new LinearLayout.LayoutParams((int) (56 * d), (int) (56 * d)));

        LinearLayout col = new LinearLayout(ctx);
        col.setOrientation(LinearLayout.VERTICAL);
        String t = m == null ? null : m.getString(MediaMetadata.METADATA_KEY_TITLE);
        String ar = m == null ? null : m.getString(MediaMetadata.METADATA_KEY_ARTIST);
        col.addView(text(t != null ? t : ctx.getString(R.string.cc_idle), 16f, 600, 0xFFFFFFFF));
        LinearLayout.LayoutParams ap = new LinearLayout.LayoutParams(-2, -2);
        ap.topMargin = (int) (3 * d);
        col.addView(text(ar == null ? ctx.getString(R.string.cc_now_playing) : ar, 12f, 400, 0xB3FFFFFF), ap);
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        cp.setMarginStart((int) (12 * d));
        h.addView(col, cp);

        ImageView prev = ctl(R.drawable.ic_prev);
        playBtn = ctl(isPlaying() ? R.drawable.ic_pause : R.drawable.ic_play);
        ImageView next = ctl(R.drawable.ic_next);
        prev.setOnClickListener(v -> controller.getTransportControls().skipToPrevious());
        next.setOnClickListener(v -> controller.getTransportControls().skipToNext());
        playBtn.setOnClickListener(v -> {
            if (isPlaying()) controller.getTransportControls().pause();
            else controller.getTransportControls().play();
            playBtn.setImageResource(isPlaying() ? R.drawable.ic_play : R.drawable.ic_pause);   // state flips after this call
        });
        int sz = (int) (38 * d);
        h.addView(prev, new LinearLayout.LayoutParams(sz, sz));
        LinearLayout.LayoutParams mp = new LinearLayout.LayoutParams(sz, sz);
        mp.setMarginStart((int) (4 * d));
        h.addView(playBtn, mp);
        LinearLayout.LayoutParams np = new LinearLayout.LayoutParams(sz, sz);
        np.setMarginStart((int) (4 * d));
        h.addView(next, np);
        return h;
    }

    private ImageView ctl(int res) {
        ImageView iv = new ImageView(ctx);
        iv.setImageResource(res);
        iv.setFocusable(true);
        iv.setClickable(true);
        int p = (int) (8 * d);
        iv.setPadding(p, p, p, p);
        iv.setBackground(states(0, true, false, 0));
        iv.setImageTintList(ColorStateList.valueOf(0xFFFFFFFF));
        focusFx(iv, null, 1.08f);
        return iv;
    }
}
