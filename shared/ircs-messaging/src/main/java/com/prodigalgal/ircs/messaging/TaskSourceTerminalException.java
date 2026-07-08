package com.prodigalgal.ircs.messaging;

public class TaskSourceTerminalException extends RuntimeException {

    private final String sourceName;
    private final String reason;

    public TaskSourceTerminalException(String sourceName, String reason, Throwable cause) {
        super("Source terminal failure from data source " + safeSourceName(sourceName) + ": " + safeReason(reason), cause);
        this.sourceName = sourceName;
        this.reason = reason;
    }

    public String sourceName() {
        return sourceName;
    }

    public String reason() {
        return reason;
    }

    private static String safeSourceName(String sourceName) {
        if (sourceName == null || sourceName.isBlank()) {
            return "unknown";
        }
        String normalized = sourceName.replaceAll("\\s+", " ").trim();
        return normalized.length() <= 120 ? normalized : normalized.substring(0, 120);
    }

    private static String safeReason(String reason) {
        if (reason == null || reason.isBlank()) {
            return "source response cannot be processed";
        }
        String normalized = reason.replaceAll("\\s+", " ").trim();
        return normalized.length() <= 240 ? normalized : normalized.substring(0, 240);
    }
}
