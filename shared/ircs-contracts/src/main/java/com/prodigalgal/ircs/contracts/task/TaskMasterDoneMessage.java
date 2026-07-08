package com.prodigalgal.ircs.contracts.task;

import java.time.Instant;
import java.util.UUID;

public record TaskMasterDoneMessage(
        UUID masterTaskId,
        String status,
        long pageScheduled,
        long pageCompleted,
        long pageSucceeded,
        long pageFailed,
        long detailScheduled,
        long detailCompleted,
        long detailSucceeded,
        long detailFailed,
        String lastError,
        String correlationId,
        Instant completedAt
) {
}
