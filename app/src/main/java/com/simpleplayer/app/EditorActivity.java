package com.simpleplayer.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.RectF;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.media3.common.C;
import androidx.media3.common.Effect;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.VideoSize;
import androidx.media3.common.audio.AudioProcessor;
import androidx.media3.effect.Crop;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.transformer.Composition;
import androidx.media3.transformer.DefaultEncoderFactory;
import androidx.media3.transformer.EditedMediaItem;
import androidx.media3.transformer.Effects;
import androidx.media3.transformer.ExportException;
import androidx.media3.transformer.ExportResult;
import androidx.media3.transformer.ProgressHolder;
import androidx.media3.transformer.Transformer;
import androidx.media3.transformer.VideoEncoderSettings;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.Locale;

/**
 * Video trimmer: pick a video, preview it, choose the part to keep on a zoomable timeline,
 * optionally crop the frame, mute it or keep the audio only, then save.
 */
public class EditorActivity extends Activity {

    private static final int REQ_PICK = 1;
    private static final int MODE_VIDEO = 0, MODE_MUTE = 1, MODE_AUDIO = 2;

    private final Handler handler = new Handler(Looper.getMainLooper());

    private ExoPlayer player;
    private Uri uri;
    private long durationMs;
    private int videoW, videoH;
    private int mode = MODE_VIDEO;
    private boolean cropOn;

    private AspectFrameLayout previewFrame;
    private TextureView textureView;
    private CropOverlayView cropOverlay;
    private TrimBarView trimBar;
    private ImageButton playBtn;
    private TextView timeText, startText, endText, lengthText, emptyText, progressText;
    private TextView modeVideo, modeMute, modeAudio;
    private View cropRow, progressOverlay;
    private LinearLayout cropChips;
    private ProgressBar progressBar;
    private Button cropBtn;
    private final ArrayList<TextView> chips = new ArrayList<>();

    private Transformer transformer;
    private File tmpFile;
    private boolean exporting;

    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            tick();
            handler.postDelayed(this, 40);
        }
    };
    private final Runnable progressPoll = new Runnable() {
        @Override
        public void run() {
            if (!exporting || transformer == null) return;
            ProgressHolder ph = new ProgressHolder();
            int st = transformer.getProgress(ph);
            if (st == Transformer.PROGRESS_STATE_AVAILABLE) progressBar.setProgress(ph.progress);
            handler.postDelayed(this, 250);
        }
    };

    // ================================================================== setup

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_editor);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        previewFrame = findViewById(R.id.previewFrame);
        textureView = findViewById(R.id.textureView);
        cropOverlay = findViewById(R.id.cropOverlay);
        trimBar = findViewById(R.id.trimBar);
        playBtn = findViewById(R.id.playBtn);
        timeText = findViewById(R.id.timeText);
        startText = findViewById(R.id.startText);
        endText = findViewById(R.id.endText);
        lengthText = findViewById(R.id.lengthText);
        emptyText = findViewById(R.id.emptyText);
        modeVideo = findViewById(R.id.modeVideo);
        modeMute = findViewById(R.id.modeMute);
        modeAudio = findViewById(R.id.modeAudio);
        cropRow = findViewById(R.id.cropRow);
        cropChips = findViewById(R.id.cropChips);
        cropBtn = findViewById(R.id.cropBtn);
        progressOverlay = findViewById(R.id.progressOverlay);
        progressText = findViewById(R.id.progressText);
        progressBar = findViewById(R.id.progressBar);

        findViewById(R.id.backBtn).setOnClickListener(v -> finish());
        findViewById(R.id.pickBtn).setOnClickListener(v -> pick());
        emptyText.setOnClickListener(v -> pick());
        playBtn.setOnClickListener(v -> togglePlay());
        findViewById(R.id.zoomIn).setOnClickListener(v -> trimBar.zoomBy(1.8f));
        findViewById(R.id.zoomOut).setOnClickListener(v -> trimBar.zoomBy(1 / 1.8f));
        findViewById(R.id.saveBtn).setOnClickListener(v -> save());
        findViewById(R.id.cancelBtn).setOnClickListener(v -> cancelExport());
        cropBtn.setOnClickListener(v -> toggleCrop());

        modeVideo.setOnClickListener(v -> setMode(MODE_VIDEO));
        modeMute.setOnClickListener(v -> setMode(MODE_MUTE));
        modeAudio.setOnClickListener(v -> setMode(MODE_AUDIO));
        setMode(MODE_VIDEO);
        buildCropChips();
        setLoaded(false);

        trimBar.setListener(new TrimBarView.Listener() {
            @Override
            public void onSeek(long ms) {
                if (player != null) player.seekTo(ms);
                updateLabels();
            }

            @Override
            public void onHandleDragging(boolean isStart, long ms) {
                if (player == null) return;
                player.pause();
                player.seekTo(ms);
            }

            @Override
            public void onRangeChanged(long s, long e) {
                updateLabels();
            }
        });

        // First time in: open the picker straight away.
        if (savedInstanceState == null) pick();
    }

    private void pick() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("video/*");
        try {
            startActivityForResult(i, REQ_PICK);
        } catch (Exception e) {
            Toast.makeText(this, R.string.unsupported_file, Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req != REQ_PICK || res != RESULT_OK || data == null || data.getData() == null) return;
        load(data.getData());
    }

    // ================================================================== loading / playback

    private void load(Uri u) {
        releasePlayer();
        uri = u;
        durationMs = 0;
        videoW = 0;
        videoH = 0;
        cropOn = false;
        cropOverlay.setVisibility(View.GONE);
        cropRow.setVisibility(View.GONE);

        // Duration and size first (also validates the file).
        MediaMetadataRetriever r = new MediaMetadataRetriever();
        try {
            r.setDataSource(this, u);
            String dur = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            String w = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH);
            String h = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT);
            String rot = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION);
            durationMs = dur != null ? Long.parseLong(dur) : 0;
            videoW = w != null ? Integer.parseInt(w) : 0;
            videoH = h != null ? Integer.parseInt(h) : 0;
            if (rot != null && (rot.equals("90") || rot.equals("270"))) {
                int t = videoW;
                videoW = videoH;
                videoH = t;
            }
        } catch (Throwable t) {
            durationMs = 0;
        } finally {
            try {
                r.release();
            } catch (Throwable ignored) {
            }
        }
        if (durationMs <= 0 || videoW <= 0 || videoH <= 0) {
            uri = null;
            setLoaded(false);
            Toast.makeText(this, R.string.unsupported_file, Toast.LENGTH_SHORT).show();
            return;
        }

        previewFrame.setAspect((float) videoW / videoH);
        trimBar.setSource(u, durationMs, (float) videoW / videoH);
        setLoaded(true);

        player = MediaUtils.newPlayer(this, true);
        player.setVideoTextureView(textureView);
        player.setMediaItem(MediaItem.fromUri(u));
        player.addListener(new Player.Listener() {
            @Override
            public void onVideoSizeChanged(VideoSize size) {
                if (size.width > 0 && size.height > 0) {
                    videoW = size.width;
                    videoH = size.height;
                    previewFrame.setAspect((float) videoW / videoH);
                }
            }

            @Override
            public void onIsPlayingChanged(boolean playing) {
                playBtn.setImageResource(playing ? R.drawable.ic_pause : R.drawable.ic_play);
            }

            @Override
            public void onPlayerError(PlaybackException error) {
                Toast.makeText(EditorActivity.this, R.string.play_error, Toast.LENGTH_SHORT).show();
            }
        });
        player.prepare();
        player.play(); // "upload and play"
        updateLabels();
    }

    private void setLoaded(boolean loaded) {
        emptyText.setVisibility(loaded ? View.GONE : View.VISIBLE);
        previewFrame.setVisibility(loaded ? View.VISIBLE : View.INVISIBLE);
        trimBar.setVisibility(loaded ? View.VISIBLE : View.INVISIBLE);
        findViewById(R.id.saveBtn).setEnabled(loaded);
        playBtn.setEnabled(loaded);
    }

    private void togglePlay() {
        if (player == null) return;
        if (player.isPlaying()) {
            player.pause();
        } else {
            long pos = player.getCurrentPosition();
            if (pos < trimBar.getStartMs() || pos >= trimBar.getEndMs() - 80) {
                player.seekTo(trimBar.getStartMs());
            }
            player.play();
        }
    }

    /** Keeps playback inside the selected range and moves the playhead. */
    private void tick() {
        if (player == null || exporting) return;
        long pos = player.getCurrentPosition();
        if (player.isPlaying()) {
            if (pos >= trimBar.getEndMs()) {
                player.pause();
                player.seekTo(trimBar.getEndMs());
                pos = trimBar.getEndMs();
            } else if (pos < trimBar.getStartMs() - 200) {
                player.seekTo(trimBar.getStartMs());
            }
        }
        trimBar.setPlayhead(pos);
        timeText.setText(fmt(pos) + " / " + fmt(durationMs));
    }

    private void updateLabels() {
        long s = trimBar.getStartMs(), e = trimBar.getEndMs();
        startText.setText(getString(R.string.ed_start) + "  " + fmt(s));
        endText.setText(getString(R.string.ed_end) + "  " + fmt(e));
        lengthText.setText(getString(R.string.ed_length) + "  " + fmt(e - s));
    }

    private static String fmt(long ms) {
        long tenths = (ms % 1000) / 100;
        return MediaUtils.formatTime(ms) + "." + tenths;
    }

    // ================================================================== mode / crop

    private void setMode(int m) {
        mode = m;
        modeVideo.setSelected(m == MODE_VIDEO);
        modeMute.setSelected(m == MODE_MUTE);
        modeAudio.setSelected(m == MODE_AUDIO);
        modeVideo.setTextColor(m == MODE_VIDEO ? 0xFF000000 : 0xFFFFFFFF);
        modeMute.setTextColor(m == MODE_MUTE ? 0xFF000000 : 0xFFFFFFFF);
        modeAudio.setTextColor(m == MODE_AUDIO ? 0xFF000000 : 0xFFFFFFFF);
        boolean audioOnly = m == MODE_AUDIO;
        cropBtn.setEnabled(!audioOnly);
        cropBtn.setAlpha(audioOnly ? 0.4f : 1f);
        if (audioOnly && cropOn) toggleCrop();
    }

    private void toggleCrop() {
        if (uri == null) return;
        cropOn = !cropOn;
        cropOverlay.setVisibility(cropOn ? View.VISIBLE : View.GONE);
        cropRow.setVisibility(cropOn ? View.VISIBLE : View.GONE);
        cropBtn.setBackgroundResource(cropOn ? R.drawable.btn_accent : R.drawable.bg_btn_outline);
        cropBtn.setTextColor(cropOn ? 0xFF000000 : 0xFFFFFFFF);
        if (!cropOn) cropOverlay.reset();
    }

    private void buildCropChips() {
        String[] names = {getString(R.string.ed_crop_free), "1:1", "9:16", "16:9", "4:5", "4:3",
                getString(R.string.ed_crop_reset)};
        final float[] ratios = {0f, 1f, 9f / 16f, 16f / 9f, 4f / 5f, 4f / 3f, -1f};
        float d = getResources().getDisplayMetrics().density;
        for (int i = 0; i < names.length; i++) {
            final TextView t = new TextView(this);
            t.setText(names[i]);
            t.setTextSize(14);
            t.setTextColor(0xFFFFFFFF);
            t.setGravity(android.view.Gravity.CENTER);
            t.setBackgroundResource(R.drawable.bg_chip);
            t.setPadding((int) (16 * d), 0, (int) (16 * d), 0);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, (int) (36 * d));
            lp.setMargins((int) (4 * d), 0, (int) (4 * d), 0);
            final float ratio = ratios[i];
            t.setOnClickListener(v -> {
                if (ratio < 0) {
                    cropOverlay.reset();
                    selectChip(chips.get(0));
                } else {
                    cropOverlay.setRatio(ratio);
                    selectChip(t);
                }
            });
            cropChips.addView(t, lp);
            chips.add(t);
        }
        selectChip(chips.get(0));
    }

    private void selectChip(TextView sel) {
        for (TextView c : chips) {
            c.setSelected(c == sel);
            c.setTextColor(c == sel ? 0xFF000000 : 0xFFFFFFFF);
        }
    }

    // ================================================================== export

    private void save() {
        if (uri == null || exporting) return;
        long s = trimBar.getStartMs(), e = trimBar.getEndMs();
        boolean trimmed = s > 50 || e < durationMs - 50;
        boolean audioOnly = mode == MODE_AUDIO;

        // Crop rectangle in whole, even pixels (encoders need even sizes).
        Crop crop = null;
        if (cropOn && !audioOnly && !cropOverlay.isFull() && videoW > 0 && videoH > 0) {
            RectF n = new RectF();
            cropOverlay.getNormalized(n);
            int x0 = Math.round(n.left * videoW) & ~1;
            int y0 = Math.round(n.top * videoH) & ~1;
            int w = Math.round((n.right - n.left) * videoW) & ~1;
            int h = Math.round((n.bottom - n.top) * videoH) & ~1;
            w = Math.max(16, Math.min(w, (videoW - x0) & ~1));
            h = Math.max(16, Math.min(h, (videoH - y0) & ~1));
            float l = (float) x0 / videoW, r = (float) (x0 + w) / videoW;
            float t = (float) y0 / videoH, b = (float) (y0 + h) / videoH;
            // normalized device coordinates: x -1..1 (left to right), y -1..1 (bottom to top)
            crop = new Crop(2 * l - 1, 2 * r - 1, 1 - 2 * b, 1 - 2 * t);
        }

        if (!trimmed && crop == null && mode == MODE_VIDEO) {
            Toast.makeText(this, R.string.ed_nothing, Toast.LENGTH_LONG).show();
            return;
        }

        if (player != null) player.pause();

        try {
            tmpFile = File.createTempFile("edit", audioOnly ? ".m4a" : ".mp4", getCacheDir());
        } catch (Exception ex) {
            Toast.makeText(this, R.string.ed_error, Toast.LENGTH_LONG).show();
            return;
        }

        MediaItem.Builder mi = new MediaItem.Builder().setUri(uri);
        if (trimmed) {
            mi.setClippingConfiguration(new MediaItem.ClippingConfiguration.Builder()
                    .setStartPositionMs(s)
                    .setEndPositionMs(e)
                    .build());
        }
        EditedMediaItem.Builder eb = new EditedMediaItem.Builder(mi.build());
        if (audioOnly) eb.setRemoveVideo(true);
        if (mode == MODE_MUTE) eb.setRemoveAudio(true);
        if (crop != null) {
            eb.setEffects(new Effects(Collections.<AudioProcessor>emptyList(),
                    Collections.<Effect>singletonList(crop)));
        }

        Transformer.Builder tb = new Transformer.Builder(this)
                .experimentalSetTrimOptimizationEnabled(true)
                .addListener(new Transformer.Listener() {
                    @Override
                    public void onCompleted(Composition composition, ExportResult result) {
                        onExportDone(true);
                    }

                    @Override
                    public void onError(Composition composition, ExportResult result,
                                        ExportException ex) {
                        onExportDone(false);
                    }
                });
        if (crop != null) {
            // Re-encode the cropped video with a bitrate close to the source, in H.264.
            tb.setVideoMimeType(MimeTypes.VIDEO_H264);
            tb.setEncoderFactory(new DefaultEncoderFactory.Builder(this)
                    .setRequestedVideoEncoderSettings(new VideoEncoderSettings.Builder()
                            .setBitrate(targetBitrate())
                            .build())
                    .setEnableFallback(true)
                    .build());
        }
        transformer = tb.build();

        exporting = true;
        progressBar.setProgress(0);
        progressText.setText(R.string.ed_exporting);
        progressOverlay.setVisibility(View.VISIBLE);
        try {
            transformer.start(eb.build(), tmpFile.getAbsolutePath());
            handler.postDelayed(progressPoll, 250);
        } catch (Throwable t) {
            onExportDone(false);
        }
    }

    private int targetBitrate() {
        int br = 0;
        MediaMetadataRetriever r = new MediaMetadataRetriever();
        try {
            r.setDataSource(this, uri);
            String b = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE);
            if (b != null) br = Integer.parseInt(b);
        } catch (Throwable ignored) {
        } finally {
            try {
                r.release();
            } catch (Throwable ignored) {
            }
        }
        if (br <= 0) br = 8_000_000;
        return Math.max(2_000_000, Math.min(br, 20_000_000));
    }

    private void cancelExport() {
        if (transformer != null && exporting) {
            exporting = false;
            try {
                transformer.cancel();
            } catch (Throwable ignored) {
            }
        }
        finishExportUi();
        deleteTmp();
    }

    private void onExportDone(boolean ok) {
        if (!exporting) return; // cancelled
        exporting = false;
        finishExportUi();
        if (!ok || tmpFile == null || !tmpFile.exists() || tmpFile.length() == 0) {
            deleteTmp();
            Toast.makeText(this, R.string.ed_error, Toast.LENGTH_LONG).show();
            return;
        }
        progressOverlay.setVisibility(View.VISIBLE);
        progressText.setText(R.string.rec_saving);
        final boolean audioOnly = mode == MODE_AUDIO;
        final File src = tmpFile;
        final String name = "Trim_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
                .format(new Date()) + (audioOnly ? ".m4a" : ".mp4");
        new Thread(() -> {
            MediaOutput out = null;
            boolean saved = false;
            try {
                out = MediaOutput.create(this, name, audioOnly ? "audio/mp4" : "video/mp4", audioOnly);
                out.copyFrom(src);
                saved = true;
            } catch (Throwable t) {
                saved = false;
            } finally {
                if (out != null) out.finish(saved);
                //noinspection ResultOfMethodCallIgnored
                src.delete();
            }
            final MediaOutput result = saved ? out : null;
            handler.post(() -> {
                progressOverlay.setVisibility(View.GONE);
                if (result == null) {
                    Toast.makeText(this, R.string.ed_error, Toast.LENGTH_LONG).show();
                } else {
                    showDone(result);
                }
            });
        }).start();
    }

    private void finishExportUi() {
        handler.removeCallbacks(progressPoll);
        progressOverlay.setVisibility(View.GONE);
    }

    private void deleteTmp() {
        if (tmpFile != null) {
            //noinspection ResultOfMethodCallIgnored
            tmpFile.delete();
            tmpFile = null;
        }
    }

    private void showDone(final MediaOutput out) {
        new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle(R.string.ed_done)
                .setMessage(R.string.ed_done_msg)
                .setPositiveButton(R.string.ed_play, (d, w) -> {
                    ArrayList<Uri> list = new ArrayList<>();
                    list.add(out.uri);
                    Intent play = new Intent(this,
                            out.audio ? MusicPlayerActivity.class : VideoPlayerActivity.class);
                    play.putParcelableArrayListExtra(MediaUtils.EXTRA_URIS, list);
                    startActivity(play);
                })
                .setNegativeButton(R.string.ed_close, null)
                .show();
    }

    // ================================================================== lifecycle

    private void releasePlayer() {
        if (player != null) {
            player.release();
            player = null;
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        handler.post(ticker);
    }

    @Override
    protected void onStop() {
        handler.removeCallbacks(ticker);
        if (player != null) player.pause();
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        if (exporting && transformer != null) {
            exporting = false;
            try {
                transformer.cancel();
            } catch (Throwable ignored) {
            }
        }
        deleteTmp();
        releasePlayer();
        trimBar.release();
        super.onDestroy();
    }
}
