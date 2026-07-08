package com.prodigalgal.ircs.opsalert.domain;

import java.time.Instant;
import java.util.UUID;

public record HealingAction(
        UUID id,
        UUID incidentId,
        Instant createdAt,
        Instant updatedAt,
        String policyKey,
        String playbookKey,
        boolean dryRun,
        HealingActionStatus status,
        String requestPayload,
        String resultPayload,
        Instant startedAt,
        Instant finishedAt) {
}
