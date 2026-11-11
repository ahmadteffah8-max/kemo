package com.simpleplayer.app;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.graphics.drawable.Icon;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Locale;

/** Foreground service that owns the screen recording so it survives leaving the app. */
public class ScreenRecorderService extends Service {

    static final String ACTION_START = "com.simpleplayer.app.REC_START";
    static final String ACTION_STOP = "com.simpleplayer.app.REC_STOP";
    static final String EXTRA_CODE = "code";
    static final String EXTRA_DATA = "data";
    static final String EXTRA_INTERNAL = "internal";
    static final String EXTRA_MIC = "mic";
    static final String EXTRA_LIMIT_MS = "limit_ms";

    private static final String CHANNEL_REC = "recording";
    private static final String CHANNEL_DONE = "recording_done";
    private static final int NOTIF_REC = 41;
    private static final int NOTIF_DONE = 42;

    /** Read by RecorderActivity to show the right button / timer. */
    static volatile boolean running;
    static volatile long startedAt; // SystemClock.elapsedRealtime()
    static volatile long limitMs;   // 0 = no limit

    private final Handler handler = new Handler(Looper.getMainLooper());
    private ScreenRecorder recorder;
    private boolean stopping;

    private final Runnable autoStop = this::stopRecording;

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : null;
        if (ACTION_STOP.equals(action)) {
            if (running) {
                stopRecording();
            } else {
                stopSelf();
            }
            return START_NOT_STICKY;
        }
        if (!ACTION_START.equals(action) || running) {
            if (!running) stopSelf();
            return START_NOT_STICKY;
        }
        beginRecording(intent);
        return START_NOT_STICKY;
    }

    // ------------------------------------------------------------------ start

    private void beginRecording(Intent in) {
        boolean wantInternal = in.getBooleanExtra(EXTRA_INTERNAL, false);
        boolean wantMic = in.getBooleanExtra(EXTRA_MIC, false);
        long limit = in.getLongExtra(EXTRA_LIMIT_MS, 0);

        boolean audioGranted = checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED;
        if (!audioGranted) {
            wantInternal = false;
            wantMic = false;
        }

        // The foreground notification must be up before touching MediaProjection.
        createChannels();
        Notification n = buildOngoing();
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                int type = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION;
                if (Build.VERSION.SDK_INT >= 30 && (wantMic || wantInternal)) {
                    type |= ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE;
                }
                startForeground(NOTIF_REC, n, type);
            } else {
                startForeground(NOTIF_REC, n);
            }
        } catch (Throwable t) {
            fail();
            return;
        }

        int code = in.getIntExtra(EXTRA_CODE, 0);
        Intent data = in.getParcelableExtra(EXTRA_DATA);
        if (data == null) {
            fail();
            return;
        }
        MediaProjectionManager mpm =
                (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        MediaProjection projection;
        try {
            projection = mpm.getMediaProjection(code, data);
        } catch (Throwable t) {
            projection = null;
        }
        if (projection == null) {
            fail();
            return;
        }

        final MediaProjection proj = projection;
        final boolean fInternal = wantInternal;
        final boolean fMic = wantMic;
        running = true;
        stopping = false;
        limitMs = limit;
        startedAt = SystemClock.elapsedRealtime();

        // Short delay so the permission dialog / app transition is not in the video.
        handler.postDelayed(() -> {
            if (!running || stopping) {
                try {
                    proj.stop();
                } catch (Throwable ignored) {
                }
                return;
            }
            try {
                String name = "Screen_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
                        .format(new Date()) + ".mp4";
                MediaOutput out = MediaOutput.create(this, name, "video/mp4", false);
                recorder = new ScreenRecorder(this, proj, fInternal, fMic, out, this::onFinished);
                recorder.start();
                startedAt = SystemClock.elapsedRealtime();
                updateOngoing();
                if (limitMs > 0) handler.postDelayed(autoStop, limitMs);
            } catch (Throwable t) {
                recorder = null;
                try {
                    proj.stop();
                } catch (Throwable ignored) {
                }
                fail();
            }
        }, 700);
    }

    private void fail() {
        running = false;
        Toast.makeText(this, R.string.rec_failed, Toast.LENGTH_LONG).show();
        stopForeground(true);
        stopSelf();
    }

    // ------------------------------------------------------------------ stop / finished

    private void stopRecording() {
        if (stopping) return;
        stopping = true;
        handler.removeCallbacks(autoStop);
        if (recorder != null) {
            Toast.makeText(this, R.string.rec_saving, Toast.LENGTH_SHORT).show();
            recorder.stop(); // finishes on a worker thread, then onFinished
        } else {
            running = false;
            stopForeground(true);
            stopSelf();
        }
    }

    /** Runs on the recorder's worker thread. */
    private void onFinished(MediaOutput out, boolean ok) {
        handler.post(() -> {
            running = false;
            recorder = null;
            stopForeground(true);
            if (ok) {
                Toast.makeText(this, R.string.rec_saved, Toast.LENGTH_LONG).show();
                postDone(out);
            } else {
                Toast.makeText(this, R.string.rec_failed, Toast.LENGTH_LONG).show();
            }
            stopSelf();
        });
    }

    // ------------------------------------------------------------------ notifications

    private void createChannels() {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationManager nm = getSystemService(NotificationManager.class);
        NotificationChannel rec = new NotificationChannel(CHANNEL_REC,
                getString(R.string.rec_notif_channel), NotificationManager.IMPORTANCE_LOW);
        rec.setShowBadge(false);
        nm.createNotificationChannel(rec);
        NotificationChannel done = new NotificationChannel(CHANNEL_DONE,
                getString(R.string.rec_saved), NotificationManager.IMPORTANCE_DEFAULT);
        nm.createNotificationChannel(done);
    }

    private Notification.Builder builder(String channel) {
        return Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, channel)
                : new Notification.Builder(this);
    }

    private Notification buildOngoing() {
        Intent stop = new Intent(this, ScreenRecorderService.class).setAction(ACTION_STOP);
        PendingIntent stopPi = PendingIntent.getService(this, 1, stop,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent open = PendingIntent.getActivity(this, 2,
                new Intent(this, RecorderActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification.Builder b = builder(CHANNEL_REC)
                .setSmallIcon(R.drawable.ic_record)
                .setContentTitle(getString(R.string.rec_notif_title))
                .setContentText(getString(R.string.rec_notif_text))
                .setContentIntent(open)
                .setOngoing(true)
                .setUsesChronometer(true)
                .setWhen(System.currentTimeMillis())
                .addAction(new Notification.Action.Builder(
                        Icon.createWithResource(this, R.drawable.ic_stop),
                        getString(R.string.rec_stop), stopPi).build());
        return b.build();
    }

    private void updateOngoing() {
        try {
            getSystemService(NotificationManager.class).notify(NOTIF_REC, buildOngoing());
        } catch (Throwable ignored) {
        }
    }

    private void postDone(MediaOutput out) {
        try {
            ArrayList<Uri> list = new ArrayList<>();
            list.add(out.uri);
            Intent play = new Intent(this, VideoPlayerActivity.class)
                    .putParcelableArrayListExtra(MediaUtils.EXTRA_URIS, list)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            PendingIntent pi = PendingIntent.getActivity(this, 3, play,
                    PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            Notification n = builder(CHANNEL_DONE)
                    .setSmallIcon(R.drawable.ic_record)
                    .setContentTitle(getString(R.string.rec_saved_tap))
                    .setContentIntent(pi)
                    .setAutoCancel(true)
                    .build();
            getSystemService(NotificationManager.class).notify(NOTIF_DONE, n);
        } catch (Throwable ignored) {
        }
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        if (recorder != null) recorder.stop();
        running = false;
        super.onDestroy();
    }
}
