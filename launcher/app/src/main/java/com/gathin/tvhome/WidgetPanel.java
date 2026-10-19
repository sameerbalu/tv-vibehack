package com.gathin.tvhome;

import android.app.ActivityManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Environment;
import android.os.StatFs;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Small glass cards on the right side of the home screen. Each one can be switched on or off in
 * Settings > Widgets. Cards that do not fit above the dock are left out (the first ones win).
 */
public class WidgetPanel extends LinearLayout {

    static final String[] IDS = {"now_playing", "calendar", "storage", "memory", "network"};
    static final int CARD_H_DP = 64;
    static final int GAP_DP = 8;
    static final int WIDTH_DP = 250;

    private static final String PREFS = "launcher_preferences";
    private static final String KEY_ON = "widgets_on_v6";

    private final SharedPreferences prefs;
    private final float d;
    private Typeface font;
    private final Map<String, View> cards = new LinkedHashMap<>();
    private int maxCards = 5;
    private int shown;
    private boolean playing;

    private TextView npTitle, npArtist;
    private ImageView npArt;
    private TextView calDay, calWeek, calMonth;
    private TextView stValue, memValue, netValue, netSub;
    private Bar stBar, memBar;

    public WidgetPanel(Context context, AttributeSet attrs) {
        super(context, attrs);
        prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        d = getResources().getDisplayMetrics().density;
        try {
            font = getResources().getFont(R.font.outfit);
        } catch (Exception ignored) {
            font = Typeface.DEFAULT;
        }
        setOrientation(VERTICAL);
        setClipChildren(false);
        buildNowPlaying();
        buildCalendar();
        buildStorage();
        buildMemory();
        buildNetwork();
        for (String id : IDS) {
            View c = cards.get(id);
            LayoutParams lp = new LayoutParams((int) (WIDTH_DP * d), (int) (CARD_H_DP * d));
            lp.bottomMargin = (int) (GAP_DP * d);
            addView(c, lp);
        }
        applyVisibility();
    }

    // ---- settings ----------------------------------------------------------------------------------

    static int titleRes(String id) {
        switch (id) {
            case "now_playing": return R.string.widget_now_playing;
            case "calendar": return R.string.widget_calendar;
            case "storage": return R.string.widget_storage;
            case "memory": return R.string.widget_memory;
            default: return R.string.widget_network;
        }
    }

    boolean isOn(String id) {
        Set<String> on = prefs.getStringSet(KEY_ON, null);
        if (on == null) return id.equals("now_playing");     // default: just the music card
        return on.contains(id);
    }

    void setOn(String id, boolean on) {
        Set<String> set = new HashSet<>();
        for (String i : IDS) if (isOn(i)) set.add(i);
        if (on) set.add(id);
        else set.remove(id);
        prefs.edit().putStringSet(KEY_ON, set).apply();
        refresh();
    }

    /** Only this many cards are shown (what fits above the dock). */
    void setMaxCards(int n) {
        if (n == maxCards) return;
        maxCards = n;
        applyVisibility();
    }

    /** Height of the visible cards, without the gap after the last one. */
    int contentHeight() {
        return shown == 0 ? 0 : (int) (shown * (CARD_H_DP + GAP_DP) * d - GAP_DP * d);
    }

    private void applyVisibility() {
        int count = 0;
        for (String id : IDS) {
            View c = cards.get(id);
            boolean want = isOn(id) && (!id.equals("now_playing") || playing);
            boolean show = want && count < maxCards;
            if (show) count++;
            int vis = show ? VISIBLE : GONE;
            if (c.getVisibility() != vis) {
                c.setVisibility(vis);
                if (show) {
                    c.setAlpha(0f);
                    c.animate().alpha(1f).setDuration(350).start();
                }
            }
        }
        shown = count;
    }

    // ---- content -----------------------------------------------------------------------------------

    /** Refreshes the clock-driven cards; called on resume and every minute. */
    void refresh() {
        Context ctx = getContext();
        Date now = new Date();
        calDay.setText(new SimpleDateFormat("d", Locale.getDefault()).format(now));
        calWeek.setText(new SimpleDateFormat("EEEE", Locale.getDefault()).format(now).toUpperCase(Locale.getDefault()));
        calMonth.setText(new SimpleDateFormat("MMMM yyyy", Locale.getDefault()).format(now));

        try {
            StatFs fs = new StatFs(Environment.getDataDirectory().getPath());
            long total = fs.getTotalBytes();
            long free = fs.getAvailableBytes();
            stValue.setText(String.format(Locale.US, "%.1f GB free of %.0f GB", free / 1e9, total / 1e9));
            stBar.set(total > 0 ? 1f - free / (float) total : 0f);
        } catch (Exception ignored) {
        }

        try {
            ActivityManager am = (ActivityManager) ctx.getSystemService(Context.ACTIVITY_SERVICE);
            ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
            am.getMemoryInfo(mi);
            memValue.setText(String.format(Locale.US, "%.1f of %.1f GB in use",
                    (mi.totalMem - mi.availMem) / 1e9, mi.totalMem / 1e9));
            memBar.set(mi.totalMem > 0 ? 1f - mi.availMem / (float) mi.totalMem : 0f);
        } catch (Exception ignored) {
        }

        String type = "Offline";
        String sub = "No connection";
        try {
            ConnectivityManager cm = (ConnectivityManager) ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
            Network n = cm.getActiveNetwork();
            NetworkCapabilities caps = n == null ? null : cm.getNetworkCapabilities(n);
            if (caps != null) {
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) type = "Ethernet";
                else if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) type = "Wi-Fi";
                else type = "Connected";
                int down = caps.getLinkDownstreamBandwidthKbps() / 1000;
                sub = down > 0 ? "Link speed " + down + " Mbps" : "Online";
            }
        } catch (Exception ignored) {
        }
        netValue.setText(type);
        netSub.setText(sub);
        applyVisibility();
    }

    /** A song is playing in Spotify. */
    void setNowPlaying(String title, String artist, Bitmap art, int color) {
        npTitle.setText(TextUtils.isEmpty(title) ? "Spotify" : title);
        npArtist.setText(TextUtils.isEmpty(artist) ? "" : artist);
        npArtist.setVisibility(TextUtils.isEmpty(artist) ? GONE : VISIBLE);
        if (art != null && art.getWidth() > 0 && art.getHeight() > 0) {
            npArt.setImageBitmap(Bitmap.createScaledBitmap(art, 96, 96, true));   // our own copy
        } else {
            npArt.setImageDrawable(new ColorDrawable(color));
        }
        playing = true;
        applyVisibility();
    }

    void clearNowPlaying() {
        if (!playing) return;
        playing = false;
        applyVisibility();
    }

    // ---- card builders -----------------------------------------------------------------------------

    private LinearLayout card(boolean horizontal) {
        LinearLayout l = new LinearLayout(getContext());
        l.setOrientation(horizontal ? HORIZONTAL : VERTICAL);
        l.setGravity(Gravity.CENTER_VERTICAL);
        l.setPadding((int) (12 * d), (int) (6 * d), (int) (12 * d), (int) (6 * d));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0x99101216);
        bg.setCornerRadius(16 * d);
        bg.setStroke(Math.max(1, (int) d), 0x2EFFFFFF);
        l.setBackground(bg);
        return l;
    }

    private TextView text(float sp, int color, boolean caps) {
        TextView t = new TextView(getContext());
        t.setTypeface(font);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        t.setIncludeFontPadding(false);
        if (caps) {
            t.setAllCaps(true);
            t.setLetterSpacing(0.16f);
        }
        return t;
    }

    private TextView label(String caption, float sp, int color) {
        TextView t = text(sp, color, true);
        t.setText(caption);
        return t;
    }

    private LinearLayout column() {
        LinearLayout c = new LinearLayout(getContext());
        c.setOrientation(VERTICAL);
        c.setGravity(Gravity.CENTER_VERTICAL);
        return c;
    }

    private void buildNowPlaying() {
        LinearLayout c = card(true);
        npArt = new ImageView(getContext());
        npArt.setScaleType(ImageView.ScaleType.CENTER_CROP);
        npArt.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View v, Outline o) {
                o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), 8 * d);
            }
        });
        npArt.setClipToOutline(true);
        LayoutParams art = new LayoutParams((int) (44 * d), (int) (44 * d));
        art.setMarginEnd((int) (12 * d));
        c.addView(npArt, art);
        LinearLayout col = column();
        col.addView(label("Spotify", 9, 0xFF1DB954));
        npTitle = text(14, Color.WHITE, false);
        npTitle.setTypeface(font, Typeface.BOLD);
        col.addView(npTitle);
        npArtist = text(12, 0xB3FFFFFF, false);
        col.addView(npArtist);
        c.addView(col, new LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        cards.put("now_playing", c);
    }

    private void buildCalendar() {
        LinearLayout c = card(true);
        calDay = text(34, Color.WHITE, false);
        calDay.setSingleLine(true);
        LayoutParams dl = new LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        dl.setMarginEnd((int) (14 * d));
        c.addView(calDay, dl);
        LinearLayout col = column();
        calWeek = text(12, Color.WHITE, true);
        calMonth = text(12, 0xB3FFFFFF, false);
        col.addView(calWeek);
        col.addView(calMonth);
        c.addView(col, new LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        cards.put("calendar", c);
    }

    private void buildStorage() {
        LinearLayout c = card(false);
        c.addView(label("Storage", 9, 0x99FFFFFF));
        stValue = text(14, Color.WHITE, false);
        c.addView(stValue);
        stBar = new Bar(getContext());
        LayoutParams bl = new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (int) (5 * d));
        bl.topMargin = (int) (6 * d);
        c.addView(stBar, bl);
        cards.put("storage", c);
    }

    private void buildMemory() {
        LinearLayout c = card(false);
        c.addView(label("Memory", 9, 0x99FFFFFF));
        memValue = text(14, Color.WHITE, false);
        c.addView(memValue);
        memBar = new Bar(getContext());
        LayoutParams bl = new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (int) (5 * d));
        bl.topMargin = (int) (6 * d);
        c.addView(memBar, bl);
        cards.put("memory", c);
    }

    private void buildNetwork() {
        LinearLayout c = card(false);
        c.addView(label("Network", 9, 0x99FFFFFF));
        netValue = text(16, Color.WHITE, false);
        c.addView(netValue);
        netSub = text(12, 0xB3FFFFFF, false);
        c.addView(netSub);
        cards.put("network", c);
    }

    /** A thin rounded progress bar. */
    private static class Bar extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();
        private float frac;

        Bar(Context c) {
            super(c);
        }

        void set(float f) {
            frac = Math.max(0f, Math.min(1f, f));
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float r = getHeight() / 2f;
            paint.setColor(0x33FFFFFF);
            rect.set(0, 0, getWidth(), getHeight());
            canvas.drawRoundRect(rect, r, r, paint);
            paint.setColor(0xDDFFFFFF);
            rect.set(0, 0, Math.max(getHeight(), getWidth() * frac), getHeight());
            canvas.drawRoundRect(rect, r, r, paint);
        }
    }
}
