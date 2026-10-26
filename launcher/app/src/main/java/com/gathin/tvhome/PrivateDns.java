package com.gathin.tvhome;

import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.provider.Settings;

/**
 * System-wide DNS over TLS ("Private DNS"). Pointing it at a filtering resolver such as AdGuard DNS blocks the
 * ad and tracker domains for every app on the TV. Writing the setting needs WRITE_SECURE_SETTINGS, which can
 * only be granted once over adb (see {@link #grantCommand}); reading it needs nothing.
 */
final class PrivateDns {

    static final String[] NAMES = {"Automatic", "AdGuard DNS", "AdGuard Family"};
    static final String[] NOTES = {"The TV's own DNS (ads not blocked)", "Blocks ads and trackers", "Ads, trackers and adult sites"};
    static final String[] HOSTS = {"", "dns.adguard-dns.com", "family.adguard-dns.com"};

    private static final String MODE = "private_dns_mode";
    private static final String SPECIFIER = "private_dns_specifier";

    private PrivateDns() {
    }

    static boolean supported() {
        return Build.VERSION.SDK_INT >= 28;
    }

    static boolean canWrite(Context c) {
        return c.checkSelfPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED;
    }

    static String grantCommand(Context c) {
        return "adb shell pm grant " + c.getPackageName() + " android.permission.WRITE_SECURE_SETTINGS";
    }

    /** The host in use, or "" when Private DNS is off or automatic. */
    static String current(Context c) {
        try {
            String mode = Settings.Global.getString(c.getContentResolver(), MODE);
            if (!"hostname".equals(mode)) return "";
            String host = Settings.Global.getString(c.getContentResolver(), SPECIFIER);
            return host == null ? "" : host.trim();
        } catch (RuntimeException e) {
            return "";
        }
    }

    /** A usable DNS host name (letters, digits, dots and dashes, at least one dot). */
    static boolean validHost(String h) {
        return h != null && h.length() <= 253 && h.matches("^(?=.{1,253}$)([a-zA-Z0-9]([a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?\\.)+[a-zA-Z]{2,63}$");
    }

    /** host "" = automatic. Returns true when the system now has the value. */
    static boolean set(Context c, String host) {
        if (!supported() || !canWrite(c)) return false;
        try {
            android.content.ContentResolver cr = c.getContentResolver();
            if (host == null || host.isEmpty()) {
                Settings.Global.putString(cr, MODE, "opportunistic");
            } else {
                Settings.Global.putString(cr, SPECIFIER, host);       // host first, then the mode that uses it
                Settings.Global.putString(cr, MODE, "hostname");
            }
            return host == null || host.isEmpty() ? !"hostname".equals(Settings.Global.getString(cr, MODE))
                    : host.equalsIgnoreCase(current(c));
        } catch (RuntimeException e) {
            return false;
        }
    }
}
