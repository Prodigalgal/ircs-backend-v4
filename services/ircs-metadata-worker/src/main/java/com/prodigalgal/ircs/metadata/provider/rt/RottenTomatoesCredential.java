package com.prodigalgal.ircs.metadata.provider.rt;

import java.util.UUID;

public record RottenTomatoesCredential(
        UUID id,
        String cookie,
        String userAgent,
        Integer rateLimit,
        String rateLimitUnit,
        Integer priority) {
}
