package com.gathin.tvhome;

import android.app.ActivityManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.media.AudioManager;
import android.os.SystemClock;
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
import java.util.Calendar;
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

    static final String[] IDS = {"now_playing", "calendar", "storage", "memory", "network", "volume", "day", "uptime"};
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
    private TextView stValue, memValue, netValue, netSub, volValue, dayValue, upValue, upSub;
    private ColorBar stBar, memBar, volBar, dayBar;

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
        buildVolume();
        buildDay();
        buildUptime();
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
            case "volume": return R.string.widget_volume;
            case "day": return R.string.widget_day;
            case "uptime": return R.string.widget_uptime;
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
            float used = total > 0 ? 1f - free / (float) total : 0f;
            int[] sc = used < 0.85f ? new int[]{0xFF818CF8, 0xFFC084FC} : levelColors(used);
            stBar.set(used, sc[0], sc[1]);
        } catch (Exception ignored) {
        }

        try {
            ActivityManager am = (ActivityManager) ctx.getSystemService(Context.ACTIVITY_SERVICE);
            ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
            am.getMemoryInfo(mi);
            memValue.setText(String.format(Locale.US, "%.1f of %.1f GB in use",
                    (mi.totalMem - mi.availMem) / 1e9, mi.totalMem / 1e9));
            float memUsed = mi.totalMem > 0 ? 1f - mi.availMem / (float) mi.totalMem : 0f;
            int[] mc = levelColors(memUsed);
            memBar.set(memUsed, mc[0], mc[1]);
        } catch (Exception ignored) {
        }

        String type = "Offline";
        String sub = "No connection";
        boolean online = false;
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
                online = true;
            }
        } catch (Exception ignored) {
        }
        netValue.setText(type);
        netValue.setTextColor(online ? 0xFF4ADE80 : 0xFFF87171);
        netSub.setText(sub);

        Calendar cal = Calendar.getInstance();
        float dayFrac = (cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)) / 1440f;
        dayValue.setText(String.format(Locale.US, "%d%% of today  \u00B7  week %d",
                Math.round(dayFrac * 100), cal.get(Calendar.WEEK_OF_YEAR)));
        dayBar.set(dayFrac, 0xFFFBBF24, 0xFFF472B6);

        long up = SystemClock.elapsedRealtime() / 60000L;
        long days = up / 1440, hours = (up / 60) % 24, mins = up % 60;
        upValue.setText(days > 0 ? days + " d  " + hours + " h" : hours + " h  " + mins + " m");
        upSub.setText("Since the last restart");
        refreshVolume();
        applyVisibility();
    }

    void refreshVolume() {
        try {
            AudioManager am = (AudioManager) getContext().getSystemService(Context.AUDIO_SERVICE);
            int max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
            int cur = am.getStreamVolume(AudioManager.STREAM_MUSIC);
            float f = max > 0 ? cur / (float) max : 0f;
            volValue.setText(max <= 0 ? "Set by the TV" : cur == 0 ? "Muted" : Math.round(f * 100) + "%");
            volBar.set(f, 0xFF38BDF8, 0xFFA78BFA);
        } catch (RuntimeException ignored) {
        }
    }

    private final BroadcastReceiver volumeReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            refreshVolume();
        }
    };

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        try {
            getContext().registerReceiver(volumeReceiver, new IntentFilter("android.media.VOLUME_CHANGED_ACTION"));
        } catch (RuntimeException ignored) {
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        try {
            getContext().unregisterReceiver(volumeReceiver);
        } catch (RuntimeException ignored) {
        }
        super.onDetachedFromWindow();
    }

    /** Teal when there is plenty of room, through amber to red as the bar fills up. */
    static int[] levelColors(float f) {
        int green = 0xFF22D3A6, amber = 0xFFFBBF24, red = 0xFFEF4444;
        int end;
        if (f < 0.6f) end = blend(green, amber, f / 0.6f * 0.35f);
        else if (f < 0.85f) end = blend(amber, red, (f - 0.6f) / 0.25f * 0.6f);
        else end = red;
        return new int[]{green, end};
    }

    private static int blend(int a, int b, float t) {
        return Color.rgb((int) (Color.red(a) + (Color.red(b) - Color.red(a)) * t),
                (int) (Color.green(a) + (Color.green(b) - Color.green(a)) * t),
                (int) (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t));
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
        return card(horizontal, 0xFF6B7280);
    }

    /** Glass card with a faint wash of the widget's own colour on the left. */
    private LinearLayout card(boolean horizontal, int tint) {
        LinearLayout l = new LinearLayout(getContext());
        l.setOrientation(horizontal ? HORIZONTAL : VERTICAL);
        l.setGravity(Gravity.CENTER_VERTICAL);
        l.setPadding((int) (12 * d), (int) (6 * d), (int) (12 * d), (int) (6 * d));
        GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{0x4D000000 | (tint & 0x00FFFFFF), 0x99101216, 0x99101216});
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
        LinearLayout c = card(true, 0xFF1DB954);
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
        LinearLayout c = card(true, 0xFFFF6B5E);
        calDay = text(34, 0xFFFF7A6B, false);
        calDay.setSingleLine(true);
        LayoutParams dl = new LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        dl.setMarginEnd((int) (14 * d));
        c.addView(calDay, dl);
        LinearLayout col = column();
        calWeek = text(12, 0xFFFFA498, true);
        calMonth = text(12, 0xB3FFFFFF, false);
        col.addView(calWeek);
        col.addView(calMonth);
        c.addView(col, new LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        cards.put("calendar", c);
    }

    private void buildStorage() {
        LinearLayout c = card(false, 0xFF818CF8);
        c.addView(label("Storage", 9, 0xFFA5B4FC));
        stValue = text(14, Color.WHITE, false);
        c.addView(stValue);
        stBar = new ColorBar(getContext());
        LayoutParams bl = new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (int) (5 * d));
        bl.topMargin = (int) (6 * d);
        c.addView(stBar, bl);
        cards.put("storage", c);
    }

    private void buildMemory() {
        LinearLayout c = card(false, 0xFF22D3A6);
        c.addView(label("Memory", 9, 0xFF5EEAD4));
        memValue = text(14, Color.WHITE, false);
        c.addView(memValue);
        memBar = new ColorBar(getContext());
        LayoutParams bl = new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (int) (5 * d));
        bl.topMargin = (int) (6 * d);
        c.addView(memBar, bl);
        cards.put("memory", c);
    }

    private void buildNetwork() {
        LinearLayout c = card(false, 0xFF38BDF8);
        c.addView(label("Network", 9, 0xFF7DD3FC));
        netValue = text(16, Color.WHITE, false);
        c.addView(netValue);
        netSub = text(12, 0xB3FFFFFF, false);
        c.addView(netSub);
        cards.put("network", c);
    }

    private void buildVolume() {
        LinearLayout c = card(false, 0xFF38BDF8);
        c.addView(label("Volume", 9, 0xFF7DD3FC));
        volValue = text(14, Color.WHITE, false);
        c.addView(volValue);
        volBar = new ColorBar(getContext());
        LayoutParams bl = new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (int) (5 * d));
        bl.topMargin = (int) (6 * d);
        c.addView(volBar, bl);
        cards.put("volume", c);
    }

    private void buildDay() {
        LinearLayout c = card(false, 0xFFF472B6);
        c.addView(label("Today", 9, 0xFFFBA8D4));
        dayValue = text(14, Color.WHITE, false);
        c.addView(dayValue);
        dayBar = new ColorBar(getContext());
        LayoutParams bl = new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (int) (5 * d));
        bl.topMargin = (int) (6 * d);
        c.addView(dayBar, bl);
        cards.put("day", c);
    }

    private void buildUptime() {
        LinearLayout c = card(false, 0xFFA3E635);
        c.addView(label("Uptime", 9, 0xFFBEF264));
        upValue = text(16, Color.WHITE, false);
        c.addView(upValue);
        upSub = text(12, 0xB3FFFFFF, false);
        c.addView(upSub);
        cards.put("uptime", c);
    }
}
