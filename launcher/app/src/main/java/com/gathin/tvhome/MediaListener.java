package com.gathin.tvhome;

import android.content.ComponentName;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.service.notification.NotificationListenerService;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Holding notification access is what lets the launcher read media sessions: the Spotify glow in
 * MainActivity, and here, in the background, what is being watched in Nuvio ("Continue watching").
 */
public class MediaListener extends NotificationListenerService {

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final List<Watcher> watchers = new ArrayList<>();
    private final Set<String> fetching = new HashSet<>();
    private MediaSessionManager sessionManager;
    private ExecutorService io;

    private final MediaSessionManager.OnActiveSessionsChangedListener sessionsListener = this::watch;

    /** While something plays, the position is saved every 20 seconds. */
    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            boolean any = false;
            for (Watcher w : watchers) {
                if (w.playing()) {
                    snapshot(w.controller);
                    any = true;
                }
            }
            if (any) handler.postDelayed(this, 20_000L);
        }
    };

    @Override
    public void onListenerConnected() {
        super.onListenerConnected();
        start();
    }

    @Override
    public void onListenerDisconnected() {
        stop();
        super.onListenerDisconnected();
    }

    @Override
    public void onDestroy() {
        stop();
        super.onDestroy();
    }

    private void start() {
        stop();
        io = Executors.newSingleThreadExecutor();
        backfillYoutube();
        try {
            sessionManager = (MediaSessionManager) getSystemService(Context.MEDIA_SESSION_SERVICE);
            ComponentName me = new ComponentName(this, MediaListener.class);
            sessionManager.addOnActiveSessionsChangedListener(sessionsListener, me, handler);
            watch(sessionManager.getActiveSessions(me));
        } catch (RuntimeException ignored) {
        }
    }

    private void stop() {
        handler.removeCallbacks(tick);
        for (Watcher w : watchers) w.release();
        watchers.clear();
        if (sessionManager != null) {
            try {
                sessionManager.removeOnActiveSessionsChangedListener(sessionsListener);
            } catch (RuntimeException ignored) {
            }
            sessionManager = null;
        }
        if (io != null) {
            io.shutdownNow();
            io = null;
        }
        fetching.clear();
    }

    static boolean isNuvio(String pkg) {
        return pkg != null && pkg.toLowerCase(Locale.ROOT).contains("nuvio");
    }

    /** Video / streaming apps whose playback appears in "Continue watching" (matched on the package name). */
    private static final String[] VIDEO_APPS = {
            "nuvio", "netflix", "com.google.android.youtube.tv", "com.google.android.youtube.tvunplugged",
            "com.amazon.amazonvideo", "com.amazon.avod", "hotstar", "jiocinema", "jio.media", "disney",
            "plex", "kodi", "stremio", "jellyfin", "emby", "hulu", "hbo", "com.wbd", "paramount", "peacock",
            "apple.atve", "com.apple.tv", "sonyliv", "com.graymatrix.did", "zee5", "voot", "mxtech", "mxplayer",
            "vlc", "justwatch", "crunchyroll", "tubi", "pluto", "twitch", "discovery", "starz", "showtime",
            "viki", "curiosity", "mubi", "sunnxt", "erosnow", "altbalaji", "lionsgate", "bbc.iplayer",
            "itvx", "channel4", "canalplus", "rakuten", "kinopoisk", "hbomax",
            "youtube", "tizentube", "smarttube", "teamsmart", "liskovsoft", "revanced", "vanced", "newpipe", "cobalt"};
    /** Music, podcast and other audio apps: never listed, whatever they report. */
    private static final String[] AUDIO_APPS = {
            "spotify", "music", "deezer", "tidal", "soundcloud", "pandora", "shazam", "audible", "podcast",
            "radio", "tunein", "jiosaavn", "gaana", "wynk", "hungama", "napster", "qobuz", "audio"};

    /** True for apps whose playing video should be remembered. */
    static boolean isVideoApp(String pkg, long durationMs) {
        if (pkg == null) return false;
        String p = pkg.toLowerCase(Locale.ROOT);
        if (p.contains("youtube") && p.contains("music")) return durationMs >= 8 * 60_000L;   // songs are not "watching"
        for (String a : AUDIO_APPS) if (p.contains(a) && !p.contains("youtube")) return false;
        for (String v : VIDEO_APPS) if (p.contains(v)) return true;
        // Any other app that plays something long, like a film or an episode.
        return durationMs >= 8 * 60_000L;
    }

    private void watch(List<MediaController> controllers) {
        for (Watcher w : watchers) w.release();
        watchers.clear();
        if (controllers != null) {
            for (MediaController c : controllers) {
                if (c.getPackageName() != null && !c.getPackageName().equals(getPackageName())
                        && !isAudioOnly(c.getPackageName())) watchers.add(new Watcher(c));
            }
        }
        for (Watcher w : watchers) snapshot(w.controller);
        scheduleTick();
    }

    private static boolean isAudioOnly(String pkg) {
        String p = pkg.toLowerCase(Locale.ROOT);
        for (String a : AUDIO_APPS) if (p.contains(a) && !p.contains("youtube")) return true;
        return false;
    }

    private void scheduleTick() {
        handler.removeCallbacks(tick);
        for (Watcher w : watchers) {
            if (w.playing()) {
                handler.postDelayed(tick, 20_000L);
                return;
            }
        }
    }

    private final class Watcher extends MediaController.Callback {
        final MediaController controller;

        Watcher(MediaController c) {
            controller = c;
            c.registerCallback(this, handler);
        }

        boolean playing() {
            PlaybackState st = controller.getPlaybackState();
            return st != null && st.getState() == PlaybackState.STATE_PLAYING;
        }

        void release() {
            try {
                controller.unregisterCallback(this);
            } catch (RuntimeException ignored) {
            }
        }

        @Override
        public void onPlaybackStateChanged(PlaybackState state) {
            snapshot(controller);
            scheduleTick();
        }

        @Override
        public void onMetadataChanged(MediaMetadata metadata) {
            snapshot(controller);
        }
    }

    private static String first(MediaMetadata m, String... keys) {
        for (String k : keys) {
            String v = m.getString(k);
            if (v != null && !v.trim().isEmpty()) return v.trim();
        }
        return null;
    }

    /** Saves what the Nuvio session is showing right now. */
    private void snapshot(MediaController c) {
        try {
            PlaybackState st = c.getPlaybackState();
            MediaMetadata m = c.getMetadata();
            if (st == null || m == null) return;
            int state = st.getState();
            if (state != PlaybackState.STATE_PLAYING && state != PlaybackState.STATE_PAUSED
                    && state != PlaybackState.STATE_BUFFERING) return;
            final String title = first(m, MediaMetadata.METADATA_KEY_TITLE, MediaMetadata.METADATA_KEY_DISPLAY_TITLE);
            if (title == null) return;
            final String sub = first(m, MediaMetadata.METADATA_KEY_ARTIST, MediaMetadata.METADATA_KEY_ALBUM,
                    MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE);
            long dur = m.getLong(MediaMetadata.METADATA_KEY_DURATION);
            if (!isVideoApp(c.getPackageName(), dur)) return;
            long pos = st.getPosition();
            if (state == PlaybackState.STATE_PLAYING) {
                pos += (long) ((SystemClock.elapsedRealtime() - st.getLastPositionUpdateTime()) * st.getPlaybackSpeed());
            }
            Bitmap art = landscapeArt(m);
            // A wide picture saved earlier must not be overwritten by this session's portrait poster.
            Bitmap toSave = (art != null && (isWide(art) || !NuvioHistory.hasPoster(this, title, sub))) ? art : null;
            String artUri = first(m, MediaMetadata.METADATA_KEY_ART_URI, MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI,
                    MediaMetadata.METADATA_KEY_ALBUM_ART_URI);
            NuvioHistory.record(this, c.getPackageName(), title, sub, toSave, pos, dur,
                    first(m, MediaMetadata.METADATA_KEY_MEDIA_ID), first(m, MediaMetadata.METADATA_KEY_MEDIA_URI), artUri);
            // The app's own "open the player" shortcut: valid while this process lives, used as a resume path.
            try {
                android.app.PendingIntent pi = c.getSessionActivity();
                if (pi != null) SESSION_ACTIVITY.put(title + "|" + (sub == null ? "" : sub), pi);
            } catch (RuntimeException ignored) {
            }
            if (isNuvio(c.getPackageName()) && !NuvioHistory.hasImdb(this, title, sub)
                    && lookedUp.add(title + "|" + (sub == null ? "" : sub))) {
                lookupImdb(title, sub);
            }
            // YouTube-style apps: find the video id by searching its title once, so the card opens that video.
            if (DeepLinks.isYoutubeFamily(c.getPackageName().toLowerCase(Locale.ROOT))
                    && !NuvioHistory.hasImdb(this, title, sub) && lookedUp.add("yt:" + title + "|" + (sub == null ? "" : sub))) {
                lookupYoutube(title, sub);
            }
            // Only a portrait poster (or nothing) so far: look for a wide picture (the episode still) by link.
            if ((art == null || !isWide(art)) && triedArt.add(title + "|" + sub)) {
                String uri = first(m, MediaMetadata.METADATA_KEY_ART_URI, MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI,
                        MediaMetadata.METADATA_KEY_ALBUM_ART_URI);
                if (uri != null) fetchPoster(title, sub, uri);
            }
        } catch (RuntimeException ignored) {
        }
    }

    private final Set<String> triedArt = new HashSet<>();
    private final Set<String> lookedUp = new HashSet<>();

    /** Session shortcuts by entry key (title|subtitle), kept in memory only. */
    private static final java.util.Map<String, android.app.PendingIntent> SESSION_ACTIVITY =
            new java.util.concurrent.ConcurrentHashMap<>();

    static android.app.PendingIntent sessionActivityFor(String key) {
        return key == null ? null : SESSION_ACTIVITY.get(key);
    }

    /** Finds the IMDb id of a title through the public Cinemeta catalogue (the one Stremio / Nuvio use). */
    private void lookupImdb(final String title, final String sub) {
        if (io == null) return;
        io.execute(() -> {
            String id = null, kind = null;
            try {
                String q = Uri.encode(title.replaceAll("\\(\\d{4}\\)\\s*$", "").trim());
                String want = norm(title.replaceAll("\\(\\d{4}\\)\\s*$", ""));
                boolean looksSeries = sub != null && sub.matches("(?is).*\\b(s\\d+|e\\d+|season|episode)\\b.*");
                String[] types = looksSeries ? new String[]{"series", "movie"} : new String[]{"movie", "series"};
                for (String type : types) {
                    String json = httpText("https://v3-cinemeta.strem.io/catalog/" + type + "/top/search=" + q + ".json");
                    org.json.JSONArray metas = new org.json.JSONObject(json).optJSONArray("metas");
                    for (int i = 0; metas != null && i < metas.length(); i++) {
                        org.json.JSONObject m = metas.getJSONObject(i);
                        String mid = m.optString("imdb_id", m.optString("id", ""));
                        if (mid.startsWith("tt") && norm(m.optString("name", "")).equals(want)) {
                            id = mid;
                            kind = type;
                            break;
                        }
                    }
                    if (id != null) break;
                }
            } catch (Exception ignored) {
            }
            final String fid = id, fkind = kind;
            if (fid != null) handler.post(() -> NuvioHistory.setLink(MediaListener.this, title, sub, fid, fkind));
        });
    }

    /** History saved before video ids were looked up: find them now so those cards open the video too. */
    private void backfillYoutube() {
        int n = 0;
        for (NuvioHistory.Entry e : NuvioHistory.load(this)) {
            if (n >= 12) break;
            if (e.pkg == null || !DeepLinks.isYoutubeFamily(e.pkg.toLowerCase(Locale.ROOT))) continue;
            if (!e.imdb.isEmpty() || e.mediaUri.contains("watch") || e.artUri.contains("ytimg")) continue;
            String sub = e.subtitle.isEmpty() ? null : e.subtitle;
            if (lookedUp.add("yt:" + e.title + "|" + (sub == null ? "" : sub))) {
                lookupYoutube(e.title, sub);
                n++;
            }
        }
    }

    /**
     * Finds the YouTube video id for a title (and channel) by reading YouTube's public search page, and only
     * accepts a result whose title matches, so a wrong video is never opened.
     */
    private void lookupYoutube(final String title, final String sub) {
        if (io == null) return;
        io.execute(() -> {
            String id = null;
            try {
                String q = Uri.encode((title + (sub == null ? "" : " " + sub)).trim());
                String html = httpPage("https://www.youtube.com/results?search_query=" + q + "&sp=EgIQAQ%253D%253D");
                id = pickVideoId(html, title);
                if (id == null && sub != null) {          // the channel name can confuse the search: try the title alone
                    html = httpPage("https://www.youtube.com/results?search_query=" + Uri.encode(title) + "&sp=EgIQAQ%253D%253D");
                    id = pickVideoId(html, title);
                }
            } catch (Exception ignored) {
            }
            final String fid = id;
            if (fid != null) handler.post(() -> NuvioHistory.setLink(MediaListener.this, title, sub, fid, "yt"));
        });
    }

    private static final java.util.regex.Pattern VIDEO_RENDERER =
            java.util.regex.Pattern.compile("\"videoRenderer\":\\{\"videoId\":\"([A-Za-z0-9_-]{11})\"");
    private static final java.util.regex.Pattern RUN_TEXT =
            java.util.regex.Pattern.compile("\"title\":\\{\"runs\":\\[\\{\"text\":\"((?:[^\"\\\\]|\\\\.)*)\"");

    private static String pickVideoId(String html, String title) {
        String want = normU(title);
        if (want.isEmpty()) return null;
        java.util.regex.Matcher m = VIDEO_RENDERER.matcher(html);
        int seen = 0;
        while (m.find() && seen++ < 8) {
            int from = m.end();
            String chunk = html.substring(from, Math.min(html.length(), from + 1800));
            java.util.regex.Matcher t = RUN_TEXT.matcher(chunk);
            if (!t.find()) continue;
            String raw = t.group(1);
            try {
                raw = String.valueOf(new org.json.JSONTokener("\"" + raw + "\"").nextValue());
            } catch (Exception ignored) {
            }
            String got = normU(raw);
            if (!got.isEmpty() && (got.equals(want) || got.contains(want) || want.contains(got))) return m.group(1);
        }
        return null;
    }

    /** Letters and digits of any language, lower case: so Hindi and other titles can be compared too. */
    private static String normU(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]", "");
    }

    /** A web page read like a browser would (some sites answer unknown clients with a stripped page). */
    private static String httpPage(String url) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        try {
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(12000);
            conn.setRequestProperty("User-Agent",
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36");
            conn.setRequestProperty("Accept-Language", "en-US,en;q=0.9");
            conn.setRequestProperty("Cookie", "CONSENT=YES+1; SOCS=CAI");
            if (conn.getResponseCode() != 200) throw new java.io.IOException("HTTP " + conn.getResponseCode());
            try (InputStream in = conn.getInputStream()) {
                java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
                byte[] chunk = new byte[16 * 1024];
                int n;
                while ((n = in.read(chunk)) > 0) {
                    buf.write(chunk, 0, n);
                    if (buf.size() > 4 * 1024 * 1024) break;      // the first results are near the top
                }
                return new String(buf.toByteArray(), "UTF-8");
            }
        } finally {
            conn.disconnect();
        }
    }

    private static String norm(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    private static String httpText(String url) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        try {
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(10000);
            conn.setRequestProperty("User-Agent", "TVHome/9");
            if (conn.getResponseCode() != 200) throw new java.io.IOException("HTTP " + conn.getResponseCode());
            try (InputStream in = conn.getInputStream()) {
                java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
                byte[] chunk = new byte[8192];
                int n;
                while ((n = in.read(chunk)) > 0) {
                    buf.write(chunk, 0, n);
                    if (buf.size() > 2 * 1024 * 1024) throw new java.io.IOException("too large");
                }
                return new String(buf.toByteArray(), "UTF-8");
            }
        } finally {
            conn.disconnect();
        }
    }

    private static boolean isWide(Bitmap b) {
        return b != null && b.getHeight() > 0 && b.getWidth() >= b.getHeight() * 1.3f;
    }

    /** The picture the app gives us that is wide (an episode thumbnail) if there is one, else any picture. */
    private static Bitmap landscapeArt(MediaMetadata m) {
        Bitmap[] all = {m.getBitmap(MediaMetadata.METADATA_KEY_ART), m.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON),
                m.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)};
        Bitmap fallback = null;
        for (Bitmap b : all) {
            if (b == null || b.getWidth() <= 0 || b.getHeight() <= 0) continue;
            if (isWide(b)) return b;
            if (fallback == null) fallback = b;
        }
        return fallback;
    }

    /** Artwork given only as a link: fetched once in the background. */
    private void fetchPoster(final String title, final String sub, final String uri) {
        final String key = title + "|" + sub;
        if (io == null || !fetching.add(key)) return;
        io.execute(() -> {
            Bitmap b = null;
            try {
                b = loadUri(uri);
            } catch (Exception | OutOfMemoryError ignored) {
            }
            final Bitmap result = b;
            handler.post(() -> {
                fetching.remove(key);
                // A downloaded wide picture replaces a portrait poster; anything else only fills an empty slot.
                if (result != null && (isWide(result) || !NuvioHistory.hasPoster(MediaListener.this, title, sub))) {
                    NuvioHistory.setPoster(MediaListener.this, title, sub, result);
                }
            });
        });
    }

    private Bitmap loadUri(String uriString) throws Exception {
        Uri uri = Uri.parse(uriString);
        String scheme = uri.getScheme();
        if (scheme == null) return null;
        InputStream in = null;
        HttpURLConnection conn = null;
        try {
            if (scheme.equals("http") || scheme.equals("https")) {
                conn = (HttpURLConnection) new URL(uriString).openConnection();
                conn.setConnectTimeout(8000);
                conn.setReadTimeout(8000);
                in = conn.getInputStream();
            } else {
                in = getContentResolver().openInputStream(uri);
            }
            if (in == null) return null;
            // Read it (at most 8 MB), then decode it small: the history only needs ~640 px.
            java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
            byte[] chunk = new byte[16 * 1024];
            int n;
            while ((n = in.read(chunk)) > 0) {
                buf.write(chunk, 0, n);
                if (buf.size() > 8 * 1024 * 1024) return null;
            }
            byte[] data = buf.toByteArray();
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(data, 0, data.length, o);
            int sample = 1;
            while (Math.max(o.outWidth, o.outHeight) / (sample * 2) >= 640) sample *= 2;
            o.inJustDecodeBounds = false;
            o.inSampleSize = sample;
            return BitmapFactory.decodeByteArray(data, 0, data.length, o);
        } finally {
            try {
                if (in != null) in.close();
            } catch (Exception ignored) {
            }
            if (conn != null) conn.disconnect();
        }
    }
}
