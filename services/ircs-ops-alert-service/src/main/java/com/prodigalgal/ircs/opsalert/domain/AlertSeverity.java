package com.prodigalgal.ircs.opsalert.domain;

import java.util.Locale;

public enum AlertSeverity {
    INFO,
    WARNING,
    ERROR,
    CRITICAL;

    public boolean atLeast(AlertSeverity threshold) {
        return ordinal() >= threshold.ordinal();
    }

    public static AlertSeverity max(AlertSeverity left, AlertSeverity right) {
        return left.ordinal() >= right.ordinal() ? left : right;
    }

    public static AlertSeverity parse(String raw, AlertSeverity fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            return AlertSeverity.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            return fallback;
        }
    }
}
