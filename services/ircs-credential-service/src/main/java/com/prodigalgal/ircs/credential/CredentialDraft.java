package com.prodigalgal.ircs.credential;

import java.util.UUID;

record CredentialDraft(
        UUID id,
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
}
