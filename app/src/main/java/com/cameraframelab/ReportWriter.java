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
        public final IntervalAnalyzer.Stats cameraStats;
        public final IntervalAnalyzer.Stats encoderStats;
        public final int repeatedEncoderPts;
        ReportResult(Uri summaryUri, Uri cameraCsvUri, Uri encoderCsvUri, Uri thermalCsvUri,
                     IntervalAnalyzer.Stats cameraStats, IntervalAnalyzer.Stats encoderStats, int repeatedEncoderPts) {
            this.summaryUri = summaryUri;
            this.cameraCsvUri = cameraCsvUri;
            this.encoderCsvUri = encoderCsvUri;
            this.thermalCsvUri = thermalCsvUri;
            this.cameraStats = cameraStats;
            this.encoderStats = encoderStats;
            this.repeatedEncoderPts = repeatedEncoderPts;
        }
    }

    private ReportWriter() {}

    public static ReportResult write(Context context, CameraRecorder.SessionResult session, TestProfile profile,
                                     CameraCapabilities caps) throws Exception {
        List<MetricCollector.CameraSample> camera = session.metrics.cameraSnapshot();
        List<MetricCollector.EncoderSample> encoder = session.metrics.encoderSnapshot();
        List<MetricCollector.ThermalSample> thermal = session.metrics.thermalSnapshot();

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
        root.put("schemaVersion", 1);
        root.put("baseName", session.baseName);
        root.put("videoUri", String.valueOf(session.videoUri));
        root.put("profile", profileJson(profile));
        root.put("durationSeconds", (session.endElapsedNs - session.startElapsedNs) / 1_000_000_000.0);
        root.put("device", deviceJson());
        root.put("cameraCapabilities", caps.description);
        root.put("cameraStats", statsJson(cameraStats));
        root.put("encoderStats", statsJson(encoderStats));
        root.put("repeatedEncoderPts", repeatedPts);
        root.put("cameraSampleCount", camera.size());
        root.put("encoderSampleCount", encoder.size());
        root.put("thermalSamples", thermalJson(thermal));
        root.put("diagnosisHint", diagnosis(cameraStats, encoderStats));

        Uri summary = writeSharedFile(context, session.baseName + "_summary.json", "application/json", root.toString(2));
        Uri cameraCsv = writeSharedFile(context, session.baseName + "_camera.csv", "text/csv", cameraCsv(camera));
        Uri encoderCsv = writeSharedFile(context, session.baseName + "_encoder.csv", "text/csv", encoderCsv(encoder));
        Uri thermalCsv = writeSharedFile(context, session.baseName + "_thermal.csv", "text/csv", thermalCsv(thermal));
        return new ReportResult(summary, cameraCsv, encoderCsv, thermalCsv, cameraStats, encoderStats, repeatedPts);
    }

    private static JSONObject profileJson(TestProfile p) throws Exception {
        JSONObject o = new JSONObject();
        o.put("id", p.id); o.put("width", p.size.getWidth()); o.put("height", p.size.getHeight());
        o.put("fps", p.fps); o.put("preview", p.preview); o.put("highSpeed", p.highSpeed); return o;
    }

    private static JSONObject deviceJson() throws Exception {
        JSONObject o = new JSONObject();
        o.put("manufacturer", Build.MANUFACTURER); o.put("brand", Build.BRAND); o.put("model", Build.MODEL);
        o.put("device", Build.DEVICE); o.put("hardware", Build.HARDWARE); o.put("sdk", Build.VERSION.SDK_INT);
        o.put("release", Build.VERSION.RELEASE); o.put("display", Build.DISPLAY); return o;
    }

    private static JSONObject statsJson(IntervalAnalyzer.Stats s) throws Exception {
        JSONObject o = new JSONObject();
        o.put("samples", s.samples); o.put("effectiveFps", s.effectiveFps); o.put("medianMs", s.medianMs);
        o.put("p95Ms", s.p95Ms); o.put("p99Ms", s.p99Ms); o.put("maxMs", s.maxMs);
        o.put("gapCount", s.gapCount); o.put("estimatedMissingFrames", s.estimatedMissingFrames); return o;
    }

    private static JSONArray thermalJson(List<MetricCollector.ThermalSample> samples) throws Exception {
        JSONArray a = new JSONArray();
        for (MetricCollector.ThermalSample s : samples) {
            JSONObject o = new JSONObject(); o.put("elapsedNs", s.elapsedNs); o.put("thermalStatus", s.thermalStatus);
            o.put("batteryTempC", s.batteryTempC); a.put(o);
        }
        return a;
    }

    private static String diagnosis(IntervalAnalyzer.Stats camera, IntervalAnalyzer.Stats encoder) {
        if (camera.gapCount > 0) return "CAMERA_SENSOR_CADENCE_GAPS";
        if (encoder.gapCount > 0) return "ENCODER_ONLY_GAPS";
        return "NO_TIMESTAMP_GAPS_DETECTED";
    }

    private static String cameraCsv(List<MetricCollector.CameraSample> list) {
        StringBuilder b = new StringBuilder("sequence,frameNumber,sensorTimestampNs,deltaSensorNs,callbackElapsedNs,deltaCallbackNs\n");
        long prevTs = 0, prevCb = 0;
        for (MetricCollector.CameraSample s : list) {
            b.append(s.sequence).append(',').append(s.frameNumber).append(',').append(s.sensorTimestampNs).append(',')
                    .append(prevTs == 0 ? 0 : s.sensorTimestampNs-prevTs).append(',').append(s.callbackElapsedNs).append(',')
                    .append(prevCb == 0 ? 0 : s.callbackElapsedNs-prevCb).append('\n');
            prevTs=s.sensorTimestampNs; prevCb=s.callbackElapsedNs;
        }
        return b.toString();
    }

    private static String encoderCsv(List<MetricCollector.EncoderSample> list) {
        StringBuilder b = new StringBuilder("sequence,ptsUs,deltaPtsUs,sizeBytes,flags,callbackElapsedNs,deltaCallbackNs\n");
        long prevPts = 0, prevCb = 0;
        for (MetricCollector.EncoderSample s : list) {
            b.append(s.sequence).append(',').append(s.ptsUs).append(',').append(prevPts == 0 ? 0 : s.ptsUs-prevPts).append(',')
                    .append(s.sizeBytes).append(',').append(s.flags).append(',').append(s.callbackElapsedNs).append(',')
                    .append(prevCb == 0 ? 0 : s.callbackElapsedNs-prevCb).append('\n');
            prevPts=s.ptsUs; prevCb=s.callbackElapsedNs;
        }
        return b.toString();
    }

    private static String thermalCsv(List<MetricCollector.ThermalSample> list) {
        StringBuilder b = new StringBuilder("elapsedNs,thermalStatus,batteryTempC\n");
        for (MetricCollector.ThermalSample s : list) b.append(s.elapsedNs).append(',').append(s.thermalStatus).append(',').append(String.format(Locale.US,"%.1f",s.batteryTempC)).append('\n');
        return b.toString();
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
