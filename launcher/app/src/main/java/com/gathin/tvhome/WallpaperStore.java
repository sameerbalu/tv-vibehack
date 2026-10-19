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
}
