package com.simpleplayer.app;

import android.app.Activity;
import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Matrix;
import android.net.Uri;
import android.os.Bundle;
import android.view.TextureView;
import android.widget.ImageButton;
import android.widget.Toast;

import androidx.media3.common.AudioAttributes;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.Player;
import androidx.media3.common.VideoSize;
import androidx.media3.exoplayer.ExoPlayer;

import java.util.ArrayList;
import java.util.List;

/**
 * Home screen: a looping background video with three buttons on top
 * (play media / record screen / trim video). Also routes "Open with" intents.
 */
public class MainActivity extends Activity {

    private static final int REQ_PICK = 1;
    private static final String PREFS = "simple_prefs";
    private static final String KEY_MUTED = "bg_muted";

    private TextureView bgView;
    private ImageButton muteBtn;
    private ExoPlayer bgPlayer;
    private boolean muted;
    private int videoW, videoH;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // "Open with Simple Player" from a file manager / other app.
        Intent in = getIntent();
        if (Intent.ACTION_VIEW.equals(in.getAction()) && in.getData() != null) {
            List<Uri> one = new ArrayList<>();
            one.add(in.getData());
            if (openMedia(one)) {
                finish();
                return;
            }
        }

        setContentView(R.layout.activity_main);
        bgView = findViewById(R.id.bgVideo);
        muteBtn = findViewById(R.id.muteBtn);
        findViewById(R.id.openButton).setOnClickListener(v -> pickFiles());
        findViewById(R.id.recordButton).setOnClickListener(
                v -> startActivity(new Intent(this, RecorderActivity.class)));
        findViewById(R.id.trimButton).setOnClickListener(
                v -> startActivity(new Intent(this, EditorActivity.class)));

        // The mute choice is remembered, so it stays muted every time the app is opened.
        muted = getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_MUTED, false);
        updateMuteIcon();
        muteBtn.setOnClickListener(v -> {
            muted = !muted;
            getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                    .putBoolean(KEY_MUTED, muted).apply();
            applyMute();
            updateMuteIcon();
        });
        bgView.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            if (r - l != or - ol || b - t != ob - ot) updateBgTransform();
        });
    }

    // ------------------------------------------------------------ background video

    @Override
    protected void onStart() {
        super.onStart();
        if (bgView != null) startBackground();
    }

    @Override
    protected void onStop() {
        stopBackground();
        super.onStop();
    }

    private void startBackground() {
        if (bgPlayer != null) return;
        bgPlayer = MediaUtils.newPlayer(this, true);
        bgPlayer.setRepeatMode(Player.REPEAT_MODE_ALL);
        bgPlayer.setVideoTextureView(bgView);
        bgPlayer.addListener(new Player.Listener() {
            @Override
            public void onVideoSizeChanged(VideoSize size) {
                videoW = size.width;
                videoH = size.height;
                updateBgTransform();
            }
        });
        bgPlayer.setMediaItem(MediaItem.fromUri(
                Uri.parse("android.resource://" + getPackageName() + "/" + R.raw.bg_video)));
        applyMute();
        bgPlayer.prepare();
        bgPlayer.play();
    }

    private void stopBackground() {
        if (bgPlayer != null) {
            bgPlayer.release();
            bgPlayer = null;
        }
    }

    /** Muted = audio track off and no audio focus, so other apps' music is never interrupted. */
    private void applyMute() {
        if (bgPlayer == null) return;
        AudioAttributes attrs = new AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                .build();
        bgPlayer.setAudioAttributes(attrs, !muted);
        bgPlayer.setTrackSelectionParameters(bgPlayer.getTrackSelectionParameters()
                .buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, muted)
                .build());
        bgPlayer.setVolume(muted ? 0f : 1f);
    }

    private void updateMuteIcon() {
        muteBtn.setImageResource(muted ? R.drawable.ic_volume_off : R.drawable.ic_volume);
    }

    /** Center-crop: the video always fills the whole screen. */
    private void updateBgTransform() {
        int w = bgView.getWidth();
        int h = bgView.getHeight();
        if (w == 0 || h == 0 || videoW == 0 || videoH == 0) return;
        float videoAspect = (float) videoW / videoH;
        float viewAspect = (float) w / h;
        float sx = 1f, sy = 1f;
        if (videoAspect > viewAspect) sx = videoAspect / viewAspect;
        else sy = viewAspect / videoAspect;
        Matrix m = new Matrix();
        m.setScale(sx, sy, w / 2f, h / 2f);
        bgView.setTransform(m);
    }

    private void pickFiles() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        i.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{
                "audio/*", "video/*", "application/ogg", "application/x-ogg", "application/x-matroska"});
        i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        startActivityForResult(i, REQ_PICK);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_PICK || resultCode != RESULT_OK || data == null) return;

        List<Uri> picked = new ArrayList<>();
        ClipData clip = data.getClipData();
        if (clip != null) {
            for (int i = 0; i < clip.getItemCount(); i++) picked.add(clip.getItemAt(i).getUri());
        } else if (data.getData() != null) {
            picked.add(data.getData());
        }
        if (!openMedia(picked)) {
            Toast.makeText(this, R.string.unsupported_file, Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * Detects the type of the first recognised file, keeps only files of that same type
     * (the two players are never mixed) and starts the matching player.
     */
    private boolean openMedia(List<Uri> uris) {
        int type = MediaUtils.TYPE_UNKNOWN;
        ArrayList<Uri> playlist = new ArrayList<>();
        for (Uri u : uris) {
            int t = MediaUtils.detectType(this, u);
            if (t == MediaUtils.TYPE_UNKNOWN) continue;
            if (type == MediaUtils.TYPE_UNKNOWN) type = t;
            if (t == type) playlist.add(u);
        }
        if (playlist.isEmpty()) return false;

        Intent play = new Intent(this, type == MediaUtils.TYPE_AUDIO
                ? MusicPlayerActivity.class : VideoPlayerActivity.class);
        play.putParcelableArrayListExtra(MediaUtils.EXTRA_URIS, playlist);

        ClipData clip = ClipData.newRawUri("media", playlist.get(0));
        for (int i = 1; i < playlist.size(); i++) clip.addItem(new ClipData.Item(playlist.get(i)));
        play.setClipData(clip);
        play.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivity(play);
        return true;
    }
}
