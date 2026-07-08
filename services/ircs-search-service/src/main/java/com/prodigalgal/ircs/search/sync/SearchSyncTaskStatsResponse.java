package com.prodigalgal.ircs.search.sync;

import java.time.Instant;
import java.util.Map;

public record SearchSyncTaskStatsResponse(
        boolean available,
        String unavailableReason,
        long total,
        long pending,
        long processing,
        long completed,
        long failed,
        long duePending,
        long rawVideo,
        long unifiedVideo,
        long indexOperations,
        long deleteOperations,
        int maxRetryCount,
        Instant oldestPendingAt,
        Instant nextDueAt,
        SearchSyncQueueStats rawQueue,
        SearchSyncQueueStats unifiedQueue,
        SearchSyncWorkerStats worker) {

    static SearchSyncTaskStatsResponse unavailable(String reason) {
        return new SearchSyncTaskStatsResponse(
                false,
                reason,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                null,
                null,
                SearchSyncQueueStats.empty(),
                SearchSyncQueueStats.empty(),
                SearchSyncWorkerStats.empty());
    }
}

record SearchSyncQueueStats(
        long pending,
        long processing,
        long failed,
        long expiredInflight) {
    static SearchSyncQueueStats empty() {
        return new SearchSyncQueueStats(0, 0, 0, 0);
    }
}

record SearchSyncWorkerStats(
        boolean running,
        String workerId,
        Instant lastRunAt,
        Instant lastSuccessAt,
        Instant lastFailureAt,
        Instant lastProgressAt,
        int lastProcessed,
        int lastFailed,
        int lastRequeued,
        long consecutiveFailures,
        String lastRunState,
        String lastError,
        Map<String, SearchSyncWorkLaneState> lanes) {
    static SearchSyncWorkerStats empty() {
        return new SearchSyncWorkerStats(
                false,
                null,
                null,
                null,
                null,
                null,
                0,
                0,
                0,
                0,
                "UNAVAILABLE",
                null,
                Map.of());
    }

    static SearchSyncWorkerStats from(SearchSyncWorkQueueState state) {
        if (state == null) {
            return empty();
        }
        return new SearchSyncWorkerStats(
                state.running(),
                state.workerId(),
                state.lastRunAt(),
                state.lastSuccessAt(),
                state.lastFailureAt(),
                state.lastProgressAt(),
                state.lastProcessed(),
                state.lastFailed(),
                state.lastRequeued(),
                state.consecutiveFailures(),
                state.lastRunState(),
                state.lastError(),
                state.lanes());
    }
}
