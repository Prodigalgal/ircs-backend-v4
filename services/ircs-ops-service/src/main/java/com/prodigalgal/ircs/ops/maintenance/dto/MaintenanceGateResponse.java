package com.prodigalgal.ircs.ops.maintenance.dto;

import com.prodigalgal.ircs.contracts.maintenance.MaintenanceGateMode;
import com.prodigalgal.ircs.contracts.maintenance.MaintenanceGateStatus;
import java.time.Instant;
import java.util.UUID;

public record MaintenanceGateResponse(
        UUID id,
        Instant createdAt,
        Instant updatedAt,
        long version,
        String operationKey,
        String ownerService,
        String resourceType,
        String resourceScope,
        MaintenanceGateMode mode,
        MaintenanceGateStatus status,
        String reason,
        String requestedBy,
        String correlationId,
        Instant expiresAt,
        Instant closedAt,
        String closeReason
) {
}
