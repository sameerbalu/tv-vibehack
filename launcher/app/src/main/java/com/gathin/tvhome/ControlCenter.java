package com.gathin.tvhome;

import android.app.Dialog;
import android.content.ComponentName;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.AttributeSet;
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
 * The settings screen, laid out like the iOS Control Center: a frosted full-screen backdrop and, floating on it,
 * round buttons and a wide pill on top, a now-playing card with two tall gauges (Storage, Memory) in the middle,
 * and a dark bar of coloured round buttons at the bottom. Every control does something when OK is pressed.
 */
final class ControlCenter {

    /** What the controls do (each may be null = hidden). */
    static final class Actions {
        Runnable wallpaper, hideApps, effects, widgets, saver, bluetooth, system, musicAccess, clearCache, stopApps;
    }

    private static final int BLACK = 0xE60B0B0D;

    private final Context ctx;
    private final float d;
    private final Bitmap backdrop;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final List<PillIcon> icons = new ArrayList<>();
    private Dialog dialog;
    private TextView hint;
    private MediaController controller;
    private ImageView playBtn;

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

    void show(Actions a, long[] storage, long[] memory) {
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

        final ImageView bg = new ImageView(ctx);
        bg.setScaleType(ImageView.ScaleType.CENTER_CROP);
        if (backdrop != null) bg.setImageBitmap(backdrop);
        root.addView(bg, new FrameLayout.LayoutParams(-1, -1));
        final View scrim = new View(ctx);
        scrim.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{backdrop != null ? 0x80000000 : 0xF2101114, backdrop != null ? 0xB3000000 : 0xF2050506}));
        root.addView(scrim, new FrameLayout.LayoutParams(-1, -1));
        bg.setAlpha(0f);
        scrim.setAlpha(0f);
        bg.animate().alpha(1f).setDuration(320).start();
        scrim.animate().alpha(1f).setDuration(320).start();
        // A tap on the empty backdrop closes it.
        root.setOnClickListener(v -> dismiss());

        final LinearLayout col = new LinearLayout(ctx);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setClipChildren(false);
        root.addView(col, new FrameLayout.LayoutParams((int) (640 * d), ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
        final List<View> pop = new ArrayList<>();

        // ---- row 1: two round buttons and a wide pill --------------------------------------------------
        LinearLayout r1 = row();
        View b1 = round(MenuIcons.WALLPAPER, 76, BLACK, a.wallpaper, ctx.getString(R.string.wallpaper_tab));
        View b2 = round(MenuIcons.EYE, 76, BLACK, a.hideApps, ctx.getString(R.string.hide_apps));
        View pill = pill(MenuIcons.SPARKLE, ctx.getString(R.string.fx_title), a.effects);
        r1.addView(b1, sq(76, 0));
        r1.addView(b2, sq(76, 14));
        LinearLayout.LayoutParams pp = new LinearLayout.LayoutParams(0, (int) (76 * d), 1f);
        pp.setMarginStart((int) (14 * d));
        r1.addView(pill, pp);
        col.addView(r1, rowLp(0));
        pop.add(b1);
        pop.add(b2);
        pop.add(pill);

        // ---- row 2: now playing + two gauges -----------------------------------------------------------
        LinearLayout r2 = row();
        View now = nowPlaying();
        LinearLayout.LayoutParams nl = new LinearLayout.LayoutParams(0, (int) (214 * d), 1.7f);
        r2.addView(now, nl);
        GaugeView g1 = gauge(ctx.getString(R.string.tc_storage), storage, a.clearCache);
        GaugeView g2 = gauge(ctx.getString(R.string.tc_memory), memory, a.stopApps);
        LinearLayout.LayoutParams g1p = new LinearLayout.LayoutParams(0, (int) (214 * d), 0.75f);
        g1p.setMarginStart((int) (14 * d));
        LinearLayout.LayoutParams g2p = new LinearLayout.LayoutParams(0, (int) (214 * d), 0.75f);
        g2p.setMarginStart((int) (14 * d));
        r2.addView(g1, g1p);
        r2.addView(g2, g2p);
        col.addView(r2, rowLp(14));
        pop.add(now);
        pop.add(g1);
        pop.add(g2);

        // ---- row 3: the dark bar of coloured round buttons ---------------------------------------------
        LinearLayout bar = new LinearLayout(ctx);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER);
        bar.setPadding((int) (14 * d), (int) (12 * d), (int) (14 * d), (int) (12 * d));
        GradientDrawable barBg = new GradientDrawable();
        barBg.setColor(BLACK);
        barBg.setCornerRadius(46 * d);
        barBg.setStroke(Math.max(1, (int) d), 0x26FFFFFF);
        bar.setBackground(barBg);
        List<View> circles = new ArrayList<>();
        circles.add(round(MenuIcons.WIDGETS, 60, 0xFFFF9500, a.widgets, ctx.getString(R.string.widgets)));
        circles.add(round(MenuIcons.MOON, 60, 0xFF0A84FF, a.saver, ctx.getString(R.string.screen_saver)));
        circles.add(round(MenuIcons.BLUETOOTH, 60, 0xFF4DA3F0, a.bluetooth, ctx.getString(R.string.bluetooth)));
        circles.add(round(MenuIcons.GEAR, 60, 0xFF34C759, a.system, ctx.getString(R.string.system_settings)));
        if (a.musicAccess != null) {
            circles.add(round(MenuIcons.BELL, 60, 0xFF8E8E93, a.musicAccess, ctx.getString(R.string.music_access)));
        }
        for (int i = 0; i < circles.size(); i++) {
            LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(0, (int) (60 * d), 1f);
            cp.gravity = Gravity.CENTER;
            FrameLayout cell = new FrameLayout(ctx);
            cell.setClipChildren(false);
            cell.addView(circles.get(i), new FrameLayout.LayoutParams((int) (60 * d), (int) (60 * d), Gravity.CENTER));
            bar.addView(cell, cp);
            pop.add(circles.get(i));
        }
        col.addView(bar, rowLp(14));

        // Name of the focused control, under everything.
        hint = new TextView(ctx);
        style(hint, 14f, 500, 0xCCFFFFFF);
        hint.setGravity(Gravity.CENTER);
        hint.setText(ctx.getString(R.string.settings));
        LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT);
        hp.topMargin = (int) (16 * d);
        col.addView(hint, hp);

        dialog.setContentView(root);
        dialog.setCanceledOnTouchOutside(true);
        dialog.setOnDismissListener(di -> {
            for (PillIcon p : icons) p.rest();
            icons.clear();
            ui.removeCallbacksAndMessages(null);
        });
        dialog.show();

        col.setAlpha(0f);
        col.setTranslationY(26 * d);
        col.animate().alpha(1f).translationY(0f).setDuration(360).setInterpolator(new DecelerateInterpolator(1.8f)).start();
        for (int i = 0; i < pop.size(); i++) {
            View v = pop.get(i);
            v.setAlpha(0f);
            v.setScaleX(0.8f);
            v.setScaleY(0.8f);
            v.animate().alpha(1f).scaleX(1f).scaleY(1f).setStartDelay(90L + i * 40L).setDuration(340)
                    .setInterpolator(new OvershootInterpolator(1.4f)).start();
        }
        b1.requestFocus();
    }

    // ---- building blocks ------------------------------------------------------------------------------

    private LinearLayout row() {
        LinearLayout r = new LinearLayout(ctx);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setClipChildren(false);
        return r;
    }

    private LinearLayout.LayoutParams rowLp(int topDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = (int) (topDp * d);
        return lp;
    }

    private LinearLayout.LayoutParams sq(int sizeDp, int startDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams((int) (sizeDp * d), (int) (sizeDp * d));
        lp.setMarginStart((int) (startDp * d));
        return lp;
    }

    private void style(TextView tv, float sp, int weight, int color) {
        tv.setTextSize(sp);
        tv.setTextColor(color);
        tv.setSingleLine(true);
        tv.setIncludeFontPadding(false);
        if (Build.VERSION.SDK_INT >= 26) {
            try {
                tv.setTypeface(ctx.getResources().getFont(R.font.outfit));
                tv.setFontVariationSettings("'wght' " + weight);
                return;
            } catch (RuntimeException ignored) {
            }
        }
        tv.setTypeface(Typeface.DEFAULT_BOLD);
    }

    private GradientDrawable fill(int color, float radiusDp, int stroke, float strokeDp, boolean oval) {
        GradientDrawable g = new GradientDrawable();
        g.setShape(oval ? GradientDrawable.OVAL : GradientDrawable.RECTANGLE);
        g.setColor(color);
        if (!oval) g.setCornerRadius(radiusDp * d);
        g.setStroke(Math.max(1, Math.round(strokeDp * d)), stroke);
        return g;
    }

    private StateListDrawable states(int color, float radiusDp, boolean oval) {
        StateListDrawable sl = new StateListDrawable();
        sl.addState(new int[]{android.R.attr.state_focused}, fill(color, radiusDp, 0xFFFFFFFF, 2.4f, oval));
        sl.addState(new int[0], fill(color, radiusDp, 0x26FFFFFF, 1f, oval));
        return sl;
    }

    private void focusFx(final View v, final PillIcon icon, final String label) {
        v.setOnFocusChangeListener((view, f) -> {
            view.animate().scaleX(f ? 1.07f : 1f).scaleY(f ? 1.07f : 1f).setDuration(f ? 240 : 160)
                    .setInterpolator(new OvershootInterpolator(f ? 1.5f : 0f)).start();
            if (icon != null) icon.setFocused(f);
            if (f && hint != null && label != null) hint.setText(label);
        });
    }

    /** Runs after the settings screen has closed. A detached view cannot post, so a Handler is used. */
    private void run(final Runnable action) {
        dismiss();
        if (action != null) new Handler(Looper.getMainLooper()).postDelayed(action, 220);
    }

    private View round(int iconKind, int sizeDp, int color, final Runnable action, String label) {
        FrameLayout v = new FrameLayout(ctx);
        v.setBackground(states(color, 0, true));
        v.setFocusable(true);
        v.setClickable(true);
        ImageView iv = new ImageView(ctx);
        PillIcon icon = MenuIcons.make(iconKind, d);
        icons.add(icon);
        iv.setImageDrawable(icon);
        int s = (int) (sizeDp * 0.42f * d);
        v.addView(iv, new FrameLayout.LayoutParams(s, s, Gravity.CENTER));
        focusFx(v, icon, label);
        v.setOnClickListener(view -> run(action));
        return v;
    }

    private View pill(int iconKind, String label, final Runnable action) {
        LinearLayout v = new LinearLayout(ctx);
        v.setOrientation(LinearLayout.HORIZONTAL);
        v.setGravity(Gravity.CENTER);
        v.setBackground(states(BLACK, 38f, false));
        v.setFocusable(true);
        v.setClickable(true);
        ImageView iv = new ImageView(ctx);
        PillIcon icon = MenuIcons.make(iconKind, d);
        icons.add(icon);
        iv.setImageDrawable(icon);
        v.addView(iv, new LinearLayout.LayoutParams((int) (28 * d), (int) (28 * d)));
        TextView tv = new TextView(ctx);
        style(tv, 17f, 600, 0xFFFFFFFF);
        tv.setText(label);
        tv.setPadding((int) (12 * d), 0, 0, 0);
        v.addView(tv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        focusFx(v, icon, label);
        v.setOnClickListener(view -> run(action));
        return v;
    }

    private GaugeView gauge(String label, long[] stat, final Runnable action) {
        float used = 0.3f;
        String value = "";
        if (stat != null && stat[0] > 0) {
            used = 1f - stat[1] / (float) stat[0];
            value = ctx.getString(R.string.tc_free, TvTools.fmt(stat[1]));
        }
        GaugeView g = new GaugeView(ctx, label, value, used);
        g.setFocusable(true);
        g.setClickable(true);
        focusFx(g, null, label);
        g.setOnClickListener(view -> run(action));
        return g;
    }

    // ---- now playing ----------------------------------------------------------------------------------

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

    private View nowPlaying() {
        controller = pickController();
        MediaMetadata m = controller == null ? null : controller.getMetadata();
        LinearLayout card = new LinearLayout(ctx);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(states(BLACK, 30f, false));
        card.setPadding((int) (16 * d), (int) (16 * d), (int) (16 * d), (int) (12 * d));
        card.setFocusable(true);
        card.setClickable(true);
        card.setClipChildren(false);

        ImageView art = new ImageView(ctx);
        art.setScaleType(ImageView.ScaleType.CENTER_CROP);
        art.setOutlineProvider(new android.view.ViewOutlineProvider() {
            @Override
            public void getOutline(View view, android.graphics.Outline o) {
                o.setRoundRect(0, 0, view.getWidth(), view.getHeight(), 14 * d);
            }
        });
        art.setClipToOutline(true);
        Bitmap bmp = m == null ? null : SongColors.artOf(m);
        if (bmp != null) art.setImageBitmap(bmp);
        else art.setBackground(fill(0xFF26262B, 14f, 0x00000000, 0f, false));
        card.addView(art, new LinearLayout.LayoutParams(-1, (int) (88 * d)));

        TextView title = new TextView(ctx);
        style(title, 15f, 600, 0xFFFFFFFF);
        title.setGravity(Gravity.CENTER);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        String t = m == null ? null : m.getString(MediaMetadata.METADATA_KEY_TITLE);
        title.setText(t != null ? t : ctx.getString(R.string.cc_nothing_playing));
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT);
        tp.topMargin = (int) (10 * d);
        card.addView(title, tp);

        TextView artist = new TextView(ctx);
        style(artist, 12f, 400, 0xB3FFFFFF);
        artist.setGravity(Gravity.CENTER);
        artist.setEllipsize(android.text.TextUtils.TruncateAt.END);
        String ar = m == null ? null : m.getString(MediaMetadata.METADATA_KEY_ARTIST);
        artist.setText(ar == null ? "" : ar);
        LinearLayout.LayoutParams ap = new LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT);
        ap.topMargin = (int) (2 * d);
        card.addView(artist, ap);

        LinearLayout ctl = new LinearLayout(ctx);
        ctl.setOrientation(LinearLayout.HORIZONTAL);
        ctl.setGravity(Gravity.CENTER);
        ImageView prev = ctlButton(R.drawable.ic_prev);
        playBtn = ctlButton(isPlaying() ? R.drawable.ic_pause : R.drawable.ic_play);
        ImageView next = ctlButton(R.drawable.ic_next);
        int sz = (int) (30 * d);
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(sz, sz);
        cp.setMargins((int) (14 * d), 0, (int) (14 * d), 0);
        ctl.addView(prev, cp);
        ctl.addView(playBtn, new LinearLayout.LayoutParams(cp));
        ctl.addView(next, new LinearLayout.LayoutParams(cp));
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT);
        clp.topMargin = (int) (8 * d);
        card.addView(ctl, clp);

        // OK on the card: play / pause. Left / Right while it is focused: previous / next track.
        card.setOnClickListener(v -> togglePlay());
        prev.setOnClickListener(v -> {
            if (controller != null) controller.getTransportControls().skipToPrevious();
        });
        next.setOnClickListener(v -> {
            if (controller != null) controller.getTransportControls().skipToNext();
        });
        playBtn.setOnClickListener(v -> togglePlay());
        card.setOnKeyListener((v, keyCode, event) -> {
            if (event.getAction() != android.view.KeyEvent.ACTION_DOWN || controller == null) return false;
            if (keyCode == android.view.KeyEvent.KEYCODE_MEDIA_NEXT) {
                controller.getTransportControls().skipToNext();
                return true;
            }
            if (keyCode == android.view.KeyEvent.KEYCODE_MEDIA_PREVIOUS) {
                controller.getTransportControls().skipToPrevious();
                return true;
            }
            return false;
        });
        focusFx(card, null, ctx.getString(R.string.cc_now_playing));
        return card;
    }

    private ImageView ctlButton(int res) {
        ImageView iv = new ImageView(ctx);
        iv.setImageResource(res);
        iv.setFocusable(true);
        iv.setClickable(true);
        iv.setPadding((int) (3 * d), (int) (3 * d), (int) (3 * d), (int) (3 * d));
        iv.setBackground(states(0x00000000, 15f, true));
        iv.setOnFocusChangeListener((view, f) -> {
            view.animate().scaleX(f ? 1.2f : 1f).scaleY(f ? 1.2f : 1f).setDuration(150).start();
            if (f && hint != null) hint.setText(ctx.getString(R.string.cc_now_playing));
        });
        return iv;
    }

    private boolean isPlaying() {
        if (controller == null) return false;
        PlaybackState st = controller.getPlaybackState();
        return st != null && st.getState() == PlaybackState.STATE_PLAYING;
    }

    private void togglePlay() {
        if (controller == null) return;
        if (isPlaying()) controller.getTransportControls().pause();
        else controller.getTransportControls().play();
        final boolean nowPlaying = !isPlaying();
        playBtn.setImageResource(nowPlaying ? R.drawable.ic_pause : R.drawable.ic_play);
    }

    // ---- a tall gauge, like the brightness / volume sliders ---------------------------------------------

    private static final class GaugeView extends View {
        private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint level = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint txt = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path clip = new Path();
        private final RectF r = new RectF();
        private final String label, value;
        private final float frac, d;
        private float shown;

        GaugeView(Context c, String label, String value, float frac) {
            super(c);
            this.d = c.getResources().getDisplayMetrics().density;
            this.label = label;
            this.value = value;
            this.frac = Math.max(0.22f, Math.min(1f, frac));
            track.setColor(BLACK);
            level.setColor(0xF2FFFFFF);
            txt.setTextAlign(Paint.Align.CENTER);
            txt.setColor(0xFF111114);
            if (Build.VERSION.SDK_INT >= 26) {
                try {
                    txt.setTypeface(c.getResources().getFont(R.font.outfit));
                } catch (RuntimeException ignored) {
                }
            }
            setBackground(null);
        }

        @Override
        protected void onAttachedToWindow() {
            super.onAttachedToWindow();
            // The level rises when the screen opens.
            android.animation.ValueAnimator a = android.animation.ValueAnimator.ofFloat(0f, 1f);
            a.setStartDelay(260);
            a.setDuration(700);
            a.setInterpolator(new DecelerateInterpolator(1.6f));
            a.addUpdateListener(an -> {
                shown = (Float) an.getAnimatedValue();
                invalidate();
            });
            a.start();
        }

        @Override
        protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight(), rad = 34 * d;
            r.set(0, 0, w, h);
            clip.reset();
            clip.addRoundRect(r, rad, rad, Path.Direction.CW);
            c.save();
            c.clipPath(clip);
            c.drawRect(r, track);
            float top = h - h * frac * shown;
            c.drawRect(0, top, w, h, level);
            c.restore();
            Paint rim = new Paint(Paint.ANTI_ALIAS_FLAG);
            rim.setStyle(Paint.Style.STROKE);
            rim.setStrokeWidth(isFocused() ? 2.4f * d : d);
            rim.setColor(isFocused() ? 0xFFFFFFFF : 0x26FFFFFF);
            float in = rim.getStrokeWidth() / 2f;
            c.drawRoundRect(in, in, w - in, h - in, rad, rad, rim);
            txt.setTextSize(15 * d);
            txt.setFakeBoldText(true);
            c.drawText(label, w / 2f, h - 46 * d, txt);
            txt.setTextSize(12 * d);
            txt.setFakeBoldText(false);
            c.drawText(value, w / 2f, h - 26 * d, txt);
        }

        @Override
        protected void onFocusChanged(boolean gained, int direction, android.graphics.Rect prev) {
            super.onFocusChanged(gained, direction, prev);
            invalidate();
        }
    }
}
