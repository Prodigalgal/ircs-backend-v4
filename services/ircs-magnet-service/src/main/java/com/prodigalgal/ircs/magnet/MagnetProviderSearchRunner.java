package com.prodigalgal.ircs.magnet;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

interface MagnetProviderSearchRunner {

    MagnetProviderSearchResult search(
            MagnetProviderSummary provider,
            MagnetExternalIdQuery query,
            UUID unifiedVideoId);
}

record MagnetExternalIdQuery(
        String type,
        String value
) {
}

record MagnetProviderSearchResult(
        String requestUrl,
        Integer httpStatus,
        List<MagnetProviderCandidate> candidates
) {
}

record MagnetProviderCandidate(
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
        String sourceUrl,
        List<String> tags,
        Map<String, Object> providerEvidence
) {
}
