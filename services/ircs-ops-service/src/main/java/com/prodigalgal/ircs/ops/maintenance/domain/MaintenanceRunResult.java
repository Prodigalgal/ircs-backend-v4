package com.prodigalgal.ircs.ops.maintenance.domain;

import java.util.List;
import java.util.UUID;

public record MaintenanceRunResult(
        String taskName,
        int selectedCount,
        int publishedCount,
        List<UUID> entityIds
) {
    public MaintenanceRunResult {
        entityIds = List.copyOf(entityIds);
    }
}
