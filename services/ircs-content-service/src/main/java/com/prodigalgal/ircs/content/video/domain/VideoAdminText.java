package com.prodigalgal.ircs.content.video.domain;


import com.prodigalgal.ircs.content.video.api.ContentApiException;
import com.prodigalgal.ircs.common.security.IrcsPermissions;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedHashSet;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

public final class VideoAdminText {

    private VideoAdminText() {
    }

    public static String normalizeYear(String year) {
        if (!StringUtils.hasText(year)) {
            return null;
        }
        String trimmed = year.trim();
        return trimmed.length() > 20 ? trimmed.substring(0, 20) : trimmed;
    }

    public static String normalizeExternalId(String id, int maxLength) {
        if (!StringUtils.hasText(id)) {
            return null;
        }
        String trimmed = id.trim();
        if ("0".equals(trimmed) || "0.0".equals(trimmed) || "null".equalsIgnoreCase(trimmed)) {
            return null;
        }
        return trimToLength(trimmed, maxLength);
    }

    public static Set<String> normalizeTags(Set<String> tags) {
        if (CollectionUtils.isEmpty(tags)) {
            return Set.of();
        }
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String tag : tags) {
            String value = cleanText(tag, 128);
            if (StringUtils.hasText(value)) {
                normalized.add(value);
            }
        }
        return normalized;
    }

    public static String cleanText(String value, int maxLength) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        String cleaned = value.replace("\u0000", "").trim();
        return trimToLength(cleaned, maxLength);
    }

    public static String trimToLength(String value, int maxLength) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.length() > maxLength ? trimmed.substring(0, maxLength) : trimmed;
    }

    public static String domainOf(String url) {
        try {
            URI uri = URI.create(url);
            if (StringUtils.hasText(uri.getHost())) {
                String scheme = StringUtils.hasText(uri.getScheme()) ? uri.getScheme() : "https";
                return scheme + "://" + uri.getHost();
            }
        } catch (IllegalArgumentException ignored) {
        }
        return "external";
    }

    public static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder();
            for (byte b : hash) {
                builder.append(String.format("%02x", b));
            }
            return builder.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    public static String normalizeContentVisibility(String value) {
        if (!StringUtils.hasText(value)) {
            return IrcsPermissions.VISIBILITY_PUBLIC;
        }
        String normalized = value.trim().toUpperCase(java.util.Locale.ROOT);
        if (IrcsPermissions.VISIBILITY_PUBLIC.equals(normalized)
                || IrcsPermissions.VISIBILITY_MEMBER.equals(normalized)
                || IrcsPermissions.VISIBILITY_ADMIN.equals(normalized)
                || IrcsPermissions.VISIBILITY_DRAFT.equals(normalized)
                || IrcsPermissions.VISIBILITY_HIDDEN.equals(normalized)) {
            return normalized;
        }
        throw new ContentApiException(HttpStatus.BAD_REQUEST, "Invalid contentVisibility: " + value);
    }
}
