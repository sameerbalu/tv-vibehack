package com.gathin.tvhome;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.animation.OvershootInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The "Continue watching" section at the end of the All apps grid: a heading followed by poster tiles
 * (same size and focus look as the app tiles) with a progress bar for how much was watched.
 * Items come from NuvioHistory (filled by MediaListener).
 */
final class ContinueAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    interface Host {
        void onPosterFocused(int index, View card, int color);

        boolean onPosterKey(int index, int keyCode);
    }

    private static final int TYPE_HEADER = 1, TYPE_POSTER = 2;
    private static final int CARD_W_DP = 280, CARD_H_DP = 158;

    private final Context ctx;
    private final Host host;
    private final float d;
    private final List<NuvioHistory.Entry> entries = new ArrayList<>();
    private final android.util.LruCache<String, Bitmap> posters = new android.util.LruCache<>(24);
    private final android.util.SparseIntArray colors = new android.util.SparseIntArray();
    private String signature = "";
    private String nuvioPkg;

    ContinueAdapter(Context ctx, Host host) {
        this.ctx = ctx;
        this.host = host;
        this.d = ctx.getResources().getDisplayMetrics().density;
        setHasStableIds(true);
    }

    /** Reloads the list from disk. Only rebinds when something changed. */
    void refresh() {
        List<NuvioHistory.Entry> fresh = NuvioHistory.load(ctx);
        StringBuilder sig = new StringBuilder();
        for (NuvioHistory.Entry e : fresh) {
            sig.append(e.key).append('|').append(e.poster).append('|').append(e.position / 15000L)
                    .append('|').append(e.duration).append(';');
        }
        nuvioPkg = findNuvio();
        if (sig.toString().equals(signature)) return;
        signature = sig.toString();
        entries.clear();
        entries.addAll(fresh);
        colors.clear();
        notifyDataSetChanged();
    }

    int posterCount() {
        return entries.size();
    }

    @Override
    public int getItemCount() {
        return entries.isEmpty() ? 0 : entries.size() + 1;
    }

    @Override
    public int getItemViewType(int position) {
        return position == 0 ? TYPE_HEADER : TYPE_POSTER;
    }

    @Override
    public long getItemId(int position) {
        return position == 0 ? -7L : entries.get(position - 1).key.hashCode();
    }

    /** True for the heading row (it takes the full width of the grid). */
    boolean isHeader(int position) {
        return position == 0 && !entries.isEmpty();
    }

    // ---- views ---------------------------------------------------------------------------------------

    private static final class HeaderHolder extends RecyclerView.ViewHolder {
        HeaderHolder(View v) {
            super(v);
        }
    }

    private static final class PosterHolder extends RecyclerView.ViewHolder {
        final View card, scrim;
        final ImageView poster;
        final TextView name;
        final ColorBar progress;

        PosterHolder(View v) {
            super(v);
            card = v.findViewById(R.id.card);
            scrim = v.findViewById(R.id.scrim);
            poster = v.findViewById(R.id.poster);
            name = v.findViewById(R.id.name);
            progress = v.findViewById(R.id.progress);
        }
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        if (viewType == TYPE_HEADER) {
            View t = new View(ctx);      // just a gap that starts the posters on a fresh row
            t.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (int) (26 * d)));
            return new HeaderHolder(t);
        }
        View v = LayoutInflater.from(ctx).inflate(R.layout.item_continue, parent, false);
        final PosterHolder h = new PosterHolder(v);
        final float radius = 16f * d;
        h.card.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), radius);
            }
        });
        h.card.setClipToOutline(true);
        h.scrim.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0x00000000, 0xCC000000}));
        h.card.setOnKeyListener((view, keyCode, event) -> {
            if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
            int p = h.getBindingAdapterPosition();
            if (p == RecyclerView.NO_POSITION) return false;
            if (keyCode == KeyEvent.KEYCODE_DPAD_UP || keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                return host.onPosterKey(p - 1, keyCode);
            }
            return false;
        });
        h.card.setOnFocusChangeListener((view, focused) -> {
            float s = focused ? AppAdapter.FOCUS_SCALE : 1f;
            view.animate().scaleX(s).scaleY(s)
                    .setDuration(focused ? 260 : 180)
                    .setInterpolator(new OvershootInterpolator(focused ? 1.6f : 0f))
                    .start();
            h.name.animate().alpha(focused ? 1f : 0f)
                    .translationY(focused ? d * 4f : -d * 6f)
                    .setDuration(220).start();
            int p = h.getBindingAdapterPosition();
            if (focused && p != RecyclerView.NO_POSITION) {
                host.onPosterFocused(p - 1, view, colors.get(p, 0xFF666666));
            }
        });
        h.card.setOnClickListener(view -> {
            int p = h.getBindingAdapterPosition();
            if (p != RecyclerView.NO_POSITION && p - 1 < entries.size()) resume(entries.get(p - 1));
        });
        return h;
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        if (!(holder instanceof PosterHolder)) return;
        PosterHolder h = (PosterHolder) holder;
        NuvioHistory.Entry e = entries.get(position - 1);

        Bitmap bmp = null;
        if (!e.poster.isEmpty()) {
            bmp = posters.get(e.poster);
            if (bmp == null) {
                bmp = decode(e);
                if (bmp != null) posters.put(e.poster, bmp);
            }
        }
        int color;
        if (bmp != null) {
            h.poster.setImageBitmap(bmp);
            color = AppAdapter.vividColor(Bitmap.createScaledBitmap(bmp, 12, 12, true));
        } else {
            float hue = Math.abs(e.title.hashCode()) % 360;
            int c1 = Color.HSVToColor(new float[]{hue, 0.55f, 0.55f});
            int c2 = Color.HSVToColor(new float[]{(hue + 40f) % 360f, 0.7f, 0.22f});
            h.poster.setImageDrawable(new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{c1, c2}));
            color = c1;
        }
        colors.put(position, color);

        h.name.setText(e.title);
        h.name.setTextColor(Color.WHITE);
        if (e.duration > 0) {
            h.progress.setVisibility(View.VISIBLE);
            h.progress.set(e.position / (float) e.duration, 0xFFE50914, 0xFFFFFFFF);
            h.scrim.setVisibility(View.VISIBLE);
        } else {
            h.progress.setVisibility(View.GONE);
            h.scrim.setVisibility(View.GONE);
        }
    }

    private Bitmap decode(NuvioHistory.Entry e) {
        try {
            File f = new File(new File(ctx.getFilesDir(), "nuvio_posters"), e.poster);
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(f.getPath(), o);
            int target = (int) (CARD_W_DP * d * 1.5f);
            int sample = 1;
            while (o.outWidth / (sample * 2) >= target) sample *= 2;
            o = new BitmapFactory.Options();
            o.inSampleSize = sample;
            return BitmapFactory.decodeFile(f.getPath(), o);
        } catch (Exception | OutOfMemoryError ex) {
            return null;
        }
    }

    // ---- opening ---------------------------------------------------------------------------------------

    private String findNuvio() {
        PackageManager pm = ctx.getPackageManager();
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
        PackageManager pm = ctx.getPackageManager();
        Intent i = pm.getLeanbackLaunchIntentForPackage(pkg);
        if (i == null) i = pm.getLaunchIntentForPackage(pkg);
        if (i == null) {
            Toast.makeText(ctx, R.string.library_no_nuvio, Toast.LENGTH_SHORT).show();
            return;
        }
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            ctx.startActivity(i);
        } catch (Exception ex) {
            Toast.makeText(ctx, R.string.library_no_nuvio, Toast.LENGTH_SHORT).show();
        }
    }

    /** If Nuvio still holds this title (paused) tell it to play, then bring Nuvio to the front. */
    private void resume(NuvioHistory.Entry e) {
        PackageManager pm = ctx.getPackageManager();
        String pkg = (e.pkg == null || e.pkg.isEmpty() || pm.getLaunchIntentForPackage(e.pkg) == null
                && pm.getLeanbackLaunchIntentForPackage(e.pkg) == null) ? nuvioPkg : e.pkg;
        if (pkg == null) {
            Toast.makeText(ctx, R.string.library_no_nuvio, Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            MediaSessionManager sm = (MediaSessionManager) ctx.getSystemService(Context.MEDIA_SESSION_SERVICE);
            for (MediaController c : sm.getActiveSessions(new ComponentName(ctx, MediaListener.class))) {
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
}
