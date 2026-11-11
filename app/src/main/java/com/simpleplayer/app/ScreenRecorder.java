package com.simpleplayer.app;

import android.content.Context;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioPlaybackCaptureConfiguration;
import android.media.AudioRecord;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.media.MediaRecorder;
import android.media.projection.MediaProjection;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Surface;
import android.view.WindowManager;

import java.io.FileDescriptor;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Screen recorder engine: hardware H.264 video from a VirtualDisplay, plus optional device
 * audio (AudioPlaybackCapture, Android 10+) and/or microphone, mixed into one AAC track,
 * muxed to MP4. Everything runs on background threads so the phone never lags.
 */
final class ScreenRecorder {

    interface Listener {
        /** Called on a background thread once the file is finalised (ok) or discarded. */
        void onFinished(MediaOutput output, boolean ok);
    }

    private static final String TAG = "ScreenRecorder";
    private static final String VIDEO_MIME = MediaFormat.MIMETYPE_VIDEO_AVC;
    private static final String AUDIO_MIME = MediaFormat.MIMETYPE_AUDIO_AAC;
    private static final int FPS = 30;
    private static final int SAMPLE_RATE = 44100;
    private static final int CHUNK_FRAMES = 1024; // ~23 ms of audio per loop

    private final Context context;
    private final MediaProjection projection;
    private final boolean wantInternal;
    private final boolean wantMic;
    private final MediaOutput output;
    private final Listener listener;

    private MediaCodec videoEnc;
    private MediaCodec audioEnc;
    private VirtualDisplay display;
    private Surface inputSurface;
    private MediaMuxer muxer;
    private AudioRecord internalRec;
    private AudioRecord micRec;
    private Thread videoThread;
    private Thread audioThread;
    private MediaProjection.Callback projectionCallback;

    private volatile boolean stopRequested;
    private volatile boolean videoStarted; // first video frame is flowing: audio may start
    private final AtomicBoolean finishing = new AtomicBoolean();

    private final Object muxLock = new Object();
    private int expectedTracks;
    private int tracksAdded;
    private boolean muxStarted;
    private boolean muxClosed;
    private boolean audioTrackDropped;

    ScreenRecorder(Context context, MediaProjection projection, boolean internalAudio,
                   boolean mic, MediaOutput output, Listener listener) {
        this.context = context.getApplicationContext();
        this.projection = projection;
        this.wantInternal = internalAudio;
        this.wantMic = mic;
        this.output = output;
        this.listener = listener;
    }

    // ================================================================== start

    void start() throws IOException {
        try {
            setUpVideo();
            boolean hasAudio = setUpAudio();
            expectedTracks = hasAudio ? 2 : 1;

            FileDescriptor fd = output.openFd();
            muxer = fd != null
                    ? new MediaMuxer(fd, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
                    : new MediaMuxer(output.file.getAbsolutePath(),
                            MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);

            videoThread = new Thread(this::videoLoop, "rec-video");
            videoThread.start();
            if (hasAudio) {
                audioThread = new Thread(this::audioLoop, "rec-audio");
                audioThread.start();
            }
        } catch (Throwable t) {
            Log.e(TAG, "start failed", t);
            releaseAll();
            output.finish(false);
            if (t instanceof IOException) throw (IOException) t;
            throw new IOException(t);
        }
    }

    @SuppressWarnings("deprecation")
    private void setUpVideo() throws IOException {
        DisplayMetrics dm = new DisplayMetrics();
        WindowManager wm = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        wm.getDefaultDisplay().getRealMetrics(dm);

        int w = dm.widthPixels;
        int h = dm.heightPixels;
        int longSide = Math.max(w, h);
        if (longSide > 2560) { // keep it within what hardware encoders handle comfortably
            float k = 2560f / longSide;
            w = Math.round(w * k);
            h = Math.round(h * k);
        }

        videoEnc = MediaCodec.createEncoderByType(VIDEO_MIME);
        MediaCodecInfo.VideoCapabilities caps = videoEnc.getCodecInfo()
                .getCapabilitiesForType(VIDEO_MIME).getVideoCapabilities();
        int wa = Math.max(2, caps.getWidthAlignment());
        int ha = Math.max(2, caps.getHeightAlignment());
        // Scale down only if the encoder cannot do the full size.
        for (int i = 0; i < 6; i++) {
            int aw = Math.max(wa, w - w % wa);
            int ah = Math.max(ha, h - h % ha);
            if (caps.isSizeSupported(aw, ah)) {
                w = aw;
                h = ah;
                break;
            }
            w = Math.round(w * 0.8f);
            h = Math.round(h * 0.8f);
        }
        w = Math.max(wa, w - w % wa);
        h = Math.max(ha, h - h % ha);

        long bitrate = (long) (w * (long) h * FPS * 0.13);
        bitrate = Math.max(2_000_000L, Math.min(bitrate, 24_000_000L));
        bitrate = caps.getBitrateRange().clamp((int) bitrate);

        MediaFormat f = MediaFormat.createVideoFormat(VIDEO_MIME, w, h);
        f.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
        f.setInteger(MediaFormat.KEY_BIT_RATE, (int) bitrate);
        f.setInteger(MediaFormat.KEY_FRAME_RATE, FPS);
        f.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);
        // Static screens: keep emitting frames so the video never stalls.
        f.setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 100_000L);
        videoEnc.configure(f, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        inputSurface = videoEnc.createInputSurface();
        videoEnc.start();

        // Android 14+ requires a callback to be registered before creating the display.
        projectionCallback = new MediaProjection.Callback() {
            @Override
            public void onStop() {
                stop();
            }
        };
        projection.registerCallback(projectionCallback, new Handler(Looper.getMainLooper()));
        display = projection.createVirtualDisplay("SimpleRecorder", w, h, dm.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, inputSurface, null, null);
    }

    /** Returns true when at least one audio source could be opened. */
    private boolean setUpAudio() {
        if (wantInternal && Build.VERSION.SDK_INT >= 29) {
            try {
                internalRec = buildInternalRecord();
            } catch (Throwable t) {
                Log.w(TAG, "internal audio unavailable", t);
                internalRec = null;
            }
        }
        if (wantMic) {
            try {
                int min = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
                        AudioFormat.ENCODING_PCM_16BIT);
                int size = Math.max(min, CHUNK_FRAMES * 2) * 4;
                AudioRecord r = new AudioRecord(MediaRecorder.AudioSource.MIC, SAMPLE_RATE,
                        AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, size);
                if (r.getState() == AudioRecord.STATE_INITIALIZED) {
                    micRec = r;
                } else {
                    r.release();
                }
            } catch (Throwable t) {
                Log.w(TAG, "microphone unavailable", t);
                micRec = null;
            }
        }
        if (internalRec == null && micRec == null) return false;

        try {
            MediaFormat af = MediaFormat.createAudioFormat(AUDIO_MIME, SAMPLE_RATE, 2);
            af.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC);
            af.setInteger(MediaFormat.KEY_BIT_RATE, 160_000);
            af.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, CHUNK_FRAMES * 4 * 4);
            audioEnc = MediaCodec.createEncoderByType(AUDIO_MIME);
            audioEnc.configure(af, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            audioEnc.start();
        } catch (Throwable t) {
            Log.w(TAG, "audio encoder unavailable", t);
            if (audioEnc != null) {
                try {
                    audioEnc.release();
                } catch (Throwable ignored) {
                }
                audioEnc = null;
            }
            if (internalRec != null) internalRec.release();
            if (micRec != null) micRec.release();
            internalRec = null;
            micRec = null;
            return false;
        }
        return true;
    }

    private AudioRecord buildInternalRecord() {
        AudioPlaybackCaptureConfiguration cfg = new AudioPlaybackCaptureConfiguration.Builder(projection)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                .build();
        AudioFormat fmt = new AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(SAMPLE_RATE)
                .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
                .build();
        int min = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_STEREO,
                AudioFormat.ENCODING_PCM_16BIT);
        AudioRecord r = new AudioRecord.Builder()
                .setAudioPlaybackCaptureConfig(cfg)
                .setAudioFormat(fmt)
                .setBufferSizeInBytes(Math.max(min, CHUNK_FRAMES * 4) * 4)
                .build();
        if (r.getState() != AudioRecord.STATE_INITIALIZED) {
            r.release();
            return null;
        }
        return r;
    }

    // ================================================================== video thread

    private void videoLoop() {
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        int track = -1;
        long firstPts = -1;
        long lastPts = -1;
        try {
            while (true) {
                int idx = videoEnc.dequeueOutputBuffer(info, 10_000);
                if (idx == MediaCodec.INFO_TRY_AGAIN_LATER) continue;
                if (idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    track = addTrack(videoEnc.getOutputFormat());
                    videoStarted = true; // audio starts counting from here
                    continue;
                }
                if (idx < 0) continue;

                ByteBuffer buf = videoEnc.getOutputBuffer(idx);
                boolean eos = (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) info.size = 0;
                if (info.size > 0 && buf != null && track >= 0) {
                    if (firstPts < 0) firstPts = info.presentationTimeUs;
                    long pts = Math.max(0, info.presentationTimeUs - firstPts);
                    if (pts <= lastPts) pts = lastPts + 1;
                    lastPts = pts;
                    info.presentationTimeUs = pts;
                    buf.position(info.offset);
                    buf.limit(info.offset + info.size);
                    writeSample(track, buf, info);
                }
                videoEnc.releaseOutputBuffer(idx, false);
                if (eos) break;
            }
        } catch (Throwable t) {
            Log.e(TAG, "video loop", t);
        } finally {
            videoStarted = true;
        }
    }

    // ================================================================== audio thread

    private void audioLoop() {
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        short[] sys = new short[CHUNK_FRAMES * 2];
        short[] mic = new short[CHUNK_FRAMES];
        short[] mixed = new short[CHUNK_FRAMES * 2];
        int[] track = {-1};
        long framesFed = 0;
        try {
            if (internalRec != null) internalRec.startRecording();
            if (micRec != null) micRec.startRecording();

            long gateDeadline = System.currentTimeMillis() + 3000;
            while (!stopRequested) {
                boolean gotSys = false, gotMic = false;
                if (internalRec != null) gotSys = readFully(internalRec, sys, sys.length);
                if (micRec != null) gotMic = readFully(micRec, mic, mic.length);
                if (!videoStarted && System.currentTimeMillis() < gateDeadline) continue; // drop until video flows

                for (int i = 0; i < CHUNK_FRAMES; i++) {
                    int l = 0, r = 0;
                    if (gotSys) {
                        l += sys[2 * i];
                        r += sys[2 * i + 1];
                    }
                    if (gotMic) {
                        l += mic[i];
                        r += mic[i];
                    }
                    mixed[2 * i] = clamp(l);
                    mixed[2 * i + 1] = clamp(r);
                }

                int in = audioEnc.dequeueInputBuffer(10_000);
                if (in >= 0) {
                    ByteBuffer ib = audioEnc.getInputBuffer(in);
                    ib.clear();
                    ib.order(ByteOrder.nativeOrder()).asShortBuffer().put(mixed, 0, mixed.length);
                    long pts = framesFed * 1_000_000L / SAMPLE_RATE;
                    audioEnc.queueInputBuffer(in, 0, mixed.length * 2, pts, 0);
                    framesFed += CHUNK_FRAMES;
                }
                drainAudio(info, track, false);
            }

            // end of stream
            long pts = framesFed * 1_000_000L / SAMPLE_RATE;
            for (int i = 0; i < 50; i++) {
                int in = audioEnc.dequeueInputBuffer(20_000);
                if (in >= 0) {
                    audioEnc.queueInputBuffer(in, 0, 0, pts, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                    break;
                }
            }
            drainAudio(info, track, true);
        } catch (Throwable t) {
            Log.e(TAG, "audio loop", t);
        } finally {
            if (track[0] < 0) dropAudioTrack(); // never produced data: do not block the muxer
            try {
                if (internalRec != null) internalRec.stop();
            } catch (Throwable ignored) {
            }
            try {
                if (micRec != null) micRec.stop();
            } catch (Throwable ignored) {
            }
        }
    }

    private void drainAudio(MediaCodec.BufferInfo info, int[] track, boolean untilEos) {
        int emptyRounds = 0;
        while (true) {
            int idx = audioEnc.dequeueOutputBuffer(info, untilEos ? 20_000 : 0);
            if (idx == MediaCodec.INFO_TRY_AGAIN_LATER) {
                if (!untilEos || ++emptyRounds > 100) return;
                continue;
            }
            if (idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                if (track[0] < 0) track[0] = addTrack(audioEnc.getOutputFormat());
                continue;
            }
            if (idx < 0) continue;
            ByteBuffer buf = audioEnc.getOutputBuffer(idx);
            boolean eos = (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
            if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) info.size = 0;
            if (info.size > 0 && buf != null && track[0] >= 0) {
                buf.position(info.offset);
                buf.limit(info.offset + info.size);
                writeSample(track[0], buf, info);
            }
            audioEnc.releaseOutputBuffer(idx, false);
            if (eos) return;
            emptyRounds = 0;
        }
    }

    private static boolean readFully(AudioRecord rec, short[] dst, int count) {
        int off = 0;
        while (off < count) {
            int n = rec.read(dst, off, count - off);
            if (n < 0) return false;
            off += n;
        }
        return true;
    }

    private static short clamp(int v) {
        return (short) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, v));
    }

    // ================================================================== muxer helpers

    private int addTrack(MediaFormat format) {
        synchronized (muxLock) {
            int t = muxer.addTrack(format);
            tracksAdded++;
            maybeStartMuxer();
            return t;
        }
    }

    private void dropAudioTrack() {
        synchronized (muxLock) {
            if (audioTrackDropped) return;
            audioTrackDropped = true;
            expectedTracks = Math.max(1, expectedTracks - 1);
            maybeStartMuxer();
        }
    }

    private void maybeStartMuxer() { // muxLock held
        if (!muxStarted && tracksAdded >= expectedTracks && tracksAdded > 0) {
            muxer.start();
            muxStarted = true;
            muxLock.notifyAll();
        }
    }

    private void writeSample(int track, ByteBuffer buf, MediaCodec.BufferInfo info) {
        synchronized (muxLock) {
            long deadline = System.currentTimeMillis() + 4000;
            while (!muxStarted && !muxClosed) {
                long left = deadline - System.currentTimeMillis();
                if (left <= 0) {
                    // the other stream is not coming: go on without it
                    audioTrackDropped = true;
                    expectedTracks = Math.max(1, tracksAdded);
                    maybeStartMuxer();
                    break;
                }
                try {
                    muxLock.wait(Math.min(left, 100));
                } catch (InterruptedException e) {
                    return;
                }
            }
            if (muxStarted && !muxClosed) {
                try {
                    muxer.writeSampleData(track, buf, info);
                } catch (Throwable t) {
                    Log.w(TAG, "writeSampleData", t);
                }
            }
        }
    }

    // ================================================================== stop

    /** Stops recording and finalises the file. Safe to call from any thread, any number of times. */
    void stop() {
        if (!finishing.compareAndSet(false, true)) return;
        new Thread(this::finishInternal, "rec-finish").start();
    }

    private void finishInternal() {
        boolean ok = false;
        stopRequested = true;
        try {
            try {
                if (display != null) display.release();
            } catch (Throwable ignored) {
            }
            try {
                if (videoEnc != null) videoEnc.signalEndOfInputStream();
            } catch (Throwable ignored) {
            }
            join(videoThread);
            join(audioThread);

            synchronized (muxLock) {
                muxClosed = true;
                muxLock.notifyAll();
                if (muxer != null && muxStarted) {
                    try {
                        muxer.stop();
                        ok = true;
                    } catch (Throwable t) {
                        Log.w(TAG, "muxer.stop", t);
                    }
                }
            }
        } finally {
            releaseAll();
            output.finish(ok);
            if (listener != null) listener.onFinished(output, ok);
        }
    }

    private static void join(Thread t) {
        if (t == null) return;
        try {
            t.join(8000);
        } catch (InterruptedException ignored) {
        }
    }

    private void releaseAll() {
        try {
            if (display != null) display.release();
        } catch (Throwable ignored) {
        }
        try {
            if (projectionCallback != null) projection.unregisterCallback(projectionCallback);
        } catch (Throwable ignored) {
        }
        try {
            projection.stop();
        } catch (Throwable ignored) {
        }
        try {
            if (inputSurface != null) inputSurface.release();
        } catch (Throwable ignored) {
        }
        if (videoEnc != null) {
            try {
                videoEnc.stop();
            } catch (Throwable ignored) {
            }
            try {
                videoEnc.release();
            } catch (Throwable ignored) {
            }
        }
        if (audioEnc != null) {
            try {
                audioEnc.stop();
            } catch (Throwable ignored) {
            }
            try {
                audioEnc.release();
            } catch (Throwable ignored) {
            }
        }
        try {
            if (internalRec != null) internalRec.release();
        } catch (Throwable ignored) {
        }
        try {
            if (micRec != null) micRec.release();
        } catch (Throwable ignored) {
        }
        synchronized (muxLock) {
            try {
                if (muxer != null) muxer.release();
            } catch (Throwable ignored) {
            }
        }
    }
}
