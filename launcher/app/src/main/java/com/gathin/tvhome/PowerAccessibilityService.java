package com.gathin.tvhome;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

/**
 * Opens the TV's own power menu and presses the item that was chosen in the launcher's power menu. Apps cannot
 * restart or switch off a TV by themselves, so the real system menu does the work. This service is idle (it does
 * not even listen to window content) except for a few seconds after you pick something.
 */
public class PowerAccessibilityService extends AccessibilityService {

    private static PowerAccessibilityService instance;
    private static String[] wanted;                  // lower-case texts of the item to press; null = idle
    private static Runnable onMiss;                  // runs when the item was not found
    private static boolean clicked;
    private static long until;
    private final Handler handler = new Handler(Looper.getMainLooper());

    static boolean ready() {
        return instance != null;
    }

    @Override
    protected void onServiceConnected() {
        instance = this;
        listen(false);
    }

    @Override
    public boolean onUnbind(Intent intent) {
        if (instance == this) instance = null;
        return super.onUnbind(intent);
    }

    @Override
    public void onInterrupt() {
    }

    /** Opens the system power menu and presses the item whose text contains one of {@code texts}. */
    static boolean run(String[] texts, Runnable miss) {
        PowerAccessibilityService s = instance;
        if (s == null) return false;
        wanted = texts;
        onMiss = miss;
        clicked = false;
        until = SystemClock.uptimeMillis() + 4500;
        s.listen(true);
        boolean ok = s.performGlobalAction(GLOBAL_ACTION_POWER_DIALOG);
        int[] scans = {350, 800, 1400, 2200, 3200};
        for (int ms : scans) s.handler.postDelayed(s::scan, ms);
        s.handler.postDelayed(s::finish, 4600);
        return ok;
    }

    /** Window content events are only requested while a choice is being pressed. */
    private void listen(boolean content) {
        AccessibilityServiceInfo info = getServiceInfo();
        if (info == null) return;
        info.eventTypes = content
                ? AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED | AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
                : AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED;
        setServiceInfo(info);
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (wanted == null) return;                  // idle: costs nothing
        if (SystemClock.uptimeMillis() > until) return;
        scan();
    }

    private void finish() {
        if (wanted == null) return;
        boolean miss = !clicked;
        Runnable r = onMiss;
        wanted = null;
        onMiss = null;
        listen(false);
        if (miss && r != null) r.run();
    }

    private void scan() {
        String[] texts = wanted;
        if (texts == null || clicked) return;
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return;
        if (getPackageName().contentEquals(root.getPackageName() == null ? "" : root.getPackageName())) return;
        AccessibilityNodeInfo hit = find(root, texts, new int[]{0});
        if (hit == null) return;
        AccessibilityNodeInfo target = hit;
        while (target != null && !target.isClickable()) target = target.getParent();
        if (target != null && target.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            clicked = true;
            // A confirmation ("Restart?" with OK) may follow: press the matching words once more, briefly.
            handler.postDelayed(() -> {
                if (wanted == null) return;
                AccessibilityNodeInfo r2 = getRootInActiveWindow();
                if (r2 == null) return;
                AccessibilityNodeInfo again = find(r2, new String[]{"ok", "yes", "confirm"}, new int[]{0});
                AccessibilityNodeInfo t2 = again;
                while (t2 != null && !t2.isClickable()) t2 = t2.getParent();
                if (t2 != null) t2.performAction(AccessibilityNodeInfo.ACTION_CLICK);
            }, 700);
        }
    }

    /** Depth-first search with a hard limit, so a huge window can never make this slow. */
    private static AccessibilityNodeInfo find(AccessibilityNodeInfo node, String[] texts, int[] visited) {
        if (node == null || visited[0]++ > 400) return null;
        CharSequence t = node.getText();
        CharSequence cd = node.getContentDescription();
        String s = ((t == null ? "" : t.toString()) + " " + (cd == null ? "" : cd.toString())).toLowerCase();
        for (String w : texts) {
            if (s.contains(w)) return node;
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo hit = find(node.getChild(i), texts, visited);
            if (hit != null) return hit;
        }
        return null;
    }
}
