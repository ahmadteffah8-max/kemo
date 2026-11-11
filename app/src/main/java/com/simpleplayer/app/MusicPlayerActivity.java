package com.simpleplayer.app;

import android.Manifest;
import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.AudioManager;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.view.KeyEvent;
import android.widget.ImageButton;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;

import java.util.ArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Music UI: big square cover, title/artist, progress, transport controls and volume.
 * The player itself lives in {@link MusicService}, so music keeps playing in the background.
 */
public class MusicPlayerActivity extends Activity {

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService metaExecutor = Executors.newSingleThreadExecutor();

    private ExoPlayer player; // owned by MusicService; null until connected
    private ArrayList<Uri> uris;
    private boolean bound;

    private SquareImageView cover;
    private TextView title, artist, timeCur, timeDur;
    private SeekBar seekBar;
    private ImageButton playBtn, prevBtn, nextBtn;
    private VolumeControl volume;
    private boolean dragging;

    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            updateProgress();
            handler.postDelayed(this, 400);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_music);
        setVolumeControlStream(AudioManager.STREAM_MUSIC);

        uris = MediaUtils.urisFrom(getIntent());
        if (uris.isEmpty()) {
            finish();
            return;
        }

        cover = findViewById(R.id.cover);
        title = findViewById(R.id.title);
        artist = findViewById(R.id.artist);
        timeCur = findViewById(R.id.timeCur);
        timeDur = findViewById(R.id.timeDur);
        seekBar = findViewById(R.id.seekBar);
        playBtn = findViewById(R.id.playBtn);
        prevBtn = findViewById(R.id.prevBtn);
        nextBtn = findViewById(R.id.nextBtn);
        volume = new VolumeControl(this, findViewById(R.id.volumeBar));

        findViewById(R.id.backBtn).setOnClickListener(v -> finish());
        playBtn.setOnClickListener(v -> togglePlay());
        prevBtn.setOnClickListener(v -> {
            if (player != null) player.seekToPrevious();
        });
        nextBtn.setOnClickListener(v -> {
            if (player != null) player.seekToNext();
        });

        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar s, int progress, boolean fromUser) {
                if (fromUser) timeCur.setText(MediaUtils.formatTime(progress));
            }

            @Override
            public void onStartTrackingTouch(SeekBar s) {
                dragging = true;
            }

            @Override
            public void onStopTrackingTouch(SeekBar s) {
                if (player != null) player.seekTo(s.getProgress());
                dragging = false;
            }
        });

        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(
                Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 20);
        }

        // Start (or continue) playback in the service and connect to its player.
        Intent svc = new Intent(this, MusicService.class)
                .setAction(MusicService.ACTION_PLAY)
                .putParcelableArrayListExtra(MediaUtils.EXTRA_URIS, uris);
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(svc);
        else startService(svc);
        bound = bindService(new Intent(this, MusicService.class), connection,
                Context.BIND_AUTO_CREATE);
        updateUi();
    }

    private final Player.Listener playerListener = new Player.Listener() {
        @Override
        public void onPlaybackStateChanged(int state) {
            updateUi();
            updateProgress();
        }

        @Override
        public void onPlayWhenReadyChanged(boolean playWhenReady, int reason) {
            updateUi();
        }

        @Override
        public void onIsPlayingChanged(boolean isPlaying) {
            updateUi();
        }

        @Override
        public void onMediaItemTransition(MediaItem item, int reason) {
            if (player != null) loadMeta(player.getCurrentMediaItemIndex());
            updateUi();
            updateProgress();
        }

        @Override
        public void onPlayerError(PlaybackException error) {
            Toast.makeText(MusicPlayerActivity.this, R.string.play_error,
                    Toast.LENGTH_SHORT).show();
        }
    };

    private final ServiceConnection connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            player = ((MusicService.LocalBinder) binder).service().getPlayer();
            if (player == null) return;
            player.addListener(playerListener);
            loadMeta(Math.max(0, player.getCurrentMediaItemIndex()));
            updateUi();
            updateProgress();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            player = null;
        }
    };

    private void togglePlay() {
        if (player == null) return;
        int state = player.getPlaybackState();
        if (state == Player.STATE_ENDED) {
            player.seekTo(0, 0);
            player.play();
        } else if (state == Player.STATE_IDLE) {
            player.prepare();
            player.play();
        } else if (player.getPlayWhenReady()) {
            player.pause();
        } else {
            player.play();
        }
    }

    private void updateUi() {
        if (player == null) return;
        int state = player.getPlaybackState();
        boolean playing = player.getPlayWhenReady()
                && state != Player.STATE_ENDED && state != Player.STATE_IDLE;
        playBtn.setImageResource(playing ? R.drawable.ic_pause : R.drawable.ic_play);
        nextBtn.setAlpha(player.hasNextMediaItem() ? 1f : 0.35f);
    }

    private void updateProgress() {
        if (player == null) return;
        long dur = player.getDuration();
        if (dur == C.TIME_UNSET || dur < 0) dur = 0;
        int max = (int) Math.min(dur, Integer.MAX_VALUE);
        if (seekBar.getMax() != max) seekBar.setMax(max);
        timeDur.setText(MediaUtils.formatTime(dur));
        if (!dragging) {
            long pos = player.getCurrentPosition();
            seekBar.setProgress((int) Math.min(pos, max));
            timeCur.setText(MediaUtils.formatTime(pos));
        }
    }

    /** Reads title / artist / embedded cover on a background thread. */
    private void loadMeta(final int index) {
        if (index < 0 || index >= uris.size()) return;
        final Uri uri = uris.get(index);
        metaExecutor.execute(() -> {
            String t = null;
            String a = null;
            Bitmap bmp = null;
            MediaMetadataRetriever r = new MediaMetadataRetriever();
            try {
                r.setDataSource(getApplicationContext(), uri);
                t = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE);
                a = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST);
                if (a == null || a.trim().isEmpty()) {
                    a = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST);
                }
                byte[] pic = r.getEmbeddedPicture();
                if (pic != null) bmp = decodeSampled(pic, 720);
            } catch (Throwable ignored) {
            } finally {
                try {
                    r.release();
                } catch (Throwable ignored) {
                }
            }
            final String ft = (t == null || t.trim().isEmpty())
                    ? MediaUtils.baseName(MediaUtils.displayName(getApplicationContext(), uri))
                    : t.trim();
            final String fa = (a == null || a.trim().isEmpty())
                    ? getString(R.string.unknown_artist) : a.trim();
            final Bitmap fb = bmp;
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed() || player == null
                        || player.getCurrentMediaItemIndex() != index) {
                    return;
                }
                title.setText(ft);
                artist.setText(fa);
                if (fb != null) cover.setImageBitmap(fb);
                else cover.setImageResource(R.drawable.cover_default);
            });
        });
    }

    private static Bitmap decodeSampled(byte[] data, int maxSide) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(data, 0, data.length, o);
        int sample = 1;
        while (o.outWidth / sample > maxSide * 2 || o.outHeight / sample > maxSide * 2) sample *= 2;
        o = new BitmapFactory.Options();
        o.inSampleSize = sample;
        return BitmapFactory.decodeByteArray(data, 0, data.length, o);
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (volume != null && (keyCode == KeyEvent.KEYCODE_VOLUME_UP
                || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN)) {
            volume.syncSoon();
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (volume != null) volume.start();
        handler.post(ticker);
    }

    @Override
    protected void onStop() {
        handler.removeCallbacks(ticker);
        if (volume != null) volume.stop();
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        metaExecutor.shutdownNow();
        if (player != null) {
            player.removeListener(playerListener);
            player = null;
        }
        // The service (and the music) keeps going after the screen is closed.
        if (bound) {
            unbindService(connection);
            bound = false;
        }
        super.onDestroy();
    }
}
