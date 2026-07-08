package com.prodigalgal.ircs.credential;

import java.time.Instant;
import java.util.UUID;

public record CredentialRecord(
        UUID id,
        Instant createdAt,
        Instant updatedAt,
        long revision,
        String provider,
        String name,
        String payloadJson,
        String fingerprint,
        boolean enabled,
        Integer priority,
        Integer rateLimit,
        String rateLimitUnit,
        Long dayLimit,
        Long monthLimit,
        Long classALimit,
        Long classBLimit,
        String remark) {
    public CredentialRecord(
            UUID id,
            Instant createdAt,
            Instant updatedAt,
            String provider,
            String name,
            String payloadJson,
            String fingerprint,
            boolean enabled,
            Integer priority,
            Integer rateLimit,
            String rateLimitUnit,
            Long dayLimit,
            Long monthLimit,
            Long classALimit,
            Long classBLimit,
            String remark) {
        this(
                id,
                createdAt,
                updatedAt,
                0L,
                provider,
                name,
                payloadJson,
                fingerprint,
                enabled,
                priority,
                rateLimit,
                rateLimitUnit,
                dayLimit,
                monthLimit,
                classALimit,
                classBLimit,
                remark);
    }
}
