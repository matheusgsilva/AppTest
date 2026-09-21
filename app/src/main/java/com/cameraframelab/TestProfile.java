package com.cameraframelab;

import android.hardware.camera2.CameraDevice;
import android.media.MediaFormat;
import android.util.Size;

public final class TestProfile {
    public final String id;
    public final Size size;
    public final int fps;
    public final boolean preview;
    public final boolean highSpeed;

    public final String codecMime;
    public final int bitrate;
    public final int aeLowerFps;
    public final int aeUpperFps;
    public final int videoStabilizationMode;
    public final int opticalStabilizationMode;
    public final int cameraTemplate;

    public TestProfile(
            String id,
            Size size,
            int fps,
            boolean preview,
            boolean highSpeed,
            String codecMime,
            int bitrate,
            int aeLowerFps,
            int aeUpperFps,
            int videoStabilizationMode,
            int opticalStabilizationMode,
            int cameraTemplate
    ) {
        this.id = id;
        this.size = size;
        this.fps = fps;
        this.preview = preview;
        this.highSpeed = highSpeed;
        this.codecMime = codecMime;
        this.bitrate = bitrate;
        this.aeLowerFps = aeLowerFps;
        this.aeUpperFps = aeUpperFps;
        this.videoStabilizationMode = videoStabilizationMode;
        this.opticalStabilizationMode = opticalStabilizationMode;
        this.cameraTemplate = cameraTemplate;
    }

    public static TestProfile baseline(String id, Size size, int fps, boolean preview, boolean highSpeed, int bitrate) {
        return new TestProfile(
                id, size, fps, preview, highSpeed,
                MediaFormat.MIMETYPE_VIDEO_AVC,
                bitrate,
                fps, fps,
                0,
                0,
                CameraDevice.TEMPLATE_RECORD
        );
    }

    public TestProfile with(
            String newId,
            String codecMime,
            int bitrate,
            int aeLower,
            int aeUpper,
            int eis,
            int ois,
            int template
    ) {
        return new TestProfile(
                newId, size, fps, preview, highSpeed,
                codecMime, bitrate, aeLower, aeUpper,
                eis, ois, template
        );
    }

    public String codecLabel() {
        if (MediaFormat.MIMETYPE_VIDEO_HEVC.equals(codecMime)) return "HEVC";
        if (MediaFormat.MIMETYPE_VIDEO_AVC.equals(codecMime)) return "AVC";
        return codecMime;
    }

    public String templateLabel() {
        if (cameraTemplate == CameraDevice.TEMPLATE_PREVIEW) return "PREVIEW";
        if (cameraTemplate == CameraDevice.TEMPLATE_VIDEO_SNAPSHOT) return "VIDEO_SNAPSHOT";
        return "RECORD";
    }

    @Override public String toString() {
        return id +
                " · " + size.getWidth() + "x" + size.getHeight() +
                " @ " + fps + "fps" +
                " · " + codecLabel() +
                " " + (bitrate / 1_000_000) + "Mbps" +
                " · AE[" + aeLowerFps + "," + aeUpperFps + "]" +
                " · preview " + (preview ? "ON" : "OFF") +
                " · EIS " + videoStabilizationMode +
                " · OIS " + opticalStabilizationMode +
                " · " + templateLabel() +
                (highSpeed ? " · HIGH_SPEED" : "");
    }
}
