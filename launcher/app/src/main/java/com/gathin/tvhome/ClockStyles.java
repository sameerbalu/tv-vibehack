package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.view.View;
import android.widget.TextView;

import androidx.core.content.res.ResourcesCompat;

/** The clock looks (home screen and screensaver). The choice is kept in the launcher preferences. */
final class ClockStyles {

    static final String KEY = "clock_style";
    static final int MINECRAFT = 7;
    static final String[] NAMES = {"Classic", "Bold", "Thin", "Neon", "Serif", "Digital", "Sunset", "Minecraft"};
    static final String[] NOTES = {"Clean and calm", "Heavy and tight", "Light and airy", "Cyan and pink glow",
            "Elegant, gold", "Green display", "Warm orange", "Pixel blocks"};

    private ClockStyles() {
    }

    static int get(Context c) {
        return Math.max(0, Math.min(NAMES.length - 1, prefs(c).getInt(KEY, 0)));
    }

    static void set(Context c, int style) {
        prefs(c).edit().putInt(KEY, style).commit();
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE);
    }

    /** The original XML look of one view, captured the first time apply() sees it and kept in the view's tag. */
    private static final class Orig {
        Typeface typeface;
        String variations;
        float spacing;
        ColorStateList colors;
        float shadowRadius, shadowDx, shadowDy;
        int shadowColor;
        int visibility;
        int bottomMargin;
    }

    private static Orig capture(View v) {
        if (v == null) return null;
        Object t = v.getTag();
        if (t instanceof Orig) return (Orig) t;
        Orig o = new Orig();
        o.visibility = v.getVisibility();
        if (v.getLayoutParams() instanceof android.view.ViewGroup.MarginLayoutParams)
            o.bottomMargin = ((android.view.ViewGroup.MarginLayoutParams) v.getLayoutParams()).bottomMargin;
        if (v instanceof TextView) {
            TextView tv = (TextView) v;
            o.typeface = tv.getTypeface();
            o.variations = tv.getFontVariationSettings();
            o.spacing = tv.getLetterSpacing();
            o.colors = tv.getTextColors();
            o.shadowRadius = tv.getShadowRadius();
            o.shadowDx = tv.getShadowDx();
            o.shadowDy = tv.getShadowDy();
            o.shadowColor = tv.getShadowColor();
        }
        v.setTag(o);
        return o;
    }

    private static void restore(View v) {
        Orig o = capture(v);
        if (o == null) return;
        if (v instanceof TextView) {
            TextView tv = (TextView) v;
            tv.setTypeface(o.typeface);
            tv.setFontVariationSettings(o.variations);
            tv.setLetterSpacing(o.spacing);
            tv.setTextColor(o.colors);
            tv.setShadowLayer(o.shadowRadius, o.shadowDx, o.shadowDy, o.shadowColor);
        }
        v.setVisibility(o.visibility);
        if (v.getLayoutParams() instanceof android.view.ViewGroup.MarginLayoutParams) {
            android.view.ViewGroup.MarginLayoutParams lp = (android.view.ViewGroup.MarginLayoutParams) v.getLayoutParams();
            if (lp.bottomMargin != o.bottomMargin) {
                lp.bottomMargin = o.bottomMargin;
                v.setLayoutParams(lp);
            }
        }
    }

    static final String OWNER = "Sameer";

    /** "Good morning" in the normal styles; "Good morning Sameer" in the Minecraft style. */
    static CharSequence greeting(Context c, int res) {
        String base = c.getString(res);
        return get(c) == MINECRAFT ? base + " " + OWNER : base;
    }

    /**
     * Styles all the clock texts (home screen or screensaver). hours/minutes may be the same view (home: "h:mm")
     * and null parts are skipped. Every call first puts each view back to its captured XML look, so no state
     * from a previous style is left behind and Classic is exactly the original. Styles only change typeface,
     * weight, letter spacing and text colour: no glow, no shadow, no background.
     */
    static void apply(Context ctx, int style, TextView greeting, TextView hours, TextView minutes, TextView ampm,
                      TextView date, PixelClockView pixel, boolean saver) {
        View[] all = {greeting, hours, minutes, ampm, date, pixel};
        for (View v : all) restore(v);                       // captures the original look on first sight
        if (style <= 0 || style >= NAMES.length) return;     // Classic: exactly the original look
        boolean mc = style == MINECRAFT;
        Orig go = capture(greeting);
        float gBase = go != null ? go.spacing : 0.22f;
        if (pixel != null) {
            pixel.setVisibility(mc ? View.VISIBLE : View.GONE);
            pixel.setCellDp(saver ? 13f : 5.5f);
            pixel.setColors(0xFFFFFFFF, 0xFFFFAA00);
        }
        if (mc) {
            if (hours != null) hours.setVisibility(View.GONE);
            if (minutes != null && minutes != hours) minutes.setVisibility(View.GONE);
            if (greeting != null) {
                // Air between the greeting and the pixel clock (the blocks start right at the top of their view).
                if (greeting.getLayoutParams() instanceof android.view.ViewGroup.MarginLayoutParams) {
                    android.view.ViewGroup.MarginLayoutParams lp = (android.view.ViewGroup.MarginLayoutParams) greeting.getLayoutParams();
                    lp.bottomMargin = Math.round((saver ? 16f : 10f) * ctx.getResources().getDisplayMetrics().density);
                    greeting.setLayoutParams(lp);
                }
                style(ctx, greeting, 0, 700, gBase, 0xFFFFAA00);
                greeting.setShadowLayer(0.01f, 2f * ctx.getResources().getDisplayMetrics().density,
                        2f * ctx.getResources().getDisplayMetrics().density, 0xCC000000);   // the game's small dark drop shadow
            }
            if (ampm != null) style(ctx, ampm, 0, 700, 0.08f, 0xFFFFAA00);
            if (date != null) style(ctx, date, 0, 500, 0.14f, 0xFFFFFFFF);
            return;
        }
        int hc = 0xFFFFFFFF, mcol = saver ? 0xFFB3121B : 0xFFFFFFFF, kind = 0, wght = 450, gw = 500;
        float ls = -0.04f;
        switch (style) {
            case 1: wght = 800; gw = 700; ls = -0.05f; break;
            case 2: wght = 150; gw = 250; ls = 0.02f; break;
            case 3: wght = 600; ls = 0f; hc = 0xFF7DF9FF; mcol = saver ? 0xFFFF4FD8 : 0xFF7DF9FF; break;
            case 4: kind = 1; ls = 0f; hc = 0xFFF6E7C1; mcol = 0xFFE0A93B; break;
            case 5: kind = 2; ls = 0f; hc = 0xFF5CFF8F; mcol = 0xFF5CFF8F; break;
            case 6: wght = 500; ls = -0.03f; hc = 0xFFFFD9A8; mcol = 0xFFFF7A3D; break;
            default: break;
        }
        if (!saver) mcol = hc;
        boolean plain = style <= 2;
        if (hours != null) style(ctx, hours, kind, wght, ls, hc);
        if (minutes != null && minutes != hours) style(ctx, minutes, kind, wght, ls, mcol);
        if (greeting != null) style(ctx, greeting, kind, gw, gBase + (ls + 0.04f),
                plain ? (saver ? 0xFFB3121B : 0x8CFFFFFF) : (saver ? mcol : (hc & 0x00FFFFFF) | 0x8C000000));
        if (ampm != null) style(ctx, ampm, kind, saver ? 600 : 500, 0.08f,
                plain ? (saver ? 0xFFB3121B : 0xB3FFFFFF) : (saver ? mcol : (hc & 0x00FFFFFF) | 0xB3000000));
        if (date != null) style(ctx, date, kind, 400, 0.14f,
                plain ? (saver ? 0xFFFFFFFF : 0xA6FFFFFF) : (hc & 0x00FFFFFF) | 0xCC000000);
    }

    /** Typeface, weight, letter spacing and colour only. Any glow or shadow is cleared. */
    private static void style(Context ctx, TextView tv, int kind, int wght, float spacing, int color) {
        Typeface tf;
        if (kind == 1) tf = Typeface.create(Typeface.SERIF, Typeface.BOLD);
        else if (kind == 2) tf = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD);
        else tf = ResourcesCompat.getFont(ctx, R.font.outfit);
        tv.setTypeface(tf);
        if (kind == 0) tv.setFontVariationSettings("'wght' " + wght);
        else tv.setFontVariationSettings(null);
        tv.setLetterSpacing(spacing);
        tv.setTextColor(color);
        tv.setShadowLayer(0f, 0f, 0f, 0);
    }
}
