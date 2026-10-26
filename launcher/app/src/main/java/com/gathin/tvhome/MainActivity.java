package com.gathin.tvhome;

import android.Manifest;
import android.animation.ArgbEvaluator;
import android.animation.ObjectAnimator;
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
import android.graphics.RectF;
import android.graphics.Typeface;
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
import android.view.animation.AccelerateInterpolator;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.AccelerateDecelerateInterpolator;
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
    private static final int REQUEST_DOCK_PNG = 74;
    private static final String KEY_DOCK_PNG_ON = "dock_png_on";
    static final String PREFS = "launcher_preferences";
    private static final String WALLPAPER_URI = "wallpaper_uri";

    private AppAdapter adapter;
    private RecyclerView grid;
    private DockDecoration dockDecoration;
    private Bitmap veilBackdrop;            // lightly blurred wallpaper behind open menus
    private Bitmap menuBackdrop;            // blurred wallpaper, for the frosted pop-up menus
    private FrameLayout root;
    private View header;
    private WidgetPanel widgets;
    private WidgetPanel widgetsLeft;
    private LyricsView lyricsView;
    private String lyricsKey = "";
    private Lyrics lyricsData;
    private boolean lyricsFits;
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
    private int fxColor = 0;                // 0 = follow the app, else a fixed colour from FX_COLORS
    private int fxPos = 1;                  // where the glow sits: left, dock, centre, right
    private int fxParticles = 0;            // 0 off, then stars, snow, fireflies, rain, bubbles, embers
    private int widgetsDepth;               // widgets behind the depth PNG: 0 off, 1 right, 2 left, 3 both
    private ParticlesView particles;
    private DepthOverlayView depthWidgets;
    private DockPngView dockPng;
    private boolean dockPngOn;
    private AppsPanelDecoration appsPanel;
    private float appsPanelFade;
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
    /** Always-running line animation inside the corner clock's digits (see startClockLoop). */
    private ValueAnimator clockLoop;
    private ObjectAnimator dividerPulse;
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
    /** True while the dock rises after the fade; the continue row must stay hidden then. */
    private boolean snapRising;
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
                    if (d != null) rememberBt(d.getAddress(), btLabel(d));
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
            tintPills(musicColor);
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
            // Posters older than 48 hours drop out (only while the row is out of sight, so nothing moves under the cursor).
            if (!allAppsMode && !arranging && continueAdapter.refresh()) layoutContinue();
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
        widgetsLeft = findViewById(R.id.widgetsLeft);
        widgets.setSide(false);
        widgetsLeft.setSide(true);
        widgets.setPeer(widgetsLeft);
        widgetsLeft.setPeer(widgets);
        lyricsView = findViewById(R.id.lyricsView);
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
        particles = findViewById(R.id.particles);
        depthWidgets = findViewById(R.id.depthWidgets);
        dockPng = findViewById(R.id.dockPng);
        dockPngOn = getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(KEY_DOCK_PNG_ON, false);
        loadDockPng();                                           // the saved PNG is back right after a restart
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
        appsPanel = new AppsPanelDecoration(COLUMNS, density);   // dark panel behind the All apps rows
        grid.addItemDecoration(appsPanel);
        // Room above the first row of apps for the Continue watching row (which floats over that gap).
        grid.addItemDecoration(new RecyclerView.ItemDecoration() {
            @Override
            public void getItemOffsets(@NonNull Rect outRect, @NonNull View view, @NonNull RecyclerView parent,
                                       @NonNull RecyclerView.State state) {
                int pos = parent.getChildAdapterPosition(view);
                if (pos >= COLUMNS && pos < 2 * COLUMNS && sectionsActive()) outRect.top = gapTotalPx();
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
        particles.animate().cancel();
        if (ambientAnim != null) ambientAnim.cancel();
        ambient.setAlpha(0f);
        particles.setAlpha(0f);
        // Start in the dock state; focusing a tile in the app list switches to the blurred state again.
        // (If we left from the full app list, its look stays exactly as it was: no flash of the dock state.)
        arranging = false;
        snapActive = false;
        allAppsPanel.animate().cancel();
        wallpaperBlurView.animate().cancel();
        allAppsScrim.animate().cancel();
        wallpaperFront.animate().cancel();
        wallpaperFrontTop.animate().cancel();
        depthWidgets.animate().cancel();
        header.animate().cancel();
        allAppsMode = keepAll;
        overlayApplied = keepAll;
        allUiApplied = keepAll;
        allAppsPanel.setAlpha(keepAll ? 1f : 0f);
        wallpaperBlurView.setAlpha(keepAll ? 1f : 0f);
        allAppsScrim.setAlpha(keepAll ? 1f : 0f);
        wallpaperFront.setAlpha(keepAll ? 0f : 1f);
        wallpaperFrontTop.setAlpha(keepAll ? 0f : 1f);
        depthWidgets.setAlpha(keepAll ? 0f : 1f);
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
        if (keepAll) stopClockLoop();
        else startClockLoop();
        if (wallpaperView.getVisibility() != View.VISIBLE) restoreWallpaper();   // safety net after a restart
        widgets.animate().cancel();
        widgets.setAlpha(keepAll ? 0f : 1f);
        widgets.setVisibility(keepAll ? View.INVISIBLE : View.VISIBLE);
        widgetsLeft.animate().cancel();
        widgetsLeft.setAlpha(keepAll ? 0f : 1f);
        widgetsLeft.setVisibility(keepAll ? View.INVISIBLE : View.VISIBLE);
        widgets.refresh();
        positionWidgets();
        applyLyricsVisibility();
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
        fxColor = Math.max(0, Math.min(FX_COLORS.length - 1, fx.getInt("fx_color", 0)));
        fxPos = Math.max(0, Math.min(FX_POS.length - 1, fx.getInt("fx_pos", 1)));
        fxParticles = Math.max(0, Math.min(ParticlesView.COUNT - 1, fx.getInt("fx_particles", 0)));
        widgetsDepth = Math.max(0, Math.min(3, fx.getInt("widgets_depth_side", 0)));
        particles.setMode(fxParticles);
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
        stopClockLoop();
        handler.removeCallbacks(idleSaver);
        if (controlCenter != null) controlCenter.dismissNow();
        if (openPopup instanceof AnimPopup) ((AnimPopup) openPopup).dismissNow();
        if (menuVeil != null) {
            menuVeil.animate().cancel();
            menuVeil.setVisibility(View.GONE);
        }
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
        if (requestCode == REQUEST_DOCK_PNG) {
            try {
                WallpaperStore.copyDock(this, uri);
                dockPng.setBitmap(null);
                loadDockPng();
                if (!dockPng.hasBitmap()) throw new IllegalArgumentException("Unable to decode image");
                setDockPngOn(true);
            } catch (Exception | OutOfMemoryError e) {
                Toast.makeText(this, R.string.wallpaper_failed, Toast.LENGTH_SHORT).show();
            }
            return;
        }
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
            depthWidgets.animate().alpha(overlay ? 0f : 1f).setDuration(350).start();
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
        widgetsLeft.animate().cancel();
        if (show) {
            header.setVisibility(View.VISIBLE);
            header.animate().alpha(1f).setDuration(300).start();
            startClockLoop();
            widgets.setVisibility(View.VISIBLE);
            widgets.animate().alpha(1f).setDuration(300).start();
            widgetsLeft.setVisibility(View.VISIBLE);
            widgetsLeft.animate().alpha(1f).setDuration(300).start();
        } else {
            header.animate().alpha(0f).setDuration(180).withEndAction(() -> {
                if (overlayApplied) {
                    header.setVisibility(View.INVISIBLE);
                    stopClockLoop();
                }
            }).start();
            widgets.animate().alpha(0f).setDuration(180).withEndAction(() -> {
                if (overlayApplied) widgets.setVisibility(View.INVISIBLE);
            }).start();
            widgetsLeft.animate().alpha(0f).setDuration(180).withEndAction(() -> {
                if (overlayApplied) widgetsLeft.setVisibility(View.INVISIBLE);
            }).start();
        }
        applyLyricsVisibility();
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

    /** Puts the widget cards (right and left column) just above the dock, and drops the ones that do not fit. */
    private void positionWidgets() {
        if (widgets == null || root.getHeight() == 0) return;
        float dockTop = lastDockTop > 0 ? lastDockTop : root.getHeight() * 0.62f;
        float top = header.getBottom() + 4 * density;
        float bottom = dockTop - 26 * density;
        float step = (WidgetPanel.CARD_H_DP + WidgetPanel.GAP_DP) * density;
        int fit = (int) Math.max(0, Math.floor((bottom - top + WidgetPanel.GAP_DP * density) / step));
        widgets.setMaxCards(fit);
        widgets.setY(bottom - widgets.contentHeight());
        widgetsLeft.setMaxCards(fit);
        widgetsLeft.setY(bottom - widgetsLeft.contentHeight());
        positionLyrics(top, bottom);
        updateDepthWidgets();
    }

    /** Draws the depth PNG over the widget columns (when switched on), so the widgets sit behind it. */
    private void updateDepthWidgets() {
        if (depthWidgets == null || widgets == null) return;
        boolean on = widgetsDepth != 0 && depthWidgets.hasBitmap();
        depthWidgets.setVisibility(on ? View.VISIBLE : View.GONE);
        if (!on) return;
        java.util.List<Rect> list = new java.util.ArrayList<>();
        for (WidgetPanel panel : new WidgetPanel[]{widgets, widgetsLeft}) {
            boolean isLeftPanel = panel == widgetsLeft;
            if (!(widgetsDepth == 3 || (widgetsDepth == 2 && isLeftPanel) || (widgetsDepth == 1 && !isLeftPanel))) continue;
            int h = panel.contentHeight();
            if (h <= 0 || panel.getWidth() <= 0) continue;
            int left = (int) panel.getX();
            int top = (int) panel.getY();
            list.add(new Rect(left, top, left + panel.getWidth(), top + h));
        }
        depthWidgets.setRects(list);
    }

    /**
     * The Spotify lyrics take the free space on the side they were placed (left by default), above the dock and
     * above any cards on the same side. They sit under the depth PNG, so the wallpaper's "character" covers them.
     */
    private void positionLyrics(float top, float bottom) {
        if (lyricsView == null) return;
        boolean onLeft = widgets.state("lyrics") != 1;
        WidgetPanel column = onLeft ? widgetsLeft : widgets;
        float lyricsBottom = column.contentHeight() > 0 ? column.getY() - 8 * density : bottom;
        float h = Math.max(0f, lyricsBottom - top);
        lyricsFits = h >= 96 * density;
        int w = (int) Math.min(root.getWidth() * 0.5f, 620 * density);
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) lyricsView.getLayoutParams();
        int gravity = (onLeft ? Gravity.START : Gravity.END) | Gravity.TOP;
        int margin = (int) (56 * density);
        if (lp.width != w || lp.height != (int) h || lp.gravity != gravity || lp.topMargin != (int) top
                || lp.getMarginStart() != margin) {
            lp.width = w;
            lp.height = (int) h;
            lp.gravity = gravity;
            lp.topMargin = (int) top;
            lp.setMarginStart(onLeft ? margin : 0);
            lp.setMarginEnd(onLeft ? 0 : margin);
            lyricsView.setLayoutParams(lp);
        }
        lyricsView.setSide(onLeft);
        applyLyricsVisibility();
    }

    /** The lyrics are shown only while a song plays, they are switched on, they fit, and the dock state is showing. */
    private void applyLyricsVisibility() {
        if (lyricsView == null || widgets == null) return;
        boolean show = widgets.isOn("lyrics") && lyricsData != null && lyricsFits && !overlayApplied && !arranging;
        lyricsView.animate().alpha(show ? 1f : 0f).setDuration(show ? 500 : 250).start();
    }

    private void maybeLoadLyrics(String title, String artist, String album, long durMs) {
        if (!widgets.isOn("lyrics") || title == null || title.trim().isEmpty()) {
            clearLyrics();
            return;
        }
        String key = Lyrics.key(artist, title);
        if (key.equals(lyricsKey)) return;
        lyricsKey = key;
        lyricsData = null;
        lyricsView.setLyrics(null);
        applyLyricsVisibility();
        Lyrics.fetch(this, artist, title, album, durMs, (k, l) -> {
            if (!k.equals(lyricsKey)) return;            // the song changed while we were looking
            lyricsData = l;
            lyricsView.setLyrics(l);
            applyLyricsVisibility();
            updateNowProgress();                         // jump straight to the line being sung
        });
    }

    private void clearLyrics() {
        lyricsKey = "";
        lyricsData = null;
        if (lyricsView != null) lyricsView.setLyrics(null);
        applyLyricsVisibility();
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
            snapRising = true;
            grid.scrollBy(0, dy);                     // the scroll listener also moves glassTargetY
            handler.removeCallbacks(clipRunnable);
            clipRunnable.run();                       // hide the rows below the dock right away
            grid.setTranslationY(lift);
            glass.setTranslationX(glassTargetX);
            glass.setTranslationY(glassTargetY + lift);
            DecelerateInterpolator rise = new DecelerateInterpolator(1.8f);
            grid.animate().alpha(1f).translationY(0f).setDuration(340).setInterpolator(rise)
                    .withEndAction(() -> {
                        snapActive = false;
                        snapRising = false;
                    }).start();
            glassShown = true;
            glass.animate().alpha(1f).translationY(glassTargetY).setDuration(340).setInterpolator(rise).start();
        }).start();
    }

    /** Stops the back-to-dock animation early (another key arrived) and puts everything back in place. */
    private void endSnap() {
        snapActive = false;
        snapRising = false;
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
        // That call ignores translation, and the Continue watching row is positioned with translationY.
        boolean poster = continueRow != null && card.getParent() instanceof View
                && ((View) card.getParent()).getParent() == continueRow;
        if (poster) {
            r.offset(Math.round(continueRow.getTranslationX()), Math.round(continueRow.getTranslationY()));
        }
        // A poster grows less than an app tile when focused; size the cursor to the poster so the gap matches the apps.
        float scale = poster ? ContinueAdapter.POSTER_FOCUS_SCALE : AppAdapter.FOCUS_SCALE;
        float pad = GlassView.PAD_DP * density;        // matches GlassView's glow inset
        float w = card.getWidth() * scale + 2 * pad;
        float h = card.getHeight() * scale + 2 * pad;
        float x = r.exactCenterX() - w / 2f;
        float y = r.exactCenterY() - h / 2f;

        ViewGroup.LayoutParams lp = glass.getLayoutParams();
        if (lp.width != (int) w || lp.height != (int) h) {
            lp.width = (int) w;
            lp.height = (int) h;
            glass.setLayoutParams(lp);
            glass.setRadius((poster ? ContinueAdapter.RADIUS_DP : 16f) * density * scale);
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

    /** Dock state: no app title any more, only the glow behind the dock follows the focused app. */
    private void showHero(int position, float dockTopInRoot) {
        lastDockTop = dockTopInRoot;
        positionWidgets();
        if (!heroShown) {
            if (ambientAnim != null) ambientAnim.cancel();
            applyAmbientColor(ambientFor(position));
            ambient.animate().alpha(1f).setDuration(600).start();
            particles.animate().alpha(1f).setDuration(900).start();
            heroShown = true;
        } else {
            animateAmbient(ambientFor(position), 600);   // cross-fade to the next app's colour
        }
    }

    /** While a Spotify song plays the glow follows the album colour; otherwise it follows the focused app. */
    private int ambientFor(int position) {
        if (fxColor > 0) return FX_COLORS[fxColor];
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
    private static final long[] STYLE_MS = {0, 4800, 1500, 9000, 7000, 24000, 3200, 8000, 30000};
    private static final float[] FX_POS = {0.12f, 0.32f, 0.5f, 0.78f};
    private static final String[] FX_POS_NAMES = {"Left", "Dock", "Centre", "Right"};
    private static final int[] FX_COLORS = {0, 0xFFEF4444, 0xFFF97316, 0xFFFBBF24, 0xFF22C55E, 0xFF06B6D4, 0xFF3B82F6,
            0xFFA855F7, 0xFFEC4899, 0xFFFFFFFF};
    private static final String[] FX_COLOR_NAMES = {"Auto (app colour)", "Red", "Orange", "Gold", "Green", "Cyan", "Blue",
            "Purple", "Pink", "White"};
    private static final String[] FX_PARTICLE_NAMES = {"Off", "Stars", "Snow", "Fireflies", "Rain", "Bubbles", "Embers", "Code rain", "Hyperspace",
            "Sakura petals", "Neon rain", "Thunderstorm", "Sky lanterns", "Magic dust", "Red spores",
            "Minecraft", "Batman", "Spider-Man", "John Wick"};
    private static final int[] STYLE_NAMES = {R.string.fx_steady, R.string.fx_breathing, R.string.fx_heartbeat,
            R.string.fx_drift, R.string.fx_shimmer, R.string.fx_rainbow, R.string.fx_flicker, R.string.fx_wave,
            R.string.fx_aurora};

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
        float basePos = FX_POS[fxPos];
        float cx = basePos;      // horizontal centre
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
                    cx = basePos + 0.16f * (float) Math.sin(2 * Math.PI * t);
                    break;
                case 4:          // shimmer: the colour wavers a little either way
                    shown = shiftHue(color, 30f * (float) Math.sin(2 * Math.PI * t));
                    k = 1.05f;
                    break;
                case 5:          // rainbow: the colour slowly goes all the way round
                    shown = shiftHue(color, 360f * t);
                    break;
                case 6: {        // flicker: like firelight, uneven and warm
                    double a = 2 * Math.PI * t;
                    float f = (float) (0.5 * Math.sin(3 * a) + 0.3 * Math.sin(7 * a + 1) + 0.2 * Math.sin(11 * a + 2));
                    k = 0.9f + 0.22f * f;
                    swing = 0.97f + 0.05f * f;
                    break;
                }
                case 7: {        // wave: the glow rolls from side to side and swells
                    double a = 2 * Math.PI * t;
                    cx = basePos + 0.2f * (float) Math.sin(a);
                    swing = 0.96f + 0.1f * (float) Math.sin(2 * a);
                    k = 1f + 0.08f * (float) Math.sin(2 * a + 1);
                    break;
                }
                case 8: {        // aurora: colour and place drift together like northern lights
                    double a = 2 * Math.PI * t;
                    shown = shiftHue(color, 70f * (float) Math.sin(a));
                    cx = basePos + 0.22f * (float) Math.sin(2 * a);
                    swing = 1.02f + 0.08f * (float) Math.sin(3 * a);
                    break;
                }
                default:
                    break;
            }
        }
        k *= fxLevel == 0 ? 0.6f : fxLevel == 2 ? 1.4f : 1f;
        int c = darken(shown) & 0x00FFFFFF;
        int a1 = Math.min(255, (int) (0x50 * k));
        int a2 = Math.min(255, (int) (0x20 * k));
        ambientGlow.setColors(new int[]{(a1 << 24) | c, (a2 << 24) | c, c});
        if (particles != null) particles.setTint(shown);
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
            ambientGlow.setGradientCenter(FX_POS[fxPos], 0.42f);
        }
    }

    private static int darken(int color) {
        return Color.rgb((int) (Color.red(color) * 0.8f), (int) (Color.green(color) * 0.8f),
                (int) (Color.blue(color) * 0.8f));
    }

    // ---- clock in the corner: always running -----------------------------------------------------------

    /** The corner clock is static (no animation); this only makes sure no old effect is left on it. */
    private void startClockLoop() {
        stopClockLoop();
        View c = findViewById(R.id.clock);
        if (c instanceof TextView) {
            TextView tv = (TextView) c;
            tv.getPaint().setShader(null);
            tv.setShadowLayer(0f, 0f, 0f, 0);
            tv.invalidate();
        }
        applyClockStyle();
    }

    /** Applies the chosen clock look to the home screen clock (the screensaver reads the same choice). */
    private void applyClockStyle() {
        ClockStyles.apply(this, ClockStyles.get(this), (TextView) findViewById(R.id.clock), null,
                (TextView) findViewById(R.id.ampm), (TextView) findViewById(R.id.date),
                (PixelClockView) findViewById(R.id.clockPixel), false);
    }

    /** Pick a clock look; it is applied right away so the clock behind the menu changes live. */
    private void showClockStyles() {
        View anchor = findViewById(R.id.btnSettings);
        View panel = getLayoutInflater().inflate(R.layout.popup_apps, root, false);
        panel.findViewById(R.id.title).setVisibility(View.GONE);
        LinearLayout rows = panel.findViewById(R.id.rows);
        int minWidth = (int) (320 * density);
        final PopupWindow popup = newDropdown(panel, minWidth);
        final View[] items = new View[ClockStyles.NAMES.length];
        for (int i = 0; i < items.length; i++) {
            final int style = i;
            View row = getLayoutInflater().inflate(R.layout.item_input, rows, false);
            setMenuIcon(row, MenuIcons.CLOCK);
            ((TextView) row.findViewById(R.id.title)).setText(ClockStyles.NAMES[i]);
            ((TextView) row.findViewById(R.id.status)).setText(ClockStyles.NOTES[i]);
            View dot = row.findViewById(R.id.dot);
            if (dot instanceof SwitchView) ((SwitchView) dot).setCheckStyle(true);
            dot.setVisibility(ClockStyles.get(this) == i ? View.VISIBLE : View.GONE);
            row.setOnClickListener(v -> {
                ClockStyles.set(this, style);
                applyClockStyle();
                for (int k = 0; k < items.length; k++) items[k].findViewById(R.id.dot).setVisibility(k == style ? View.VISIBLE : View.GONE);
            });
            items[i] = row;
            rows.addView(row);
        }
        fitScroll(panel);
        showDropdown(popup, panel, anchor, minWidth, true);
    }

    private void stopClockLoop() {
        if (clockLoop != null) clockLoop.cancel();
        clockLoop = null;
        if (dividerPulse != null) dividerPulse.cancel();
        dividerPulse = null;
        View dv = findViewById(R.id.clockDivider);
        if (dv != null) dv.setAlpha(1f);
        View c = findViewById(R.id.clock);
        if (c instanceof TextView) ((TextView) c).getPaint().setShader(null);
    }

    private void hideHero() {
        if (!heroShown) return;
        heroShown = false;
        ambient.animate().alpha(0f).setDuration(300).start();
        particles.animate().alpha(0f).setDuration(300).start();
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
        dismissOpenPopup();
        ControlCenter.Actions a = new ControlCenter.Actions();
        a.wallpaper = this::showWallpaperMenu;
        a.hideApps = this::showHideApps;
        a.effects = this::showEffects;
        a.widgets = this::showWidgets;
        a.saver = () -> startActivity(new Intent(this, ScreensaverActivity.class));
        a.bluetooth = this::showBluetooth;                  // the same device list as the Bluetooth pill
        a.wifi = () -> open(new Intent(Settings.ACTION_WIFI_SETTINGS));
        a.sound = () -> open(new Intent(Settings.ACTION_SOUND_SETTINGS));
        a.clockStyle = this::showClockStyles;
        a.about = () -> open(new Intent(Settings.ACTION_DEVICE_INFO_SETTINGS));
        a.system = () -> open(new Intent(Settings.ACTION_SETTINGS));
        a.clearCache = this::quickClearCache;
        a.stopApps = this::quickStopApps;
        if (!hasListenerAccess()) a.musicAccess = this::openMusicAccess;
        ControlCenter.Info info = new ControlCenter.Info();
        info.effectsOn = fxGlowOn;
        info.effectsValue = getString(fxGlowOn ? STYLE_NAMES[fxStyle] : R.string.cc_off);
        info.wallpaperOn = WallpaperStore.exists(this);
        info.wallpaperValue = getString(info.wallpaperOn ? R.string.cc_custom : R.string.cc_default);
        info.storage = TvTools.storage();
        info.memory = TvTools.memory(this);
        showVeil();
        controlCenter = new ControlCenter(this);
        controlCenter.setOnDismiss(this::scheduleHideVeil);
        controlCenter.show(a, info);
    }

    private ControlCenter controlCenter;

    // ---- small frosted notice (replaces the plain toast for "working ... done") ----------------------------

    private LinearLayout noticePanel;
    private TextView noticeText;
    private View noticeDots;
    private final Runnable hideNotice = () -> {
        if (noticePanel == null) return;
        final View p = noticePanel;
        p.animate().alpha(0f).translationY(-10 * density).setDuration(260).withEndAction(() -> {
            if (p.getParent() == root) root.removeView(p);
            if (noticePanel == p) {
                noticePanel = null;
                noticeText = null;
                noticeDots = null;
            }
        }).start();
    };

    /** Shows a blurred glass notice at the top of the screen, like the menus; sticky ones stay until replaced. */
    private void showNotice(String text, boolean sticky) {
        handler.removeCallbacks(hideNotice);
        if (noticePanel == null || noticePanel.getParent() != root) {
            final LinearLayout panel = new LinearLayout(this);
            panel.setOrientation(LinearLayout.HORIZONTAL);
            panel.setGravity(Gravity.CENTER_VERTICAL);
            int px = (int) (26 * density), py = (int) (16 * density);
            panel.setPadding(px, py, px, py);
            panel.setBackground(new BlurPanelDrawable(density, menuBackdrop, root, panel));
            panel.setElevation(0f);
            noticeDots = new View(this);
            GradientDrawable dot = new GradientDrawable();
            dot.setShape(GradientDrawable.OVAL);
            dot.setColor(0xFFFFFFFF);
            noticeDots.setBackground(dot);
            LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams((int) (9 * density), (int) (9 * density));
            dlp.setMarginEnd((int) (14 * density));
            panel.addView(noticeDots, dlp);
            noticeText = new TextView(this);
            noticeText.setTextColor(0xFFFFFFFF);
            noticeText.setTextSize(17f);
            try {
                noticeText.setTypeface(getResources().getFont(R.font.outfit), Typeface.NORMAL);
            } catch (Exception ignored) {
            }
            panel.addView(noticeText);
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL | Gravity.TOP);
            lp.topMargin = (int) (72 * density);
            root.addView(panel, lp);
            panel.setAlpha(0f);
            panel.setTranslationY(-14 * density);
            panel.animate().alpha(1f).translationY(0f).setDuration(260)
                    .setInterpolator(new DecelerateInterpolator(1.6f)).start();
            noticePanel = panel;
        }
        noticePanel.animate().cancel();
        noticePanel.setAlpha(1f);
        noticePanel.setTranslationY(0f);
        noticeText.setText(text);
        noticeDots.animate().cancel();
        if (sticky) {                                    // a dot that breathes while the work runs
            noticeDots.setAlpha(1f);
            noticeDots.animate().alpha(0.2f).setDuration(650).withEndAction(new Runnable() {
                @Override public void run() {
                    if (noticeDots == null || noticePanel == null) return;
                    float next = noticeDots.getAlpha() < 0.5f ? 1f : 0.2f;
                    noticeDots.animate().alpha(next).setDuration(650).withEndAction(this).start();
                }
            }).start();
        } else {
            noticeDots.setAlpha(1f);
            handler.postDelayed(hideNotice, 2600);
        }
    }

    /** Memory row of the settings screen: stop the apps running in the background. */
    private void quickStopApps() {
        showNotice(getString(R.string.tc_working), true);
        new Thread(() -> {
            int n = 0;
            try {
                n = TvTools.stopBackground(this);
            } catch (Exception ignored) {
            }
            final int count = n;
            runOnUiThread(() -> showNotice(getString(R.string.tc_stopped, count), false));
        }).start();
    }

    // ---- blurred, slightly dark veil behind every menu --------------------------------------------------------

    private FrameLayout menuVeil;
    private ImageView menuVeilImg;
    private final Runnable hideVeilRunnable = () -> {
        if (menuVeil == null) return;
        boolean open = (this.openPopup != null && this.openPopup.isShowing()) || (controlCenter != null && controlCenter.isShowing());
        if (open) return;
        pauseBackground(false);
        menuVeil.animate().cancel();
        menuVeil.animate().alpha(0f).setDuration(240).withEndAction(() -> menuVeil.setVisibility(View.GONE)).start();
    };

    /** While a big menu covers the home screen, the glow, particles and widget animations behind it rest. */
    private void pauseBackground(boolean pause) {
        if (Perf.paused == pause) return;
        Perf.paused = pause;
        if (pause) {
            stopAmbientPulse();
        } else {
            syncPulse();
            if (particles != null) particles.invalidate();
        }
    }

    private View menuVeilDark;

    private void showVeil() {
        showVeil(false);
    }

    /**
     * light = false: the wallpaper, lightly blurred and dark, hides the screen behind (big menus).
     * light = true: only a gentle dark tint, so the screen stays visible (Inputs, Bluetooth, poster options).
     */
    private void showVeil(boolean light) {
        handler.removeCallbacks(hideVeilRunnable);
        if (!light) pauseBackground(true);
        if (menuVeil == null) {
            menuVeil = new FrameLayout(this);
            menuVeil.setFocusable(false);
            menuVeil.setClickable(false);
            menuVeil.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            menuVeilImg = new ImageView(this);
            menuVeilImg.setScaleType(ImageView.ScaleType.CENTER_CROP);
            menuVeil.addView(menuVeilImg, new FrameLayout.LayoutParams(-1, -1));
            View dark = new View(this);
            menuVeilDark = dark;
            menuVeil.addView(dark, new FrameLayout.LayoutParams(-1, -1));
            menuVeil.setVisibility(View.GONE);
            root.addView(menuVeil, new FrameLayout.LayoutParams(-1, -1));
        }
        menuVeilDark.setBackgroundColor(light ? 0x59000000 : 0xB8000000);
        menuVeilImg.setImageBitmap(light ? null : veilBackdrop);
        menuVeilImg.setAlpha(!light && veilBackdrop != null ? 1f : 0f);
        menuVeil.animate().cancel();
        if (menuVeil.getVisibility() != View.VISIBLE) {
            menuVeil.setAlpha(0f);
            menuVeil.setVisibility(View.VISIBLE);
        }
        menuVeil.animate().alpha(1f).setDuration(240).start();
    }

    private void scheduleHideVeil() {
        handler.removeCallbacks(hideVeilRunnable);
        handler.postDelayed(hideVeilRunnable, 320);
    }

    // ---- double tap on the settings icon: clear cache -----------------------------------------------

    /** Double tap on the settings icon: clear the cache right away. */
    private void quickClearCache() {
        View gear = findViewById(R.id.btnSettings);
        gear.animate().rotationBy(360f).setDuration(600).start();
        showNotice(getString(R.string.tc_working), true);
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
                if (!fok) showNotice(getString(R.string.tc_refused), false);
                else if (freed > 0) showNotice(getString(R.string.tc_cache_done, TvTools.fmt(after)), false);
                else showNotice(getString(R.string.tc_cache_none), false);
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
        addInputRow(rows, popup, MenuIcons.WALLPAPER, getString(R.string.choose_wallpaper), -1, this::chooseWallpaper);
        addInputRow(rows, popup, MenuIcons.DEPTH, getString(R.string.depth_front), -1, this::chooseFront);
        if (WallpaperStore.hasFront(this)) {
            addInputRow(rows, popup, MenuIcons.TRASH, getString(R.string.depth_remove), -1, this::removeFront);
        }
        // PNG over the dock: a switch, plus choose / remove.
        final View dockRow = getLayoutInflater().inflate(R.layout.item_input, rows, false);
        setMenuIcon(dockRow, MenuIcons.DEPTH);
        ((TextView) dockRow.findViewById(R.id.title)).setText(R.string.dock_png);
        updateWidgetRow(dockRow, dockPngOn && dockPng.hasBitmap());
        dockRow.setOnClickListener(v -> {
            if (!dockPng.hasBitmap()) {                       // nothing picked yet: pick one first
                popup.dismiss();
                chooseDockPng();
                return;
            }
            setDockPngOn(!dockPngOn);
            updateWidgetRow(dockRow, dockPngOn);
        });
        rows.addView(dockRow);
        addInputRow(rows, popup, MenuIcons.WALLPAPER, getString(R.string.dock_png_choose), -1, this::chooseDockPng);
        if (WallpaperStore.hasDock(this)) {
            addInputRow(rows, popup, MenuIcons.TRASH, getString(R.string.dock_png_remove), -1, this::removeDockPng);
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
        final View depthRow = getLayoutInflater().inflate(R.layout.item_input, rows, false);
        setMenuIcon(depthRow, MenuIcons.DEPTH);
        ((TextView) depthRow.findViewById(R.id.title)).setText("Depth (behind wallpaper)");
        updateDepthRow(depthRow);
        depthRow.setOnClickListener(v -> {
            if (!WallpaperStore.hasFront(this)) {
                Toast.makeText(this, "Add a depth PNG first: Settings > Wallpaper", Toast.LENGTH_LONG).show();
                return;
            }
            widgetsDepth = (widgetsDepth + 1) % 4;                    // off -> right -> left -> both
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().putInt("widgets_depth_side", widgetsDepth).apply();
            updateDepthRow(depthRow);
            updateDepthWidgets();
        });
        rows.addView(depthRow);
        final View cityRow = getLayoutInflater().inflate(R.layout.item_input, rows, false);
        setMenuIcon(cityRow, MenuIcons.WIDGETS);
        ((TextView) cityRow.findViewById(R.id.title)).setText("Weather city");
        final String curCity = widgets.weatherCity();
        ((TextView) cityRow.findViewById(R.id.status)).setText(curCity.isEmpty() ? "Auto" : curCity);
        noIndicator(cityRow);                            // a value (Auto / city), not an on/off
        cityRow.setOnClickListener(v -> {
            popup.dismiss();
            askWeatherCity(curCity);
        });
        rows.addView(cityRow);
        for (final String id : WidgetPanel.IDS) {
            final View row = getLayoutInflater().inflate(R.layout.item_input, rows, false);
            setMenuIcon(row, MenuIcons.WIDGETS);
            ((TextView) row.findViewById(R.id.title)).setText(WidgetPanel.titleRes(id));
            updateWidgetSideRow(row, widgets.state(id));
            row.setOnClickListener(v -> {
                widgets.cycle(id);                       // off -> right -> left -> off
                updateWidgetSideRow(row, widgets.state(id));
                positionWidgets();
                if (id.equals("lyrics")) {
                    lyricsKey = "";                      // look the song up again (or clear it)
                    updateMusicGlow();
                }
            });
            rows.addView(row);
        }
        fitScroll(panel);
        showDropdown(popup, panel, anchor, minWidth, true);
    }

    /** Asks for a city name for the weather widget (empty = automatic). */
    private void askWeatherCity(String current) {
        final android.widget.EditText input = new android.widget.EditText(this);
        input.setSingleLine(true);
        input.setText(current);
        input.setSelection(input.getText().length());
        input.setHint("City, e.g. Nawanshahr (empty = Nawanshahr)");
        new android.app.AlertDialog.Builder(this)
                .setTitle("Weather city")
                .setView(input)
                .setPositiveButton("Save", (d, w) -> widgets.setWeatherCity(input.getText().toString(), city -> {
                    Toast.makeText(this, city == null ? "City not found"
                            : city.isEmpty() ? "Weather: Nawanshahr" : "Weather: " + city, Toast.LENGTH_SHORT).show();
                    widgets.refresh();
                    if (widgetsLeft != null) widgetsLeft.refresh();
                }))
                .setNegativeButton("Cancel", null)
                .show();
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
        setMenuIcon(glowRow, MenuIcons.GLOW);
        ((TextView) glowRow.findViewById(R.id.title)).setText(R.string.fx_glow);
        updateWidgetRow(glowRow, fxGlowOn);
        rows.addView(glowRow);

        final View styleRow = getLayoutInflater().inflate(R.layout.item_input, rows, false);
        setMenuIcon(styleRow, MenuIcons.BRUSH);
        ((TextView) styleRow.findViewById(R.id.title)).setText(R.string.fx_style);
        updateStyleRow(styleRow);
        rows.addView(styleRow);

        final View strength = getLayoutInflater().inflate(R.layout.item_input, rows, false);
        setMenuIcon(strength, MenuIcons.LEVELS);
        ((TextView) strength.findViewById(R.id.title)).setText(R.string.fx_strength);
        updateStrengthRow(strength);
        rows.addView(strength);

        final View colorRow = getLayoutInflater().inflate(R.layout.item_input, rows, false);
        setMenuIcon(colorRow, MenuIcons.BRUSH);
        ((TextView) colorRow.findViewById(R.id.title)).setText("Glow colour");
        rows.addView(colorRow);
        final View posRow = getLayoutInflater().inflate(R.layout.item_input, rows, false);
        setMenuIcon(posRow, MenuIcons.LEVELS);
        ((TextView) posRow.findViewById(R.id.title)).setText("Glow position");
        rows.addView(posRow);
        final View partRow = getLayoutInflater().inflate(R.layout.item_input, rows, false);
        setMenuIcon(partRow, MenuIcons.GLOW);
        ((TextView) partRow.findViewById(R.id.title)).setText("Particles");
        rows.addView(partRow);
        updateFxRows(colorRow, posRow, partRow);
        colorRow.setOnClickListener(v -> {
            fxColor = (fxColor + 1) % FX_COLORS.length;
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().putInt("fx_color", fxColor).apply();
            updateFxRows(colorRow, posRow, partRow);
            if (heroShown && lastFocused >= 0) animateAmbient(ambientFor(lastFocused), 400);
        });
        posRow.setOnClickListener(v -> {
            fxPos = (fxPos + 1) % FX_POS.length;
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().putInt("fx_pos", fxPos).apply();
            updateFxRows(colorRow, posRow, partRow);
            syncPulse();
        });
        partRow.setOnClickListener(v -> {
            fxParticles = (fxParticles + 1) % ParticlesView.COUNT;
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().putInt("fx_particles", fxParticles).apply();
            particles.setMode(fxParticles);
            updateFxRows(colorRow, posRow, partRow);
        });
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

    /** A row that shows a value rather than on/off: no switch at all. */
    private void noIndicator(View row) {
        View dot = row.findViewById(R.id.dot);
        if (dot instanceof SwitchView) ((SwitchView) dot).setIndicatorHidden(true);
        else dot.setVisibility(View.GONE);
    }

    private void updateDepthRow(View row) {
        String[] names = {"Off", "Right side", "Left side", "Both sides"};
        ((TextView) row.findViewById(R.id.status)).setText(names[widgetsDepth]);
        row.findViewById(R.id.dot).setVisibility(widgetsDepth == 0 ? View.GONE : View.VISIBLE);
        row.findViewById(R.id.title).setAlpha(widgetsDepth == 0 ? 0.45f : 1f);
        row.findViewById(R.id.icon).setAlpha(widgetsDepth == 0 ? 0.45f : 1f);
    }

    private void updateFxRows(View colorRow, View posRow, View partRow) {
        ((TextView) colorRow.findViewById(R.id.status)).setText(FX_COLOR_NAMES[fxColor]);
        noIndicator(colorRow);
        ((TextView) posRow.findViewById(R.id.status)).setText(FX_POS_NAMES[fxPos]);
        noIndicator(posRow);
        ((TextView) partRow.findViewById(R.id.status)).setText(FX_PARTICLE_NAMES[fxParticles]);
        partRow.findViewById(R.id.dot).setVisibility(fxParticles == 0 ? View.GONE : View.VISIBLE);
    }

    private void updateStyleRow(View row) {
        ((TextView) row.findViewById(R.id.status)).setText(STYLE_NAMES[fxStyle]);
        noIndicator(row);
        row.setAlpha(fxGlowOn ? 1f : 0.45f);
    }

    private void updateStrengthRow(View row) {
        ((TextView) row.findViewById(R.id.status)).setText(
                fxLevel == 0 ? R.string.fx_soft : fxLevel == 2 ? R.string.fx_strong : R.string.fx_normal);
        noIndicator(row);
    }

    private void updateWidgetSideRow(View row, int state) {
        ((TextView) row.findViewById(R.id.status)).setText(
                state == 0 ? R.string.widget_off : state == 1 ? R.string.widget_right : R.string.widget_left);
        row.findViewById(R.id.dot).setVisibility(state == 0 ? View.GONE : View.VISIBLE);
        row.findViewById(R.id.title).setAlpha(state == 0 ? 0.45f : 1f);
        row.findViewById(R.id.icon).setAlpha(state == 0 ? 0.45f : 1f);
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
        if (dot instanceof SwitchView) ((SwitchView) dot).setCheckStyle(true);   // "selected" tick, not a switch
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
            if (changed[0]) adapter.reloadAsync();
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
        menuBackdrop = null;
        veilBackdrop = null;
        if (dockDecoration != null) dockDecoration.setBackdrop(null);
        if (appsPanel != null) appsPanel.setBackdrop(null);
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
        depthWidgets.setBitmap(null);
        updateDepthWidgets();
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
            depthWidgets.setBitmap(bitmap);
            root.post(this::updateFrontTopClip);
            root.post(this::updateDepthWidgets);
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

    /** Loads the saved PNG for the dock (kept in the app's own storage, so it survives a restart). */
    private void loadDockPng() {
        if (dockPng == null || dockPng.hasBitmap() || !WallpaperStore.hasDock(this)) return;
        try {
            String path = WallpaperStore.dockPath(this);
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(path, bounds);
            int limit = Math.max(1, getResources().getDisplayMetrics().widthPixels);
            int sample = 1;
            while (bounds.outWidth / sample > limit * 2 || bounds.outHeight / sample > limit * 2) sample *= 2;
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inSampleSize = sample;                       // ARGB_8888: keeps the transparency
            Bitmap bitmap = BitmapFactory.decodeFile(path, options);
            if (bitmap != null) dockPng.setBitmap(bitmap);
        } catch (Exception | OutOfMemoryError ignored) {
        }
    }

    private void setDockPngOn(boolean on) {
        dockPngOn = on;
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(KEY_DOCK_PNG_ON, on).apply();
        if (grid != null) grid.invalidate();
    }

    private void chooseDockPng() {
        Intent pick = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        pick.addCategory(Intent.CATEGORY_OPENABLE);
        pick.setType("image/*");
        pick.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            startActivityForResult(pick, REQUEST_DOCK_PNG);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, R.string.not_available, Toast.LENGTH_SHORT).show();
        }
    }

    private void removeDockPng() {
        WallpaperStore.deleteDock(this);
        dockPng.setBitmap(null);
        setDockPngOn(false);
    }

    private void restoreFront() {
        if (!WallpaperStore.hasFront(this) || wallpaperFront.getVisibility() == View.VISIBLE) return;
        applyFront();
    }

    private void restoreWallpaper() {
        loadDockPng();
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
            Bitmap blurred = BlurUtil.blur(bitmap, 192, 12);   // heavy, smooth blur like a frosted TV home
            wallpaperBlurView.setImageBitmap(blurred);
            wallpaperBlurView.setVisibility(View.VISIBLE);
            menuBackdrop = blurred;
            final Bitmap src = bitmap;                          // a gentle blur for the menu veil, made off the main thread
            new Thread(() -> {
                final Bitmap v = BlurUtil.blur(src, 360, 4);
                runOnUiThread(() -> veilBackdrop = v);
            }).start();
            if (dockDecoration != null) dockDecoration.setBackdrop(blurred);
            if (appsPanel != null) appsPanel.setBackdrop(blurred);
            if (allAppsPanel != null) allAppsPanel.setBackdrop(blurred);
            if (grid != null) grid.invalidate();
        } catch (Exception e) {
            clearWallpaperViews();
            Toast.makeText(this, R.string.wallpaper_failed, Toast.LENGTH_SHORT).show();
        }
    }

    // ---- Continue watching (row above the apps in All apps) ---------------------------------------

    private RecyclerView continueRow;
    /** Side padding of the poster row: keeps a focused (scaled) poster and its glass cursor inside the panel. */
    private static final int CONT_PAD_DP = 84;
    /** The row is clipped this far from each screen edge (panel margin 40dp + a little). */
    private static final int CONT_CLIP_DP = 46;
    private static final int PILL_H_DP = 34;          // height of the "Continue watching" / "All apps" pills
    private static final int TOP_BUF_DP = 40;         // hidden space above the first pill: the dock never reaches the panel
    private static final int CONT_PILL_GAP_DP = 18;   // pill -> posters
    private static final int APPS_PILL_GAP_DP = 14 + AppsPanelDecoration.TITLE_DP;   // pill -> first row of apps
    private TextView contPill, appsPill;
    private PillIcon.Film contPillIcon;
    private PillIcon.Apps appsPillIcon;
    private boolean pillsRunning;
    private int continueGapPx, continueRowPx;

    /** True while the row is part of the layout: there is something to show and we are not arranging. */
    private boolean continueActive() {
        return continueAdapter != null && continueAdapter.getItemCount() > 0 && !arranging
                && adapter != null && adapter.getItemCount() > COLUMNS;
    }

    /** The first row of apps carries the section pills (and the posters) above it. */
    private boolean sectionsActive() {
        return adapter != null && adapter.getItemCount() > COLUMNS && !arranging;
    }

    private int contPillBlockPx() {
        return (int) ((PILL_H_DP + CONT_PILL_GAP_DP) * density);
    }

    private int appsPillBlockPx() {
        return (int) ((PILL_H_DP + APPS_PILL_GAP_DP) * density);
    }

    /** Whole space reserved above the first row of apps, including the hidden buffer under the panel top. */
    private int gapTotalPx() {
        int g = (int) (TOP_BUF_DP * density) + appsPillBlockPx();
        if (continueActive()) g += contPillBlockPx() + continueRowPx + (int) (6 * density);
        return g;
    }

    /** The part of the gap that has to be on screen when the first row of apps is shown (no hidden buffer). */
    private int firstRowGap(int position) {
        return position >= COLUMNS && position < 2 * COLUMNS && sectionsActive()
                ? gapTotalPx() - (int) (TOP_BUF_DP * density) : 0;
    }

    private void setupContinueRow() {
        continueAdapter = new ContinueAdapter(this, this);
        continueAdapter.setAppSource(adapter);
        continueRow = new RecyclerView(this);
        continueRow.setLayoutManager(new LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false));
        continueRow.setClipChildren(false);
        continueRow.setClipToPadding(false);
        continueRow.setItemAnimator(null);
        continueRow.setOverScrollMode(View.OVER_SCROLL_NEVER);
        continueRow.setHorizontalScrollBarEnabled(false);
        continueRow.setAdapter(continueAdapter);
        continueRow.setAlpha(0f);
        // A RecyclerView is focusable itself; this invisible one must never catch focus (it sat in the way of Up).
        continueRow.setFocusable(false);
        continueRow.setFocusableInTouchMode(false);
        continueRow.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        continueRow.setPadding((int) (CONT_PAD_DP * density), 0, (int) (CONT_PAD_DP * density), 0);
        root.addView(continueRow, root.indexOfChild(grid) + 1,
                new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (int) (200 * density)));
        contPillIcon = new PillIcon.Film(density, android.content.res.ColorStateList.valueOf(0xFFFFFFFF));
        appsPillIcon = new PillIcon.Apps(density, android.content.res.ColorStateList.valueOf(0xFFFFFFFF));
        contPill = makeSectionPill(getString(R.string.continue_watching), contPillIcon);
        appsPill = makeSectionPill(getString(R.string.all_apps_title), appsPillIcon);
        // The pills float over the gap above the posters and above the first row of apps.
        root.addView(contPill, root.indexOfChild(continueRow) + 1,
                new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, (int) (PILL_H_DP * density)));
        root.addView(appsPill, root.indexOfChild(continueRow) + 1,
                new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, (int) (PILL_H_DP * density)));
        // The row floats over the gap above the first row of apps and follows the grid (scroll, fades, clip).
        grid.getViewTreeObserver().addOnPreDrawListener(() -> {
            syncContinueRow();
            trackPosterCursor();
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

    private TextView makeSectionPill(String text, PillIcon icon) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextColor(0xFFFFFFFF);
        tv.setTextSize(13f);
        tv.setSingleLine(true);
        tv.setGravity(android.view.Gravity.CENTER_VERTICAL);
        tv.setPadding((int) (12 * density), 0, (int) (16 * density), 0);
        tv.setCompoundDrawablePadding((int) (7 * density));
        int sz = (int) (17 * density);
        icon.setBounds(0, 0, sz, sz);
        tv.setCompoundDrawablesRelative(icon, null, null, null);
        tv.setLetterSpacing(0.01f);
        if (Build.VERSION.SDK_INT >= 26) {
            try {
                tv.setTypeface(getResources().getFont(R.font.outfit));
                tv.setFontVariationSettings("'wght' 600");
            } catch (RuntimeException ignored) {
            }
        }
        tv.setBackground(pillBackground(false, 17f, pillTintCur));
        tv.setFocusable(false);
        tv.setClickable(false);
        tv.setAlpha(0f);
        tv.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        return tv;
    }

    // ---- pill colour: follows the album art while a Spotify song plays ----------------------------------

    private int pillTintCur;              // 0 = plain glass
    private ValueAnimator pillTintAnim;

    private static int mix(int a, int b, float t) {
        return Color.argb(255, Math.round(Color.red(a) + (Color.red(b) - Color.red(a)) * t),
                Math.round(Color.green(a) + (Color.green(b) - Color.green(a)) * t),
                Math.round(Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t));
    }

    private static int lerpArgb(int a, int b, float t) {
        return Color.argb(Math.round(Color.alpha(a) + (Color.alpha(b) - Color.alpha(a)) * t),
                Math.round(Color.red(a) + (Color.red(b) - Color.red(a)) * t),
                Math.round(Color.green(a) + (Color.green(b) - Color.green(a)) * t),
                Math.round(Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t));
    }

    /**
     * A frosted pill background (focused / normal). The tint's alpha is how strongly the song colour is
     * blended in: 0 = plain white glass, 255 = fully the song colour.
     */
    private Drawable pillBackground(boolean round, float radiusDp, int tint) {
        float k = Color.alpha(tint) / 255f;
        int fTop = 0x66FFFFFF, fBot = 0x2EFFFFFF, fStroke = 0xA6FFFFFF;
        int nTop = 0x2EFFFFFF, nBot = 0x14FFFFFF, nStroke = 0x38FFFFFF;
        if (k > 0f) {
            int rgb = tint & 0xFFFFFF;
            int soft = mix(tint | 0xFF000000, 0xFFFFFFFF, 0.30f) & 0xFFFFFF;
            int bright = mix(tint | 0xFF000000, 0xFFFFFFFF, 0.55f) & 0xFFFFFF;
            fTop = lerpArgb(fTop, 0xAA000000 | rgb, k);
            fBot = lerpArgb(fBot, 0x66000000 | rgb, k);
            fStroke = lerpArgb(fStroke, 0xE6000000 | bright, k);
            nTop = lerpArgb(nTop, 0x78000000 | rgb, k);
            nBot = lerpArgb(nBot, 0x40000000 | rgb, k);
            nStroke = lerpArgb(nStroke, 0x8C000000 | soft, k);
        }
        android.graphics.drawable.StateListDrawable sl = new android.graphics.drawable.StateListDrawable();
        sl.addState(new int[]{android.R.attr.state_focused},
                pillShape(round, radiusDp, fTop, fBot, fStroke, 1.5f));
        sl.addState(new int[0], pillShape(round, radiusDp, nTop, nBot, nStroke, 1f));
        return sl;
    }

    private GradientDrawable pillShape(boolean round, float radiusDp, int top, int bottom, int stroke, float strokeDp) {
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{top, bottom});
        g.setShape(round ? GradientDrawable.OVAL : GradientDrawable.RECTANGLE);
        if (!round) g.setCornerRadius(radiusDp * density);
        g.setStroke(Math.max(1, Math.round(strokeDp * density)), stroke);
        return g;
    }

    private void applyPillTint(int tint) {
        pillTintCur = tint;
        View b1 = findViewById(R.id.btnInputs), b2 = findViewById(R.id.btnBluetooth), b3 = findViewById(R.id.btnSettings);
        if (b1 != null) b1.setBackground(pillBackground(false, 21f, tint));
        if (b2 != null) b2.setBackground(pillBackground(false, 21f, tint));
        if (b3 != null) b3.setBackground(pillBackground(true, 0f, tint));
        if (contPill != null) contPill.setBackground(pillBackground(false, 17f, tint));
        if (appsPill != null) appsPill.setBackground(pillBackground(false, 17f, tint));
    }

    private int pillTintTarget;           // the colour the pills are heading to (0 = plain glass)

    /** Fades every pill to the song colour (0 = back to plain glass). */
    private void tintPills(final int target) {
        if (target == pillTintTarget) return;
        pillTintTarget = target;
        if (pillTintAnim != null) pillTintAnim.cancel();
        final int from = pillTintCur;                    // ARGB: alpha = strength
        final int fromRgb = Color.alpha(from) == 0 ? (target & 0xFFFFFF) : (from & 0xFFFFFF);
        final int fromK = Color.alpha(from);
        final int toRgb = target == 0 ? fromRgb : (target & 0xFFFFFF);
        final int toK = target == 0 ? 0 : 255;
        pillTintAnim = ValueAnimator.ofFloat(0f, 1f);
        pillTintAnim.setDuration(1400);
        pillTintAnim.addUpdateListener(an -> {
            float t = (Float) an.getAnimatedValue();
            int rgb = mix(0xFF000000 | fromRgb, 0xFF000000 | toRgb, t) & 0xFFFFFF;
            int k = Math.round(fromK + (toK - fromK) * t);
            applyPillTint(k == 0 ? 0 : (k << 24) | rgb);
        });
        pillTintAnim.start();
    }

    /** Poster size: three full posters and a peek of the next one fit across the screen. */
    private void layoutContinue() {
        if (continueRow == null || grid == null || grid.getWidth() == 0) return;
        int contentW = grid.getWidth() - 2 * (int) (CONT_PAD_DP * density);
        int gap = (int) (18 * density);
        int w = (int) ((contentW - 2 * gap) / 3.2f);
        int h = Math.round(w * 9f / 16f);
        continueAdapter.setSize(w, h, gap);
        int block = h + (int) (52 * density);                  // 8 air + poster + title line + 8 air
        continueGapPx = block + (int) (6 * density) + appsPillBlockPx();   // poster row top -> first row of apps
        if (continueRowPx != block) {
            continueRowPx = block;
            ViewGroup.LayoutParams lp = continueRow.getLayoutParams();
            lp.height = block;
            continueRow.setLayoutParams(lp);
            grid.invalidateItemDecorations();
        }
    }

    /** Keeps the row and the pills glued to the gap above the first row of apps. Only draw properties change here. */
    private float dockFade = 1f;          // 1 = the dock row is shown, 0 = hidden
    private boolean dockTouched;

    /**
     * The dock belongs to the dock state only. In the All apps section (pills, posters, apps) it fades away
     * instead of sliding up behind the posters. Only draw properties change here.
     */
    private void syncDock() {
        if (grid == null || dockDecoration == null) return;
        float target = allAppsMode && !arranging && !snapRising && sectionsActive() ? 0f : 1f;
        if (dockFade == target && !dockTouched) return;
        float next = target > dockFade ? Math.min(target, dockFade + 0.25f) : Math.max(target, dockFade - 0.2f);
        boolean changed = next != dockFade;
        dockFade = next;
        dockTouched = dockFade != 1f;
        dockDecoration.setAlpha(dockFade);
        for (int i = 0; i < grid.getChildCount(); i++) {
            View c = grid.getChildAt(i);
            int p = grid.getChildAdapterPosition(c);
            if (p >= 0 && p < COLUMNS && c.getAlpha() != dockFade) c.setAlpha(dockFade);
        }
        if (changed) grid.invalidate();
    }

    /** The dark panel behind the All apps rows fades in and out with the All apps section. */
    private void syncAppsPanel() {
        if (grid == null || appsPanel == null) return;
        syncAppsPanelBounds();
        float target = allAppsMode && allUiApplied && !arranging && sectionsActive() ? 1f : 0f;
        if (appsPanelFade == target) return;
        appsPanelFade = target > appsPanelFade ? Math.min(target, appsPanelFade + 0.1f)
                : Math.max(target, appsPanelFade - 0.2f);
        appsPanel.setAlpha(appsPanelFade);
        grid.invalidate();
    }

    /** Tells the dark panel where the glass panel is (it draws 1dp inside its view) so both keep an even gap. */
    private void syncAppsPanelBounds() {
        if (appsPanel == null || allAppsPanel == null) return;
        float inset = density;                                            // GlassPanelView draws 1dp inside its bounds
        float margin = 10 * density;
        // The first row sits ROW_INSET inside the panel and its tiles another 8dp (item padding) below that.
        float innerGap = (ROW_INSET_DP + 8 - 1 - 10) * density;
        appsPanel.setGlass(allAppsPanel.getLeft() + inset, allAppsPanel.getTop() + inset,
                allAppsPanel.getRight() - inset, allAppsPanel.getBottom() - inset, margin, innerGap);
    }

    /** The PNG over the dock fades together with the dock (it is not shown in the All apps section). */
    private void syncDockPng() {
        if (dockPng == null) return;
        boolean want = dockPngOn && dockPng.hasBitmap();
        if (!want) {
            if (dockPng.getVisibility() != View.GONE) dockPng.setVisibility(View.GONE);
            return;
        }
        if (dockPng.getVisibility() != View.VISIBLE) dockPng.setVisibility(View.VISIBLE);
        // Coming back from All apps the dock rises and fades in with the grid: the PNG rides along with it
        // (same lift, alpha following the grid squared) instead of popping up at its final place first.
        float ga = grid.getAlpha();
        float pa = dockFade * ga * ga;
        if (dockPng.getAlpha() != pa) dockPng.setAlpha(pa);
        if (dockPng.getTranslationY() != grid.getTranslationY()) dockPng.setTranslationY(grid.getTranslationY());
    }

    private boolean rowsFaded;

    /**
     * In All apps a row that only partly fits inside the glass panel fades with the amount that is inside,
     * so a second row never sticks out of (or gets sliced by) the panel border. Fully visible rows stay at 1.
     */
    private void syncRowFade() {
        if (grid == null) return;
        // Also while snapping back to the dock: the bottom apps must stay faded the whole way, not pop to full.
        boolean on = ((allAppsMode && allUiApplied) || snapActive) && !arranging && sectionsActive();
        if (!on && !rowsFaded) return;
        float bottomLimit = grid.getHeight() - (PANEL_BOTTOM_DP + 8) * density;
        float topLimit = panelTopPx() + 6 * density;
        boolean any = false;
        for (int i = 0; i < grid.getChildCount(); i++) {
            View c = grid.getChildAt(i);
            int p = grid.getChildAdapterPosition(c);
            if (p < COLUMNS) continue;
            float a = 1f;
            if (on) {
                float h = Math.max(1f, c.getHeight());
                float below = (bottomLimit - (c.getTop() + grid.getTranslationY())) / h;       // 1 = fully inside
                float above = ((c.getBottom() + grid.getTranslationY()) - topLimit) / h;
                float f = Math.max(0f, Math.min(1f, Math.min(below, above)));
                a = f * f * (3f - 2f * f);
            }
            if (a != 1f) any = true;
            if (c.getAlpha() != a) c.setAlpha(a);
        }
        rowsFaded = on && any;
    }

    private final Rect contClip = new Rect();
    private boolean contClipSet;

    private View trackedPoster;
    private long trackUntil;

    /**
     * Right after the focus lands on a poster the row, the grid and the glass are all still settling (scroll,
     * fades, relayout). For a short while the cursor is re-aimed at the poster's real position every frame, so it
     * ends exactly on the poster instead of keeping a small offset from the first frame.
     */
    private void trackPosterCursor() {
        View c = trackedPoster;
        if (c == null) return;
        if (SystemClock.uptimeMillis() > trackUntil || getCurrentFocus() != c || !c.isAttachedToWindow()) {
            trackedPoster = null;
            return;
        }
        moveGlassTo(c, true);
    }

    private void syncContinueRow() {
        syncDock();
        syncAppsPanel();
        syncDockPng();
        syncRowFade();
        if (continueRow == null) return;
        boolean active = continueActive();
        // In dock state the row is invisible but overlaps the dock's neighbourhood, so keys like Up must not find it.
        int want = active && allAppsMode ? ViewGroup.FOCUS_AFTER_DESCENDANTS : ViewGroup.FOCUS_BLOCK_DESCENDANTS;
        if (continueRow.getDescendantFocusability() != want) continueRow.setDescendantFocusability(want);
        RecyclerView.ViewHolder vh = sectionsActive() ? grid.findViewHolderForAdapterPosition(COLUMNS) : null;
        if (vh == null) {
            if (continueRow.getAlpha() != 0f) continueRow.setAlpha(0f);
            hidePills();
            return;
        }
        float itemY = grid.getTop() + grid.getTranslationY() + vh.itemView.getTop();
        float y = itemY - continueGapPx;
        if (continueRow.getTranslationY() != y) continueRow.setTranslationY(y);
        float alpha = grid.getAlpha() * (allAppsPanel != null ? allAppsPanel.getAlpha() : 1f);
        if (snapRising || (!allAppsMode && !snapActive)) alpha = 0f;   // never over the dock
        if (!active) alpha = 0f;
        if (continueRow.getAlpha() != alpha) continueRow.setAlpha(alpha);
        Rect gc = grid.getClipBounds();
        int side = (int) (CONT_CLIP_DP * density);
        int cl = Math.max(gc == null ? 0 : gc.left, side);
        int cr = Math.min(gc == null ? root.getWidth() : gc.right, root.getWidth() - side);
        int ct = gc == null ? Math.round(-y) : Math.round(gc.top - y);
        int cb = gc == null ? continueRow.getHeight() : Math.round(gc.bottom - y);
        if (!contClipSet || contClip.left != cl || contClip.top != ct || contClip.right != cr || contClip.bottom != cb) {
            contClip.set(cl, ct, cr, cb);
            contClipSet = true;
            continueRow.setClipBounds(new Rect(contClip));
        }

        // Pills: "Continue watching" above the posters, "All apps" above the first row of apps.
        float pillAlpha = grid.getAlpha() * (allAppsPanel != null ? allAppsPanel.getAlpha() : 1f);
        if (snapRising || (!allAppsMode && !snapActive)) pillAlpha = 0f;
        float px = CONT_PAD_DP * density;
        placePill(contPill, active ? pillAlpha : 0f, px, y - contPillBlockPx(), gc);
        placePill(appsPill, pillAlpha, px, itemY - appsPillBlockPx(), gc);
        boolean run = pillAlpha > 0.05f;
        if (run != pillsRunning) {
            pillsRunning = run;
            contPillIcon.setFocused(run);
            appsPillIcon.setFocused(run);
        }
    }

    private void placePill(TextView pill, float alpha, float x, float y, Rect gc) {
        if (pill == null) return;
        if (pill.getAlpha() != alpha) pill.setAlpha(alpha);
        if (pill.getTranslationX() != x) pill.setTranslationX(x);
        if (pill.getTranslationY() != y) pill.setTranslationY(y);
        if (gc == null) {
            if (pill.getClipBounds() != null) pill.setClipBounds(null);
        } else {
            Rect c = new Rect(0, Math.round(gc.top - y), pill.getWidth(), Math.round(gc.bottom - y));
            if (!c.equals(pill.getClipBounds())) pill.setClipBounds(c);
        }
    }

    private void hidePills() {
        if (contPill != null && contPill.getAlpha() != 0f) contPill.setAlpha(0f);
        if (appsPill != null && appsPill.getAlpha() != 0f) appsPill.setAlpha(0f);
        if (pillsRunning) {
            pillsRunning = false;
            contPillIcon.setFocused(false);
            appsPillIcon.setFocused(false);
        }
    }

    private View cardAt(int position) {
        RecyclerView.ViewHolder vh = grid.findViewHolderForAdapterPosition(position);
        return vh == null ? null : vh.itemView.findViewById(R.id.card);
    }

    private float centerX(View v) {
        if (v == null) return root.getWidth() / 2f;
        Rect r = new Rect(0, 0, v.getWidth(), v.getHeight());
        root.offsetDescendantRectToMyCoords(v, r);
        if (continueRow != null && v.getParent() instanceof View && ((View) v.getParent()).getParent() == continueRow) {
            r.offset(Math.round(continueRow.getTranslationX()), 0);
        }
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

    private Bitmap cursorBgFor;
    private int cursorBgAvg;

    /** Netflix red, unless the wallpaper behind is close to that red: then the cursor would vanish, so use light grey. */
    private int posterCursorColor() {
        final int red = 0xFFE50914;
        Bitmap b = menuBackdrop;
        if (b == null || b.isRecycled()) return red;
        if (b != cursorBgFor) {
            long r = 0, g = 0, bl = 0;
            int n = 0;
            for (int yy = 1; yy < 8; yy++) {
                for (int xx = 1; xx < 8; xx++) {
                    int p = b.getPixel(xx * b.getWidth() / 8, yy * b.getHeight() / 8);
                    r += Color.red(p);
                    g += Color.green(p);
                    bl += Color.blue(p);
                    n++;
                }
            }
            cursorBgAvg = Color.rgb((int) (r / n), (int) (g / n), (int) (bl / n));
            cursorBgFor = b;
        }
        float dr = Color.red(cursorBgAvg) - Color.red(red);
        float dg = Color.green(cursorBgAvg) - Color.green(red);
        float db = Color.blue(cursorBgAvg) - Color.blue(red);
        return Math.sqrt(dr * dr + dg * dg + db * db) < 150 ? 0xFFF0F0F0 : red;
    }

    @Override
    public void onPosterFocused(int index, View card, int color) {
        if (restorePending || arranging) return;
        if (snapActive) endSnap();
        boolean entering = !allAppsMode;
        allAppsMode = true;
        syncContinueRow();                                 // the row must be in place before the cursor is measured against it
        glass.setColor(posterCursorColor(), glassShown);   // red cursor on the posters (light when the wallpaper is red too)
        moveGlassTo(card, true);
        trackedPoster = card;
        trackUntil = SystemClock.uptimeMillis() + 900;
        refreshChrome();
        hideHero();
        RecyclerView.ViewHolder vh = grid.findViewHolderForAdapterPosition(COLUMNS);
        if (vh == null) return;
        int rowTop = vh.itemView.getTop() - continueGapPx;
        int pillTop = rowTop - contPillBlockPx();
        if (entering) scrollGridBy(pillTop - allTopPx());
        else ensureVisible(pillTop, rowTop + continueRowPx);
    }

    @Override
    public void onPosterLongPressed(int index, View card) {
        showPosterMenu(index, card);
    }

    /** Menu for a poster (hold OK): resume, open the app, remove it, clear the list. Small, on frosted glass. */
    private void showPosterMenu(final int index, View card) {
        if (arranging) return;
        final NuvioHistory.Entry e = continueAdapter.entryAt(index);
        if (e == null) return;
        View panel = getLayoutInflater().inflate(R.layout.popup_menu, root, false);
        panel.setBackground(new BlurPanelDrawable(density, menuBackdrop, root, panel));
        ((TextView) panel.findViewById(R.id.title)).setText(e.title);
        LinearLayout rows = panel.findViewById(R.id.rows);
        int minWidth = (int) (230 * density);
        final PopupWindow popup = newDropdown(panel, minWidth);
        addMenuRow(rows, popup, MenuIcons.PLAY, getString(R.string.poster_resume),
                () -> continueAdapter.resume(e));
        String app = continueAdapter.appLabel(e);
        if (app != null) {
            addMenuRow(rows, popup, MenuIcons.OPEN, getString(R.string.poster_open_app, app),
                    () -> continueAdapter.openApp(e));
        }
        addSeparator(rows);
        addMenuRow(rows, popup, MenuIcons.TRASH, getString(R.string.poster_remove), () -> {
            NuvioHistory.remove(this, e);
            postersChanged(index);
        });
        addMenuRow(rows, popup, MenuIcons.CLEAR, getString(R.string.poster_clear), () -> {
            NuvioHistory.clear(this);
            postersChanged(0);
        });
        showDropdown(popup, panel, card, minWidth, false, true, true);   // same space left and right of the poster
    }

    private void addMenuRow(LinearLayout rows, PopupWindow popup, int iconRes, String title, Runnable action) {
        View row = getLayoutInflater().inflate(R.layout.item_menu, rows, false);
        setMenuIcon(row, iconRes);
        ((TextView) row.findViewById(R.id.title)).setText(title);
        row.setOnClickListener(v -> {
            popup.dismiss();
            handler.postDelayed(action, 160);
        });
        rows.addView(row);
    }

    /** Puts the animated icon of this kind on a menu row; it plays while the row has focus. */
    private void setMenuIcon(View row, int kind) {
        final ImageView iv = row.findViewById(R.id.icon);
        final PillIcon icon = MenuIcons.make(kind, density);
        iv.setImageDrawable(icon);
        final View.OnFocusChangeListener prev = row.getOnFocusChangeListener();
        row.setOnFocusChangeListener((v, focused) -> {
            if (prev != null) prev.onFocusChange(v, focused);
            icon.setFocused(focused);
        });
        // The menu closed: stop the loop so nothing keeps animating behind the scenes.
        iv.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override
            public void onViewAttachedToWindow(View v) {
            }

            @Override
            public void onViewDetachedFromWindow(View v) {
                icon.rest();
            }
        });
    }

    /** The poster list changed (removed / cleared): rebuild the row and put the focus somewhere sensible. */
    private void postersChanged(final int focusIndex) {
        continueAdapter.refresh();
        layoutContinue();
        grid.invalidateItemDecorations();      // the gap above the apps grows or shrinks with the row
        syncContinueRow();
        grid.post(() -> {
            int n = continueAdapter.posterCount();
            if (n > 0) focusPoster(Math.min(focusIndex, n - 1));
            else focusTile(COLUMNS);
        });
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
                        String n = btLabel(devices.get(0));
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
                        String n = btLabel(dev);
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
    /** The name you gave the device on the TV (its alias), else the name it calls itself. */
    private static String btLabel(BluetoothDevice dev) {
        try {
            Object alias = BluetoothDevice.class.getMethod("getAlias").invoke(dev);
            if (alias instanceof String && ((String) alias).trim().length() > 0) return ((String) alias).trim();
        } catch (Exception ignored) {
        }
        return dev.getName();
    }

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
                    String n = btLabel(dev);
                    String orig = dev.getName();
                    BtDev b = new BtDev(n == null || n.trim().isEmpty() ? dev.getAddress() : n.trim(), dev.getAddress());
                    try {
                        b.connected = Boolean.TRUE.equals(BluetoothDevice.class.getMethod("isConnected").invoke(dev));
                    } catch (Exception ignored) {
                    }
                    if (!b.connected && (audio.containsKey(b.name.toLowerCase())
                            || (orig != null && audio.containsKey(orig.trim().toLowerCase())))) b.connected = true;
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
        addInputRow(rows, popup, MenuIcons.BLUETOOTH, getString(R.string.bt_settings), -1, this::openRemotesAndAccessories);
        fitScroll(panel);
        showDropdown(popup, panel, anchor, minWidth, false, true);
    }

    private View addBtRow(LinearLayout rows, PopupWindow popup, String title, String status, boolean connected, float alpha) {
        View row = getLayoutInflater().inflate(R.layout.item_input, rows, false);
        setMenuIcon(row, MenuIcons.BLUETOOTH);
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
        lyricsView.setPosition(pos, dur);
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
            lyricsView.setAccent(color);
            maybeLoadLyrics(meta == null ? null : meta.getString(MediaMetadata.METADATA_KEY_TITLE), artist,
                    meta == null ? null : meta.getString(MediaMetadata.METADATA_KEY_ALBUM),
                    meta == null ? 0L : meta.getLong(MediaMetadata.METADATA_KEY_DURATION));
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
            clearLyrics();
        }
        tintPills(musicColor);
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
                CharSequence custom = null;
                try {
                    custom = info.loadCustomLabel(this);              // the name you gave the input (e.g. "PS4")
                } catch (RuntimeException ignored) {
                }
                CharSequence l = info.loadLabel(this);
                String label = l == null ? info.getId() : l.toString();
                boolean hdmi = info.getType() == TvInputInfo.TYPE_HDMI;
                if (custom != null && custom.toString().trim().length() > 0) label = custom.toString().trim();
                else if (hdmi && !label.toUpperCase().contains("HDMI")) label = "HDMI " + label;
                final String id = info.getId();
                int state = hdmi && hdmiStateBogus ? -1 : tim.getInputState(id);
                if (!hdmi && rows.getChildCount() > 0 && !sawNonHdmi) addSeparator(rows);
                if (!hdmi) sawNonHdmi = true;
                addInputRow(rows, popup, MenuIcons.SOURCE, label, state,
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
            addInputRow(rows, popup, MenuIcons.TV, getString(R.string.live_tv), -1, () -> open(liveTvIntent));
        }
        if (rows.getChildCount() == 0) {
            Toast.makeText(this, R.string.no_inputs, Toast.LENGTH_SHORT).show();
            return;
        }

        showDropdown(popup, panel, anchor, width, false, true);
    }

    /** A popup that fades and folds back up when it closes, the mirror of how showDropdown opens it. */
    private final class AnimPopup extends PopupWindow {
        private boolean closing;
        private OnDismissListener user;

        @Override
        public void setOnDismissListener(OnDismissListener l) {
            user = l;
            super.setOnDismissListener(l);
        }

        AnimPopup(View content, int w, int h, boolean focusable) {
            super(content, w, h, focusable);
        }

        @Override
        public void dismiss() {
            if (closing) return;
            View c = getContentView();
            if (!isShowing() || c == null || !c.isAttachedToWindow()) {
                closing = true;
                super.dismiss();
                return;
            }
            closing = true;
            c.animate().cancel();
            c.animate().alpha(0f).scaleY(0.94f).translationY(-6 * density).setStartDelay(0).setDuration(150)
                    .setInterpolator(new AccelerateInterpolator(1.3f))
                    .withEndAction(() -> super.dismiss()).start();
        }

        void dismissNow() {
            closing = true;
            View c = getContentView();
            if (c != null) c.animate().cancel();
            super.dismiss();
        }
    }

    private PopupWindow newDropdown(View panel, int width) {
        PopupWindow popup = new AnimPopup(panel, width, ViewGroup.LayoutParams.WRAP_CONTENT, true);
        popup.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));   // needed for Back to dismiss
        popup.setOutsideTouchable(true);
        popup.setAnimationStyle(0);
        popup.setElevation(0f);
        return popup;
    }

    /** Fits the popup to its widest row, drops it in under the anchor (unfolding from the top) and focuses row one. */
    private void showDropdown(PopupWindow popup, View panel, View anchor, int minWidth, boolean alignEnd) {
        showDropdown(popup, panel, anchor, minWidth, alignEnd, false);
    }

    private void showDropdown(PopupWindow popup, View panel, View anchor, int minWidth, boolean alignEnd, boolean lightVeil) {
        showDropdown(popup, panel, anchor, minWidth, alignEnd, lightVeil, false);
    }

    private void showDropdown(PopupWindow popup, View panel, View anchor, int minWidth, boolean alignEnd,
                              boolean lightVeil, boolean centered) {
        LinearLayout rows = panel.findViewById(R.id.rows);
        panel.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
        int width = Math.max(minWidth, panel.getMeasuredWidth());
        popup.setWidth(width);

        panel.setAlpha(0f);
        panel.setPivotY(0f);
        panel.setScaleY(0.92f);
        panel.setTranslationY(-8 * density);
        int xOff = centered ? (anchor.getWidth() - width) / 2 : (alignEnd ? anchor.getWidth() - width : 0);
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
        showVeil(lightVeil);
        // Keep a listener the caller already set (Hide apps reloads the list when it closes).
        final PopupWindow.OnDismissListener prev = popup instanceof AnimPopup ? ((AnimPopup) popup).user : null;
        popup.setOnDismissListener(() -> {
            if (prev != null) prev.onDismiss();
            scheduleHideVeil();
        });
        if (rows.getChildCount() > 0) rows.getChildAt(0).requestFocus();
    }

    private PopupWindow openPopup;

    /** Closes the open dropdown, if any. Returns true when there was one. */
    private boolean dismissOpenPopup() {
        if (controlCenter != null && controlCenter.isShowing()) {
            controlCenter.dismiss();
            return true;
        }
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
        setMenuIcon(row, iconRes);
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
            noIndicator(row);                                // state unknown: show nothing rather than a wrong switch
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
