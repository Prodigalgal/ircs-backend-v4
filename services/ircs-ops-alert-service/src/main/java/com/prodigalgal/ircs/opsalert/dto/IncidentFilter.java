package com.prodigalgal.ircs.opsalert.dto;

import com.prodigalgal.ircs.opsalert.domain.AlertSeverity;
import com.prodigalgal.ircs.opsalert.domain.IncidentStatus;
import java.time.Instant;

public record IncidentFilter(
        IncidentStatus status,
        AlertSeverity severity,
        String source,
        String resourceType,
        String resourceName,
        String fingerprint,
        Instant from,
        Instant to) {
}
