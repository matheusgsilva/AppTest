package com.cameraframelab;

import android.os.SystemClock;

import java.util.ArrayList;
import java.util.List;

public final class MetricCollector {
    public static final class CameraSample {
        public final long sequence;
        public final long frameNumber;
        public final long sensorTimestampNs;
        public final long callbackElapsedNs;
        CameraSample(long sequence, long frameNumber, long sensorTimestampNs, long callbackElapsedNs) {
            this.sequence = sequence;
            this.frameNumber = frameNumber;
            this.sensorTimestampNs = sensorTimestampNs;
            this.callbackElapsedNs = callbackElapsedNs;
        }
    }

    public static final class EncoderSample {
        public final long sequence;
        public final long ptsUs;
        public final int sizeBytes;
        public final int flags;
        public final long callbackElapsedNs;
        EncoderSample(long sequence, long ptsUs, int sizeBytes, int flags, long callbackElapsedNs) {
            this.sequence = sequence;
            this.ptsUs = ptsUs;
            this.sizeBytes = sizeBytes;
            this.flags = flags;
            this.callbackElapsedNs = callbackElapsedNs;
        }
    }

    public static final class ThermalSample {
        public final long elapsedNs;
        public final int thermalStatus;
        public final float batteryTempC;
        ThermalSample(long elapsedNs, int thermalStatus, float batteryTempC) {
            this.elapsedNs = elapsedNs;
            this.thermalStatus = thermalStatus;
            this.batteryTempC = batteryTempC;
        }
    }

    private final List<CameraSample> camera = new ArrayList<>();
    private final List<EncoderSample> encoder = new ArrayList<>();
    private final List<ThermalSample> thermal = new ArrayList<>();

    public synchronized void addCamera(long frameNumber, long sensorTimestampNs) {
        camera.add(new CameraSample(camera.size(), frameNumber, sensorTimestampNs, SystemClock.elapsedRealtimeNanos()));
    }

    public synchronized void addEncoder(long ptsUs, int sizeBytes, int flags) {
        encoder.add(new EncoderSample(encoder.size(), ptsUs, sizeBytes, flags, SystemClock.elapsedRealtimeNanos()));
    }

    public synchronized void addThermal(int status, float batteryTempC) {
        thermal.add(new ThermalSample(SystemClock.elapsedRealtimeNanos(), status, batteryTempC));
    }

    public synchronized List<CameraSample> cameraSnapshot() { return new ArrayList<>(camera); }
    public synchronized List<EncoderSample> encoderSnapshot() { return new ArrayList<>(encoder); }
    public synchronized List<ThermalSample> thermalSnapshot() { return new ArrayList<>(thermal); }
}
