package com.gathin.tvhome;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Builds an intent that opens one specific video in the app it was playing in, from what the app told us
 * (media id, link, artwork link) and what we looked up. Only intents the app really handles are returned
 * (checked with resolveActivity), so a wrong guess never shows an error: the caller falls back to opening the app.
 */
final class DeepLinks {
    private static final Pattern YT_ID = Pattern.compile("(?:ytimg\\.com/vi(?:_webp)?/|[?&]v=|youtu\\.be/|/watch/|/embed/)([A-Za-z0-9_-]{11})");
    private static final Pattern IMDB = Pattern.compile("tt\\d{6,10}");

    private DeepLinks() {
    }

    static boolean isNuvioFamily(String pkg) {
        if (pkg == null) return false;
        String p = pkg.toLowerCase(Locale.ROOT);
        return p.contains("nuvio");
    }

    private static final Pattern SEASON_EP = Pattern.compile(
            "(?i)\\bs(?:eason)?\\s*(\\d{1,2})\\s*[:.\\-\\u00b7x, ]*\\s*e(?:p(?:isode)?)?\\s*(\\d{1,3})\\b|\\b(\\d{1,2})x(\\d{1,3})\\b");

    /** {season, episode} found in the title / subtitle text, or null. */
    static int[] seasonEpisode(NuvioHistory.Entry e) {
        for (String t : new String[]{e.subtitle, e.title}) {
            if (t == null) continue;
            Matcher m = SEASON_EP.matcher(t);
            if (m.find()) {
                String a = m.group(1) != null ? m.group(1) : m.group(3);
                String b = m.group(2) != null ? m.group(2) : m.group(4);
                try {
                    return new int[]{Integer.parseInt(a), Integer.parseInt(b)};
                } catch (NumberFormatException ignored) {
                }
            }
        }
        return null;
    }

    /**
     * Nuvio's own "continue watching" launch: its main screen reads these extras and goes straight to the
     * stream list of that exact movie / episode. Without a season and episode it opens the title's page instead.
     */
    static Intent nuvioPlay(Context ctx, String pkg, NuvioHistory.Entry e, String imdb, String kind) {
        PackageManager pm = ctx.getPackageManager();
        // The activity that handles nuvio:// links is the app's main screen.
        Intent probe = new Intent(Intent.ACTION_VIEW, Uri.parse("nuvio://detail/movie/" + imdb));
        probe.setPackage(pkg);
        android.content.pm.ResolveInfo ri = pm.resolveActivity(probe, 0);
        if (ri == null || ri.activityInfo == null) return null;
        int[] se = seasonEpisode(e);
        boolean series = "series".equals(kind) || (kind == null || kind.isEmpty()) && se != null;
        Intent i = new Intent();
        i.setComponent(new android.content.ComponentName(ri.activityInfo.packageName, ri.activityInfo.name));
        i.putExtra("contentId", imdb);
        i.putExtra("contentType", series ? "series" : "movie");
        i.putExtra("name", e.title);
        if (series && se == null) return null;               // cannot name the episode: caller opens the title page
        i.putExtra("launchMode", "stream");
        i.putExtra("videoId", series ? imdb + ":" + se[0] + ":" + se[1] : imdb);
        if (series) {
            i.putExtra("season", se[0]);
            i.putExtra("episode", se[1]);
            if (e.subtitle != null && !e.subtitle.isEmpty()) i.putExtra("episodeTitle", e.subtitle);
        }
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        return i;
    }

    private static String normName(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "");
    }

    private static String httpGet(String url) throws Exception {
        java.net.HttpURLConnection c = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
        c.setConnectTimeout(7000);
        c.setReadTimeout(7000);
        try (java.io.InputStream in = c.getInputStream()) {
            java.io.ByteArrayOutputStream b = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
            return b.toString("UTF-8");
        } finally {
            c.disconnect();
        }
    }

    /** IMDb id and type ({id, kind}) of a title, from the public Cinemeta catalogue; blocking, call off the main thread. */
    static String[] lookupImdb(String title, String sub, boolean preferSeries) {
        if (title == null) return null;
        String base = title.replaceAll("\\(\\d{4}\\)\\s*$", "");
        java.util.List<String> cands = new java.util.ArrayList<>();
        cands.add(base.trim());
        for (String sep : new String[]{" - ", " \u2013 ", ": ", " | "}) {
            int k = base.indexOf(sep);
            if (k > 2) cands.add(base.substring(0, k).trim());
        }
        String noSe = SEASON_EP.matcher(base).replaceAll("").trim();
        if (noSe.length() > 2) cands.add(noSe);
        boolean series = preferSeries || seasonEpisode(fake(title, sub)) != null;
        String[] types = series ? new String[]{"series", "movie"} : new String[]{"movie", "series"};
        for (int pass = 0; pass < 2; pass++) {              // pass 0: exact name, pass 1: one name inside the other
            for (String cand : cands) {
                if (cand.length() < 2) continue;
                String want = normName(cand);
                for (String type : types) {
                    try {
                        String json = httpGet("https://v3-cinemeta.strem.io/catalog/" + type + "/top/search="
                                + Uri.encode(cand) + ".json");
                        org.json.JSONArray metas = new org.json.JSONObject(json).optJSONArray("metas");
                        for (int i = 0; metas != null && i < metas.length(); i++) {
                            org.json.JSONObject m = metas.getJSONObject(i);
                            String id = m.optString("imdb_id", m.optString("id", ""));
                            String name = normName(m.optString("name", ""));
                            if (!id.startsWith("tt") || name.isEmpty()) continue;
                            boolean ok = pass == 0 ? name.equals(want)
                                    : want.length() >= 5 && (name.contains(want) || want.contains(name)) && name.length() >= 4;
                            if (ok) return new String[]{id, type};
                        }
                    } catch (Exception ignored) {
                    }
                }
            }
        }
        return null;
    }

    private static NuvioHistory.Entry fake(String title, String sub) {
        NuvioHistory.Entry e = new NuvioHistory.Entry();
        e.title = title == null ? "" : title;
        e.subtitle = sub == null ? "" : sub;
        return e;
    }

    static Intent build(Context ctx, String pkg, NuvioHistory.Entry e) {
        if (pkg == null || e == null) return null;
        String p = pkg.toLowerCase(Locale.ROOT);
        PackageManager pm = ctx.getPackageManager();
        long sec = Math.max(0L, e.position / 1000L);

        if (isYoutubeFamily(p)) {
            String id = firstMatch(YT_ID, e.mediaUri, e.mediaId, e.artUri);
            if (id == null && "yt".equals(e.kind) && e.imdb != null && e.imdb.matches("[A-Za-z0-9_-]{11}")) id = e.imdb;
            if (id == null && e.mediaId != null && e.mediaId.matches("[A-Za-z0-9_-]{11}")) id = e.mediaId;
            if (id != null) {
                String t = sec > 5 ? "&t=" + sec : "";
                String[] links = {
                        "https://www.youtube.com/watch?v=" + id + t,
                        "https://youtu.be/" + id + (sec > 5 ? "?t=" + sec : ""),
                        "https://www.youtube.com/tv?v=" + id,
                        "vnd.youtube://" + id,
                        "vnd.youtube:" + id};
                for (String l : links) {
                    Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(l));
                    i.setPackage(pkg);
                    if (handles(pm, i)) return i;
                }
                // Modified YouTube apps (TizenTube, ...) may not list a video link in their filters: hand the
                // link straight to their main screen, which is where they read a start link from.
                Intent main = pm.getLeanbackLaunchIntentForPackage(pkg);
                if (main == null) main = pm.getLaunchIntentForPackage(pkg);
                if (main != null && main.getComponent() != null) {
                    Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(links[0]));
                    i.setComponent(main.getComponent());
                    return i;
                }
            }
        }

        if (p.contains("nuvio") || p.contains("stremio")) {
            String imdb = e.imdb != null && !e.imdb.isEmpty() ? e.imdb : firstMatch(IMDB, e.mediaId, e.mediaUri, e.artUri);
            if (imdb == null) return null;
            boolean series = "series".equals(e.kind) || (e.kind == null || e.kind.isEmpty())
                    && e.subtitle != null && e.subtitle.matches("(?is).*\\b(s\\d+|e\\d+|season|episode)\\b.*");
            String[] links = p.contains("nuvio")
                    ? new String[]{series ? "nuvio://detail/tv/" + imdb : "nuvio://movie/" + imdb,
                    series ? "nuvio://tv/" + imdb : "nuvio://detail/movie/" + imdb}
                    : new String[]{"stremio:///detail/" + (series ? "series/" : "movie/") + imdb + "/" + imdb};
            for (String l : links) {
                Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(l));
                i.setPackage(pkg);
                if (handles(pm, i)) return i;
            }
            return null;
        }

        // A link the app itself reported for this video (its own scheme, or a web address).
        for (String l : new String[]{e.mediaUri, e.mediaId}) {
            if (l == null || !l.contains("://")) continue;
            Uri u;
            try {
                u = Uri.parse(l);
            } catch (RuntimeException ex) {
                continue;
            }
            Intent plain = new Intent(Intent.ACTION_VIEW, u);
            plain.setPackage(pkg);
            plain.putExtra("position", e.position);
            if (handles(pm, plain)) return plain;
            if (l.startsWith("http://") || l.startsWith("https://")) {
                Intent video = new Intent(Intent.ACTION_VIEW);
                video.setDataAndType(u, "video/*");
                video.setPackage(pkg);
                video.putExtra("position", e.position);
                video.putExtra("from_start", false);
                if (handles(pm, video)) return video;
            }
        }
        return null;
    }

    /**
     * Last resort before just opening the app: ask it to play the title by name (the same request a voice
     * search makes). Apps that support it jump to that title; others do not resolve and are skipped.
     */
    static Intent searchIntent(Context ctx, String pkg, NuvioHistory.Entry e) {
        if (pkg == null || e == null || e.title == null || e.title.isEmpty()) return null;
        PackageManager pm = ctx.getPackageManager();
        Intent i = new Intent("android.media.action.MEDIA_PLAY_FROM_SEARCH");
        i.setPackage(pkg);
        i.putExtra(android.app.SearchManager.QUERY, e.title);
        i.putExtra("android.intent.extra.title", e.title);
        i.putExtra("android.intent.extra.focus", "vnd.android.cursor.item/video");
        return handles(pm, i) ? i : null;
    }

    /** YouTube and its look-alikes: the official TV app, TizenTube, SmartTube, ReVanced, NewPipe. */
    static boolean isYoutubeFamily(String pkgLower) {
        if (pkgLower == null) return false;
        if (pkgLower.contains("youtube") && pkgLower.contains("music")) return false;
        String[] names = {"youtube", "tizentube", "smarttube", "teamsmart", "liskovsoft", "revanced", "vanced",
                "newpipe", "cobalt"};
        for (String n : names) if (pkgLower.contains(n)) return true;
        return false;
    }

    private static boolean handles(PackageManager pm, Intent i) {
        try {
            return pm.resolveActivity(i, 0) != null;
        } catch (RuntimeException ex) {
            return false;
        }
    }

    private static String firstMatch(Pattern pat, String... texts) {
        for (String t : texts) {
            if (t == null) continue;
            Matcher m = pat.matcher(t);
            if (m.find()) return m.groupCount() >= 1 ? m.group(1) : m.group();
        }
        return null;
    }
}
