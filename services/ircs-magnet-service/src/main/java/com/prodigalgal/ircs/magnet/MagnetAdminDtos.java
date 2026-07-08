package com.prodigalgal.ircs.magnet;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

record MagnetProviderRequest(
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

record MagnetSearchJobSummary(
        UUID id,
        UUID unifiedVideoId,
        String triggerType,
        String status,
        List<String> providerCodes,
        List<Object> externalIdPlan,
        Instant startedAt,
        Instant finishedAt,
        Integer totalCandidates,
        Integer acceptedCount,
        Integer rejectedCount,
        String skippedReason,
        String errorMessage,
        List<MagnetLinkSummary> links,
        Instant createdAt,
        Instant updatedAt
) {
}

record MagnetProviderRunSummary(
        UUID id,
        UUID jobId,
        UUID providerId,
        String providerCode,
        String externalIdType,
        String externalIdValue,
        String status,
        String requestUrl,
        Integer httpStatus,
        Integer candidateCount,
        Integer acceptedCount,
        Long durationMs,
        String errorMessage,
        Instant createdAt,
        Instant updatedAt
) {
}

record MagnetUnifiedVideoSearchTarget(
        UUID id,
        String title,
        String aliasTitle,
        String year,
        String imdbId,
        String tmdbId,
        String doubanId,
        String metadataStatus
) {
}

record MagnetSearchWorkPayload(
        UUID jobId,
        UUID unifiedVideoId,
        String triggerType
) {
}

record MagnetLinkStatusRequest(
        String status
) {
}
