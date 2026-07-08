package com.prodigalgal.ircs.magnet;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record MagnetProviderSummary(
        UUID id,
        String code,
        String name,
        String providerType,
        String baseUrl,
        Boolean enabled,
        Integer priority,
        String riskLevel,
        List<String> supportedExternalIds,
        Integer minDelayMs,
        Integer maxDelayMs,
        Integer timeoutMs,
        Integer resultLimit,
        Boolean autoApproveAllowed,
        String contentPolicy,
        Instant lastHealthCheckAt,
        String lastHealthStatus,
        String lastErrorMessage,
        Instant createdAt,
        Instant updatedAt
) {
}
