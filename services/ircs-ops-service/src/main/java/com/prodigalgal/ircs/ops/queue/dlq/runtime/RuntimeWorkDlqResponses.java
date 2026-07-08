package com.prodigalgal.ircs.ops.queue.dlq.runtime;

import java.time.Instant;
import java.util.List;

record RuntimeWorkDlqQueueResponse(
        String key,
        String label,
        String taskType,
        long pending,
        long inflight,
        long dlq,
        long dlqConsumers,
        List<RuntimeWorkDlqItemResponse> samples) {
}

record RuntimeWorkDlqItemResponse(
        String taskType,
        String taskId,
        String submissionId,
        String aggregateId,
        String version,
        String status,
        int attempt,
        Instant createdAt,
        Instant updatedAt,
        String ownerId,
        String lastError,
        String payloadPreview) {
}

record RuntimeWorkDlqActionResponse(
        String taskType,
        String action,
        int requested,
        int affected,
        int maxReplayAttempts,
        RuntimeWorkDlqQueueResponse queue) {
}
