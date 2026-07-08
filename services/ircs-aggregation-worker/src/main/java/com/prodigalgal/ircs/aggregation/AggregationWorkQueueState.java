package com.prodigalgal.ircs.aggregation;

import java.time.Instant;

record AggregationWorkQueueState(
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
        String currentStage,
        String currentRawVideoId,
        String currentTaskId,
        int currentBatchSize,
        Instant currentStageStartedAt,
        long runningForSeconds) {
}
