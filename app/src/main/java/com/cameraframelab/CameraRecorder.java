package com.cameraframelab;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraConstrainedHighSpeedCaptureSession;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.TotalCaptureResult;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.net.Uri;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.ParcelFileDescriptor;
import android.provider.MediaStore;
import android.util.Range;
import android.view.Surface;
import android.view.TextureView;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public final class CameraRecorder {
    public interface Listener {
        void onStarted(String baseName, Uri videoUri);
        void onStatus(String text);
        void onStopped(SessionResult result);
        void onError(Throwable error);
    }

    public static final class SessionResult {
        public final String baseName;
        public final Uri videoUri;
        public final MetricCollector metrics;
        public final long startElapsedNs;
        public final long endElapsedNs;
        public final String encoderName;
        public final String encoderOutputFormat;
        public final String requestDescription;
        SessionResult(String baseName, Uri videoUri, MetricCollector metrics, long startElapsedNs, long endElapsedNs,
                      String encoderName, String encoderOutputFormat, String requestDescription) {
            this.baseName = baseName;
            this.videoUri = videoUri;
            this.metrics = metrics;
            this.startElapsedNs = startElapsedNs;
            this.endElapsedNs = endElapsedNs;
            this.encoderName = encoderName;
            this.encoderOutputFormat = encoderOutputFormat;
            this.requestDescription = requestDescription;
        }
    }

    private final Activity activity;
    private final CameraManager manager;
    private final TextureView textureView;
    private final Handler mainHandler;
    private final Listener listener;

    private HandlerThread cameraThread;
    private Handler cameraHandler;
    private CameraDevice camera;
    private CameraCaptureSession session;
    private MediaCodec codec;
    private Surface codecSurface;
    private Surface previewSurface;
    private MediaMuxer muxer;
    private ParcelFileDescriptor outputPfd;
    private Uri outputUri;
    private Thread drainThread;
    private final AtomicBoolean drainRunning = new AtomicBoolean(false);
    private final AtomicBoolean eosRequested = new AtomicBoolean(false);
    private CountDownLatch encoderDone;
    private MetricCollector collector;
    private TestProfile profile;
    private String baseName;
    private long startElapsedNs;
    private boolean muxerStarted;
    private int videoTrack = -1;
    private String encoderName = "";
    private String encoderOutputFormat = "";
    private String requestDescription = "";

    public CameraRecorder(Activity activity, CameraManager manager, TextureView textureView, Handler mainHandler, Listener listener) {
        this.activity = activity;
        this.manager = manager;
        this.textureView = textureView;
        this.mainHandler = mainHandler;
        this.listener = listener;
    }

    public boolean isRunning() { return camera != null || codec != null; }

    public void addThermalSample(int status, float batteryTempC, long availableMemoryBytes, int appPssKb) {
        MetricCollector c = collector;
        if (c != null) c.addThermal(status, batteryTempC, availableMemoryBytes, appPssKb);
    }

    @SuppressLint("MissingPermission")
    public void start(CameraCapabilities caps, TestProfile profile) {
        if (isRunning()) throw new IllegalStateException("Recorder already running");
        this.profile = profile;
        this.collector = new MetricCollector();
        this.encoderDone = new CountDownLatch(1);
        this.eosRequested.set(false);
        this.muxerStarted = false;
        this.videoTrack = -1;
        this.startElapsedNs = android.os.SystemClock.elapsedRealtimeNanos();
        this.baseName = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date()) + "_" + profile.id;
        try {
            prepareOutput();
            prepareEncoder();
            cameraThread = new HandlerThread("CameraFrameLab-Camera");
            cameraThread.start();
            cameraHandler = new Handler(cameraThread.getLooper());
            manager.openCamera(caps.cameraId, cameraStateCallback, cameraHandler);
        } catch (Throwable t) {
            fail(t);
        }
    }

    public void stop() {
        new Thread(() -> {
            try {
                CameraCaptureSession s = session;
                if (s != null) {
                    try { s.stopRepeating(); } catch (Throwable ignored) {}
                    try { s.abortCaptures(); } catch (Throwable ignored) {}
                    try { s.close(); } catch (Throwable ignored) {}
                    session = null;
                }
                CameraDevice c = camera;
                if (c != null) {
                    try { c.close(); } catch (Throwable ignored) {}
                    camera = null;
                }
                if (codec != null) {
                    try { codec.signalEndOfInputStream(); } catch (Throwable ignored) {}
                    eosRequested.set(true);
                    if (encoderDone != null) encoderDone.await(6, TimeUnit.SECONDS);
                }
                finishResources(false);
                long end = android.os.SystemClock.elapsedRealtimeNanos();
                SessionResult result = new SessionResult(
                        baseName, outputUri, collector, startElapsedNs, end,
                        encoderName, encoderOutputFormat, requestDescription
                );
                mainHandler.post(() -> listener.onStopped(result));
            } catch (Throwable t) {
                fail(t);
            }
        }, "CameraFrameLab-Stop").start();
    }

    private void prepareOutput() throws IOException {
        ContentResolver resolver = activity.getContentResolver();
        ContentValues values = new ContentValues();
        values.put(MediaStore.Video.Media.DISPLAY_NAME, baseName + ".mp4");
        values.put(MediaStore.Video.Media.MIME_TYPE, "video/mp4");
        values.put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/CameraFrameLab");
        values.put(MediaStore.MediaColumns.IS_PENDING, 1);
        outputUri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
        if (outputUri == null) throw new IOException("Could not create MediaStore video");
        outputPfd = resolver.openFileDescriptor(outputUri, "rw");
        if (outputPfd == null) throw new IOException("Could not open video file descriptor");
        muxer = new MediaMuxer(outputPfd.getFileDescriptor(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
    }

    private void prepareEncoder() throws IOException {
        int w = profile.size.getWidth();
        int h = profile.size.getHeight();
        String mime = profile.codecMime;
        MediaFormat format = MediaFormat.createVideoFormat(mime, w, h);
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
        format.setInteger(MediaFormat.KEY_FRAME_RATE, profile.fps);
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);
        format.setInteger(MediaFormat.KEY_BIT_RATE, profile.bitrate);
        format.setInteger(MediaFormat.KEY_PRIORITY, 0);
        format.setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0);
        format.setFloat(MediaFormat.KEY_OPERATING_RATE, profile.fps);
        codec = MediaCodec.createEncoderByType(mime);
        encoderName = codec.getName();
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        codecSurface = codec.createInputSurface();
        codec.start();
        drainRunning.set(true);
        drainThread = new Thread(this::drainEncoder, "CameraFrameLab-Encoder");
        drainThread.start();
    }

    private int bitrateFor(int w, int h, int fps) {
        long pixels = (long) w * h;
        if (pixels >= 3840L * 2160L) return fps >= 60 ? 80_000_000 : 50_000_000;
        return fps >= 60 ? 28_000_000 : 18_000_000;
    }

    private final CameraDevice.StateCallback cameraStateCallback = new CameraDevice.StateCallback() {
        @Override public void onOpened(CameraDevice cameraDevice) {
            camera = cameraDevice;
            try { createSession(); } catch (Throwable t) { fail(t); }
        }
        @Override public void onDisconnected(CameraDevice cameraDevice) {
            cameraDevice.close();
            fail(new CameraAccessException(CameraAccessException.CAMERA_DISCONNECTED));
        }
        @Override public void onError(CameraDevice cameraDevice, int error) {
            cameraDevice.close();
            fail(new RuntimeException("CameraDevice error=" + error));
        }
    };

    private void createSession() throws CameraAccessException {
        List<Surface> outputs = new ArrayList<>();
        outputs.add(codecSurface);
        if (profile.preview) {
            SurfaceTexture st = textureView.getSurfaceTexture();
            if (st == null) throw new IllegalStateException("Preview SurfaceTexture is not ready");
            st.setDefaultBufferSize(profile.size.getWidth(), profile.size.getHeight());
            previewSurface = new Surface(st);
            outputs.add(previewSurface);
        }
        if (profile.highSpeed) {
            camera.createConstrainedHighSpeedCaptureSession(outputs, sessionStateCallback, cameraHandler);
        } else {
            camera.createCaptureSession(outputs, sessionStateCallback, cameraHandler);
        }
    }

    private final CameraCaptureSession.StateCallback sessionStateCallback = new CameraCaptureSession.StateCallback() {
        @Override public void onConfigured(CameraCaptureSession s) {
            session = s;
            try {
                CaptureRequest.Builder b = camera.createCaptureRequest(profile.cameraTemplate);
                b.addTarget(codecSurface);
                if (profile.preview && previewSurface != null) b.addTarget(previewSurface);
                b.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, new Range<>(profile.aeLowerFps, profile.aeUpperFps));
                try { b.set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, profile.videoStabilizationMode); } catch (Throwable ignored) {}
                try { b.set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, profile.opticalStabilizationMode); } catch (Throwable ignored) {}
                CaptureRequest request = b.build();
                requestDescription =
                        "template=" + profile.templateLabel() +
                        "; ae=[" + profile.aeLowerFps + "," + profile.aeUpperFps + "]" +
                        "; eis=" + profile.videoStabilizationMode +
                        "; ois=" + profile.opticalStabilizationMode +
                        "; preview=" + profile.preview +
                        "; highSpeed=" + profile.highSpeed +
                        "; codec=" + profile.codecLabel() +
                        "; bitrate=" + profile.bitrate;
                if (profile.highSpeed && s instanceof CameraConstrainedHighSpeedCaptureSession) {
                    List<CaptureRequest> burst = ((CameraConstrainedHighSpeedCaptureSession) s).createHighSpeedRequestList(request);
                    ((CameraConstrainedHighSpeedCaptureSession) s).setRepeatingBurst(burst, captureCallback, cameraHandler);
                } else {
                    s.setRepeatingRequest(request, captureCallback, cameraHandler);
                }
                mainHandler.post(() -> {
                    listener.onStarted(baseName, outputUri);
                    listener.onStatus("Recording " + profile);
                });
            } catch (Throwable t) { fail(t); }
        }
        @Override public void onConfigureFailed(CameraCaptureSession s) {
            fail(new RuntimeException("CameraCaptureSession configuration failed"));
        }
    };

    private final CameraCaptureSession.CaptureCallback captureCallback = new CameraCaptureSession.CaptureCallback() {
        @Override public void onCaptureCompleted(CameraCaptureSession session, CaptureRequest request, TotalCaptureResult result) {
            Long sensorTs = result.get(TotalCaptureResult.SENSOR_TIMESTAMP);
            if (sensorTs == null || collector == null) return;

            Long exposure = result.get(TotalCaptureResult.SENSOR_EXPOSURE_TIME);
            Long frameDuration = result.get(TotalCaptureResult.SENSOR_FRAME_DURATION);
            Integer iso = result.get(TotalCaptureResult.SENSOR_SENSITIVITY);
            Integer ae = result.get(TotalCaptureResult.CONTROL_AE_STATE);
            Integer af = result.get(TotalCaptureResult.CONTROL_AF_STATE);
            Integer awb = result.get(TotalCaptureResult.CONTROL_AWB_STATE);
            Integer eis = result.get(TotalCaptureResult.CONTROL_VIDEO_STABILIZATION_MODE);
            Integer ois = result.get(TotalCaptureResult.LENS_OPTICAL_STABILIZATION_MODE);
            Float focal = result.get(TotalCaptureResult.LENS_FOCAL_LENGTH);

            collector.addCamera(
                    result.getFrameNumber(),
                    sensorTs,
                    exposure == null ? -1L : exposure,
                    frameDuration == null ? -1L : frameDuration,
                    iso == null ? -1 : iso,
                    ae == null ? -1 : ae,
                    af == null ? -1 : af,
                    awb == null ? -1 : awb,
                    eis == null ? -1 : eis,
                    ois == null ? -1 : ois,
                    focal == null ? Float.NaN : focal
            );
        }
    };

    private void drainEncoder() {
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        try {
            while (drainRunning.get()) {
                long dequeueStartNs = android.os.SystemClock.elapsedRealtimeNanos();
                int index = codec.dequeueOutputBuffer(info, 20_000);
                long dequeueWaitUs = (android.os.SystemClock.elapsedRealtimeNanos() - dequeueStartNs) / 1000L;
                if (index == MediaCodec.INFO_TRY_AGAIN_LATER) {
                    if (eosRequested.get()) continue;
                } else if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    if (muxerStarted) throw new IllegalStateException("Encoder format changed twice");
                    encoderOutputFormat = String.valueOf(codec.getOutputFormat());
                    videoTrack = muxer.addTrack(codec.getOutputFormat());
                    muxer.start();
                    muxerStarted = true;
                } else if (index >= 0) {
                    ByteBuffer data = codec.getOutputBuffer(index);
                    if (data == null) throw new IllegalStateException("Null encoder buffer");
                    if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) info.size = 0;
                    if (info.size > 0) {
                        if (!muxerStarted) throw new IllegalStateException("Muxer not started");
                        data.position(info.offset);
                        data.limit(info.offset + info.size);
                        muxer.writeSampleData(videoTrack, data, info);
                        if (collector != null) collector.addEncoder(info.presentationTimeUs, info.size, info.flags, dequeueWaitUs);
                    }
                    boolean eos = (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                    codec.releaseOutputBuffer(index, false);
                    if (eos) break;
                }
            }
        } catch (Throwable t) {
            mainHandler.post(() -> listener.onStatus("Encoder error: " + t.getMessage()));
        } finally {
            drainRunning.set(false);
            if (encoderDone != null) encoderDone.countDown();
        }
    }

    private synchronized void finishResources(boolean deleteVideo) {
        drainRunning.set(false);
        if (codec != null) {
            try { codec.stop(); } catch (Throwable ignored) {}
            try { codec.release(); } catch (Throwable ignored) {}
            codec = null;
        }
        if (codecSurface != null) { try { codecSurface.release(); } catch (Throwable ignored) {} codecSurface = null; }
        if (previewSurface != null) { try { previewSurface.release(); } catch (Throwable ignored) {} previewSurface = null; }
        if (muxer != null) {
            if (muxerStarted) try { muxer.stop(); } catch (Throwable ignored) {}
            try { muxer.release(); } catch (Throwable ignored) {}
            muxer = null;
        }
        if (outputPfd != null) { try { outputPfd.close(); } catch (Throwable ignored) {} outputPfd = null; }
        if (cameraThread != null) {
            try { cameraThread.quitSafely(); } catch (Throwable ignored) {}
            cameraThread = null;
            cameraHandler = null;
        }
        if (outputUri != null) {
            ContentResolver resolver = activity.getContentResolver();
            if (deleteVideo) {
                try { resolver.delete(outputUri, null, null); } catch (Throwable ignored) {}
            } else {
                ContentValues values = new ContentValues();
                values.put(MediaStore.Video.Media.IS_PENDING, 0);
                try { resolver.update(outputUri, values, null, null); } catch (Throwable ignored) {}
            }
        }
    }

    private void fail(Throwable t) {
        try {
            if (session != null) session.close();
            if (camera != null) camera.close();
        } catch (Throwable ignored) {}
        session = null;
        camera = null;
        finishResources(true);
        mainHandler.post(() -> listener.onError(t));
    }
}
