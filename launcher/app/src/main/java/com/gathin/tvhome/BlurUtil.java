package com.gathin.tvhome;

import android.graphics.Bitmap;

/**
 * Small software blur (three box-blur passes ~ gaussian). Works on every API level, so the launcher
 * does not depend on RenderEffect (Android 12+), which most TVs do not have.
 * The image is shrunk first; stretched back to full screen it looks like a large, smooth blur.
 */
final class BlurUtil {
    private BlurUtil() {}

    static Bitmap blur(Bitmap src, int maxDim, int radius) {
        float s = Math.min(1f, (float) maxDim / Math.max(src.getWidth(), src.getHeight()));
        int w = Math.max(1, Math.round(src.getWidth() * s));
        int h = Math.max(1, Math.round(src.getHeight() * s));
        Bitmap small = Bitmap.createScaledBitmap(src, w, h, true);
        int[] a = new int[w * h];
        int[] b = new int[w * h];
        small.getPixels(a, 0, w, 0, 0, w, h);
        for (int i = 0; i < 3; i++) {
            pass(a, b, w, h, radius, true);
            pass(b, a, w, h, radius, false);
        }
        Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        out.setPixels(a, 0, w, 0, 0, w, h);
        return out;
    }

    private static void pass(int[] src, int[] dst, int w, int h, int r, boolean horizontal) {
        int lines = horizontal ? h : w;
        int len = horizontal ? w : h;
        int step = horizontal ? 1 : w;
        int lineStep = horizontal ? w : 1;
        int div = 2 * r + 1;
        for (int l = 0; l < lines; l++) {
            int base = l * lineStep;
            int sr = 0, sg = 0, sb = 0;
            for (int i = -r; i <= r; i++) {
                int p = src[base + clamp(i, len) * step];
                sr += (p >> 16) & 0xFF;
                sg += (p >> 8) & 0xFF;
                sb += p & 0xFF;
            }
            for (int i = 0; i < len; i++) {
                dst[base + i * step] = 0xFF000000 | (sr / div) << 16 | (sg / div) << 8 | (sb / div);
                int add = src[base + clamp(i + r + 1, len) * step];
                int sub = src[base + clamp(i - r, len) * step];
                sr += ((add >> 16) & 0xFF) - ((sub >> 16) & 0xFF);
                sg += ((add >> 8) & 0xFF) - ((sub >> 8) & 0xFF);
                sb += (add & 0xFF) - (sub & 0xFF);
            }
        }
    }

    private static int clamp(int i, int len) {
        return i < 0 ? 0 : (i >= len ? len - 1 : i);
    }
}
