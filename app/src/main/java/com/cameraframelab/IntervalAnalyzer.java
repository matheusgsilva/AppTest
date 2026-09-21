package com.cameraframelab;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class IntervalAnalyzer {
    public static final class Stats {
        public final int samples;
        public final double effectiveFps;
        public final double medianMs;
        public final double p95Ms;
        public final double p99Ms;
        public final double maxMs;
        public final int gapCount;
        public final int estimatedMissingFrames;

        Stats(int samples, double effectiveFps, double medianMs, double p95Ms,
              double p99Ms, double maxMs, int gapCount, int estimatedMissingFrames) {
            this.samples = samples;
            this.effectiveFps = effectiveFps;
            this.medianMs = medianMs;
            this.p95Ms = p95Ms;
            this.p99Ms = p99Ms;
            this.maxMs = maxMs;
            this.gapCount = gapCount;
            this.estimatedMissingFrames = estimatedMissingFrames;
        }
    }

    private IntervalAnalyzer() {}

    public static Stats analyzeNanoseconds(List<Long> timestampsNs, int targetFps) {
        if (timestampsNs == null || timestampsNs.size() < 2 || targetFps <= 0) {
            return new Stats(timestampsNs == null ? 0 : timestampsNs.size(), 0, 0, 0, 0, 0, 0, 0);
        }
        ArrayList<Long> deltas = new ArrayList<>();
        long first = timestampsNs.get(0);
        long last = first;
        int gaps = 0;
        int missing = 0;
        final double expected = 1_000_000_000.0 / targetFps;
        final double gapThreshold = expected * 1.5;

        for (int i = 1; i < timestampsNs.size(); i++) {
            long current = timestampsNs.get(i);
            long delta = current - timestampsNs.get(i - 1);
            if (delta > 0) {
                deltas.add(delta);
                if (delta > gapThreshold) {
                    gaps++;
                    int periods = Math.max(1, (int) Math.round(delta / expected));
                    missing += Math.max(0, periods - 1);
                }
            }
            last = current;
        }
        if (deltas.isEmpty() || last <= first) {
            return new Stats(timestampsNs.size(), 0, 0, 0, 0, 0, gaps, missing);
        }
        Collections.sort(deltas);
        double seconds = (last - first) / 1_000_000_000.0;
        double fps = (timestampsNs.size() - 1) / seconds;
        return new Stats(
                timestampsNs.size(), fps,
                percentileNs(deltas, 0.50) / 1_000_000.0,
                percentileNs(deltas, 0.95) / 1_000_000.0,
                percentileNs(deltas, 0.99) / 1_000_000.0,
                deltas.get(deltas.size() - 1) / 1_000_000.0,
                gaps, missing);
    }

    public static Stats analyzeMicroseconds(List<Long> timestampsUs, int targetFps) {
        ArrayList<Long> nanos = new ArrayList<>();
        if (timestampsUs != null) {
            for (Long value : timestampsUs) nanos.add(value * 1000L);
        }
        return analyzeNanoseconds(nanos, targetFps);
    }

    private static double percentileNs(List<Long> sorted, double p) {
        if (sorted.isEmpty()) return 0;
        double index = (sorted.size() - 1) * p;
        int low = (int) Math.floor(index);
        int high = (int) Math.ceil(index);
        if (low == high) return sorted.get(low);
        double weight = index - low;
        return sorted.get(low) * (1.0 - weight) + sorted.get(high) * weight;
    }
}
