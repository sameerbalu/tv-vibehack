package com.gathin.tvhome;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.media.tv.TvContract;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.animation.AnimationUtils;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.LayoutAnimationController;
import android.view.animation.OvershootInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.dynamicanimation.animation.DynamicAnimation;
import androidx.dynamicanimation.animation.SpringAnimation;
import androidx.dynamicanimation.animation.SpringForce;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.List;

/**
 * tvOS style home with three states:
 * <ul>
 *   <li>Dock: clock, top buttons and the dock row at the bottom; nothing else is visible.</li>
 *   <li>All apps (Down from the dock): the background blurs, the clock, buttons and dock fade out and only
 *       the apps that are not in the dock are listed.</li>
 *   <li>Arrange: every app, dock included, can be moved to any position.</li>
 * </ul>
 */
public class MainActivity extends Activity implements AppAdapter.TileListener {

    /** Tiles per row and dock slots (res/values/config.xml, columns). Set in onCreate. */
    static int COLUMNS = 4;
    private static final long MINUTE_MS = 60_000L;
    /** Where the first row of the app list sits (dp from the top) when it opens. Keeps the dock row off screen. */
    private static final int PANEL_TOP_STRIP_DP = 208;   // glass panel top when the Suggested row is shown
    private static final int PANEL_TOP_PLAIN_DP = 28;    // ... and when there is nothing to suggest
    private static final int PANEL_BOTTOM_DP = 24;
    private static final int ROW_INSET_DP = 14;          // first row of apps sits this far inside the panel
    /** Top padding of the grid while arranging, so the dock row and the rows below are all visible. */
    private static final int ARRANGE_TOP_DP = 36;
    private String liveTvPkg;
    private static final int REQUEST_WALLPAPER = 71;
    private static final String PREFS = "launcher_preferences";
    private static final String WALLPAPER_URI = "wallpaper_uri";

    private AppAdapter adapter;
    private RecyclerView grid;
    private DockDecoration dockDecoration;
    private FrameLayout root;
    private View header;
    private ImageView wallpaperView;
    private ImageView wallpaperBlurView;
    private View allAppsScrim;
    private GlassPanelView allAppsPanel;
    private View suggestStrip;
    private LinearLayout suggestRow;
    private TextView suggestContext;
    private GlassView glass;
    private TextView heroName;
    private View ambient;
    private GradientDrawable ambientGlow;
    private int ambientColor;
    private boolean heroShown;
    private TextView greeting;
    private TextView btnWifi;
    private SpringAnimation springX, springY;
    private float glassTargetX, glassTargetY;
    private boolean glassShown;
    /** True while focus is on the app list below the dock. */
    private boolean allAppsMode;
    /** True while apps are being rearranged. */
    private boolean arranging;
    /** What the blur / header state on screen currently reflects (see refreshChrome). */
    private boolean overlayApplied;
    /** True while the all-apps extras (glass panel, Suggested row) are shown. */
    private boolean allUiApplied;
    /** True from the start of the "back to dock" animation until it ends. */
    private boolean snapActive;
    private final List<String> suggestKeys = new ArrayList<>();
    /** Align the focused row to the top of the screen on the next focus, even if we were already in the app list. */
    private boolean alignNext;
    private List<String> arrangeBackup;
    // Static so they survive the activity being destroyed while another app is open (low-memory TVs,
    // or Developer options → "Don't keep activities"); they only reset when the process dies.
    private static int lastFocused = 0;
    /** App of the last focused tile, so focus follows it when the app list changes. */
    private static String lastFocusedKey;
    private float density;

    private final Handler handler = new Handler(Looper.getMainLooper());
    /** Refreshes the network pill when the connection changes: at boot Wi-Fi often connects after Home is shown. */
    private final ConnectivityManager.NetworkCallback networkCallback = new ConnectivityManager.NetworkCallback() {
        @Override
        public void onAvailable(@NonNull Network network) {
            handler.post(MainActivity.this::updateWifiLabel);
        }

        @Override
        public void onLost(@NonNull Network network) {
            handler.post(MainActivity.this::updateWifiLabel);
        }
    };
    private final Runnable minuteTick = new Runnable() {
        @Override
        public void run() {
            updateGreeting();
            updateWifiLabel();
            updateSuggestions();
            handler.postDelayed(this, MINUTE_MS);
        }
    };
    /** In dock mode, hide everything below the dock band (the second row would otherwise peek in). */
    private final Runnable clipRunnable = new Runnable() {
        @Override
        public void run() {
            if (grid == null || grid.getHeight() == 0) return;
            int w = grid.getWidth(), h = grid.getHeight();
            if (arranging) {
                grid.setClipBounds(null);
            } else if (allUiApplied) {
                // All apps: tiles scroll inside the glass panel.
                grid.setClipBounds(new Rect(0, panelTopPx(), w, h - (int) (PANEL_BOTTOM_DP * density)));
            } else if (overlayApplied) {
                grid.setClipBounds(null);
            } else {
                grid.setClipBounds(new Rect(0, 0, w, h - (int) (40 * density)));   // dock: hide the rows below it
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        density = getResources().getDisplayMetrics().density;
        COLUMNS = Math.max(1, getResources().getInteger(R.integer.columns));
        liveTvPkg = getString(R.string.live_tv_package).trim();

        root = findViewById(R.id.root);
        header = findViewById(R.id.header);
        wallpaperView = findViewById(R.id.wallpaper);
        wallpaperBlurView = findViewById(R.id.wallpaperBlur);
        allAppsScrim = findViewById(R.id.allAppsScrim);
        allAppsPanel = findViewById(R.id.allAppsPanel);
        suggestStrip = findViewById(R.id.suggestStrip);
        suggestRow = findViewById(R.id.suggestRow);
        suggestContext = findViewById(R.id.suggestContext);
        glass = findViewById(R.id.glass);
        heroName = findViewById(R.id.heroName);
        ambient = findViewById(R.id.ambient);
        ambientGlow = new GradientDrawable();
        ambientGlow.setGradientType(GradientDrawable.RADIAL_GRADIENT);
        ambientGlow.setGradientCenter(0.32f, 0.42f);
        ambient.setBackground(ambientGlow);
        ambient.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) ->
                ambientGlow.setGradientRadius((r - l) * 0.62f));
        greeting = findViewById(R.id.greeting);
        btnWifi = findViewById(R.id.btnWifi);

        grid = findViewById(R.id.grid);
        grid.setLayoutManager(new GridLayoutManager(this, COLUMNS) {
            // The grid scrolls itself (scrollGridBy). RecyclerView's built-in "scroll the focused tile into
            // view" works from the padding, which is deliberately huge here, and made the rows jump around.
            @Override
            public boolean requestChildRectangleOnScreen(@NonNull RecyclerView parent, @NonNull View child,
                                                         @NonNull Rect rect, boolean immediate,
                                                         boolean focusedChildVisible) {
                return false;
            }

            @Override
            public boolean requestChildRectangleOnScreen(@NonNull RecyclerView parent, @NonNull View child,
                                                         @NonNull Rect rect, boolean immediate) {
                return false;
            }

            @Override
            protected void calculateExtraLayoutSpace(@NonNull RecyclerView.State state, @NonNull int[] extraLayoutSpace) {
                // Keep the rows just above and below the screen laid out, so focus can jump to them at once.
                extraLayoutSpace[0] = getHeight() / 2;
                extraLayoutSpace[1] = getHeight();
            }
        });
        grid.setHasFixedSize(true);
        grid.setItemAnimator(null);
        dockDecoration = new DockDecoration(COLUMNS, density);
        grid.addItemDecoration(dockDecoration);
        grid.setLayoutAnimation(new LayoutAnimationController(
                AnimationUtils.loadAnimation(this, R.anim.tile_enter), 0.06f));
        adapter = new AppAdapter(this, this, COLUMNS);
        grid.setAdapter(adapter);

        // tvOS layout: the dock row sits at the bottom of the screen; the app list is below it, out of sight.
        grid.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            updateGridPadding();
            if (!overlayApplied) clipRunnable.run();
        });

        // Keep the glass glued to its tile while the grid scrolls.
        grid.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView rv, int dx, int dy) {
                if (!glassShown) return;
                glassTargetY -= dy;
                if (springY.isRunning()) springY.animateToFinalPosition(glassTargetY);
                else glass.setTranslationY(glassTargetY);
            }
        });

        // Liquid feel: soft springs on the highlight's position.
        springX = new SpringAnimation(glass, DynamicAnimation.TRANSLATION_X);
        springY = new SpringAnimation(glass, DynamicAnimation.TRANSLATION_Y);
        for (SpringAnimation s : new SpringAnimation[]{springX, springY}) {
            s.setSpring(new SpringForce().setStiffness(260f).setDampingRatio(0.58f));
        }

        View btnInputs = findViewById(R.id.btnInputs);
        View btnSettings = findViewById(R.id.btnSettings);
        btnInputs.setOnClickListener(v -> showInputs());
        btnWifi.setOnClickListener(v -> open(new Intent(Settings.ACTION_WIFI_SETTINGS)));
        btnSettings.setOnClickListener(v -> showLauncherSettings());

        // Keep focus on the top bar at its ends and at the top: Android's focus finder would otherwise
        // jump to a tile far below when there is nothing in that direction.
        View.OnKeyListener pillKeys = (v, keyCode, event) -> {
            if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
            if (keyCode == KeyEvent.KEYCODE_DPAD_UP) return true;
            if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT && v.getId() == R.id.btnInputs) return true;
            return keyCode == KeyEvent.KEYCODE_DPAD_RIGHT && v.getId() == R.id.btnSettings;
        };
        btnInputs.setOnKeyListener(pillKeys);
        btnWifi.setOnKeyListener(pillKeys);
        btnSettings.setOnKeyListener(pillKeys);

        // Hide the glass when focus leaves the tiles.
        getWindow().getDecorView().getViewTreeObserver().addOnGlobalFocusChangeListener((oldF, newF) -> {
            boolean onTile = newF != null && newF.getId() == R.id.card;
            if (!onTile) {
                hideGlass();
                if (allAppsMode) {
                    allAppsMode = false;
                    refreshChrome();
                }
            }
        });

        ConnectivityManager cm = getSystemService(ConnectivityManager.class);
        if (cm != null) cm.registerDefaultNetworkCallback(networkCallback);

        applyAllLayout();
        restoreWallpaper();   // after the grid exists: the dock band uses the blurred wallpaper too
    }

    @Override
    protected void onResume() {
        super.onResume();
        glassShown = false;
        glass.setAlpha(0f);
        heroShown = false;
        heroName.setAlpha(0f);
        ambient.animate().cancel();
        ambient.setAlpha(0f);
        // Start in the dock state; focusing a tile in the app list switches to the blurred state again.
        allAppsMode = false;
        arranging = false;
        overlayApplied = false;
        allUiApplied = false;
        snapActive = false;
        allAppsPanel.animate().cancel();
        suggestStrip.animate().cancel();
        allAppsPanel.setAlpha(0f);
        suggestStrip.setAlpha(0f);
        suggestStrip.setVisibility(View.INVISIBLE);
        wallpaperBlurView.animate().cancel();
        allAppsScrim.animate().cancel();
        header.animate().cancel();
        wallpaperBlurView.setAlpha(0f);
        allAppsScrim.setAlpha(0f);
        header.setAlpha(1f);
        header.setVisibility(View.VISIBLE);
        grid.animate().cancel();
        grid.setAlpha(1f);
        grid.setTranslationY(0f);
        updateGridPadding();
        clipRunnable.run();
        // Read these before reload(): rebinding can move focus to the first tile and overwrite them.
        final String returnKey = lastFocusedKey;
        final int returnSlot = lastFocused;
        adapter.reload();
        suggestKeys.clear();
        updateSuggestions();
        grid.scheduleLayoutAnimation();
        updateGreeting();
        updateWifiLabel();
        handler.postDelayed(minuteTick, MINUTE_MS);

        // Return to the app we left from, or the same slot if that app is gone. Always refocus: after
        // reload() the view that holds focus may no longer show the app it had.
        grid.post(() -> {
            int pos = adapter.positionOf(returnKey);
            focusTile(pos >= 0 ? pos : returnSlot);
        });
    }

    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacks(minuteTick);
        if (arranging) finishArrange(false);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_WALLPAPER || resultCode != RESULT_OK || data == null) return;
        Uri uri = data.getData();
        if (uri == null) return;
        try {
            try {
                getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } catch (SecurityException ignored) {
                // Some TV document providers do not offer persistable grants; still use it this session.
            }
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(WALLPAPER_URI, uri.toString()).apply();
            applyWallpaper(uri);
        } catch (Exception e) {
            Toast.makeText(this, R.string.wallpaper_failed, Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        handler.removeCallbacks(clipRunnable);
        ConnectivityManager cm = getSystemService(ConnectivityManager.class);
        if (cm != null) cm.unregisterNetworkCallback(networkCallback);
    }

    @Override
    public void onBackPressed() {
        if (arranging) {
            finishArrange(false);
            return;
        }
        // Home screen: Back goes nowhere.
    }

    // ---- grid layout and modes -------------------------------------------------------------------

    /**
     * Dock mode parks the dock row at the bottom (big top padding) and leaves enough bottom padding to scroll
     * the last row up to the top; arrange mode uses a small top padding so everything is visible at once.
     */
    private void updateGridPadding() {
        int h = grid.getHeight();
        if (h == 0) return;
        int top, bottom;
        if (arranging) {
            top = (int) (ARRANGE_TOP_DP * density);
            bottom = (int) (40 * density);
        } else {
            int dockRow = (int) ((145 + 6 + 12) * density);   // item height plus DockDecoration offsets
            top = Math.max(0, h - dockRow - (int) (30 * density));
            bottom = Math.max((int) (40 * density), h - (int) (145 * density) - allTopPx());
        }
        if (grid.getPaddingTop() != top || grid.getPaddingBottom() != bottom) {
            grid.setPadding(grid.getPaddingLeft(), top, grid.getPaddingRight(), bottom);
        }
    }

    /**
     * Three looks: the dock state (clock, buttons, dock row), the all-apps state (background blurred and
     * lighter, a glass panel with the app list, the Suggested row on top, everything else hidden) and the
     * arrange state (blurred, everything visible). This brings the screen in line with allAppsMode/arranging.
     */
    private void refreshChrome() {
        boolean overlay = allAppsMode || arranging;
        boolean allUi = allAppsMode && !arranging;
        boolean overlayChanged = overlay != overlayApplied;
        boolean allUiChanged = allUi != allUiApplied;
        if (!overlayChanged && !allUiChanged) return;
        overlayApplied = overlay;
        allUiApplied = allUi;
        if (overlayChanged) {
            wallpaperBlurView.animate().alpha(overlay ? 1f : 0f).setDuration(350).start();
            allAppsScrim.animate().alpha(overlay ? 1f : 0f).setDuration(350).start();
            setHeaderShown(!overlay);
        }
        if (allUiChanged) setAllUiShown(allUi);
        handler.removeCallbacks(clipRunnable);
        if (overlay) clipRunnable.run();
        else handler.postDelayed(clipRunnable, 380);   // after the app list has slid away
    }

    private void setAllUiShown(boolean show) {
        allAppsPanel.animate().cancel();
        suggestStrip.animate().cancel();
        if (show) {
            suggestStrip.setVisibility(suggestKeys.isEmpty() ? View.INVISIBLE : View.VISIBLE);
            allAppsPanel.animate().alpha(1f).setDuration(350).start();
            suggestStrip.animate().alpha(1f).setDuration(350).start();
        } else {
            allAppsPanel.animate().alpha(0f).setDuration(200).start();
            suggestStrip.animate().alpha(0f).setDuration(200).withEndAction(() -> {
                if (!allUiApplied) suggestStrip.setVisibility(View.INVISIBLE);
            }).start();
        }
    }

    // ---- all apps: glass panel and the Suggested row ---------------------------------------------

    private int panelTopPx() {
        return (int) ((suggestKeys.isEmpty() ? PANEL_TOP_PLAIN_DP : PANEL_TOP_STRIP_DP) * density);
    }

    /** Where the first row of apps sits when the list opens: just inside the glass panel. */
    private int allTopPx() {
        return panelTopPx() + (int) (ROW_INSET_DP * density);
    }

    /** Moves the glass panel (and the grid's scroll range) to match whether the Suggested row is shown. */
    private void applyAllLayout() {
        ViewGroup.MarginLayoutParams lp = (ViewGroup.MarginLayoutParams) allAppsPanel.getLayoutParams();
        int top = panelTopPx();
        if (lp.topMargin != top) {
            lp.topMargin = top;
            allAppsPanel.setLayoutParams(lp);
        }
        updateGridPadding();
        if (overlayApplied) clipRunnable.run();
    }

    private int suggestCount() {
        return suggestKeys.size();
    }

    /** Recomputes what to suggest right now; rebuilds the row only when the picks changed. */
    private void updateSuggestions() {
        if (adapter == null || suggestRow == null) return;
        List<SmartSuggestions.Pick> picks = adapter.suggestions(COLUMNS);
        suggestContext.setText(adapter.suggestionContext());
        List<String> keys = new ArrayList<>();
        for (SmartSuggestions.Pick p : picks) keys.add(p.entry.key);
        if (keys.equals(suggestKeys)) return;
        if (suggestRow.hasFocus()) return;            // never pull a tile away from under the cursor
        suggestKeys.clear();
        suggestKeys.addAll(keys);
        suggestRow.removeAllViews();
        int index = 0;
        for (SmartSuggestions.Pick p : picks) {
            suggestRow.addView(buildSuggestTile(p.entry, index, picks.size()),
                    new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            index++;
        }
        for (; index < COLUMNS; index++) {            // keep the tiles on the same columns as the grid
            suggestRow.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1f));
        }
        if (allUiApplied) suggestStrip.setVisibility(keys.isEmpty() ? View.INVISIBLE : View.VISIBLE);
        applyAllLayout();
    }

    private View buildSuggestTile(final AppAdapter.Entry entry, final int index, final int count) {
        View item = getLayoutInflater().inflate(R.layout.item_app, suggestRow, false);
        final View card = item.findViewById(R.id.card);
        final TextView name = item.findViewById(R.id.name);
        ImageView banner = item.findViewById(R.id.banner);
        ImageView icon = item.findViewById(R.id.icon);
        TextView label = item.findViewById(R.id.label);
        AppAdapter.applyVisual(banner, icon, label, entry);
        name.setText(entry.label);
        final float radius = 16f * density;
        card.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), radius);
            }
        });
        card.setClipToOutline(true);
        card.setOnClickListener(v -> adapter.launchEntry(entry));
        card.setOnFocusChangeListener((v, focused) -> {
            float sc = focused ? AppAdapter.FOCUS_SCALE : 1.0f;
            v.animate().scaleX(sc).scaleY(sc)
                    .setDuration(focused ? 260 : 180)
                    .setInterpolator(new OvershootInterpolator(focused ? 1.6f : 0f))
                    .start();
            name.animate().alpha(focused ? 1f : 0f)
                    .translationY(focused ? density * 4f : -density * 6f)
                    .setDuration(220).start();
            if (focused) onSuggestionFocused(v);
        });
        card.setOnKeyListener((v, keyCode, event) -> {
            if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
            int total = adapter.getItemCount();
            if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
                if (index > 0) focusSuggestion(index - 1);
                return true;
            }
            if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                if (index < count - 1) focusSuggestion(index + 1);
                return true;
            }
            if (keyCode == KeyEvent.KEYCODE_DPAD_UP) {
                focusTile(Math.min(index, Math.min(COLUMNS, total) - 1));      // back up to the dock
                return true;
            }
            if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                if (total > COLUMNS) focusTile(Math.min(COLUMNS + index, total - 1));
                return true;
            }
            return false;
        });
        return item;
    }

    private void focusSuggestion(int index) {
        if (index < 0 || index >= suggestRow.getChildCount()) return;
        View card = suggestRow.getChildAt(index).findViewById(R.id.card);
        if (card != null) card.requestFocus();
    }

    private void onSuggestionFocused(View card) {
        if (snapActive) endSnap();
        moveGlassTo(card, true);
        hideHero();
        if (!allAppsMode && !arranging) enterAllApps();
    }

    /** Switches to the all-apps state with the first row of apps parked just inside the glass panel. */
    private void enterAllApps() {
        if (allAppsMode || arranging) return;
        updateSuggestions();
        allAppsMode = true;
        refreshChrome();
        if (adapter.getItemCount() > COLUMNS) {
            RecyclerView.ViewHolder vh = grid.findViewHolderForAdapterPosition(COLUMNS);
            if (vh != null) scrollGridBy(vh.itemView.getTop() - allTopPx());
        }
    }

    private void setHeaderShown(boolean show) {
        header.animate().cancel();
        if (show) {
            header.setVisibility(View.VISIBLE);
            header.animate().alpha(1f).setDuration(300).start();
        } else {
            header.animate().alpha(0f).setDuration(180).withEndAction(() -> {
                if (overlayApplied) header.setVisibility(View.INVISIBLE);
            }).start();
        }
    }

    private void scrollGridBy(int dy) {
        if (dy == 0) return;
        int duration = Math.min(380, 140 + Math.abs(dy) / 3);
        grid.smoothScrollBy(0, dy, new DecelerateInterpolator(1.4f), duration);
    }

    /**
     * Coming back to the dock from the app list. The list and the glass fade out, the grid jumps to the dock
     * position while invisible, then the dock rises into place with the glass. The first row of apps never
     * crosses the screen.
     */
    private void snapToDock(final int dy) {
        snapActive = true;
        grid.stopScroll();
        grid.animate().cancel();
        glass.animate().cancel();
        springX.cancel();
        springY.cancel();
        glass.animate().alpha(0f).setDuration(110).start();
        grid.animate().alpha(0f).setDuration(110).withEndAction(() -> {
            if (!snapActive) return;
            final float lift = 56 * density;
            grid.scrollBy(0, dy);                     // the scroll listener also moves glassTargetY
            handler.removeCallbacks(clipRunnable);
            clipRunnable.run();                       // hide the rows below the dock right away
            grid.setTranslationY(lift);
            glass.setTranslationX(glassTargetX);
            glass.setTranslationY(glassTargetY + lift);
            DecelerateInterpolator rise = new DecelerateInterpolator(1.8f);
            grid.animate().alpha(1f).translationY(0f).setDuration(340).setInterpolator(rise)
                    .withEndAction(() -> snapActive = false).start();
            glass.animate().alpha(1f).translationY(glassTargetY).setDuration(340).setInterpolator(rise).start();
        }).start();
    }

    /** Stops the back-to-dock animation early (another key arrived) and puts everything back in place. */
    private void endSnap() {
        snapActive = false;
        grid.animate().cancel();
        glass.animate().cancel();
        grid.setAlpha(1f);
        grid.setTranslationY(0f);
        glass.setAlpha(1f);
    }

    /** Scrolls just enough so the whole tile, with its glass and name, is on screen (inside the panel). */
    private void ensureVisible(View item) {
        int topLimit, bottomLimit;
        if (arranging) {
            topLimit = (int) (12 * density);
            bottomLimit = grid.getHeight() - (int) (16 * density);
        } else {
            topLimit = panelTopPx() + (int) (10 * density);
            bottomLimit = grid.getHeight() - (int) ((PANEL_BOTTOM_DP + 8) * density);
        }
        int dy = 0;
        if (item.getTop() < topLimit) dy = item.getTop() - topLimit;
        else if (item.getBottom() > bottomLimit) dy = item.getBottom() - bottomLimit;
        scrollGridBy(dy);
    }

    // ---- glass highlight -------------------------------------------------------------------------

    /** Puts the glass over a tile. Returns the tile's rect in root coordinates (layout position, no scale). */
    private Rect moveGlassTo(View card, boolean animate) {
        Rect r = new Rect(0, 0, card.getWidth(), card.getHeight());
        root.offsetDescendantRectToMyCoords(card, r);
        float pad = 6f * density;        // matches GlassView's glow inset
        float w = card.getWidth() * AppAdapter.FOCUS_SCALE + 2 * pad;
        float h = card.getHeight() * AppAdapter.FOCUS_SCALE + 2 * pad;
        float x = r.exactCenterX() - w / 2f;
        float y = r.exactCenterY() - h / 2f;

        ViewGroup.LayoutParams lp = glass.getLayoutParams();
        if (lp.width != (int) w || lp.height != (int) h) {
            lp.width = (int) w;
            lp.height = (int) h;
            glass.setLayoutParams(lp);
            glass.setRadius(16f * density * AppAdapter.FOCUS_SCALE + pad);
        }
        glassTargetX = x;
        glassTargetY = y;
        if (!animate) return r;
        if (!glassShown) {
            springX.cancel();
            springY.cancel();
            glass.setTranslationX(x);
            glass.setTranslationY(y);
            glass.animate().alpha(1f).setDuration(180).start();
            glassShown = true;
        } else {
            springX.animateToFinalPosition(x);
            springY.animateToFinalPosition(y);
        }
        return r;
    }

    @Override
    public void onTileFocused(int position, View card) {
        if (snapActive) endSnap();
        lastFocused = position;
        lastFocusedKey = adapter.keyAt(position);
        boolean leavingAll = !arranging && allAppsMode && position < COLUMNS;
        Rect r = moveGlassTo(card, !leavingAll);      // leaving the list: snapToDock moves the glass itself
        View item = (View) card.getParent();

        if (arranging) {
            hideHero();
            ensureVisible(item);
            return;
        }

        boolean entering = alignNext || (!allAppsMode && position >= COLUMNS);
        alignNext = false;
        if (entering) updateSuggestions();
        allAppsMode = position >= COLUMNS;
        refreshChrome();
        if (position < COLUMNS) {
            // Dock: park it at the bottom of the screen and show the top shelf for this app.
            int decoratedTop = item.getTop() - (int) (6 * density);
            int dy = decoratedTop - grid.getPaddingTop();
            if (leavingAll) snapToDock(dy);
            else scrollGridBy(dy);
            showHero(position, r.top - dy);
        } else {
            hideHero();
            if (entering) {
                // The list opens with its first row just inside the glass panel.
                scrollGridBy(item.getTop() - allTopPx());
            } else {
                ensureVisible(item);
            }
        }
    }

    // ---- top shelf -------------------------------------------------------------------------------

    private void showHero(int position, float dockTopInRoot) {
        String label = adapter.labelAt(position);
        float y = dockTopInRoot - 14 * density - 26 * density - heroName.getHeight();
        boolean changed = !label.contentEquals(heroName.getText());

        if (!heroShown) {
            heroName.setText(label);
            heroName.setY(y);
            heroName.setTranslationX(0f);
            heroName.animate().alpha(1f).setDuration(260).start();
            setAmbient(adapter.ambientColorAt(position));
            ambient.animate().alpha(1f).setDuration(600).start();
            heroShown = true;
        } else if (changed) {
            heroName.animate().alpha(0f).translationX(-12 * density).setDuration(120).withEndAction(() -> {
                heroName.setText(label);
                heroName.setY(y);
                heroName.setTranslationX(12 * density);
                heroName.animate().alpha(1f).translationX(0f).setDuration(200).start();
            }).start();
            final int color = adapter.ambientColorAt(position);
            if (color != ambientColor) {
                ambient.animate().alpha(0.35f).setDuration(200).withEndAction(() -> {
                    setAmbient(color);
                    ambient.animate().alpha(1f).setDuration(500).start();
                }).start();
            }
        } else {
            heroName.setY(y);
        }
    }

    /** Soft radial glow behind the dock, darker than the app's own colour so it stays easy on the eyes. */
    private void setAmbient(int color) {
        ambientColor = color;
        int c = darken(color) & 0x00FFFFFF;
        ambientGlow.setColors(new int[]{0x48000000 | c, 0x1C000000 | c, 0x00000000 | c});
    }

    private static int darken(int color) {
        return Color.rgb((int) (Color.red(color) * 0.8f), (int) (Color.green(color) * 0.8f),
                (int) (Color.blue(color) * 0.8f));
    }

    private void hideHero() {
        if (!heroShown) return;
        heroShown = false;
        heroName.animate().alpha(0f).setDuration(160).start();
        ambient.animate().alpha(0f).setDuration(300).start();
    }

    private void hideGlass() {
        if (!glassShown) return;
        glassShown = false;
        glass.animate().alpha(0f).setDuration(160).start();
    }

    // ---- navigation ------------------------------------------------------------------------------

    @Override
    public boolean onTileVerticalKey(int position, int keyCode) {
        int count = adapter.getItemCount();
        if (arranging) {
            int target = keyCode == KeyEvent.KEYCODE_DPAD_UP ? position - COLUMNS : position + COLUMNS;
            if (target < 0) return true;
            if (target >= count) {
                int lastRowStart = ((count - 1) / COLUMNS) * COLUMNS;
                if (position >= lastRowStart) return true;
                target = count - 1;
            }
            moveGrabbed(position, target);
            return true;
        }
        int target;
        if (keyCode == KeyEvent.KEYCODE_DPAD_UP) {
            if (position < COLUMNS) return false;          // dock row: let focus move to the top bar
            if (position < 2 * COLUMNS && suggestCount() > 0) {
                focusSuggestion(Math.min(position - COLUMNS, suggestCount() - 1));   // first row -> Suggested row
                return true;
            }
            target = position - COLUMNS;
        } else {
            if (position < COLUMNS && count > COLUMNS) {
                updateSuggestions();
                if (suggestCount() > 0) {                  // dock -> Suggested row first
                    enterAllApps();
                    focusSuggestion(Math.min(position, suggestCount() - 1));
                    return true;
                }
            }
            int lastRowStart = ((count - 1) / COLUMNS) * COLUMNS;
            if (position >= lastRowStart) return true;     // already on the last row: stay put
            target = Math.min(position + COLUMNS, count - 1);
        }
        focusTile(target);
        return true;
    }

    @Override
    public boolean onTileHorizontalKey(int position, int keyCode) {
        if (!arranging) return false;
        int target = position + (keyCode == KeyEvent.KEYCODE_DPAD_LEFT ? -1 : 1);
        if (target < 0 || target >= adapter.getItemCount()) return true;
        moveGrabbed(position, target);
        return true;
    }

    @Override
    public void onTileLongPressed(int position) {
        beginArrange(position);
    }

    @Override
    public boolean onTileClick(int position) {
        if (!arranging) return false;
        finishArrange(true);
        return true;
    }

    private void moveGrabbed(int from, int to) {
        adapter.moveItem(from, to);
        final String key = adapter.keyAt(to);
        grid.post(() -> focusTile(adapter.positionOf(key)));
    }

    private void beginArrange(int position) {
        if (arranging || adapter.getItemCount() == 0) return;
        arranging = true;
        arrangeBackup = adapter.orderSnapshot();
        Toast.makeText(this, R.string.arrange_help, Toast.LENGTH_LONG).show();
        hideHero();
        updateGridPadding();
        refreshChrome();
        final int pos = Math.max(0, Math.min(position, adapter.getItemCount() - 1));
        grid.scrollToPosition(pos);
        grid.post(() -> focusTile(pos));
    }

    private void finishArrange(boolean save) {
        if (!arranging) return;
        arranging = false;
        if (save) {
            adapter.saveOrder();
            Toast.makeText(this, R.string.order_saved, Toast.LENGTH_SHORT).show();
        } else {
            adapter.restoreOrder(arrangeBackup);
            Toast.makeText(this, R.string.order_cancelled, Toast.LENGTH_SHORT).show();
        }
        arrangeBackup = null;
        int pos = adapter.positionOf(lastFocusedKey);
        if (pos < 0) pos = Math.max(0, Math.min(lastFocused, adapter.getItemCount() - 1));
        final int target = pos;
        // Go back to the state that matches where the app ended up, without a flicker through the dock state.
        allAppsMode = target >= COLUMNS;
        alignNext = allAppsMode;
        updateGridPadding();
        refreshChrome();
        grid.scrollToPosition(target);
        grid.post(() -> focusTile(target));
    }

    private void showLauncherSettings() {
        View anchor = findViewById(R.id.btnSettings);
        View panel = getLayoutInflater().inflate(R.layout.popup_apps, root, false);
        panel.findViewById(R.id.title).setVisibility(View.GONE);
        LinearLayout rows = panel.findViewById(R.id.rows);
        int minWidth = (int) (230 * density);
        final PopupWindow popup = newDropdown(panel, minWidth);
        addInputRow(rows, popup, R.drawable.ic_wallpaper, getString(R.string.choose_wallpaper), -1, this::chooseWallpaper);
        addInputRow(rows, popup, R.drawable.ic_reset, getString(R.string.reset_wallpaper), -1, this::resetWallpaper);
        addInputRow(rows, popup, R.drawable.ic_arrange, getString(R.string.arrange_apps), -1,
                () -> beginArrange(lastFocused));
        addInputRow(rows, popup, R.drawable.ic_hide, getString(R.string.hide_apps), -1, this::showHideApps);
        addInputRow(rows, popup, R.drawable.ic_palette, getString(R.string.icon_pack), -1, this::showIconPacks);
        addInputRow(rows, popup, R.drawable.ic_shape, getString(R.string.icon_shape), -1, this::showIconShapes);
        addInputRow(rows, popup, R.drawable.ic_saver, getString(R.string.screen_saver), -1,
                () -> startActivity(new Intent(this, ScreensaverActivity.class)));
        addSeparator(rows);
        addInputRow(rows, popup, R.drawable.ic_settings, getString(R.string.system_settings), -1,
                () -> open(new Intent(Settings.ACTION_SETTINGS)));
        fitScroll(panel);
        showDropdown(popup, panel, anchor, minWidth, true);
    }

    /** Tall menus scroll inside the popup instead of running off the screen. */
    private void fitScroll(View panel) {
        LinearLayout rows = panel.findViewById(R.id.rows);
        View scroll = panel.findViewById(R.id.scroll);
        rows.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
        ViewGroup.LayoutParams slp = scroll.getLayoutParams();
        slp.height = Math.min(rows.getMeasuredHeight(), (int) (root.getHeight() * 0.6f));
        scroll.setLayoutParams(slp);
    }

    private void showIconPacks() {
        View anchor = findViewById(R.id.btnSettings);
        View panel = getLayoutInflater().inflate(R.layout.popup_apps, root, false);
        LinearLayout rows = panel.findViewById(R.id.rows);
        int minWidth = (int) (300 * density);
        final PopupWindow popup = newDropdown(panel, minWidth);
        List<IconPack.Info> packs = adapter.iconPacks();
        ((TextView) panel.findViewById(R.id.title)).setText(packs.isEmpty() ? R.string.none_icon_packs : R.string.no_icon_packs);
        String current = adapter.iconPackPackage();
        addChoiceRow(rows, popup, null, R.drawable.ic_reset, getString(R.string.icon_pack_default), current.isEmpty(),
                () -> applyIconSettings(() -> adapter.setIconPack("")));
        for (IconPack.Info info : packs) {
            final String pkg = info.pkg;
            addChoiceRow(rows, popup, info.icon, 0, info.label, pkg.equals(current),
                    () -> applyIconSettings(() -> adapter.setIconPack(pkg)));
        }
        fitScroll(panel);
        showDropdown(popup, panel, anchor, minWidth, true);
    }

    private void showIconShapes() {
        View anchor = findViewById(R.id.btnSettings);
        View panel = getLayoutInflater().inflate(R.layout.popup_apps, root, false);
        LinearLayout rows = panel.findViewById(R.id.rows);
        int minWidth = (int) (300 * density);
        final PopupWindow popup = newDropdown(panel, minWidth);
        ((TextView) panel.findViewById(R.id.title)).setText(R.string.icon_shape_title);
        int[] shapes = {IconShapes.AUTO, IconShapes.CIRCLE, IconShapes.SQUIRCLE, IconShapes.ROUNDED, IconShapes.SQUARE};
        int[] names = {R.string.shape_auto, R.string.shape_circle, R.string.shape_squircle, R.string.shape_rounded,
                R.string.shape_square};
        Drawable sample = null;
        try {
            sample = getDrawable(R.mipmap.ic_launcher);
        } catch (Exception ignored) {
        }
        for (int i = 0; i < shapes.length; i++) {
            final int shape = shapes[i];
            Drawable preview = shape == IconShapes.AUTO ? null : IconShapes.shape(this, sample, shape, 96);
            addChoiceRow(rows, popup, preview, R.drawable.ic_shape, getString(names[i]), adapter.iconShape() == shape,
                    () -> applyIconSettings(() -> adapter.setIconShape(shape)));
        }
        fitScroll(panel);
        showDropdown(popup, panel, anchor, minWidth, true);
    }

    /** Applies an icon pack / shape change: rebuilds every tile and the Suggested row. */
    private void applyIconSettings(Runnable change) {
        change.run();
        adapter.reload();
        suggestKeys.clear();
        updateSuggestions();
    }

    /** A menu row with a "Selected" marker, and either an app-style icon (drawable) or a vector (iconRes). */
    private void addChoiceRow(LinearLayout rows, PopupWindow popup, Drawable iconDrawable, int iconRes, String title,
                              boolean selected, Runnable action) {
        View row = getLayoutInflater().inflate(R.layout.item_input, rows, false);
        ImageView icon = row.findViewById(R.id.icon);
        if (iconDrawable != null) {
            icon.setImageTintList(null);                       // keep the icon's own colours
            icon.setImageDrawable(iconDrawable);
            ViewGroup.LayoutParams ilp = icon.getLayoutParams();
            ilp.width = (int) (26 * density);
            ilp.height = (int) (26 * density);
        } else {
            icon.setImageResource(iconRes);
        }
        ((TextView) row.findViewById(R.id.title)).setText(title);
        TextView status = row.findViewById(R.id.status);
        View dot = row.findViewById(R.id.dot);
        if (selected) {
            status.setText(R.string.selected);
            dot.setVisibility(View.VISIBLE);
        } else {
            status.setVisibility(View.GONE);
            dot.setVisibility(View.GONE);
        }
        row.setOnClickListener(v -> {
            popup.dismiss();
            action.run();
        });
        rows.addView(row);
    }

    /** Scrollable list of every app; click one to hide it from (or show it on) the home screen. */
    private void showHideApps() {
        View anchor = findViewById(R.id.btnSettings);
        View panel = getLayoutInflater().inflate(R.layout.popup_apps, root, false);
        LinearLayout rows = panel.findViewById(R.id.rows);
        View scroll = panel.findViewById(R.id.scroll);
        int minWidth = (int) (320 * density);
        final PopupWindow popup = newDropdown(panel, minWidth);
        final boolean[] changed = {false};
        for (AppAdapter.AppRef app : adapter.allApps()) {
            final String pkg = app.pkg;
            final View row = getLayoutInflater().inflate(R.layout.item_input, rows, false);
            ImageView icon = row.findViewById(R.id.icon);
            icon.setImageTintList(null);                       // keep the app's own colours
            icon.setImageDrawable(app.icon);
            ViewGroup.LayoutParams ilp = icon.getLayoutParams();
            ilp.width = (int) (26 * density);
            ilp.height = (int) (26 * density);
            ((TextView) row.findViewById(R.id.title)).setText(app.label);
            updateHideRow(row, adapter.isUserHidden(pkg));
            row.setOnClickListener(v -> {
                boolean nowHidden = !adapter.isUserHidden(pkg);
                adapter.setUserHidden(pkg, nowHidden);
                changed[0] = true;
                updateHideRow(row, nowHidden);
            });
            rows.addView(row);
        }
        if (rows.getChildCount() == 0) {
            Toast.makeText(this, R.string.not_available, Toast.LENGTH_SHORT).show();
            return;
        }
        // Tall lists scroll inside the popup instead of running off the screen.
        rows.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
        ViewGroup.LayoutParams slp = scroll.getLayoutParams();
        slp.height = Math.min(rows.getMeasuredHeight(), (int) (root.getHeight() * 0.6f));
        scroll.setLayoutParams(slp);
        popup.setOnDismissListener(() -> {
            if (changed[0]) {
                adapter.reload();
                suggestKeys.clear();
                updateSuggestions();
            }
        });
        showDropdown(popup, panel, anchor, minWidth, true);
    }

    private void updateHideRow(View row, boolean hidden) {
        TextView status = row.findViewById(R.id.status);
        status.setText(hidden ? R.string.app_hidden : R.string.app_shown);
        row.findViewById(R.id.dot).setVisibility(hidden ? View.GONE : View.VISIBLE);
        row.findViewById(R.id.title).setAlpha(hidden ? 0.45f : 1f);
        row.findViewById(R.id.icon).setAlpha(hidden ? 0.45f : 1f);
    }

    private void chooseWallpaper() {
        Intent pick = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        pick.addCategory(Intent.CATEGORY_OPENABLE);
        pick.setType("image/*");
        pick.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        try {
            startActivityForResult(pick, REQUEST_WALLPAPER);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, R.string.not_available, Toast.LENGTH_SHORT).show();
        }
    }

    private void resetWallpaper() {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().remove(WALLPAPER_URI).apply();
        clearWallpaperViews();
    }

    private void clearWallpaperViews() {
        wallpaperView.setImageDrawable(null);
        wallpaperView.setVisibility(View.GONE);
        wallpaperBlurView.setImageDrawable(null);
        wallpaperBlurView.setVisibility(View.GONE);
        if (dockDecoration != null) dockDecoration.setBackdrop(null);
        if (allAppsPanel != null) allAppsPanel.setBackdrop(null);
        if (grid != null) grid.invalidate();
    }

    private void restoreWallpaper() {
        String savedUri = getSharedPreferences(PREFS, MODE_PRIVATE).getString(WALLPAPER_URI, null);
        if (savedUri == null) return;
        try {
            applyWallpaper(Uri.parse(savedUri));
        } catch (Exception ignored) {
            resetWallpaper();
        }
    }

    private void applyWallpaper(Uri uri) {
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            try (java.io.InputStream in = getContentResolver().openInputStream(uri)) {
                BitmapFactory.decodeStream(in, null, bounds);
            }
            int targetWidth = Math.max(1, getResources().getDisplayMetrics().widthPixels);
            int targetHeight = Math.max(1, getResources().getDisplayMetrics().heightPixels);
            int sample = 1;
            while (bounds.outWidth / sample > targetWidth * 2 || bounds.outHeight / sample > targetHeight * 2) sample *= 2;
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inSampleSize = sample;
            options.inPreferredConfig = Bitmap.Config.RGB_565;
            Bitmap bitmap;
            try (java.io.InputStream in = getContentResolver().openInputStream(uri)) {
                bitmap = BitmapFactory.decodeStream(in, null, options);
            }
            if (bitmap == null) throw new IllegalArgumentException("Unable to decode wallpaper");
            wallpaperView.setImageBitmap(bitmap);
            wallpaperView.setVisibility(View.VISIBLE);
            // One blurred copy serves the full-screen blur (app list) and the frosted dock band.
            Bitmap blurred = BlurUtil.blur(bitmap, 256, 8);
            wallpaperBlurView.setImageBitmap(blurred);
            wallpaperBlurView.setVisibility(View.VISIBLE);
            if (dockDecoration != null) dockDecoration.setBackdrop(blurred);
            if (allAppsPanel != null) allAppsPanel.setBackdrop(blurred);
            if (grid != null) grid.invalidate();
        } catch (Exception e) {
            clearWallpaperViews();
            Toast.makeText(this, R.string.wallpaper_failed, Toast.LENGTH_SHORT).show();
        }
    }

    private void focusTile(int position) {
        if (adapter.getItemCount() == 0) return;
        final int pos = Math.max(0, Math.min(position, adapter.getItemCount() - 1));
        RecyclerView.ViewHolder vh = grid.findViewHolderForAdapterPosition(pos);
        if (vh != null) {
            View card = vh.itemView.findViewById(R.id.card);
            // Already focused (e.g. back from an app): no focus change fires, so restore the glass by hand.
            if (card.isFocused()) onTileFocused(pos, card);
            else card.requestFocus();
            return;
        }
        // Not laid out yet: scroll there, then focus once the scroll settles.
        grid.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrollStateChanged(@NonNull RecyclerView rv, int newState) {
                if (newState != RecyclerView.SCROLL_STATE_IDLE) return;
                rv.removeOnScrollListener(this);
                RecyclerView.ViewHolder h = rv.findViewHolderForAdapterPosition(pos);
                View target = h != null ? h.itemView.findViewById(R.id.card) : rv.getChildAt(0);
                if (target != null) target.requestFocus();
            }
        });
        grid.smoothScrollToPosition(pos);
    }

    // ---- top bar ---------------------------------------------------------------------------------

    private void updateGreeting() {
        int hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        int res;
        if (hour < 5) res = R.string.good_night;
        else if (hour < 12) res = R.string.good_morning;
        else if (hour < 17) res = R.string.good_afternoon;
        else if (hour < 22) res = R.string.good_evening;
        else res = R.string.good_night;
        greeting.setText(res);
    }

    /** Shows the Wi-Fi network name on the button when we can read it, otherwise the connection type. */
    private void updateWifiLabel() {
        String text = getString(R.string.wifi);
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            Network net = cm != null ? cm.getActiveNetwork() : null;
            NetworkCapabilities caps = net != null ? cm.getNetworkCapabilities(net) : null;
            if (caps == null) {
                text = getString(R.string.offline);
            } else if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) {
                text = getString(R.string.ethernet);
            } else if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
                WifiInfo info = wm != null ? wm.getConnectionInfo() : null;
                String ssid = info != null ? info.getSSID() : null;
                if (ssid != null) {
                    ssid = ssid.replace("\"", "").trim();
                    if (!ssid.isEmpty() && !ssid.contains("unknown") && !ssid.equals("0x")) text = ssid;
                }
            }
        } catch (Exception ignored) {
        }
        btnWifi.setText(text);
    }

    private void open(Intent i) {
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(i);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, R.string.not_available, Toast.LENGTH_SHORT).show();
        }
    }

    /** Dropdown menu under the Inputs pill: HDMI ports (with connection state), AV and Live TV. */
    private void showInputs() {
        View anchor = findViewById(R.id.btnInputs);
        View panel = getLayoutInflater().inflate(R.layout.popup_inputs, root, false);
        LinearLayout rows = panel.findViewById(R.id.rows);

        // Compact: as wide as the Inputs pill (a touch wider so "HDMI 1" fits), hanging right below it.
        int width = Math.max(anchor.getWidth(), (int) (150 * density));
        final PopupWindow popup = newDropdown(panel, width);

        TvInputManager tim = (TvInputManager) getSystemService(TV_INPUT_SERVICE);
        boolean sawNonHdmi = false;
        String tunerId = null;
        if (tim != null) {
            List<TvInputInfo> inputs = new ArrayList<>(tim.getTvInputList());
            // HDMI ports first in port order, then AV and the rest.
            Collections.sort(inputs, (a, b) -> {
                boolean ha = a.getType() == TvInputInfo.TYPE_HDMI, hb = b.getType() == TvInputInfo.TYPE_HDMI;
                if (ha != hb) return ha ? -1 : 1;
                return String.valueOf(a.loadLabel(this)).compareToIgnoreCase(String.valueOf(b.loadLabel(this)));
            });
            // Some TVs (Sony) report every HDMI port as connected whether or not anything is plugged in,
            // so drop the HDMI status then. A TV with every port really in use loses it too.
            int hdmiPorts = 0, hdmiConnected = 0;
            for (TvInputInfo info : inputs) {
                if (!info.isPassthroughInput() || info.getType() != TvInputInfo.TYPE_HDMI) continue;
                hdmiPorts++;
                if (tim.getInputState(info.getId()) == TvInputManager.INPUT_STATE_CONNECTED) hdmiConnected++;
            }
            boolean hdmiStateBogus = hdmiPorts > 1 && hdmiConnected == hdmiPorts;
            for (TvInputInfo info : inputs) {
                if (info.getType() == TvInputInfo.TYPE_TUNER
                        && (info.getServiceInfo().applicationInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0
                        && (tunerId == null || info.getId().compareTo(tunerId) < 0)) {
                    tunerId = info.getId();
                }
                if (!info.isPassthroughInput()) continue;
                CharSequence l = info.loadLabel(this);
                String label = l == null ? info.getId() : l.toString();
                boolean hdmi = info.getType() == TvInputInfo.TYPE_HDMI;
                if (hdmi && !label.toUpperCase().contains("HDMI")) label = "HDMI " + label;
                final String id = info.getId();
                int state = hdmi && hdmiStateBogus ? -1 : tim.getInputState(id);
                if (!hdmi && rows.getChildCount() > 0 && !sawNonHdmi) addSeparator(rows);
                if (!hdmi) sawNonHdmi = true;
                addInputRow(rows, popup, hdmi ? R.drawable.ic_input : R.drawable.ic_av, label, state,
                        () -> open(new Intent(Intent.ACTION_VIEW).setDataAndType(
                                TvContract.buildChannelUriForPassthroughInput(id), "vnd.android.cursor.item/channel")));
            }
        }
        Intent liveTv = liveTvPkg.isEmpty() ? null : getPackageManager().getLeanbackLaunchIntentForPackage(liveTvPkg);
        if (liveTv != null && tunerId != null) {
            liveTv = new Intent(Intent.ACTION_VIEW)
                    .setDataAndType(TvContract.buildChannelUriForPassthroughInput(tunerId), "vnd.android.cursor.item/channel")
                    .setPackage(liveTvPkg);
        }
        if (liveTv != null) {
            if (rows.getChildCount() > 0) addSeparator(rows);
            final Intent liveTvIntent = liveTv;
            addInputRow(rows, popup, R.drawable.ic_livetv, getString(R.string.live_tv), -1, () -> open(liveTvIntent));
        }
        if (rows.getChildCount() == 0) {
            Toast.makeText(this, R.string.no_inputs, Toast.LENGTH_SHORT).show();
            return;
        }

        showDropdown(popup, panel, anchor, width, false);
    }

    private PopupWindow newDropdown(View panel, int width) {
        PopupWindow popup = new PopupWindow(panel, width, ViewGroup.LayoutParams.WRAP_CONTENT, true);
        popup.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));   // needed for Back to dismiss
        popup.setOutsideTouchable(true);
        popup.setAnimationStyle(0);
        popup.setElevation(0f);
        return popup;
    }

    /** Fits the popup to its widest row, drops it in under the anchor (unfolding from the top) and focuses row one. */
    private void showDropdown(PopupWindow popup, View panel, View anchor, int minWidth, boolean alignEnd) {
        LinearLayout rows = panel.findViewById(R.id.rows);
        panel.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
        int width = Math.max(minWidth, panel.getMeasuredWidth());
        popup.setWidth(width);

        panel.setAlpha(0f);
        panel.setPivotY(0f);
        panel.setScaleY(0.92f);
        panel.setTranslationY(-8 * density);
        int xOff = alignEnd ? anchor.getWidth() - width : 0;
        popup.showAsDropDown(anchor, xOff, (int) (10 * density), Gravity.START);
        panel.animate().alpha(1f).scaleY(1f).translationY(0f).setDuration(220)
                .setInterpolator(new DecelerateInterpolator(1.6f)).start();
        if (rows.getChildCount() > 0) rows.getChildAt(0).requestFocus();
    }

    private void addSeparator(LinearLayout rows) {
        View line = new View(this);
        line.setBackgroundColor(0x1FFFFFFF);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (int) density);
        int m = (int) (6 * density), h = (int) (12 * density);
        lp.setMargins(h, m, h, m);
        rows.addView(line, lp);
    }

    private void addInputRow(LinearLayout rows, PopupWindow popup, int iconRes, String title, int state, Runnable action) {
        View row = getLayoutInflater().inflate(R.layout.item_input, rows, false);
        ((ImageView) row.findViewById(R.id.icon)).setImageResource(iconRes);
        ((TextView) row.findViewById(R.id.title)).setText(title);
        TextView status = row.findViewById(R.id.status);
        View dot = row.findViewById(R.id.dot);
        // This TV reports "standby" for every port whether or not something is plugged in, so only
        // definite states are shown.
        if (state == TvInputManager.INPUT_STATE_CONNECTED) {
            status.setText(R.string.connected);
            dot.setVisibility(View.VISIBLE);
        } else if (state == TvInputManager.INPUT_STATE_DISCONNECTED) {
            status.setText(R.string.not_connected);
            dot.setVisibility(View.GONE);
        } else {
            status.setVisibility(View.GONE);
            dot.setVisibility(View.GONE);
        }
        row.setOnClickListener(v -> {
            popup.dismiss();
            action.run();
        });
        rows.addView(row);
    }
}
