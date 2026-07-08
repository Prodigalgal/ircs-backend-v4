package com.prodigalgal.ircs.search.sync;

import java.time.Instant;
import java.util.Map;

record SearchSyncWorkQueueState(
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
}

record SearchSyncWorkLaneState(
        String taskType,
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
        String currentTaskId,
        String currentEntityId,
        String currentStage,
        Instant currentStageStartedAt,
        long runningForSeconds) {
}
