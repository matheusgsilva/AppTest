package com.cameraframelab;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.hardware.camera2.CameraManager;
import android.os.BatteryManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.view.Gravity;
import android.view.TextureView;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class MainActivity extends Activity implements CameraRecorder.Listener {
    private static final int REQ_CAMERA = 41;
    private static final long SUITE_RECORD_MS = 10_000L;
    private static final long SUITE_COOLDOWN_MS = 2_000L;

    private final Handler main = new Handler(Looper.getMainLooper());
    private CameraManager cameraManager;
    private CameraCapabilities capabilities;
    private CameraRecorder recorder;

    private TextureView preview;
    private Spinner profileSpinner;
    private Button refreshButton;
    private Button startButton;
    private Button stopButton;
    private Button suiteButton;
    private TextView status;
    private TextView live;
    private TextView capsText;

    private TestProfile activeProfile;
    private boolean suiteRunning;
    private final List<TestProfile> suiteQueue = new ArrayList<>();
    private int suiteIndex;
    private Runnable liveTicker;
    private Runnable suiteStopper;
    private Runnable thermalTicker;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        cameraManager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
        buildUi();
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.CAMERA}, REQ_CAMERA);
        } else {
            refreshCapabilities();
        }
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(14), dp(10), dp(14), dp(14));
        root.setBackgroundColor(Color.WHITE);

        TextView title = text("Camera Frame Lab", 24, true);
        root.addView(title);
        TextView subtitle = text("Diagnóstico isolado: Camera2 sensor timestamps vs MediaCodec PTS", 13, false);
        root.addView(subtitle, lpMatchWrap(0, dp(6)));

        preview = new TextureView(this);
        preview.setBackgroundColor(Color.BLACK);
        LinearLayout.LayoutParams previewLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(220));
        previewLp.setMargins(0, dp(8), 0, dp(8));
        root.addView(preview, previewLp);

        profileSpinner = new Spinner(this);
        root.addView(profileSpinner, lpMatchWrap(0, dp(4)));

        LinearLayout row1 = horizontal();
        refreshButton = button("Refresh capabilities");
        startButton = button("Start selected");
        row1.addView(refreshButton, weighted()); row1.addView(startButton, weighted());
        root.addView(row1);

        LinearLayout row2 = horizontal();
        stopButton = button("Stop");
        suiteButton = button("Run automatic suite");
        row2.addView(stopButton, weighted()); row2.addView(suiteButton, weighted());
        root.addView(row2);

        status = text("Ready", 14, true);
        root.addView(status, lpMatchWrap(dp(6), dp(2)));
        live = text("", 13, false);
        root.addView(live, lpMatchWrap(0, dp(2)));

        capsText = text("", 11, false);
        capsText.setTextIsSelectable(true);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(capsText);
        root.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        setContentView(root);

        refreshButton.setOnClickListener(v -> refreshCapabilities());
        startButton.setOnClickListener(v -> startSelected());
        stopButton.setOnClickListener(v -> stopCurrent());
        suiteButton.setOnClickListener(v -> { if (suiteRunning) cancelSuite(); else startSuite(); });
        updateButtons();
    }

    private void refreshCapabilities() {
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return;
        status.setText("Inspecting camera capabilities...");
        new Thread(() -> {
            try {
                CameraCapabilities c = CameraCapabilities.inspect(cameraManager);
                main.post(() -> {
                    capabilities = c;
                    ArrayAdapter<TestProfile> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, c.profiles);
                    adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
                    profileSpinner.setAdapter(adapter);
                    capsText.setText(c.description);
                    status.setText(c.profiles.isEmpty() ? "No test profile detected" : "Ready · " + c.profiles.size() + " profiles");
                    updateButtons();
                });
            } catch (Throwable t) {
                main.post(() -> showError(t));
            }
        }, "Capabilities").start();
    }

    private void startSelected() {
        if (capabilities == null || profileSpinner.getSelectedItem() == null) return;
        startProfile((TestProfile) profileSpinner.getSelectedItem());
    }

    private void startProfile(TestProfile profile) {
        if (recorder != null && recorder.isRunning()) return;
        if (profile.preview && !preview.isAvailable()) {
            status.setText("Preview surface is not ready yet. Try again.");
            return;
        }
        activeProfile = profile;
        recorder = new CameraRecorder(this, cameraManager, preview, main, this);
        status.setText("Starting " + profile.id + "...");
        updateButtons();
        recorder.start(capabilities, profile);
    }

    private void stopCurrent() {
        if (recorder != null && recorder.isRunning()) {
            status.setText("Stopping and finalizing report...");
            recorder.stop();
        }
    }

    private void startSuite() {
        if (capabilities == null || capabilities.profiles.isEmpty() || (recorder != null && recorder.isRunning())) return;
        suiteQueue.clear();
        suiteQueue.addAll(capabilities.profiles);
        suiteIndex = 0;
        suiteRunning = true;
        suiteButton.setText("Suite running");
        status.setText("Automatic suite: " + suiteQueue.size() + " tests");
        updateButtons();
        runNextSuiteProfile();
    }

    private void cancelSuite() {
        suiteRunning = false;
        suiteQueue.clear();
        if (suiteStopper != null) main.removeCallbacks(suiteStopper);
        suiteButton.setText("Run automatic suite");
        status.setText("Suite cancelled");
        if (recorder != null && recorder.isRunning()) recorder.stop();
        updateButtons();
    }

    private void runNextSuiteProfile() {
        if (!suiteRunning) return;
        if (suiteIndex >= suiteQueue.size()) {
            suiteRunning = false;
            suiteButton.setText("Run automatic suite");
            status.setText("Suite complete. Reports: DCIM/CameraFrameLab · Videos: Movies/CameraFrameLab");
            updateButtons();
            return;
        }
        TestProfile p = suiteQueue.get(suiteIndex);
        status.setText("Suite " + (suiteIndex + 1) + "/" + suiteQueue.size() + " · starting " + p.id);
        startProfile(p);
    }

    @Override public void onStarted(String baseName, android.net.Uri videoUri) {
        updateButtons();
        startThermalTicker();
        startLiveTicker();
        if (suiteRunning) {
            suiteStopper = this::stopCurrent;
            main.postDelayed(suiteStopper, SUITE_RECORD_MS);
        }
    }

    @Override public void onStatus(String text) {
        status.setText(text);
    }

    @Override public void onStopped(CameraRecorder.SessionResult result) {
        stopTickers();
        final TestProfile finishedProfile = activeProfile;
        recorder = null;
        activeProfile = null;
        updateButtons();
        status.setText("Writing CSV/JSON report for " + result.baseName + "...");
        new Thread(() -> {
            try {
                ReportWriter.ReportResult report = ReportWriter.write(this, result, finishedProfile, capabilities);
                main.post(() -> {
                    String summary = String.format(Locale.US,
                            "%s\nCamera %.3f fps · gaps %d · missing~%d · max %.2f ms\nEncoder %.3f fps · gaps %d · missing~%d · max %.2f ms · repeatedPTS %d",
                            result.baseName,
                            report.cameraStats.effectiveFps, report.cameraStats.gapCount, report.cameraStats.estimatedMissingFrames, report.cameraStats.maxMs,
                            report.encoderStats.effectiveFps, report.encoderStats.gapCount, report.encoderStats.estimatedMissingFrames, report.encoderStats.maxMs,
                            report.repeatedEncoderPts);
                    live.setText(summary);
                    if (suiteRunning) {
                        suiteIndex++;
                        status.setText("Saved report. Cooling briefly before next test...");
                        main.postDelayed(this::runNextSuiteProfile, SUITE_COOLDOWN_MS);
                    } else {
                        status.setText("Saved. Reports: DCIM/CameraFrameLab · Video: Movies/CameraFrameLab");
                    }
                });
            } catch (Throwable t) {
                main.post(() -> {
                    showError(t);
                    if (suiteRunning) {
                        suiteIndex++;
                        main.postDelayed(this::runNextSuiteProfile, SUITE_COOLDOWN_MS);
                    }
                });
            }
        }, "ReportWriter").start();
    }

    @Override public void onError(Throwable error) {
        stopTickers();
        recorder = null;
        activeProfile = null;
        showError(error);
        if (suiteRunning) {
            suiteIndex++;
            main.postDelayed(this::runNextSuiteProfile, SUITE_COOLDOWN_MS);
        }
        updateButtons();
    }

    private void startLiveTicker() {
        liveTicker = new Runnable() {
            @Override public void run() {
                CameraRecorder r = recorder;
                if (r == null || activeProfile == null) return;
                live.setText("Recording " + activeProfile + "\nFinal sensor/encoder stats will be written when stopped.");
                main.postDelayed(this, 1000);
            }
        };
        main.post(liveTicker);
    }

    private void startThermalTicker() {
        thermalTicker = new Runnable() {
            @Override public void run() {
                if (recorder == null || activeProfile == null) return;
                try { recorder.addThermalSample(currentThermalStatus(), batteryTempC()); } catch (Throwable ignored) {}
                main.postDelayed(this, 1000);
            }
        };
        main.post(thermalTicker);
    }

    private int currentThermalStatus() {
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        return pm == null ? -1 : pm.getCurrentThermalStatus();
    }

    private float batteryTempC() {
        Intent i = registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if (i == null) return Float.NaN;
        int tenths = i.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Integer.MIN_VALUE);
        return tenths == Integer.MIN_VALUE ? Float.NaN : tenths / 10f;
    }

    private void stopTickers() {
        if (liveTicker != null) main.removeCallbacks(liveTicker);
        if (thermalTicker != null) main.removeCallbacks(thermalTicker);
        if (suiteStopper != null) main.removeCallbacks(suiteStopper);
        liveTicker = null; thermalTicker = null; suiteStopper = null;
    }

    private void updateButtons() {
        boolean running = recorder != null && recorder.isRunning();
        refreshButton.setEnabled(!running && !suiteRunning);
        startButton.setEnabled(!running && !suiteRunning && capabilities != null && !capabilities.profiles.isEmpty());
        stopButton.setEnabled(running);
        suiteButton.setEnabled((suiteRunning && running) || (!running && capabilities != null && !capabilities.profiles.isEmpty()));
    }

    private void showError(Throwable t) {
        status.setText("ERROR: " + t.getClass().getSimpleName() + ": " + String.valueOf(t.getMessage()));
        capsText.append("\n\nERROR\n" + android.util.Log.getStackTraceString(t));
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_CAMERA && grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) refreshCapabilities();
        else status.setText("Camera permission is required.");
    }

    @Override protected void onDestroy() {
        suiteRunning = false;
        stopTickers();
        if (recorder != null && recorder.isRunning()) recorder.stop();
        super.onDestroy();
    }

    private TextView text(String s, int sp, boolean bold) {
        TextView v = new TextView(this); v.setText(s); v.setTextSize(sp); v.setTextColor(Color.rgb(20,20,20));
        if (bold) v.setTypeface(v.getTypeface(), android.graphics.Typeface.BOLD); return v;
    }
    private Button button(String s) { Button b = new Button(this); b.setText(s); b.setAllCaps(false); return b; }
    private LinearLayout horizontal() { LinearLayout l=new LinearLayout(this); l.setOrientation(LinearLayout.HORIZONTAL); l.setGravity(Gravity.CENTER); return l; }
    private LinearLayout.LayoutParams weighted() { LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT,1f); p.setMargins(dp(2),dp(2),dp(2),dp(2)); return p; }
    private LinearLayout.LayoutParams lpMatchWrap(int top, int bottom) { LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT); p.setMargins(0,top,0,bottom); return p; }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
