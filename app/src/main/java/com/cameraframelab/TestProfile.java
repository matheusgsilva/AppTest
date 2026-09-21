package com.cameraframelab;

import android.util.Size;

public final class TestProfile {
    public final String id;
    public final Size size;
    public final int fps;
    public final boolean preview;
    public final boolean highSpeed;

    public TestProfile(String id, Size size, int fps, boolean preview, boolean highSpeed) {
        this.id = id;
        this.size = size;
        this.fps = fps;
        this.preview = preview;
        this.highSpeed = highSpeed;
    }

    @Override public String toString() {
        return id + " · " + size.getWidth() + "x" + size.getHeight() + " @ " + fps + "fps · preview " + (preview ? "ON" : "OFF") + (highSpeed ? " · HIGH_SPEED" : "");
    }
}
