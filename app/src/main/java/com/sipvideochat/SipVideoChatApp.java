package com.sipvideochat;

import android.app.Application;
import android.util.Log;

/**
 * 自定义 Application 类
 * 主要用于全局异常捕获和日志记录
 */
public class SipVideoChatApp extends Application {
    private static final String TAG = "SipVideoChatApp";

    @Override
    public void onCreate() {
        super.onCreate();

        // 全局异常处理器，确保崩溃信息能输出到 logcat
        Thread.UncaughtExceptionHandler defaultHandler = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, throwable) -> {
            Log.e(TAG, "=== 应用崩溃 ===", throwable);
            Log.e(TAG, "线程: " + thread.getName());
            Log.e(TAG, "异常: " + throwable.getClass().getName() + ": " + throwable.getMessage());

            // 打印完整堆栈
            Throwable cause = throwable.getCause();
            while (cause != null) {
                Log.e(TAG, "Caused by: " + cause.getClass().getName() + ": " + cause.getMessage());
                cause = cause.getCause();
            }

            // 交给默认处理器
            if (defaultHandler != null) {
                defaultHandler.uncaughtException(thread, throwable);
            }
        });

        Log.i(TAG, "SipVideoChat Application 已启动");
    }
}
