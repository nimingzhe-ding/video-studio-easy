package com.videostudio.common;

import java.util.regex.Pattern;

/** Redact credentials before diagnostic text is truncated, persisted or displayed. */
public final class SafeErrors {
    private static final String MASK = "[REDACTED]";
    private static final Pattern AUTH = Pattern.compile("(?i)\\b(Bearer|Basic)\\s+[^\\s\\\"'<>;,}]+" );
    private static final Pattern VALUE = Pattern.compile(
        "(?i)([\\\"']?\\b(?:api[_-]?key|x-api-key|access[_-]?key|secret[_-]?key|client[_-]?secret|"
        + "password|passwd|pwd|token|access[_-]?token|refresh[_-]?token|authorization|signature|"
        + "x-amz-signature|x-amz-credential)\\b[\\\"']?\\s*[:=]\\s*)"
        + "(?:\\\"[^\\\"\\r\\n]*\\\"|'[^'\\r\\n]*'|[^\\s&,;})\\]]+)");
    private static final Pattern KEY = Pattern.compile("\\bsk-[A-Za-z0-9_-]{6,}");
    private static final Pattern USER_INFO = Pattern.compile("(?i)([a-z][a-z0-9+.-]*://)[^/\\s@]+@");

    private SafeErrors() {}

    public static String redact(String text) {
        if (text == null) return "";
        String safe = AUTH.matcher(text).replaceAll("$1 " + MASK);
        safe = VALUE.matcher(safe).replaceAll("$1" + MASK);
        safe = KEY.matcher(safe).replaceAll(MASK);
        return USER_INFO.matcher(safe).replaceAll("$1" + MASK + "@");
    }

    public static String message(String text, int max) {
        String safe = redact(text).replaceAll("[\\s\\p{Cntrl}]+", " ").strip();
        if (max < 1) return "";
        return safe.length() <= max ? safe : safe.substring(0, max - 1) + "…";
    }
}
