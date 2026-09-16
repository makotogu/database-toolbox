package com.example.dbtoolbox.common;

public final class StringChecks {

    private StringChecks() {
    }

    public static boolean hasText(String value) {
        return value != null && value.trim().length() > 0;
    }

    public static String requireText(String value, String message) {
        if (!hasText(value)) {
            throw new AppException(message);
        }
        return value.trim();
    }
}
