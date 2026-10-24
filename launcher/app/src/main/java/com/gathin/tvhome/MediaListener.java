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
            "itvx", "channel4", "canalplus", "rakuten", "kinopoisk", "hbomax"};
    /** Music, podcast and other audio apps: never listed, whatever they report. */
    private static final String[] AUDIO_APPS = {
            "spotify", "music", "deezer", "tidal", "soundcloud", "pandora", "shazam", "audible", "podcast",
            "radio", "tunein", "jiosaavn", "gaana", "wynk", "hungama", "napster", "qobuz", "audio"};

    /** True for apps whose playing video should be remembered. */
    static boolean isVideoApp(String pkg, long durationMs) {
        if (pkg == null) return false;
        String p = pkg.toLowerCase(Locale.ROOT);
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
            NuvioHistory.record(this, c.getPackageName(), title, sub, toSave, pos, dur);
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
