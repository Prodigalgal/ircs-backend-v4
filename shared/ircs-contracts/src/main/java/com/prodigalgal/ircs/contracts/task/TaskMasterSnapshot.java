package com.prodigalgal.ircs.contracts.task;

import java.time.Instant;
import java.util.UUID;

public record TaskMasterSnapshot(
        UUID masterTaskId,
        UUID dataSourceId,
        String taskName,
        String status,
        boolean resume,
        int startPage,
        Integer endPage,
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
        Instant queuedAt,
        Instant updatedAt
) {
}
