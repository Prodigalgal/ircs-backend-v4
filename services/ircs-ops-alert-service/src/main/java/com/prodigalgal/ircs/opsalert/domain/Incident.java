package com.prodigalgal.ircs.opsalert.domain;

import java.time.Instant;
import java.util.UUID;

public record Incident(
        UUID id,
        Instant createdAt,
        Instant updatedAt,
        long version,
        String fingerprint,
        IncidentStatus status,
        AlertSeverity severity,
        String title,
        String source,
        String resourceType,
        String resourceName,
        Instant firstSeenAt,
        Instant lastSeenAt,
        Instant recoveredAt,
        long occurrenceCount,
        String lastReason,
        UUID lastEventId) {
}
