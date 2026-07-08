package com.prodigalgal.ircs.messaging;

public class RabbitTaskHttpStatusException extends RuntimeException {

    private final int statusCode;
    private final String sourceName;
    private final String url;

    public RabbitTaskHttpStatusException(int statusCode, String sourceName, String url) {
        super("HTTP status " + statusCode + " from data source " + safeSourceName(sourceName));
        this.statusCode = statusCode;
        this.sourceName = sourceName;
        this.url = url;
    }

    public int statusCode() {
        return statusCode;
    }

    public String sourceName() {
        return sourceName;
    }

    public String url() {
        return url;
    }

    private static String safeSourceName(String sourceName) {
        if (sourceName == null || sourceName.isBlank()) {
            return "unknown";
        }
        String normalized = sourceName.replaceAll("\\s+", " ").trim();
        return normalized.length() <= 120 ? normalized : normalized.substring(0, 120);
    }
}
