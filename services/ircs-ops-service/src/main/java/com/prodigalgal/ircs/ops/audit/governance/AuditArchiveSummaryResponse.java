package com.prodigalgal.ircs.ops.audit.governance;

import java.time.Instant;

public record AuditArchiveSummaryResponse(
        long total,
        long securityCount,
        long behaviorCount,
        long systemCount,
        Instant oldestArchivedAt,
        Instant newestArchivedAt
) {
}
