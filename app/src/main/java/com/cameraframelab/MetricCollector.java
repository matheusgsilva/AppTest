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
        public final long exposureTimeNs;
        public final long frameDurationNs;
        public final int sensitivityIso;
        public final int aeState;
        public final int afState;
        public final int awbState;
        public final int videoStabilizationMode;
        public final int opticalStabilizationMode;
        public final float focalLengthMm;

        CameraSample(
                long sequence,
                long frameNumber,
                long sensorTimestampNs,
                long callbackElapsedNs,
                long exposureTimeNs,
                long frameDurationNs,
                int sensitivityIso,
                int aeState,
                int afState,
                int awbState,
                int videoStabilizationMode,
                int opticalStabilizationMode,
                float focalLengthMm
        ) {
            this.sequence = sequence;
            this.frameNumber = frameNumber;
            this.sensorTimestampNs = sensorTimestampNs;
            this.callbackElapsedNs = callbackElapsedNs;
            this.exposureTimeNs = exposureTimeNs;
            this.frameDurationNs = frameDurationNs;
            this.sensitivityIso = sensitivityIso;
            this.aeState = aeState;
            this.afState = afState;
            this.awbState = awbState;
            this.videoStabilizationMode = videoStabilizationMode;
            this.opticalStabilizationMode = opticalStabilizationMode;
            this.focalLengthMm = focalLengthMm;
        }
    }

    public static final class EncoderSample {
        public final long sequence;
        public final long ptsUs;
        public final int sizeBytes;
        public final int flags;
        public final long callbackElapsedNs;
        public final long dequeueWaitUs;

        EncoderSample(long sequence, long ptsUs, int sizeBytes, int flags, long callbackElapsedNs, long dequeueWaitUs) {
            this.sequence = sequence;
            this.ptsUs = ptsUs;
            this.sizeBytes = sizeBytes;
            this.flags = flags;
            this.callbackElapsedNs = callbackElapsedNs;
            this.dequeueWaitUs = dequeueWaitUs;
        }
    }

    public static final class ThermalSample {
        public final long elapsedNs;
        public final int thermalStatus;
        public final float batteryTempC;
        public final long availableMemoryBytes;
        public final int appPssKb;

        ThermalSample(long elapsedNs, int thermalStatus, float batteryTempC, long availableMemoryBytes, int appPssKb) {
            this.elapsedNs = elapsedNs;
            this.thermalStatus = thermalStatus;
            this.batteryTempC = batteryTempC;
            this.availableMemoryBytes = availableMemoryBytes;
            this.appPssKb = appPssKb;
        }
    }

    private final List<CameraSample> camera = new ArrayList<>();
    private final List<EncoderSample> encoder = new ArrayList<>();
    private final List<ThermalSample> thermal = new ArrayList<>();

    public synchronized void addCamera(
            long frameNumber,
            long sensorTimestampNs,
            long exposureTimeNs,
            long frameDurationNs,
            int sensitivityIso,
            int aeState,
            int afState,
            int awbState,
            int videoStabilizationMode,
            int opticalStabilizationMode,
            float focalLengthMm
    ) {
        camera.add(new CameraSample(
                camera.size(), frameNumber, sensorTimestampNs, SystemClock.elapsedRealtimeNanos(),
                exposureTimeNs, frameDurationNs, sensitivityIso, aeState, afState, awbState,
                videoStabilizationMode, opticalStabilizationMode, focalLengthMm
        ));
    }

    public synchronized void addEncoder(long ptsUs, int sizeBytes, int flags, long dequeueWaitUs) {
        encoder.add(new EncoderSample(
                encoder.size(), ptsUs, sizeBytes, flags,
                SystemClock.elapsedRealtimeNanos(), dequeueWaitUs
        ));
    }

    public synchronized void addThermal(
            int status,
            float batteryTempC,
            long availableMemoryBytes,
            int appPssKb
    ) {
        thermal.add(new ThermalSample(
                SystemClock.elapsedRealtimeNanos(), status, batteryTempC,
                availableMemoryBytes, appPssKb
        ));
    }

    public synchronized List<CameraSample> cameraSnapshot() { return new ArrayList<>(camera); }
    public synchronized List<EncoderSample> encoderSnapshot() { return new ArrayList<>(encoder); }
    public synchronized List<ThermalSample> thermalSnapshot() { return new ArrayList<>(thermal); }
}
