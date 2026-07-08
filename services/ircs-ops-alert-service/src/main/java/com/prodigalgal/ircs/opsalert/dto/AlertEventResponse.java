package com.prodigalgal.ircs.opsalert.dto;

import com.prodigalgal.ircs.opsalert.domain.AlertSeverity;
import java.time.Instant;
import java.util.UUID;

public record AlertEventResponse(
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
