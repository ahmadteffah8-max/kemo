package com.kemo.calc;

import android.content.Context;
import android.content.SharedPreferences;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.media.SoundPool;

import java.util.Random;

/** Low-latency SFX via SoundPool (per-group volume) + looping chiptune BGM via MediaPlayer. */
public final class SoundManager {
    public static final String KEYS = "keys", DELETE = "delete", RESULT = "result", CLEAR = "clear", MUSIC = "music";
    public static final String[] GROUPS = {KEYS, DELETE, RESULT, CLEAR, MUSIC};
    private static final int[] DEFAULTS = {80, 80, 85, 80, 35};

    private static SoundManager instance;
    public static synchronized SoundManager get(Context c) {
        if (instance == null) instance = new SoundManager(c.getApplicationContext());
        return instance;
    }

    private final Context ctx;
    private final SharedPreferences prefs;
    private final SoundPool pool;
    private final Random rnd = new Random();
    private final int[] digit = new int[10];
    private int keyOp, keyDot, keyParen, del, chime, clear, startup;
    private final java.util.HashSet<Integer> loaded = new java.util.HashSet<>();
    private boolean startupPending = false;
    private MediaPlayer bgm;
    private boolean musicWanted = false;

    private SoundManager(Context c) {
        ctx = c;
        prefs = c.getSharedPreferences("kemo_audio", Context.MODE_PRIVATE);
        AudioAttributes aa = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_GAME)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build();
        pool = new SoundPool.Builder().setMaxStreams(6).setAudioAttributes(aa).build();
        pool.setOnLoadCompleteListener((sp, id, status) -> {
            if (status == 0) {
                synchronized (loaded) { loaded.add(id); }
                if (id == startup && startupPending) { startupPending = false; play(startup, getVolume(RESULT), 1f); }
            }
        });
        int[] ids = {R.raw.key_0, R.raw.key_1, R.raw.key_2, R.raw.key_3, R.raw.key_4,
                R.raw.key_5, R.raw.key_6, R.raw.key_7, R.raw.key_8, R.raw.key_9};
        for (int i = 0; i < 10; i++) digit[i] = pool.load(c, ids[i], 1);
        keyOp = pool.load(c, R.raw.key_op, 1);
        keyDot = pool.load(c, R.raw.key_dot, 1);
        keyParen = pool.load(c, R.raw.key_paren, 1);
        del = pool.load(c, R.raw.del_pixel, 1);
        chime = pool.load(c, R.raw.result_chime, 1);
        clear = pool.load(c, R.raw.clear_vanish, 1);
        startup = pool.load(c, R.raw.startup, 1);
    }

    private static int defaultFor(String g) {
        for (int i = 0; i < GROUPS.length; i++) if (GROUPS[i].equals(g)) return DEFAULTS[i];
        return 80;
    }

    public int getVolume(String group) { return prefs.getInt("vol_" + group, defaultFor(group)); }

    public void setVolume(String group, int percent) {
        prefs.edit().putInt("vol_" + group, Math.max(0, Math.min(100, percent))).apply();
        if (MUSIC.equals(group)) applyMusic();
    }

    private static float curve(int percent) { float v = percent / 100f; return v * v; }

    private void play(int id, int percent, float rate) {
        synchronized (loaded) { if (!loaded.contains(id)) return; }
        float v = curve(percent);
        if (v <= 0f) return;
        pool.play(id, v, v, 1, 0, rate);
    }

    private float jitter() { return 0.985f + rnd.nextFloat() * 0.03f; }   // tiny pitch variation

    public void playKey(String key) {
        int v = getVolume(KEYS);
        char c = key.charAt(0);
        if (c >= '0' && c <= '9') play(digit[c - '0'], v, jitter());
        else if (c == '.') play(keyDot, v, jitter());
        else if (c == '(' || c == ')') play(keyParen, v, c == ')' ? 0.9f : 1f);
        else play(keyOp, v, jitter());
    }
    public void playDelete() { play(del, getVolume(DELETE), jitter()); }
    public void playResult() { play(chime, getVolume(RESULT), 1f); }
    public void playClear() { play(clear, getVolume(CLEAR), 1f); }

    /** Plays the startup jingle as soon as it is loaded (SoundPool loads asynchronously). */
    public void playStartup() {
        boolean ready;
        synchronized (loaded) { ready = loaded.contains(startup); }
        if (ready) play(startup, getVolume(RESULT), 1f); else startupPending = true;
    }

    /** Sample for the settings sliders. */
    public void preview(String group) {
        switch (group) {
            case KEYS: playKey(String.valueOf(rnd.nextInt(10))); break;
            case DELETE: playDelete(); break;
            case RESULT: playResult(); break;
            case CLEAR: playClear(); break;
            default: break;
        }
    }

    // ---- music ----
    public void startMusic() {
        musicWanted = true;
        if (bgm == null) {
            bgm = MediaPlayer.create(ctx, R.raw.kemo_bgm);
            if (bgm == null) return;
            bgm.setLooping(true);
        }
        applyMusic();
    }

    public void pauseMusic() {
        musicWanted = false;
        if (bgm != null && bgm.isPlaying()) bgm.pause();
    }

    private void applyMusic() {
        if (bgm == null) return;
        float v = curve(getVolume(MUSIC));
        bgm.setVolume(v, v);
        if (musicWanted && v > 0f) { if (!bgm.isPlaying()) bgm.start(); }
        else if (bgm.isPlaying()) bgm.pause();
    }
}
