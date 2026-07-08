package com.prodigalgal.ircs.ops.audit.request;

import java.time.Instant;

record RequestAuditSummarySnapshot(
        RequestAuditSummaryResponse summary,
        Instant generatedAt,
        Instant expiresAt,
        Instant staleUntil,
        String source
) {
    boolean usableAt(Instant now) {
        return now != null && now.isBefore(staleUntil);
    }
}
