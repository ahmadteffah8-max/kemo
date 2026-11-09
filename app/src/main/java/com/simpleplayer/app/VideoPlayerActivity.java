package com.simpleplayer.app;

import android.app.Activity;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Matrix;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.GestureDetector;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.TextureView;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageButton;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.PlaybackParameters;
import androidx.media3.common.Player;
import androidx.media3.common.VideoSize;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.SeekParameters;

import java.util.ArrayList;

/**
 * Video UI: full-screen video with double-tap seek (+/-10s), pinch zoom + pan,
 * 2x speed toggle, volume, fit/fill and portrait/landscape (full-screen) toggle.
 */
public class VideoPlayerActivity extends Activity {

    private static final long SEEK_MS = 10_000L;
    private static final long HIDE_DELAY_MS = 3500L;
    private static final long CHAIN_MS = 800L;
    private static final float MIN_ZOOM = 0.5f;
    private static final float MAX_ZOOM = 6f;

    private final Handler handler = new Handler(Looper.getMainLooper());

    private ExoPlayer player;
    private ArrayList<Uri> uris;

    private View root, controls;
    private TextureView textureView;
    private TextView title, timeCur, timeDur, speedBtn, seekLeft, seekRight;
    private SeekBar seekBar;
    private ImageButton playBtn, fsBtn;
    private VolumeControl volume;

    private boolean controlsVisible = true;
    private boolean dragging;
    private boolean fast;
    private boolean fillMode;

    private float zoom = 1f, panX, panY;
    private int videoW, videoH;
    private float videoPar = 1f;

    private GestureDetector gestures;
    private ScaleGestureDetector scaler;
    private long chainUntil;
    private boolean skipConfirm;
    private long lastPinchEnd;

    private final Runnable hideControls = () -> {
        if (player != null && player.getPlayWhenReady() && !dragging) setControlsVisible(false);
    };
    private final Runnable hideLeft = () -> fadeOut(seekLeft);
    private final Runnable hideRight = () -> fadeOut(seekRight);
    private final Runnable restoreAutoRotate =
            () -> setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            if (controlsVisible) updateProgress();
            handler.postDelayed(this, 300);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_video);
        setVolumeControlStream(AudioManager.STREAM_MUSIC);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (Build.VERSION.SDK_INT >= 28) {
            getWindow().getAttributes().layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        }

        uris = MediaUtils.urisFrom(getIntent());
        if (uris.isEmpty()) {
            finish();
            return;
        }

        root = findViewById(R.id.videoRoot);
        controls = findViewById(R.id.controls);
        textureView = findViewById(R.id.textureView);
        title = findViewById(R.id.title);
        timeCur = findViewById(R.id.timeCur);
        timeDur = findViewById(R.id.timeDur);
        speedBtn = findViewById(R.id.speedBtn);
        seekLeft = findViewById(R.id.seekLeft);
        seekRight = findViewById(R.id.seekRight);
        seekBar = findViewById(R.id.seekBar);
        playBtn = findViewById(R.id.playBtn);
        fsBtn = findViewById(R.id.fsBtn);
        volume = new VolumeControl(this, findViewById(R.id.volumeBar));

        // --- player ---
        player = MediaUtils.newPlayer(this, true);
        player.setSeekParameters(SeekParameters.CLOSEST_SYNC); // fast, smooth +/-10s jumps
        player.setVideoTextureView(textureView);
        for (Uri u : uris) player.addMediaItem(MediaItem.fromUri(u));
        player.addListener(new Player.Listener() {
            @Override
            public void onVideoSizeChanged(VideoSize size) {
                videoW = size.width;
                videoH = size.height;
                videoPar = size.pixelWidthHeightRatio > 0 ? size.pixelWidthHeightRatio : 1f;
                zoom = 1f;
                panX = 0f;
                panY = 0f;
                updateTransform();
            }

            @Override
            public void onMediaItemTransition(MediaItem item, int reason) {
                updateTitle();
            }

            @Override
            public void onPlaybackStateChanged(int state) {
                updatePlayState();
                updateProgress();
            }

            @Override
            public void onPlayWhenReadyChanged(boolean playWhenReady, int reason) {
                updatePlayState();
            }

            @Override
            public void onPlayerError(PlaybackException error) {
                Toast.makeText(VideoPlayerActivity.this, R.string.play_error,
                        Toast.LENGTH_SHORT).show();
                if (player.hasNextMediaItem()) {
                    player.seekToNextMediaItem();
                    player.prepare();
                    player.play();
                }
            }
        });

        // --- controls ---
        findViewById(R.id.backBtn).setOnClickListener(v -> finish());
        playBtn.setOnClickListener(v -> {
            togglePlay();
            showControls();
        });
        speedBtn.setOnClickListener(v -> {
            fast = !fast;
            player.setPlaybackParameters(new PlaybackParameters(fast ? 2f : 1f));
            speedBtn.setText(fast ? "×2" : "×1");
            speedBtn.setTextColor(fast ? getResources().getColor(R.color.accent) : Color.WHITE);
            showControls();
        });
        findViewById(R.id.aspectBtn).setOnClickListener(v -> {
            fillMode = !fillMode;
            updateTransform();
            Toast.makeText(this, fillMode ? R.string.mode_fill : R.string.mode_fit,
                    Toast.LENGTH_SHORT).show();
            showControls();
        });
        fsBtn.setOnClickListener(v -> {
            boolean land = isLandscape();
            handler.removeCallbacks(restoreAutoRotate);
            if (land) {
                setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT);
                handler.postDelayed(restoreAutoRotate, 1500);
            } else {
                setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
            }
            showControls();
        });

        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar s, int progress, boolean fromUser) {
                if (fromUser) timeCur.setText(MediaUtils.formatTime(progress));
            }

            @Override
            public void onStartTrackingTouch(SeekBar s) {
                dragging = true;
                handler.removeCallbacks(hideControls);
            }

            @Override
            public void onStopTrackingTouch(SeekBar s) {
                player.seekTo(s.getProgress());
                dragging = false;
                scheduleHide();
            }
        });

        setupGestures();
        textureView.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            if (r - l != or - ol || b - t != ob - ot) updateTransform();
        });

        player.prepare();
        player.play();
        updateTitle();
        updatePlayState();
        applySystemUi();
    }

    // ------------------------------------------------------------------ gestures

    private void setupGestures() {
        scaler = new ScaleGestureDetector(this,
                new ScaleGestureDetector.SimpleOnScaleGestureListener() {
                    @Override
                    public boolean onScale(ScaleGestureDetector d) {
                        float old = zoom;
                        float nz = Math.max(MIN_ZOOM, Math.min(MAX_ZOOM, zoom * d.getScaleFactor()));
                        float r = nz / old;
                        // keep the point under the fingers fixed while zooming
                        float fx = d.getFocusX() - root.getWidth() / 2f;
                        float fy = d.getFocusY() - root.getHeight() / 2f;
                        panX = fx * (1 - r) + panX * r;
                        panY = fy * (1 - r) + panY * r;
                        zoom = nz;
                        updateTransform();
                        return true;
                    }

                    @Override
                    public void onScaleEnd(ScaleGestureDetector d) {
                        lastPinchEnd = SystemClock.uptimeMillis();
                    }
                });
        scaler.setQuickScaleEnabled(false); // would clash with double-tap seeking

        gestures = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onDown(MotionEvent e) {
                skipConfirm = false;
                return true;
            }

            @Override
            public boolean onSingleTapUp(MotionEvent e) {
                if (busyPinching()) return false;
                // Right after a double-tap, extra single taps keep seeking (fast chain).
                if (SystemClock.uptimeMillis() < chainUntil) {
                    seekTap(e.getX());
                    skipConfirm = true;
                    return true;
                }
                return false;
            }

            @Override
            public boolean onSingleTapConfirmed(MotionEvent e) {
                if (busyPinching()) return false;
                if (skipConfirm) {
                    skipConfirm = false;
                    return true;
                }
                if (controlsVisible) setControlsVisible(false);
                else showControls();
                return true;
            }

            @Override
            public boolean onDoubleTap(MotionEvent e) {
                if (busyPinching()) return false;
                skipConfirm = false;
                seekTap(e.getX());
                return true;
            }

            @Override
            public boolean onScroll(MotionEvent e1, MotionEvent e2, float dx, float dy) {
                if (busyPinching() || e2.getPointerCount() > 1) return false;
                panX -= dx;
                panY -= dy;
                updateTransform();
                return true;
            }
        });
        gestures.setIsLongpressEnabled(false);

        root.setOnTouchListener((v, e) -> {
            scaler.onTouchEvent(e);
            gestures.onTouchEvent(e);
            return true;
        });
    }

    private boolean busyPinching() {
        return scaler.isInProgress() || SystemClock.uptimeMillis() - lastPinchEnd < 250;
    }

    private void seekTap(float x) {
        boolean forward = x > root.getWidth() / 2f;
        seekBy(forward ? SEEK_MS : -SEEK_MS);
        showSeekIndicator(forward);
        chainUntil = SystemClock.uptimeMillis() + CHAIN_MS;
    }

    private void seekBy(long deltaMs) {
        long target = player.getCurrentPosition() + deltaMs;
        long dur = player.getDuration();
        if (dur != C.TIME_UNSET && target > dur) target = dur;
        if (target < 0) target = 0;
        player.seekTo(target);
        updateProgress();
    }

    private void showSeekIndicator(boolean forward) {
        TextView tv = forward ? seekRight : seekLeft;
        Runnable hide = forward ? hideRight : hideLeft;
        handler.removeCallbacks(hide);
        tv.animate().cancel();
        tv.setText(forward ? "+10" : "-10");
        tv.setAlpha(1f);
        tv.setScaleX(0.8f);
        tv.setScaleY(0.8f);
        tv.animate().scaleX(1f).scaleY(1f).setStartDelay(0).setDuration(100).start();
        handler.postDelayed(hide, 550);
    }

    private void fadeOut(View v) {
        v.animate().alpha(0f).setStartDelay(0).setDuration(200).start();
    }

    // ------------------------------------------------------------------ zoom / fit / fill

    private void updateTransform() {
        if (textureView == null) return;
        int w = textureView.getWidth();
        int h = textureView.getHeight();
        if (w == 0 || h == 0 || videoW == 0 || videoH == 0) return;

        float videoAspect = videoW * videoPar / videoH;
        float viewAspect = (float) w / h;
        float sx, sy; // scale of the video relative to the full view
        if (videoAspect > viewAspect) {
            sx = 1f;
            sy = viewAspect / videoAspect;
        } else {
            sx = videoAspect / viewAspect;
            sy = 1f;
        }
        if (fillMode) {
            float k = 1f / Math.min(sx, sy);
            sx *= k;
            sy *= k;
        }
        sx *= zoom;
        sy *= zoom;

        float maxPanX = Math.max(0f, (w * sx - w) / 2f);
        float maxPanY = Math.max(0f, (h * sy - h) / 2f);
        panX = Math.max(-maxPanX, Math.min(maxPanX, panX));
        panY = Math.max(-maxPanY, Math.min(maxPanY, panY));

        Matrix m = new Matrix();
        m.setScale(sx, sy, w / 2f, h / 2f);
        m.postTranslate(panX, panY);
        textureView.setTransform(m);
    }

    // ------------------------------------------------------------------ playback UI

    private void togglePlay() {
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

    private void updatePlayState() {
        if (player == null) return;
        int state = player.getPlaybackState();
        boolean playing = player.getPlayWhenReady()
                && state != Player.STATE_ENDED && state != Player.STATE_IDLE;
        playBtn.setImageResource(playing ? R.drawable.ic_pause : R.drawable.ic_play);
        if (playing) {
            scheduleHide();
        } else {
            handler.removeCallbacks(hideControls);
            if (!controlsVisible) setControlsVisible(true);
        }
    }

    private void updateTitle() {
        int index = player.getCurrentMediaItemIndex();
        if (index < 0 || index >= uris.size()) return;
        title.setText(MediaUtils.displayName(this, uris.get(index)));
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

    // ------------------------------------------------------------------ controls visibility

    private void showControls() {
        setControlsVisible(true);
        scheduleHide();
    }

    private void scheduleHide() {
        handler.removeCallbacks(hideControls);
        if (player != null && player.getPlayWhenReady()) {
            handler.postDelayed(hideControls, HIDE_DELAY_MS);
        }
    }

    private void setControlsVisible(boolean visible) {
        controlsVisible = visible;
        controls.animate().cancel();
        if (visible) {
            controls.setVisibility(View.VISIBLE);
            controls.animate().alpha(1f).setStartDelay(0).setDuration(150).start();
            updateProgress();
        } else {
            controls.animate().alpha(0f).setStartDelay(0).setDuration(150).withEndAction(() -> {
                if (!controlsVisible) controls.setVisibility(View.GONE);
            }).start();
        }
    }

    // ------------------------------------------------------------------ orientation / system bars

    private boolean isLandscape() {
        return getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE;
    }

    @SuppressWarnings("deprecation")
    private void applySystemUi() {
        boolean land = isLandscape();
        int flags = View.SYSTEM_UI_FLAG_LAYOUT_STABLE;
        if (land) {
            flags |= View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY;
        }
        getWindow().getDecorView().setSystemUiVisibility(flags);
        fsBtn.setImageResource(land ? R.drawable.ic_fullscreen_exit : R.drawable.ic_fullscreen);
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        applySystemUi();
        root.post(this::updateTransform);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus && fsBtn != null) applySystemUi();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            if (volume != null) {
                volume.syncSoon();
                showControls();
            }
        }
        return super.onKeyDown(keyCode, event);
    }

    // ------------------------------------------------------------------ lifecycle

    @Override
    protected void onStart() {
        super.onStart();
        if (volume != null) volume.start();
        handler.post(ticker);
    }

    @Override
    protected void onPause() {
        if (player != null) player.pause();
        super.onPause();
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
        if (player != null) {
            player.release();
            player = null;
        }
        super.onDestroy();
    }
}
