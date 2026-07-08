package com.prodigalgal.ircs.contracts.task;

import java.time.Instant;
import java.util.UUID;

public record TaskPageMessage(
        UUID masterTaskId,
        UUID pageTaskId,
        UUID dataSourceId,
        int pageNumber,
        boolean resume,
        int attempt,
        TaskScrapeOptions options,
        String correlationId,
        Instant createdAt
) {
}
