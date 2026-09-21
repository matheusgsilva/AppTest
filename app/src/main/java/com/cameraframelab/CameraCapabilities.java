package com.cameraframelab;

import android.annotation.SuppressLint;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.util.Range;
import android.util.Size;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

public final class CameraCapabilities {
    public final String cameraId;
    public final CameraCharacteristics chars;
    public final List<TestProfile> profiles;
    public final String description;

    private CameraCapabilities(String cameraId, CameraCharacteristics chars, List<TestProfile> profiles, String description) {
        this.cameraId = cameraId;
        this.chars = chars;
        this.profiles = profiles;
        this.description = description;
    }

    @SuppressLint("MissingPermission")
    public static CameraCapabilities inspect(CameraManager manager) throws Exception {
        String selected = null;
        CameraCharacteristics selectedChars = null;
        for (String id : manager.getCameraIdList()) {
            CameraCharacteristics c = manager.getCameraCharacteristics(id);
            Integer facing = c.get(CameraCharacteristics.LENS_FACING);
            if (facing != null && facing == CameraCharacteristics.LENS_FACING_BACK) {
                selected = id;
                selectedChars = c;
                break;
            }
        }
        if (selected == null) throw new IllegalStateException("No back camera found");

        StreamConfigurationMap map = selectedChars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
        if (map == null) throw new IllegalStateException("No stream configuration map");
        Range<Integer>[] aeRanges = selectedChars.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES);
        List<TestProfile> profiles = new ArrayList<>();
        addIfSupported(profiles, map, aeRanges, new Size(1920,1080), 30, true);
        addIfSupported(profiles, map, aeRanges, new Size(1920,1080), 60, true);
        addIfSupported(profiles, map, aeRanges, new Size(1920,1080), 60, false);
        addIfSupported(profiles, map, aeRanges, new Size(3840,2160), 30, true);
        addIfSupported(profiles, map, aeRanges, new Size(3840,2160), 60, true);
        addIfSupported(profiles, map, aeRanges, new Size(3840,2160), 60, false);

        Integer hw = selectedChars.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL);
        Integer ts = selectedChars.get(CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE);
        StringBuilder d = new StringBuilder();
        d.append("cameraId=").append(selected).append('\n');
        d.append("hardwareLevel=").append(hw).append('\n');
        d.append("timestampSource=").append(ts).append('\n');
        d.append("AE FPS ranges=").append(Arrays.toString(aeRanges)).append('\n');
        Size[] hsSizes = map.getHighSpeedVideoSizes();
        Arrays.sort(hsSizes, Comparator.comparingLong(s -> (long) s.getWidth() * s.getHeight()));
        d.append("High-speed modes:\n");
        for (Size s : hsSizes) {
            d.append("  ").append(s).append(" -> ").append(Arrays.toString(map.getHighSpeedVideoFpsRangesFor(s))).append('\n');
        }
        d.append("\nSuite profiles:\n");
        for (TestProfile p : profiles) d.append("  ").append(p).append('\n');
        return new CameraCapabilities(selected, selectedChars, profiles, d.toString());
    }

    private static void addIfSupported(List<TestProfile> out, StreamConfigurationMap map, Range<Integer>[] aeRanges,
                                       Size size, int fps, boolean preview) {
        boolean sizeRegular = false;
        Size[] outputs = map.getOutputSizes(android.media.MediaCodec.class);
        if (outputs != null) for (Size s : outputs) if (s.equals(size)) { sizeRegular = true; break; }
        boolean regularFps = false;
        if (aeRanges != null) for (Range<Integer> r : aeRanges) if (r.getUpper() >= fps && r.contains(fps)) { regularFps = true; break; }
        boolean hs = false;
        try {
            for (Size hsSize : map.getHighSpeedVideoSizes()) {
                if (!hsSize.equals(size)) continue;
                for (Range<Integer> r : map.getHighSpeedVideoFpsRangesFor(size)) {
                    if (r.getUpper() >= fps && r.contains(fps)) { hs = true; break; }
                }
            }
        } catch (IllegalArgumentException ignored) {}
        if (!sizeRegular) return;
        boolean useHighSpeed = fps > 30 && hs;
        if (fps <= 30 || regularFps || hs) {
            String id = String.format(Locale.US, "%dp_%d_%s", size.getHeight(), fps, preview ? "preview" : "nopreview");
            out.add(new TestProfile(id, size, fps, preview, useHighSpeed));
        }
    }
}
