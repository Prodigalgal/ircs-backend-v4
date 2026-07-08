package com.prodigalgal.ircs.ops.maintenance.domain;

import com.prodigalgal.ircs.ops.maintenance.dto.MaintenanceRunnerMetadata;

public record MaintenanceRunnerExecution(
        MaintenanceRunnerMetadata metadata,
        MaintenanceRunResult result,
        boolean refused,
        String reason
) {
    public static MaintenanceRunnerExecution executed(
            MaintenanceRunnerMetadata metadata,
            MaintenanceRunResult result) {
        return new MaintenanceRunnerExecution(metadata, result, false, "");
    }

    public static MaintenanceRunnerExecution refused(
            MaintenanceRunnerMetadata metadata,
            String reason) {
        return new MaintenanceRunnerExecution(metadata, null, true, reason);
    }

    public MaintenanceRunnerExecution {
        if (metadata == null) {
            throw new IllegalArgumentException("metadata must not be null");
        }
        reason = reason == null ? "" : reason;
    }
}
