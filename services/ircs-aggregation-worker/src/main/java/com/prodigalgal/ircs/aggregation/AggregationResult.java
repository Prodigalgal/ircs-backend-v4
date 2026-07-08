package com.prodigalgal.ircs.aggregation;

import java.util.UUID;
import java.util.List;

public record AggregationResult(UUID rawVideoId, UUID unifiedVideoId, String status, List<UUID> deletedUnifiedVideoIds) {
    public static AggregationResult bound(UUID rawVideoId, UUID unifiedVideoId) {
        return bound(rawVideoId, unifiedVideoId, List.of());
    }

    public static AggregationResult bound(UUID rawVideoId, UUID unifiedVideoId, List<UUID> deletedUnifiedVideoIds) {
        return new AggregationResult(rawVideoId, unifiedVideoId, "BOUND", List.copyOf(deletedUnifiedVideoIds));
    }

    public static AggregationResult skipped(UUID rawVideoId, String status) {
        return new AggregationResult(rawVideoId, null, status, List.of());
    }

    public static AggregationResult pipelineFailed(
            UUID rawVideoId,
            UUID unifiedVideoId,
            List<UUID> deletedUnifiedVideoIds) {
        return new AggregationResult(rawVideoId, unifiedVideoId, "PIPELINE_FAILED", List.copyOf(deletedUnifiedVideoIds));
    }
}
