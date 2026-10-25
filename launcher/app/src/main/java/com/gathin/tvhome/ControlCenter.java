package com.gathin.tvhome;

import android.app.Dialog;
import android.content.ComponentName;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
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

    boolean isShowing() {
        return dialog != null && dialog.isShowing();
    }

    void dismiss() {
        if (dialog != null && dialog.isShowing()) dialog.dismiss();
    }

    void setOnDismiss(final Runnable r) {
        onDismiss = r;
    }

    private Runnable onDismiss;

    void show(Actions a, Info info) {
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
        root.setOnClickListener(v -> dismiss());

        final LinearLayout card = new LinearLayout(ctx);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setClipChildren(false);
        int pad = (int) (18 * d);
        card.setPadding(pad, pad, pad, pad);
        card.setBackground(cardBackground());
        FrameLayout.LayoutParams clp = new FrameLayout.LayoutParams((int) (452 * d), ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.END);
        clp.topMargin = (int) (26 * d);
        clp.setMarginEnd((int) (40 * d));
        root.addView(card, clp);
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
                LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT);
                rp.topMargin = (int) (10 * d);
                card.addView(row, rp);
            }
            LinearLayout row = (LinearLayout) card.getChildAt(card.getChildCount() - 1);
            LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(0, (int) (80 * d), 1f);
            tp.setMarginStart(i % 3 == 0 ? 0 : (int) (10 * d));
            row.addView(tiles[i], tp);
            pop.add(tiles[i]);
            if (first == null) first = tiles[i];
        }

        // ---- system block -----------------------------------------------------------------------------
        LinearLayout sys = new LinearLayout(ctx);
        sys.setOrientation(LinearLayout.VERTICAL);
        sys.setBackground(glass(26f, false, false, false));
        sys.setPadding((int) (14 * d), (int) (10 * d), (int) (14 * d), (int) (8 * d));
        TextView st = text(ctx.getString(R.string.cc_system), 14f, 600, 0xFFFFFFFF);
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
        List<View> rb = new ArrayList<>();
        rb.add(round(MenuIcons.MOON, a.saver));
        rb.add(round(a.musicAccess != null ? MenuIcons.BELL : MenuIcons.LEVELS, a.musicAccess != null ? a.musicAccess : a.sound));
        rb.add(round(MenuIcons.TV, a.about));
        rb.add(round(MenuIcons.GEAR, a.system));
        for (View v : rb) {
            FrameLayout cell = new FrameLayout(ctx);
            cell.setClipChildren(false);
            cell.addView(v, new FrameLayout.LayoutParams((int) (48 * d), (int) (48 * d), Gravity.CENTER));
            rr.addView(cell, new LinearLayout.LayoutParams(0, (int) (48 * d), 1f));
            pop.add(v);
        }
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT);
        rlp.topMargin = (int) (12 * d);
        card.addView(rr, rlp);

        dialog.setContentView(root);
        dialog.setCanceledOnTouchOutside(true);
        dialog.setOnDismissListener(di -> {
            for (PillIcon p : icons) p.rest();
            icons.clear();
            ui.removeCallbacksAndMessages(null);
            if (onDismiss != null) onDismiss.run();
        });
        dialog.show();

        card.setPivotX(452 * d);
        card.setPivotY(0f);
        card.setAlpha(0f);
        card.setScaleX(0.9f);
        card.setScaleY(0.9f);
        card.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(340).setInterpolator(new DecelerateInterpolator(1.8f)).start();
        for (int i = 0; i < pop.size(); i++) {
            View v = pop.get(i);
            v.setAlpha(0f);
            v.setTranslationY(10 * d);
            v.animate().alpha(1f).translationY(0f).setStartDelay(80L + i * 30L).setDuration(300)
                    .setInterpolator(new DecelerateInterpolator(1.6f)).start();
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

    private GradientDrawable cardBackground() {
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0x4DFFFFFF, 0x26FFFFFF});
        g.setCornerRadius(38 * d);
        g.setStroke(Math.max(1, Math.round(1.2f * d)), 0x73FFFFFF);
        return g;
    }

    /** Glass fill: normal, focused, or "on" (light, with dark text). */
    private GradientDrawable glass(float radiusDp, boolean oval, boolean focused, boolean on) {
        int top, bottom, stroke;
        if (on) {
            top = focused ? 0xFFFFFFFF : 0xF2F2F2F4;
            bottom = focused ? 0xFFF4F4F6 : 0xE6E4E4E8;
            stroke = focused ? 0xFFFFFFFF : 0x66FFFFFF;
        } else {
            top = focused ? 0x80FFFFFF : 0x3DFFFFFF;
            bottom = focused ? 0x4DFFFFFF : 0x1FFFFFFF;
            stroke = focused ? 0xF2FFFFFF : 0x4DFFFFFF;
        }
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{top, bottom});
        g.setShape(oval ? GradientDrawable.OVAL : GradientDrawable.RECTANGLE);
        if (!oval) g.setCornerRadius(radiusDp * d);
        g.setStroke(Math.max(1, Math.round((focused ? 2f : 1f) * d)), stroke);
        return g;
    }

    private StateListDrawable states(float radiusDp, boolean oval, boolean on) {
        StateListDrawable sl = new StateListDrawable();
        sl.addState(new int[]{android.R.attr.state_focused}, glass(radiusDp, oval, true, on));
        sl.addState(new int[0], glass(radiusDp, oval, false, on));
        return sl;
    }

    private void focusFx(final View v, final PillIcon icon) {
        v.setOnFocusChangeListener((view, f) -> {
            view.animate().scaleX(f ? 1.05f : 1f).scaleY(f ? 1.05f : 1f).setDuration(f ? 220 : 150)
                    .setInterpolator(new OvershootInterpolator(f ? 1.4f : 0f)).start();
            if (icon != null) icon.setFocused(f);
        });
    }

    /** Runs after the card has closed (a detached view cannot post, so a Handler is used). */
    private void run(final Runnable action) {
        dismiss();
        if (action != null) new Handler(Looper.getMainLooper()).postDelayed(action, 220);
    }

    private View round(int iconKind, final Runnable action) {
        FrameLayout v = new FrameLayout(ctx);
        v.setBackground(states(0, true, false));
        v.setFocusable(true);
        v.setClickable(true);
        ImageView iv = new ImageView(ctx);
        PillIcon icon = MenuIcons.make(iconKind, d);
        icons.add(icon);
        iv.setImageDrawable(icon);
        int s = (int) (22 * d);
        v.addView(iv, new FrameLayout.LayoutParams(s, s, Gravity.CENTER));
        focusFx(v, icon);
        v.setOnClickListener(view -> run(action));
        return v;
    }

    /** A rounded tile: coloured badge top-left, title and value underneath. "On" tiles are light with dark text. */
    private View tile(int vectorRes, int iconKind, int badgeColor, boolean on, String title, String value, final Runnable action) {
        LinearLayout v = new LinearLayout(ctx);
        v.setOrientation(LinearLayout.VERTICAL);
        v.setGravity(Gravity.CENTER_VERTICAL);
        v.setPadding((int) (12 * d), (int) (8 * d), (int) (10 * d), (int) (8 * d));
        v.setBackground(states(26f, false, on));
        v.setFocusable(true);
        v.setClickable(true);

        FrameLayout badge = new FrameLayout(ctx);
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        bg.setColor(on ? badgeColor : 0x40FFFFFF);
        badge.setBackground(bg);
        ImageView iv = new ImageView(ctx);
        PillIcon icon = null;
        if (iconKind > 0) {
            icon = MenuIcons.make(iconKind, d);
            icons.add(icon);
            iv.setImageDrawable(icon);
        } else {
            iv.setImageResource(vectorRes);
        }
        int is = (int) (15 * d);
        badge.addView(iv, new FrameLayout.LayoutParams(is, is, Gravity.CENTER));
        v.addView(badge, new LinearLayout.LayoutParams((int) (28 * d), (int) (28 * d)));

        TextView t = text(title, 13f, 600, on ? 0xFF16161A : 0xFFFFFFFF);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT);
        tp.topMargin = (int) (8 * d);
        v.addView(t, tp);
        TextView val = text(value, 11f, 400, on ? 0xB316161A : 0xB3FFFFFF);
        LinearLayout.LayoutParams vp = new LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT);
        vp.topMargin = (int) (2 * d);
        v.addView(val, vp);
        focusFx(v, icon);
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
        focusFx(m, null);
        m.setOnClickListener(view -> run(action));
        return m;
    }

    private final class MeterRow extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF r = new RectF();
        private final String label, value;
        private final float frac;
        private float shown;

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
        }

        @Override
        protected void onAttachedToWindow() {
            super.onAttachedToWindow();
            android.animation.ValueAnimator a = android.animation.ValueAnimator.ofFloat(0f, 1f);
            a.setStartDelay(300);
            a.setDuration(650);
            a.setInterpolator(new DecelerateInterpolator(1.6f));
            a.addUpdateListener(an -> {
                shown = (Float) an.getAnimatedValue();
                invalidate();
            });
            a.start();
        }

        @Override
        protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight();
            if (isFocused()) {
                p.setStyle(Paint.Style.FILL);
                p.setColor(0x33FFFFFF);
                r.set(0, 0, w, h);
                c.drawRoundRect(r, 12 * d, 12 * d, p);
            }
            p.setStyle(Paint.Style.FILL);
            p.setTextSize(12 * d);
            p.setColor(0xCCFFFFFF);
            p.setTextAlign(Paint.Align.LEFT);
            c.drawText(label, 8 * d, h / 2f + 4 * d, p);
            float bx = 96 * d, bw = w - bx - 110 * d, bh = 6 * d, by = h / 2f - bh / 2f;
            p.setColor(0x40FFFFFF);
            r.set(bx, by, bx + bw, by + bh);
            c.drawRoundRect(r, bh / 2f, bh / 2f, p);
            p.setColor(0xFFFFFFFF);
            r.set(bx, by, bx + bw * frac * shown, by + bh);
            c.drawRoundRect(r, bh / 2f, bh / 2f, p);
            p.setTextAlign(Paint.Align.RIGHT);
            p.setColor(0xA6FFFFFF);
            p.setTextSize(11 * d);
            c.drawText(value, w - 8 * d, h / 2f + 4 * d, p);
        }

        @Override
        protected void onFocusChanged(boolean gained, int direction, android.graphics.Rect prev) {
            super.onFocusChanged(gained, direction, prev);
            invalidate();
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
        h.setPadding((int) (2 * d), (int) (2 * d), 0, (int) (4 * d));

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
        else art.setBackground(glass(12f, false, false, false));
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
        int sz = (int) (36 * d);
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
        iv.setBackground(states(0, true, false));
        iv.setOnFocusChangeListener((view, f) ->
                view.animate().scaleX(f ? 1.12f : 1f).scaleY(f ? 1.12f : 1f).setDuration(150).start());
        return iv;
    }
}
