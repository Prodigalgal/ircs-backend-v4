package com.prodigalgal.ircs.opsalert.domain;

import java.time.Instant;
import java.util.UUID;

public record AlertEvent(
        UUID id,
        Instant createdAt,
        Instant observedAt,
        String source,
        String eventType,
        AlertSeverity severity,
        String resourceType,
        String resourceName,
        String fingerprint,
        String summary,
        String detailsJson) {
}
