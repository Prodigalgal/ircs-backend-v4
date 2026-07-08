package com.prodigalgal.ircs.ops.maintenance.dto;

import com.prodigalgal.ircs.ops.maintenance.domain.MaintenanceOwnerStep;
import com.prodigalgal.ircs.ops.maintenance.domain.MaintenanceRiskLevel;
import java.util.List;

public record MaintenanceRunnerMetadata(
        String taskName,
        MaintenanceRiskLevel riskLevel,
        boolean devAllowed,
        boolean supportsDryRun,
        int defaultLimit,
        int maxLimit,
        String refusalReason,
        List<MaintenanceOwnerStep> ownerSteps
) {
    public MaintenanceRunnerMetadata(
            String taskName,
            MaintenanceRiskLevel riskLevel,
            boolean devAllowed,
            boolean supportsDryRun,
            int defaultLimit,
            int maxLimit,
            String refusalReason) {
        this(taskName, riskLevel, devAllowed, supportsDryRun, defaultLimit, maxLimit, refusalReason, List.of());
    }

    public MaintenanceRunnerMetadata {
        if (taskName == null || taskName.isBlank()) {
            throw new IllegalArgumentException("taskName must not be blank");
        }
        if (riskLevel == null) {
            throw new IllegalArgumentException("riskLevel must not be null");
        }
        defaultLimit = Math.max(0, defaultLimit);
        maxLimit = Math.max(defaultLimit, maxLimit);
        refusalReason = refusalReason == null ? "" : refusalReason;
        ownerSteps = ownerSteps == null ? List.of() : List.copyOf(ownerSteps);
    }
}
