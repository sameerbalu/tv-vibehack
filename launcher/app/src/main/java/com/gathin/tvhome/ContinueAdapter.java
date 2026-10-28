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
import android.graphics.drawable.Drawable;
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
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The "Continue watching" row at the top of All apps, laid out like a "For You" shelf: the current title is a
 * large 16:9 card with its name and a one-line description under it, the next titles are small cards beside it.
 * Moving along the row grows the new title, shrinks the old one and glides the row so the large card always
 * sits at the left. A progress bar on each card shows how much was watched.
 * Items come from NuvioHistory (filled by MediaListener).
 */
final class ContinueAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    interface Host {
        void onPosterFocused(int index, View card, int color);

        boolean onPosterKey(int index, int keyCode);

        /** OK held on a poster. */
        void onPosterLongPressed(int index, View card);
    }

    static final float RADIUS_DP = 18f;

    private final Context ctx;
    private final Host host;
    private final float d;
    private final List<NuvioHistory.Entry> entries = new ArrayList<>();
    private final android.util.LruCache<String, Bitmap> posters = new android.util.LruCache<>(32);
    private final android.util.LruCache<String, Bitmap> blurs = new android.util.LruCache<>(32);
    static final float POSTER_FOCUS_SCALE = 1.07f;
    private String signature = "";
    /** The wallpaper, used as the thumbnail when an app gives no real picture (VLC only offers its own icon). */
    private Bitmap wallpaperBmp;
    private long wallpaperStamp;
    private String nuvioPkg;
    private AppAdapter apps;

    /** Where the apps' tile art comes from (used when a title has no thumbnail of its own). */
    void setAppSource(AppAdapter apps) {
        this.apps = apps;
    }
    private int largeW, largeH, smallW, smallH, gapPx;
    /** The title shown large. It stays large while the focus is somewhere else, like a shelf's current pick. */
    private int current;
    /** The title that was large and is shrinking (-1 when no change is running). */
    private int prev = -1;
    private float prevStart = 1f;      // how large the shrinking title was when its change began (0..1)
    private float heroT = 1f;          // progress of the running change, 1 = settled
    private android.animation.ValueAnimator heroAnim;
    private RecyclerView rv;

    ContinueAdapter(Context ctx, Host host) {
        this.ctx = ctx;
        this.host = host;
        this.d = ctx.getResources().getDisplayMetrics().density;
        setHasStableIds(true);
    }

    /** Card sizes in px: the large (16:9) one, the small (portrait) ones, and the space between cards. */
    void setSizes(int lw, int lh, int sw, int sh, int gap) {
        if (lw == largeW && lh == largeH && sw == smallW && sh == smallH && gap == gapPx) return;
        largeW = lw;
        largeH = lh;
        smallW = sw;
        smallH = sh;
        gapPx = gap;
        notifyDataSetChanged();
        if (rv != null) rv.post(this::pin);
    }

    /** Width of the large card plus its gap: what the row's right padding has to leave free so it can sit at the left. */
    int heroSlotPx() {
        return largeW + gapPx;
    }

    int currentIndex() {
        return entries.isEmpty() ? 0 : Math.max(0, Math.min(current, entries.size() - 1));
    }

    @Override
    public void onAttachedToRecyclerView(@NonNull RecyclerView recyclerView) {
        super.onAttachedToRecyclerView(recyclerView);
        rv = recyclerView;
    }

    @Override
    public void onDetachedFromRecyclerView(@NonNull RecyclerView recyclerView) {
        super.onDetachedFromRecyclerView(recyclerView);
        if (heroAnim != null) heroAnim.cancel();
        rv = null;
    }

    // ---- the large card ------------------------------------------------------------------------------

    private static int lerp(int a, int b, float t) {
        return Math.round(a + (b - a) * t);
    }

    /** 0 = small card, 1 = large card, for the title at this position. */
    private float amtFor(int pos) {
        if (pos == current) return heroT;
        if (pos == prev) return prevStart * (1f - heroT);
        return 0f;
    }

    /** Titles left of the large one are scrolled out of view: hide them so no sliver shows at the edge. */
    private float alphaFor(int pos) {
        if (pos == current) return prev > current ? Math.max(heroT, 0.001f) : 1f;      // coming in from the left
        if (pos == prev) return prev < current ? 1f - heroT : 1f;                        // leaving to the left
        return pos < current ? 0f : 1f;
    }

    /** Applies the size, text and fade for this title's place in the row (cheap: called every frame of a change). */
    private void applyAmt(PosterHolder h, int pos) {
        if (largeW == 0) return;
        float amt = amtFor(pos);
        int cw = lerp(smallW, largeW, amt), ch = lerp(smallH, largeH, amt);
        RecyclerView.LayoutParams ilp = (RecyclerView.LayoutParams) h.itemView.getLayoutParams();
        if (ilp == null) ilp = new RecyclerView.LayoutParams(cw, ViewGroup.LayoutParams.WRAP_CONTENT);
        boolean changed = ilp.width != cw || ilp.getMarginEnd() != gapPx;
        ilp.width = cw;
        ilp.setMarginEnd(gapPx);
        if (changed) h.itemView.setLayoutParams(ilp);
        ViewGroup.LayoutParams cp = h.card.getLayoutParams();
        if (cp.width != cw || cp.height != ch) {
            cp.width = cw;
            cp.height = ch;
            h.card.setLayoutParams(cp);
        }
        int below = ch + (int) (10 * d);               // just clear of the cursor's border under the poster
        FrameLayout.LayoutParams np = (FrameLayout.LayoutParams) h.name.getLayoutParams();
        FrameLayout.LayoutParams dp = (FrameLayout.LayoutParams) h.desc.getLayoutParams();
        if (np.topMargin != below || np.width != largeW) {       // fixed width: nothing to lay out while the card grows
            np.width = largeW;
            np.topMargin = below;
            h.name.setLayoutParams(np);
            dp.width = largeW;
            dp.topMargin = below + (int) (24 * d);
            h.desc.setLayoutParams(dp);
        }
        float textA = Math.max(0f, (amt - 0.35f) / 0.65f);        // the words appear once the card is mostly large
        h.name.setAlpha(textA);
        h.desc.setAlpha(textA * 0.85f);
        h.itemView.setAlpha(alphaFor(pos));
        if (h.lastCw == cw && h.lastCh == ch) return;               // same size as last time: the artwork is already fitted
        h.lastCw = cw;
        h.lastCh = ch;
        if (h.matrix) h.poster.setImageMatrix(coverMatrix(h.bw, h.bh, cw, ch));
        if (h.iconTile) {
            int size = Math.round(ch * 0.46f);
            int padX = Math.max(0, (cw - size) / 2), padY = Math.max(0, (ch - size) / 2);
            h.poster.setPadding(padX, padY, padX, padY);
        }
    }

    private void applyVisible() {
        if (rv == null) return;
        for (int i = 0; i < rv.getChildCount(); i++) {
            RecyclerView.ViewHolder vh = rv.getChildViewHolder(rv.getChildAt(i));
            int pos = vh.getBindingAdapterPosition();
            if (vh instanceof PosterHolder && pos != RecyclerView.NO_POSITION) applyAmt((PosterHolder) vh, pos);
        }
    }

    /** Keeps the large card at the left edge (after the list changed or the screen was resized). */
    void pin() {
        if (rv == null || entries.isEmpty()) return;
        RecyclerView.LayoutManager lm = rv.getLayoutManager();
        if (lm instanceof LinearLayoutManager) ((LinearLayoutManager) lm).scrollToPositionWithOffset(currentIndex(), 0);
    }

    /** The focus moved to this title: it becomes the large one, the old one shrinks, the row glides. */
    private void makeCurrent(final int idx) {
        if (idx < 0 || idx >= entries.size() || (idx == current && heroAnim == null)) return;
        float running = heroT;
        boolean wasRunning = heroAnim != null;
        if (wasRunning) {
            heroAnim.cancel();
            heroAnim = null;
        }
        final int old = current;
        int dropped = prev;                           // a title that was still shrinking just snaps small
        current = idx;
        if (rv == null || !rv.isAttachedToWindow() || largeW == 0) {
            prev = -1;
            heroT = 1f;
            applyVisible();
            return;
        }
        RecyclerView.ViewHolder target = rv.findViewHolderForAdapterPosition(idx);
        if (target == null || old == idx) {
            prev = -1;
            heroT = 1f;
            applyVisible();
            pin();
            return;
        }
        final int startOffset = target.itemView.getLeft() - rv.getPaddingLeft();
        prev = old;
        prevStart = wasRunning ? running : 1f;
        heroT = 0f;
        if (dropped >= 0 && dropped != old) {
            RecyclerView.ViewHolder dv = rv.findViewHolderForAdapterPosition(dropped);
            if (dv instanceof PosterHolder) applyAmt((PosterHolder) dv, dropped);
        }
        final LinearLayoutManager lm = rv.getLayoutManager() instanceof LinearLayoutManager
                ? (LinearLayoutManager) rv.getLayoutManager() : null;
        heroAnim = android.animation.ValueAnimator.ofFloat(0f, 1f);
        heroAnim.setDuration(360);
        heroAnim.setInterpolator(new android.view.animation.DecelerateInterpolator(2f));
        heroAnim.addUpdateListener(an -> {
            heroT = (Float) an.getAnimatedValue();
            applyVisible();
            if (lm != null) lm.scrollToPositionWithOffset(idx, Math.round(startOffset * (1f - heroT)));
        });
        heroAnim.addListener(new android.animation.AnimatorListenerAdapter() {
            private boolean cancelled;

            @Override
            public void onAnimationCancel(android.animation.Animator animation) {
                cancelled = true;
            }

            @Override
            public void onAnimationEnd(android.animation.Animator animation) {
                if (cancelled) return;
                heroAnim = null;
                prev = -1;
                heroT = 1f;
                applyVisible();
                if (lm != null) lm.scrollToPositionWithOffset(idx, 0);
            }
        });
        heroAnim.start();
    }

    /** Reloads the list from disk. Only rebinds when something changed. Returns true when it did. */
    boolean refresh() {
        File wf = wallpaperFile();
        return applyFresh(NuvioHistory.load(ctx), findNuvio(), wf != null ? wf.lastModified() : 0L);
    }

    private static final java.util.concurrent.ExecutorService IO = java.util.concurrent.Executors.newSingleThreadExecutor();
    private final android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());

    /** Same as refresh(), but the file reading happens off the main thread; changed runs on it when the list changed. */
    void refreshAsync(final Runnable changed) {
        IO.execute(() -> {
            final List<NuvioHistory.Entry> fresh = NuvioHistory.load(ctx);
            final String pkg = findNuvio();
            File wf = wallpaperFile();
            final long stamp = wf != null ? wf.lastModified() : 0L;
            main.post(() -> {
                if (applyFresh(fresh, pkg, stamp) && changed != null) changed.run();
            });
        });
    }

    private static final String KEY_OFF = "cw_off";

    /** Packages whose titles are hidden from Continue watching. */
    static java.util.Set<String> offApps(Context c) {
        return new java.util.HashSet<>(c.getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE)
                .getStringSet(KEY_OFF, new java.util.HashSet<String>()));
    }

    static void setOff(Context c, String pkg, boolean off) {
        java.util.Set<String> set = offApps(c);
        if (off) set.add(pkg); else set.remove(pkg);
        c.getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE).edit().putStringSet(KEY_OFF, set).apply();
    }

    private boolean applyFresh(List<NuvioHistory.Entry> fresh, String pkg, long wallpaperStampNow) {
        java.util.Set<String> off = offApps(ctx);
        if (!off.isEmpty()) {
            List<NuvioHistory.Entry> kept = new ArrayList<>();
            for (NuvioHistory.Entry e : fresh) {
                String p = e.pkg != null && !e.pkg.isEmpty() ? e.pkg : pkg;
                if (p == null || !off.contains(p)) kept.add(e);
            }
            fresh = kept;
        }
        StringBuilder sig = new StringBuilder();
        for (NuvioHistory.Entry e : fresh) {
            sig.append(e.key).append('|').append(e.poster).append('|').append(e.position / 15000L)
                    .append('|').append(e.duration).append(';');
        }
        nuvioPkg = pkg;
        sig.append("w").append(wallpaperStampNow);
        if (sig.toString().equals(signature)) return false;
        signature = sig.toString();
        entries.clear();
        entries.addAll(fresh);
        if (heroAnim != null) {
            heroAnim.cancel();
            heroAnim = null;
        }
        prev = -1;
        heroT = 1f;
        current = entries.isEmpty() ? 0 : Math.max(0, Math.min(current, entries.size() - 1));
        notifyDataSetChanged();
        main.post(this::pin);
        return true;
    }

    int posterCount() {
        return entries.size();
    }

    NuvioHistory.Entry entryAt(int index) {
        return index >= 0 && index < entries.size() ? entries.get(index) : null;
    }

    /** The app this title belongs to (falls back to Nuvio), or null when it is not installed. */
    private String resolvePkg(NuvioHistory.Entry e) {
        PackageManager pm = ctx.getPackageManager();
        boolean ok = e.pkg != null && !e.pkg.isEmpty()
                && (pm.getLaunchIntentForPackage(e.pkg) != null || pm.getLeanbackLaunchIntentForPackage(e.pkg) != null);
        return ok ? e.pkg : nuvioPkg;
    }

    /** Name of the app for the "Open ..." menu row. */
    String appLabel(NuvioHistory.Entry e) {
        String pkg = resolvePkg(e);
        if (pkg == null) return null;
        try {
            PackageManager pm = ctx.getPackageManager();
            return String.valueOf(pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)));
        } catch (PackageManager.NameNotFoundException | RuntimeException ex) {
            return null;
        }
    }

    /** Just opens the app, without telling it to play. */
    void openApp(NuvioHistory.Entry e) {
        String pkg = resolvePkg(e);
        if (pkg == null) {
            { if (ctx instanceof MainActivity) ((MainActivity) ctx).toast(R.string.library_no_nuvio, false); else Toast.makeText(ctx, R.string.library_no_nuvio, Toast.LENGTH_SHORT).show(); }
            return;
        }
        launch(pkg);
    }

    @Override
    public int getItemCount() {
        return entries.size();
    }

    @Override
    public long getItemId(int position) {
        return entries.get(position).key.hashCode();
    }

    // ---- views ---------------------------------------------------------------------------------------

    private static final class PosterHolder extends RecyclerView.ViewHolder {
        final View card, scrim;
        final ImageView poster, posterBg;
        final TextView name, desc;
        final ColorBar progress;
        int bw, bh;                 // size of the artwork when it is placed with a matrix
        boolean matrix;             // artwork is placed with coverMatrix (re-done as the card changes size)
        boolean iconTile;           // the app's icon in the middle of a colour tile (padding follows the card size)
        int lastCw = -1, lastCh = -1;   // the size the artwork was last fitted to (skips the work while it is unchanged)

        PosterHolder(View v) {
            super(v);
            card = v.findViewById(R.id.card);
            scrim = v.findViewById(R.id.scrim);
            poster = v.findViewById(R.id.poster);
            posterBg = v.findViewById(R.id.posterBg);
            name = v.findViewById(R.id.name);
            desc = v.findViewById(R.id.desc);
            progress = v.findViewById(R.id.progress);
        }
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(ctx).inflate(R.layout.item_continue, parent, false);
        final PosterHolder h = new PosterHolder(v);
        final float radius = RADIUS_DP * d;
        h.card.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), radius);
            }
        });
        h.card.setClipToOutline(true);
        // The same light as on the app tiles: the soft sheen from the top, a touch of shade below, and the rim light.
        android.graphics.drawable.Drawable sheen = ctx.getResources().getDrawable(R.drawable.tile_highlight, ctx.getTheme()).mutate();
        h.card.setForeground(new android.graphics.drawable.LayerDrawable(new android.graphics.drawable.Drawable[]{
                sheen, new RimLight(d, RADIUS_DP, false, false)}));
        h.scrim.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0x00000000, 0xB8000000}));
        h.card.setOnKeyListener((view, keyCode, event) -> {
            if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
            int p = h.getBindingAdapterPosition();
            if (p == RecyclerView.NO_POSITION) return false;
            switch (keyCode) {
                case KeyEvent.KEYCODE_DPAD_UP:
                case KeyEvent.KEYCODE_DPAD_DOWN:
                    return host.onPosterKey(p, keyCode);
                case KeyEvent.KEYCODE_DPAD_LEFT:
                    return p == 0;                               // end of the row: stay put
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                    return p == entries.size() - 1;
                default:
                    return false;
            }
        });
        h.card.setOnFocusChangeListener((view, focused) -> {
            float s = focused ? POSTER_FOCUS_SCALE : 1f;
            view.animate().scaleX(s).scaleY(s)
                    .setDuration(focused ? 260 : 180)
                    .setInterpolator(new OvershootInterpolator(focused ? 1.6f : 0f))
                    .start();
            int p = h.getBindingAdapterPosition();
            if (focused && p != RecyclerView.NO_POSITION) {
                makeCurrent(p);
                host.onPosterFocused(p, view, 0xFFE50914);
            }
        });
        h.card.setOnLongClickListener(view -> {
            int p = h.getBindingAdapterPosition();
            if (p != RecyclerView.NO_POSITION && p < entries.size()) host.onPosterLongPressed(p, view);
            return true;
        });
        h.card.setOnClickListener(view -> {
            int p = h.getBindingAdapterPosition();
            if (p != RecyclerView.NO_POSITION && p < entries.size()) resume(entries.get(p));
        });
        return h;
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        PosterHolder h = (PosterHolder) holder;
        NuvioHistory.Entry e = entries.get(position);

        // Item = 8dp air, the card, then (for the large one) a title and a description line.
        int w = lerp(smallW > 0 ? smallW : (int) (100 * d), largeW > 0 ? largeW : (int) (300 * d), amtFor(position));
        int ht = lerp(smallH > 0 ? smallH : (int) (150 * d), largeH > 0 ? largeH : (int) (170 * d), amtFor(position));
        h.matrix = false;
        h.iconTile = false;
        h.lastCw = -1;
        h.lastCh = -1;
        h.poster.setPadding(0, 0, 0, 0);
        h.poster.setImageMatrix(null);
        Bitmap bmp = null;
        if (!e.poster.isEmpty()) {
            bmp = posters.get(e.poster);
            if (bmp == null) {
                bmp = decode(e, largeW > 0 ? largeW : w);
                if (bmp != null) posters.put(e.poster, bmp);
            }
        }
        if (bmp != null && isIconLike(bmp)) bmp = null;   // an app icon is not a thumbnail: use the app's own tile
        if (bmp != null) {
            float ratio = bmp.getWidth() / (float) bmp.getHeight();
            if (ratio >= 1.5f || ratio < 0.8f) {
                // Wide artwork (episode stills) and tall posters fill the whole card. Tall artwork keeps its upper part.
                h.posterBg.setImageDrawable(null);
                h.poster.setScaleType(ImageView.ScaleType.MATRIX);
                h.poster.setImageBitmap(bmp);
                h.poster.setImageMatrix(coverMatrix(bmp.getWidth(), bmp.getHeight(), w, ht));
                h.matrix = true;
                h.bw = bmp.getWidth();
                h.bh = bmp.getHeight();
            } else {
                // Square-ish artwork (VLC and similar): shown whole, not zoomed, over a soft blurred copy of itself.
                h.poster.setScaleType(ImageView.ScaleType.FIT_CENTER);
                h.poster.setImageBitmap(bmp);
                Bitmap bl = blurs.get(e.poster);
                if (bl == null) {
                    try {
                        bl = BlurUtil.blur(bmp, 96, 6);
                        if (bl != null) blurs.put(e.poster, bl);
                    } catch (RuntimeException | OutOfMemoryError ignored) {
                    }
                }
                h.posterBg.setScaleType(ImageView.ScaleType.CENTER_CROP);
                h.posterBg.setImageBitmap(bl);
            }
        } else if (applyAppTile(h, e, w, ht)) {
            h.iconTile = h.poster.getScaleType() == ImageView.ScaleType.FIT_CENTER;
            // The app's own thumbnail, exactly as the apps list shows it.
        } else {
            h.posterBg.setImageDrawable(null);
            h.poster.setScaleType(ImageView.ScaleType.CENTER_CROP);
            float hue = Math.abs(e.title.hashCode()) % 360;
            int c1 = Color.HSVToColor(new float[]{hue, 0.55f, 0.50f});
            int c2 = Color.HSVToColor(new float[]{(hue + 40f) % 360f, 0.7f, 0.16f});
            h.poster.setImageDrawable(new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{c1, c2}));
        }
        h.name.setText(e.title);
        h.desc.setText(describe(e));
        applyAmt(h, position);
        if (e.duration > 0) {
            h.progress.setVisibility(View.VISIBLE);
            h.progress.set(e.position / (float) e.duration, 0xFFE50914, 0xFFFF4D4D);
            h.scrim.setVisibility(View.VISIBLE);
        } else {
            h.progress.setVisibility(View.GONE);
            h.scrim.setVisibility(View.GONE);
        }
    }

    /** One line under the large card: what it is, which episode, and where it plays, e.g. "Series · S2 E5 · Nuvio". */
    private String describe(NuvioHistory.Entry e) {
        StringBuilder sb = new StringBuilder();
        if ("movie".equals(e.kind)) sb.append(ctx.getString(R.string.kind_movie));
        else if ("series".equals(e.kind)) sb.append(ctx.getString(R.string.kind_series));
        if (e.subtitle != null && !e.subtitle.trim().isEmpty()) {
            if (sb.length() > 0) sb.append(" \u00B7 ");
            sb.append(e.subtitle.trim());
        }
        String app = appLabel(e);
        if (app != null && !app.isEmpty()) {
            if (sb.length() > 0) sb.append(" \u00B7 ");
            sb.append(app);
        }
        return sb.toString();
    }

    /** A copy of the drawable, so the tile in the apps list and this poster never fight over its bounds. */
    private Drawable copyOf(Drawable dr) {
        if (dr == null) return null;
        Drawable.ConstantState cs = dr.getConstantState();
        return cs != null ? cs.newDrawable(ctx.getResources()).mutate() : dr;
    }

    /**
     * No real thumbnail for this title: show what the app's tile in the apps list shows. A banner fills the card;
     * an icon-only app gets the same colour gradient with its icon in the middle. False if the app is unknown.
     */
    private boolean applyAppTile(PosterHolder h, NuvioHistory.Entry e, int w, int ht) {
        if (apps == null) return false;
        AppAdapter.Entry ae = apps.entryFor(resolvePkg(e));
        if (ae == null) return false;
        AppAdapter.TileArt art = apps.artFor(ae);
        if (art.packIcon == null && art.banner != null) {
            h.posterBg.setImageDrawable(null);
            h.poster.setScaleType(ImageView.ScaleType.CENTER_CROP);
            h.poster.setImageDrawable(copyOf(art.banner));
            return true;
        }
        Drawable icon = copyOf(art.packIcon != null ? art.packIcon : art.icon);
        if (icon == null) return false;
        int color = apps.ambientOf(ae);
        h.posterBg.setScaleType(ImageView.ScaleType.FIT_XY);
        h.posterBg.setImageDrawable(new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{AppAdapter.mixBlack(color, 0.42f), AppAdapter.mixBlack(color, 0.78f)}));
        int size = Math.round(ht * 0.46f);
        int padX = Math.max(0, (w - size) / 2), padY = Math.max(0, (ht - size) / 2);
        h.poster.setPadding(padX, padY, padX, padY);
        h.poster.setScaleType(ImageView.ScaleType.FIT_CENTER);
        h.poster.setImageDrawable(icon);
        return true;
    }

    /** Square-ish pictures (about 1:1) are app icons, not video thumbnails. Posters (tall) and stills (wide) are not. */
    private static boolean isIconLike(Bitmap b) {
        float ratio = b.getWidth() / (float) b.getHeight();
        return ratio >= 0.85f && ratio <= 1.2f;
    }

    private File wallpaperFile() {
        if (!WallpaperStore.exists(ctx)) return null;
        String path = WallpaperStore.uri(ctx).getPath();
        return path == null ? null : new File(path);
    }

    /** The chosen wallpaper, decoded once at a size that suits the cards; null when none is set. */
    private Bitmap wallpaper(int cardWidth) {
        File f = wallpaperFile();
        if (f == null) {
            wallpaperBmp = null;
            wallpaperStamp = 0L;
            return null;
        }
        long stamp = f.lastModified();
        if (wallpaperBmp != null && stamp == wallpaperStamp) return wallpaperBmp;
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(f.getPath(), o);
            int target = Math.max(960, (int) (cardWidth * 2.2f));
            int sample = 1;
            while (o.outWidth / (sample * 2) >= target) sample *= 2;
            o = new BitmapFactory.Options();
            o.inSampleSize = sample;
            o.inPreferredConfig = Bitmap.Config.RGB_565;
            wallpaperBmp = BitmapFactory.decodeFile(f.getPath(), o);
            wallpaperStamp = stamp;
        } catch (Exception | OutOfMemoryError ex) {
            wallpaperBmp = null;
        }
        return wallpaperBmp;
    }

    /** Covers the card with the wallpaper, slightly zoomed, panned to (px, py) = 0..1 across the spare picture. */
    private static android.graphics.Matrix wallpaperMatrix(int bw, int bh, int vw, int vh, float px, float py) {
        float scale = Math.max(vw / (float) bw, vh / (float) bh) * 1.25f;
        float dx = -(bw * scale - vw) * px;
        float dy = -(bh * scale - vh) * py;
        android.graphics.Matrix m = new android.graphics.Matrix();
        m.setScale(scale, scale);
        m.postTranslate(dx, dy);
        return m;
    }

    /** Scales the artwork to cover the card completely. Tall artwork keeps its upper part in view. */
    private static android.graphics.Matrix coverMatrix(int bw, int bh, int vw, int vh) {
        float scale = Math.max(vw / (float) bw, vh / (float) bh);
        float dx = (vw - bw * scale) / 2f;
        float spare = bh * scale - vh;                     // how much taller than the card the artwork is
        float bias = bw / (float) bh < 1.2f ? 0.22f : 0.5f;  // 0 = top edge, 0.5 = centre
        android.graphics.Matrix m = new android.graphics.Matrix();
        m.setScale(scale, scale);
        m.postTranslate(dx, -spare * bias);
        return m;
    }

    private Bitmap decode(NuvioHistory.Entry e, int targetW) {
        try {
            File f = new File(new File(ctx.getFilesDir(), "nuvio_posters"), e.poster);
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(f.getPath(), o);
            int target = Math.max(1, (int) (targetW * 1.3f));
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
            { if (ctx instanceof MainActivity) ((MainActivity) ctx).toast(R.string.library_no_nuvio, false); else Toast.makeText(ctx, R.string.library_no_nuvio, Toast.LENGTH_SHORT).show(); }
            return;
        }
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            ctx.startActivity(i);
        } catch (Exception ex) {
            { if (ctx instanceof MainActivity) ((MainActivity) ctx).toast(R.string.library_no_nuvio, false); else Toast.makeText(ctx, R.string.library_no_nuvio, Toast.LENGTH_SHORT).show(); }
        }
    }

    /**
     * Opens exactly what was being watched. In order: the app still has the video open (it jumps to the saved
     * spot and plays), a link that opens that one video in the app, the app's own "open player" shortcut,
     * and finally just the app.
     */
    void resume(NuvioHistory.Entry e) {
        PackageManager pm = ctx.getPackageManager();
        String pkg = (e.pkg == null || e.pkg.isEmpty() || pm.getLaunchIntentForPackage(e.pkg) == null
                && pm.getLeanbackLaunchIntentForPackage(e.pkg) == null) ? nuvioPkg : e.pkg;
        if (pkg == null) {
            { if (ctx instanceof MainActivity) ((MainActivity) ctx).toast(R.string.library_no_nuvio, false); else Toast.makeText(ctx, R.string.library_no_nuvio, Toast.LENGTH_SHORT).show(); }
            return;
        }
        // 1) The app still holds this video: play it from the saved position and bring the app up.
        try {
            MediaSessionManager sm = (MediaSessionManager) ctx.getSystemService(Context.MEDIA_SESSION_SERVICE);
            for (MediaController c : sm.getActiveSessions(new ComponentName(ctx, MediaListener.class))) {
                if (!pkg.equals(c.getPackageName())) continue;
                MediaMetadata m = c.getMetadata();
                String t = m == null ? null : m.getString(MediaMetadata.METADATA_KEY_TITLE);
                if (t == null || !t.trim().equals(e.title)) continue;
                android.media.session.PlaybackState st = c.getPlaybackState();
                long cur = st == null ? 0L : st.getPosition();
                if (e.position > 0 && Math.abs(cur - e.position) > 5000L) c.getTransportControls().seekTo(e.position);
                c.getTransportControls().play();
                android.app.PendingIntent pi = c.getSessionActivity();
                if (pi != null) {
                    try {
                        pi.send();
                        return;
                    } catch (android.app.PendingIntent.CanceledException ignored) {
                    }
                }
                launch(pkg);
                return;
            }
        } catch (RuntimeException ignored) {
        }
        // Nuvio: open exactly this movie / episode (its own "continue watching" launch). The app's player shortcut
        // is never used for it: that one just reopens whatever was playing last, the same for every card.
        if (DeepLinks.isNuvioFamily(pkg)) {
            resumeNuvio(pkg, e);
            return;
        }
        // 2) A link that opens this one video in the app.
        Intent link = DeepLinks.build(ctx, pkg, e);
        if (link != null) {
            link.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try {
                ctx.startActivity(link);
                return;
            } catch (Exception ignored) {
            }
        }
        // 3) The app's own shortcut to its player (kept while the launcher is running).
        android.app.PendingIntent pi = MediaListener.sessionActivityFor(e.key);
        if (pi != null) {
            try {
                pi.send();
                return;
            } catch (android.app.PendingIntent.CanceledException ignored) {
            }
        }
        // 4) Ask the app to play the title by name, like a voice search does.
        Intent search = DeepLinks.searchIntent(ctx, pkg, e);
        if (search != null) {
            search.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try {
                ctx.startActivity(search);
                return;
            } catch (Exception ignored) {
            }
        }
        launch(pkg);
    }

    private void resumeNuvio(final String pkg, final NuvioHistory.Entry e) {
        if (e.imdb != null && !e.imdb.isEmpty()) {
            openNuvio(pkg, e, e.imdb, e.kind);
            return;
        }
        // The id was not known yet (or the first lookup missed): look it up now, then open.
        final android.os.Handler ui = new android.os.Handler(android.os.Looper.getMainLooper());
        new Thread(() -> {
            final String[] hit = DeepLinks.lookupImdb(e.title, e.subtitle, "series".equals(e.kind));
            ui.post(() -> {
                if (hit == null) {
                    launch(pkg);
                    return;
                }
                NuvioHistory.setLink(ctx, e.title, e.subtitle.isEmpty() ? null : e.subtitle, hit[0], hit[1]);
                openNuvio(pkg, e, hit[0], hit[1]);
            });
        }, "nuvio-lookup").start();
    }

    private void openNuvio(String pkg, NuvioHistory.Entry e, String imdb, String kind) {
        Intent play = DeepLinks.nuvioPlay(ctx, pkg, e, imdb, kind);
        if (play != null) {
            try {
                ctx.startActivity(play);
                return;
            } catch (Exception ignored) {
            }
        }
        // Episode unknown: the title's own page.
        Entry2.fill(e, imdb, kind);
        Intent link = DeepLinks.build(ctx, pkg, e);
        if (link != null) {
            link.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try {
                ctx.startActivity(link);
                return;
            } catch (Exception ignored) {
            }
        }
        launch(pkg);
    }

    private static final class Entry2 {
        static void fill(NuvioHistory.Entry e, String imdb, String kind) {
            e.imdb = imdb;
            e.kind = kind == null ? "" : kind;
        }
    }
}
