package com.prodigalgal.ircs.task.domain;

import java.util.Locale;

public enum TaskRuntimeStatus {
    QUEUED,
    RUNNING,
    DISCOVERED,
    COMPLETED,
    COMPLETED_WITH_ERRORS,
    FAILED,
    PAUSED,
    STOPPING;

    public String value() {
        return name();
    }

    public static String normalizeValue(String status, TaskRuntimeStatus fallback) {
        if (status == null || status.isBlank()) {
            return fallback.value();
        }
        return status.trim().toUpperCase(Locale.ROOT);
    }

    public static String normalizeHoldStatus(String status) {
        return STOPPING == parse(status) ? STOPPING.value() : PAUSED.value();
    }

    public static boolean isBlockedForDispatch(String status) {
        TaskRuntimeStatus parsed = parse(status);
        return parsed == PAUSED
                || parsed == STOPPING
                || parsed == FAILED
                || parsed == COMPLETED_WITH_ERRORS;
    }

    public static boolean allowsPageExpansion(String status) {
        TaskRuntimeStatus parsed = parse(status);
        return parsed == QUEUED || parsed == RUNNING;
    }

    public static boolean isPageTerminal(String status) {
        TaskRuntimeStatus parsed = parse(status);
        return parsed == COMPLETED || parsed == COMPLETED_WITH_ERRORS;
    }

    public static boolean isCompleted(String status) {
        return parse(status) == COMPLETED;
    }

    public static boolean isErrorLike(String status) {
        TaskRuntimeStatus parsed = parse(status);
        return parsed == FAILED || parsed == COMPLETED_WITH_ERRORS;
    }

    private static TaskRuntimeStatus parse(String status) {
        if (status == null || status.isBlank()) {
            return null;
        }
        try {
            return valueOf(status.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }
}
