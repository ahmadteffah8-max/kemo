package com.simpleplayer.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.content.res.Configuration;
import android.net.Uri;
import android.os.Binder;
import android.os.Build;
import android.os.IBinder;
import android.widget.RemoteViews;

import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;

import java.util.ArrayList;

/**
 * Owns the music player so audio keeps playing when the app is in the background. Shows a
 * notification with the floating cat, the file name and a single play / pause button.
 */
public class MusicService extends Service {

    static final String ACTION_PLAY = "com.simpleplayer.app.MUSIC_PLAY";
    static final String ACTION_TOGGLE = "com.simpleplayer.app.MUSIC_TOGGLE";
    static final String ACTION_CLOSE = "com.simpleplayer.app.MUSIC_CLOSE";

    private static final String CHANNEL = "music_playback";
    private static final int NOTIF_ID = 51;

    class LocalBinder extends Binder {
        MusicService service() {
            return MusicService.this;
        }
    }

    private final IBinder binder = new LocalBinder();
    private final ArrayList<Uri> uris = new ArrayList<>();
    private ExoPlayer player;
    private boolean foreground;

    ExoPlayer getPlayer() {
        return player;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        createChannel();
        player = MediaUtils.newPlayer(this, false);
        player.addListener(new Player.Listener() {
            @Override
            public void onIsPlayingChanged(boolean isPlaying) {
                refresh();
            }

            @Override
            public void onPlayWhenReadyChanged(boolean playWhenReady, int reason) {
                refresh();
            }

            @Override
            public void onPlaybackStateChanged(int state) {
                refresh();
            }

            @Override
            public void onMediaItemTransition(MediaItem item, int reason) {
                refresh();
            }

            @Override
            public void onPlayerError(PlaybackException error) {
                // Skip a broken file and carry on with the next one.
                if (player.hasNextMediaItem()) {
                    player.seekToNextMediaItem();
                    player.prepare();
                    player.play();
                }
            }
        });
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : null;

        if (ACTION_PLAY.equals(action)) {
            ArrayList<Uri> list = MediaUtils.urisFrom(intent);
            if (list.isEmpty()) {
                // startForegroundService() promises a notification, so show one before leaving.
                startForegroundSafe(buildNotification());
                stopForegroundCompat(true);
                stopSelf();
                return START_NOT_STICKY;
            }
            if (!list.equals(uris)) {
                uris.clear();
                uris.addAll(list);
                ArrayList<MediaItem> items = new ArrayList<>();
                for (Uri u : list) items.add(MediaItem.fromUri(u));
                player.setMediaItems(items);
                player.prepare();
                player.play();
            } else if (player.getPlaybackState() == Player.STATE_ENDED) {
                player.seekTo(0, 0);
                player.play();
            }
            startForegroundSafe(buildNotification());
            refresh();
        } else if (ACTION_TOGGLE.equals(action)) {
            if (uris.isEmpty()) {
                stopSelf();
            } else {
                togglePlay();
            }
        } else if (ACTION_CLOSE.equals(action)) {
            stopForegroundCompat(true);
            stopSelf();
        } else if (uris.isEmpty()) {
            stopSelf();
        }
        return START_NOT_STICKY;
    }

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

    private boolean isPlaying() {
        int state = player.getPlaybackState();
        return player.getPlayWhenReady()
                && state != Player.STATE_ENDED && state != Player.STATE_IDLE;
    }

    // ------------------------------------------------------------------ notification

    /** Playing: ongoing notification in the foreground. Paused: a normal one that can be swiped away. */
    private void refresh() {
        if (uris.isEmpty()) return;
        Notification n = buildNotification();
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (isPlaying()) {
            if (!foreground) startForegroundSafe(n);
            else nm.notify(NOTIF_ID, n);
        } else {
            if (foreground) stopForegroundCompat(false);
            nm.notify(NOTIF_ID, n);
        }
    }

    private void startForegroundSafe(Notification n) {
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
            } else {
                startForeground(NOTIF_ID, n);
            }
            foreground = true;
        } catch (Throwable t) {
            // Not allowed to run in the foreground right now: still show the notification.
            foreground = false;
            try {
                getSystemService(NotificationManager.class).notify(NOTIF_ID, n);
            } catch (Throwable ignored) {
            }
        }
    }

    /** @param remove true removes the notification, false keeps it (detached) for pause. */
    private void stopForegroundCompat(boolean remove) {
        try {
            stopForeground(remove ? STOP_FOREGROUND_REMOVE : STOP_FOREGROUND_DETACH);
        } catch (Throwable ignored) {
        }
        foreground = false;
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationChannel ch = new NotificationChannel(CHANNEL,
                getString(R.string.music_notif_channel), NotificationManager.IMPORTANCE_LOW);
        ch.setShowBadge(false);
        ch.setSound(null, null);
        getSystemService(NotificationManager.class).createNotificationChannel(ch);
    }

    private String currentName() {
        int i = player != null ? player.getCurrentMediaItemIndex() : 0;
        if (i < 0 || i >= uris.size()) i = 0;
        if (uris.isEmpty()) return getString(R.string.app_name);
        String name = MediaUtils.displayName(this, uris.get(i));
        return name.isEmpty() ? getString(R.string.now_playing) : name;
    }

    private PendingIntent servicePi(int code, String action) {
        return PendingIntent.getService(this, code,
                new Intent(this, MusicService.class).setAction(action),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private Notification buildNotification() {
        boolean playing = player != null && !uris.isEmpty() && isPlaying();
        boolean night = (getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;

        RemoteViews rv = new RemoteViews(getPackageName(), R.layout.notif_music);
        rv.setTextViewText(R.id.notifTitle, currentName());
        rv.setTextColor(R.id.notifTitle, night ? 0xFFFFFFFF : 0xFF202020);
        rv.setImageViewResource(R.id.notifToggle, playing ? R.drawable.ic_pause : R.drawable.ic_play);
        rv.setOnClickPendingIntent(R.id.notifToggle, servicePi(1, ACTION_TOGGLE));

        // Tapping the notification opens the player on the same list (playback is not restarted).
        Intent open = new Intent(this, MusicPlayerActivity.class)
                .putParcelableArrayListExtra(MediaUtils.EXTRA_URIS, new ArrayList<>(uris))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent openPi = PendingIntent.getActivity(this, 2, open,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL)
                : new Notification.Builder(this);
        b.setSmallIcon(R.drawable.ic_music_note)
                .setStyle(new Notification.DecoratedCustomViewStyle())
                .setCustomContentView(rv)
                .setContentIntent(openPi)
                .setDeleteIntent(servicePi(3, ACTION_CLOSE))
                .setOngoing(playing)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setCategory(Notification.CATEGORY_TRANSPORT)
                .setVisibility(Notification.VISIBILITY_PUBLIC);
        return b.build();
    }

    // ------------------------------------------------------------------ lifecycle

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        // App swiped away from recents: keep playing, but a paused player is not worth keeping.
        if (player == null || !isPlaying()) {
            stopForegroundCompat(true);
            stopSelf();
        }
        super.onTaskRemoved(rootIntent);
    }

    @Override
    public void onDestroy() {
        try {
            getSystemService(NotificationManager.class).cancel(NOTIF_ID);
        } catch (Throwable ignored) {
        }
        if (player != null) {
            player.release();
            player = null;
        }
        super.onDestroy();
    }
}
