package com.sipvideochat;

import android.app.Application;

import com.sipvideochat.util.DiagnosticLog;

public class SipVideoChatApp extends Application {
    private static final String TAG = "SipVideoChatApp";

    @Override
    public void onCreate() {
        super.onCreate();
        DiagnosticLog.init(this);

        Thread.UncaughtExceptionHandler defaultHandler = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, throwable) -> {
            DiagnosticLog.e(TAG, "uncaught crash on thread=" + thread.getName(), throwable);
            if (defaultHandler != null) {
                defaultHandler.uncaughtException(thread, throwable);
            }
        });

        DiagnosticLog.i(TAG, "application started, diagnostics=" + DiagnosticLog.getLogPath());
    }
}
