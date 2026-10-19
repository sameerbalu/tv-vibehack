package com.gathin.tvhome;

import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.provider.Settings;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.animation.OvershootInterpolator;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** App tiles. The first dockSize positions are pinned favourites, followed by alphabetical apps. */
public class AppAdapter extends RecyclerView.Adapter<AppAdapter.Holder> {

    interface TileListener {
        void onTileFocused(int position, View card);
        boolean onTileVerticalKey(int position, int keyCode);
        boolean onTileHorizontalKey(int position, int keyCode);
        void onTileLongPressed(int position);
        boolean onTileClick(int position);
    }

    /** An installed app as listed in the "Hide apps" menu. */
    static final class AppRef {
        final String label;
        final String pkg;
        final Drawable icon;

        AppRef(String label, String pkg, Drawable icon) {
            this.label = label;
            this.pkg = pkg;
            this.icon = icon;
        }
    }

    static final class Entry {
        final String label;
        final ComponentName component;
        final String pkg;
        final String key;
        final Drawable banner;
        final Drawable icon;
        /** Icon from the selected icon pack, or null when the pack has none for this app. */
        Drawable packIcon;
        int ambient;

        Entry(String label, ComponentName component, Drawable banner, Drawable icon) {
            this.label = label;
            this.component = component;
            this.pkg = component.getPackageName();
            this.key = component.flattenToShortString();
            this.banner = banner;
            this.icon = icon;
        }
    }

    static final float FOCUS_SCALE = 1.10f;
    private static final String PREFS = "launcher_preferences";
    private static final String DOCK_ORDER = "dock_order";      // old: dock packages only
    private static final String HIDDEN_APPS = "hidden_apps";    // packages the person chose to hide
    private static final String ICON_PACK = "icon_pack";        // package of the chosen icon pack, or empty
    private static final String ICON_SHAPE = "icon_shape";      // IconShapes.*
    private static final String APP_ORDER = "app_order";        // new: every app, in order; the first dockSize are the dock

    private final Context ctx;
    private final TileListener listener;
    private final int dockSize;
    private final List<String> favourites = new ArrayList<>();
    private final Set<String> hidden;
    private final List<Entry> items = new ArrayList<>();
    private final Set<String> userHidden = new HashSet<>();
    /** Saved order of all apps (component keys), or null while the default order is in use. */
    private List<String> order;
    private String iconPackPkg = "";
    private int iconShape = IconShapes.AUTO;
    private IconPack pack;

    AppAdapter(Context ctx, TileListener listener, int dockSize) {
        this.ctx = ctx;
        this.listener = listener;
        this.dockSize = dockSize;
        List<String> defaults = new ArrayList<>(Arrays.asList(ctx.getResources().getStringArray(R.array.dock_apps)));
        SharedPreferences prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String savedAll = prefs.getString(APP_ORDER, null);
        if (savedAll != null && !savedAll.trim().isEmpty()) {
            order = new ArrayList<>();
            for (String key : savedAll.split(";")) if (!key.trim().isEmpty()) order.add(key.trim());
        }
        iconPackPkg = prefs.getString(ICON_PACK, "");
        String hiddenSaved = prefs.getString(HIDDEN_APPS, null);
        if (hiddenSaved != null) {
            for (String pkg : hiddenSaved.split(";")) if (!pkg.trim().isEmpty()) userHidden.add(pkg.trim());
        }
        String saved = prefs.getString(DOCK_ORDER, null);
        if (saved != null && !saved.trim().isEmpty()) {
            for (String pkg : saved.split(";")) {
                if (!pkg.trim().isEmpty() && !favourites.contains(pkg.trim())) favourites.add(pkg.trim());
            }
        }
        for (String pkg : defaults) if (!favourites.contains(pkg)) favourites.add(pkg);
        hidden = new HashSet<>(Arrays.asList(ctx.getResources().getStringArray(R.array.hidden_apps)));
        setHasStableIds(true);
    }

    void reload() {
        PackageManager pm = ctx.getPackageManager();
        Intent probe = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
        List<ResolveInfo> found = pm.queryIntentActivities(probe, 0);
        List<Entry> fresh = new ArrayList<>();
        if (iconPackPkg.isEmpty()) pack = null;
        else if (pack == null || !pack.pkg.equals(iconPackPkg)) pack = IconPack.load(ctx, iconPackPkg);
        final float density = ctx.getResources().getDisplayMetrics().density;
        final int iconPx = Math.max(64, (int) (64 * density));
        for (ResolveInfo ri : found) {
            if (ri.activityInfo == null) continue;
            String pkg = ri.activityInfo.packageName;
            if (pkg.equals(ctx.getPackageName()) || hidden.contains(pkg) || userHidden.contains(pkg)) continue;
            Drawable banner = ri.activityInfo.loadBanner(pm);
            if (banner == null) banner = ri.activityInfo.applicationInfo.loadBanner(pm);
            Drawable icon = IconShapes.shape(ctx, ri.loadIcon(pm), iconShape, iconPx);
            String label = String.valueOf(ri.loadLabel(pm));
            ComponentName component = new ComponentName(pkg, ri.activityInfo.name);
            Entry entry = new Entry(label, component, banner, icon);
            if (pack != null) {
                Drawable packed = pack.iconFor(component);
                if (packed != null) entry.packIcon = IconShapes.shape(ctx, packed, iconShape, iconPx);
            }
            fresh.add(entry);
        }
        final List<String> ord = order;
        Collections.sort(fresh, (a, b) -> {
            if (ord != null) {
                int ia = ord.indexOf(a.key), ib = ord.indexOf(b.key);
                if (ia >= 0 || ib >= 0) {
                    if (ia < 0) return 1;
                    if (ib < 0) return -1;
                    return Integer.compare(ia, ib);
                }
                return a.label.compareToIgnoreCase(b.label);
            }
            int fa = favourites.indexOf(a.pkg), fb = favourites.indexOf(b.pkg);
            if (fa >= 0 || fb >= 0) {
                if (fa < 0) return 1;
                if (fb < 0) return -1;
                return Integer.compare(fa, fb);
            }
            return a.label.compareToIgnoreCase(b.label);
        });
        items.clear();
        items.addAll(fresh);
        notifyDataSetChanged();
    }

    /** Every launchable app, hidden or not (except the ones config.xml always hides), sorted by name. */
    List<AppRef> allApps() {
        PackageManager pm = ctx.getPackageManager();
        Intent probe = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
        List<AppRef> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (ResolveInfo ri : pm.queryIntentActivities(probe, 0)) {
            if (ri.activityInfo == null) continue;
            String pkg = ri.activityInfo.packageName;
            if (pkg.equals(ctx.getPackageName()) || hidden.contains(pkg) || !seen.add(pkg)) continue;
            out.add(new AppRef(String.valueOf(ri.loadLabel(pm)), pkg, ri.loadIcon(pm)));
        }
        Collections.sort(out, (a, b) -> a.label.compareToIgnoreCase(b.label));
        return out;
    }

    boolean isUserHidden(String pkg) {
        return userHidden.contains(pkg);
    }

    /** Remembers the choice; call reload() afterwards to apply it. */
    void setUserHidden(String pkg, boolean hide) {
        if (hide) userHidden.add(pkg);
        else userHidden.remove(pkg);
        StringBuilder serialized = new StringBuilder();
        for (String p : userHidden) {
            if (serialized.length() > 0) serialized.append(';');
            serialized.append(p);
        }
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(HIDDEN_APPS, serialized.toString()).apply();
    }

    private List<String> currentKeys() {
        List<String> keys = new ArrayList<>();
        for (Entry e : items) keys.add(e.key);
        return keys;
    }

    /** The current arrangement, to restore if the person cancels while arranging. */
    List<String> orderSnapshot() {
        return currentKeys();
    }

    void restoreOrder(List<String> snapshot) {
        order = snapshot == null ? null : new ArrayList<>(snapshot);
        reload();
    }

    void saveOrder() {
        order = currentKeys();
        StringBuilder serialized = new StringBuilder();
        for (String key : order) {
            if (serialized.length() > 0) serialized.append(';');
            serialized.append(key);
        }
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(APP_ORDER, serialized.toString()).apply();
    }

    /** Moves one app to any position; everything in between shifts by one. Positions below dockSize are the dock. */
    void moveItem(int from, int to) {
        if (from == to || from < 0 || to < 0 || from >= items.size() || to >= items.size()) return;
        Entry e = items.remove(from);
        items.add(to, e);
        order = currentKeys();
        notifyItemMoved(from, to);
        // Rebind the range: tiles that crossed the dock boundary change how they look, and click targets move.
        notifyItemRangeChanged(Math.min(from, to), Math.abs(from - to) + 1);
    }

    @NonNull
    @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_app, parent, false);
        return new Holder(v, listener);
    }

    @Override
    public void onBindViewHolder(@NonNull Holder h, int position) {
        Entry e = items.get(position);
        h.name.setText(e.label);
        h.name.setVisibility(position < dockSize ? View.INVISIBLE : View.VISIBLE);
        boolean focused = h.card.isFocused();
        h.name.setAlpha(focused ? 1f : 0f);
        h.card.setScaleX(focused ? FOCUS_SCALE : 1f);
        h.card.setScaleY(focused ? FOCUS_SCALE : 1f);
        applyVisual(h.banner, h.icon, h.label, e);
        if (e.packIcon == null && e.banner == null) {
            // Icon-only tile: a gradient in the app's own colour instead of flat grey.
            int c = ambientColorAt(position);
            GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                    new int[]{mixBlack(c, 0.55f), mixBlack(c, 0.82f)});
            g.setCornerRadius(16f * ctx.getResources().getDisplayMetrics().density);
            h.card.setBackground(g);
        } else {
            h.card.setBackground(h.defaultBg);
        }
        h.card.setOnClickListener(v -> {
            int p = h.getBindingAdapterPosition();
            if (p == RecyclerView.NO_POSITION || p >= items.size()) return;
            if (listener != null && listener.onTileClick(p)) return;
            launch(items.get(p));
        });
        h.card.setOnLongClickListener(v -> {
            int p = h.getBindingAdapterPosition();
            if (p != RecyclerView.NO_POSITION && listener != null) listener.onTileLongPressed(p);
            return true;
        });
    }

    /** Shows the tile content: the icon-pack icon if there is one, else the banner, else the (shaped) app icon. */
    static void applyVisual(ImageView banner, ImageView icon, TextView label, Entry e) {
        float d = banner.getResources().getDisplayMetrics().density;
        ViewGroup.MarginLayoutParams lp = (ViewGroup.MarginLayoutParams) icon.getLayoutParams();
        if (e.packIcon != null) {
            banner.setImageDrawable(null);
            banner.setVisibility(View.GONE);
            lp.width = lp.height = (int) (60 * d);
            lp.bottomMargin = (int) (14 * d);
            icon.setLayoutParams(lp);
            icon.setImageDrawable(e.packIcon);
            icon.setVisibility(View.VISIBLE);
            label.setText(e.label);
            label.setVisibility(View.VISIBLE);
        } else if (e.banner != null) {
            banner.setImageDrawable(e.banner);
            banner.setVisibility(View.VISIBLE);
            icon.setVisibility(View.GONE);
            label.setVisibility(View.GONE);
        } else {
            banner.setImageDrawable(null);
            banner.setVisibility(View.GONE);
            lp.width = lp.height = (int) (48 * d);
            lp.bottomMargin = (int) (14 * d);
            icon.setLayoutParams(lp);
            icon.setImageDrawable(e.icon);
            icon.setVisibility(View.VISIBLE);
            label.setText(e.label);
            label.setVisibility(View.VISIBLE);
        }
    }

    // ---- icon packs ----------------------------------------------------

    List<IconPack.Info> iconPacks() {
        return IconPack.installed(ctx.getPackageManager());
    }

    String iconPackPackage() {
        return iconPackPkg;
    }

    /** Pass an empty string for the default icons. Call reload() afterwards. */
    void setIconPack(String pkg) {
        iconPackPkg = pkg == null ? "" : pkg;
        pack = null;
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(ICON_PACK, iconPackPkg).apply();
    }

    void launchEntry(Entry e) {
        launch(e);
    }

    private void launch(Entry e) {
        Intent i = new Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
                .setComponent(e.component)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        try {
            ctx.startActivity(i);
        } catch (Exception ex) {
            Toast.makeText(ctx, ctx.getString(R.string.cant_open, e.label), Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    @Override
    public long getItemId(int position) {
        return items.get(position).component.flattenToShortString().hashCode();
    }

    String keyAt(int position) {
        return position >= 0 && position < items.size() ? items.get(position).component.flattenToShortString() : null;
    }

    int positionOf(String key) {
        if (key == null) return -1;
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i).component.flattenToShortString().equals(key)) return i;
        }
        return -1;
    }

    String labelAt(int position) {
        return position >= 0 && position < items.size() ? items.get(position).label : "";
    }

    int ambientColorAt(int position) {
        if (position < 0 || position >= items.size()) return 0xFF444444;
        Entry e = items.get(position);
        if (e.ambient == 0) e.ambient = vividColor(e.packIcon != null ? e.packIcon : (e.banner != null ? e.banner : e.icon));
        return e.ambient;
    }

    /** The colour darkened towards black by this share (0 = unchanged, 1 = black). */
    static int mixBlack(int color, float share) {
        float k = 1f - share;
        return Color.rgb((int) (Color.red(color) * k), (int) (Color.green(color) * k), (int) (Color.blue(color) * k));
    }

    /** A light, readable version of an app colour for text on dark backgrounds. */
    static int textTint(int color) {
        float[] hsv = new float[3];
        Color.colorToHSV(color, hsv);
        hsv[1] = Math.min(hsv[1], 0.55f);
        hsv[2] = 1f;
        return Color.HSVToColor(hsv);
    }

    private static int vividColor(Drawable live) {
        if (live == null) return 0xFF444444;
        Drawable d = live.getConstantState() != null ? live.getConstantState().newDrawable().mutate() : live;
        Rect saved = new Rect(d.getBounds());
        int n = 12;
        Bitmap bmp = Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        d.setBounds(0, 0, n, n);
        d.draw(c);
        d.setBounds(saved);
        return vividColor(bmp);
    }

    /** The most vivid colour of a small bitmap (recycles it). Also used for the Spotify album colour. */
    static int vividColor(Bitmap bmp) {
        int n = bmp.getWidth();
        float[] hsv = new float[3];
        int best = 0xFF444444;
        float bestScore = -1f;
        long r = 0, g = 0, b = 0;
        for (int y = 0; y < n; y++) {
            for (int x = 0; x < n; x++) {
                int p = bmp.getPixel(x, y);
                r += Color.red(p);
                g += Color.green(p);
                b += Color.blue(p);
                Color.colorToHSV(p, hsv);
                float score = hsv[1] * Math.min(1f, hsv[2] * 1.4f);
                if (score > bestScore) {
                    bestScore = score;
                    best = p;
                }
            }
        }
        bmp.recycle();
        if (bestScore < 0.25f) {
            int avg = Color.rgb((int) (r / (n * n)), (int) (g / (n * n)), (int) (b / (n * n)));
            Color.colorToHSV(avg, hsv);
            hsv[2] = Math.max(hsv[2], 0.55f);
            return Color.HSVToColor(hsv);
        }
        Color.colorToHSV(best, hsv);
        hsv[1] = Math.min(1f, hsv[1] * 1.1f);
        hsv[2] = Math.max(0.5f, hsv[2]);
        return Color.HSVToColor(hsv);
    }

    static final class Holder extends RecyclerView.ViewHolder {
        final View card;
        final ImageView banner;
        final ImageView icon;
        final TextView label;
        final TextView name;
        final Drawable defaultBg;

        Holder(View v, TileListener listener) {
            super(v);
            card = v.findViewById(R.id.card);
            banner = v.findViewById(R.id.banner);
            icon = v.findViewById(R.id.icon);
            label = v.findViewById(R.id.label);
            name = v.findViewById(R.id.name);
            defaultBg = card.getBackground();
            final float density = v.getResources().getDisplayMetrics().density;
            final float radius = density * 16f;
            card.setOutlineProvider(new ViewOutlineProvider() {
                @Override
                public void getOutline(View view, Outline outline) {
                    outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), radius);
                }
            });
            card.setClipToOutline(true);
            card.setOnKeyListener((view, keyCode, event) -> {
                if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
                int pos = getBindingAdapterPosition();
                if (pos == RecyclerView.NO_POSITION || listener == null) return false;
                if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                    return listener.onTileHorizontalKey(pos, keyCode);
                }
                if (keyCode == KeyEvent.KEYCODE_DPAD_UP || keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                    return listener.onTileVerticalKey(pos, keyCode);
                }
                return false;
            });
            card.setOnFocusChangeListener((view, focused) -> {
                float s = focused ? FOCUS_SCALE : 1.0f;
                view.animate().scaleX(s).scaleY(s)
                        .setDuration(focused ? 260 : 180)
                        .setInterpolator(new OvershootInterpolator(focused ? 1.6f : 0f))
                        .start();
                name.animate().alpha(focused ? 1f : 0f)
                        .translationY(focused ? density * 4f : -density * 6f)
                        .setDuration(220).start();
                int pos = getBindingAdapterPosition();
                if (focused && listener != null && pos != RecyclerView.NO_POSITION) listener.onTileFocused(pos, view);
            });
        }
    }
}
