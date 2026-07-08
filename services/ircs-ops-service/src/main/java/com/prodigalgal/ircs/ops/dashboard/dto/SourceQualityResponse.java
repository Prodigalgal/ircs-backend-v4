package com.prodigalgal.ircs.ops.dashboard.dto;

import java.util.UUID;

public record SourceQualityResponse(
        UUID dataSourceId,
        String dataSourceName,
        long totalCount,
        long perfectCount,
        long missingDoubanCount,
        long missingTmdbCount,
        long normalizeFailedCount,
        long imageErrorCount,
        int qualityScore
) {
    public static SourceQualityResponse of(
            UUID dataSourceId,
            String dataSourceName,
            long totalCount,
            long perfectCount,
            long missingDoubanCount,
            long missingTmdbCount,
            long normalizeFailedCount,
            long imageErrorCount
    ) {
        return new SourceQualityResponse(
                dataSourceId,
                dataSourceName,
                totalCount,
                perfectCount,
                missingDoubanCount,
                missingTmdbCount,
                normalizeFailedCount,
                imageErrorCount,
                calculateQualityScore(totalCount, missingDoubanCount, missingTmdbCount, normalizeFailedCount, imageErrorCount));
    }

    private static int calculateQualityScore(
            long totalCount,
            long missingDoubanCount,
            long missingTmdbCount,
            long normalizeFailedCount,
            long imageErrorCount
    ) {
        if (totalCount <= 0) {
            return 0;
        }
        double score = 100.0;
        score -= ((double) normalizeFailedCount / totalCount) * 200.0;
        score -= ((double) imageErrorCount / totalCount) * 100.0;
        score -= ((double) (missingDoubanCount + missingTmdbCount) / (totalCount * 2.0)) * 50.0;
        return (int) Math.max(0, Math.min(100, score));
    }
}
