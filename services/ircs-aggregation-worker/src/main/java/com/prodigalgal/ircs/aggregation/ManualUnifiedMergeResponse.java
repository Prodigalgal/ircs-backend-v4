package com.prodigalgal.ircs.aggregation;

import java.util.List;
import java.util.UUID;

public record ManualUnifiedMergeResponse(
        UUID rootUnifiedVideoId,
        List<UUID> victimUnifiedVideoIds,
        List<UUID> rawVideoIds,
        String status,
        List<AggregationPipelineStageFailure> failures) {
}
