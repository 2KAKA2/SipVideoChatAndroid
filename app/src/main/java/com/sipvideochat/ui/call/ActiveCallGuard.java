package com.sipvideochat.ui.call;

public final class ActiveCallGuard {
    private static volatile boolean active;

    private ActiveCallGuard() {
    }

    public static void markActive() {
        active = true;
    }

    public static void markInactive() {
        active = false;
    }

    public static boolean isActive() {
        return active;
    }
}
