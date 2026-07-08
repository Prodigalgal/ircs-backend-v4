package com.prodigalgal.ircs.ops.dashboard.dto;

public record QueueMetricResponse(
        String name,
        String key,
        String color,
        int messageCount,
        int consumerCount,
        String blockedReason,
        RateKind rateKind,
        RateMetricResponse rate
) {
}
