package com.gathin.tvhome;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.os.Bundle;
import android.provider.Settings;
import android.util.LruCache;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The page after the last row of All apps: Nuvio "Continue watching" with rounded posters.
 * What is listed comes from the playback Nuvio reports (see MediaListener / NuvioHistory).
 */
public class LibraryActivity extends Activity {

    private static final int CARD_H_DP = 210;
    private static final int RADIUS_DP = 22;

    private float d;
    private Typeface font;
    private RecyclerView row;
    private TextView note;
    private Bitmap background;
    private String nuvioPkg;
    private final List<NuvioHistory.Entry> entries = new ArrayList<>();
    private CardAdapter adapter;
    private final LruCache<String, Bitmap> posters = new LruCache<String, Bitmap>(12 * 1024 * 1024) {
        @Override
        protected int sizeOf(String key, Bitmap value) {
            return value.getByteCount();
        }
    };

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        d = getResources().getDisplayMetrics().density;
        try {
            font = getResources().getFont(R.font.outfit);
        } catch (Exception e) {
            font = Typeface.DEFAULT;
        }

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        ImageView bg = new ImageView(this);
        bg.setScaleType(ImageView.ScaleType.CENTER_CROP);
        loadBackground(bg);
        root.addView(bg, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        View dim = new View(this);
        dim.setBackgroundColor(0xCC000000);
        root.addView(dim, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setClipChildren(false);
        col.setClipToPadding(false);
        col.setPadding((int) (56 * d), (int) (48 * d), 0, (int) (32 * d));

        TextView label = text(12, 0xFFB3121B);
        label.setText("NUVIO");
        label.setLetterSpacing(0.24f);
        col.addView(label);

        TextView title = text(36, Color.WHITE);
        title.setText(R.string.library_title);
        title.setFontVariationSettings("'wght' 520");
        col.addView(title);

        TextView sub = text(15, 0x99FFFFFF);
        sub.setText(R.string.library_sub);
        col.addView(sub);

        row = new RecyclerView(this);
        row.setLayoutManager(new LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false));
        row.setClipChildren(false);
        row.setClipToPadding(false);
        row.setPadding(0, (int) (30 * d), (int) (56 * d), (int) (30 * d));
        row.setItemAnimator(null);
        row.setOverScrollMode(View.OVER_SCROLL_NEVER);
        adapter = new CardAdapter();
        row.setAdapter(adapter);
        LinearLayout.LayoutParams rl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        rl.topMargin = (int) (18 * d);
        col.addView(row, rl);

        note = text(15, 0xB3FFFFFF);
        note.setMaxWidth((int) (760 * d));
        LinearLayout.LayoutParams nl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        nl.topMargin = (int) (6 * d);
        col.addView(note, nl);

        root.addView(col, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(root);
    }

    @Override
    protected void onResume() {
        super.onResume();
        posters.evictAll();
        nuvioPkg = findNuvio();
        entries.clear();
        entries.addAll(NuvioHistory.load(this));
        adapter.notifyDataSetChanged();
        updateNote();
        row.post(() -> {
            View first = row.getChildAt(0);
            if (first != null) first.requestFocus();
        });
    }

    @Override
    protected void onDestroy() {
        posters.evictAll();
        if (background != null) {
            background.recycle();
            background = null;
        }
        super.onDestroy();
    }

    @Override
    public void finish() {
        super.finish();
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
    }

    // ---- helpers -------------------------------------------------------------------------------------

    private TextView text(float sp, int color) {
        TextView t = new TextView(this);
        t.setTypeface(font);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setIncludeFontPadding(false);
        return t;
    }

    private void loadBackground(ImageView bg) {
        try {
            if (!WallpaperStore.exists(this)) return;
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            try (InputStream in = getContentResolver().openInputStream(WallpaperStore.uri(this))) {
                BitmapFactory.decodeStream(in, null, o);
            }
            int sample = 1;
            while (Math.max(o.outWidth, o.outHeight) / (sample * 2) >= 1280) sample *= 2;
            o.inJustDecodeBounds = false;
            o.inSampleSize = sample;
            o.inPreferredConfig = Bitmap.Config.RGB_565;
            try (InputStream in = getContentResolver().openInputStream(WallpaperStore.uri(this))) {
                background = BitmapFactory.decodeStream(in, null, o);
            }
            if (background != null) bg.setImageBitmap(background);
        } catch (Exception | OutOfMemoryError ignored) {
        }
    }

    private boolean hasListenerAccess() {
        String enabled = Settings.Secure.getString(getContentResolver(), "enabled_notification_listeners");
        return enabled != null && enabled.contains(getPackageName());
    }

    private void updateNote() {
        if (nuvioPkg == null) {
            note.setText(R.string.library_no_nuvio);
        } else if (entries.isEmpty()) {
            String text = getString(R.string.library_empty);
            if (!hasListenerAccess()) {
                text += "\n" + getString(R.string.library_setup, getString(R.string.music_access));
            }
            note.setText(text);
        } else {
            note.setText("");
        }
        note.setVisibility(note.getText().length() == 0 ? View.GONE : View.VISIBLE);
    }

    /** Finds the installed Nuvio app (by package name or label). */
    private String findNuvio() {
        PackageManager pm = getPackageManager();
        String[] categories = {Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER};
        for (String category : categories) {
            Intent q = new Intent(Intent.ACTION_MAIN).addCategory(category);
            for (ResolveInfo ri : pm.queryIntentActivities(q, 0)) {
                String pkg = ri.activityInfo.packageName;
                String label = String.valueOf(ri.loadLabel(pm)).toLowerCase(Locale.ROOT);
                if (MediaListener.isNuvio(pkg) || label.contains("nuvio")) return pkg;
            }
        }
        return null;
    }

    private void launch(String pkg) {
        PackageManager pm = getPackageManager();
        Intent i = pm.getLeanbackLaunchIntentForPackage(pkg);
        if (i == null) i = pm.getLaunchIntentForPackage(pkg);
        if (i == null) {
            Toast.makeText(this, R.string.library_no_nuvio, Toast.LENGTH_SHORT).show();
            return;
        }
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(i);
            finish();
        } catch (Exception e) {
            Toast.makeText(this, R.string.library_no_nuvio, Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * If Nuvio is still holding this title (paused), tell it to play; then bring Nuvio to the front.
     * Otherwise Nuvio just opens, where its own Continue Watching is the first thing on its home page.
     */
    private void resume(NuvioHistory.Entry e) {
        String pkg = (e.pkg == null || e.pkg.isEmpty() || getPackageManager().getLaunchIntentForPackage(e.pkg) == null
                && getPackageManager().getLeanbackLaunchIntentForPackage(e.pkg) == null) ? nuvioPkg : e.pkg;
        if (pkg == null) {
            Toast.makeText(this, R.string.library_no_nuvio, Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            MediaSessionManager sm = (MediaSessionManager) getSystemService(Context.MEDIA_SESSION_SERVICE);
            for (MediaController c : sm.getActiveSessions(new ComponentName(this, MediaListener.class))) {
                if (!pkg.equals(c.getPackageName())) continue;
                MediaMetadata m = c.getMetadata();
                String t = m == null ? null : m.getString(MediaMetadata.METADATA_KEY_TITLE);
                if (t != null && t.trim().equals(e.title)) {
                    c.getTransportControls().play();
                    break;
                }
            }
        } catch (RuntimeException ignored) {
        }
        launch(pkg);
    }

    private static String ago(long time) {
        long mins = Math.max(0, (System.currentTimeMillis() - time) / 60000L);
        if (mins < 1) return "just now";
        if (mins < 60) return mins + " min ago";
        long hours = mins / 60;
        if (hours < 24) return hours + " h ago";
        return (hours / 24) + " d ago";
    }

    private static String left(long ms) {
        long mins = Math.max(1, ms / 60000L);
        if (mins >= 60) return (mins / 60) + "h " + (mins % 60) + "m left";
        return mins + " min left";
    }

    // ---- cards ---------------------------------------------------------------------------------------

    private final class CardHolder extends RecyclerView.ViewHolder {
        final LinearLayout item;
        final FrameLayout frame;
        final ImageView image;
        final TextView badge, title, sub;
        final ColorBar bar;
        final View scrim;

        CardHolder(LinearLayout item, FrameLayout frame, ImageView image, View scrim, TextView badge,
                   ColorBar bar, TextView title, TextView sub) {
            super(item);
            this.item = item;
            this.frame = frame;
            this.image = image;
            this.scrim = scrim;
            this.badge = badge;
            this.bar = bar;
            this.title = title;
            this.sub = sub;
        }
    }

    private final class CardAdapter extends RecyclerView.Adapter<CardHolder> {

        @Override
        public int getItemCount() {
            return entries.size() + (nuvioPkg != null ? 1 : 0);
        }

        @NonNull
        @Override
        public CardHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            LinearLayout item = new LinearLayout(LibraryActivity.this);
            item.setOrientation(LinearLayout.VERTICAL);
            item.setFocusable(true);
            item.setClickable(true);
            item.setClipChildren(false);
            LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            RecyclerView.LayoutParams rp = new RecyclerView.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            rp.setMarginEnd((int) (22 * d));
            item.setLayoutParams(rp);

            final FrameLayout frame = new FrameLayout(LibraryActivity.this);
            frame.setOutlineProvider(new ViewOutlineProvider() {
                @Override
                public void getOutline(View v, Outline o) {
                    o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), RADIUS_DP * d);   // rounded poster corners
                }
            });
            frame.setClipToOutline(true);

            ImageView image = new ImageView(LibraryActivity.this);
            image.setScaleType(ImageView.ScaleType.CENTER_CROP);
            frame.addView(image, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT));

            View scrim = new View(LibraryActivity.this);
            GradientDrawable fade = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                    new int[]{0x00000000, 0xDD000000});
            scrim.setBackground(fade);
            frame.addView(scrim, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    (int) (96 * d), Gravity.BOTTOM));

            TextView badge = text(11, Color.WHITE);
            badge.setTypeface(font, Typeface.BOLD);
            badge.setPadding((int) (10 * d), (int) (5 * d), (int) (10 * d), (int) (5 * d));
            GradientDrawable pill = new GradientDrawable();
            pill.setColor(0x99000000);
            pill.setCornerRadius(12 * d);
            badge.setBackground(pill);
            FrameLayout.LayoutParams bp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.START);
            bp.setMargins((int) (12 * d), (int) (12 * d), 0, 0);
            frame.addView(badge, bp);

            ColorBar bar = new ColorBar(LibraryActivity.this);
            FrameLayout.LayoutParams barLp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    (int) (4 * d), Gravity.BOTTOM);
            barLp.setMargins((int) (14 * d), 0, (int) (14 * d), (int) (14 * d));
            frame.addView(bar, barLp);

            item.addView(frame, new LinearLayout.LayoutParams((int) (300 * d), (int) (CARD_H_DP * d)));

            TextView title = text(15, Color.WHITE);
            title.setTypeface(font, Typeface.BOLD);
            title.setMaxLines(2);
            title.setEllipsize(android.text.TextUtils.TruncateAt.END);
            LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams((int) (300 * d),
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            tl.topMargin = (int) (12 * d);
            item.addView(title, tl);

            TextView sub = text(12, 0x99FFFFFF);
            sub.setSingleLine(true);
            sub.setEllipsize(android.text.TextUtils.TruncateAt.END);
            LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams((int) (300 * d),
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            sl.topMargin = (int) (3 * d);
            item.addView(sub, sl);

            final GradientDrawable ring = new GradientDrawable();
            ring.setColor(Color.TRANSPARENT);
            ring.setStroke((int) (5 * d), Color.WHITE);        // half of it is clipped: ~2.5dp shows
            ring.setCornerRadius(RADIUS_DP * d);
            item.setOnFocusChangeListener((v, focused) -> {
                v.animate().scaleX(focused ? 1.07f : 1f).scaleY(focused ? 1.07f : 1f).setDuration(180).start();
                v.setTranslationZ(focused ? 12 * d : 0f);
                frame.setForeground(focused ? ring : null);
            });
            return new CardHolder(item, frame, image, scrim, badge, bar, title, sub);
        }

        @Override
        public void onBindViewHolder(@NonNull CardHolder h, int position) {
            if (position < entries.size()) bindEntry(h, entries.get(position));
            else bindOpen(h);
        }

        private void size(CardHolder h, int widthPx) {
            h.frame.setLayoutParams(new LinearLayout.LayoutParams(widthPx, (int) (CARD_H_DP * d)));
            LinearLayout.LayoutParams tl = (LinearLayout.LayoutParams) h.title.getLayoutParams();
            tl.width = widthPx;
            h.title.setLayoutParams(tl);
            LinearLayout.LayoutParams sl = (LinearLayout.LayoutParams) h.sub.getLayoutParams();
            sl.width = widthPx;
            h.sub.setLayoutParams(sl);
        }

        private void bindEntry(final CardHolder h, final NuvioHistory.Entry e) {
            Bitmap bmp = null;
            if (!e.poster.isEmpty()) {
                bmp = posters.get(e.poster);
                if (bmp == null) {
                    bmp = NuvioHistory.loadPoster(LibraryActivity.this, e);
                    if (bmp != null) posters.put(e.poster, bmp);
                }
            }
            h.image.setPadding(0, 0, 0, 0);          // an Open card may have used this holder
            h.frame.setBackground(null);
            int w = (int) (300 * d);
            if (bmp != null && bmp.getHeight() > 0) {
                float aspect = bmp.getWidth() / (float) bmp.getHeight();
                w = Math.max((int) (150 * d), Math.min((int) (360 * d), Math.round(CARD_H_DP * d * aspect)));
                h.image.setScaleType(ImageView.ScaleType.CENTER_CROP);
                h.image.setImageBitmap(bmp);
            } else {
                // No artwork was reported: a soft gradient from the title keeps the card looking designed.
                float hue = Math.abs(e.title.hashCode()) % 360;
                int c1 = Color.HSVToColor(new float[]{hue, 0.55f, 0.55f});
                int c2 = Color.HSVToColor(new float[]{(hue + 40f) % 360f, 0.7f, 0.22f});
                h.image.setImageDrawable(new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{c1, c2}));
            }
            size(h, w);
            h.scrim.setVisibility(View.VISIBLE);
            h.title.setText(e.title);
            h.sub.setText((e.subtitle.isEmpty() ? "" : e.subtitle + "  \u00B7  ") + ago(e.time));
            if (e.duration > 0) {
                h.badge.setVisibility(View.VISIBLE);
                h.badge.setText(left(e.duration - e.position));
                h.bar.setVisibility(View.VISIBLE);
                h.bar.set(e.position / (float) e.duration, 0xFFB3121B, 0xFFFFFFFF);
            } else {
                h.badge.setVisibility(View.GONE);
                h.bar.setVisibility(View.GONE);
            }
            h.item.setOnClickListener(v -> resume(e));
        }

        private void bindOpen(CardHolder h) {
            size(h, (int) (200 * d));
            h.image.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
            Drawable icon = null;
            try {
                icon = getPackageManager().getApplicationIcon(nuvioPkg);
            } catch (Exception ignored) {
            }
            h.image.setPadding((int) (44 * d), (int) (44 * d), (int) (44 * d), (int) (44 * d));
            GradientDrawable back = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                    new int[]{0xFF1B1B20, 0xFF0B0B0D});
            h.frame.setBackground(back);
            h.image.setImageDrawable(icon);
            h.scrim.setVisibility(View.GONE);
            h.badge.setVisibility(View.GONE);
            h.bar.setVisibility(View.GONE);
            h.title.setText(R.string.library_open);
            h.sub.setText(R.string.library_open_sub);
            h.item.setOnClickListener(v -> launch(nuvioPkg));
        }

        @Override
        public void onViewRecycled(@NonNull CardHolder h) {
            h.image.setPadding(0, 0, 0, 0);          // the Open card pads its icon; the others must not
            h.frame.setBackground(null);
            super.onViewRecycled(h);
        }
    }
}
