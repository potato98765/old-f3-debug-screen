package com.mrpotato.oldf3debugscreen.client.debug;

import java.util.List;

public final class DebugFeedbackOverlay {

    private static final long DISPLAY_TIME_MS = 3000L;

    private static volatile String message;
    private static volatile long expiresAt;

    private DebugFeedbackOverlay() {}

    public static void push(String text) {
        if (text == null || text.isBlank()) {
            return;
        }

        message = text;
        expiresAt = System.currentTimeMillis() + DISPLAY_TIME_MS;
    }

    public static List<String> getActiveMessages() {
        String current = message;
        if (current == null) {
            return List.of();
        }

        if (System.currentTimeMillis() > expiresAt) {
            message = null;
            expiresAt = 0L;
            return List.of();
        }

        return List.of(current);
    }
}
