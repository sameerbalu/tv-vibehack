package com.gathin.tvhome;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;

/** Built-in wallpapers, painted in code (no image files in the app). */
final class WallpaperPresets {

    private WallpaperPresets() {
    }

    static final int[] NAMES = {
            R.string.preset_midnight, R.string.preset_sunset, R.string.preset_forest,
            R.string.preset_ember, R.string.preset_ocean, R.string.preset_graphite
    };
    static final int COUNT = NAMES.length;

    /** {top-left, bottom-right, glow 1, glow 2, glow 3} */
    private static final int[][] PALETTES = {
            {0xFF0B1030, 0xFF2A1250, 0xFF3B6CFF, 0xFF8B45FF, 0xFF12C2B0},
            {0xFF1A0B2E, 0xFF5B1A3A, 0xFFFF7A3D, 0xFFE5559A, 0xFFFFB347},
            {0xFF04140F, 0xFF0B3B2E, 0xFF12C2B0, 0xFF2ECC71, 0xFF0E7C86},
            {0xFF120406, 0xFF3A0A10, 0xFFB3121B, 0xFFE0661B, 0xFF7A0C12},
            {0xFF021224, 0xFF06406B, 0xFF1E90FF, 0xFF00C2D1, 0xFF4F46E5},
            {0xFF0D0E11, 0xFF23262D, 0xFF5A6275, 0xFF3A3F4B, 0xFF7C8294}
    };
    private static final float[][] SPOTS = {{0.18f, 0.25f, 0.55f}, {0.82f, 0.35f, 0.5f}, {0.55f, 0.92f, 0.6f}};

    static Bitmap render(int index, int w, int h) {
        int[] p = PALETTES[Math.max(0, Math.min(index, PALETTES.length - 1))];
        Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setShader(new LinearGradient(0, 0, w, h, p[0], p[1], Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, h, paint);
        for (int k = 0; k < SPOTS.length; k++) {
            int rgb = p[2 + k] & 0x00FFFFFF;
            paint.setShader(new RadialGradient(w * SPOTS[k][0], h * SPOTS[k][1], w * SPOTS[k][2],
                    0x80000000 | rgb, rgb, Shader.TileMode.CLAMP));
            c.drawRect(0, 0, w, h, paint);
        }
        paint.setShader(new RadialGradient(w / 2f, h / 2f, w * 0.75f, 0x00000000, 0x99000000, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, h, paint);
        return b;
    }
}
