package com.prodigalgal.ircs.contracts.task;

import java.time.Instant;
import java.util.UUID;

public record TaskDetailMessage(
        UUID masterTaskId,
        UUID pageTaskId,
        UUID detailTaskId,
        UUID dataSourceId,
        String sourceVid,
        String detailUrl,
        int attempt,
        String idempotencyKey,
        TaskScrapeOptions options,
        String correlationId,
        Instant createdAt
) {
}
