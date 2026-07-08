package com.prodigalgal.ircs.task.dto;

import java.util.UUID;

public record MediaRequestBatchActionResponse(
        UUID batchId,
        String status,
        int scheduledCount,
        int skippedCount,
        int failedCount) {
}
