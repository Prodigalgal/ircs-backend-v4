package com.prodigalgal.ircs.ops.maintenance.dto;

import com.prodigalgal.ircs.ops.maintenance.domain.MaintenanceOwnerStep;
import com.prodigalgal.ircs.ops.maintenance.domain.MaintenanceRunResult;
import com.prodigalgal.ircs.ops.maintenance.domain.MaintenanceRunnerExecution;
import java.util.List;

public record MaintenanceSchedulerRunResult(
        String taskName,
        List<MaintenanceOwnerStep> ownerSteps,
        boolean dryRun,
        boolean executed,
        boolean refused,
        boolean skipped,
        String reason,
        int selectedCount,
        int publishedCount
) {
    public MaintenanceSchedulerRunResult {
        taskName = taskName == null || taskName.isBlank() ? "unknown" : taskName.trim();
        ownerSteps = ownerSteps == null ? List.of() : List.copyOf(ownerSteps);
        reason = reason == null ? "" : reason;
        selectedCount = Math.max(0, selectedCount);
        publishedCount = Math.max(0, publishedCount);
    }

    public static MaintenanceSchedulerRunResult dryRun(MaintenanceRunnerMetadata metadata) {
        return new MaintenanceSchedulerRunResult(
                metadata.taskName(),
                metadata.ownerSteps(),
                true,
                false,
                false,
                false,
                "dry-run",
                0,
                0);
    }

    public static MaintenanceSchedulerRunResult executed(MaintenanceRunnerExecution execution) {
        MaintenanceRunResult result = execution.result();
        return new MaintenanceSchedulerRunResult(
                execution.metadata().taskName(),
                execution.metadata().ownerSteps(),
                false,
                true,
                false,
                false,
                "",
                result == null ? 0 : result.selectedCount(),
                result == null ? 0 : result.publishedCount());
    }

    public static MaintenanceSchedulerRunResult refused(
            MaintenanceRunnerMetadata metadata,
            boolean dryRun,
            String reason) {
        return new MaintenanceSchedulerRunResult(
                metadata.taskName(),
                metadata.ownerSteps(),
                dryRun,
                false,
                true,
                false,
                reason,
                0,
                0);
    }

    public static MaintenanceSchedulerRunResult skipped(String taskName, String reason) {
        return new MaintenanceSchedulerRunResult(
                taskName,
                List.of(),
                false,
                false,
                false,
                true,
                reason,
                0,
                0);
    }
}
