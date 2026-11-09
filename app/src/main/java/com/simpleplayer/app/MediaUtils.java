package com.simpleplayer.app;

import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.provider.OpenableColumns;

import androidx.media3.common.AudioAttributes;
import androidx.media3.common.C;
import androidx.media3.exoplayer.DefaultLoadControl;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.extractor.DefaultExtractorsFactory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/** Shared helpers: file-type detection, player creation, formatting. */
final class MediaUtils {

    static final int TYPE_UNKNOWN = 0;
    static final int TYPE_AUDIO = 1;
    static final int TYPE_VIDEO = 2;

    static final String EXTRA_URIS = "uris";

    private static final Set<String> AUDIO_EXT = new HashSet<>(Arrays.asList(
            "mp3", "ogg", "oga", "opus", "wav", "flac", "aac", "m4a", "mka"));
    private static final Set<String> VIDEO_EXT = new HashSet<>(Arrays.asList(
            "mp4", "m4v", "mkv", "avi", "mov", "webm", "3gp"));

    private MediaUtils() {}

    /** Decides whether a file is audio or video: extension first, then MIME type, then content. */
    static int detectType(Context c, Uri uri) {
        String ext = extension(displayName(c, uri));
        if (AUDIO_EXT.contains(ext)) return TYPE_AUDIO;
        if (VIDEO_EXT.contains(ext)) return TYPE_VIDEO;

        String mime = null;
        try {
            mime = c.getContentResolver().getType(uri);
        } catch (Exception ignored) {
        }
        if (mime != null) {
            mime = mime.toLowerCase(Locale.US);
            if (mime.startsWith("audio/") || mime.equals("application/ogg") || mime.equals("application/x-ogg")) return TYPE_AUDIO;
            if (mime.startsWith("video/")) return TYPE_VIDEO;
        }

        MediaMetadataRetriever r = new MediaMetadataRetriever();
        try {
            r.setDataSource(c, uri);
            if ("yes".equals(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO))) {
                return TYPE_VIDEO;
            }
            if ("yes".equals(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO))) {
                return TYPE_AUDIO;
            }
        } catch (Throwable ignored) {
        } finally {
            try {
                r.release();
            } catch (Throwable ignored) {
            }
        }
        return TYPE_UNKNOWN;
    }

    static String displayName(Context c, Uri uri) {
        String name = null;
        if ("content".equals(uri.getScheme())) {
            try (Cursor cur = c.getContentResolver().query(
                    uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
                if (cur != null && cur.moveToFirst()) name = cur.getString(0);
            } catch (Exception ignored) {
            }
        }
        if (name == null) name = uri.getLastPathSegment();
        return name == null ? "" : name;
    }

    static String extension(String name) {
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot == name.length() - 1) return "";
        return name.substring(dot + 1).toLowerCase(Locale.US);
    }

    static String baseName(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    static String formatTime(long ms) {
        if (ms < 0) ms = 0;
        long total = ms / 1000;
        long h = total / 3600;
        long m = (total % 3600) / 60;
        long s = total % 60;
        if (h > 0) return String.format(Locale.US, "%d:%02d:%02d", h, m, s);
        return String.format(Locale.US, "%d:%02d", m, s);
    }

    static ArrayList<Uri> urisFrom(Intent intent) {
        ArrayList<Uri> list = intent.getParcelableArrayListExtra(EXTRA_URIS);
        if (list == null) {
            list = new ArrayList<>();
            if (intent.getData() != null) list.add(intent.getData());
        }
        return list;
    }

    /** Creates a lightly-buffered ExoPlayer. Small buffers keep RAM use low for local files. */
    static ExoPlayer newPlayer(Context c, boolean video) {
        DefaultLoadControl loadControl = new DefaultLoadControl.Builder()
                .setBufferDurationsMs(video ? 5000 : 2500, video ? 20000 : 10000, 1000, 2000)
                .build();
        DefaultRenderersFactory renderers = new DefaultRenderersFactory(c)
                .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF)
                .setEnableDecoderFallback(true);
        // Constant-bitrate seeking makes MP3/AAC files without a seek table seekable.
        DefaultExtractorsFactory extractors = new DefaultExtractorsFactory()
                .setConstantBitrateSeekingEnabled(true);

        AudioAttributes attrs = new AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(video ? C.AUDIO_CONTENT_TYPE_MOVIE : C.AUDIO_CONTENT_TYPE_MUSIC)
                .build();

        ExoPlayer player = new ExoPlayer.Builder(c, renderers)
                .setMediaSourceFactory(new DefaultMediaSourceFactory(c, extractors))
                .setLoadControl(loadControl)
                .setAudioAttributes(attrs, true)
                .setHandleAudioBecomingNoisy(true)
                .setWakeMode(C.WAKE_MODE_LOCAL)
                .build();

        if (!video) {
            // Audio UI never shows video: don't decode embedded cover-art video tracks.
            player.setTrackSelectionParameters(player.getTrackSelectionParameters()
                    .buildUpon()
                    .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, true)
                    .build());
        }
        return player;
    }
}
