package com.gathin.tvhome;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Song lyrics from LRCLIB (free, no account). Synced lyrics come with a time for each line, so the
 * Lyrics widget can follow the song; plain lyrics are spread over the length of the song instead.
 * Answers are kept on the TV, so a song played before shows its words instantly.
 */
final class Lyrics {

    static final class Line {
        final long time;
        final String text;

        Line(long time, String text) {
            this.time = time;
            this.text = text;
        }
    }

    interface Callback {
        void onResult(String key, Lyrics lyrics);     // lyrics is null when none were found
    }

    final List<Line> lines = new ArrayList<>();
    boolean synced;

    private static final ExecutorService IO = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final Pattern STAMP = Pattern.compile("\\[(\\d+):(\\d+(?:\\.\\d+)?)\\]");

    static String key(String artist, String title) {
        return (artist == null ? "" : artist.trim()) + "|" + (title == null ? "" : title.trim());
    }

    /** Looks the lyrics up in the background; the callback runs on the main thread. */
    static void fetch(Context ctx, final String artist, final String title, final String album, final long durMs,
                      final Callback cb) {
        final Context app = ctx.getApplicationContext();
        final String key = key(artist, title);
        IO.execute(() -> {
            Lyrics result = null;
            try {
                result = load(app, key, artist, title, album, durMs);
            } catch (Exception | OutOfMemoryError ignored) {
            }
            final Lyrics r = result;
            MAIN.post(() -> cb.onResult(key, r));
        });
    }

    private static Lyrics load(Context ctx, String key, String artist, String title, String album, long durMs)
            throws Exception {
        String json = readCache(ctx, key);
        if (json == null) {
            String cleanTitle = cleanTitle(title);
            String url = "https://lrclib.net/api/get?artist_name=" + enc(artist) + "&track_name=" + enc(cleanTitle)
                    + (album != null && !album.trim().isEmpty() ? "&album_name=" + enc(album.trim()) : "")
                    + (durMs > 0 ? "&duration=" + (durMs / 1000) : "");
            json = get(url);
            if (json == null) {      // exact match not found: try a search
                String list = get("https://lrclib.net/api/search?q=" + enc(artist + " " + cleanTitle));
                json = firstUsable(list);
            }
            if (json != null) writeCache(ctx, key, json);
        }
        return json == null ? null : parse(json);
    }

    private static String cleanTitle(String t) {
        if (t == null) return "";
        return t.replaceAll("(?i)\\s*[-\u2013]\\s*(remaster(ed)?|live|mono|stereo|version|radio edit|single).*$", "")
                .replaceAll("(?i)\\s*\\((remaster(ed)?|live|mono|stereo).*?\\)", "").trim();
    }

    private static String enc(String s) throws Exception {
        return URLEncoder.encode(s == null ? "" : s.trim(), "UTF-8");
    }

    private static String get(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(10000);
        c.setRequestProperty("User-Agent", "TvHomeLauncher/1.0");
        try {
            if (c.getResponseCode() != 200) return null;
            try (InputStream in = c.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0 && out.size() < 600_000) out.write(buf, 0, n);
                return out.toString("UTF-8");
            }
        } finally {
            c.disconnect();
        }
    }

    private static String firstUsable(String list) {
        if (list == null) return null;
        try {
            JSONArray arr = new JSONArray(list);
            String plain = null;
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                if (!o.isNull("syncedLyrics") && !o.optString("syncedLyrics").trim().isEmpty()) return o.toString();
                if (plain == null && !o.isNull("plainLyrics") && !o.optString("plainLyrics").trim().isEmpty()) {
                    plain = o.toString();
                }
            }
            return plain;
        } catch (Exception e) {
            return null;
        }
    }

    private static Lyrics parse(String json) throws Exception {
        JSONObject o = new JSONObject(json);
        Lyrics l = new Lyrics();
        String synced = o.isNull("syncedLyrics") ? "" : o.optString("syncedLyrics");
        if (!synced.trim().isEmpty()) {
            for (String raw : synced.split("\n")) {
                Matcher m = STAMP.matcher(raw);
                List<Long> times = new ArrayList<>();
                int end = 0;
                while (m.find()) {
                    long ms = (long) (Integer.parseInt(m.group(1)) * 60_000L + Double.parseDouble(m.group(2)) * 1000);
                    times.add(ms);
                    end = m.end();
                }
                if (times.isEmpty()) continue;
                String text = raw.substring(end).trim();
                if (text.isEmpty()) text = "\u266A";                   // instrumental gap
                for (long t : times) l.lines.add(new Line(t, text));
            }
            Collections.sort(l.lines, new Comparator<Line>() {
                @Override
                public int compare(Line a, Line b) {
                    return Long.compare(a.time, b.time);
                }
            });
            l.synced = !l.lines.isEmpty();
        }
        if (l.lines.isEmpty()) {
            String plain = o.isNull("plainLyrics") ? "" : o.optString("plainLyrics");
            for (String raw : plain.split("\n")) {
                if (!raw.trim().isEmpty()) l.lines.add(new Line(0, raw.trim()));
            }
        }
        return l.lines.isEmpty() ? null : l;
    }

    // ---- small on-device cache -----------------------------------------------------------------------

    private static File dir(Context c) {
        File d = new File(c.getFilesDir(), "lyrics");
        if (!d.isDirectory()) d.mkdirs();
        return d;
    }

    private static File file(Context c, String key) {
        return new File(dir(c), Integer.toHexString(key.hashCode()) + ".json");
    }

    private static String readCache(Context c, String key) {
        File f = file(c, key);
        if (!f.isFile()) return null;
        try (InputStream in = new java.io.FileInputStream(f)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return out.toString("UTF-8");
        } catch (Exception e) {
            return null;
        }
    }

    private static void writeCache(Context c, String key, String json) {
        try {
            File d = dir(c);
            File[] all = d.listFiles();
            if (all != null && all.length > 200) {                      // keep the newest 150
                java.util.Arrays.sort(all, (a, b) -> Long.compare(a.lastModified(), b.lastModified()));
                for (int i = 0; i < all.length - 150; i++) all[i].delete();
            }
            try (FileOutputStream out = new FileOutputStream(file(c, key))) {
                out.write(json.getBytes("UTF-8"));
            }
        } catch (Exception ignored) {
        }
    }
}
