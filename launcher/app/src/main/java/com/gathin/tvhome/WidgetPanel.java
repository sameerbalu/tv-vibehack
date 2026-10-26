package com.gathin.tvhome;

import android.app.ActivityManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.location.Location;
import android.location.LocationManager;
import org.json.JSONObject;
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
import java.util.TimeZone;
import java.util.Map;
import java.util.Set;

/**
 * Small glass cards on the right side of the home screen. Each one can be switched on or off in
 * Settings > Widgets. Cards that do not fit above the dock are left out (the first ones win).
 */
public class WidgetPanel extends LinearLayout {

    static final String[] IDS = {"now_playing", "weather", "calendar", "week", "sun", "storage", "memory", "network",
            "volume", "day", "uptime", "moon", "world", "year", "quote", "lyrics"};
    static final int DARK_RED_FROM = 0xFF7A0C12;
    static final int DARK_RED_TO = 0xFFB3121B;
    static final int CARD_H_DP = 64;
    static final int GAP_DP = 8;
    static final int WIDTH_DP = 250;

    private static final String PREFS = "launcher_preferences";
    private static final String KEY_ON = "widgets_on_v6";
    private static final String KEY_LEFT = "widgets_left_v1";      // widgets placed on the left side
    private static final String KEY_WX = "weather_cache_v1";
    private static final String KEY_WX_MANUAL = "weather_city_manual_v1";
    private static final java.util.concurrent.ExecutorService WX_IO = java.util.concurrent.Executors.newSingleThreadExecutor();
    private static volatile long wxTried;
    private static volatile boolean wxFetching;

    private final SharedPreferences prefs;
    private final float d;
    private Typeface font;
    private final java.util.ArrayList<Sweep> sweeps = new java.util.ArrayList<>();
    private android.animation.ValueAnimator sweepAnim;
    private final Map<String, View> cards = new LinkedHashMap<>();
    private int maxCards = 5;
    private int shown;
    private boolean playing;
    private boolean left;               // this panel is the left column
    private WidgetPanel peer;            // the other column; music and weather updates go to both
    private WeatherView wxView;
    private TextView wxTemp, wxText, wxRange, sunValue;
    private ColorBar sunBar;
    private final TextView[] weekLetter = new TextView[7];
    private final TextView[] weekNum = new TextView[7];

    private TextView npTitle, npArtist, npLabel, npTime;
    private EqView npEq;
    private ColorBar npBar;
    private int nowColor = 0xFF1DB954;
    private ImageView npArt;
    private TextView calDay, calWeek, calMonth;
    private TextView stValue, memValue, netValue, netSub, volValue, dayValue, upValue, upSub;
    private ColorBar stBar, memBar, volBar, dayBar, yearBar;
    private MoonView moonView;
    private TextView moonName, moonLit, worldValue, yearValue, quoteText;

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
        buildMoon();
        buildWorld();
        buildYear();
        buildQuote();
        buildWeather();
        buildWeek();
        buildSun();
        for (String id : IDS) {
            View c = cards.get(id);
            if (c == null) continue;                       // "lyrics" has no card: it is drawn behind the wallpaper
            int heightDp = id.equals("now_playing") ? 2 * CARD_H_DP + GAP_DP : CARD_H_DP;
            LayoutParams lp = new LayoutParams((int) (WIDTH_DP * d), (int) (heightDp * d));
            lp.bottomMargin = (int) (GAP_DP * d);
            addView(c, lp);
            if (id.equals("now_playing")) {              // the light sweep is only for the music card
                sweeps.add(new Sweep(c, 0));
                c.setForeground(sweeps.get(0));
            }
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
            case "moon": return R.string.widget_moon;
            case "world": return R.string.widget_world;
            case "year": return R.string.widget_year;
            case "quote": return R.string.widget_quote;
            case "weather": return R.string.widget_weather;
            case "week": return R.string.widget_week;
            case "sun": return R.string.widget_sun;
            case "lyrics": return R.string.widget_lyrics;
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

    void setSide(boolean left) {
        this.left = left;
        applyVisibility();
    }

    void setPeer(WidgetPanel other) {
        peer = other;
    }

    boolean isLeft(String id) {
        Set<String> s = prefs.getStringSet(KEY_LEFT, null);
        return s != null && s.contains(id);
    }

    /** 0 = off, 1 = on the right, 2 = on the left. */
    int state(String id) {
        return !isOn(id) ? 0 : isLeft(id) ? 2 : 1;
    }

    /** Off, right, left, off ... (the lyrics start on the left, where the screen is free). */
    void cycle(String id) {
        int s = state(id);
        int next = id.equals("lyrics") ? (s == 0 ? 2 : s == 2 ? 1 : 0) : (s == 0 ? 1 : s == 1 ? 2 : 0);
        Set<String> on = new HashSet<>();
        Set<String> lefts = new HashSet<>();
        for (String i : IDS) {
            if (isOn(i)) on.add(i);
            if (isLeft(i)) lefts.add(i);
        }
        if (next == 0) {
            on.remove(id);
            lefts.remove(id);
        } else {
            on.add(id);
            if (next == 2) lefts.add(id);
            else lefts.remove(id);
        }
        prefs.edit().putStringSet(KEY_ON, on).putStringSet(KEY_LEFT, lefts).apply();
        applyVisibility();
        if (peer != null) peer.applyVisibility();
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
        int count = 0;                                   // slots used; the Now playing card takes two
        for (String id : IDS) {
            View c = cards.get(id);
            if (c == null) continue;
            boolean want = isOn(id) && isLeft(id) == left && (!id.equals("now_playing") || playing);
            int cost = id.equals("now_playing") ? 2 : 1;
            boolean show = want && count + cost <= maxCards;
            if (show) count += cost;
            int vis = show ? VISIBLE : GONE;
            if (c.getVisibility() != vis) {
                c.setVisibility(vis);
                if (show) {
                    c.setAlpha(0f);
                    c.setTranslationY(14 * d);
                    c.setScaleX(0.96f);
                    c.setScaleY(0.96f);
                    c.animate().alpha(1f).translationY(0f).scaleX(1f).scaleY(1f)
                            .setStartDelay(Math.min(5, count) * 70L).setDuration(520)
                            .setInterpolator(new android.view.animation.DecelerateInterpolator(1.6f)).start();
                }
            }
        }
        shown = count;
    }

    // ---- content -----------------------------------------------------------------------------------

    /** Refreshes the clock-driven cards; called on resume and every minute. */
    void refresh() {
        refreshLocal();
        if (peer != null) peer.refreshLocal();
    }

    private void refreshLocal() {
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
            memBar.set(memUsed, DARK_RED_FROM, DARK_RED_TO);      // memory use is always dark red
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

        float phase = MoonView.phaseNow();
        moonView.setPhase(phase);
        moonName.setText(MoonView.phaseName(phase));
        moonLit.setText(MoonView.litPercent(phase) + "% lit");

        String[][] zones = {{"DXB", "Asia/Dubai"}, {"LON", "Europe/London"}, {"NYC", "America/New_York"}};
        StringBuilder world = new StringBuilder();
        for (int i = 0; i < zones.length; i++) {
            SimpleDateFormat f = new SimpleDateFormat("HH:mm", Locale.US);
            f.setTimeZone(TimeZone.getTimeZone(zones[i][1]));
            world.append(zones[i][0]).append(' ').append(f.format(now));
            if (i < zones.length - 1) world.append("   ");
        }
        worldValue.setText(world.toString());

        int doy = cal.get(Calendar.DAY_OF_YEAR);
        int daysInYear = cal.getActualMaximum(Calendar.DAY_OF_YEAR);
        float yearFrac = doy / (float) daysInYear;
        yearValue.setText(String.format(Locale.US, "%d%% of %d  \u00B7  day %d", Math.round(yearFrac * 100),
                cal.get(Calendar.YEAR), doy));
        yearBar.set(yearFrac, 0xFF2DD4BF, 0xFF60A5FA);

        quoteText.setText(Quotes.forDay(doy));
        refreshWeek(cal);
        showWeather();
        showSun();
        if (isOn("weather") || isOn("sun")) maybeFetchWeather();
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

    /** A soft diagonal highlight that glides across one card now and then (cards take turns). */
    private final class Sweep extends android.graphics.drawable.Drawable {
        private static final long CYCLE = 6000L, RUN = 1400L;
        private final View card;
        private final long start;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final android.graphics.Path clip = new android.graphics.Path();
        private final RectF r = new RectF();
        private float progress = -1f;

        Sweep(View card, int index) {
            this.card = card;
            this.start = (index * 800L) % CYCLE;
        }

        /** Returns true while this card's sweep is running. */
        boolean tick(long now) {
            long local = ((now % CYCLE) - start + CYCLE) % CYCLE;
            float p = local < RUN ? local / (float) RUN : -1f;
            boolean changed = p != progress;
            progress = p;
            if (changed && card.getVisibility() == VISIBLE) invalidateSelf();
            return p >= 0f;
        }

        @Override
        public void draw(Canvas c) {
            if (progress < 0f) return;
            int w = getBounds().width(), h = getBounds().height();
            if (w <= 0 || h <= 0) return;
            float band = w * 0.45f;
            float x = -band + (w + band * 2) * progress;
            paint.setShader(new android.graphics.LinearGradient(x - band, 0, x + band, h * 0.6f,
                    new int[]{0x00FFFFFF, 0x2EFFFFFF, 0x00FFFFFF}, null, android.graphics.Shader.TileMode.CLAMP));
            r.set(0, 0, w, h);
            clip.reset();
            clip.addRoundRect(r, 16 * d, 16 * d, android.graphics.Path.Direction.CW);
            c.save();
            c.clipPath(clip);
            c.drawRect(r, paint);
            c.restore();
        }

        @Override public void setAlpha(int alpha) { }
        @Override public void setColorFilter(android.graphics.ColorFilter cf) { }
        @Override public int getOpacity() { return android.graphics.PixelFormat.TRANSLUCENT; }
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        sweepAnim = android.animation.ValueAnimator.ofFloat(0f, 1f);
        sweepAnim.setDuration(1000);
        sweepAnim.setRepeatCount(android.animation.ValueAnimator.INFINITE);
        sweepAnim.setInterpolator(new android.view.animation.LinearInterpolator());
        sweepAnim.addUpdateListener(a -> {
            long now = SystemClock.uptimeMillis();
            for (Sweep sw : sweeps) sw.tick(now);
        });
        sweepAnim.start();
        try {
            getContext().registerReceiver(volumeReceiver, new IntentFilter("android.media.VOLUME_CHANGED_ACTION"));
        } catch (RuntimeException ignored) {
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        if (sweepAnim != null) {
            sweepAnim.cancel();
            sweepAnim = null;
        }
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
        setNowPlayingLocal(title, artist, art, color);
        if (peer != null) peer.setNowPlayingLocal(title, artist, art, color);
    }

    private void setNowPlayingLocal(String title, String artist, Bitmap art, int color) {
        nowColor = color;
        npTitle.setText(TextUtils.isEmpty(title) ? "Spotify" : title);
        npArtist.setText(TextUtils.isEmpty(artist) ? "" : artist);
        npArtist.setVisibility(TextUtils.isEmpty(artist) ? GONE : VISIBLE);
        npLabel.setTextColor(color);
        npEq.setColor(color);
        if (art != null && art.getWidth() > 0 && art.getHeight() > 0) {
            npArt.setImageBitmap(Bitmap.createScaledBitmap(art, 192, 192, true));   // our own copy
        } else {
            npArt.setImageDrawable(new ColorDrawable(color));
        }
        playing = true;
        npEq.setActive(true);
        applyVisibility();
    }

    /** Where the song is: updates the thin progress bar and the "1:23 / 3:45" text. */
    void setNowProgress(long posMs, long durMs) {
        setNowProgressLocal(posMs, durMs);
        if (peer != null) peer.setNowProgressLocal(posMs, durMs);
    }

    private void setNowProgressLocal(long posMs, long durMs) {
        if (durMs <= 0) {
            npBar.set(0f, nowColor, nowColor);
            npTime.setText("");
            return;
        }
        npBar.set(posMs / (float) durMs, nowColor, lighten(nowColor));
        npTime.setText(clock(posMs) + " / " + clock(durMs));
    }

    private static String clock(long ms) {
        long sec = Math.max(0, ms / 1000);
        return String.format(Locale.US, "%d:%02d", sec / 60, sec % 60);
    }

    private static int lighten(int color) {
        return Color.rgb(Color.red(color) + (255 - Color.red(color)) * 4 / 10,
                Color.green(color) + (255 - Color.green(color)) * 4 / 10,
                Color.blue(color) + (255 - Color.blue(color)) * 4 / 10);
    }

    void clearNowPlaying() {
        clearNowPlayingLocal();
        if (peer != null) peer.clearNowPlayingLocal();
    }

    private void clearNowPlayingLocal() {
        if (!playing) return;
        playing = false;
        npEq.setActive(false);
        applyVisibility();
    }

    // ---- card builders -----------------------------------------------------------------------------

    private LinearLayout card(boolean horizontal) {
        return card(horizontal, 0xFF6B7280);
    }

    /** Plain dark glass card; each widget's colour only appears in its title. */
    private LinearLayout card(boolean horizontal, int tint) {
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
        LinearLayout c = card(true, 0xFF1DB954);
        c.setPadding((int) (12 * d), (int) (10 * d), (int) (14 * d), (int) (10 * d));
        npArt = new ImageView(getContext());
        npArt.setScaleType(ImageView.ScaleType.CENTER_CROP);
        npArt.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View v, Outline o) {
                o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), 14 * d);
            }
        });
        npArt.setClipToOutline(true);
        npArt.setElevation(6 * d);
        LayoutParams art = new LayoutParams((int) (92 * d), (int) (92 * d));
        art.setMarginEnd((int) (14 * d));
        c.addView(npArt, art);

        LinearLayout col = column();
        LinearLayout head = new LinearLayout(getContext());
        head.setOrientation(HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        npLabel = label("Now playing", 9, 0xFF1DB954);
        head.addView(npLabel, new LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        npEq = new EqView(getContext());
        head.addView(npEq, new LayoutParams((int) (18 * d), (int) (12 * d)));
        col.addView(head, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        npTitle = text(15, Color.WHITE, false);
        npTitle.setTypeface(font, Typeface.BOLD);
        npTitle.setSingleLine(false);
        npTitle.setMaxLines(2);
        npTitle.setEllipsize(TextUtils.TruncateAt.END);
        LayoutParams tl = new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tl.topMargin = (int) (4 * d);
        col.addView(npTitle, tl);

        npArtist = text(12, 0xB3FFFFFF, false);
        LayoutParams al = new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        al.topMargin = (int) (2 * d);
        col.addView(npArtist, al);

        npBar = new ColorBar(getContext());
        npBar.setShine(true);
        LayoutParams bl = new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (int) (4 * d));
        bl.topMargin = (int) (10 * d);
        col.addView(npBar, bl);

        npTime = text(10, 0x99FFFFFF, false);
        LayoutParams ml = new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        ml.topMargin = (int) (4 * d);
        col.addView(npTime, ml);

        c.addView(col, new LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        cards.put("now_playing", c);
    }

    private void buildCalendar() {
        LinearLayout c = card(true, 0xFFFF6B5E);
        calDay = text(34, Color.WHITE, false);
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

    private void buildMoon() {
        LinearLayout c = card(true, 0xFFFDE68A);
        moonView = new MoonView(getContext(), null);
        LayoutParams ml = new LayoutParams((int) (40 * d), (int) (40 * d));
        ml.setMarginEnd((int) (12 * d));
        c.addView(moonView, ml);
        LinearLayout col = column();
        col.addView(label("Moon", 9, 0xFFFDE68A));
        moonName = text(14, Color.WHITE, false);
        col.addView(moonName);
        moonLit = text(12, 0xB3FFFFFF, false);
        col.addView(moonLit);
        c.addView(col, new LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        cards.put("moon", c);
    }

    private void buildWorld() {
        LinearLayout c = card(false, 0xFF67E8F9);
        c.addView(label("World clock", 9, 0xFF67E8F9));
        worldValue = text(11, Color.WHITE, false);
        LayoutParams wl = new LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        wl.topMargin = (int) (4 * d);
        c.addView(worldValue, wl);
        cards.put("world", c);
    }

    private void buildYear() {
        LinearLayout c = card(false, 0xFF86EFAC);
        c.addView(label("Year", 9, 0xFF86EFAC));
        yearValue = text(14, Color.WHITE, false);
        c.addView(yearValue);
        yearBar = new ColorBar(getContext());
        LayoutParams bl = new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (int) (5 * d));
        bl.topMargin = (int) (6 * d);
        c.addView(yearBar, bl);
        cards.put("year", c);
    }

    // ---- weather, this week, sunrise & sunset --------------------------------------------------------

    private void buildWeather() {
        LinearLayout c = card(true, 0xFFFBBF24);
        wxView = new WeatherView(getContext());
        LayoutParams il = new LayoutParams((int) (46 * d), (int) (46 * d));
        il.setMarginEnd((int) (8 * d));
        c.addView(wxView, il);
        wxTemp = text(30, Color.WHITE, false);
        wxTemp.setTypeface(font, Typeface.BOLD);
        LayoutParams tl = new LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tl.setMarginEnd((int) (12 * d));
        c.addView(wxTemp, tl);
        LinearLayout col = column();
        col.addView(label("Weather", 9, 0xFFFCD34D));
        wxText = text(13, Color.WHITE, false);
        col.addView(wxText);
        wxRange = text(11, 0xB3FFFFFF, false);
        col.addView(wxRange);
        c.addView(col, new LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        cards.put("weather", c);
    }

    private void buildWeek() {
        LinearLayout c = card(true, 0xFFFF6B5E);
        c.setPadding((int) (8 * d), (int) (6 * d), (int) (8 * d), (int) (6 * d));
        for (int i = 0; i < 7; i++) {
            LinearLayout col = column();
            col.setGravity(Gravity.CENTER_HORIZONTAL);
            weekLetter[i] = text(9, 0x99FFFFFF, true);
            weekLetter[i].setGravity(Gravity.CENTER);
            weekNum[i] = text(13, Color.WHITE, false);
            weekNum[i].setGravity(Gravity.CENTER);
            LayoutParams nl = new LayoutParams((int) (26 * d), (int) (26 * d));
            nl.topMargin = (int) (2 * d);
            col.addView(weekLetter[i]);
            col.addView(weekNum[i], nl);
            c.addView(col, new LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        }
        cards.put("week", c);
    }

    private void buildSun() {
        LinearLayout c = card(false, 0xFFFB923C);
        c.addView(label("Sun", 9, 0xFFFDBA74));
        sunValue = text(14, Color.WHITE, false);
        c.addView(sunValue);
        sunBar = new ColorBar(getContext());
        LayoutParams bl = new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (int) (5 * d));
        bl.topMargin = (int) (6 * d);
        c.addView(sunBar, bl);
        cards.put("sun", c);
    }

    /** The seven days of this week, today in a coloured circle. */
    private void refreshWeek(Calendar today) {
        Calendar c = (Calendar) today.clone();
        int back = (c.get(Calendar.DAY_OF_WEEK) - c.getFirstDayOfWeek() + 7) % 7;
        c.add(Calendar.DAY_OF_YEAR, -back);
        SimpleDateFormat letter = new SimpleDateFormat("EEEEE", Locale.getDefault());
        for (int i = 0; i < 7; i++) {
            boolean isToday = c.get(Calendar.YEAR) == today.get(Calendar.YEAR)
                    && c.get(Calendar.DAY_OF_YEAR) == today.get(Calendar.DAY_OF_YEAR);
            int dow = c.get(Calendar.DAY_OF_WEEK);
            boolean weekend = dow == Calendar.SATURDAY || dow == Calendar.SUNDAY;
            weekLetter[i].setText(letter.format(c.getTime()));
            weekNum[i].setText(String.valueOf(c.get(Calendar.DAY_OF_MONTH)));
            weekNum[i].setTextColor(isToday ? Color.WHITE : weekend ? 0x99FFFFFF : 0xE6FFFFFF);
            weekNum[i].setTypeface(font, isToday ? Typeface.BOLD : Typeface.NORMAL);
            if (isToday) {
                GradientDrawable dot = new GradientDrawable();
                dot.setShape(GradientDrawable.OVAL);
                dot.setColor(0xFFFF6B5E);
                weekNum[i].setBackground(dot);
            } else {
                weekNum[i].setBackground(null);
            }
            c.add(Calendar.DAY_OF_YEAR, 1);
        }
    }

    // Weather comes from Open-Meteo (free, no account). The place is the TV's last known location, or else
    // where the internet connection is. It is refreshed every 30 minutes and kept for the next start.

    private JSONObject readWx() {
        String s = prefs.getString(KEY_WX, null);
        if (s == null) return null;
        try {
            return new JSONObject(s);
        } catch (Exception e) {
            return null;
        }
    }

    private void showWeather() {
        JSONObject o = readWx();
        if (o == null) {
            wxView.setCondition(3, true);
            wxTemp.setText("--");
            wxText.setText("Getting the weather");
            wxRange.setText("");
            return;
        }
        int code = o.optInt("code");
        String city = o.optString("city");
        boolean isDay = o.has("day") ? o.optBoolean("day", true) : dayByClock();
        wxView.setCondition(code, isDay);
        wxTemp.setText(Math.round(o.optDouble("temp")) + "\u00B0");
        wxText.setText(!isDay && code <= 1 ? "Clear night" : weatherText(code));
        wxRange.setText((city.isEmpty() ? "" : city + "  \u00B7  ") + "H " + Math.round(o.optDouble("hi"))
                + "\u00B0  L " + Math.round(o.optDouble("lo")) + "\u00B0");
    }

    private static boolean dayByClock() {
        int h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        return h >= 6 && h < 18;
    }

    private static String weatherIcon(int c) {
        if (c == 0) return "\u2600\uFE0F";
        if (c <= 2) return "\u26C5";
        if (c == 3) return "\u2601\uFE0F";
        if (c == 45 || c == 48) return "\uD83C\uDF2B\uFE0F";
        if (c >= 51 && c <= 57) return "\uD83C\uDF26\uFE0F";
        if ((c >= 61 && c <= 67) || (c >= 80 && c <= 82)) return "\uD83C\uDF27\uFE0F";
        if ((c >= 71 && c <= 77) || c == 85 || c == 86) return "\u2744\uFE0F";
        if (c >= 95) return "\u26C8\uFE0F";
        return "\u2601\uFE0F";
    }

    private static String weatherText(int c) {
        if (c == 0) return "Clear sky";
        if (c == 1) return "Mostly clear";
        if (c == 2) return "Partly cloudy";
        if (c == 3) return "Overcast";
        if (c == 45 || c == 48) return "Fog";
        if (c >= 51 && c <= 57) return "Drizzle";
        if (c >= 61 && c <= 67) return "Rain";
        if (c >= 71 && c <= 77) return "Snow";
        if (c >= 80 && c <= 82) return "Rain showers";
        if (c == 85 || c == 86) return "Snow showers";
        if (c >= 95) return "Thunderstorm";
        return "Cloudy";
    }

    /** The city the user picked for the weather, or "" for automatic. */
    String weatherCity() {
        try {
            String m = prefs.getString(KEY_WX_MANUAL, null);
            return m == null ? "" : new JSONObject(m).optString("city");
        } catch (Exception e) {
            return "";
        }
    }

    /** Sets the weather city (blank = automatic). Looks the name up online; the callback gets the city found or null. */
    void setWeatherCity(final String name, final java.util.function.Consumer<String> done) {
        WX_IO.execute(() -> {
            String found = "";
            boolean ok = true;
            try {
                String q = name == null ? "" : name.trim();
                if (q.isEmpty()) {
                    prefs.edit().remove(KEY_WX_MANUAL).remove(KEY_WX).apply();
                } else {
                    JSONObject g = new JSONObject(http("https://geocoding-api.open-meteo.com/v1/search?count=1&name="
                            + java.net.URLEncoder.encode(q, "UTF-8")));
                    JSONObject r = g.getJSONArray("results").getJSONObject(0);
                    JSONObject m = new JSONObject();
                    m.put("lat", r.getDouble("latitude"));
                    m.put("lon", r.getDouble("longitude"));
                    found = r.optString("name", q);
                    m.put("city", found);
                    prefs.edit().putString(KEY_WX_MANUAL, m.toString()).remove(KEY_WX).apply();
                }
            } catch (Exception e) {
                ok = false;
            }
            final boolean good = ok;
            final String city = found;
            wxTried = 0;
            post(() -> {
                if (good) maybeFetchWeather();
                if (done != null) done.accept(good ? city : null);
            });
        });
    }

    private void maybeFetchWeather() {
        long now = System.currentTimeMillis();
        if (wxFetching || now - wxTried < 30 * 60_000L) return;
        wxTried = now;
        wxFetching = true;
        final Context app = getContext().getApplicationContext();
        WX_IO.execute(() -> {
            boolean ok = false;
            try {
                double lat = Double.NaN, lon = Double.NaN;
                String city = "";
                String manual = prefs.getString(KEY_WX_MANUAL, null);
                if (manual != null) {
                    JSONObject m = new JSONObject(manual);
                    lat = m.optDouble("lat");
                    lon = m.optDouble("lon");
                    city = m.optString("city");
                } else {
                    Location loc = lastKnown(app);
                    if (loc != null) {
                        lat = loc.getLatitude();
                        lon = loc.getLongitude();
                    } else {
                        JSONObject ip = new JSONObject(http("https://ipwho.is/"));
                        lat = ip.optDouble("latitude");
                        lon = ip.optDouble("longitude");
                        city = ip.optString("city");
                    }
                }
                if (!Double.isNaN(lat) && !Double.isNaN(lon)) {
                    JSONObject w = new JSONObject(http("https://api.open-meteo.com/v1/forecast?latitude=" + lat
                            + "&longitude=" + lon + "&current=temperature_2m,weather_code,is_day"
                            + "&daily=temperature_2m_max,temperature_2m_min&timezone=auto&forecast_days=1"));
                    JSONObject cur = w.getJSONObject("current");
                    JSONObject daily = w.getJSONObject("daily");
                    JSONObject out = new JSONObject();
                    out.put("lat", lat);
                    out.put("lon", lon);
                    out.put("city", city);
                    out.put("temp", cur.getDouble("temperature_2m"));
                    out.put("code", cur.getInt("weather_code"));
                    out.put("day", cur.optInt("is_day", dayByClock() ? 1 : 0) == 1);
                    out.put("hi", daily.getJSONArray("temperature_2m_max").getDouble(0));
                    out.put("lo", daily.getJSONArray("temperature_2m_min").getDouble(0));
                    prefs.edit().putString(KEY_WX, out.toString()).apply();
                    ok = true;
                }
            } catch (Exception ignored) {
            }
            if (!ok) wxTried = System.currentTimeMillis() - 25 * 60_000L;       // try again in 5 minutes
            wxFetching = false;
            post(() -> {
                showWeather();
                showSun();
                if (peer != null) {
                    peer.showWeather();
                    peer.showSun();
                }
            });
        });
    }

    private static String http(String url) throws Exception {
        java.net.HttpURLConnection c = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(10000);
        c.setRequestProperty("User-Agent", "TvHomeLauncher/1.0");
        try {
            if (c.getResponseCode() != 200) throw new java.io.IOException("HTTP " + c.getResponseCode());
            try (java.io.InputStream in = c.getInputStream()) {
                java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
                byte[] buf = new byte[4096];
                int n;
                while ((n = in.read(buf)) > 0 && out.size() < 200_000) out.write(buf, 0, n);
                return out.toString("UTF-8");
            }
        } finally {
            c.disconnect();
        }
    }

    private static Location lastKnown(Context ctx) {
        try {
            if (ctx.checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION)
                    != android.content.pm.PackageManager.PERMISSION_GRANTED) return null;
            LocationManager lm = (LocationManager) ctx.getSystemService(Context.LOCATION_SERVICE);
            if (lm == null) return null;
            Location best = null;
            for (String p : lm.getProviders(true)) {
                Location l = lm.getLastKnownLocation(p);
                if (l != null && (best == null || l.getTime() > best.getTime())) best = l;
            }
            return best;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Sunrise and sunset today (local hours). Works from the position found for the weather. */
    private void showSun() {
        JSONObject o = readWx();
        double lat, lon;
        if (o != null && !Double.isNaN(o.optDouble("lat"))) {
            lat = o.optDouble("lat");
            lon = o.optDouble("lon");
        } else {
            Location l = lastKnown(getContext());
            if (l == null) {
                sunValue.setText("Needs a location");
                sunBar.set(0f, 0xFFFBBF24, 0xFFF97316);
                return;
            }
            lat = l.getLatitude();
            lon = l.getLongitude();
        }
        Calendar now = Calendar.getInstance();
        int n = now.get(Calendar.DAY_OF_YEAR);
        double decl = Math.toRadians(23.44) * Math.sin(2 * Math.PI * (284 + n) / 365.0);
        double x = -Math.tan(Math.toRadians(lat)) * Math.tan(decl);
        if (x <= -1 || x >= 1) {
            sunValue.setText(x <= -1 ? "Sun all day" : "Sun stays down");
            sunBar.set(x <= -1 ? 1f : 0f, 0xFFFBBF24, 0xFFF97316);
            return;
        }
        double half = Math.toDegrees(Math.acos(x)) / 15.0;
        double zone = now.getTimeZone().getOffset(now.getTimeInMillis()) / 3_600_000.0;
        double noon = 12 - lon / 15.0 + zone;
        double rise = noon - half, set = noon + half;
        double hour = now.get(Calendar.HOUR_OF_DAY) + now.get(Calendar.MINUTE) / 60.0;
        sunValue.setText("\u2191 " + clockText(rise) + "   \u2193 " + clockText(set));
        float f = hour < rise ? 0f : hour > set ? 1f : (float) ((hour - rise) / (set - rise));
        sunBar.set(f, 0xFFFBBF24, 0xFFF97316);
    }

    private String clockText(double hours) {
        int h = (int) Math.floor(hours);
        int m = (int) Math.round((hours - h) * 60);
        if (m == 60) {
            h++;
            m = 0;
        }
        h = ((h % 24) + 24) % 24;
        if (android.text.format.DateFormat.is24HourFormat(getContext())) return String.format(Locale.US, "%02d:%02d", h, m);
        return String.format(Locale.US, "%d:%02d %s", h % 12 == 0 ? 12 : h % 12, m, h < 12 ? "AM" : "PM");
    }

    private void buildQuote() {
        LinearLayout c = card(false, 0xFFFCA5A5);
        c.addView(label("Quote of the day", 9, 0xFFFCA5A5));
        quoteText = text(12, Color.WHITE, false);
        quoteText.setSingleLine(false);
        quoteText.setMaxLines(2);
        quoteText.setEllipsize(TextUtils.TruncateAt.END);
        LayoutParams ql = new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        ql.topMargin = (int) (3 * d);
        c.addView(quoteText, ql);
        cards.put("quote", c);
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
