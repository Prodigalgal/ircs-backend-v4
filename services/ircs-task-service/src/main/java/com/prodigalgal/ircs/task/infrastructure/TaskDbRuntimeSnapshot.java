package com.prodigalgal.ircs.task.infrastructure;

import java.time.Instant;
import java.util.UUID;

public record TaskDbRuntimeSnapshot(
        UUID masterTaskId,
        String status,
        Integer currentPage,
        long totalFound,
        long processed,
        long success,
        long failed,
        Instant startedAt,
        Instant endedAt,
        String lastError,
        Instant flushedAt
) {
}
