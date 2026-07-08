package com.prodigalgal.ircs.contracts.normalization;

import java.util.List;
import java.util.UUID;

public record NormalizationMaintenanceRunResponse(
        String taskName,
        long rawVideoCount,
        long changedRows,
        long enqueuedRows,
        List<UUID> sampleRawVideoIds
) {
    public NormalizationMaintenanceRunResponse {
        sampleRawVideoIds = sampleRawVideoIds == null ? List.of() : List.copyOf(sampleRawVideoIds);
    }
}
