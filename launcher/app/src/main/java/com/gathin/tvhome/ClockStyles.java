package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;
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
        prefs(c).edit().putInt(KEY, style).apply();
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE);
    }

    /**
     * Styles the clock texts. hours/minutes may be the same view (home: "h:mm") and null parts are skipped.
     * @param homeMinutesRed the saver keeps its red minutes in the classic style
     */
    static void apply(Context ctx, int style, TextView hours, TextView minutes, TextView ampm, TextView date,
                      PixelClockView pixel, boolean saver) {
        boolean mc = style == MINECRAFT;
        if (pixel != null) {
            pixel.setVisibility(mc ? View.VISIBLE : View.GONE);
            pixel.setCellDp(saver ? 13f : 5.5f);
            pixel.setColors(0xFFFFFFFF, saver ? 0xFFFFAA00 : 0xFFFFAA00);
        }
        if (hours != null) hours.setVisibility(mc ? View.GONE : View.VISIBLE);
        if (minutes != null && minutes != hours) minutes.setVisibility(mc ? View.GONE : View.VISIBLE);
        float dls = saver ? 0.14f : 0.14f;
        if (mc) {
            if (ampm != null) style(ctx, ampm, 0, 700, 0.08f, 0xFFFFAA00, 0, 0);
            if (date != null) style(ctx, date, 0, 500, dls, 0xFFFFFFFF, 0, 0);
            return;
        }
        int hc = 0xFFFFFFFF, mcol = saver ? 0xFFB3121B : 0xFFFFFFFF, glow = 0, kind = 0, wght = 450;
        float ls = -0.04f;
        switch (style) {
            case 1: wght = 800; ls = -0.05f; break;
            case 2: wght = 150; ls = 0.02f; break;
            case 3: wght = 600; ls = 0f; hc = 0xFF7DF9FF; mcol = saver ? 0xFFFF4FD8 : 0xFF7DF9FF; glow = 0xFF22E5FF; break;
            case 4: kind = 1; ls = 0f; hc = 0xFFF6E7C1; mcol = 0xFFE0A93B; break;
            case 5: kind = 2; ls = 0f; hc = 0xFF5CFF8F; mcol = 0xFF5CFF8F; glow = 0xFF22C55E; break;
            case 6: wght = 500; ls = -0.03f; hc = 0xFFFFD9A8; mcol = 0xFFFF7A3D; glow = 0xFFFF6A1F; break;
            default: break;
        }
        if (!saver) mcol = hc;
        if (hours != null) style(ctx, hours, kind, wght, ls, hc, glow, 22);
        if (minutes != null && minutes != hours) style(ctx, minutes, kind, wght, ls, mcol, style == 3 ? 0xFFFF2BD6 : glow, 22);
        boolean plain = style <= 2;
        if (ampm != null) style(ctx, ampm, kind, saver ? 600 : 500, 0.08f,
                plain ? (saver ? 0xFFB3121B : 0xB3FFFFFF) : (saver ? mcol : (hc & 0x00FFFFFF) | 0xB3000000), 0, 0);
        if (date != null) style(ctx, date, kind, 400, dls,
                plain ? (saver ? 0xFFFFFFFF : 0xA6FFFFFF) : (hc & 0x00FFFFFF) | 0xCC000000, 0, 0);
    }

    private static void style(Context ctx, TextView tv, int kind, int wght, float spacing, int color, int glow, int glowDp) {
        Typeface tf;
        if (kind == 1) tf = Typeface.create(Typeface.SERIF, Typeface.BOLD);
        else if (kind == 2) tf = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD);
        else tf = ResourcesCompat.getFont(ctx, R.font.outfit);
        tv.setTypeface(tf);
        if (kind == 0) tv.setFontVariationSettings("'wght' " + wght);
        else tv.setFontVariationSettings(null);
        tv.setLetterSpacing(spacing);
        tv.setTextColor(color);
        if (glow != 0) tv.setShadowLayer(glowDp * ctx.getResources().getDisplayMetrics().density, 0f, 0f, glow);
        else tv.setShadowLayer(0f, 0f, 0f, 0);
    }
}
