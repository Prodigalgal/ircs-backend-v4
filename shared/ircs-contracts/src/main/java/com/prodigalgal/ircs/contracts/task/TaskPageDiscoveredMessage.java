package com.prodigalgal.ircs.contracts.task;

import java.time.Instant;
import java.util.UUID;

public record TaskPageDiscoveredMessage(
        UUID masterTaskId,
        UUID pageTaskId,
        int pageNumber,
        long detailScheduled,
        Integer totalPages,
        Integer totalItems,
        String correlationId,
        Instant discoveredAt
) {
}
