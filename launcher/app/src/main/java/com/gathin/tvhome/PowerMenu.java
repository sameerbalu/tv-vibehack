package com.gathin.tvhome;

import android.app.Dialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
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
import java.util.List;

/**
 * The launcher's own power menu: Sleep, Restart, Power off. Three big buttons on a dimmed home screen that pop in one
 * after the other, a focused button grows and lights up in its own colour while its icon plays. Light on purpose:
 * nothing is built until it opens, and only alpha / scale / translation are animated (all on the GPU).
 */
final class PowerMenu {

    interface Pick {
        void on(int which);
    }

    private final Context ctx;
    private final float d;
    private final List<PillIcon> icons = new ArrayList<>();
    private Dialog dialog;
    private View scrim, box;
    private boolean closing;

    PowerMenu(Context ctx) {
        this.ctx = ctx;
        this.d = ctx.getResources().getDisplayMetrics().density;
    }

    boolean isShowing() {
        return dialog != null && dialog.isShowing();
    }

    void dismissNow() {
        closing = true;
        if (dialog != null && dialog.isShowing()) dialog.dismiss();
    }

    private void close(final Runnable after) {
        if (dialog == null || !dialog.isShowing() || closing) return;
        closing = true;
        scrim.animate().cancel();
        box.animate().cancel();
        scrim.animate().alpha(0f).setDuration(190).start();
        box.animate().alpha(0f).scaleX(0.94f).scaleY(0.94f).translationY(10 * d).setDuration(190)
                .setInterpolator(new AccelerateInterpolator(1.4f)).withLayer().withEndAction(() -> {
                    if (dialog != null && dialog.isShowing()) dialog.dismiss();
                    if (after != null) after.run();
                }).start();
    }

    void show(final Pick pick) {
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
        scrim = new View(ctx);
        scrim.setBackgroundColor(0xC4000000);
        scrim.setAlpha(0f);
        root.addView(scrim, new FrameLayout.LayoutParams(-1, -1));
        root.setOnClickListener(v -> close(null));

        LinearLayout column = new LinearLayout(ctx);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setGravity(Gravity.CENTER_HORIZONTAL);
        column.setClipChildren(false);
        column.setClipToPadding(false);
        box = column;

        TextView title = label(ctx.getString(R.string.power_menu_title), 22f, 0xFFFFFFFF, true);
        column.addView(title, new LinearLayout.LayoutParams(-2, -2));

        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setClipChildren(false);
        row.setClipToPadding(false);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(-2, -2);
        rlp.topMargin = (int) (22 * d);
        column.addView(row, rlp);

        final View[] buttons = {
                button(MenuIcons.MOON, R.string.power_sleep, 0xFF8E7CFF, PowerActions.SLEEP, pick),
                button(MenuIcons.RESTART, R.string.power_restart, 0xFFFF9F0A, PowerActions.RESTART, pick),
                button(MenuIcons.POWER, R.string.power_off, 0xFFFF453A, PowerActions.OFF, pick)};
        for (int i = 0; i < buttons.length; i++) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams((int) (128 * d), (int) (148 * d));
            if (i > 0) lp.setMarginStart((int) (16 * d));
            row.addView(buttons[i], lp);
        }

        TextView hint = label(ctx.getString(R.string.power_hint), 12.5f, 0x99FFFFFF, false);
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(-2, -2);
        hlp.topMargin = (int) (20 * d);
        column.addView(hint, hlp);

        root.addView(column, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));
        dialog.setContentView(root);
        dialog.setCanceledOnTouchOutside(true);
        dialog.setOnKeyListener((di, keyCode, event) -> {
            if (keyCode != KeyEvent.KEYCODE_BACK) return false;
            if (event.getAction() == KeyEvent.ACTION_UP) close(null);
            return true;
        });
        dialog.setOnDismissListener(di -> {
            for (PillIcon p : icons) p.rest();
            icons.clear();
        });
        dialog.show();

        // Entrance: the dim fades in, the group settles, the three buttons pop one after the other.
        scrim.animate().alpha(1f).setDuration(220).start();
        box.setAlpha(0f);
        box.setScaleX(0.94f);
        box.setScaleY(0.94f);
        box.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(300)
                .setInterpolator(new DecelerateInterpolator(2f)).withLayer().start();
        for (int i = 0; i < buttons.length; i++) {
            View b = buttons[i];
            b.setAlpha(0f);
            b.setScaleX(0.82f);
            b.setScaleY(0.82f);
            b.setTranslationY(22 * d);
            b.animate().alpha(1f).scaleX(1f).scaleY(1f).translationY(0f).setStartDelay(70L + i * 60L).setDuration(380)
                    .setInterpolator(new OvershootInterpolator(1.15f)).withLayer().start();
        }
        buttons[0].requestFocus();
    }

    private TextView label(String s, float sp, int color, boolean bold) {
        TextView t = new TextView(ctx);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        try {
            t.setTypeface(Typeface.create(ctx.getResources().getFont(R.font.outfit), bold ? Typeface.BOLD : Typeface.NORMAL));
        } catch (Exception ignored) {
        }
        t.setSingleLine(true);
        return t;
    }

    private GradientDrawable plate(int fill, int stroke, float strokeDp) {
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(28 * d);
        g.setColor(fill);
        g.setStroke(Math.max(1, Math.round(strokeDp * d)), stroke);
        return g;
    }

    private View button(int iconKind, int labelRes, final int accent, final int which, final Pick pick) {
        final LinearLayout b = new LinearLayout(ctx);
        b.setOrientation(LinearLayout.VERTICAL);
        b.setGravity(Gravity.CENTER);
        b.setClipChildren(false);
        b.setFocusable(true);
        b.setClickable(true);
        StateListDrawable bg = new StateListDrawable();
        bg.addState(new int[]{android.R.attr.state_focused}, plate((accent & 0x00FFFFFF) | 0x40000000, (accent & 0x00FFFFFF) | 0xE6000000, 2f));
        bg.addState(new int[]{}, plate(0x26FFFFFF, 0x1FFFFFFF, 1f));
        b.setBackground(bg);

        final PillIcon icon = MenuIcons.make(iconKind, d);
        icons.add(icon);
        ImageView iv = new ImageView(ctx);
        iv.setImageDrawable(icon);
        int s = (int) (46 * d);
        b.addView(iv, new LinearLayout.LayoutParams(s, s));
        TextView t = label(ctx.getString(labelRes), 15f, 0xFFFFFFFF, true);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(-2, -2);
        tp.topMargin = (int) (14 * d);
        b.addView(t, tp);

        b.setOnFocusChangeListener((v, f) -> {
            v.animate().cancel();
            v.animate().scaleX(f ? 1.08f : 1f).scaleY(f ? 1.08f : 1f).setStartDelay(0).setDuration(f ? 220 : 160)
                    .setInterpolator(f ? new OvershootInterpolator(1.4f) : new DecelerateInterpolator()).start();
            icon.setFocused(f);
        });
        b.setOnClickListener(v -> {
            v.animate().cancel();
            v.animate().scaleX(0.94f).scaleY(0.94f).setStartDelay(0).setDuration(90).withEndAction(() -> close(() -> pick.on(which))).start();
        });
        return b;
    }
}
