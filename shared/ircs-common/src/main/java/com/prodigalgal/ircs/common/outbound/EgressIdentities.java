package com.prodigalgal.ircs.common.outbound;

import org.springframework.util.StringUtils;

public final class EgressIdentities {

    private static final String UNKNOWN = "unknown";

    private EgressIdentities() {
    }

    public static String sanitize(String value) {
        if (!StringUtils.hasText(value)) {
            return UNKNOWN;
        }
        String sanitized = value.trim().replaceAll("[^A-Za-z0-9_.-]", "_");
        return sanitized.isBlank() ? UNKNOWN : sanitized;
    }

    public static String resolve(String value, String fallback) {
        return sanitize(isPlaceholder(value) ? fallback : value);
    }

    public static boolean isPlaceholder(String value) {
        return !StringUtils.hasText(value) || UNKNOWN.equalsIgnoreCase(value.trim());
    }
}
