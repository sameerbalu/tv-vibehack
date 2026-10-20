package com.gathin.tvcontrol;

import android.app.ActivityManager;
import android.app.AppOpsManager;
import android.app.usage.NetworkStats;
import android.app.usage.NetworkStatsManager;
import android.app.usage.StorageStats;
import android.app.usage.StorageStatsManager;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.Drawable;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Environment;
import android.os.Process;
import android.os.StatFs;
import android.os.storage.StorageManager;
import android.provider.Settings;
import android.view.inputmethod.InputMethodInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** All the system queries and actions. Anything slow must be called off the main thread. */
final class Sys {
    private Sys() {
    }

    static final String PKG = "com.gathin.tvcontrol";

    static final class App {
        String pkg;
        String label;
        Drawable icon;
        int uid;
        boolean system;
        boolean launchable;
        long cache = -1, data = -1, code = -1;
        long lastUsed;      // last time in the foreground (last 24 h), 0 = not seen
        long fgToday;       // foreground time since midnight
    }

    // ---- permissions ---------------------------------------------------------------------------

    static boolean hasUsageAccess(Context c) {
        try {
            AppOpsManager ops = (AppOpsManager) c.getSystemService(Context.APP_OPS_SERVICE);
            int mode = ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), c.getPackageName());
            if (mode == AppOpsManager.MODE_ALLOWED) return true;
            return mode == AppOpsManager.MODE_DEFAULT
                    && c.checkSelfPermission("android.permission.PACKAGE_USAGE_STATS") == PackageManager.PERMISSION_GRANTED;
        } catch (Exception e) {
            return false;
        }
    }

    static boolean canWriteSecure(Context c) {
        return c.checkSelfPermission("android.permission.WRITE_SECURE_SETTINGS") == PackageManager.PERMISSION_GRANTED;
    }

    // ---- apps ----------------------------------------------------------------------------------

    static Set<String> launchables(PackageManager pm) {
        Set<String> s = new HashSet<>();
        for (String cat : new String[]{Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER}) {
            Intent i = new Intent(Intent.ACTION_MAIN).addCategory(cat);
            for (ResolveInfo r : pm.queryIntentActivities(i, 0)) s.add(r.activityInfo.packageName);
        }
        return s;
    }

    static Set<String> homePackages(PackageManager pm) {
        Set<String> s = new HashSet<>();
        Intent i = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
        for (ResolveInfo r : pm.queryIntentActivities(i, 0)) s.add(r.activityInfo.packageName);
        return s;
    }

    /** Every installed app with name and icon (slow: call from a background thread). */
    static Map<String, App> loadApps(Context c) {
        PackageManager pm = c.getPackageManager();
        Set<String> launch = launchables(pm);
        Map<String, App> out = new LinkedHashMap<>();
        for (ApplicationInfo ai : pm.getInstalledApplications(0)) {
            App a = new App();
            a.pkg = ai.packageName;
            a.uid = ai.uid;
            a.system = (ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
            a.launchable = launch.contains(ai.packageName);
            try {
                a.label = String.valueOf(ai.loadLabel(pm));
                a.icon = ai.loadIcon(pm);
            } catch (Exception e) {
                a.label = ai.packageName;
            }
            out.put(a.pkg, a);
        }
        return out;
    }

    /** Last-used time (24 h) and foreground time (today) per app, from the usage events. */
    static void fillUsage(Context c, Map<String, App> apps) {
        try {
            UsageStatsManager u = (UsageStatsManager) c.getSystemService(Context.USAGE_STATS_SERVICE);
            long now = System.currentTimeMillis();
            long midnight = midnight();
            UsageEvents ev = u.queryEvents(now - 24L * 3600_000L, now);
            UsageEvents.Event e = new UsageEvents.Event();
            Map<String, Long> open = new HashMap<>();
            while (ev.hasNextEvent()) {
                ev.getNextEvent(e);
                String p = e.getPackageName();
                long ts = e.getTimeStamp();
                int t = e.getEventType();
                App a = apps.get(p);
                if (a == null) continue;
                if (t == UsageEvents.Event.MOVE_TO_FOREGROUND) {
                    open.put(p, ts);
                    a.lastUsed = Math.max(a.lastUsed, ts);
                } else if (t == UsageEvents.Event.MOVE_TO_BACKGROUND) {
                    Long s = open.remove(p);
                    a.lastUsed = Math.max(a.lastUsed, ts);
                    if (s != null && ts > midnight) a.fgToday += ts - Math.max(s, midnight);
                }
            }
            for (Map.Entry<String, Long> en : open.entrySet()) {     // still on screen
                App a = apps.get(en.getKey());
                if (a != null) {
                    a.lastUsed = now;
                    a.fgToday += now - Math.max(en.getValue(), midnight);
                }
            }
        } catch (Exception ignored) {
        }
    }

    static long midnight() {
        Calendar c = Calendar.getInstance();
        c.set(Calendar.HOUR_OF_DAY, 0);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        return c.getTimeInMillis();
    }

    /** Cache / data / app sizes (needs usage access; slow). */
    static void fillStorage(Context c, Iterable<App> apps) {
        StorageStatsManager s = (StorageStatsManager) c.getSystemService(Context.STORAGE_STATS_SERVICE);
        for (App a : apps) {
            try {
                StorageStats st = s.queryStatsForPackage(StorageManager.UUID_DEFAULT, a.pkg, Process.myUserHandle());
                a.cache = st.getCacheBytes();
                a.data = Math.max(0, st.getDataBytes() - st.getCacheBytes());
                a.code = st.getAppBytes();
            } catch (Exception e) {
                a.cache = -1;
            }
        }
    }

    // ---- stopping apps -------------------------------------------------------------------------

    static void kill(Context c, String pkg) {
        ActivityManager am = (ActivityManager) c.getSystemService(Context.ACTIVITY_SERVICE);
        am.killBackgroundProcesses(pkg);
    }

    /** User-facing apps that are safe to stop (not us, the home screen, or the keyboard). */
    static Set<String> boostTargets(Context c) {
        PackageManager pm = c.getPackageManager();
        Set<String> t = launchables(pm);
        t.removeAll(homePackages(pm));
        t.remove(PKG);
        t.remove("android");
        t.remove("com.android.systemui");
        try {
            InputMethodManager imm = (InputMethodManager) c.getSystemService(Context.INPUT_METHOD_SERVICE);
            for (InputMethodInfo i : imm.getInputMethodList()) t.remove(i.getPackageName());
        } catch (Exception ignored) {
        }
        return t;
    }

    // ---- memory / storage / network ------------------------------------------------------------

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

    /** Frees cache of other apps by asking Android for (almost) all the space it could make available. */
    static void clearAllCache(Context c) throws IOException {
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

    static String linkType(Context c) {
        try {
            ConnectivityManager cm = (ConnectivityManager) c.getSystemService(Context.CONNECTIVITY_SERVICE);
            Network n = cm.getActiveNetwork();
            if (n == null) return "Offline";
            NetworkCapabilities nc = cm.getNetworkCapabilities(n);
            if (nc == null) return "Offline";
            if (nc.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return "Wi-Fi";
            if (nc.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) return "Ethernet";
            if (nc.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) return "Mobile";
            return "Connected";
        } catch (Exception e) {
            return "Unknown";
        }
    }

    static String wifiDetail(Context c) {
        try {
            WifiManager wm = (WifiManager) c.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            WifiInfo wi = wm.getConnectionInfo();
            if (wi == null || wi.getNetworkId() == -1) return "";
            int rssi = wi.getRssi();
            String q = rssi >= -55 ? "Excellent" : rssi >= -67 ? "Good" : rssi >= -78 ? "Fair" : "Weak";
            int f = wi.getFrequency();
            String band = f > 5900 ? "6 GHz" : f > 4900 ? "5 GHz" : f > 2300 ? "2.4 GHz" : "";
            StringBuilder sb = new StringBuilder(q + " signal (" + rssi + " dBm)");
            if (wi.getLinkSpeed() > 0) sb.append(" · link ").append(wi.getLinkSpeed()).append(" Mbps");
            if (!band.isEmpty()) sb.append(" · ").append(band);
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    static String ipAddress() {
        try {
            Enumeration<NetworkInterface> en = NetworkInterface.getNetworkInterfaces();
            while (en != null && en.hasMoreElements()) {
                NetworkInterface ni = en.nextElement();
                if (ni.isLoopback() || !ni.isUp()) continue;
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (a instanceof Inet4Address && !a.isLoopbackAddress()) return a.getHostAddress();
                }
            }
        } catch (Exception ignored) {
        }
        return "";
    }

    /** Wi-Fi bytes per uid in [start, end]: uid -> {rx, tx}. Needs usage access. */
    static Map<Integer, long[]> wifiByUid(Context c, long start, long end) throws Exception {
        NetworkStatsManager m = (NetworkStatsManager) c.getSystemService(Context.NETWORK_STATS_SERVICE);
        NetworkStats ns = m.querySummary(ConnectivityManager.TYPE_WIFI, null, start, end);
        NetworkStats.Bucket b = new NetworkStats.Bucket();
        Map<Integer, long[]> out = new HashMap<>();
        while (ns.hasNextBucket()) {
            ns.getNextBucket(b);
            long[] v = out.get(b.getUid());
            if (v == null) {
                v = new long[2];
                out.put(b.getUid(), v);
            }
            v[0] += b.getRxBytes();
            v[1] += b.getTxBytes();
        }
        ns.close();
        return out;
    }

    // ---- opening settings ----------------------------------------------------------------------

    static void appInfo(Context c, String pkg) {
        try {
            Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + pkg));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            c.startActivity(i);
        } catch (Exception e) {
            toast(c, "Can't open app info on this TV");
        }
    }

    /** Opens a settings screen; falls back to the main Settings app, then to a message. */
    static void openSettings(Context c, String action) {
        List<Intent> tries = new ArrayList<>();
        tries.add(new Intent(action));
        tries.add(new Intent(Settings.ACTION_SETTINGS));
        for (Intent i : tries) {
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try {
                c.startActivity(i);
                return;
            } catch (Exception ignored) {
            }
        }
        toast(c, "Not available on this TV");
    }

    static void toast(Context c, String s) {
        Toast.makeText(c, s, Toast.LENGTH_SHORT).show();
    }
}
