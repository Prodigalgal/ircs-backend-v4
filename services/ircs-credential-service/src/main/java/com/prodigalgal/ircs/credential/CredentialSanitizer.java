package com.prodigalgal.ircs.credential;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class CredentialSanitizer {

    private static final int FINGERPRINT_SUFFIX_LENGTH = 8;

    private final ObjectMapper objectMapper;

    public CredentialSummary toSummary(CredentialRecord record) {
        Map<String, Object> payload = payload(record.payloadJson());
        return new CredentialSummary(
                record.id(),
                record.createdAt(),
                record.updatedAt(),
                record.revision(),
                record.provider(),
                record.name(),
                fingerprintSuffix(record.fingerprint()),
                redactedPayload(record.provider(), payload),
                record.enabled(),
                record.priority(),
                record.rateLimit(),
                record.rateLimitUnit(),
                record.dayLimit(),
                record.monthLimit(),
                record.classALimit(),
                record.classBLimit(),
                record.remark(),
                0L,
                0L,
                0L,
                0L,
                0.0,
                0L,
                payloadKeys(payload));
    }

    Map<String, Object> redactedPayload(String provider, Map<String, Object> payload) {
        if (payload == null || payload.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> redacted = new LinkedHashMap<>();
        payload.keySet().stream().sorted().forEach(key -> {
            if ("MAIL".equalsIgnoreCase(provider) && isMailVisiblePayloadKey(key)) {
                redacted.put(key, payload.get(key));
                return;
            }
            redacted.put(key, Map.of(
                    "present", true,
                    "redacted", true));
        });
        return redacted;
    }

    private boolean isMailVisiblePayloadKey(String key) {
        return switch (key) {
            case "username",
                    "smtp_host",
                    "smtp_port",
                    "smtp_protocol",
                    "smtp_auth",
                    "smtp_ssl_enabled",
                    "smtp_starttls_enabled",
                    "smtp_timeout_ms" -> true;
            default -> false;
        };
    }

    Map<String, Object> payload(String payloadJson) {
        if (payloadJson == null || payloadJson.isBlank()) {
            return Map.of();
        }
        try {
            Map<String, Object> payload = objectMapper.readValue(payloadJson, new TypeReference<>() {
            });
            return new LinkedHashMap<>(payload);
        } catch (Exception ignored) {
            return Map.of();
        }
    }

    List<String> payloadKeys(Map<String, Object> payload) {
        if (payload == null || payload.isEmpty()) {
            return List.of();
        }
        return payload.keySet().stream().sorted().toList();
    }

    String fingerprintSuffix(String fingerprint) {
        if (fingerprint == null || fingerprint.isBlank()) {
            return null;
        }
        String value = fingerprint.trim();
        if (value.length() <= FINGERPRINT_SUFFIX_LENGTH) {
            return value;
        }
        return value.substring(value.length() - FINGERPRINT_SUFFIX_LENGTH);
    }
}
