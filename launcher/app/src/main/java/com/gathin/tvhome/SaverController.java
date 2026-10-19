package com.gathin.tvhome;

import android.content.ComponentName;
import android.content.Context;
import android.graphics.Bitmap;
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

import java.util.Calendar;
import java.util.List;
import java.util.Locale;
import java.util.Random;

/**
 * Drives the screensaver layout (dream_clock.xml) for both the real screensaver and the preview:
 * the aurora background, the clock block that drifts to a new spot every minute (no burn-in),
 * a greeting, how much of the day has passed, and the Spotify song that is playing (its colours
 * then flow through the aurora).
 */
final class SaverController {

    private static final long DRIFT_MS = 60_000L;
    private static final long INFO_MS = 4_000L;

    private final Context ctx;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Random random = new Random();
    private final AuroraView aurora;
    private final View content;
    private final TextView greeting, dayLabel, nowTitle, nowArtist;
    private final ColorBar dayBar;
    private final View nowRow;
    private final ImageView nowArt;
    private String songId = "";
    private boolean running;

    private final Runnable drift = new Runnable() {
        @Override
        public void run() {
            content.animate().alpha(0f).setDuration(900).withEndAction(() -> {
                place();
                content.animate().alpha(1f).setDuration(1400).start();
            }).start();
            handler.postDelayed(this, DRIFT_MS);
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
        content = root.findViewById(R.id.saverContent);
        greeting = root.findViewById(R.id.saverGreeting);
        dayLabel = root.findViewById(R.id.saverDayLabel);
        dayBar = root.findViewById(R.id.saverDayBar);
        nowRow = root.findViewById(R.id.saverNowRow);
        nowArt = root.findViewById(R.id.saverNowArt);
        nowTitle = root.findViewById(R.id.saverNowTitle);
        nowArtist = root.findViewById(R.id.saverNowArtist);
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
        content.setAlpha(0f);
        content.post(() -> {
            if (!running) return;
            place();
            content.animate().alpha(1f).setDuration(1500).start();
        });
        updateInfo();
        handler.postDelayed(drift, DRIFT_MS);
        handler.postDelayed(info, INFO_MS);
    }

    void stop() {
        running = false;
        handler.removeCallbacksAndMessages(null);
        content.animate().cancel();
    }

    /** A random spot that keeps the whole block on screen. */
    private void place() {
        View parent = (View) content.getParent();
        int maxX = Math.max(0, parent.getWidth() - content.getWidth());
        int maxY = Math.max(0, parent.getHeight() - content.getHeight());
        content.setX(maxX * (0.08f + 0.84f * random.nextFloat()));
        content.setY(maxY * (0.08f + 0.84f * random.nextFloat()));
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
        dayBar.set(frac, 0xFF38BDF8, 0xFFF472B6);
        dayLabel.setText(String.format(Locale.US, "%d%% of today", Math.round(frac * 100)));

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
