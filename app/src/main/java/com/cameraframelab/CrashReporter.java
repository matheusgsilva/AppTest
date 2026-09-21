package com.cameraframelab;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.provider.MediaStore;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public final class CrashReporter {
    private CrashReporter() {}

    public static void install(Context context) {
        final Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> {
            try {
                String report = buildReport(thread, error);
                writeInternal(context, report);
                writeDcim(context, report);
            } catch (Throwable ignored) {}
            if (previous != null) previous.uncaughtException(thread, error);
        });
    }

    public static String readLastCrash(Context context) {
        try {
            File f = new File(context.getFilesDir(), "last_crash.txt");
            if (!f.exists()) return null;
            byte[] bytes = java.nio.file.Files.readAllBytes(f.toPath());
            return new String(bytes, StandardCharsets.UTF_8);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String buildReport(Thread thread, Throwable error) {
        StringBuilder b = new StringBuilder();
        b.append("CameraFrameLab crash\n");
        b.append("time=").append(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(new Date())).append('\n');
        b.append("thread=").append(thread == null ? "null" : thread.getName()).append('\n');
        b.append("device=").append(android.os.Build.MANUFACTURER).append(' ').append(android.os.Build.MODEL).append('\n');
        b.append("android=").append(android.os.Build.VERSION.RELEASE).append(" sdk=").append(android.os.Build.VERSION.SDK_INT).append("\n\n");
        b.append(android.util.Log.getStackTraceString(error));
        return b.toString();
    }

    private static void writeInternal(Context context, String report) throws Exception {
        File f = new File(context.getFilesDir(), "last_crash.txt");
        try (FileOutputStream out = new FileOutputStream(f, false)) {
            out.write(report.getBytes(StandardCharsets.UTF_8));
        }
    }

    private static void writeDcim(Context context, String report) throws Exception {
        String name = "crash_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date()) + ".txt";
        ContentResolver resolver = context.getContentResolver();
        ContentValues values = new ContentValues();
        values.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
        values.put(MediaStore.MediaColumns.MIME_TYPE, "text/plain");
        values.put(MediaStore.MediaColumns.RELATIVE_PATH, "DCIM/CameraFrameLab");
        values.put(MediaStore.MediaColumns.IS_PENDING, 1);
        Uri uri = resolver.insert(MediaStore.Files.getContentUri("external"), values);
        if (uri == null) return;
        try (OutputStream os = resolver.openOutputStream(uri, "w")) {
            if (os != null) os.write(report.getBytes(StandardCharsets.UTF_8));
        }
        ContentValues done = new ContentValues();
        done.put(MediaStore.MediaColumns.IS_PENDING, 0);
        resolver.update(uri, done, null, null);
    }
}
