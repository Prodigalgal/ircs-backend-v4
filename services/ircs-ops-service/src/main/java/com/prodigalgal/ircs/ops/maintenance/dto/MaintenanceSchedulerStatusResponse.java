package com.prodigalgal.ircs.ops.maintenance.dto;

import java.time.Instant;
import java.util.List;

public record MaintenanceSchedulerStatusResponse(
        boolean enabled,
        boolean dryRun,
        boolean executeEnabled,
        boolean running,
        boolean clusterLeaseEnabled,
        String workerId,
        List<String> tasks,
        String lastCorrelationId,
        Instant lastStartedAt,
        Instant lastFinishedAt,
        List<MaintenanceSchedulerRunResult> lastResults
) {
    public MaintenanceSchedulerStatusResponse {
        tasks = tasks == null ? List.of() : List.copyOf(tasks);
        lastResults = lastResults == null ? List.of() : List.copyOf(lastResults);
    }
}
