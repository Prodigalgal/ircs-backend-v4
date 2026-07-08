package com.prodigalgal.ircs.task.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record MediaRequestBatchResponse(
        UUID id,
        String status,
        int requestCount,
        int scheduledCount,
        int skippedCount,
        int failedCount,
        Instant createdAt,
        Instant updatedAt,
        Instant startedAt,
        Instant cancelledAt,
        Instant completedAt,
        String lastErrorMessage,
        List<MediaRequestBatchItemResponse> items) {

    public MediaRequestBatchResponse {
        items = items == null ? List.of() : List.copyOf(items);
    }
}
