package com.prodigalgal.ircs.task.dto;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record TaskMasterRuntimeSummary(
        String status,
        UUID dataSourceId,
        String taskName,
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
        Integer totalPages,
        Integer totalItems,
        String lastError,
        String correlationId,
        Instant queuedAt,
        Instant updatedAt,
        Map<String, String> state
) {
}
