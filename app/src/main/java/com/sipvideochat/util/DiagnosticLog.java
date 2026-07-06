package com.sipvideochat.util;

import android.content.Context;
import android.util.Log;

import androidx.annotation.Nullable;

import java.io.File;
import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class DiagnosticLog {
    private static final String TAG = "DiagnosticLog";
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();
    private static final Object LOCK = new Object();
    private static final SimpleDateFormat FORMAT =
            new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US);

    private static volatile File logFile;

    private DiagnosticLog() {
    }

    public static void init(Context context) {
        synchronized (LOCK) {
            if (logFile != null) {
                return;
            }
            File root = context.getExternalFilesDir(null);
            if (root == null) {
                root = context.getFilesDir();
            }
            File dir = new File(root, "diagnostics");
            if (!dir.exists() && !dir.mkdirs()) {
                Log.w(TAG, "Failed to create diagnostics dir: " + dir.getAbsolutePath());
            }
            logFile = new File(dir, "current.log");
        }
        writeRaw("===== app start ===== path=" + getLogPath());
    }

    public static String getLogPath() {
        File file = logFile;
        return file == null ? "" : file.getAbsolutePath();
    }

    public static void d(String tag, String message) {
        Log.d(tag, message);
        write("D", tag, message, null);
    }

    public static void i(String tag, String message) {
        Log.i(tag, message);
        write("I", tag, message, null);
    }

    public static void w(String tag, String message) {
        Log.w(tag, message);
        write("W", tag, message, null);
    }

    public static void e(String tag, String message) {
        Log.e(tag, message);
        write("E", tag, message, null);
    }

    public static void e(String tag, String message, @Nullable Throwable throwable) {
        Log.e(tag, message, throwable);
        write("E", tag, message, throwable);
    }

    private static void write(String level, String tag, String message, @Nullable Throwable throwable) {
        StringBuilder builder = new StringBuilder()
                .append(FORMAT.format(new Date()))
                .append(' ')
                .append(level)
                .append('/')
                .append(tag)
                .append(": ")
                .append(message);
        if (throwable != null) {
            builder.append('\n').append(stackTrace(throwable));
        }
        writeRaw(builder.toString());
    }

    private static void writeRaw(String line) {
        EXECUTOR.execute(() -> {
            File file = logFile;
            if (file == null) {
                return;
            }
            try (FileOutputStream out = new FileOutputStream(file, true)) {
                out.write((line + "\n").getBytes(StandardCharsets.UTF_8));
                out.flush();
            } catch (Exception e) {
                Log.e(TAG, "Failed to write diagnostic log", e);
            }
        });
    }

    private static String stackTrace(Throwable throwable) {
        StringWriter stringWriter = new StringWriter();
        PrintWriter printWriter = new PrintWriter(stringWriter);
        throwable.printStackTrace(printWriter);
        printWriter.flush();
        return stringWriter.toString();
    }
}
