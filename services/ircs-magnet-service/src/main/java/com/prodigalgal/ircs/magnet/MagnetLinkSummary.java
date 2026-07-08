package com.prodigalgal.ircs.magnet;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record MagnetLinkSummary(
        UUID id,
        UUID unifiedVideoId,
        String providerCode,
        String infoHash,
        String magnetUri,
        String title,
        Long sizeBytes,
        String sizeLabel,
        Instant publishedAt,
        Integer seeders,
        Integer leechers,
        String quality,
        String resolution,
        String matchedExternalIdType,
        String matchedExternalIdValue,
        Integer matchScore,
        String status,
        String sourceUrl,
        List<String> tags,
        Instant createdAt,
        Instant updatedAt
) {
}
