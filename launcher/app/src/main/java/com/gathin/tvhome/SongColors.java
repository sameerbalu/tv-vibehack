package com.gathin.tvhome;

import android.graphics.Bitmap;
import android.graphics.Color;
import android.media.MediaMetadata;

import java.util.ArrayList;
import java.util.List;

/** Picks a small palette (3-4 colours) from a song's artwork, so the glow can drift between them. */
final class SongColors {
    private SongColors() {
    }

    static Bitmap artOf(MediaMetadata m) {
        if (m == null) return null;
        Bitmap art = m.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
        if (art == null) art = m.getBitmap(MediaMetadata.METADATA_KEY_ART);
        if (art == null) art = m.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON);
        return art;
    }

    /** Same song, same id (used to avoid restarting the colour cycle on every metadata callback). */
    static String idOf(MediaMetadata m) {
        if (m == null) return "";
        return String.valueOf(m.getString(MediaMetadata.METADATA_KEY_TITLE)) + "|"
                + m.getString(MediaMetadata.METADATA_KEY_ARTIST);
    }

    static int[] palette(MediaMetadata m) {
        Bitmap art = artOf(m);
        List<Float> hues = new ArrayList<>();
        List<Integer> colors = new ArrayList<>();
        if (art != null && art.getWidth() > 0 && art.getHeight() > 0) {
            Bitmap small = Bitmap.createScaledBitmap(art, 16, 16, true);
            float[] hsv = new float[3];
            final int buckets = 12;
            float[] weight = new float[buckets];
            float[] bestScore = new float[buckets];
            int[] bestColor = new int[buckets];
            for (int y = 0; y < 16; y++) {
                for (int x = 0; x < 16; x++) {
                    int p = small.getPixel(x, y);
                    Color.colorToHSV(p, hsv);
                    float score = hsv[1] * Math.min(1f, hsv[2] * 1.4f);
                    if (score < 0.3f) continue;
                    int b = Math.min(buckets - 1, (int) (hsv[0] / (360f / buckets)));
                    weight[b] += score;
                    if (score > bestScore[b]) {
                        bestScore[b] = score;
                        bestColor[b] = p;
                    }
                }
            }
            if (small != art) small.recycle();
            for (int n = 0; n < 4; n++) {
                int top = -1;
                for (int b = 0; b < buckets; b++) if (weight[b] > 0 && (top < 0 || weight[b] > weight[top])) top = b;
                if (top < 0) break;
                weight[top] = 0;
                Color.colorToHSV(bestColor[top], hsv);
                boolean close = false;
                for (float h : hues) {
                    float d = Math.abs(h - hsv[0]);
                    if (Math.min(d, 360f - d) < 28f) close = true;
                }
                if (close) continue;
                hues.add(hsv[0]);
                colors.add(vivid(hsv[0], hsv[1], hsv[2]));
            }
            if (colors.isEmpty()) {
                // Grey or very dark artwork: start from its average brightness and a hue from the title.
                colors.add(vivid(hashHue(m), 0.6f, 0.9f));
            }
        } else {
            colors.add(m == null ? 0xFF1DB954 : vivid(hashHue(m), 0.75f, 0.9f));
        }
        // Not enough distinct colours in the art: rotate the hue so the glow still travels.
        float[] base = new float[3];
        Color.colorToHSV(colors.get(0), base);
        int step = 1;
        while (colors.size() < 3) {
            colors.add(vivid((base[0] + 55f * step) % 360f, base[1], base[2]));
            step++;
        }
        int[] out = new int[colors.size()];
        for (int i = 0; i < out.length; i++) out[i] = colors.get(i);
        return out;
    }

    private static float hashHue(MediaMetadata m) {
        return Math.abs(idOf(m).hashCode()) % 360;
    }

    private static int vivid(float h, float s, float v) {
        return Color.HSVToColor(new float[]{h, Math.max(0.55f, Math.min(1f, s * 1.1f)), Math.max(0.6f, v)});
    }
}
