package com.prodigalgal.ircs.ops.selfhealing;

import java.time.Instant;
import java.util.Map;

public record ServiceRestartHealingResponse(
        String service,
        boolean dryRun,
        boolean accepted,
        boolean recoveryVerified,
        String status,
        String reason,
        Map<String, Object> evidence,
        Instant requestedAt) {
}
