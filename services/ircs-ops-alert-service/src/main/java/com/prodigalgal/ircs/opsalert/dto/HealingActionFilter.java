package com.prodigalgal.ircs.opsalert.dto;

import com.prodigalgal.ircs.opsalert.domain.HealingActionStatus;
import java.time.Instant;
import java.util.UUID;

public record HealingActionFilter(
        UUID incidentId,
        HealingActionStatus status,
        String policyKey,
        String playbookKey,
        Boolean dryRun,
        Instant from,
        Instant to) {
}
