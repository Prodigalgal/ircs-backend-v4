package com.prodigalgal.ircs.common.audit;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

final class RequestAuditSanitizer {

    private static final String REDACTED = "***";

    private RequestAuditSanitizer() {
    }

    static String sanitizeQueryString(String queryString) {
        if (queryString == null || queryString.isBlank()) {
            return queryString;
        }
        String[] parts = queryString.split("&", -1);
        List<String> sanitized = new ArrayList<>(parts.length);
        for (String part : parts) {
            sanitized.add(sanitizeQueryPart(part));
        }
        return String.join("&", sanitized);
    }

    private static String sanitizeQueryPart(String part) {
        if (part == null || part.isEmpty()) {
            return part;
        }
        int separator = part.indexOf('=');
        String key = separator >= 0 ? part.substring(0, separator) : part;
        if (!isSensitiveKey(key)) {
            return part;
        }
        return key + "=" + REDACTED;
    }

    private static boolean isSensitiveKey(String key) {
        if (key == null || key.isBlank()) {
            return false;
        }
        String normalized = decode(key).toLowerCase(Locale.ROOT);
        return normalized.contains("token")
                || normalized.contains("password")
                || normalized.contains("secret")
                || normalized.equals("api_key")
                || normalized.equals("apikey")
                || normalized.equals("authorization")
                || normalized.equals("cookie");
    }

    private static String decode(String value) {
        try {
            return URLDecoder.decode(value, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException ex) {
            return value;
        }
    }
}
