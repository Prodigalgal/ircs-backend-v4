package com.prodigalgal.ircs.magnet;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class MagnetUriUtils {

    private static final Pattern BTIH_PATTERN = Pattern.compile("(?i)(?:xt=urn:btih:|btih:)([a-z0-9]{32,40})");
    private static final Pattern HEX_PATTERN = Pattern.compile("(?i)^[a-f0-9]{40}$");
    private static final Pattern BASE32_PATTERN = Pattern.compile("(?i)^[a-z2-7]{32}$");

    private MagnetUriUtils() {
    }

    static Optional<String> normalizeInfoHash(String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        String trimmed = raw.trim();
        Matcher matcher = BTIH_PATTERN.matcher(trimmed);
        if (matcher.find()) {
            trimmed = matcher.group(1);
        }
        if (!HEX_PATTERN.matcher(trimmed).matches() && !BASE32_PATTERN.matcher(trimmed).matches()) {
            return Optional.empty();
        }
        return Optional.of(trimmed.toUpperCase(Locale.ROOT));
    }

    static String buildMagnetUri(String infoHash, String title) {
        String normalized = normalizeInfoHash(infoHash)
                .orElseThrow(() -> new IllegalArgumentException("Invalid info hash"));
        StringBuilder builder = new StringBuilder("magnet:?xt=urn:btih:").append(normalized);
        if (title != null && !title.isBlank()) {
            builder.append("&dn=").append(encodeQueryValue(title.trim()));
        }
        return builder.toString();
    }

    static String encodeQueryValue(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
