package com.gathin.tvhome;

import android.animation.ValueAnimator;
import android.content.ComponentName;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Outline;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.view.animation.DecelerateInterpolator;
import android.widget.ImageView;
import android.widget.TextView;

import java.io.InputStream;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;

/**
 * Drives the screensaver layout (dream_clock.xml) for both the real screensaver and the preview:
 * the user's wallpaper as background (the aurora when none is set), the clock fixed in the top-left
 * corner (behind the depth PNG when one is set), a greeting, and a Spotify scene while a song plays.
 */
final class SaverController {

    private static final long INFO_MS = 4_000L;

    private final Context ctx;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final AuroraView aurora;
    private final ImageView wallpaper;
    private Bitmap wallpaperBitmap;
    private final View content;
    private final TextView greeting, nowTitle, nowArtist, nowTime;
    private final ColorBar nowBar;
    private final EqView nowEq;
    private final View nowRow, musicGlow;
    private final ImageView nowArt;
    private final GradientDrawable glowDrawable = new GradientDrawable();
    private ValueAnimator glowPulse;
    private MediaController current;
    private int accent = 0xFFB3121B;
    private final ImageView front;
    private final View scrimView;
    private Bitmap frontBitmap;
    private String songId = "";
    private boolean running;

    private final Runnable progress = new Runnable() {
        @Override
        public void run() {
            updateProgress();
            if (running && current != null) handler.postDelayed(this, 1000);
        }
    };

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
        nowRow = root.findViewById(R.id.saverNowRow);
        nowArt = root.findViewById(R.id.saverNowArt);
        nowTitle = root.findViewById(R.id.saverNowTitle);
        nowArtist = root.findViewById(R.id.saverNowArtist);
        nowBar = root.findViewById(R.id.saverNowBar);
        nowTime = root.findViewById(R.id.saverNowTime);
        nowEq = root.findViewById(R.id.saverNowEq);
        musicGlow = root.findViewById(R.id.saverMusicGlow);
        front = root.findViewById(R.id.saverFront);
        scrimView = root.findViewById(R.id.saverScrim);
        ClockStyles.apply(ctx, ClockStyles.get(ctx), (android.widget.TextView) root.findViewById(R.id.saverHours),
                (android.widget.TextView) root.findViewById(R.id.saverMinutes), (android.widget.TextView) root.findViewById(R.id.saverAmPm),
                (android.widget.TextView) root.findViewById(R.id.saverDate), (PixelClockView) root.findViewById(R.id.saverPixel), true);
        final float d = ctx.getResources().getDisplayMetrics().density;
        nowArt.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View v, Outline o) {
                o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), 14 * d);
            }
        });
        nowArt.setClipToOutline(true);
        nowArt.setElevation(10 * d);
        // Soft coloured light behind the album art (the colour is set per song).
        glowDrawable.setGradientType(GradientDrawable.RADIAL_GRADIENT);
        glowDrawable.setGradientRadius(330 * d);
        glowDrawable.setGradientCenter(0.5f, 0.5f);
        musicGlow.setBackground(glowDrawable);
    }

    void start() {
        running = true;
        loadBackground();
        loadFront();
        // Slow cinematic entrance: the picture fades in while easing out of a slight zoom, then the clock
        // rises into place.
        final float d = ctx.getResources().getDisplayMetrics().density;
        for (View v : new View[]{aurora, wallpaper, front, scrimView}) {
            if (v == null || v.getVisibility() != View.VISIBLE) continue;
            v.animate().cancel();
            v.setAlpha(0f);
            v.setScaleX(1.12f);
            v.setScaleY(1.12f);
            v.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(2600)
                    .setInterpolator(new DecelerateInterpolator(1.4f)).start();
        }
        content.animate().cancel();
        content.setAlpha(0f);
        content.setTranslationY(26 * d);
        content.animate().alpha(1f).translationY(0f).setStartDelay(700).setDuration(1400)
                .setInterpolator(new DecelerateInterpolator(1.8f)).start();
        updateInfo();
        handler.postDelayed(info, INFO_MS);
    }

    void stop() {
        running = false;
        handler.removeCallbacksAndMessages(null);
        content.animate().cancel();
        stopGlowPulse();
        current = null;
        songId = "";
        for (View v : new View[]{aurora, wallpaper, front, scrimView}) {
            if (v == null) continue;
            v.animate().cancel();
            v.setAlpha(1f);
            v.setScaleX(1f);
            v.setScaleY(1f);
        }
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

    /** The transparent depth PNG, if one was chosen (kept with its transparency). It sits over the clock. */
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
        int hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        int res;
        if (hour < 5) res = R.string.good_night;
        else if (hour < 12) res = R.string.good_morning;
        else if (hour < 17) res = R.string.good_afternoon;
        else if (hour < 22) res = R.string.good_evening;
        else res = R.string.good_night;
        greeting.setText(res);
        updateMusic();
    }

    private void updateMusic() {
        MediaMetadata meta = null;
        MediaController playing = null;
        try {
            MediaSessionManager sm = (MediaSessionManager) ctx.getSystemService(Context.MEDIA_SESSION_SERVICE);
            List<MediaController> list = sm.getActiveSessions(new ComponentName(ctx, MediaListener.class));
            for (MediaController mc : list) {
                String pkg = mc.getPackageName();
                PlaybackState st = mc.getPlaybackState();
                if (pkg != null && pkg.startsWith("com.spotify") && st != null
                        && st.getState() == PlaybackState.STATE_PLAYING) {
                    meta = mc.getMetadata();
                    playing = mc;
                    break;
                }
            }
        } catch (RuntimeException ignored) {
            // No notification access: the saver simply shows no song.
        }
        current = playing;
        if (meta == null) {
            if (!songId.isEmpty()) {
                songId = "";
                aurora.setPalette(AuroraView.DEFAULT_PALETTE);
                stopGlowPulse();
                nowEq.setActive(false);
                musicGlow.animate().alpha(0f).setDuration(800).start();
                nowRow.animate().alpha(0f).setDuration(500).withEndAction(() -> nowRow.setVisibility(View.GONE)).start();
            }
            return;
        }
        // Keep the progress bar moving once a second while the song plays.
        handler.removeCallbacks(progress);
        handler.post(progress);
        String id = SongColors.idOf(meta);
        if (id.equals(songId)) return;
        songId = id;
        int[] palette = SongColors.palette(meta);
        accent = 0xFFFFFFFF;              // cover art and playback only: the background keeps its own look
        String title = meta.getString(MediaMetadata.METADATA_KEY_TITLE);
        String artist = meta.getString(MediaMetadata.METADATA_KEY_ARTIST);
        nowTitle.setText(title == null ? "Spotify" : title);
        nowArtist.setText(artist == null ? "" : artist);
        Bitmap art = SongColors.artOf(meta);
        if (art != null && art.getWidth() > 0 && art.getHeight() > 0) {
            nowArt.setImageBitmap(Bitmap.createScaledBitmap(art, 224, 224, true));
        } else {
            nowArt.setImageDrawable(new android.graphics.drawable.ColorDrawable(accent));
        }
        nowEq.setColor(accent);
        nowEq.setActive(true);
        nowRow.setVisibility(View.VISIBLE);
        nowRow.setAlpha(0f);
        nowRow.animate().alpha(1f).setDuration(900).start();
        updateProgress();
    }

    /** Song position: the thin bar and "1:23 / 3:45". */
    private void updateProgress() {
        MediaController c = current;
        if (c == null) return;
        PlaybackState st = c.getPlaybackState();
        MediaMetadata m = c.getMetadata();
        if (st == null || m == null) return;
        long dur = m.getLong(MediaMetadata.METADATA_KEY_DURATION);
        long pos = st.getPosition();
        if (st.getState() == PlaybackState.STATE_PLAYING) {
            pos += (long) ((SystemClock.elapsedRealtime() - st.getLastPositionUpdateTime()) * st.getPlaybackSpeed());
        }
        if (dur <= 0) {
            nowBar.set(0f, accent, accent);
            nowTime.setText("");
            return;
        }
        pos = Math.max(0, Math.min(pos, dur));
        nowBar.set(pos / (float) dur, accent, 0xFFFFFFFF);
        nowTime.setText(clock(pos) + " / " + clock(dur));
    }

    private static String clock(long ms) {
        long sec = Math.max(0, ms / 1000);
        return String.format(Locale.US, "%d:%02d", sec / 60, sec % 60);
    }

    /** The light behind the album art slowly breathes while the song plays. */
    private void startGlowPulse() {
        if (glowPulse != null) return;
        glowPulse = ValueAnimator.ofFloat(0.72f, 1f);
        glowPulse.setDuration(4200);
        glowPulse.setRepeatCount(ValueAnimator.INFINITE);
        glowPulse.setRepeatMode(ValueAnimator.REVERSE);
        glowPulse.setInterpolator(new AccelerateDecelerateInterpolator());
        glowPulse.addUpdateListener(a -> {
            float v = (Float) a.getAnimatedValue();
            musicGlow.setScaleX(0.9f + 0.18f * v);
            musicGlow.setScaleY(0.9f + 0.18f * v);
        });
        glowPulse.start();
    }

    private void stopGlowPulse() {
        if (glowPulse != null) {
            glowPulse.cancel();
            glowPulse = null;
        }
    }
}
