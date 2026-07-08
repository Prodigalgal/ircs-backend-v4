package com.prodigalgal.ircs.ops.maintenance.domain;

import com.prodigalgal.ircs.contracts.maintenance.MaintenanceGateMode;
import java.time.Instant;
import java.util.UUID;

public record MaintenanceGateDraft(
        UUID id,
        Instant now,
        String operationKey,
        String ownerService,
        String resourceType,
        String resourceScope,
        MaintenanceGateMode mode,
        String reason,
        String requestedBy,
        String correlationId,
        Instant expiresAt
) {
}
