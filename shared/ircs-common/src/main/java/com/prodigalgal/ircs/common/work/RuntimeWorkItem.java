package com.prodigalgal.ircs.common.work;

import java.time.Instant;

public record RuntimeWorkItem(
        String taskType,
        String taskId,
        String submissionId,
        String aggregateId,
        String version,
        String payload,
        String status,
        int attempt,
        Instant createdAt,
        Instant updatedAt,
        Instant dueAt,
        Instant visibleAt,
        String ownerId,
        String lastError) {
}
