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
