package com.simpleplayer.app;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
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
 * A new media file saved to the public "Simple" folder (Movies/Simple or Music/Simple) on
 * Android 10+, or to the app's own external folder on older versions (no storage permission).
 */
final class MediaOutput {

    final Uri uri;
    final boolean audio;
    /** Only set on Android 9 and older. */
    final File file;
    private final Context context;
    private ParcelFileDescriptor pfd;

    private MediaOutput(Context c, Uri uri, File file, boolean audio) {
        this.context = c.getApplicationContext();
        this.uri = uri;
        this.file = file;
        this.audio = audio;
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
            return new MediaOutput(c, uri, null, audio);
        }
        File dir = c.getExternalFilesDir(
                audio ? Environment.DIRECTORY_MUSIC : Environment.DIRECTORY_MOVIES);
        if (dir == null) dir = c.getFilesDir();
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        File f = new File(dir, displayName);
        return new MediaOutput(c, Uri.fromFile(f), f, audio);
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
                if (!ok) //noinspection ResultOfMethodCallIgnored
                    file.delete();
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
