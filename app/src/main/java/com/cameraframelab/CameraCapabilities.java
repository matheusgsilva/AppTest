package com.cameraframelab;

import android.annotation.SuppressLint;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaFormat;
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

        boolean has4k = hasSize(map.getOutputSizes(MediaCodec.class), new Size(3840,2160));
        boolean has1080 = hasSize(map.getOutputSizes(MediaCodec.class), new Size(1920,1080));
        boolean fixed60 = supportsRange(aeRanges, 60, 60);
        boolean flexible60 = supportsContainingRange(aeRanges, 60);
        boolean regular60 = fixed60 || flexible60;
        boolean highSpeed4k60 = supportsHighSpeed(map, new Size(3840,2160), 60);
        boolean highSpeed1080p60 = supportsHighSpeed(map, new Size(1920,1080), 60);

        int[] eisModes = selectedChars.get(CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES);
        int[] oisModes = selectedChars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION);
        boolean eisOn = contains(eisModes, CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON);
        boolean oisOn = contains(oisModes, CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON);
        boolean hevc4k60 = encoderSupports(MediaFormat.MIMETYPE_VIDEO_HEVC, 3840, 2160, 60);
        boolean avc4k60 = encoderSupports(MediaFormat.MIMETYPE_VIDEO_AVC, 3840, 2160, 60);

        List<TestProfile> profiles = new ArrayList<>();

        if (has1080 && (fixed60 || flexible60 || highSpeed1080p60)) {
            TestProfile p = TestProfile.baseline(
                    "1080p_60_control",
                    new Size(1920,1080), 60, true, !regular60 && highSpeed1080p60,
                    28_000_000
            );
            profiles.add(p);
        }

        if (has4k) {
            profiles.add(TestProfile.baseline(
                    "2160p_30_control",
                    new Size(3840,2160), 30, true, false,
                    50_000_000
            ));
        }

        if (has4k && (fixed60 || flexible60 || highSpeed4k60) && avc4k60) {
            TestProfile basePreview = TestProfile.baseline(
                    "2160p_60_baseline_preview",
                    new Size(3840,2160), 60, true, !regular60 && highSpeed4k60,
                    80_000_000
            );
            TestProfile baseNoPreview = TestProfile.baseline(
                    "2160p_60_baseline_nopreview",
                    new Size(3840,2160), 60, false, !regular60 && highSpeed4k60,
                    80_000_000
            );
            profiles.add(basePreview);
            profiles.add(baseNoPreview);

            profiles.add(basePreview.with(
                    "2160p_60_avc50",
                    MediaFormat.MIMETYPE_VIDEO_AVC, 50_000_000,
                    60,60, 0,0, CameraDevice.TEMPLATE_RECORD
            ));

            if (hevc4k60) {
                profiles.add(basePreview.with(
                        "2160p_60_hevc80",
                        MediaFormat.MIMETYPE_VIDEO_HEVC, 80_000_000,
                        60,60, 0,0, CameraDevice.TEMPLATE_RECORD
                ));
                profiles.add(basePreview.with(
                        "2160p_60_hevc50",
                        MediaFormat.MIMETYPE_VIDEO_HEVC, 50_000_000,
                        60,60, 0,0, CameraDevice.TEMPLATE_RECORD
                ));
            }

            if (eisOn) {
                profiles.add(basePreview.with(
                        "2160p_60_eis_on",
                        MediaFormat.MIMETYPE_VIDEO_AVC, 80_000_000,
                        60,60,
                        CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON,
                        0,
                        CameraDevice.TEMPLATE_RECORD
                ));
            }

            if (oisOn) {
                profiles.add(basePreview.with(
                        "2160p_60_ois_on",
                        MediaFormat.MIMETYPE_VIDEO_AVC, 80_000_000,
                        60,60,
                        0,
                        CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON,
                        CameraDevice.TEMPLATE_RECORD
                ));
            }

            if (eisOn && oisOn) {
                profiles.add(basePreview.with(
                        "2160p_60_eis_ois_on",
                        MediaFormat.MIMETYPE_VIDEO_AVC, 80_000_000,
                        60,60,
                        CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON,
                        CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON,
                        CameraDevice.TEMPLATE_RECORD
                ));
            }

            Range<Integer> alt = chooseFlexible60Range(aeRanges);
            if (alt != null && !(alt.getLower() == 60 && alt.getUpper() == 60)) {
                profiles.add(basePreview.with(
                        "2160p_60_ae_" + alt.getLower() + "_" + alt.getUpper(),
                        MediaFormat.MIMETYPE_VIDEO_AVC, 80_000_000,
                        alt.getLower(), alt.getUpper(),
                        0,0,
                        CameraDevice.TEMPLATE_RECORD
                ));
            }

            if (regular60 && highSpeed4k60) {
                profiles.add(new TestProfile(
                        "2160p_60_highspeed_session",
                        new Size(3840,2160), 60, true, true,
                        MediaFormat.MIMETYPE_VIDEO_AVC,
                        80_000_000,
                        60,60,
                        0,0,
                        CameraDevice.TEMPLATE_RECORD
                ));
            }

            profiles.add(basePreview.with(
                    "2160p_60_template_preview",
                    MediaFormat.MIMETYPE_VIDEO_AVC, 80_000_000,
                    60,60, 0,0,
                    CameraDevice.TEMPLATE_PREVIEW
            ));
        }

        Integer hw = selectedChars.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL);
        Integer ts = selectedChars.get(CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE);
        Integer sensorOrientation = selectedChars.get(CameraCharacteristics.SENSOR_ORIENTATION);
        int[] caps = selectedChars.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES);

        StringBuilder d = new StringBuilder();
        d.append("cameraId=").append(selected).append('\n');
        d.append("hardwareLevel=").append(hw).append('\n');
        d.append("timestampSource=").append(ts).append('\n');
        d.append("sensorOrientation=").append(sensorOrientation).append('\n');
        d.append("requestCapabilities=").append(Arrays.toString(caps)).append('\n');
        d.append("AE FPS ranges=").append(Arrays.toString(aeRanges)).append('\n');
        d.append("EIS modes=").append(Arrays.toString(eisModes)).append('\n');
        d.append("OIS modes=").append(Arrays.toString(oisModes)).append('\n');
        d.append("AVC 4K60 encoder=").append(avc4k60).append('\n');
        d.append("HEVC 4K60 encoder=").append(hevc4k60).append('\n');
        d.append("highSpeed4K60=").append(highSpeed4k60).append('\n');

        Size[] hsSizes = map.getHighSpeedVideoSizes();
        Arrays.sort(hsSizes, Comparator.comparingLong(s -> (long) s.getWidth() * s.getHeight()));
        d.append("High-speed modes:\n");
        for (Size s : hsSizes) {
            d.append("  ").append(s).append(" -> ")
                    .append(Arrays.toString(map.getHighSpeedVideoFpsRangesFor(s))).append('\n');
        }

        d.append("\nSuite profiles (").append(profiles.size()).append("):\n");
        for (TestProfile p : profiles) d.append("  ").append(p).append('\n');

        return new CameraCapabilities(selected, selectedChars, profiles, d.toString());
    }

    private static boolean hasSize(Size[] sizes, Size target) {
        if (sizes == null) return false;
        for (Size s : sizes) if (s.equals(target)) return true;
        return false;
    }

    private static boolean supportsRange(Range<Integer>[] ranges, int lower, int upper) {
        if (ranges == null) return false;
        for (Range<Integer> r : ranges) {
            if (r.getLower() == lower && r.getUpper() == upper) return true;
        }
        return false;
    }

    private static boolean supportsContainingRange(Range<Integer>[] ranges, int fps) {
        if (ranges == null) return false;
        for (Range<Integer> r : ranges) if (r.contains(fps)) return true;
        return false;
    }

    private static Range<Integer> chooseFlexible60Range(Range<Integer>[] ranges) {
        if (ranges == null) return null;
        Range<Integer> best = null;
        for (Range<Integer> r : ranges) {
            if (!r.contains(60)) continue;
            if (best == null || r.getLower() < best.getLower()) best = r;
        }
        return best;
    }

    private static boolean supportsHighSpeed(StreamConfigurationMap map, Size size, int fps) {
        try {
            for (Size hsSize : map.getHighSpeedVideoSizes()) {
                if (!hsSize.equals(size)) continue;
                for (Range<Integer> r : map.getHighSpeedVideoFpsRangesFor(size)) {
                    if (r.contains(fps)) return true;
                }
            }
        } catch (Throwable ignored) {}
        return false;
    }

    private static boolean contains(int[] values, int target) {
        if (values == null) return false;
        for (int v : values) if (v == target) return true;
        return false;
    }

    private static boolean encoderSupports(String mime, int width, int height, int fps) {
        try {
            MediaCodecList list = new MediaCodecList(MediaCodecList.REGULAR_CODECS);
            for (MediaCodecInfo info : list.getCodecInfos()) {
                if (!info.isEncoder()) continue;
                for (String type : info.getSupportedTypes()) {
                    if (!type.equalsIgnoreCase(mime)) continue;
                    MediaCodecInfo.CodecCapabilities caps = info.getCapabilitiesForType(type);
                    MediaCodecInfo.VideoCapabilities videoCaps = caps.getVideoCapabilities();
                    if (videoCaps != null &&
                            videoCaps.areSizeAndRateSupported(width, height, fps)) {
                        return true;
                    }
                }
            }
        } catch (Throwable ignored) {}
        return false;
    }
}
