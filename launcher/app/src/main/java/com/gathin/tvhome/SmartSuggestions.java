package com.gathin.tvhome;

import android.Manifest;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationManager;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.List;

/**
 * On-device "what would you open now?" engine for the Suggested row. Everything stays on the TV.
 * It weighs four things: the time of day (and weekend), whether it is dark outside (from the last known
 * location, if the TV has one), what audio is connected (headphones or a soundbar), and your own habits
 * (how often you open each app around this hour, learned from launches made through the launcher).
 */
final class SmartSuggestions {

    static final class Pick {
        final AppAdapter.Entry entry;
        final float score;

        Pick(AppAdapter.Entry entry, float score) {
            this.entry = entry;
            this.score = score;
        }
    }

    private static final String PREFS = "smart_habits";
    private static final int VIDEO = 1, AUDIO = 2, GAME = 3, NEWS = 4;
    private static final int MORNING = 0, AFTERNOON = 1, EVENING = 2, NIGHT = 3;
    // Prior interest by category for [morning, afternoon, evening, night].
    private static final float[] VIDEO_PRIOR = {0.2f, 0.6f, 1.6f, 1.0f};
    private static final float[] AUDIO_PRIOR = {1.2f, 0.8f, 0.8f, 0.6f};
    private static final float[] NEWS_PRIOR = {1.4f, 0.7f, 0.5f, 0.1f};
    private static final float[] GAME_PRIOR = {0.1f, 0.5f, 1.0f, 0.7f};

    private final Context ctx;
    private final SharedPreferences prefs;
    private double[] sunCache;
    private long sunCacheTime;

    private static final class Situation {
        int period;
        boolean weekend;
        boolean headphones;
        boolean soundbar;
    }

    SmartSuggestions(Context ctx) {
        this.ctx = ctx.getApplicationContext();
        this.prefs = this.ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    // ---- learning -------------------------------------------------------------------------------

    void recordLaunch(String pkg) {
        int[] counts = load(pkg);
        counts[slot(Calendar.getInstance())]++;
        int total = 0;
        for (int c : counts) total += c;
        if (total > 240) for (int i = 0; i < counts.length; i++) counts[i] /= 2;   // slowly forget old habits
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < counts.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(counts[i]);
        }
        prefs.edit().putString(pkg, sb.toString()).apply();
    }

    private static int slot(Calendar now) {
        int day = now.get(Calendar.DAY_OF_WEEK);
        boolean weekend = day == Calendar.SATURDAY || day == Calendar.SUNDAY;
        return (weekend ? 24 : 0) + now.get(Calendar.HOUR_OF_DAY);
    }

    private int[] load(String pkg) {
        int[] counts = new int[48];
        String saved = prefs.getString(pkg, null);
        if (saved == null) return counts;
        String[] parts = saved.split(",");
        for (int i = 0; i < parts.length && i < counts.length; i++) {
            try {
                counts[i] = Integer.parseInt(parts[i].trim());
            } catch (NumberFormatException ignored) {
            }
        }
        return counts;
    }

    // ---- reading the situation ------------------------------------------------------------------

    private Situation situation() {
        Calendar now = Calendar.getInstance();
        Situation s = new Situation();
        int hour = now.get(Calendar.HOUR_OF_DAY);
        int day = now.get(Calendar.DAY_OF_WEEK);
        s.weekend = day == Calendar.SATURDAY || day == Calendar.SUNDAY;
        if (hour >= 5 && hour < 11) s.period = MORNING;
        else if (hour >= 11 && hour < 17) s.period = AFTERNOON;
        else if (hour >= 17 && hour < 23) s.period = EVENING;
        else s.period = NIGHT;
        // Dark already (short winter days)? Then it is evening for viewing purposes.
        if (s.period == AFTERNOON && hour >= 15 && isDark(now)) s.period = EVENING;
        try {
            AudioManager am = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
            if (am != null) {
                for (AudioDeviceInfo d : am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
                    switch (d.getType()) {
                        case AudioDeviceInfo.TYPE_BLUETOOTH_A2DP:
                        case AudioDeviceInfo.TYPE_BLUETOOTH_SCO:
                        case AudioDeviceInfo.TYPE_WIRED_HEADPHONES:
                        case AudioDeviceInfo.TYPE_WIRED_HEADSET:
                        case AudioDeviceInfo.TYPE_USB_HEADSET:
                            s.headphones = true;
                            break;
                        case AudioDeviceInfo.TYPE_HDMI_ARC:
                        case 29:   // TYPE_HDMI_EARC (API 31)
                            s.soundbar = true;
                            break;
                        default:
                            break;
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return s;
    }

    private boolean isDark(Calendar now) {
        double hour = now.get(Calendar.HOUR_OF_DAY) + now.get(Calendar.MINUTE) / 60.0;
        double[] sun = sunTimes(now);
        if (sun == null) return hour < 6 || hour >= 19;
        return hour < sun[0] || hour >= sun[1];
    }

    /** Local sunrise and sunset (hours) from the last known location, or null when there is no location. */
    private double[] sunTimes(Calendar now) {
        long t = System.currentTimeMillis();
        if (sunCacheTime != 0 && t - sunCacheTime < 30 * 60_000L) return sunCache;
        sunCacheTime = t;
        sunCache = null;
        try {
            if (ctx.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                return null;
            }
            LocationManager lm = (LocationManager) ctx.getSystemService(Context.LOCATION_SERVICE);
            if (lm == null) return null;
            Location best = null;
            for (String provider : lm.getProviders(true)) {
                Location l = lm.getLastKnownLocation(provider);
                if (l != null && (best == null || l.getTime() > best.getTime())) best = l;
            }
            if (best == null) return null;
            int n = now.get(Calendar.DAY_OF_YEAR);
            double decl = Math.toRadians(23.44) * Math.sin(2 * Math.PI * (284 + n) / 365.0);
            double lat = Math.toRadians(best.getLatitude());
            double x = -Math.tan(lat) * Math.tan(decl);
            if (x <= -1) sunCache = new double[]{0, 24};          // midnight sun
            else if (x >= 1) sunCache = new double[]{12, 12};     // polar night
            else {
                double halfDay = Math.toDegrees(Math.acos(x)) / 15.0;
                double zone = now.getTimeZone().getOffset(now.getTimeInMillis()) / 3_600_000.0;
                double noon = 12 - best.getLongitude() / 15.0 + zone;
                sunCache = new double[]{noon - halfDay, noon + halfDay};
            }
        } catch (Exception ignored) {
        }
        return sunCache;
    }

    // ---- scoring --------------------------------------------------------------------------------

    private int category(String pkg) {
        try {
            ApplicationInfo ai = ctx.getPackageManager().getApplicationInfo(pkg, 0);
            switch (ai.category) {
                case ApplicationInfo.CATEGORY_VIDEO: return VIDEO;
                case ApplicationInfo.CATEGORY_AUDIO: return AUDIO;
                case ApplicationInfo.CATEGORY_GAME: return GAME;
                case ApplicationInfo.CATEGORY_NEWS: return NEWS;
                default: break;
            }
        } catch (Exception ignored) {
        }
        String p = pkg.toLowerCase();
        if (containsAny(p, "spotify", "music", "audio", "saavn", "gaana", "wynk", "podcast", "radio", "deezer", "tidal")) return AUDIO;
        if (containsAny(p, "netflix", "video", "youtube", "hotstar", "jiocinema", "sonyliv", "zee5", "disney", "hbo",
                "hulu", "plex", "kodi", "vlc", "mxplayer", "tubi", "peacock", "paramount")) return VIDEO;
        if (containsAny(p, "news", "weather", "cnn", "bbc")) return NEWS;
        if (containsAny(p, "game", "games", "play.games")) return GAME;
        return 0;
    }

    private static boolean containsAny(String text, String... needles) {
        for (String n : needles) if (text.contains(n)) return true;
        return false;
    }

    private float score(AppAdapter.Entry e, Situation s) {
        int hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        int[] c = load(e.pkg);
        int off = s.weekend ? 24 : 0;
        float near = c[off + hour] + 0.5f * (c[off + (hour + 23) % 24] + c[off + (hour + 1) % 24]);
        float habit = Math.min(near / 5f, 1f) * 4f;
        int total = 0;
        for (int v : c) total += v;
        habit += 0.3f * Math.min(1f, total / 20f);

        int cat = category(e.pkg);
        float prior = 0f;
        switch (cat) {
            case VIDEO: prior = VIDEO_PRIOR[s.period] + (s.weekend ? 0.3f : 0f); break;
            case AUDIO: prior = AUDIO_PRIOR[s.period]; break;
            case NEWS: prior = NEWS_PRIOR[s.period]; break;
            case GAME: prior = GAME_PRIOR[s.period] + (s.weekend ? 0.5f : 0f); break;
            default: break;
        }
        float audio = 0f;
        if (s.headphones) {
            if (cat == AUDIO) audio += 1.4f;
            if (cat == GAME) audio += 0.8f;
        }
        if (s.soundbar) {
            if (cat == VIDEO) audio += 1.0f;
            if (cat == AUDIO) audio += 0.6f;
        }
        return habit + prior + audio;
    }

    /** The best apps for right now, from the candidates (apps already in the dock should not be passed in). */
    List<Pick> suggest(List<AppAdapter.Entry> candidates, int max) {
        Situation s = situation();
        List<Pick> picks = new ArrayList<>();
        for (AppAdapter.Entry e : candidates) {
            float sc = score(e, s);
            if (sc >= 0.6f) picks.add(new Pick(e, sc));
        }
        Collections.sort(picks, (a, b) -> {
            int cmp = Float.compare(b.score, a.score);
            return cmp != 0 ? cmp : a.entry.label.compareToIgnoreCase(b.entry.label);
        });
        return picks.size() > max ? new ArrayList<>(picks.subList(0, max)) : picks;
    }

    /** One line saying what the suggestions are based on, e.g. "Evening · Weekend · Headphones connected". */
    String describe() {
        Situation s = situation();
        int res;
        switch (s.period) {
            case MORNING: res = R.string.ctx_morning; break;
            case AFTERNOON: res = R.string.ctx_afternoon; break;
            case EVENING: res = R.string.ctx_evening; break;
            default: res = R.string.ctx_night; break;
        }
        StringBuilder sb = new StringBuilder(ctx.getString(res));
        if (s.weekend) sb.append("  ·  ").append(ctx.getString(R.string.ctx_weekend));
        if (s.headphones) sb.append("  ·  ").append(ctx.getString(R.string.ctx_headphones));
        else if (s.soundbar) sb.append("  ·  ").append(ctx.getString(R.string.ctx_soundbar));
        return sb.toString();
    }
}
