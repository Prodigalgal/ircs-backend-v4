package com.prodigalgal.ircs.task.dto;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public record TaskPageRuntimeSummary(
        UUID pageTaskId,
        Integer pageNumber,
        long detailScheduled,
        long detailCompleted,
        long detailSucceeded,
        long detailFailed,
        String status,
        String lastError,
        Instant updatedAt,
        int completedDetailTaskCount,
        List<String> completedDetailTaskIds,
        int failedDetailTaskCount,
        List<String> failedDetailTaskIds,
        Map<String, String> failedDetailErrors,
        long detailBacklog,
        int progressPercent,
        String attentionLevel,
        Map<String, String> state
) {
}
