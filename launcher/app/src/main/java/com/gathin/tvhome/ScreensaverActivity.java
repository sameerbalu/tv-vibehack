package com.gathin.tvhome;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageView;

/** Screen saver preview: just the wallpaper with a large clock. Any key press or touch returns Home. */
public class ScreensaverActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(R.layout.screensaver);

        ImageView wallpaper = findViewById(R.id.saverWallpaper);
        String saved = getSharedPreferences("launcher_preferences", MODE_PRIVATE).getString("wallpaper_uri", null);
        if (saved != null) {
            try {
                wallpaper.setImageBitmap(decode(Uri.parse(saved)));
            } catch (Exception ignored) {
                // No readable wallpaper: the clock simply shows on black.
            }
        }
        View clock = findViewById(R.id.saverClock);
        clock.setAlpha(0f);
        clock.animate().alpha(1f).setDuration(1200).start();
    }

    private Bitmap decode(Uri uri) throws Exception {
        int targetW = Math.max(1, getResources().getDisplayMetrics().widthPixels);
        int targetH = Math.max(1, getResources().getDisplayMetrics().heightPixels);
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        try (java.io.InputStream in = getContentResolver().openInputStream(uri)) {
            BitmapFactory.decodeStream(in, null, bounds);
        }
        int sample = 1;
        while (bounds.outWidth / sample > targetW * 2 || bounds.outHeight / sample > targetH * 2) sample *= 2;
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = sample;
        options.inPreferredConfig = Bitmap.Config.RGB_565;
        try (java.io.InputStream in = getContentResolver().openInputStream(uri)) {
            Bitmap bitmap = BitmapFactory.decodeStream(in, null, options);
            if (bitmap == null) throw new IllegalArgumentException("Unable to decode wallpaper");
            return bitmap;
        }
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getAction() == KeyEvent.ACTION_DOWN) finish();
        return true;
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (event.getAction() == MotionEvent.ACTION_DOWN) finish();
        return true;
    }
}
