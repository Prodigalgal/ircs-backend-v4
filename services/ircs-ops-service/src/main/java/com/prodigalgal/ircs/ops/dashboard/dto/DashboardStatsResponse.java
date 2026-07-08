package com.prodigalgal.ircs.ops.dashboard.dto;

public record DashboardStatsResponse(
        long rawCountDb,
        long rawCountEs,
        long unifiedCountDb,
        long unifiedCountEs,
        long totalTasks,
        long pendingNormalization,
        long pendingEnrichment,
        long normalizationFailed,
        long enrichmentMissingDouban,
        long enrichmentMissingTmdb,
        long imageDownloadFailed,
        long imageDeadLink
) {
    public DashboardStatsResponse withSearchCounts(long nextRawCountEs, long nextUnifiedCountEs) {
        return new DashboardStatsResponse(
                rawCountDb,
                Math.max(0L, nextRawCountEs),
                unifiedCountDb,
                Math.max(0L, nextUnifiedCountEs),
                totalTasks,
                pendingNormalization,
                pendingEnrichment,
                normalizationFailed,
                enrichmentMissingDouban,
                enrichmentMissingTmdb,
                imageDownloadFailed,
                imageDeadLink);
    }
}
