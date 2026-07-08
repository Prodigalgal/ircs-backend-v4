package com.prodigalgal.ircs.ops.audit.governance;

public record AuditReplicationWorkQueueSummaryResponse(
        String taskType,
        boolean available,
        long total,
        long pending,
        long inflight,
        long dlq
) {
}
