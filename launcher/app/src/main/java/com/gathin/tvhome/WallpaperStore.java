package com.gathin.tvhome;

import android.content.Context;
import android.net.Uri;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Keeps the chosen wallpaper as a private copy inside the app. The picked content:// link can lose its
 * permission (or its provider may not be ready yet) after a TV restart; the private copy always opens.
 */
final class WallpaperStore {

    private WallpaperStore() {
    }

    private static File file(Context c) {
        return new File(c.getFilesDir(), "wallpaper.img");
    }

    static boolean exists(Context c) {
        File f = file(c);
        return f.isFile() && f.length() > 0;
    }

    static long length(Context c) {
        return file(c).length();
    }

    static Uri uri(Context c) {
        return Uri.fromFile(file(c));
    }

    static void delete(Context c) {
        file(c).delete();
    }

    /** Copies the picked image into the app's storage, replacing the previous wallpaper. */
    static void copyFrom(Context c, Uri source) throws IOException {
        File tmp = new File(c.getFilesDir(), "wallpaper.tmp");
        try (InputStream in = c.getContentResolver().openInputStream(source);
             OutputStream out = new FileOutputStream(tmp)) {
            if (in == null) throw new IOException("Cannot open wallpaper");
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        }
        File dst = file(c);
        dst.delete();
        if (!tmp.renameTo(dst)) throw new IOException("Cannot save wallpaper");
    }

    // ---- PNG over the dock: a transparent PNG drawn over the dock and its apps ----

    private static File dock(Context c) {
        return new File(c.getFilesDir(), "dock_overlay.png");
    }

    static boolean hasDock(Context c) {
        File f = dock(c);
        return f.isFile() && f.length() > 0;
    }

    static String dockPath(Context c) {
        return dock(c).getAbsolutePath();
    }

    static void deleteDock(Context c) {
        dock(c).delete();
    }

    /** Copies the picked PNG as it is, so its transparency is kept. */
    static void copyDock(Context c, Uri source) throws IOException {
        File tmp = new File(c.getFilesDir(), "dock_overlay.tmp");
        try (InputStream in = c.getContentResolver().openInputStream(source);
             OutputStream out = new FileOutputStream(tmp)) {
            if (in == null) throw new IOException("Cannot open image");
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        }
        File dst = dock(c);
        dst.delete();
        if (!tmp.renameTo(dst)) throw new IOException("Cannot save image");
    }

    // ---- depth foreground: a transparent PNG drawn over the glow, under the apps ----

    private static File front(Context c) {
        return new File(c.getFilesDir(), "wallpaper_front.png");
    }

    static boolean hasFront(Context c) {
        File f = front(c);
        return f.isFile() && f.length() > 0;
    }

    static Uri frontUri(Context c) {
        return Uri.fromFile(front(c));
    }

    static void deleteFront(Context c) {
        front(c).delete();
    }

    /** Copies the picked PNG as it is, so its transparency is kept. */
    static void copyFront(Context c, Uri source) throws IOException {
        File tmp = new File(c.getFilesDir(), "wallpaper_front.tmp");
        try (InputStream in = c.getContentResolver().openInputStream(source);
             OutputStream out = new FileOutputStream(tmp)) {
            if (in == null) throw new IOException("Cannot open image");
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        }
        File dst = front(c);
        dst.delete();
        if (!tmp.renameTo(dst)) throw new IOException("Cannot save image");
    }

    // ---- default pictures shipped inside the app (assets/default) ----

    /** Copies a picture from the app's assets over the matching private file. which: 0 wallpaper, 1 depth, 2 dock. */
    static void seedFromAssets(Context c, String assetName, int which) throws IOException {
        File dst = which == 0 ? file(c) : which == 1 ? front(c) : dock(c);
        File tmp = new File(c.getFilesDir(), "seed.tmp");
        try (InputStream in = c.getAssets().open("default/" + assetName);
             OutputStream out = new FileOutputStream(tmp)) {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        }
        dst.delete();
        if (!tmp.renameTo(dst)) throw new IOException("Cannot save default picture");
    }
}
