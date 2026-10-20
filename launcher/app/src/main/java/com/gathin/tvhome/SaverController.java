package com.gathin.tvhome;

import android.content.ComponentName;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Outline;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.widget.ImageView;
import android.widget.TextView;

import java.io.InputStream;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;

/**
 * Drives the screensaver layout (dream_clock.xml) for both the real screensaver and the preview:
 * the user's wallpaper as background (the aurora when none is set), the clock fixed in the top-left
 * corner, a greeting, how much of the day has passed, and the Spotify song that is playing.
 */
final class SaverController {

    private static final long INFO_MS = 4_000L;

    private final Context ctx;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final AuroraView aurora;
    private final ImageView wallpaper;
    private Bitmap wallpaperBitmap;
    private final View content;
    private final TextView greeting, dayLabel, nowTitle, nowArtist;
    private final ColorBar dayBar;
    private final View nowRow;
    private final ImageView nowArt;
    private final TextView quote;
    private final ImageView front;
    private Bitmap frontBitmap;
    private int quoteIndex;
    private int ticks;
    private String songId = "";
    private boolean running;

    private final Runnable info = new Runnable() {
        @Override
        public void run() {
            updateInfo();
            handler.postDelayed(this, INFO_MS);
        }
    };

    SaverController(Context ctx, View root) {
        this.ctx = ctx;
        aurora = root.findViewById(R.id.saverAurora);
        wallpaper = root.findViewById(R.id.saverWallpaper);
        content = root.findViewById(R.id.saverContent);
        greeting = root.findViewById(R.id.saverGreeting);
        dayLabel = root.findViewById(R.id.saverDayLabel);
        dayBar = root.findViewById(R.id.saverDayBar);
        nowRow = root.findViewById(R.id.saverNowRow);
        nowArt = root.findViewById(R.id.saverNowArt);
        nowTitle = root.findViewById(R.id.saverNowTitle);
        nowArtist = root.findViewById(R.id.saverNowArtist);
        quote = root.findViewById(R.id.saverQuote);
        front = root.findViewById(R.id.saverFront);
        final float d = ctx.getResources().getDisplayMetrics().density;
        nowArt.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View v, Outline o) {
                o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), 8 * d);
            }
        });
        nowArt.setClipToOutline(true);
    }

    void start() {
        running = true;
        loadBackground();
        loadFront();
        content.setAlpha(0f);
        content.animate().alpha(1f).setDuration(1500).start();
        quoteIndex = Calendar.getInstance().get(Calendar.DAY_OF_YEAR);
        quote.setText(Quotes.forDay(quoteIndex));
        quote.setAlpha(0f);
        quote.animate().alpha(1f).setDuration(2000).start();
        ticks = 0;
        updateInfo();
        handler.postDelayed(info, INFO_MS);
    }

    void stop() {
        running = false;
        handler.removeCallbacksAndMessages(null);
        content.animate().cancel();
        quote.animate().cancel();
        wallpaper.setImageDrawable(null);          // give the picture's memory back
        if (wallpaperBitmap != null) {
            wallpaperBitmap.recycle();
            wallpaperBitmap = null;
        }
        front.setImageDrawable(null);
        if (frontBitmap != null) {
            frontBitmap.recycle();
            frontBitmap = null;
        }
    }

    /** Shows the wallpaper chosen in Settings; without one the aurora is the background. */
    private void loadBackground() {
        Bitmap bmp = null;
        try {
            if (WallpaperStore.exists(ctx)) bmp = decodeWallpaper();
        } catch (Exception | OutOfMemoryError ignored) {
        }
        if (bmp != null) {
            wallpaperBitmap = bmp;
            wallpaper.setImageBitmap(bmp);
            wallpaper.setVisibility(View.VISIBLE);
            aurora.setVisibility(View.GONE);
        } else {
            wallpaper.setVisibility(View.GONE);
            aurora.setVisibility(View.VISIBLE);
        }
    }

    /** The transparent depth PNG, if one was chosen (kept with its transparency). */
    private void loadFront() {
        front.setVisibility(View.GONE);
        if (!WallpaperStore.hasFront(ctx)) return;
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            try (InputStream in = ctx.getContentResolver().openInputStream(WallpaperStore.frontUri(ctx))) {
                BitmapFactory.decodeStream(in, null, o);
            }
            int sample = 1;
            while (Math.max(o.outWidth, o.outHeight) / (sample * 2) >= 1920) sample *= 2;
            o.inJustDecodeBounds = false;
            o.inSampleSize = sample;
            try (InputStream in = ctx.getContentResolver().openInputStream(WallpaperStore.frontUri(ctx))) {
                frontBitmap = BitmapFactory.decodeStream(in, null, o);
            }
            if (frontBitmap != null) {
                front.setImageBitmap(frontBitmap);
                front.setColorFilter(0x40000000, android.graphics.PorterDuff.Mode.SRC_ATOP);   // match the dimmed picture
                front.setVisibility(View.VISIBLE);
            }
        } catch (Exception | OutOfMemoryError ignored) {
        }
    }

    private Bitmap decodeWallpaper() throws java.io.IOException {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        try (InputStream in = ctx.getContentResolver().openInputStream(WallpaperStore.uri(ctx))) {
            BitmapFactory.decodeStream(in, null, o);
        }
        int sample = 1;
        while (Math.max(o.outWidth, o.outHeight) / (sample * 2) >= 1920) sample *= 2;
        o.inJustDecodeBounds = false;
        o.inSampleSize = sample;
        try (InputStream in = ctx.getContentResolver().openInputStream(WallpaperStore.uri(ctx))) {
            return BitmapFactory.decodeStream(in, null, o);
        }
    }

    private void updateInfo() {
        Calendar c = Calendar.getInstance();
        int hour = c.get(Calendar.HOUR_OF_DAY);
        int res;
        if (hour < 5) res = R.string.good_night;
        else if (hour < 12) res = R.string.good_morning;
        else if (hour < 17) res = R.string.good_afternoon;
        else if (hour < 22) res = R.string.good_evening;
        else res = R.string.good_night;
        greeting.setText(res);

        float frac = (hour * 60 + c.get(Calendar.MINUTE)) / 1440f;
        dayBar.set(frac, WidgetPanel.DARK_RED_FROM, 0xFFFFFFFF);
        dayLabel.setText(String.format(Locale.US, "%d%% of today", Math.round(frac * 100)));

        // A new line every ~24 seconds, faded in and out.
        if (++ticks % 6 == 0) {
            quoteIndex++;
            quote.animate().alpha(0f).setDuration(700).withEndAction(() -> {
                quote.setText(Quotes.forDay(quoteIndex));
                quote.animate().alpha(1f).setDuration(1200).start();
            }).start();
        }
        updateMusic();
    }

    private void updateMusic() {
        MediaMetadata meta = null;
        try {
            MediaSessionManager sm = (MediaSessionManager) ctx.getSystemService(Context.MEDIA_SESSION_SERVICE);
            List<MediaController> list = sm.getActiveSessions(new ComponentName(ctx, MediaListener.class));
            for (MediaController mc : list) {
                String pkg = mc.getPackageName();
                PlaybackState st = mc.getPlaybackState();
                if (pkg != null && pkg.startsWith("com.spotify") && st != null
                        && st.getState() == PlaybackState.STATE_PLAYING) {
                    meta = mc.getMetadata();
                    break;
                }
            }
        } catch (RuntimeException ignored) {
            // No notification access: the saver simply shows no song.
        }
        if (meta == null) {
            if (!songId.isEmpty()) {
                songId = "";
                aurora.setPalette(AuroraView.DEFAULT_PALETTE);
                nowRow.animate().alpha(0f).setDuration(400).withEndAction(() -> nowRow.setVisibility(View.GONE)).start();
            }
            return;
        }
        String id = SongColors.idOf(meta);
        if (id.equals(songId)) return;
        songId = id;
        aurora.setPalette(SongColors.palette(meta));
        String title = meta.getString(MediaMetadata.METADATA_KEY_TITLE);
        String artist = meta.getString(MediaMetadata.METADATA_KEY_ARTIST);
        nowTitle.setText(title == null ? "Spotify" : title);
        nowArtist.setText(artist == null ? "" : artist);
        Bitmap art = SongColors.artOf(meta);
        if (art != null && art.getWidth() > 0 && art.getHeight() > 0) {
            nowArt.setImageBitmap(Bitmap.createScaledBitmap(art, 96, 96, true));
        } else {
            nowArt.setImageDrawable(null);
        }
        nowRow.setVisibility(View.VISIBLE);
        nowRow.setAlpha(0f);
        nowRow.animate().alpha(1f).setDuration(600).start();
    }
}
