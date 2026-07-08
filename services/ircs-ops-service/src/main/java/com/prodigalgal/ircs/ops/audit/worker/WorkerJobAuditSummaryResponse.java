package com.prodigalgal.ircs.ops.audit.worker;

public record WorkerJobAuditSummaryResponse(
        long totalLast24h,
        long failedLast24h,
        long succeededLast24h,
        Long maxDurationMsLast24h) {
}
