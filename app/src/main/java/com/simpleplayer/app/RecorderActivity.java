package com.simpleplayer.app;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Locale;

/** Simple screen-recorder UI: start / stop, optional time limit, device sound and microphone. */
public class RecorderActivity extends Activity {

    private static final int REQ_PERMS = 10;
    private static final int REQ_CAPTURE = 11;
    private static final String PREFS = "simple_prefs";

    private final Handler handler = new Handler(Looper.getMainLooper());

    private Button startStop;
    private TextView timer, status;
    private Switch limitSwitch, systemSwitch, micSwitch;
    private EditText limitMin, limitSec;
    private View limitRow;

    private boolean wasRunning;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            refresh();
            handler.postDelayed(this, 300);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_recorder);

        startStop = findViewById(R.id.startStop);
        timer = findViewById(R.id.timer);
        status = findViewById(R.id.status);
        limitSwitch = findViewById(R.id.limitSwitch);
        systemSwitch = findViewById(R.id.systemSwitch);
        micSwitch = findViewById(R.id.micSwitch);
        limitMin = findViewById(R.id.limitMin);
        limitSec = findViewById(R.id.limitSec);
        limitRow = findViewById(R.id.limitRow);

        findViewById(R.id.backBtn).setOnClickListener(v -> finish());

        SharedPreferences p = getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        limitSwitch.setChecked(p.getBoolean("rec_limit_on", false));
        limitMin.setText(String.valueOf(p.getInt("rec_limit_min", 1)));
        limitSec.setText(String.valueOf(p.getInt("rec_limit_sec", 0)));
        systemSwitch.setChecked(p.getBoolean("rec_system", true) && Build.VERSION.SDK_INT >= 29);
        micSwitch.setChecked(p.getBoolean("rec_mic", false));
        if (Build.VERSION.SDK_INT < 29) {
            systemSwitch.setEnabled(false);
            systemSwitch.setChecked(false);
        }
        limitRow.setVisibility(limitSwitch.isChecked() ? View.VISIBLE : View.GONE);
        limitSwitch.setOnCheckedChangeListener((b, on) ->
                limitRow.setVisibility(on ? View.VISIBLE : View.GONE));

        startStop.setOnClickListener(v -> {
            if (ScreenRecorderService.running) {
                startService(new Intent(this, ScreenRecorderService.class)
                        .setAction(ScreenRecorderService.ACTION_STOP));
            } else {
                onStartClicked();
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        handler.post(tick);
    }

    @Override
    protected void onPause() {
        handler.removeCallbacks(tick);
        savePrefs();
        super.onPause();
    }

    private void savePrefs() {
        getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putBoolean("rec_limit_on", limitSwitch.isChecked())
                .putInt("rec_limit_min", parse(limitMin))
                .putInt("rec_limit_sec", parse(limitSec))
                .putBoolean("rec_system", systemSwitch.isChecked())
                .putBoolean("rec_mic", micSwitch.isChecked())
                .apply();
    }

    private static int parse(EditText e) {
        try {
            return Math.max(0, Integer.parseInt(e.getText().toString().trim()));
        } catch (Exception ex) {
            return 0;
        }
    }

    // ------------------------------------------------------------------ UI state

    private void refresh() {
        boolean running = ScreenRecorderService.running;
        if (running) {
            long ms = SystemClock.elapsedRealtime() - ScreenRecorderService.startedAt;
            long limit = ScreenRecorderService.limitMs;
            if (limit > 0) ms = Math.min(ms, limit);
            timer.setText(clock(ms));
            status.setText(R.string.rec_running);
            startStop.setText(R.string.rec_stop);
            startStop.setBackgroundResource(R.drawable.bg_btn_danger);
            startStop.setTextColor(0xFFFFFFFF);
        } else {
            if (wasRunning) timer.setText("00:00");
            status.setText(R.string.rec_idle_hint);
            startStop.setText(R.string.rec_start);
            startStop.setBackgroundResource(R.drawable.btn_accent);
            startStop.setTextColor(0xFF000000);
        }
        wasRunning = running;
        limitSwitch.setEnabled(!running);
        systemSwitch.setEnabled(!running && Build.VERSION.SDK_INT >= 29);
        micSwitch.setEnabled(!running);
        limitMin.setEnabled(!running);
        limitSec.setEnabled(!running);
    }

    private static String clock(long ms) {
        long s = ms / 1000;
        return String.format(Locale.US, "%02d:%02d", s / 60, s % 60);
    }

    // ------------------------------------------------------------------ start flow

    private void onStartClicked() {
        if (limitSwitch.isChecked()) {
            long total = parse(limitMin) * 60L + parse(limitSec);
            if (total < 3) {
                Toast.makeText(this, R.string.rec_limit_short, Toast.LENGTH_SHORT).show();
                return;
            }
        }
        savePrefs();

        ArrayList<String> need = new ArrayList<>();
        boolean wantAudio = micSwitch.isChecked() || systemSwitch.isChecked();
        if (wantAudio && checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            need.add(Manifest.permission.RECORD_AUDIO);
        }
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            need.add(Manifest.permission.POST_NOTIFICATIONS);
        }
        if (need.isEmpty()) {
            requestCapture();
        } else {
            requestPermissions(need.toArray(new String[0]), REQ_PERMS);
        }
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        super.onRequestPermissionsResult(code, perms, results);
        if (code != REQ_PERMS) return;
        if ((micSwitch.isChecked() || systemSwitch.isChecked())
                && checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, R.string.rec_need_mic, Toast.LENGTH_LONG).show();
            micSwitch.setChecked(false);
            systemSwitch.setChecked(false);
        }
        requestCapture();
    }

    private void requestCapture() {
        MediaProjectionManager mpm =
                (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        try {
            startActivityForResult(mpm.createScreenCaptureIntent(), REQ_CAPTURE);
        } catch (Exception e) {
            Toast.makeText(this, R.string.rec_failed, Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_CAPTURE) return;
        if (resultCode != RESULT_OK || data == null) {
            Toast.makeText(this, R.string.rec_denied, Toast.LENGTH_SHORT).show();
            return;
        }
        long limit = 0;
        if (limitSwitch.isChecked()) limit = (parse(limitMin) * 60L + parse(limitSec)) * 1000L;

        Intent svc = new Intent(this, ScreenRecorderService.class)
                .setAction(ScreenRecorderService.ACTION_START)
                .putExtra(ScreenRecorderService.EXTRA_CODE, resultCode)
                .putExtra(ScreenRecorderService.EXTRA_DATA, data)
                .putExtra(ScreenRecorderService.EXTRA_INTERNAL, systemSwitch.isChecked())
                .putExtra(ScreenRecorderService.EXTRA_MIC, micSwitch.isChecked())
                .putExtra(ScreenRecorderService.EXTRA_LIMIT_MS, limit);
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(svc);
        else startService(svc);

        // Leave the app so the person can go and record whatever they want.
        moveTaskToBack(true);
    }
}
