package com.simpleplayer.app;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.ParcelFileDescriptor;
import android.provider.MediaStore;

import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * A new media file saved to the public "Simple" folder (Movies/Simple or Music/Simple).
 * Android 10+ uses MediaStore (no permission). Android 9 and older write straight to the public
 * folder, which needs the storage permission; without it the file goes to the app's own hidden
 * folder as a last resort.
 */
final class MediaOutput {

    final Uri uri;
    final boolean audio;
    /** Only set on Android 9 and older. */
    final File file;
    private final Context context;
    private final String mime;
    private final String shownPath;
    private ParcelFileDescriptor pfd;

    private MediaOutput(Context c, Uri uri, File file, boolean audio, String mime, String shownPath) {
        this.context = c.getApplicationContext();
        this.uri = uri;
        this.file = file;
        this.audio = audio;
        this.mime = mime;
        this.shownPath = shownPath;
    }

    /** True on Android 9 and older when the storage permission is still missing. */
    static boolean needsStoragePermission(Context c) {
        return Build.VERSION.SDK_INT < 29 && c.checkSelfPermission(
                Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED;
    }

    /** Human readable place of the file, e.g. "Movies/Simple/Screen_20260101_120000.mp4". */
    String displayPath() {
        return shownPath;
    }

    static MediaOutput create(Context c, String displayName, String mime, boolean audio)
            throws IOException {
        if (Build.VERSION.SDK_INT >= 29) {
            ContentValues v = new ContentValues();
            v.put(MediaStore.MediaColumns.DISPLAY_NAME, displayName);
            v.put(MediaStore.MediaColumns.MIME_TYPE, mime);
            v.put(MediaStore.MediaColumns.RELATIVE_PATH,
                    (audio ? Environment.DIRECTORY_MUSIC : Environment.DIRECTORY_MOVIES) + "/Simple");
            v.put(MediaStore.MediaColumns.IS_PENDING, 1);
            Uri collection = audio
                    ? MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                    : MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
            Uri uri = c.getContentResolver().insert(collection, v);
            if (uri == null) throw new IOException("MediaStore insert failed");
            String rel = (audio ? Environment.DIRECTORY_MUSIC : Environment.DIRECTORY_MOVIES)
                    + "/Simple/" + displayName;
            return new MediaOutput(c, uri, null, audio, mime, rel);
        }

        String type = audio ? Environment.DIRECTORY_MUSIC : Environment.DIRECTORY_MOVIES;
        File dir = null;
        if (!needsStoragePermission(c)) {
            // Public folder: shows up in the gallery / music apps and any file manager.
            File pub = new File(Environment.getExternalStoragePublicDirectory(type), "Simple");
            if (pub.isDirectory() || pub.mkdirs()) dir = pub;
        }
        if (dir == null) dir = c.getExternalFilesDir(type);
        if (dir == null) dir = c.getFilesDir();
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        File f = new File(dir, displayName);
        String shown = f.getAbsolutePath();
        String root = Environment.getExternalStorageDirectory().getAbsolutePath() + "/";
        if (shown.startsWith(root)) shown = shown.substring(root.length());
        return new MediaOutput(c, Uri.fromFile(f), f, audio, mime, shown);
    }

    /** Descriptor for MediaMuxer on Android 10+ (null when {@link #file} is used). */
    FileDescriptor openFd() throws IOException {
        if (file != null) return null;
        pfd = context.getContentResolver().openFileDescriptor(uri, "rw");
        if (pfd == null) throw new IOException("cannot open " + uri);
        return pfd.getFileDescriptor();
    }

    OutputStream openStream() throws IOException {
        if (file != null) return new java.io.FileOutputStream(file);
        OutputStream os = context.getContentResolver().openOutputStream(uri, "w");
        if (os == null) throw new IOException("cannot open " + uri);
        return os;
    }

    /** Copies a finished temporary file into this output. */
    void copyFrom(File src) throws IOException {
        try (InputStream in = new FileInputStream(src); OutputStream out = openStream()) {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        }
    }

    /** Makes the file visible (ok) or removes it (failure). */
    void finish(boolean ok) {
        try {
            if (pfd != null) pfd.close();
        } catch (IOException ignored) {
        }
        pfd = null;
        ContentResolver cr = context.getContentResolver();
        try {
            if (file != null) {
                if (!ok) {
                    //noinspection ResultOfMethodCallIgnored
                    file.delete();
                } else {
                    // Tell the gallery / music apps about the new file right away.
                    MediaScannerConnection.scanFile(context,
                            new String[]{file.getAbsolutePath()}, new String[]{mime}, null);
                }
            } else if (ok) {
                if (Build.VERSION.SDK_INT >= 29) {
                    ContentValues v = new ContentValues();
                    v.put(MediaStore.MediaColumns.IS_PENDING, 0);
                    cr.update(uri, v, null, null);
                }
            } else {
                cr.delete(uri, null, null);
            }
        } catch (Exception ignored) {
        }
    }
}
