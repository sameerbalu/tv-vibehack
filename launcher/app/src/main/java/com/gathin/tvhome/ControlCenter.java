package com.gathin.tvhome;

import android.app.Dialog;
import android.content.ComponentName;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.media.AudioManager;
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
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * The settings screen: a compact control panel in the top-right corner over the home screen, in frosted-glass
 * pills. Small round buttons on top, two-line pills (title + live value), a now-playing pill, a row of round
 * buttons and a volume slider. Every control does something when OK is pressed.
 */
final class ControlCenter {

    /** What the controls do (each may be null = hidden). */
    static final class Actions {
        Runnable wallpaper, hideApps, effects, widgets, saver, bluetooth, wifi, system, musicAccess, clearCache, stopApps;
    }

    private final Context ctx;
    private final float d;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final List<PillIcon> icons = new ArrayList<>();
    private Dialog dialog;
    private TextView hint;
    private MediaController controller;
    private SliderPill volume;
    private AudioManager audio;

    ControlCenter(Context ctx, android.graphics.Bitmap backdrop) {
        this.ctx = ctx;
        this.d = ctx.getResources().getDisplayMetrics().density;
    }

    boolean isShowing() {
        return dialog != null && dialog.isShowing();
    }

    void dismiss() {
        if (dialog != null && dialog.isShowing()) dialog.dismiss();
    }

    void show(Actions a, long[] storage, long[] memory) {
        audio = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
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
        final View scrim = new View(ctx);
        scrim.setBackgroundColor(0x59000000);
        root.addView(scrim, new FrameLayout.LayoutParams(-1, -1));
        scrim.setAlpha(0f);
        scrim.animate().alpha(1f).setDuration(260).start();
        root.setOnClickListener(v -> dismiss());

        final LinearLayout col = new LinearLayout(ctx);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setClipChildren(false);
        FrameLayout.LayoutParams clp = new FrameLayout.LayoutParams((int) (350 * d), ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.END);
        clp.topMargin = (int) (26 * d);
        clp.setMarginEnd((int) (50 * d));
        root.addView(col, clp);
        final List<View> pop = new ArrayList<>();

        // small round buttons, right aligned
        LinearLayout top = row();
        top.setGravity(Gravity.END);
        List<View> small = new ArrayList<>();
        small.add(round(MenuIcons.MOON, 40, a.saver, ctx.getString(R.string.screen_saver)));
        small.add(round(MenuIcons.GEAR, 40, a.system, ctx.getString(R.string.system_settings)));
        if (a.musicAccess != null) small.add(round(MenuIcons.BELL, 40, a.musicAccess, ctx.getString(R.string.music_access)));
        for (View v : small) {
            top.addView(v, sq(40, 10));
            pop.add(v);
        }
        col.addView(top, rowLp(0));

        // two-line pills
        String stV = storage != null && storage[0] > 0 ? ctx.getString(R.string.tc_free, TvTools.fmt(storage[1])) : "";
        String meV = memory != null && memory[0] > 0 ? ctx.getString(R.string.tc_free, TvTools.fmt(memory[1])) : "";
        LinearLayout r1 = row();
        View p1 = pill(MenuIcons.LEVELS, ctx.getString(R.string.tc_storage), stV, a.clearCache);
        View p2 = pill(MenuIcons.TV, ctx.getString(R.string.tc_memory), meV, a.stopApps);
        addPair(r1, p1, p2);
        col.addView(r1, rowLp(14));
        LinearLayout r2 = row();
        View p3 = pill(MenuIcons.SOURCE, ctx.getString(R.string.cc_wifi), wifiState(), a.wifi);
        View p4 = pill(MenuIcons.BLUETOOTH, ctx.getString(R.string.bluetooth), bluetoothState(), a.bluetooth);
        addPair(r2, p3, p4);
        col.addView(r2, rowLp(10));
        pop.add(p1);
        pop.add(p2);
        pop.add(p3);
        pop.add(p4);

        // now playing
        controller = pickController();
        View now = nowPill();
        col.addView(now, wideLp(10));
        pop.add(now);

        // round buttons
        LinearLayout r3 = row();
        r3.setGravity(Gravity.CENTER);
        View[] rb = {
                round(MenuIcons.WALLPAPER, 56, a.wallpaper, ctx.getString(R.string.wallpaper_tab)),
                round(MenuIcons.EYE, 56, a.hideApps, ctx.getString(R.string.hide_apps)),
                round(MenuIcons.WIDGETS, 56, a.widgets, ctx.getString(R.string.widgets)),
                round(MenuIcons.SPARKLE, 56, a.effects, ctx.getString(R.string.fx_title))};
        for (int i = 0; i < rb.length; i++) {
            FrameLayout cell = new FrameLayout(ctx);
            cell.setClipChildren(false);
            cell.addView(rb[i], new FrameLayout.LayoutParams((int) (56 * d), (int) (56 * d), Gravity.CENTER));
            r3.addView(cell, new LinearLayout.LayoutParams(0, (int) (56 * d), 1f));
            pop.add(rb[i]);
        }
        col.addView(r3, rowLp(12));

        // volume slider
        volume = new SliderPill(ctx, ctx.getString(R.string.cc_volume));
        volume.setFocusable(true);
        volume.setClickable(true);
        volume.sync();
        volume.setOnFocusChangeListener((v, f) -> {
            v.animate().scaleX(f ? 1.04f : 1f).scaleY(f ? 1.04f : 1f).setDuration(200).start();
            if (f) hint.setText(ctx.getString(R.string.cc_volume));
        });
        volume.setOnClickListener(v -> {
            if (audio != null) audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_TOGGLE_MUTE,
                    AudioManager.FLAG_SHOW_UI);
            volume.sync();
        });
        volume.setOnKeyListener((v, keyCode, event) -> {
            if (event.getAction() != KeyEvent.ACTION_DOWN || audio == null) return false;
            if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                audio.adjustStreamVolume(AudioManager.STREAM_MUSIC,
                        keyCode == KeyEvent.KEYCODE_DPAD_RIGHT ? AudioManager.ADJUST_RAISE : AudioManager.ADJUST_LOWER,
                        AudioManager.FLAG_SHOW_UI);
                volume.sync();
                return true;
            }
            return false;
        });
        col.addView(volume, wideLp(12));
        pop.add(volume);

        hint = new TextView(ctx);
        style(hint, 13f, 500, 0xCCFFFFFF);
        hint.setGravity(Gravity.CENTER);
        hint.setText(ctx.getString(R.string.settings));
        LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT);
        hp.topMargin = (int) (12 * d);
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
        col.setTranslationX(40 * d);
        col.animate().alpha(1f).translationX(0f).setDuration(340).setInterpolator(new DecelerateInterpolator(1.8f)).start();
        for (int i = 0; i < pop.size(); i++) {
            View v = pop.get(i);
            v.setAlpha(0f);
            v.setScaleX(0.88f);
            v.setScaleY(0.88f);
            v.animate().alpha(1f).scaleX(1f).scaleY(1f).setStartDelay(60L + i * 32L).setDuration(300)
                    .setInterpolator(new OvershootInterpolator(1.2f)).start();
        }
        p1.requestFocus();
    }

    // ---- live values ----------------------------------------------------------------------------------

    private String wifiState() {
        try {
            ConnectivityManager cm = (ConnectivityManager) ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
            NetworkInfo ni = cm == null ? null : cm.getActiveNetworkInfo();
            if (ni == null || !ni.isConnected()) return ctx.getString(R.string.cc_offline);
            if (ni.getType() == ConnectivityManager.TYPE_ETHERNET) return ctx.getString(R.string.cc_ethernet);
            return ctx.getString(R.string.cc_connected);
        } catch (RuntimeException e) {
            return "";
        }
    }

    private String bluetoothState() {
        try {
            android.bluetooth.BluetoothAdapter ad = android.bluetooth.BluetoothAdapter.getDefaultAdapter();
            if (ad == null) return "";
            return ctx.getString(ad.isEnabled() ? R.string.cc_on : R.string.cc_off);
        } catch (RuntimeException e) {
            return "";
        }
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

    private LinearLayout.LayoutParams wideLp(int topDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, (int) (58 * d));
        lp.topMargin = (int) (topDp * d);
        return lp;
    }

    private LinearLayout.LayoutParams sq(int sizeDp, int startDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams((int) (sizeDp * d), (int) (sizeDp * d));
        lp.setMarginStart((int) (startDp * d));
        return lp;
    }

    private void addPair(LinearLayout r, View a, View b) {
        LinearLayout.LayoutParams l = new LinearLayout.LayoutParams(0, (int) (58 * d), 1f);
        l.setMarginEnd((int) (5 * d));
        LinearLayout.LayoutParams rr = new LinearLayout.LayoutParams(0, (int) (58 * d), 1f);
        rr.setMarginStart((int) (5 * d));
        r.addView(a, l);
        r.addView(b, rr);
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

    private GradientDrawable glass(float radiusDp, boolean oval, boolean focused) {
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                focused ? new int[]{0x73FFFFFF, 0x45FFFFFF} : new int[]{0x38FFFFFF, 0x1CFFFFFF});
        g.setShape(oval ? GradientDrawable.OVAL : GradientDrawable.RECTANGLE);
        if (!oval) g.setCornerRadius(radiusDp * d);
        g.setStroke(Math.max(1, Math.round((focused ? 1.8f : 1f) * d)), focused ? 0xE6FFFFFF : 0x4DFFFFFF);
        return g;
    }

    private StateListDrawable glassStates(float radiusDp, boolean oval) {
        StateListDrawable sl = new StateListDrawable();
        sl.addState(new int[]{android.R.attr.state_focused}, glass(radiusDp, oval, true));
        sl.addState(new int[0], glass(radiusDp, oval, false));
        return sl;
    }

    private void focusFx(final View v, final PillIcon icon, final String label) {
        v.setOnFocusChangeListener((view, f) -> {
            view.animate().scaleX(f ? 1.06f : 1f).scaleY(f ? 1.06f : 1f).setDuration(f ? 220 : 150)
                    .setInterpolator(new OvershootInterpolator(f ? 1.4f : 0f)).start();
            if (icon != null) icon.setFocused(f);
            if (f && hint != null && label != null) hint.setText(label);
        });
    }

    /** Runs after the panel has closed (a detached view cannot post, so a Handler is used). */
    private void run(final Runnable action) {
        dismiss();
        if (action != null) new Handler(Looper.getMainLooper()).postDelayed(action, 220);
    }

    private View round(int iconKind, int sizeDp, final Runnable action, String label) {
        FrameLayout v = new FrameLayout(ctx);
        v.setBackground(glassStates(0, true));
        v.setFocusable(true);
        v.setClickable(true);
        ImageView iv = new ImageView(ctx);
        PillIcon icon = MenuIcons.make(iconKind, d);
        icons.add(icon);
        iv.setImageDrawable(icon);
        int s = (int) (sizeDp * 0.46f * d);
        v.addView(iv, new FrameLayout.LayoutParams(s, s, Gravity.CENTER));
        focusFx(v, icon, label);
        v.setOnClickListener(view -> run(action));
        return v;
    }

    /** Icon on the left, title and a dimmer value line on the right. */
    private LinearLayout pillShell(int iconKind, String title, String value, StateListDrawable bg, PillIcon[] out) {
        LinearLayout v = new LinearLayout(ctx);
        v.setOrientation(LinearLayout.HORIZONTAL);
        v.setGravity(Gravity.CENTER_VERTICAL);
        v.setPadding((int) (14 * d), 0, (int) (12 * d), 0);
        v.setBackground(bg);
        v.setFocusable(true);
        v.setClickable(true);
        ImageView iv = new ImageView(ctx);
        PillIcon icon = MenuIcons.make(iconKind, d);
        icons.add(icon);
        iv.setImageDrawable(icon);
        v.addView(iv, new LinearLayout.LayoutParams((int) (22 * d), (int) (22 * d)));
        LinearLayout txt = new LinearLayout(ctx);
        txt.setOrientation(LinearLayout.VERTICAL);
        TextView t = new TextView(ctx);
        style(t, 13f, 600, 0xFFFFFFFF);
        t.setText(title);
        t.setEllipsize(android.text.TextUtils.TruncateAt.END);
        TextView val = new TextView(ctx);
        style(val, 11f, 400, 0xB3FFFFFF);
        val.setText(value);
        val.setEllipsize(android.text.TextUtils.TruncateAt.END);
        txt.addView(t);
        LinearLayout.LayoutParams vp = new LinearLayout.LayoutParams(-2, -2);
        vp.topMargin = (int) (3 * d);
        txt.addView(val, vp);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        tp.setMarginStart((int) (10 * d));
        v.addView(txt, tp);
        out[0] = icon;
        v.setTag(new TextView[]{t, val});
        return v;
    }

    private View pill(int iconKind, String title, String value, final Runnable action) {
        PillIcon[] ic = new PillIcon[2];
        LinearLayout v = pillShell(iconKind, title, value, glassStates(29f, false), ic);
        focusFx(v, ic[0], title);
        v.setOnClickListener(view -> run(action));
        return v;
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

    private boolean isPlaying() {
        if (controller == null) return false;
        PlaybackState st = controller.getPlaybackState();
        return st != null && st.getState() == PlaybackState.STATE_PLAYING;
    }

    private View nowPill() {
        MediaMetadata m = controller == null ? null : controller.getMetadata();
        String t = m == null ? null : m.getString(MediaMetadata.METADATA_KEY_TITLE);
        String ar = m == null ? null : m.getString(MediaMetadata.METADATA_KEY_ARTIST);
        PillIcon[] ic = new PillIcon[2];
        final LinearLayout v = pillShell(MenuIcons.PLAY, t != null ? t : ctx.getString(R.string.cc_idle),
                ar == null ? "" : ar, glassStates(29f, false), ic);
        final TextView[] tv = (TextView[]) v.getTag();
        focusFx(v, ic[0], ctx.getString(R.string.cc_now_playing));
        v.setOnClickListener(view -> {
            if (controller == null) return;
            if (isPlaying()) controller.getTransportControls().pause();
            else controller.getTransportControls().play();
        });
        // Left / Right on this pill: previous / next track.
        v.setOnKeyListener((view, keyCode, event) -> {
            if (event.getAction() != KeyEvent.ACTION_DOWN || controller == null) return false;
            if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
                controller.getTransportControls().skipToPrevious();
                return true;
            }
            if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                controller.getTransportControls().skipToNext();
                return true;
            }
            return false;
        });
        return v;
    }

    // ---- the volume slider pill ------------------------------------------------------------------------

    private final class SliderPill extends View {
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint txt = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint rim = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path clip = new Path();
        private final RectF r = new RectF();
        private final String label;
        private float frac;
        private int value;

        SliderPill(Context c, String label) {
            super(c);
            this.label = label;
            fill.setColor(0x5CFFFFFF);
            txt.setColor(0xFFFFFFFF);
            rim.setStyle(Paint.Style.STROKE);
            if (Build.VERSION.SDK_INT >= 26) {
                try {
                    txt.setTypeface(c.getResources().getFont(R.font.outfit));
                } catch (RuntimeException ignored) {
                }
            }
        }

        void sync() {
            if (audio != null) {
                int max = Math.max(1, audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC));
                int cur = audio.getStreamVolume(AudioManager.STREAM_MUSIC);
                frac = cur / (float) max;
                value = Math.round(frac * 100f);
            }
            invalidate();
        }

        @Override
        protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight(), rad = 29 * d;
            boolean f = isFocused();
            r.set(0, 0, w, h);
            GradientDrawable bgd = glass(29f, false, f);
            bgd.setBounds(0, 0, getWidth(), getHeight());
            bgd.draw(c);
            clip.reset();
            clip.addRoundRect(r, rad, rad, Path.Direction.CW);
            c.save();
            c.clipPath(clip);
            c.drawRect(0, 0, w * frac, h, fill);
            c.restore();
            txt.setTextSize(13 * d);
            txt.setTextAlign(Paint.Align.LEFT);
            txt.setFakeBoldText(true);
            c.drawText(label, 20 * d, h / 2f + 5 * d, txt);
            txt.setTextAlign(Paint.Align.RIGHT);
            txt.setFakeBoldText(false);
            c.drawText(String.valueOf(value), w - 20 * d, h / 2f + 5 * d, txt);
        }

        @Override
        protected void onFocusChanged(boolean gained, int direction, android.graphics.Rect prev) {
            super.onFocusChanged(gained, direction, prev);
            invalidate();
        }
    }
}
