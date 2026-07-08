package com.prodigalgal.ircs.contracts.aggregation;

import java.util.List;
import java.util.UUID;

public record AggregationResetStepResponse(
        String taskName,
        String stepName,
        long rawVideoCount,
        long unifiedVideoCount,
        long bindingCount,
        long changedRows,
        List<UUID> sampleRawVideoIds
) {
    public AggregationResetStepResponse {
        sampleRawVideoIds = sampleRawVideoIds == null ? List.of() : List.copyOf(sampleRawVideoIds);
    }
}
