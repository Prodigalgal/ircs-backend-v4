package com.prodigalgal.ircs.ops.selfhealing;

import java.time.Instant;
import java.util.Map;

public record LowRiskHealingResponse(
        LowRiskHealingPlaybook playbook,
        boolean dryRun,
        boolean executed,
        int affected,
        String status,
        String reason,
        Map<String, Object> evidence,
        Instant executedAt) {
}
