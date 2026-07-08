package com.prodigalgal.ircs.aggregation;

import java.util.List;
import java.util.UUID;

public record AggregationMatchPlan(UUID rootUnifiedVideoId, List<UUID> victimUnifiedVideoIds) {

    public static AggregationMatchPlan none() {
        return new AggregationMatchPlan(null, List.of());
    }

    public static AggregationMatchPlan rootOnly(UUID rootUnifiedVideoId) {
        return new AggregationMatchPlan(rootUnifiedVideoId, List.of());
    }

    public static AggregationMatchPlan rootWithVictims(UUID rootUnifiedVideoId, List<UUID> victimUnifiedVideoIds) {
        return new AggregationMatchPlan(rootUnifiedVideoId, List.copyOf(victimUnifiedVideoIds));
    }

    public boolean hasRoot() {
        return rootUnifiedVideoId != null;
    }

    public boolean hasVictims() {
        return !victimUnifiedVideoIds.isEmpty();
    }
}
