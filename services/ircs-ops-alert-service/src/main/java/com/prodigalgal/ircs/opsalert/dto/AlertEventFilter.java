package com.prodigalgal.ircs.opsalert.dto;

import com.prodigalgal.ircs.opsalert.domain.AlertSeverity;
import java.time.Instant;

public record AlertEventFilter(
        AlertSeverity severity,
        String source,
        String eventType,
        String resourceType,
        String resourceName,
        String fingerprint,
        Instant from,
        Instant to) {
}
