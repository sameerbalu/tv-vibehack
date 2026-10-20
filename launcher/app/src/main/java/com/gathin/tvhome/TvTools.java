package com.gathin.tvhome;

import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Environment;
import android.os.StatFs;
import android.os.storage.StorageManager;
import android.view.inputmethod.InputMethodInfo;
import android.view.inputmethod.InputMethodManager;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/** Memory, storage and background-app helpers behind the launcher's TV Control menu. Call off the main thread. */
final class TvTools {
    private TvTools() {
    }

    static final String CONTROL_PKG = "com.gathin.tvcontrol";

    /** {total, available} RAM in bytes. */
    static long[] memory(Context c) {
        ActivityManager am = (ActivityManager) c.getSystemService(Context.ACTIVITY_SERVICE);
        ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
        am.getMemoryInfo(mi);
        return new long[]{mi.totalMem, mi.availMem};
    }

    /** {total, free} internal storage in bytes. */
    static long[] storage() {
        StatFs fs = new StatFs(Environment.getDataDirectory().getPath());
        return new long[]{fs.getTotalBytes(), fs.getAvailableBytes()};
    }

    private static Set<String> packagesFor(PackageManager pm, String category) {
        Set<String> s = new HashSet<>();
        Intent i = new Intent(Intent.ACTION_MAIN).addCategory(category);
        for (ResolveInfo r : pm.queryIntentActivities(i, 0)) s.add(r.activityInfo.packageName);
        return s;
    }

    /** Asks Android to stop every user-facing app except the home screen, this launcher and the keyboard. */
    static int stopBackground(Context c) {
        PackageManager pm = c.getPackageManager();
        Set<String> t = packagesFor(pm, Intent.CATEGORY_LEANBACK_LAUNCHER);
        t.addAll(packagesFor(pm, Intent.CATEGORY_LAUNCHER));
        t.removeAll(packagesFor(pm, Intent.CATEGORY_HOME));
        t.remove(c.getPackageName());
        t.remove("android");
        t.remove("com.android.systemui");
        try {
            InputMethodManager imm = (InputMethodManager) c.getSystemService(Context.INPUT_METHOD_SERVICE);
            for (InputMethodInfo i : imm.getInputMethodList()) t.remove(i.getPackageName());
        } catch (Exception ignored) {
        }
        ActivityManager am = (ActivityManager) c.getSystemService(Context.ACTIVITY_SERVICE);
        for (String p : t) am.killBackgroundProcesses(p);
        return t.size();
    }

    /** Frees cache of other apps by asking Android for all the space it could make available. */
    static void clearCache(Context c) throws IOException {
        StorageManager sm = (StorageManager) c.getSystemService(Context.STORAGE_SERVICE);
        File f = new File(c.getFilesDir(), "cache-clear.tmp");
        try (FileOutputStream out = new FileOutputStream(f)) {
            long want = sm.getAllocatableBytes(StorageManager.UUID_DEFAULT);
            sm.allocateBytes(out.getFD(), want);
        } finally {
            //noinspection ResultOfMethodCallIgnored
            f.delete();
        }
    }

    static String fmt(long b) {
        if (b < 1024) return b + " B";
        double v = b / 1024.0;
        String[] u = {"KB", "MB", "GB", "TB"};
        int i = 0;
        while (v >= 1024 && i < 3) {
            v /= 1024;
            i++;
        }
        return String.format(Locale.US, v >= 100 ? "%.0f %s" : v >= 10 ? "%.1f %s" : "%.2f %s", v, u[i]);
    }
}
