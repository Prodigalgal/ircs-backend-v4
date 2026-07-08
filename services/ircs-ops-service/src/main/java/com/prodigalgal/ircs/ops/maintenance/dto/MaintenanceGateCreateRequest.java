package com.prodigalgal.ircs.ops.maintenance.dto;

import com.prodigalgal.ircs.contracts.maintenance.MaintenanceGateMode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.time.Instant;

public record MaintenanceGateCreateRequest(
        @NotBlank @Size(max = 160)
        String operationKey,
        @NotBlank @Size(max = 120)
        String ownerService,
        @NotBlank @Size(max = 80)
        String resourceType,
        @NotBlank @Size(max = 240)
        String resourceScope,
        @NotNull
        MaintenanceGateMode mode,
        @Size(max = 500)
        String reason,
        @Size(max = 120)
        String requestedBy,
        @Size(max = 160)
        String correlationId,
        Instant expiresAt,
        @Positive
        Long ttlSeconds
) {
}
