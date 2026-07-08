package com.prodigalgal.ircs.contracts.aggregation;

import java.util.List;
import java.util.UUID;

public record AggregationMaintenanceRunResponse(
        String taskName,
        int candidates,
        int processed,
        List<UUID> entityIds
) {
    public AggregationMaintenanceRunResponse {
        entityIds = entityIds == null ? List.of() : List.copyOf(entityIds);
    }
}
