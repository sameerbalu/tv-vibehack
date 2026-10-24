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
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageInfo;
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
import android.os.SystemClock;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.AnimationUtils;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.AccelerateDecelerateInterpolator;
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
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.List;
import java.util.Locale;
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
public class MainActivity extends Activity implements AppAdapter.TileListener, ContinueAdapter.Host {

    /** Tiles per row and dock slots (res/values/config.xml, columns). Set in onCreate. */
    static int COLUMNS = 4;
    private static final long MINUTE_MS = 60_000L;
    /** Where the glass panel starts (dp from the top) when the app list opens. Keeps the dock row off screen. */
    private static final int PANEL_TOP_DP = 28;
    private static final long MUSIC_CYCLE_MS = 13000L;      // time between album colours
    private static final int PANEL_BOTTOM_DP = 24;
    private static final int ROW_INSET_DP = 14;          // first row of apps sits this far inside the panel
    /** Top padding of the grid while arranging, so the dock row and the rows below are all visible. */
    private static final int ARRANGE_TOP_DP = 36;
    private String liveTvPkg;
    private static final int REQUEST_WALLPAPER = 71;
    private static final int REQUEST_BT = 72;
    private static final int REQUEST_FRONT = 73;
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
    private ImageView wallpaperFront;
    private ImageView wallpaperFrontTop;       // same PNG, clipped to the clock + date so they sit behind it
    private View allAppsScrim;
    private GlassPanelView allAppsPanel;
    private GlassView glass;
    private TextView heroName;
    private View ambient;
    private GradientDrawable ambientGlow;
    private int ambientColor;        // colour currently drawn
    private int ambientTarget;       // colour it is fading to
    private ValueAnimator ambientAnim;
    private ValueAnimator ambientPulse;     // slow "breathing" of the glow while a song plays
    private long lastPulseDraw;
    private float pulseT;                   // 0..1, position in the current glow style's cycle
    private final float[] hsvTmp = new float[3];
    private boolean fxGlowOn = true;        // Settings > Glow & effects
    private int fxStyle = 1;                // 0 steady, 1 breathing, 2 heartbeat, 3 drift, 4 shimmer, 5 rainbow
    private int fxLevel = 1;                // 0 soft, 1 normal, 2 strong
    private float ambientBaseRadius;
    // Spotify: album colour while a song plays (0 = nothing playing)
    private int musicColor;
    /** Colours of the song playing; the glow walks through them every few seconds. */
    private int[] musicPalette;
    private int musicIndex;
    private String musicSongId = "";
    private MediaSessionManager sessionManager;
    private MediaController playingController;
    private final Runnable progressTick = new Runnable() {
        @Override
        public void run() {
            updateNowProgress();
            if (playingController != null) handler.postDelayed(this, 1000);
        }
    };
    private final List<MediaController> watched = new ArrayList<>();
    private static boolean btPermissionAsked;
    private boolean heroShown;
    private TextView greeting;
    private TextView btnBluetooth;
    private SpringAnimation springX, springY;
    private float glassTargetX, glassTargetY;
    private boolean glassShown;
    /** True while focus is on the app list below the dock. */
    private ContinueAdapter continueAdapter;
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
    // Coming back from an app opened in All apps: the list stays exactly where it was.
    private static final int NO_TOP = Integer.MIN_VALUE;
    private int resumeTileTop = NO_TOP;      // top of the tile (in the grid) when we left
    private boolean keepScroll;
    private boolean restorePending;          // true between onResume and the focus restore
    private static final long DOUBLE_TAP_MS = 320;
    private long lastSettingsClick;
    private final Runnable openSettingsMenu = this::showLauncherSettings;
    private boolean paused;                  // true between onPause and onResume
    private static final long IDLE_MS = 2 * 60 * 1000L;       // screen saver starts after 2 minutes without input
    private final Runnable idleSaver = this::startSaver;
    // We left from the full app list: keep its look (blur, panel, hidden clock) when Home brings us back.
    private static boolean resumeInAll;
    private long lastResumeMs;
    private final Runnable restoreFocus = this::restoreFocusNow;
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
            if (BluetoothDevice.ACTION_ACL_CONNECTED.equals(intent.getAction())) {
                try {
                    BluetoothDevice d = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                    if (d != null) rememberBt(d.getAddress(), d.getName());
                } catch (RuntimeException ignored) {
                }
            }
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
            if (heroShown) animateAmbient(ambientFor(lastFocused), 9000);   // long, slow cross-fade
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
        widgets = findViewById(R.id.widgets);
        wallpaperView = findViewById(R.id.wallpaper);
        wallpaperBlurView = findViewById(R.id.wallpaperBlur);
        wallpaperFront = findViewById(R.id.wallpaperFront);
        wallpaperFrontTop = findViewById(R.id.wallpaperFrontTop);
        findViewById(R.id.leftBlock).addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> updateFrontTopClip());
        allAppsScrim = findViewById(R.id.allAppsScrim);
        allAppsPanel = findViewById(R.id.allAppsPanel);
        glass = findViewById(R.id.glass);
        heroName = findViewById(R.id.heroName);
        ambient = findViewById(R.id.ambient);
        ambientGlow = new GradientDrawable();
        ambientGlow.setGradientType(GradientDrawable.RADIAL_GRADIENT);
        ambientGlow.setGradientCenter(0.32f, 0.42f);
        ambient.setBackground(ambientGlow);
        ambient.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            ambientBaseRadius = (r - l) * 0.62f;
            ambientGlow.setGradientRadius(ambientBaseRadius);
        });
        greeting = findViewById(R.id.greeting);
        btnBluetooth = findViewById(R.id.btnBluetooth);

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
        // Room above the first row of apps for the Continue watching row (which floats over that gap).
        grid.addItemDecoration(new RecyclerView.ItemDecoration() {
            @Override
            public void getItemOffsets(@NonNull Rect outRect, @NonNull View view, @NonNull RecyclerView parent,
                                       @NonNull RecyclerView.State state) {
                int pos = parent.getChildAdapterPosition(view);
                if (pos >= COLUMNS && pos < 2 * COLUMNS && continueActive()) outRect.top = continueGapPx;
            }
        });
        grid.setLayoutAnimation(new LayoutAnimationController(
                AnimationUtils.loadAnimation(this, R.anim.tile_enter), 0.06f));
        adapter = new AppAdapter(this, this, COLUMNS);
        if (!adapter.iconPackPackage().isEmpty()) adapter.setIconPack("");   // icon packs were removed in v6
        grid.setAdapter(adapter);
        setupContinueRow();

        // tvOS layout: the dock row sits at the bottom of the screen; the app list is below it, out of sight.
        grid.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            layoutContinue();
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
                        && (((View) f.getParent()).getParent() == grid
                        || ((View) f.getParent()).getParent() == continueRow)) {
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
        // Inputs: the signal arrow glides into the screen. Bluetooth: the rune draws itself and radio waves
        // ripple out. (Settings: the gear turns, above.)
        inputsIcon = new PillIcon.Input(density, ((TextView) btnInputs).getTextColors());
        bluetoothIcon = new PillIcon.Bluetooth(density, btnBluetooth.getTextColors());
        ((TextView) btnInputs).setCompoundDrawablesRelativeWithIntrinsicBounds(inputsIcon, null, null, null);
        btnBluetooth.setCompoundDrawablesRelativeWithIntrinsicBounds(bluetoothIcon, null, null, null);
        btnInputs.setOnFocusChangeListener((v, f) -> inputsIcon.setFocused(f));
        btnBluetooth.setOnFocusChangeListener((v, f) -> bluetoothIcon.setFocused(f));
        // One press opens the menu; two quick presses clear the cache.
        btnSettings.setOnClickListener(v -> {
            long t = SystemClock.uptimeMillis();
            if (t - lastSettingsClick < DOUBLE_TAP_MS) {
                lastSettingsClick = 0;
                handler.removeCallbacks(openSettingsMenu);
                quickClearCache();
            } else {
                lastSettingsClick = t;
                handler.postDelayed(openSettingsMenu, DOUBLE_TAP_MS);
            }
        });

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
            if (!onTile) {
                hideGlass();
                // While we are in the background (or still restoring after Home) focus bounces around; that
                // must not switch the look back to the dock.
                if (allAppsMode && !paused && !restorePending) {
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
        long nowMs = SystemClock.uptimeMillis();
        paused = false;
        final boolean keepAll = resumeInAll;
        resumeInAll = false;
        // Home pressed again right away: skip the slow reload and the enter animation, they only stack up.
        final boolean quick = adapter.getItemCount() > 0 && nowMs - lastResumeMs < 2500;
        lastResumeMs = nowMs;
        restorePending = true;
        handler.removeCallbacks(restoreFocus);
        glassShown = false;
        glass.animate().cancel();
        springX.cancel();
        springY.cancel();
        glass.setAlpha(0f);
        heroShown = false;
        heroName.animate().cancel();
        heroName.setTranslationX(0f);
        heroName.setAlpha(0f);
        ambient.animate().cancel();
        if (ambientAnim != null) ambientAnim.cancel();
        ambient.setAlpha(0f);
        // Start in the dock state; focusing a tile in the app list switches to the blurred state again.
        // (If we left from the full app list, its look stays exactly as it was: no flash of the dock state.)
        arranging = false;
        snapActive = false;
        allAppsPanel.animate().cancel();
        wallpaperBlurView.animate().cancel();
        allAppsScrim.animate().cancel();
        wallpaperFront.animate().cancel();
        wallpaperFrontTop.animate().cancel();
        header.animate().cancel();
        allAppsMode = keepAll;
        overlayApplied = keepAll;
        allUiApplied = keepAll;
        allAppsPanel.setAlpha(keepAll ? 1f : 0f);
        wallpaperBlurView.setAlpha(keepAll ? 1f : 0f);
        allAppsScrim.setAlpha(keepAll ? 1f : 0f);
        wallpaperFront.setAlpha(keepAll ? 0f : 1f);
        wallpaperFrontTop.setAlpha(keepAll ? 0f : 1f);
        header.setAlpha(keepAll ? 0f : 1f);
        header.setVisibility(keepAll ? View.INVISIBLE : View.VISIBLE);
        grid.animate().cancel();
        grid.setAlpha(1f);
        grid.setTranslationY(0f);
        updateGridPadding();
        clipRunnable.run();
        // Read these before reload(): rebinding can move focus to the first tile and overwrite them.
        returnKey = lastFocusedKey;
        returnSlot = lastFocused;
        if (quick) {
            grid.clearAnimation();
            for (int i = 0; i < grid.getChildCount(); i++) grid.getChildAt(i).clearAnimation();
        } else {
            if (adapter.reloadIfChanged()) grid.scheduleLayoutAnimation();
        }
        continueAdapter.refresh();
        layoutContinue();
        grid.invalidateItemDecorations();
        updateGreeting();
        updateBluetoothLabel();
        handler.postDelayed(minuteTick, MINUTE_MS);
        rescheduleIdle();
        if (wallpaperView.getVisibility() != View.VISIBLE) restoreWallpaper();   // safety net after a restart
        widgets.animate().cancel();
        widgets.setAlpha(keepAll ? 0f : 1f);
        widgets.setVisibility(keepAll ? View.INVISIBLE : View.VISIBLE);
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
        SharedPreferences fx = getSharedPreferences(PREFS, MODE_PRIVATE);
        fxGlowOn = fx.getBoolean("fx_glow", true);
        fxStyle = Math.max(0, Math.min(STYLE_NAMES.length - 1, fx.getInt("fx_style", 1)));
        fxLevel = Math.max(0, Math.min(2, fx.getInt("fx_level", 1)));
        syncPulse();

        // Return to the app we left from, or the same slot if that app is gone. Always refocus: after
        // reload() the view that holds focus may no longer show the app it had.
        grid.post(restoreFocus);
        handler.postDelayed(() -> restorePending = false, 1500);   // never leave focus handling blocked
    }

    private String returnKey;
    private int returnSlot;

    /** Puts focus back on the app we left from; for All apps also puts the list back where it was. */
    private void restoreFocusNow() {
        restorePending = false;
        int count = adapter.getItemCount();
        if (count == 0) return;
        int pos = adapter.positionOf(returnKey);
        if (pos < 0) pos = returnSlot;
        pos = Math.max(0, Math.min(pos, count - 1));
        if (resumeTileTop != NO_TOP && pos >= COLUMNS) {
            final int target = pos;
            // Same scroll position as before, so the row is not pulled up to the first row.
            ((GridLayoutManager) grid.getLayoutManager())
                    .scrollToPositionWithOffset(target, resumeTileTop - grid.getPaddingTop());
            resumeTileTop = NO_TOP;
            grid.post(() -> {
                keepScroll = true;
                focusTile(target);
                keepScroll = false;
            });
            return;
        }
        resumeTileTop = NO_TOP;
        focusTile(pos);
    }

    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacks(restoreFocus);
        handler.removeCallbacks(minuteTick);
        handler.removeCallbacks(idleSaver);
        if (inputsIcon != null) inputsIcon.rest();
        if (bluetoothIcon != null) bluetoothIcon.rest();
        if (!restorePending) {
            // Remember where the list was, so Home returns to the same spot.
            resumeTileTop = NO_TOP;
            resumeInAll = allAppsMode && !arranging && lastFocused >= COLUMNS;
            if (resumeInAll) {
                RecyclerView.ViewHolder vh = grid.findViewHolderForAdapterPosition(lastFocused);
                if (vh != null) resumeTileTop = vh.itemView.getTop() - firstRowGap(lastFocused);
            }
        }
        paused = true;
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
        if (resultCode != RESULT_OK || data == null) return;
        Uri uri = data.getData();
        if (uri == null) return;
        if (requestCode == REQUEST_FRONT) {
            try {
                WallpaperStore.copyFront(this, uri);
                applyFront();
            } catch (Exception e) {
                Toast.makeText(this, R.string.wallpaper_failed, Toast.LENGTH_SHORT).show();
            }
            return;
        }
        if (requestCode != REQUEST_WALLPAPER) return;
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
        if (dismissOpenPopup()) return;
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
            wallpaperFront.animate().alpha(overlay ? 0f : 1f).setDuration(350).start();
            wallpaperFrontTop.animate().alpha(overlay ? 0f : 1f).setDuration(350).start();
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
    }

    // ---- all apps: glass panel ---------------------------------------------------------------------

    private int panelTopPx() {
        return (int) (PANEL_TOP_DP * density);
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
            if (vh != null) scrollGridBy(vh.itemView.getTop() - firstRowGap(COLUMNS) - allTopPx());
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

    @Override
    public void onUserInteraction() {
        super.onUserInteraction();
        rescheduleIdle();               // any key or touch starts the 2 minutes again
    }

    private void rescheduleIdle() {
        handler.removeCallbacks(idleSaver);
        if (!paused) handler.postDelayed(idleSaver, IDLE_MS);
    }

    private void startSaver() {
        if (paused || isFinishing()) return;
        if (!hasWindowFocus()) {        // a menu is open: the person is still here, try again later
            rescheduleIdle();
            return;
        }
        startActivity(new Intent(this, ScreensaverActivity.class));
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
        ensureVisible(item.getTop(), item.getBottom());
    }

    private void ensureVisible(int itemTop, int itemBottom) {
        int topLimit, bottomLimit;
        if (arranging) {
            topLimit = (int) (12 * density);
            bottomLimit = grid.getHeight() - (int) (16 * density);
        } else {
            topLimit = panelTopPx() + (int) (10 * density);
            bottomLimit = grid.getHeight() - (int) ((PANEL_BOTTOM_DP + 8) * density);
        }
        int dy = 0;
        if (itemTop < topLimit) dy = itemTop - topLimit;
        else if (itemBottom > bottomLimit) dy = itemBottom - bottomLimit;
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
        if (restorePending) return;                    // focus jumps while the list rebinds; the restore sets the real one
        if (snapActive) endSnap();
        lastFocused = position;
        lastFocusedKey = adapter.keyAt(position);
        boolean leavingAll = !arranging && allAppsMode && position < COLUMNS;
        boolean glassWasShown = glassShown;
        glass.setColor(adapter.ambientColorAt(position), glassWasShown);   // cursor takes the app's colour
        Rect r = moveGlassTo(card, !leavingAll);      // leaving the list: snapToDock moves the glass itself
        View item = (View) card.getParent();
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
                // The list opens with its first row just inside the glass panel (unless we are returning
                // from an app and the list is already where it was).
                if (!keepScroll) scrollGridBy(item.getTop() - firstRowGap(position) - allTopPx());
            } else {
                ensureVisible(item.getTop() - firstRowGap(position), item.getBottom());
            }
        }
    }

    // ---- top shelf -------------------------------------------------------------------------------

    private PillIcon inputsIcon, bluetoothIcon;

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
    private static final long[] STYLE_MS = {0, 4800, 1500, 9000, 7000, 24000};
    private static final int[] STYLE_NAMES = {R.string.fx_steady, R.string.fx_breathing, R.string.fx_heartbeat,
            R.string.fx_drift, R.string.fx_shimmer, R.string.fx_rainbow};

    private static float bump(float t, float center, float width) {
        float x = (t - center) / width;
        return (float) Math.exp(-x * x);
    }

    private int shiftHue(int color, float degrees) {
        Color.colorToHSV(color, hsvTmp);
        hsvTmp[0] = ((hsvTmp[0] + degrees) % 360f + 360f) % 360f;
        return Color.HSVToColor(hsvTmp);
    }

    /** Soft radial glow behind the dock, darker than the app's own colour so it stays easy on the eyes. */
    private void setAmbient(int color) {
        ambientColor = color;
        boolean music = musicColor != 0;
        float k = 1f;            // brightness
        float swing = 1f;        // size
        float cx = 0.32f;        // horizontal centre
        int shown = color;
        if (ambientPulse != null) {
            float t = pulseT;
            switch (fxStyle) {
                case 1: {        // breathing: swells and eases back
                    float v = 0.5f - 0.5f * (float) Math.cos(2 * Math.PI * t);
                    k = music ? 0.8f + 0.3f * v : 0.88f + 0.2f * v;
                    swing = music ? 0.95f + 0.1f * v : 0.97f + 0.06f * v;
                    break;
                }
                case 2: {        // heartbeat: a strong beat, a softer one, then rest
                    float v = Math.max(bump(t, 0.12f, 0.06f), 0.6f * bump(t, 0.34f, 0.06f));
                    k = 0.78f + 0.42f * v;
                    swing = 0.95f + 0.08f * v;
                    break;
                }
                case 3:          // drift: the glow sways slowly from side to side
                    cx = 0.32f + 0.16f * (float) Math.sin(2 * Math.PI * t);
                    break;
                case 4:          // shimmer: the colour wavers a little either way
                    shown = shiftHue(color, 30f * (float) Math.sin(2 * Math.PI * t));
                    k = 1.05f;
                    break;
                case 5:          // rainbow: the colour slowly goes all the way round
                    shown = shiftHue(color, 360f * t);
                    break;
                default:
                    break;
            }
        }
        k *= fxLevel == 0 ? 0.6f : fxLevel == 2 ? 1.4f : 1f;
        int c = darken(shown) & 0x00FFFFFF;
        int a1 = Math.min(255, (int) (0x50 * k));
        int a2 = Math.min(255, (int) (0x20 * k));
        ambientGlow.setColors(new int[]{(a1 << 24) | c, (a2 << 24) | c, c});
        ambientGlow.setGradientCenter(cx, 0.42f);
        if (ambientBaseRadius > 0) ambientGlow.setGradientRadius(ambientBaseRadius * swing);
        ambient.invalidate();
    }

    /** Applies the glow settings: on/off and which style runs. */
    private void syncPulse() {
        ambient.setVisibility(fxGlowOn ? View.VISIBLE : View.GONE);
        stopAmbientPulse();                       // restart with the right cycle length
        if (fxGlowOn && fxStyle != 0) startAmbientPulse();
        if (ambientBaseRadius > 0) setAmbient(ambientColor);
    }

    private void startAmbientPulse() {
        if (ambientPulse != null || fxStyle <= 0 || fxStyle >= STYLE_MS.length) return;
        ambientPulse = ValueAnimator.ofFloat(0f, 1f);
        ambientPulse.setDuration(STYLE_MS[fxStyle]);
        ambientPulse.setRepeatCount(ValueAnimator.INFINITE);
        ambientPulse.setRepeatMode(ValueAnimator.RESTART);
        ambientPulse.setInterpolator(new android.view.animation.LinearInterpolator());
        ambientPulse.addUpdateListener(a -> {
            pulseT = (Float) a.getAnimatedValue();
            long now = android.os.SystemClock.uptimeMillis();
            if (now - lastPulseDraw < 66) return;      // ~15 fps is plenty for a slow glow
            lastPulseDraw = now;
            if (heroShown) setAmbient(ambientColor);
        });
        ambientPulse.start();
    }

    private void stopAmbientPulse() {
        if (ambientPulse != null) {
            ambientPulse.cancel();
            ambientPulse = null;
        }
        pulseT = 0f;
        if (ambientBaseRadius > 0) {
            ambientGlow.setGradientRadius(ambientBaseRadius);
            ambientGlow.setGradientCenter(0.32f, 0.42f);
        }
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
            if (position < 2 * COLUMNS && continueActive()) {   // first row of apps: up to Continue watching
                focusPoster(nearestPoster(centerX(cardAt(position))));
                return true;
            }
            target = position - COLUMNS;
        } else {
            if (position < COLUMNS && continueActive()) {   // dock: down to Continue watching first
                focusPoster(nearestPoster(centerX(cardAt(position))));
                return true;
            }
            int lastRowStart = ((count - 1) / COLUMNS) * COLUMNS;
            if (position >= lastRowStart) return true;     // already on the last row
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
        grid.invalidateItemDecorations();
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
        grid.invalidateItemDecorations();
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
        addInputRow(rows, popup, R.drawable.ic_wallpaper, getString(R.string.wallpaper_tab), -1, this::showWallpaperMenu);
        addInputRow(rows, popup, R.drawable.ic_hide, getString(R.string.hide_apps), -1, this::showHideApps);
        addInputRow(rows, popup, R.drawable.ic_widgets, getString(R.string.widgets), -1, this::showWidgets);
        addInputRow(rows, popup, R.drawable.ic_palette, getString(R.string.fx_title), -1, this::showEffects);
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

    // ---- double tap on the settings icon: clear cache -----------------------------------------------

    /** Double tap on the settings icon: clear the cache right away. */
    private void quickClearCache() {
        View gear = findViewById(R.id.btnSettings);
        gear.animate().rotationBy(360f).setDuration(600).start();
        Toast.makeText(this, R.string.tc_working, Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            long before = TvTools.storage()[1];
            boolean ok = true;
            try {
                TvTools.clearCache(this);
            } catch (Exception e) {
                ok = false;
            }
            long after = TvTools.storage()[1];
            final boolean fok = ok;
            final long freed = after - before;
            runOnUiThread(() -> {
                if (!fok) Toast.makeText(this, R.string.tc_refused, Toast.LENGTH_SHORT).show();
                else if (freed > 0) Toast.makeText(this, getString(R.string.tc_cache_done, TvTools.fmt(after)), Toast.LENGTH_LONG).show();
                else Toast.makeText(this, R.string.tc_cache_none, Toast.LENGTH_LONG).show();
            });
        }).start();
    }

    /** One tab for the wallpaper: choose wallpaper and the depth foreground PNG. */
    private void showWallpaperMenu() {
        View anchor = findViewById(R.id.btnSettings);
        View panel = getLayoutInflater().inflate(R.layout.popup_apps, root, false);
        panel.findViewById(R.id.title).setVisibility(View.GONE);
        LinearLayout rows = panel.findViewById(R.id.rows);
        int minWidth = (int) (260 * density);
        final PopupWindow popup = newDropdown(panel, minWidth);
        addInputRow(rows, popup, R.drawable.ic_wallpaper, getString(R.string.choose_wallpaper), -1, this::chooseWallpaper);
        addInputRow(rows, popup, R.drawable.ic_wallpaper, getString(R.string.depth_front), -1, this::chooseFront);
        if (WallpaperStore.hasFront(this)) {
            addInputRow(rows, popup, R.drawable.ic_reset, getString(R.string.depth_remove), -1, this::removeFront);
        }
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

    /** Glow & effects: breathing on/off and how strong the background glow is. */
    private void showEffects() {
        View anchor = findViewById(R.id.btnSettings);
        View panel = getLayoutInflater().inflate(R.layout.popup_apps, root, false);
        panel.findViewById(R.id.title).setVisibility(View.GONE);
        LinearLayout rows = panel.findViewById(R.id.rows);
        int minWidth = (int) (300 * density);
        final PopupWindow popup = newDropdown(panel, minWidth);

        final View glowRow = getLayoutInflater().inflate(R.layout.item_input, rows, false);
        ((ImageView) glowRow.findViewById(R.id.icon)).setImageResource(R.drawable.ic_palette);
        ((TextView) glowRow.findViewById(R.id.title)).setText(R.string.fx_glow);
        updateWidgetRow(glowRow, fxGlowOn);
        rows.addView(glowRow);

        final View styleRow = getLayoutInflater().inflate(R.layout.item_input, rows, false);
        ((ImageView) styleRow.findViewById(R.id.icon)).setImageResource(R.drawable.ic_palette);
        ((TextView) styleRow.findViewById(R.id.title)).setText(R.string.fx_style);
        updateStyleRow(styleRow);
        rows.addView(styleRow);

        final View strength = getLayoutInflater().inflate(R.layout.item_input, rows, false);
        ((ImageView) strength.findViewById(R.id.icon)).setImageResource(R.drawable.ic_palette);
        ((TextView) strength.findViewById(R.id.title)).setText(R.string.fx_strength);
        updateStrengthRow(strength);
        rows.addView(strength);

        glowRow.setOnClickListener(v -> {
            fxGlowOn = !fxGlowOn;
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean("fx_glow", fxGlowOn).apply();
            updateWidgetRow(glowRow, fxGlowOn);
            updateStyleRow(styleRow);
            syncPulse();
        });
        styleRow.setOnClickListener(v -> {
            fxStyle = (fxStyle + 1) % STYLE_NAMES.length;
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().putInt("fx_style", fxStyle).apply();
            updateStyleRow(styleRow);
            syncPulse();
        });
        strength.setOnClickListener(v -> {
            fxLevel = (fxLevel + 1) % 3;
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().putInt("fx_level", fxLevel).apply();
            updateStrengthRow(strength);
            if (ambientBaseRadius > 0) setAmbient(ambientColor);
        });

        fitScroll(panel);
        showDropdown(popup, panel, anchor, minWidth, true);
    }

    private void updateStyleRow(View row) {
        ((TextView) row.findViewById(R.id.status)).setText(STYLE_NAMES[fxStyle]);
        row.findViewById(R.id.dot).setVisibility(View.VISIBLE);
        row.setAlpha(fxGlowOn ? 1f : 0.45f);
    }

    private void updateStrengthRow(View row) {
        ((TextView) row.findViewById(R.id.status)).setText(
                fxLevel == 0 ? R.string.fx_soft : fxLevel == 2 ? R.string.fx_strong : R.string.fx_normal);
        row.findViewById(R.id.dot).setVisibility(View.VISIBLE);
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

    private void chooseFront() {
        Intent pick = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        pick.addCategory(Intent.CATEGORY_OPENABLE);
        pick.setType("image/*");
        pick.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            startActivityForResult(pick, REQUEST_FRONT);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, R.string.not_available, Toast.LENGTH_SHORT).show();
        }
    }

    private void removeFront() {
        WallpaperStore.deleteFront(this);
        wallpaperFront.setImageDrawable(null);
        wallpaperFront.setVisibility(View.GONE);
        wallpaperFrontTop.setImageDrawable(null);
        wallpaperFrontTop.setVisibility(View.GONE);
    }

    /** Shows the transparent depth PNG over the glow (and over the picture), under the apps. */
    private void applyFront() {
        try {
            Uri uri = WallpaperStore.frontUri(this);
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            try (java.io.InputStream in = getContentResolver().openInputStream(uri)) {
                BitmapFactory.decodeStream(in, null, bounds);
            }
            int tw = Math.max(1, getResources().getDisplayMetrics().widthPixels);
            int th = Math.max(1, getResources().getDisplayMetrics().heightPixels);
            int sample = 1;
            while (bounds.outWidth / sample > tw || bounds.outHeight / sample > th) sample *= 2;
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inSampleSize = sample;                       // ARGB_8888: keeps the transparency
            Bitmap bitmap;
            try (java.io.InputStream in = getContentResolver().openInputStream(uri)) {
                bitmap = BitmapFactory.decodeStream(in, null, options);
            }
            if (bitmap == null) throw new IllegalArgumentException("Unable to decode image");
            wallpaperFront.setImageBitmap(bitmap);
            // The picture behind it is dimmed a bit; match that so the cut-out does not look pasted on.
            wallpaperFront.setColorFilter(0x73000000, android.graphics.PorterDuff.Mode.SRC_ATOP);
            wallpaperFront.setVisibility(View.VISIBLE);
            // Second copy over the clock and date: they end up behind the character, like on a phone lock screen.
            wallpaperFrontTop.setImageBitmap(bitmap);
            wallpaperFrontTop.setColorFilter(0x73000000, android.graphics.PorterDuff.Mode.SRC_ATOP);
            wallpaperFrontTop.setVisibility(View.VISIBLE);
            root.post(this::updateFrontTopClip);
        } catch (Exception | OutOfMemoryError e) {
            wallpaperFront.setVisibility(View.GONE);
            Toast.makeText(this, R.string.wallpaper_failed, Toast.LENGTH_SHORT).show();
        }
    }

    private void updateFrontTopClip() {
        View left = findViewById(R.id.leftBlock);
        if (left == null || wallpaperFrontTop == null || left.getWidth() == 0) return;
        Rect r = new Rect(0, 0, left.getWidth(), left.getHeight());
        root.offsetDescendantRectToMyCoords(left, r);
        wallpaperFrontTop.setClipBounds(r);
    }

    private void restoreFront() {
        if (!WallpaperStore.hasFront(this) || wallpaperFront.getVisibility() == View.VISIBLE) return;
        applyFront();
    }

    private void restoreWallpaper() {
        restoreFront();
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

    // ---- Continue watching (row above the apps in All apps) ---------------------------------------

    private RecyclerView continueRow;
    private int continueGapPx, continueRowPx;

    /** True while the row is part of the layout: there is something to show and we are not arranging. */
    private boolean continueActive() {
        return continueAdapter != null && continueAdapter.getItemCount() > 0 && !arranging
                && adapter != null && adapter.getItemCount() > COLUMNS;
    }

    /** Extra space above this app position (only the first row of apps has any). */
    private int firstRowGap(int position) {
        return position >= COLUMNS && position < 2 * COLUMNS && continueActive() ? continueGapPx : 0;
    }

    private void setupContinueRow() {
        continueAdapter = new ContinueAdapter(this, this);
        continueRow = new RecyclerView(this);
        continueRow.setLayoutManager(new LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false));
        continueRow.setClipChildren(false);
        continueRow.setClipToPadding(false);
        continueRow.setItemAnimator(null);
        continueRow.setOverScrollMode(View.OVER_SCROLL_NEVER);
        continueRow.setHorizontalScrollBarEnabled(false);
        continueRow.setAdapter(continueAdapter);
        continueRow.setAlpha(0f);
        continueRow.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        continueRow.setPadding((int) (56 * density), 0, (int) (56 * density), 0);
        root.addView(continueRow, root.indexOfChild(grid) + 1,
                new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (int) (200 * density)));
        // The row floats over the gap above the first row of apps and follows the grid (scroll, fades, clip).
        grid.getViewTreeObserver().addOnPreDrawListener(() -> {
            syncContinueRow();
            return true;
        });
        continueRow.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrollStateChanged(@NonNull RecyclerView rv, int newState) {
                if (newState != RecyclerView.SCROLL_STATE_IDLE || !glassShown) return;
                View f = getCurrentFocus();
                if (f != null && f.getId() == R.id.card && ((View) f.getParent()).getParent() == continueRow) {
                    moveGlassTo(f, true);
                }
            }

            @Override
            public void onScrolled(@NonNull RecyclerView rv, int dx, int dy) {
                if (!glassShown || dx == 0) return;
                View f = getCurrentFocus();
                if (f == null || f.getId() != R.id.card || ((View) f.getParent()).getParent() != continueRow) return;
                glassTargetX -= dx;
                if (springX.isRunning()) springX.animateToFinalPosition(glassTargetX);
                else glass.setTranslationX(glassTargetX);
            }
        });
    }

    /** Poster size: three full posters and a peek of the next one fit across the screen. */
    private void layoutContinue() {
        if (continueRow == null || grid == null || grid.getWidth() == 0) return;
        int contentW = grid.getWidth() - grid.getPaddingLeft() - grid.getPaddingRight();
        int gap = (int) (18 * density);
        int w = (int) ((contentW - 2 * gap) / 3.2f);
        int h = Math.round(w * 9f / 16f);
        continueAdapter.setSize(w, h, gap);
        int block = h + (int) (44 * density);                  // 8 air + poster + title line + 8 air
        continueGapPx = block + (int) (6 * density);
        if (continueRowPx != block) {
            continueRowPx = block;
            ViewGroup.LayoutParams lp = continueRow.getLayoutParams();
            lp.height = block;
            continueRow.setLayoutParams(lp);
            grid.invalidateItemDecorations();
        }
    }

    /** Keeps the row glued to the gap above the first row of apps. Only draw properties change here. */
    private void syncContinueRow() {
        if (continueRow == null) return;
        boolean active = continueActive();
        // In dock state the row is invisible but overlaps the dock's neighbourhood, so keys like Up must not find it.
        int want = active && allAppsMode ? ViewGroup.FOCUS_AFTER_DESCENDANTS : ViewGroup.FOCUS_BLOCK_DESCENDANTS;
        if (continueRow.getDescendantFocusability() != want) continueRow.setDescendantFocusability(want);
        RecyclerView.ViewHolder vh = active ? grid.findViewHolderForAdapterPosition(COLUMNS) : null;
        if (vh == null) {
            if (continueRow.getAlpha() != 0f) continueRow.setAlpha(0f);
            return;
        }
        float y = grid.getTop() + grid.getTranslationY() + vh.itemView.getTop() - continueGapPx;
        if (continueRow.getTranslationY() != y) continueRow.setTranslationY(y);
        float alpha = grid.getAlpha() * (allAppsPanel != null ? allAppsPanel.getAlpha() : 1f);
        if (continueRow.getAlpha() != alpha) continueRow.setAlpha(alpha);
        Rect gc = grid.getClipBounds();
        Rect want2 = gc == null ? null : new Rect(gc.left, Math.round(gc.top - y), gc.right, Math.round(gc.bottom - y));
        Rect have = continueRow.getClipBounds();
        if (want2 == null ? have != null : !want2.equals(have)) continueRow.setClipBounds(want2);
    }

    private View cardAt(int position) {
        RecyclerView.ViewHolder vh = grid.findViewHolderForAdapterPosition(position);
        return vh == null ? null : vh.itemView.findViewById(R.id.card);
    }

    private float centerX(View v) {
        if (v == null) return root.getWidth() / 2f;
        Rect r = new Rect(0, 0, v.getWidth(), v.getHeight());
        root.offsetDescendantRectToMyCoords(v, r);
        return r.exactCenterX();
    }

    /** The poster whose centre is closest to this x position. */
    private int nearestPoster(float cx) {
        int best = 0;
        float bestDist = Float.MAX_VALUE;
        for (int i = 0; i < continueRow.getChildCount(); i++) {
            View ch = continueRow.getChildAt(i);
            int pos = continueRow.getChildAdapterPosition(ch);
            View card = ch.findViewById(R.id.card);
            if (pos < 0 || card == null) continue;
            float dist = Math.abs(centerX(card) - cx);
            if (dist < bestDist) {
                bestDist = dist;
                best = pos;
            }
        }
        return best;
    }

    @Override
    public void onPosterFocused(int index, View card, int color) {
        if (restorePending || arranging) return;
        if (snapActive) endSnap();
        boolean entering = !allAppsMode;
        allAppsMode = true;
        syncContinueRow();                                 // the row must be in place before the cursor is measured against it
        glass.setColor(color, glassShown);                 // red cursor on the posters
        moveGlassTo(card, true);
        refreshChrome();
        hideHero();
        RecyclerView.ViewHolder vh = grid.findViewHolderForAdapterPosition(COLUMNS);
        if (vh == null) return;
        int rowTop = vh.itemView.getTop() - continueGapPx;
        if (entering) scrollGridBy(rowTop - allTopPx());
        else ensureVisible(rowTop, rowTop + continueRowPx);
    }

    @Override
    public boolean onPosterKey(int index, int keyCode) {
        if (arranging) return true;
        View card = continueRow.findViewHolderForAdapterPosition(index) == null ? null
                : continueRow.findViewHolderForAdapterPosition(index).itemView.findViewById(R.id.card);
        float cx = centerX(card);
        if (keyCode == KeyEvent.KEYCODE_DPAD_UP) {
            // Back up to the dock tile above.
            int best = 0;
            float bestDist = Float.MAX_VALUE;
            for (int i = 0; i < Math.min(COLUMNS, adapter.getItemCount()); i++) {
                View c = cardAt(i);
                if (c == null) continue;
                float dist = Math.abs(centerX(c) - cx);
                if (dist < bestDist) {
                    bestDist = dist;
                    best = i;
                }
            }
            focusTile(best);
        } else {
            // Down into the first row of apps, the one nearest below.
            int best = COLUMNS;
            float bestDist = Float.MAX_VALUE;
            for (int i = COLUMNS; i < Math.min(2 * COLUMNS, adapter.getItemCount()); i++) {
                View c = cardAt(i);
                if (c == null) continue;
                float dist = Math.abs(centerX(c) - cx);
                if (dist < bestDist) {
                    bestDist = dist;
                    best = i;
                }
            }
            focusTile(best);
        }
        return true;
    }

    private void focusPoster(final int index) {
        final int i = Math.max(0, Math.min(index, continueAdapter.posterCount() - 1));
        continueRow.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);   // we are going there on purpose
        RecyclerView.ViewHolder vh = continueRow.findViewHolderForAdapterPosition(i);
        if (vh != null) {
            vh.itemView.findViewById(R.id.card).requestFocus();
            return;
        }
        continueRow.scrollToPosition(i);
        continueRow.post(() -> {
            continueRow.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
            RecyclerView.ViewHolder h = continueRow.findViewHolderForAdapterPosition(i);
            if (h != null) h.itemView.findViewById(R.id.card).requestFocus();
        });
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

    /** Keeps a list of every Bluetooth device that has been connected here, to show in the Bluetooth menu. */
    private void rememberBt(String address, String name) {
        if (address == null || name == null || name.trim().isEmpty()) return;
        try {
            SharedPreferences sp = getSharedPreferences(PREFS, MODE_PRIVATE);
            Set<String> old = sp.getStringSet("bt_history", null);
            Set<String> next = new LinkedHashSet<>();
            if (old != null) for (String e : old) if (!e.startsWith(address + "\t")) next.add(e);
            next.add(address + "\t" + name.trim());
            while (next.size() > 20) next.remove(next.iterator().next());
            sp.edit().putStringSet("bt_history", next).apply();
        } catch (RuntimeException ignored) {
        }
    }

    private static final class BtDev {
        final String name;
        final String address;
        boolean connected;
        boolean remembered;   // not paired any more, but it was connected here before

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
                    if (b.connected) rememberBt(b.address, b.name);
                    devices.add(b);
                }
            } catch (RuntimeException ignored) {
            }
            // Devices that were connected here before but are no longer paired.
            Set<String> seen = new HashSet<>();
            for (BtDev d : devices) seen.add(d.address);
            Set<String> history = getSharedPreferences(PREFS, MODE_PRIVATE).getStringSet("bt_history", null);
            if (history != null) {
                for (String e : history) {
                    String[] sp = e.split("\t", 2);
                    if (sp.length < 2 || seen.contains(sp[0])) continue;
                    BtDev d = new BtDev(sp[1], sp[0]);
                    d.remembered = true;
                    devices.add(d);
                }
            }
            Collections.sort(devices, (a, b) -> {
                if (a.connected != b.connected) return a.connected ? -1 : 1;
                if (a.remembered != b.remembered) return a.remembered ? 1 : -1;
                return a.name.compareToIgnoreCase(b.name);
            });
            for (BtDev b : devices) {
                String status = b.connected ? getString(R.string.connected)
                        : b.remembered ? getString(R.string.bt_history) : getString(R.string.bt_paired);
                View row = addBtRow(rows, popup, b.name, status,
                        b.connected, b.connected ? 1f : b.remembered ? 0.5f : 0.65f);
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

    /** Song position for the Now playing card. */
    private void updateNowProgress() {
        MediaController c = playingController;
        if (c == null) return;
        PlaybackState st = c.getPlaybackState();
        MediaMetadata m = c.getMetadata();
        if (st == null || m == null) return;
        long dur = m.getLong(MediaMetadata.METADATA_KEY_DURATION);
        long pos = st.getPosition();
        if (st.getState() == PlaybackState.STATE_PLAYING) {
            pos += (long) ((SystemClock.elapsedRealtime() - st.getLastPositionUpdateTime()) * st.getPlaybackSpeed());
        }
        if (dur > 0) pos = Math.max(0, Math.min(pos, dur));
        widgets.setNowProgress(pos, dur);
    }

    private void stopMusicWatch() {
        handler.removeCallbacks(musicCycle);
        handler.removeCallbacks(progressTick);
        playingController = null;
        stopAmbientPulse();
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
        playingController = null;
        for (MediaController c : watched) {
            PlaybackState st = c.getPlaybackState();
            if (st == null || st.getState() != PlaybackState.STATE_PLAYING) continue;
            meta = c.getMetadata();
            playing = true;
            playingController = c;
            break;
        }
        handler.removeCallbacks(progressTick);
        if (playingController != null) {
            updateNowProgress();
            handler.postDelayed(progressTick, 1000);
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
        if (changed && heroShown) animateAmbient(ambientFor(lastFocused), 4500);   // slow, soft fade
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
        // Rows ease in one after the other.
        for (int i = 0; i < rows.getChildCount(); i++) {
            View r = rows.getChildAt(i);
            r.setAlpha(0f);
            r.setTranslationY(-6 * density);
            r.animate().alpha(1f).translationY(0f).setStartDelay(40L + i * 28L).setDuration(220)
                    .setInterpolator(new DecelerateInterpolator(1.6f)).start();
        }
        openPopup = popup;
        if (rows.getChildCount() > 0) rows.getChildAt(0).requestFocus();
    }

    private PopupWindow openPopup;

    /** Closes the open dropdown, if any. Returns true when there was one. */
    private boolean dismissOpenPopup() {
        PopupWindow p = openPopup;
        openPopup = null;
        if (p != null && p.isShowing()) {
            p.dismiss();
            return true;
        }
        return false;
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        dismissOpenPopup();         // Home pressed while a menu is open
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
            // Let this popup finish closing first: opening the next one in the same frame left it without
            // key focus, so Back and Home did nothing.
            handler.postDelayed(action, 160);
        });
        rows.addView(row);
    }
}
