package com.cameraframelab;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class IntervalAnalyzer {
    public static final class Stats {
        public final int samples;
        public final double effectiveFps;
        public final double meanMs;
        public final double medianMs;
        public final double minMs;
        public final double p95Ms;
        public final double p99Ms;
        public final double maxMs;
        public final double stdDevMs;
        public final double jitterMeanAbsMs;
        public final int gapCount;
        public final int estimatedMissingFrames;
        public final int intervalsOver125Pct;
        public final int intervalsOver150Pct;
        public final int intervalsOver200Pct;

        Stats(
                int samples,
                double effectiveFps,
                double meanMs,
                double medianMs,
                double minMs,
                double p95Ms,
                double p99Ms,
                double maxMs,
                double stdDevMs,
                double jitterMeanAbsMs,
                int gapCount,
                int estimatedMissingFrames,
                int intervalsOver125Pct,
                int intervalsOver150Pct,
                int intervalsOver200Pct
        ) {
            this.samples = samples;
            this.effectiveFps = effectiveFps;
            this.meanMs = meanMs;
            this.medianMs = medianMs;
            this.minMs = minMs;
            this.p95Ms = p95Ms;
            this.p99Ms = p99Ms;
            this.maxMs = maxMs;
            this.stdDevMs = stdDevMs;
            this.jitterMeanAbsMs = jitterMeanAbsMs;
            this.gapCount = gapCount;
            this.estimatedMissingFrames = estimatedMissingFrames;
            this.intervalsOver125Pct = intervalsOver125Pct;
            this.intervalsOver150Pct = intervalsOver150Pct;
            this.intervalsOver200Pct = intervalsOver200Pct;
        }
    }

    private IntervalAnalyzer() {}

    public static Stats analyzeNanoseconds(List<Long> timestampsNs, int targetFps) {
        int count = timestampsNs == null ? 0 : timestampsNs.size();
        if (timestampsNs == null || timestampsNs.size() < 2 || targetFps <= 0) {
            return empty(count);
        }

        ArrayList<Long> deltas = new ArrayList<>();
        long first = timestampsNs.get(0);
        long last = first;
        int gaps = 0;
        int missing = 0;
        int over125 = 0;
        int over150 = 0;
        int over200 = 0;
        final double expected = 1_000_000_000.0 / targetFps;

        double sum = 0;
        double sumSq = 0;
        double jitterAbs = 0;

        for (int i = 1; i < timestampsNs.size(); i++) {
            long current = timestampsNs.get(i);
            long delta = current - timestampsNs.get(i - 1);
            if (delta > 0) {
                deltas.add(delta);
                sum += delta;
                sumSq += (double) delta * delta;
                jitterAbs += Math.abs(delta - expected);

                if (delta > expected * 1.25) over125++;
                if (delta > expected * 1.50) {
                    over150++;
                    gaps++;
                    int periods = Math.max(1, (int) Math.round(delta / expected));
                    missing += Math.max(0, periods - 1);
                }
                if (delta > expected * 2.00) over200++;
            }
            last = current;
        }

        if (deltas.isEmpty() || last <= first) return empty(timestampsNs.size());

        ArrayList<Long> sorted = new ArrayList<>(deltas);
        Collections.sort(sorted);
        double seconds = (last - first) / 1_000_000_000.0;
        double fps = (timestampsNs.size() - 1) / seconds;
        double meanNs = sum / deltas.size();
        double variance = Math.max(0, sumSq / deltas.size() - meanNs * meanNs);

        return new Stats(
                timestampsNs.size(),
                fps,
                meanNs / 1_000_000.0,
                percentileNs(sorted, 0.50) / 1_000_000.0,
                sorted.get(0) / 1_000_000.0,
                percentileNs(sorted, 0.95) / 1_000_000.0,
                percentileNs(sorted, 0.99) / 1_000_000.0,
                sorted.get(sorted.size() - 1) / 1_000_000.0,
                Math.sqrt(variance) / 1_000_000.0,
                (jitterAbs / deltas.size()) / 1_000_000.0,
                gaps,
                missing,
                over125,
                over150,
                over200
        );
    }

    public static Stats analyzeMicroseconds(List<Long> timestampsUs, int targetFps) {
        ArrayList<Long> nanos = new ArrayList<>();
        if (timestampsUs != null) {
            for (Long value : timestampsUs) nanos.add(value * 1000L);
        }
        return analyzeNanoseconds(nanos, targetFps);
    }

    private static Stats empty(int samples) {
        return new Stats(samples,0,0,0,0,0,0,0,0,0,0,0,0,0,0);
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
