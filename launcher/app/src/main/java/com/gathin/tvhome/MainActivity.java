package com.gathin.tvhome;

import android.Manifest;
import android.animation.ArgbEvaluator;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Build;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.media.tv.TvContract;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.AnimationUtils;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.LayoutAnimationController;
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
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * tvOS style home with three states:
 * <ul>
 *   <li>Dock: clock, top buttons and the dock row at the bottom; nothing else is visible.</li>
 *   <li>All apps (Down from the dock): the background blurs, the clock, buttons and dock fade out and only
 *       the apps that are not in the dock are listed.</li>
 *   <li>Arrange: every app, dock included, can be moved to any position.</li>
 * </ul>
 */
public class MainActivity extends Activity implements AppAdapter.TileListener, DiscoverStrip.Listener {

    /** Tiles per row and dock slots (res/values/config.xml, columns). Set in onCreate. */
    static int COLUMNS = 4;
    private static final long MINUTE_MS = 60_000L;
    /** Where the glass panel starts (dp from the top) when the app list opens. Keeps the dock row off screen. */
    private static final int PANEL_TOP_DP = 28;
    /** Panel start when the Discover row sits above it. */
    private static final int PANEL_TOP_DISCOVER_DP = 168;
    /** Room at the top of the panel for the "All apps" caption; tiles are clipped below it. */
    private static final int PANEL_HEAD_DP = 30;
    private static final long MUSIC_CYCLE_MS = 6500L;
    private static final int PANEL_BOTTOM_DP = 24;
    private static final int ROW_INSET_DP = 38;          // first row of apps sits this far inside the panel
    /** Top padding of the grid while arranging, so the dock row and the rows below are all visible. */
    private static final int ARRANGE_TOP_DP = 36;
    private String liveTvPkg;
    private static final int REQUEST_WALLPAPER = 71;
    private static final int REQUEST_BT = 72;
    private static final String PREFS = "launcher_preferences";
    private static final String WALLPAPER_URI = "wallpaper_uri";

    private AppAdapter adapter;
    private RecyclerView grid;
    private DockDecoration dockDecoration;
    private FrameLayout root;
    private View header;
    private WidgetPanel widgets;
    private float lastDockTop = -1f;
    private ImageView wallpaperView;
    private ImageView wallpaperBlurView;
    private View allAppsScrim;
    private GlassPanelView allAppsPanel;
    private GlassView glass;
    private TextView heroName;
    private View ambient;
    private GradientDrawable ambientGlow;
    private int ambientColor;        // colour currently drawn
    private int ambientTarget;       // colour it is fading to
    private ValueAnimator ambientAnim;
    // Spotify: album colour while a song plays (0 = nothing playing)
    private int musicColor;
    /** Colours of the song playing; the glow walks through them every few seconds. */
    private int[] musicPalette;
    private int musicIndex;
    private String musicSongId = "";
    private DiscoverStrip discover;
    private TextView allAppsTitle;
    private boolean discoverOn;
    private int lastDockPos;
    private MediaSessionManager sessionManager;
    private final List<MediaController> watched = new ArrayList<>();
    private static boolean btPermissionAsked;
    private boolean heroShown;
    private TextView greeting;
    private TextView btnBluetooth;
    private SpringAnimation springX, springY;
    private float glassTargetX, glassTargetY;
    private boolean glassShown;
    /** True while focus is on the app list below the dock. */
    private boolean allAppsMode;
    /** True while apps are being rearranged. */
    private boolean arranging;
    /** What the blur / header state on screen currently reflects (see refreshChrome). */
    private boolean overlayApplied;
    /** True while the all-apps extras (the glass panel) is shown. */
    private boolean allUiApplied;
    /** True from the start of the "back to dock" animation until it ends. */
    private boolean snapActive;
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
    /** Refreshes the Bluetooth pill when a device connects or disconnects. */
    private final BroadcastReceiver btReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            updateBluetoothLabel();
            handler.postDelayed(MainActivity.this::updateBluetoothLabel, 1500);   // profiles settle a moment later
            handler.postDelayed(MainActivity.this::updateBluetoothLabel, 4500);
        }
    };
    private final MediaSessionManager.OnActiveSessionsChangedListener sessionsListener = this::watchSessions;
    private final MediaController.Callback mediaCallback = new MediaController.Callback() {
        @Override
        public void onPlaybackStateChanged(PlaybackState state) {
            updateMusicGlow();
        }

        @Override
        public void onMetadataChanged(MediaMetadata metadata) {
            updateMusicGlow();
        }
    };
    /** While a song plays, move the glow to the next album colour. */
    private final Runnable musicCycle = new Runnable() {
        @Override
        public void run() {
            if (musicPalette == null || musicPalette.length < 2) return;
            musicIndex = (musicIndex + 1) % musicPalette.length;
            musicColor = musicPalette[musicIndex];
            if (heroShown) animateAmbient(ambientFor(lastFocused), 3200);
            handler.postDelayed(this, MUSIC_CYCLE_MS);
        }
    };
    private final Runnable minuteTick = new Runnable() {
        @Override
        public void run() {
            updateGreeting();
            updateBluetoothLabel();
            widgets.refresh();
            positionWidgets();
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
                grid.setClipBounds(new Rect(0, panelTopPx() + (int) (PANEL_HEAD_DP * density), w, h - (int) (PANEL_BOTTOM_DP * density)));
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
        widgets = findViewById(R.id.widgets);
        wallpaperView = findViewById(R.id.wallpaper);
        wallpaperBlurView = findViewById(R.id.wallpaperBlur);
        allAppsScrim = findViewById(R.id.allAppsScrim);
        allAppsPanel = findViewById(R.id.allAppsPanel);
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
        btnBluetooth = findViewById(R.id.btnBluetooth);
        allAppsTitle = findViewById(R.id.allAppsTitle);
        discover = findViewById(R.id.discover);
        discover.setListener(this);
        discover.setOnContentChanged(this::syncDiscover);

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
        if (!adapter.iconPackPackage().isEmpty()) adapter.setIconPack("");   // icon packs were removed in v6
        grid.setAdapter(adapter);

        // tvOS layout: the dock row sits at the bottom of the screen; the app list is below it, out of sight.
        grid.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            updateGridPadding();
            if (!overlayApplied) clipRunnable.run();
        });

        // Keep the glass glued to its tile while the grid scrolls.
        grid.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrollStateChanged(@NonNull RecyclerView rv, int newState) {
                // Safety net: once a scroll settles, make sure the cursor sits on the focused tile.
                if (newState != RecyclerView.SCROLL_STATE_IDLE || !glassShown || snapActive) return;
                View f = getCurrentFocus();
                if (f != null && f.getId() == R.id.card && f.getParent() instanceof View
                        && ((View) f.getParent()).getParent() == grid) {
                    moveGlassTo(f, true);
                }
            }

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
        btnBluetooth.setOnClickListener(v -> showBluetooth());
        btnSettings.setOnFocusChangeListener((v, f) -> v.animate().rotation(f ? 60f : 0f).setDuration(300).start());
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
        btnBluetooth.setOnKeyListener(pillKeys);
        btnSettings.setOnKeyListener(pillKeys);

        // Hide the glass when focus leaves the tiles.
        getWindow().getDecorView().getViewTreeObserver().addOnGlobalFocusChangeListener((oldF, newF) -> {
            boolean onTile = newF != null && newF.getId() == R.id.card;
            if (DiscoverStrip.isCard(newF)) {
                // Discover row: part of the all-apps screen. Coming from the dock this opens it.
                hideGlass();
                hideHero();
                if (!allAppsMode && !arranging) enterAllApps();
                return;
            }
            if (!onTile) {
                hideGlass();
                if (allAppsMode) {
                    allAppsMode = false;
                    refreshChrome();
                }
            }
        });

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
        if (ambientAnim != null) ambientAnim.cancel();
        ambient.setAlpha(0f);
        // Start in the dock state; focusing a tile in the app list switches to the blurred state again.
        allAppsMode = false;
        arranging = false;
        overlayApplied = false;
        allUiApplied = false;
        snapActive = false;
        allAppsPanel.animate().cancel();
        allAppsPanel.setAlpha(0f);
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
        discover.animate().cancel();
        discover.setAlpha(0f);
        discover.setVisibility(View.INVISIBLE);
        allAppsTitle.animate().cancel();
        allAppsTitle.setAlpha(0f);
        allAppsTitle.setVisibility(View.INVISIBLE);
        discover.load(false);
        syncDiscover();
        updateGridPadding();
        clipRunnable.run();
        // Read these before reload(): rebinding can move focus to the first tile and overwrite them.
        final String returnKey = lastFocusedKey;
        final int returnSlot = lastFocused;
        adapter.reload();
        allAppsTitle.setText(getString(R.string.all_apps) + "  \u00B7  " + Math.max(0, adapter.getItemCount() - COLUMNS));
        grid.scheduleLayoutAnimation();
        updateGreeting();
        updateBluetoothLabel();
        handler.postDelayed(minuteTick, MINUTE_MS);
        if (wallpaperView.getVisibility() != View.VISIBLE) restoreWallpaper();   // safety net after a restart
        widgets.animate().cancel();
        widgets.setAlpha(1f);
        widgets.setVisibility(View.VISIBLE);
        widgets.refresh();
        positionWidgets();
        IntentFilter bt = new IntentFilter();
        bt.addAction(BluetoothAdapter.ACTION_STATE_CHANGED);
        bt.addAction(BluetoothAdapter.ACTION_CONNECTION_STATE_CHANGED);
        bt.addAction(BluetoothDevice.ACTION_ACL_CONNECTED);
        bt.addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED);
        registerReceiver(btReceiver, bt);
        if (Build.VERSION.SDK_INT >= 31 && !btPermissionAsked
                && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            btPermissionAsked = true;
            requestPermissions(new String[]{Manifest.permission.BLUETOOTH_CONNECT}, REQUEST_BT);
        }
        startMusicWatch();

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
        try {
            unregisterReceiver(btReceiver);
        } catch (IllegalArgumentException ignored) {
        }
        stopMusicWatch();
        if (arranging) finishArrange(false);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_BT) updateBluetoothLabel();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_WALLPAPER || resultCode != RESULT_OK || data == null) return;
        Uri uri = data.getData();
        if (uri == null) return;
        try {
            // Keep a private copy: the picked link may stop working after a TV restart.
            WallpaperStore.copyFrom(this, uri);
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().remove(WALLPAPER_URI).apply();
            applyWallpaper(WallpaperStore.uri(this));
        } catch (Exception e) {
            Toast.makeText(this, R.string.wallpaper_failed, Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        handler.removeCallbacks(clipRunnable);
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
     * lighter, a glass panel with the app list, everything else hidden) and the
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
        allAppsPanel.animate().alpha(show ? 1f : 0f).setDuration(show ? 350 : 200).start();
        fadeExtra(allAppsTitle, show);
        if (discoverOn && discover.hasItems()) fadeExtra(discover, show);
    }

    /** Fades a view of the all-apps screen in or out; hidden views also drop out of focus. */
    private void fadeExtra(final View v, boolean show) {
        v.animate().cancel();
        if (show) {
            v.setVisibility(View.VISIBLE);
            v.animate().alpha(1f).setDuration(350).start();
        } else {
            v.animate().alpha(0f).setDuration(200).withEndAction(() -> {
                if (!allUiApplied) v.setVisibility(View.INVISIBLE);
            }).start();
        }
    }

    /** Leaves room for the Discover row only while it has something to show (checked when the home screen is idle). */
    private void syncDiscover() {
        if (overlayApplied || arranging || discover == null) return;
        boolean now = discover.hasItems();
        if (now != discoverOn) {
            discoverOn = now;
            applyAllLayout();
        }
    }

    // ---- all apps: glass panel ---------------------------------------------------------------------

    private int panelTopPx() {
        return (int) ((discoverOn ? PANEL_TOP_DISCOVER_DP : PANEL_TOP_DP) * density);
    }

    /** Where the first row of apps sits when the list opens: just inside the glass panel. */
    private int allTopPx() {
        return panelTopPx() + (int) (ROW_INSET_DP * density);
    }

    /** Applies the grid's scroll range / clip for the panel position. */
    private void applyAllLayout() {
        ViewGroup.MarginLayoutParams lp = (ViewGroup.MarginLayoutParams) allAppsPanel.getLayoutParams();
        int top = panelTopPx();
        if (lp.topMargin != top) {
            lp.topMargin = top;
            allAppsPanel.setLayoutParams(lp);
        }
        ViewGroup.MarginLayoutParams tl = (ViewGroup.MarginLayoutParams) allAppsTitle.getLayoutParams();
        int titleTop = top + (int) (10 * density);
        if (tl.topMargin != titleTop) {
            tl.topMargin = titleTop;
            allAppsTitle.setLayoutParams(tl);
        }
        updateGridPadding();
        if (overlayApplied) clipRunnable.run();
    }

    /** Switches to the all-apps state with the first row of apps parked just inside the glass panel. */
    private void enterAllApps() {
        if (allAppsMode || arranging) return;
        allAppsMode = true;
        refreshChrome();
        if (adapter.getItemCount() > COLUMNS) {
            RecyclerView.ViewHolder vh = grid.findViewHolderForAdapterPosition(COLUMNS);
            if (vh != null) scrollGridBy(vh.itemView.getTop() - allTopPx());
        }
    }

    private void setHeaderShown(boolean show) {
        header.animate().cancel();
        widgets.animate().cancel();
        if (show) {
            header.setVisibility(View.VISIBLE);
            header.animate().alpha(1f).setDuration(300).start();
            widgets.setVisibility(View.VISIBLE);
            widgets.animate().alpha(1f).setDuration(300).start();
        } else {
            header.animate().alpha(0f).setDuration(180).withEndAction(() -> {
                if (overlayApplied) header.setVisibility(View.INVISIBLE);
            }).start();
            widgets.animate().alpha(0f).setDuration(180).withEndAction(() -> {
                if (overlayApplied) widgets.setVisibility(View.INVISIBLE);
            }).start();
        }
    }

    /** Puts the widget cards on the right, just above the dock, and drops the ones that do not fit. */
    private void positionWidgets() {
        if (widgets == null || root.getHeight() == 0) return;
        float dockTop = lastDockTop > 0 ? lastDockTop : root.getHeight() * 0.62f;
        float top = header.getBottom() + 4 * density;
        float bottom = dockTop - 26 * density;
        float step = (WidgetPanel.CARD_H_DP + WidgetPanel.GAP_DP) * density;
        int fit = (int) Math.max(0, Math.floor((bottom - top + WidgetPanel.GAP_DP * density) / step));
        widgets.setMaxCards(fit);
        widgets.setY(bottom - widgets.contentHeight());
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
            glassShown = true;
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
            topLimit = panelTopPx() + (int) ((PANEL_HEAD_DP + 4) * density);
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
        float pad = GlassView.PAD_DP * density;        // matches GlassView's glow inset
        float w = card.getWidth() * AppAdapter.FOCUS_SCALE + 2 * pad;
        float h = card.getHeight() * AppAdapter.FOCUS_SCALE + 2 * pad;
        float x = r.exactCenterX() - w / 2f;
        float y = r.exactCenterY() - h / 2f;

        ViewGroup.LayoutParams lp = glass.getLayoutParams();
        if (lp.width != (int) w || lp.height != (int) h) {
            lp.width = (int) w;
            lp.height = (int) h;
            glass.setLayoutParams(lp);
            glass.setRadius(16f * density * AppAdapter.FOCUS_SCALE);
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
        boolean glassWasShown = glassShown;
        glass.setColor(adapter.ambientColorAt(position), glassWasShown);   // cursor takes the app's colour
        Rect r = moveGlassTo(card, !leavingAll);      // leaving the list: snapToDock moves the glass itself
        View item = (View) card.getParent();
        if (position < COLUMNS) lastDockPos = position;
        View nameView = item.findViewById(R.id.name);
        if (nameView instanceof TextView) {
            ((TextView) nameView).setTextColor(AppAdapter.textTint(adapter.ambientColorAt(position)));
        }

        if (arranging) {
            hideHero();
            ensureVisible(item);
            return;
        }

        boolean entering = alignNext || (!allAppsMode && position >= COLUMNS);
        alignNext = false;
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

    // ---- Discover row ------------------------------------------------------------------------------

    @Override
    public void onUp() {
        focusTile(Math.min(lastDockPos, COLUMNS - 1));
    }

    @Override
    public void onDown() {
        if (adapter.getItemCount() > COLUMNS) focusTile(COLUMNS);
    }

    @Override
    public void onOpen(DiscoverStrip.Item item) {
        if (item.song) openSpotifySong(item);
        else openNuvio();
    }

    private void openNuvio() {
        PackageManager pm = getPackageManager();
        Intent probe = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
        for (ResolveInfo ri : pm.queryIntentActivities(probe, 0)) {
            if (ri.activityInfo == null) continue;
            String pkg = ri.activityInfo.packageName.toLowerCase();
            String label = String.valueOf(ri.loadLabel(pm)).toLowerCase();
            if (pkg.contains("nuvio") || label.contains("nuvio")) {
                open(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
                        .setComponent(new ComponentName(ri.activityInfo.packageName, ri.activityInfo.name)));
                return;
            }
        }
        Toast.makeText(this, R.string.nuvio_missing, Toast.LENGTH_SHORT).show();
    }

    private void openSpotifySong(DiscoverStrip.Item item) {
        Intent search = new Intent(Intent.ACTION_VIEW,
                Uri.parse("spotify:search:" + Uri.encode(item.title + " " + item.sub)));
        search.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(search);
            return;
        } catch (Exception ignored) {
        }
        PackageManager pm = getPackageManager();
        Intent probe = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
        for (ResolveInfo ri : pm.queryIntentActivities(probe, 0)) {
            if (ri.activityInfo != null && ri.activityInfo.packageName.startsWith("com.spotify")) {
                open(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
                        .setComponent(new ComponentName(ri.activityInfo.packageName, ri.activityInfo.name)));
                return;
            }
        }
        Toast.makeText(this, R.string.spotify_missing, Toast.LENGTH_SHORT).show();
    }

    // ---- top shelf -------------------------------------------------------------------------------

    private void showHero(int position, float dockTopInRoot) {
        lastDockTop = dockTopInRoot;
        positionWidgets();
        String label = adapter.labelAt(position);
        float y = dockTopInRoot - 14 * density - 26 * density - heroName.getHeight();
        boolean changed = !label.contentEquals(heroName.getText());
        final int tint = AppAdapter.textTint(adapter.ambientColorAt(position));

        if (!heroShown) {
            heroName.setText(label);
            heroName.setTextColor(tint);
            heroName.setY(y);
            heroName.setTranslationX(0f);
            heroName.animate().alpha(1f).setDuration(260).start();
            if (ambientAnim != null) ambientAnim.cancel();
            applyAmbientColor(ambientFor(position));
            ambient.animate().alpha(1f).setDuration(600).start();
            heroShown = true;
        } else if (changed) {
            heroName.animate().alpha(0f).translationX(-12 * density).setDuration(120).withEndAction(() -> {
                heroName.setText(label);
                heroName.setTextColor(tint);
                heroName.setY(y);
                heroName.setTranslationX(12 * density);
                heroName.animate().alpha(1f).translationX(0f).setDuration(200).start();
            }).start();
            animateAmbient(ambientFor(position), 600);   // cross-fade to the next app's colour
        } else {
            heroName.setY(y);
            heroName.setTextColor(tint);
        }
    }

    /** While a Spotify song plays the glow follows the album colour; otherwise it follows the focused app. */
    private int ambientFor(int position) {
        return musicColor != 0 ? musicColor : adapter.ambientColorAt(position);
    }

    /** Fades the glow behind the dock from its current colour to another. */
    private void animateAmbient(int target, long durationMs) {
        if (target == ambientTarget) return;
        ambientTarget = target;
        if (ambientAnim != null) ambientAnim.cancel();
        ambientAnim = ValueAnimator.ofObject(new ArgbEvaluator(), ambientColor, target);
        ambientAnim.setDuration(durationMs);
        ambientAnim.addUpdateListener(a -> setAmbient((Integer) a.getAnimatedValue()));
        ambientAnim.start();
    }

    private void applyAmbientColor(int color) {
        ambientTarget = color;
        setAmbient(color);
    }

    /** Soft radial glow behind the dock, darker than the app's own colour so it stays easy on the eyes. */
    private void setAmbient(int color) {
        ambientColor = color;
        int c = darken(color) & 0x00FFFFFF;
        ambientGlow.setColors(new int[]{0x50000000 | c, 0x20000000 | c, 0x00000000 | c});
        ambient.invalidate();
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
            if (position < 2 * COLUMNS && discoverOn && discover.hasItems()) {
                discover.focusRemembered();                // first row of apps: up into the Discover row
                return true;
            }
            target = position - COLUMNS;
        } else {
            if (position < COLUMNS && discoverOn && discover.hasItems() && count > COLUMNS) {
                enterAllApps();                            // dock: down into the Discover row
                discover.post(() -> discover.focusRemembered());
                return true;
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
        if (!arranging) {
            // Endless dock: Right on the last dock app wraps to the first, Left on the first to the last.
            int dockEnd = Math.min(COLUMNS, adapter.getItemCount()) - 1;
            if (position <= dockEnd && dockEnd > 0) {
                if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT && position == dockEnd) {
                    focusTile(0);
                    return true;
                }
                if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT && position == 0) {
                    focusTile(dockEnd);
                    return true;
                }
            }
            return false;
        }
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
        addInputRow(rows, popup, R.drawable.ic_widgets, getString(R.string.widgets), -1, this::showWidgets);
        addInputRow(rows, popup, R.drawable.ic_reset, getString(R.string.refresh_discover), -1,
                () -> discover.load(true));
        if (!hasListenerAccess()) {
            addInputRow(rows, popup, R.drawable.ic_saver, getString(R.string.music_access), -1, this::openMusicAccess);
        }
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

    /** Switch the home-screen widgets on or off. */
    private void showWidgets() {
        View anchor = findViewById(R.id.btnSettings);
        View panel = getLayoutInflater().inflate(R.layout.popup_apps, root, false);
        ((TextView) panel.findViewById(R.id.title)).setText(R.string.widgets_hint);
        LinearLayout rows = panel.findViewById(R.id.rows);
        int minWidth = (int) (320 * density);
        final PopupWindow popup = newDropdown(panel, minWidth);
        for (final String id : WidgetPanel.IDS) {
            final View row = getLayoutInflater().inflate(R.layout.item_input, rows, false);
            ((ImageView) row.findViewById(R.id.icon)).setImageResource(R.drawable.ic_widgets);
            ((TextView) row.findViewById(R.id.title)).setText(WidgetPanel.titleRes(id));
            updateWidgetRow(row, widgets.isOn(id));
            row.setOnClickListener(v -> {
                boolean on = !widgets.isOn(id);
                widgets.setOn(id, on);
                updateWidgetRow(row, on);
                positionWidgets();
            });
            rows.addView(row);
        }
        fitScroll(panel);
        showDropdown(popup, panel, anchor, minWidth, true);
    }

    private void updateWidgetRow(View row, boolean on) {
        ((TextView) row.findViewById(R.id.status)).setText(on ? R.string.widget_on : R.string.widget_off);
        row.findViewById(R.id.dot).setVisibility(on ? View.VISIBLE : View.GONE);
        row.findViewById(R.id.title).setAlpha(on ? 1f : 0.45f);
        row.findViewById(R.id.icon).setAlpha(on ? 1f : 0.45f);
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
            if (changed[0]) adapter.reload();
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
        WallpaperStore.delete(this);
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
        if (!WallpaperStore.exists(this)) {
            // Wallpaper chosen in an older version: move it into the app once, if its link still opens.
            String legacy = getSharedPreferences(PREFS, MODE_PRIVATE).getString(WALLPAPER_URI, null);
            if (legacy == null) return;
            try {
                WallpaperStore.copyFrom(this, Uri.parse(legacy));
            } catch (Exception e) {
                return;                       // keep the saved link so the next start can try again
            }
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().remove(WALLPAPER_URI).apply();
        }
        applyWallpaper(WallpaperStore.uri(this));
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

    private int btPending;
    private int btGen;
    private boolean btFound;

    /** Name of the connected Bluetooth device (speaker first, then remotes etc.), or "Bluetooth" when none. */
    private void updateBluetoothLabel() {
        btnBluetooth.setMaxWidth((int) (260 * density));
        btnBluetooth.setSingleLine(true);
        btnBluetooth.setEllipsize(android.text.TextUtils.TruncateAt.END);
        final BluetoothAdapter ba = BluetoothAdapter.getDefaultAdapter();
        if (ba == null || !ba.isEnabled()) {
            btnBluetooth.setText(R.string.bluetooth);
            return;
        }
        // 1) Audio devices: lists a connected speaker / headphones by name and needs no permission.
        String name = connectedAudioName();
        // 2) Paired devices that report "connected" (remotes, keyboards, gamepads).
        if (name == null) name = bondedConnectedName(ba);
        if (name != null) {
            btGen++;
            btPending = 0;
            btnBluetooth.setText(name);
            return;
        }
        // 3) Ask each Bluetooth profile; answers arrive a moment later.
        final int gen = ++btGen;
        final int[] profiles = {BluetoothProfile.A2DP, BluetoothProfile.HEADSET, 4 /* HID host */, BluetoothProfile.GATT};
        final BluetoothProfile.ServiceListener listener = new BluetoothProfile.ServiceListener() {
            @Override
            public void onServiceConnected(int profile, BluetoothProfile proxy) {
                String found = null;
                try {
                    List<BluetoothDevice> devices = proxy.getConnectedDevices();
                    if (devices != null && !devices.isEmpty()) {
                        String n = devices.get(0).getName();
                        if (n != null && !n.trim().isEmpty()) found = n.trim();
                    }
                } catch (RuntimeException ignored) {
                }
                try {
                    ba.closeProfileProxy(profile, proxy);
                } catch (RuntimeException ignored) {
                }
                final String result = found;
                handler.post(() -> btLookupDone(gen, result));
            }

            @Override
            public void onServiceDisconnected(int profile) {
            }
        };
        btFound = false;
        btPending = profiles.length;
        for (int profile : profiles) {
            boolean asked = false;
            try {
                asked = ba.getProfileProxy(this, listener, profile);
            } catch (RuntimeException ignored) {
            }
            if (!asked) btLookupDone(gen, null);         // unsupported profile: no callback will come
        }
        // Some profiles never answer: do not wait for them forever.
        handler.postDelayed(() -> {
            if (gen == btGen && btPending > 0) {
                btPending = 0;
                if (!btFound) btnBluetooth.setText(R.string.bluetooth);
            }
        }, 3000);
    }

    private void btLookupDone(int gen, String name) {
        if (gen != btGen) return;                        // an older lookup
        if (name != null && !btFound) {                  // first connected device wins
            btFound = true;
            btnBluetooth.setText(name);
        }
        if (btPending > 0 && --btPending == 0 && !btFound) btnBluetooth.setText(R.string.bluetooth);
    }

    private String connectedAudioName() {
        try {
            AudioManager am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
            for (AudioDeviceInfo info : am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
                int t = info.getType();
                // 7 = Bluetooth SCO, 8 = Bluetooth A2DP, 26 = BLE headset, 27 = BLE speaker
                if (t == 7 || t == 8 || t == 26 || t == 27) {
                    CharSequence n = info.getProductName();
                    if (n != null && n.toString().trim().length() > 0) return n.toString().trim();
                }
            }
        } catch (RuntimeException ignored) {
        }
        return null;
    }

    private String bondedConnectedName(BluetoothAdapter ba) {
        try {
            for (BluetoothDevice dev : ba.getBondedDevices()) {
                try {
                    Object connected = BluetoothDevice.class.getMethod("isConnected").invoke(dev);
                    if (Boolean.TRUE.equals(connected)) {
                        String n = dev.getName();
                        if (n != null && n.trim().length() > 0) return n.trim();
                    }
                } catch (Exception ignored) {
                }
            }
        } catch (RuntimeException ignored) {
        }
        return null;
    }

    private static final class BtDev {
        final String name;
        final String address;
        boolean connected;

        BtDev(String name, String address) {
            this.name = name;
            this.address = address;
        }
    }

    /** Dropdown under the Bluetooth pill: connected devices first, then paired ones, then the settings page. */
    private void showBluetooth() {
        View anchor = btnBluetooth;
        View panel = getLayoutInflater().inflate(R.layout.popup_apps, root, false);
        panel.findViewById(R.id.title).setVisibility(View.GONE);
        LinearLayout rows = panel.findViewById(R.id.rows);
        int minWidth = (int) (260 * density);
        final PopupWindow popup = newDropdown(panel, minWidth);
        final BluetoothAdapter ba = BluetoothAdapter.getDefaultAdapter();
        final Map<String, View> rowByAddress = new HashMap<>();

        if (ba == null || !ba.isEnabled()) {
            addBtRow(rows, popup, getString(R.string.bt_off), null, false, 0.6f);
        } else {
            List<BtDev> devices = new ArrayList<>();
            try {
                Map<String, Boolean> audio = connectedAudioNames();
                for (BluetoothDevice dev : ba.getBondedDevices()) {
                    String n = dev.getName();
                    BtDev b = new BtDev(n == null || n.trim().isEmpty() ? dev.getAddress() : n.trim(), dev.getAddress());
                    try {
                        b.connected = Boolean.TRUE.equals(BluetoothDevice.class.getMethod("isConnected").invoke(dev));
                    } catch (Exception ignored) {
                    }
                    if (!b.connected && audio.containsKey(b.name.toLowerCase())) b.connected = true;
                    devices.add(b);
                }
            } catch (RuntimeException ignored) {
            }
            Collections.sort(devices, (a, b) -> {
                if (a.connected != b.connected) return a.connected ? -1 : 1;
                return a.name.compareToIgnoreCase(b.name);
            });
            for (BtDev b : devices) {
                View row = addBtRow(rows, popup, b.name, b.connected ? getString(R.string.connected) : getString(R.string.bt_paired),
                        b.connected, b.connected ? 1f : 0.65f);
                rowByAddress.put(b.address, row);
            }
            if (devices.isEmpty()) addBtRow(rows, popup, getString(R.string.bt_none), null, false, 0.6f);
            // Some devices (remotes, keyboards) only report "connected" through their Bluetooth profile.
            final int[] profiles = {BluetoothProfile.A2DP, BluetoothProfile.HEADSET, 4 /* HID host */, BluetoothProfile.GATT};
            final BluetoothProfile.ServiceListener listener = new BluetoothProfile.ServiceListener() {
                @Override
                public void onServiceConnected(int profile, BluetoothProfile proxy) {
                    final List<String> found = new ArrayList<>();
                    try {
                        List<BluetoothDevice> list = proxy.getConnectedDevices();
                        if (list != null) for (BluetoothDevice d : list) found.add(d.getAddress());
                    } catch (RuntimeException ignored) {
                    }
                    try {
                        ba.closeProfileProxy(profile, proxy);
                    } catch (RuntimeException ignored) {
                    }
                    handler.post(() -> {
                        if (!popup.isShowing()) return;
                        for (String addr : found) {
                            View row = rowByAddress.get(addr);
                            if (row != null) markBtConnected(row);
                        }
                    });
                }

                @Override
                public void onServiceDisconnected(int profile) {
                }
            };
            for (int profile : profiles) {
                try {
                    ba.getProfileProxy(this, listener, profile);
                } catch (RuntimeException ignored) {
                }
            }
        }
        addSeparator(rows);
        addInputRow(rows, popup, R.drawable.ic_bluetooth, getString(R.string.bt_settings), -1, this::openRemotesAndAccessories);
        fitScroll(panel);
        showDropdown(popup, panel, anchor, minWidth, false);
    }

    private View addBtRow(LinearLayout rows, PopupWindow popup, String title, String status, boolean connected, float alpha) {
        View row = getLayoutInflater().inflate(R.layout.item_input, rows, false);
        ((ImageView) row.findViewById(R.id.icon)).setImageResource(R.drawable.ic_bluetooth);
        ((TextView) row.findViewById(R.id.title)).setText(title);
        TextView st = row.findViewById(R.id.status);
        if (status == null) st.setVisibility(View.GONE);
        else st.setText(status);
        row.findViewById(R.id.dot).setVisibility(connected ? View.VISIBLE : View.GONE);
        row.findViewById(R.id.title).setAlpha(alpha);
        row.findViewById(R.id.icon).setAlpha(alpha);
        row.setOnClickListener(v -> {
            popup.dismiss();
            openRemotesAndAccessories();
        });
        rows.addView(row);
        return row;
    }

    private void markBtConnected(View row) {
        ((TextView) row.findViewById(R.id.status)).setText(R.string.connected);
        row.findViewById(R.id.dot).setVisibility(View.VISIBLE);
        row.findViewById(R.id.title).setAlpha(1f);
        row.findViewById(R.id.icon).setAlpha(1f);
    }

    /** Lower-case names of the Bluetooth audio outputs that are connected right now. */
    private Map<String, Boolean> connectedAudioNames() {
        Map<String, Boolean> names = new HashMap<>();
        try {
            AudioManager am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
            for (AudioDeviceInfo info : am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
                int t = info.getType();
                if (t == 7 || t == 8 || t == 26 || t == 27) {
                    CharSequence n = info.getProductName();
                    if (n != null && n.toString().trim().length() > 0) names.put(n.toString().trim().toLowerCase(), true);
                }
            }
        } catch (RuntimeException ignored) {
        }
        return names;
    }

    /** Opens the TV's "Remotes & Accessories" page (the Bluetooth device list). */
    private void openRemotesAndAccessories() {
        Intent[] tries = {
                new Intent("android.settings.BLUETOOTH_SETTINGS"),
                new Intent().setComponent(new ComponentName("com.android.tv.settings",
                        "com.android.tv.settings.accessories.AccessoriesActivity")),
                new Intent().setComponent(new ComponentName("com.android.tv.settings",
                        "com.android.tv.settings.accessories.AddAccessoryActivity")),
                new Intent(Settings.ACTION_SETTINGS)
        };
        for (Intent i : tries) {
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try {
                startActivity(i);
                return;
            } catch (Exception ignored) {
            }
        }
        Toast.makeText(this, R.string.not_available, Toast.LENGTH_SHORT).show();
    }

    // ---- Spotify: glow follows the album colour ----------------------------------------------------

    private boolean hasListenerAccess() {
        String enabled = Settings.Secure.getString(getContentResolver(), "enabled_notification_listeners");
        return enabled != null && enabled.contains(getPackageName());
    }

    private void openMusicAccess() {
        try {
            open(new Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"));
        } catch (Exception ignored) {
        }
        Toast.makeText(this, R.string.music_access_help, Toast.LENGTH_LONG).show();
    }

    private void startMusicWatch() {
        if (!hasListenerAccess()) return;
        try {
            sessionManager = (MediaSessionManager) getSystemService(Context.MEDIA_SESSION_SERVICE);
            ComponentName cn = new ComponentName(this, MediaListener.class);
            sessionManager.addOnActiveSessionsChangedListener(sessionsListener, cn, handler);
            watchSessions(sessionManager.getActiveSessions(cn));
        } catch (RuntimeException ignored) {
        }
    }

    private void stopMusicWatch() {
        handler.removeCallbacks(musicCycle);
        musicSongId = "";
        musicPalette = null;
        musicColor = 0;
        for (MediaController c : watched) c.unregisterCallback(mediaCallback);
        watched.clear();
        if (sessionManager != null) {
            try {
                sessionManager.removeOnActiveSessionsChangedListener(sessionsListener);
            } catch (RuntimeException ignored) {
            }
        }
    }

    private void watchSessions(List<MediaController> controllers) {
        for (MediaController c : watched) c.unregisterCallback(mediaCallback);
        watched.clear();
        if (controllers != null) {
            for (MediaController c : controllers) {
                if (c.getPackageName() != null && c.getPackageName().startsWith("com.spotify")) {
                    c.registerCallback(mediaCallback, handler);
                    watched.add(c);
                }
            }
        }
        updateMusicGlow();
    }

    /** While a Spotify song plays the glow walks through the album's colours; otherwise it follows the focused app. */
    private void updateMusicGlow() {
        MediaMetadata meta = null;
        boolean playing = false;
        for (MediaController c : watched) {
            PlaybackState st = c.getPlaybackState();
            if (st == null || st.getState() != PlaybackState.STATE_PLAYING) continue;
            meta = c.getMetadata();
            playing = true;
            break;
        }
        boolean changed = false;
        if (playing) {
            String id = SongColors.idOf(meta);
            int[] fresh = SongColors.palette(meta);
            boolean sameSong = id.equals(musicSongId);
            if (!sameSong || !Arrays.equals(fresh, musicPalette)) {
                changed = true;
                if (!sameSong) musicIndex = 0;
                musicSongId = id;
                musicPalette = fresh;
                musicIndex = musicIndex % fresh.length;
            }
            int color = musicPalette[musicIndex];
            if (color != musicColor) changed = true;
            musicColor = color;
            String artist = meta == null ? null : meta.getString(MediaMetadata.METADATA_KEY_ARTIST);
            if (artist == null && meta != null) artist = meta.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST);
            widgets.setNowPlaying(meta == null ? null : meta.getString(MediaMetadata.METADATA_KEY_TITLE),
                    artist, SongColors.artOf(meta), color);
            if (changed) {
                handler.removeCallbacks(musicCycle);
                handler.postDelayed(musicCycle, MUSIC_CYCLE_MS);
            }
        } else {
            if (musicColor != 0) changed = true;
            musicPalette = null;
            musicColor = 0;
            musicSongId = "";
            handler.removeCallbacks(musicCycle);
            widgets.clearNowPlaying();
        }
        positionWidgets();
        if (changed && heroShown) animateAmbient(ambientFor(lastFocused), 1800);   // slow, soft fade
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
