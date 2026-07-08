package com.prodigalgal.ircs.ops.audit.request;

public record RequestAuditSummaryResponse(
        long totalLast24h,
        long errorsLast24h,
        long slowLast24h,
        Long maxDurationMsLast24h) {
}

