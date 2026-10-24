package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * What was being watched in Nuvio, newest first. Filled by MediaListener from the playback Nuvio reports
 * (title, artwork, position) and shown in the All apps grid (ContinueAdapter) as "Continue watching".
 */
final class NuvioHistory {

    static final class Entry {
        String key = "";
        String pkg = "";
        String title = "";
        String subtitle = "";
        String poster = "";          // file name inside the posters folder, or empty
        long position;
        long duration;
        long time;
        /** What the app told us about the video: used to open exactly this one again. */
        String mediaId = "";
        String mediaUri = "";
        String artUri = "";
        /** IMDb id and "movie" / "series" (looked up for Nuvio), or empty. */
        String imdb = "";
        String kind = "";
    }

    /** Entries older than this (since last watched) leave the list. */
    static final long MAX_AGE_MS = 48L * 60L * 60L * 1000L;

    private static final String FILE = "nuvio_history.json";
    private static final String POSTERS = "nuvio_posters";

    private NuvioHistory() {
    }

    private static String keyOf(String title, String subtitle) {
        return title + "|" + (subtitle == null ? "" : subtitle);
    }

    static synchronized List<Entry> load(Context c) {
        List<Entry> out = new ArrayList<>();
        File f = new File(c.getFilesDir(), FILE);
        if (!f.isFile()) return out;
        try {
            byte[] data = new byte[(int) f.length()];
            try (java.io.FileInputStream in = new java.io.FileInputStream(f)) {
                int off = 0;
                while (off < data.length) {
                    int n = in.read(data, off, data.length - off);
                    if (n < 0) break;
                    off += n;
                }
            }
            JSONArray arr = new JSONArray(new String(data, "UTF-8"));
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                Entry e = new Entry();
                e.key = o.optString("key");
                e.pkg = o.optString("pkg");
                e.title = o.optString("title");
                e.subtitle = o.optString("subtitle");
                e.poster = o.optString("poster");
                e.position = o.optLong("position");
                e.duration = o.optLong("duration");
                e.time = o.optLong("time");
                e.mediaId = o.optString("mid");
                e.mediaUri = o.optString("muri");
                e.artUri = o.optString("auri");
                e.imdb = o.optString("imdb");
                e.kind = o.optString("kind");
                if (!e.title.isEmpty()) out.add(e);
            }
        } catch (Exception ignored) {
            // A damaged file just means an empty list; it is rewritten on the next record().
        }
        // 48 hours after it was last watched a poster disappears (with its saved picture).
        long now = System.currentTimeMillis();
        boolean expired = false;
        for (int i = out.size() - 1; i >= 0; i--) {
            Entry e = out.get(i);
            if (e.time > 0 && now - e.time > MAX_AGE_MS) {
                deletePoster(c, e);
                out.remove(i);
                expired = true;
            }
        }
        if (expired) save(c, out);
        return out;
    }

    private static void save(Context c, List<Entry> list) {
        try {
            JSONArray arr = new JSONArray();
            for (Entry e : list) {
                JSONObject o = new JSONObject();
                o.put("key", e.key);
                o.put("pkg", e.pkg);
                o.put("title", e.title);
                o.put("subtitle", e.subtitle);
                o.put("poster", e.poster);
                o.put("position", e.position);
                o.put("duration", e.duration);
                o.put("time", e.time);
                o.put("mid", e.mediaId);
                o.put("muri", e.mediaUri);
                o.put("auri", e.artUri);
                o.put("imdb", e.imdb);
                o.put("kind", e.kind);
                arr.put(o);
            }
            File tmp = new File(c.getFilesDir(), FILE + ".tmp");
            try (OutputStream out = new FileOutputStream(tmp)) {
                out.write(arr.toString().getBytes("UTF-8"));
            }
            File dst = new File(c.getFilesDir(), FILE);
            dst.delete();
            tmp.renameTo(dst);
        } catch (Exception ignored) {
        }
    }

    /** Notes that this title is being watched (or was just paused) at this position. */
    static synchronized void record(Context c, String pkg, String title, String subtitle, Bitmap art,
                                    long position, long duration) {
        record(c, pkg, title, subtitle, art, position, duration, null, null, null);
    }

    static synchronized void record(Context c, String pkg, String title, String subtitle, Bitmap art,
                                    long position, long duration, String mediaId, String mediaUri, String artUri) {
        if (title == null || title.trim().isEmpty()) return;
        title = title.trim();
        String key = keyOf(title, subtitle);
        List<Entry> list = load(c);
        Entry e = null;
        for (Entry x : list) {
            if (x.key.equals(key)) {
                e = x;
                break;
            }
        }
        // Finished (last 5%): it no longer belongs in "continue watching".
        if (duration > 0 && position > duration * 0.95) {
            if (e != null) {
                list.remove(e);
                deletePoster(c, e);
                save(c, list);
            }
            return;
        }
        if (e == null) e = new Entry();
        else list.remove(e);
        list.add(0, e);
        e.key = key;
        e.pkg = pkg == null ? "" : pkg;
        e.title = title;
        e.subtitle = subtitle == null ? "" : subtitle.trim();
        e.time = System.currentTimeMillis();
        if (mediaId != null && !mediaId.isEmpty()) e.mediaId = mediaId;
        if (mediaUri != null && !mediaUri.isEmpty()) e.mediaUri = mediaUri;
        if (artUri != null && !artUri.isEmpty()) e.artUri = artUri;
        if (duration > 0) {
            e.duration = duration;
            e.position = Math.max(0, position);
        }
        if (art != null && art.getWidth() > 0 && art.getHeight() > 0) e.poster = writePoster(c, key, art);
        save(c, list);
    }

    static synchronized boolean hasPoster(Context c, String title, String subtitle) {
        String key = keyOf(title == null ? "" : title.trim(), subtitle);
        for (Entry e : load(c)) {
            if (e.key.equals(key)) return !e.poster.isEmpty() && new File(posterDir(c), e.poster).isFile();
        }
        return false;
    }

    /** Adds artwork that arrived later (downloaded) to an entry that has none yet. */
    static synchronized void setPoster(Context c, String title, String subtitle, Bitmap art) {
        if (art == null) return;
        String key = keyOf(title == null ? "" : title.trim(), subtitle);
        List<Entry> list = load(c);
        for (Entry e : list) {
            if (e.key.equals(key)) {
                e.poster = writePoster(c, key, art);
                save(c, list);
                return;
            }
        }
    }

    /** Remembers which title (IMDb id) this entry is, so its page can be opened later. */
    static synchronized void setLink(Context c, String title, String subtitle, String imdb, String kind) {
        String key = keyOf(title == null ? "" : title.trim(), subtitle);
        List<Entry> list = load(c);
        for (Entry e : list) {
            if (e.key.equals(key)) {
                e.imdb = imdb == null ? "" : imdb;
                e.kind = kind == null ? "" : kind;
                save(c, list);
                return;
            }
        }
    }

    static synchronized boolean hasImdb(Context c, String title, String subtitle) {
        String key = keyOf(title == null ? "" : title.trim(), subtitle);
        for (Entry e : load(c)) if (e.key.equals(key)) return !e.imdb.isEmpty();
        return false;
    }

    /** Empties the whole Continue watching list (and deletes the saved artwork). */
    static synchronized void clear(Context c) {
        List<Entry> list = load(c);
        for (Entry e : list) deletePoster(c, e);
        list.clear();
        save(c, list);
    }

    static synchronized void remove(Context c, Entry target) {
        List<Entry> list = load(c);
        for (Entry e : list) {
            if (e.key.equals(target.key)) {
                list.remove(e);
                deletePoster(c, e);
                break;
            }
        }
        save(c, list);
    }

    private static File posterDir(Context c) {
        File d = new File(c.getFilesDir(), POSTERS);
        if (!d.isDirectory()) d.mkdirs();
        return d;
    }

    private static String writePoster(Context c, String key, Bitmap art) {
        String name = "n" + Integer.toHexString(key.hashCode()) + ".jpg";
        try {
            int max = 640;
            float scale = Math.min(1f, max / (float) Math.max(art.getWidth(), art.getHeight()));
            Bitmap small = scale < 1f
                    ? Bitmap.createScaledBitmap(art, Math.max(1, Math.round(art.getWidth() * scale)),
                    Math.max(1, Math.round(art.getHeight() * scale)), true)
                    : art;
            try (OutputStream out = new FileOutputStream(new File(posterDir(c), name))) {
                small.compress(Bitmap.CompressFormat.JPEG, 88, out);
            }
            if (small != art) small.recycle();
            return name;
        } catch (Exception | OutOfMemoryError e) {
            return "";
        }
    }

    private static void deletePoster(Context c, Entry e) {
        if (e.poster != null && !e.poster.isEmpty()) new File(posterDir(c), e.poster).delete();
    }

    static Bitmap loadPoster(Context c, Entry e) {
        if (e.poster == null || e.poster.isEmpty()) return null;
        try {
            return BitmapFactory.decodeFile(new File(posterDir(c), e.poster).getPath());
        } catch (Exception | OutOfMemoryError ex) {
            return null;
        }
    }
}
