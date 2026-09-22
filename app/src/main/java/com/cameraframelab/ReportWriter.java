package com.cameraframelab;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.provider.MediaStore;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class ReportWriter {
    public static final class ReportResult {
        public final Uri summaryUri;
        public final Uri cameraCsvUri;
        public final Uri encoderCsvUri;
        public final Uri thermalCsvUri;
        public final Uri eventsCsvUri;
        public final IntervalAnalyzer.Stats cameraStats;
        public final IntervalAnalyzer.Stats encoderStats;
        public final int repeatedEncoderPts;

        ReportResult(
                Uri summaryUri,
                Uri cameraCsvUri,
                Uri encoderCsvUri,
                Uri thermalCsvUri,
                Uri eventsCsvUri,
                IntervalAnalyzer.Stats cameraStats,
                IntervalAnalyzer.Stats encoderStats,
                int repeatedEncoderPts
        ) {
            this.summaryUri = summaryUri;
            this.cameraCsvUri = cameraCsvUri;
            this.encoderCsvUri = encoderCsvUri;
            this.thermalCsvUri = thermalCsvUri;
            this.eventsCsvUri = eventsCsvUri;
            this.cameraStats = cameraStats;
            this.encoderStats = encoderStats;
            this.repeatedEncoderPts = repeatedEncoderPts;
        }
    }

    public static final class SuiteEntry {
        public final String profileId;
        public final boolean success;
        public final String error;
        public final double cameraFps;
        public final int cameraGaps;
        public final int cameraMissing;
        public final double encoderFps;
        public final int encoderGaps;
        public final int encoderMissing;
        public final int repeatedPts;
        public final double cameraMaxMs;
        public final double encoderMaxMs;

        private SuiteEntry(
                String profileId,
                boolean success,
                String error,
                double cameraFps,
                int cameraGaps,
                int cameraMissing,
                double encoderFps,
                int encoderGaps,
                int encoderMissing,
                int repeatedPts,
                double cameraMaxMs,
                double encoderMaxMs
        ) {
            this.profileId = profileId;
            this.success = success;
            this.error = error;
            this.cameraFps = cameraFps;
            this.cameraGaps = cameraGaps;
            this.cameraMissing = cameraMissing;
            this.encoderFps = encoderFps;
            this.encoderGaps = encoderGaps;
            this.encoderMissing = encoderMissing;
            this.repeatedPts = repeatedPts;
            this.cameraMaxMs = cameraMaxMs;
            this.encoderMaxMs = encoderMaxMs;
        }

        public static SuiteEntry success(String profileId, ReportResult r) {
            return new SuiteEntry(
                    profileId, true, "",
                    r.cameraStats.effectiveFps,
                    r.cameraStats.gapCount,
                    r.cameraStats.estimatedMissingFrames,
                    r.encoderStats.effectiveFps,
                    r.encoderStats.gapCount,
                    r.encoderStats.estimatedMissingFrames,
                    r.repeatedEncoderPts,
                    r.cameraStats.maxMs,
                    r.encoderStats.maxMs
            );
        }

        public static SuiteEntry failure(String profileId, Throwable t) {
            return new SuiteEntry(
                    profileId, false,
                    t == null ? "unknown" : t.getClass().getSimpleName() + ": " + String.valueOf(t.getMessage()),
                    0,0,0,0,0,0,0,0,0
            );
        }
    }

    private ReportWriter() {}

    public static ReportResult write(
            Context context,
            CameraRecorder.SessionResult session,
            TestProfile profile,
            CameraCapabilities caps
    ) throws Exception {
        List<MetricCollector.CameraSample> camera = session.metrics.cameraSnapshot();
        List<MetricCollector.EncoderSample> encoder = session.metrics.encoderSnapshot();
        List<MetricCollector.ThermalSample> thermal = session.metrics.thermalSnapshot();
        List<MetricCollector.EventSample> internalEvents = session.metrics.eventSnapshot();

        List<Long> sensorTs = new ArrayList<>();
        for (MetricCollector.CameraSample s : camera) sensorTs.add(s.sensorTimestampNs);

        List<Long> pts = new ArrayList<>();
        Set<Long> uniquePts = new HashSet<>();
        int repeatedPts = 0;
        for (MetricCollector.EncoderSample s : encoder) {
            pts.add(s.ptsUs);
            if (!uniquePts.add(s.ptsUs)) repeatedPts++;
        }

        IntervalAnalyzer.Stats cameraStats = IntervalAnalyzer.analyzeNanoseconds(sensorTs, profile.fps);
        IntervalAnalyzer.Stats encoderStats = IntervalAnalyzer.analyzeMicroseconds(pts, profile.fps);

        JSONObject root = new JSONObject();
        root.put("schemaVersion", 3);
        root.put("baseName", session.baseName);
        root.put("videoUri", String.valueOf(session.videoUri));
        root.put("profile", profileJson(profile));
        root.put("durationSeconds", (session.endElapsedNs - session.startElapsedNs) / 1_000_000_000.0);
        root.put("device", deviceJson());
        root.put("cameraCapabilities", caps.description);
        root.put("requestDescription", session.requestDescription);
        root.put("encoderName", session.encoderName);
        root.put("encoderOutputFormat", session.encoderOutputFormat);
        root.put("cameraStats", statsJson(cameraStats));
        root.put("encoderStats", statsJson(encoderStats));
        root.put("repeatedEncoderPts", repeatedPts);
        root.put("cameraSampleCount", camera.size());
        root.put("encoderSampleCount", encoder.size());
        root.put("thermalSummary", thermalSummaryJson(thermal));
        root.put("thermalSamples", thermalJson(thermal));
        root.put("cameraGapEvents", cameraGapEventsJson(camera, profile.fps));
        root.put("encoderGapEvents", encoderGapEventsJson(encoder, profile.fps));
        root.put("gapCorrelation", gapCorrelationJson(camera, encoder, profile.fps));
        root.put("internalEvents", internalEventsJson(internalEvents));
        root.put("diagnosisHint", diagnosis(cameraStats, encoderStats));

        Uri summary = writeSharedFile(context, session.baseName + "_summary.json", "application/json", root.toString(2));
        Uri cameraCsv = writeSharedFile(context, session.baseName + "_camera.csv", "text/csv", cameraCsv(camera, profile.fps));
        Uri encoderCsv = writeSharedFile(context, session.baseName + "_encoder.csv", "text/csv", encoderCsv(encoder, profile.fps));
        Uri thermalCsv = writeSharedFile(context, session.baseName + "_thermal.csv", "text/csv", thermalCsv(thermal));
        Uri eventsCsv = writeSharedFile(context, session.baseName + "_events.csv", "text/csv", eventsCsv(camera, encoder, internalEvents, profile.fps));

        return new ReportResult(
                summary, cameraCsv, encoderCsv, thermalCsv, eventsCsv,
                cameraStats, encoderStats, repeatedPts
        );
    }

    public static Uri writeSuiteSummary(Context context, List<SuiteEntry> entries) throws Exception {
        String stamp = new java.text.SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new java.util.Date());
        StringBuilder csv = new StringBuilder(
                "profileId,success,error,cameraFps,cameraGaps,cameraMissing,cameraMaxMs," +
                "encoderFps,encoderGaps,encoderMissing,encoderMaxMs,repeatedPts\n"
        );
        JSONArray jsonEntries = new JSONArray();
        for (SuiteEntry e : entries) {
            csv.append(csvSafe(e.profileId)).append(',')
                    .append(e.success).append(',')
                    .append(csvSafe(e.error)).append(',')
                    .append(String.format(Locale.US, "%.6f", e.cameraFps)).append(',')
                    .append(e.cameraGaps).append(',')
                    .append(e.cameraMissing).append(',')
                    .append(String.format(Locale.US, "%.6f", e.cameraMaxMs)).append(',')
                    .append(String.format(Locale.US, "%.6f", e.encoderFps)).append(',')
                    .append(e.encoderGaps).append(',')
                    .append(e.encoderMissing).append(',')
                    .append(String.format(Locale.US, "%.6f", e.encoderMaxMs)).append(',')
                    .append(e.repeatedPts).append('\n');

            JSONObject o = new JSONObject();
            o.put("profileId", e.profileId);
            o.put("success", e.success);
            o.put("error", e.error);
            o.put("cameraFps", e.cameraFps);
            o.put("cameraGaps", e.cameraGaps);
            o.put("cameraMissing", e.cameraMissing);
            o.put("cameraMaxMs", e.cameraMaxMs);
            o.put("encoderFps", e.encoderFps);
            o.put("encoderGaps", e.encoderGaps);
            o.put("encoderMissing", e.encoderMissing);
            o.put("encoderMaxMs", e.encoderMaxMs);
            o.put("repeatedPts", e.repeatedPts);
            jsonEntries.put(o);
        }

        JSONObject root = new JSONObject();
        root.put("schemaVersion", 1);
        root.put("createdAt", stamp);
        root.put("entries", jsonEntries);
        root.put("count", entries.size());

        writeSharedFile(context, stamp + "_suite_summary.json", "application/json", root.toString(2));
        return writeSharedFile(context, stamp + "_suite_summary.csv", "text/csv", csv.toString());
    }

    private static String csvSafe(String value) {
        if (value == null) return "";
        String s = value.replace("\"", "\"\"");
        return "\"" + s + "\"";
    }

    private static JSONObject profileJson(TestProfile p) throws Exception {
        JSONObject o = new JSONObject();
        o.put("id", p.id);
        o.put("width", p.size.getWidth());
        o.put("height", p.size.getHeight());
        o.put("fps", p.fps);
        o.put("preview", p.preview);
        o.put("highSpeed", p.highSpeed);
        o.put("codecMime", p.codecMime);
        o.put("codecLabel", p.codecLabel());
        o.put("bitrate", p.bitrate);
        o.put("bitrateMbps", p.bitrate / 1_000_000.0);
        o.put("aeLowerFps", p.aeLowerFps);
        o.put("aeUpperFps", p.aeUpperFps);
        o.put("videoStabilizationMode", p.videoStabilizationMode);
        o.put("opticalStabilizationMode", p.opticalStabilizationMode);
        o.put("cameraTemplate", p.cameraTemplate);
        o.put("cameraTemplateLabel", p.templateLabel());
        return o;
    }

    private static JSONObject deviceJson() throws Exception {
        JSONObject o = new JSONObject();
        o.put("manufacturer", Build.MANUFACTURER);
        o.put("brand", Build.BRAND);
        o.put("model", Build.MODEL);
        o.put("device", Build.DEVICE);
        o.put("product", Build.PRODUCT);
        o.put("board", Build.BOARD);
        o.put("hardware", Build.HARDWARE);
        if (Build.VERSION.SDK_INT >= 31) {
            o.put("socManufacturer", Build.SOC_MANUFACTURER);
            o.put("socModel", Build.SOC_MODEL);
        }
        o.put("sdk", Build.VERSION.SDK_INT);
        o.put("release", Build.VERSION.RELEASE);
        o.put("incremental", Build.VERSION.INCREMENTAL);
        o.put("display", Build.DISPLAY);
        o.put("fingerprint", Build.FINGERPRINT);
        return o;
    }

    private static JSONObject statsJson(IntervalAnalyzer.Stats s) throws Exception {
        JSONObject o = new JSONObject();
        o.put("samples", s.samples);
        o.put("effectiveFps", s.effectiveFps);
        o.put("meanMs", s.meanMs);
        o.put("medianMs", s.medianMs);
        o.put("minMs", s.minMs);
        o.put("p95Ms", s.p95Ms);
        o.put("p99Ms", s.p99Ms);
        o.put("maxMs", s.maxMs);
        o.put("stdDevMs", s.stdDevMs);
        o.put("jitterMeanAbsMs", s.jitterMeanAbsMs);
        o.put("gapCount", s.gapCount);
        o.put("estimatedMissingFrames", s.estimatedMissingFrames);
        o.put("intervalsOver125Pct", s.intervalsOver125Pct);
        o.put("intervalsOver150Pct", s.intervalsOver150Pct);
        o.put("intervalsOver200Pct", s.intervalsOver200Pct);
        return o;
    }

    private static JSONObject thermalSummaryJson(List<MetricCollector.ThermalSample> samples) throws Exception {
        JSONObject o = new JSONObject();
        if (samples.isEmpty()) {
            o.put("samples", 0);
            return o;
        }
        float minTemp = Float.POSITIVE_INFINITY;
        float maxTemp = Float.NEGATIVE_INFINITY;
        int maxThermal = Integer.MIN_VALUE;
        long minMemory = Long.MAX_VALUE;
        int maxPss = 0;
        for (MetricCollector.ThermalSample s : samples) {
            if (!Float.isNaN(s.batteryTempC)) {
                minTemp = Math.min(minTemp, s.batteryTempC);
                maxTemp = Math.max(maxTemp, s.batteryTempC);
            }
            maxThermal = Math.max(maxThermal, s.thermalStatus);
            if (s.availableMemoryBytes >= 0) minMemory = Math.min(minMemory, s.availableMemoryBytes);
            maxPss = Math.max(maxPss, s.appPssKb);
        }
        o.put("samples", samples.size());
        o.put("minBatteryTempC", minTemp == Float.POSITIVE_INFINITY ? JSONObject.NULL : minTemp);
        o.put("maxBatteryTempC", maxTemp == Float.NEGATIVE_INFINITY ? JSONObject.NULL : maxTemp);
        o.put("maxThermalStatus", maxThermal);
        o.put("minAvailableMemoryBytes", minMemory == Long.MAX_VALUE ? JSONObject.NULL : minMemory);
        o.put("maxAppPssKb", maxPss);
        return o;
    }

    private static JSONArray thermalJson(List<MetricCollector.ThermalSample> samples) throws Exception {
        JSONArray a = new JSONArray();
        for (MetricCollector.ThermalSample s : samples) {
            JSONObject o = new JSONObject();
            o.put("elapsedNs", s.elapsedNs);
            o.put("thermalStatus", s.thermalStatus);
            o.put("batteryTempC", s.batteryTempC);
            o.put("availableMemoryBytes", s.availableMemoryBytes);
            o.put("appPssKb", s.appPssKb);
            a.put(o);
        }
        return a;
    }

    private static JSONArray internalEventsJson(List<MetricCollector.EventSample> events) throws Exception {
        JSONArray a = new JSONArray();
        for (MetricCollector.EventSample e : events) {
            JSONObject o = new JSONObject();
            o.put("elapsedNs", e.elapsedNs);
            o.put("source", e.source);
            o.put("type", e.type);
            o.put("detail", e.detail);
            a.put(o);
        }
        return a;
    }

    private static JSONArray cameraGapEventsJson(List<MetricCollector.CameraSample> list, int fps) throws Exception {
        JSONArray a = new JSONArray();
        double expected = 1_000_000_000.0 / fps;
        if (list.isEmpty()) return a;
        long first = list.get(0).sensorTimestampNs;
        for (int i = 1; i < list.size(); i++) {
            MetricCollector.CameraSample prev = list.get(i - 1);
            MetricCollector.CameraSample cur = list.get(i);
            long delta = cur.sensorTimestampNs - prev.sensorTimestampNs;
            if (delta <= expected * 1.25) continue;
            JSONObject o = new JSONObject();
            o.put("sequence", cur.sequence);
            o.put("timeSeconds", (cur.sensorTimestampNs - first) / 1_000_000_000.0);
            o.put("deltaMs", delta / 1_000_000.0);
            o.put("estimatedMissing", missing(delta, expected));
            o.put("frameNumberJump", cur.frameNumber - prev.frameNumber);
            o.put("exposureMs", cur.exposureTimeNs < 0 ? JSONObject.NULL : cur.exposureTimeNs / 1_000_000.0);
            o.put("frameDurationMs", cur.frameDurationNs < 0 ? JSONObject.NULL : cur.frameDurationNs / 1_000_000.0);
            o.put("iso", cur.sensitivityIso);
            o.put("aeState", cur.aeState);
            o.put("afState", cur.afState);
            o.put("awbState", cur.awbState);
            o.put("eisMode", cur.videoStabilizationMode);
            o.put("oisMode", cur.opticalStabilizationMode);
            o.put("focalLengthMm", cur.focalLengthMm);
            a.put(o);
        }
        return a;
    }

    private static JSONArray encoderGapEventsJson(List<MetricCollector.EncoderSample> list, int fps) throws Exception {
        JSONArray a = new JSONArray();
        double expectedUs = 1_000_000.0 / fps;
        if (list.isEmpty()) return a;
        long first = list.get(0).ptsUs;
        for (int i = 1; i < list.size(); i++) {
            MetricCollector.EncoderSample prev = list.get(i - 1);
            MetricCollector.EncoderSample cur = list.get(i);
            long delta = cur.ptsUs - prev.ptsUs;
            if (delta <= expectedUs * 1.25) continue;
            JSONObject o = new JSONObject();
            o.put("sequence", cur.sequence);
            o.put("timeSeconds", (cur.ptsUs - first) / 1_000_000.0);
            o.put("deltaMs", delta / 1000.0);
            o.put("estimatedMissing", missing(delta, expectedUs));
            o.put("sizeBytes", cur.sizeBytes);
            o.put("flags", cur.flags);
            o.put("dequeueWaitUs", cur.dequeueWaitUs);
            a.put(o);
        }
        return a;
    }

    private static JSONObject gapCorrelationJson(
            List<MetricCollector.CameraSample> camera,
            List<MetricCollector.EncoderSample> encoder,
            int fps
    ) throws Exception {
        JSONObject o = new JSONObject();
        List<Double> cameraTimes = cameraGapTimes(camera, fps);
        List<Double> encoderTimes = encoderGapTimes(encoder, fps);
        int matched = 0;
        double tolerance = Math.max(0.050, 3.0 / fps);
        for (double c : cameraTimes) {
            for (double e : encoderTimes) {
                if (Math.abs(c - e) <= tolerance) {
                    matched++;
                    break;
                }
            }
        }
        o.put("cameraGapEvents", cameraTimes.size());
        o.put("encoderGapEvents", encoderTimes.size());
        o.put("matchedWithinSeconds", tolerance);
        o.put("matchedEvents", matched);
        o.put("cameraMatchRatio", cameraTimes.isEmpty() ? 1.0 : matched / (double) cameraTimes.size());
        return o;
    }

    private static List<Double> cameraGapTimes(List<MetricCollector.CameraSample> list, int fps) {
        List<Double> out = new ArrayList<>();
        if (list.isEmpty()) return out;
        double expected = 1_000_000_000.0 / fps;
        long first = list.get(0).sensorTimestampNs;
        for (int i = 1; i < list.size(); i++) {
            long delta = list.get(i).sensorTimestampNs - list.get(i - 1).sensorTimestampNs;
            if (delta > expected * 1.25) {
                out.add((list.get(i).sensorTimestampNs - first) / 1_000_000_000.0);
            }
        }
        return out;
    }

    private static List<Double> encoderGapTimes(List<MetricCollector.EncoderSample> list, int fps) {
        List<Double> out = new ArrayList<>();
        if (list.isEmpty()) return out;
        double expected = 1_000_000.0 / fps;
        long first = list.get(0).ptsUs;
        for (int i = 1; i < list.size(); i++) {
            long delta = list.get(i).ptsUs - list.get(i - 1).ptsUs;
            if (delta > expected * 1.25) {
                out.add((list.get(i).ptsUs - first) / 1_000_000.0);
            }
        }
        return out;
    }

    private static String diagnosis(IntervalAnalyzer.Stats camera, IntervalAnalyzer.Stats encoder) {
        if (camera.gapCount > 0 && encoder.gapCount > 0) return "CAMERA_GAPS_PROPAGATE_TO_ENCODER";
        if (camera.gapCount > 0) return "CAMERA_SENSOR_CADENCE_GAPS";
        if (encoder.gapCount > 0) return "ENCODER_ONLY_GAPS";
        return "NO_TIMESTAMP_GAPS_DETECTED";
    }

    private static String cameraCsv(List<MetricCollector.CameraSample> list, int fps) {
        StringBuilder b = new StringBuilder(
                "sequence,frameNumber,sensorTimestampNs,deltaSensorNs,deltaMs,expectedMs,gap125,gap150,estimatedMissing," +
                "callbackElapsedNs,deltaCallbackNs,exposureTimeNs,frameDurationNs,sensitivityIso,aeState,afState,awbState," +
                "videoStabilizationMode,opticalStabilizationMode,focalLengthMm\n"
        );
        long prevTs = 0, prevCb = 0;
        double expectedNs = 1_000_000_000.0 / fps;
        for (MetricCollector.CameraSample s : list) {
            long delta = prevTs == 0 ? 0 : s.sensorTimestampNs - prevTs;
            boolean gap125 = prevTs != 0 && delta > expectedNs * 1.25;
            boolean gap150 = prevTs != 0 && delta > expectedNs * 1.50;
            b.append(s.sequence).append(',')
                    .append(s.frameNumber).append(',')
                    .append(s.sensorTimestampNs).append(',')
                    .append(delta).append(',')
                    .append(delta / 1_000_000.0).append(',')
                    .append(expectedNs / 1_000_000.0).append(',')
                    .append(gap125).append(',')
                    .append(gap150).append(',')
                    .append(prevTs == 0 ? 0 : missing(delta, expectedNs)).append(',')
                    .append(s.callbackElapsedNs).append(',')
                    .append(prevCb == 0 ? 0 : s.callbackElapsedNs - prevCb).append(',')
                    .append(s.exposureTimeNs).append(',')
                    .append(s.frameDurationNs).append(',')
                    .append(s.sensitivityIso).append(',')
                    .append(s.aeState).append(',')
                    .append(s.afState).append(',')
                    .append(s.awbState).append(',')
                    .append(s.videoStabilizationMode).append(',')
                    .append(s.opticalStabilizationMode).append(',')
                    .append(String.format(Locale.US, "%.3f", s.focalLengthMm))
                    .append('\n');
            prevTs = s.sensorTimestampNs;
            prevCb = s.callbackElapsedNs;
        }
        return b.toString();
    }

    private static String encoderCsv(List<MetricCollector.EncoderSample> list, int fps) {
        StringBuilder b = new StringBuilder(
                "sequence,ptsUs,deltaPtsUs,deltaMs,expectedMs,gap125,gap150,estimatedMissing,sizeBytes,flags," +
                "callbackElapsedNs,deltaCallbackNs,dequeueWaitUs\n"
        );
        long prevPts = 0, prevCb = 0;
        double expectedUs = 1_000_000.0 / fps;
        for (MetricCollector.EncoderSample s : list) {
            long delta = prevPts == 0 ? 0 : s.ptsUs - prevPts;
            boolean gap125 = prevPts != 0 && delta > expectedUs * 1.25;
            boolean gap150 = prevPts != 0 && delta > expectedUs * 1.50;
            b.append(s.sequence).append(',')
                    .append(s.ptsUs).append(',')
                    .append(delta).append(',')
                    .append(delta / 1000.0).append(',')
                    .append(expectedUs / 1000.0).append(',')
                    .append(gap125).append(',')
                    .append(gap150).append(',')
                    .append(prevPts == 0 ? 0 : missing(delta, expectedUs)).append(',')
                    .append(s.sizeBytes).append(',')
                    .append(s.flags).append(',')
                    .append(s.callbackElapsedNs).append(',')
                    .append(prevCb == 0 ? 0 : s.callbackElapsedNs - prevCb).append(',')
                    .append(s.dequeueWaitUs)
                    .append('\n');
            prevPts = s.ptsUs;
            prevCb = s.callbackElapsedNs;
        }
        return b.toString();
    }

    private static String thermalCsv(List<MetricCollector.ThermalSample> list) {
        StringBuilder b = new StringBuilder(
                "elapsedNs,thermalStatus,batteryTempC,availableMemoryBytes,appPssKb\n"
        );
        for (MetricCollector.ThermalSample s : list) {
            b.append(s.elapsedNs).append(',')
                    .append(s.thermalStatus).append(',')
                    .append(String.format(Locale.US, "%.1f", s.batteryTempC)).append(',')
                    .append(s.availableMemoryBytes).append(',')
                    .append(s.appPssKb)
                    .append('\n');
        }
        return b.toString();
    }

    private static String eventsCsv(
            List<MetricCollector.CameraSample> camera,
            List<MetricCollector.EncoderSample> encoder,
            List<MetricCollector.EventSample> internalEvents,
            int fps
    ) {
        StringBuilder b = new StringBuilder(
                "source,sequence,timeSeconds,deltaMs,estimatedMissing,details\n"
        );
        double expectedNs = 1_000_000_000.0 / fps;
        if (!camera.isEmpty()) {
            long first = camera.get(0).sensorTimestampNs;
            for (int i = 1; i < camera.size(); i++) {
                MetricCollector.CameraSample prev = camera.get(i - 1);
                MetricCollector.CameraSample cur = camera.get(i);
                long delta = cur.sensorTimestampNs - prev.sensorTimestampNs;
                if (delta <= expectedNs * 1.25) continue;
                b.append("camera,")
                        .append(cur.sequence).append(',')
                        .append((cur.sensorTimestampNs - first) / 1_000_000_000.0).append(',')
                        .append(delta / 1_000_000.0).append(',')
                        .append(missing(delta, expectedNs)).append(',')
                        .append('"')
                        .append("frameJump=").append(cur.frameNumber - prev.frameNumber)
                        .append(";frameDurationNs=").append(cur.frameDurationNs)
                        .append(";exposureNs=").append(cur.exposureTimeNs)
                        .append(";iso=").append(cur.sensitivityIso)
                        .append(";ae=").append(cur.aeState)
                        .append(";eis=").append(cur.videoStabilizationMode)
                        .append(";ois=").append(cur.opticalStabilizationMode)
                        .append('"').append('\n');
            }
        }

        double expectedUs = 1_000_000.0 / fps;
        if (!encoder.isEmpty()) {
            long first = encoder.get(0).ptsUs;
            for (int i = 1; i < encoder.size(); i++) {
                MetricCollector.EncoderSample prev = encoder.get(i - 1);
                MetricCollector.EncoderSample cur = encoder.get(i);
                long delta = cur.ptsUs - prev.ptsUs;
                if (delta <= expectedUs * 1.25) continue;
                b.append("encoder,")
                        .append(cur.sequence).append(',')
                        .append((cur.ptsUs - first) / 1_000_000.0).append(',')
                        .append(delta / 1000.0).append(',')
                        .append(missing(delta, expectedUs)).append(',')
                        .append('"')
                        .append("size=").append(cur.sizeBytes)
                        .append(";flags=").append(cur.flags)
                        .append(";dequeueWaitUs=").append(cur.dequeueWaitUs)
                        .append('"').append('\n');
            }
        }

        for (MetricCollector.EventSample e : internalEvents) {
            b.append("internal,")
                    .append(-1).append(',')
                    .append(e.elapsedNs / 1_000_000_000.0).append(',')
                    .append(0).append(',')
                    .append(0).append(',')
                    .append('"')
                    .append(e.source.replace("\"", "'"))
                    .append(":")
                    .append(e.type.replace("\"", "'"))
                    .append(";")
                    .append(e.detail.replace("\"", "'"))
                    .append('"').append('\n');
        }
        return b.toString();
    }

    private static int missing(double delta, double expected) {
        int periods = Math.max(1, (int) Math.round(delta / expected));
        return Math.max(0, periods - 1);
    }

    private static Uri writeSharedFile(Context context, String name, String mime, String body) throws Exception {
        ContentResolver resolver = context.getContentResolver();
        ContentValues values = new ContentValues();
        values.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
        values.put(MediaStore.MediaColumns.MIME_TYPE, mime);
        values.put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/CameraFrameLab/");
        values.put(MediaStore.MediaColumns.IS_PENDING, 1);

        Uri collection = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
        Uri uri = resolver.insert(collection, values);
        if (uri == null) throw new IllegalStateException("Could not create report " + name);

        boolean completed = false;
        try {
            try (OutputStream os = resolver.openOutputStream(uri)) {
                if (os == null) throw new IllegalStateException("Could not open report " + name);
                os.write(body.getBytes(StandardCharsets.UTF_8));
                os.flush();
            }

            ContentValues done = new ContentValues();
            done.put(MediaStore.MediaColumns.IS_PENDING, 0);
            resolver.update(uri, done, null, null);
            completed = true;
            return uri;
        } finally {
            if (!completed) {
                try { resolver.delete(uri, null, null); } catch (Throwable ignored) {}
            }
        }
    }
}
