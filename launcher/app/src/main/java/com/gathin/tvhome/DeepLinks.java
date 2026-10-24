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

        if (p.contains("youtube")) {
            String id = firstMatch(YT_ID, e.mediaUri, e.mediaId, e.artUri);
            if (id == null && e.mediaId != null && e.mediaId.matches("[A-Za-z0-9_-]{11}")) id = e.mediaId;
            if (id != null) {
                Intent i = new Intent(Intent.ACTION_VIEW,
                        Uri.parse("https://www.youtube.com/watch?v=" + id + (sec > 5 ? "&t=" + sec : "")));
                i.setPackage(pkg);
                if (handles(pm, i)) return i;
            }
            return null;
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

        // Players that take a link and a start position (VLC and MX Player style).
        if (e.mediaUri != null && (e.mediaUri.startsWith("http://") || e.mediaUri.startsWith("https://"))
                && (p.contains("vlc") || p.contains("mxtech") || p.contains("mxplayer"))) {
            Intent i = new Intent(Intent.ACTION_VIEW);
            i.setDataAndType(Uri.parse(e.mediaUri), "video/*");
            i.setPackage(pkg);
            i.putExtra("position", e.position);
            i.putExtra("from_start", false);
            if (handles(pm, i)) return i;
        }
        return null;
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
