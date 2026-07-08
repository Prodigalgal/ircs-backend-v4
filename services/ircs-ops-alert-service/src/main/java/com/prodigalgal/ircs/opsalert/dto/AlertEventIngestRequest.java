package com.prodigalgal.ircs.opsalert.dto;

import com.prodigalgal.ircs.opsalert.domain.AlertSeverity;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.Map;

public record AlertEventIngestRequest(
        @NotBlank @Size(max = 128) String source,
        @NotBlank @Size(max = 128) String eventType,
        @NotNull AlertSeverity severity,
        @NotBlank @Size(max = 128) String resourceType,
        @NotBlank @Size(max = 256) String resourceName,
        @Size(max = 256) String fingerprint,
        @NotBlank @Size(max = 512) String summary,
        Instant observedAt,
        Map<String, Object> details) {
}
