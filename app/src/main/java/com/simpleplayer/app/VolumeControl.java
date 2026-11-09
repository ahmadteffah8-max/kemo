package com.simpleplayer.app;

import android.content.Context;
import android.database.ContentObserver;
import android.media.AudioManager;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.widget.SeekBar;

/** Binds a SeekBar to the system media volume (two-way). */
final class VolumeControl {

    private final Context context;
    private final AudioManager audio;
    private final SeekBar bar;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ContentObserver observer = new ContentObserver(handler) {
        @Override
        public void onChange(boolean selfChange) {
            sync();
        }
    };

    VolumeControl(Context context, SeekBar bar) {
        this.context = context;
        this.bar = bar;
        this.audio = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        bar.setMax(audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC));
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar s, int progress, boolean fromUser) {
                if (fromUser) audio.setStreamVolume(AudioManager.STREAM_MUSIC, progress, 0);
            }

            @Override
            public void onStartTrackingTouch(SeekBar s) {}

            @Override
            public void onStopTrackingTouch(SeekBar s) {}
        });
        sync();
    }

    void start() {
        context.getContentResolver().registerContentObserver(
                Settings.System.CONTENT_URI, true, observer);
        sync();
    }

    void stop() {
        context.getContentResolver().unregisterContentObserver(observer);
        handler.removeCallbacksAndMessages(null);
    }

    void syncSoon() {
        handler.postDelayed(this::sync, 120);
    }

    void sync() {
        bar.setProgress(audio.getStreamVolume(AudioManager.STREAM_MUSIC));
    }
}
