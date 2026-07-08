package com.prodigalgal.ircs.ops.dashboard.dto;

import java.time.Instant;
import java.util.UUID;

public record TaskRuntimeOverviewItemResponse(
        UUID masterTaskId,
        UUID dataSourceId,
        String taskName,
        String status,
        boolean resume,
        int startPage,
        Integer endPage,
        long pageScheduled,
        long pageDiscovered,
        long pageCompleted,
        long pageFailed,
        long detailScheduled,
        long detailCompleted,
        long detailSucceeded,
        long detailFailed,
        long detailBacklog,
        int progressPercent,
        String attentionLevel,
        String lastError,
        String correlationId,
        Instant queuedAt,
        Instant updatedAt,
        boolean snapshotPresent,
        boolean statePresent
) {
}
