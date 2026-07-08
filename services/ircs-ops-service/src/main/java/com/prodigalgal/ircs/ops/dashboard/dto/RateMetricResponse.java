package com.prodigalgal.ircs.ops.dashboard.dto;

import java.time.Instant;

public record RateMetricResponse(
        String metricKey,
        String label,
        long instantTpm,
        long stableTpm,
        long instantWindowSeconds,
        long stableWindowSeconds,
        long instantCount,
        long stableCount,
        Instant lastEventAt,
        boolean stale
) {
    public static RateMetricResponse empty(
            String metricKey,
            String label,
            long instantWindowSeconds,
            long stableWindowSeconds) {
        return new RateMetricResponse(
                metricKey,
                label,
                0,
                0,
                instantWindowSeconds,
                stableWindowSeconds,
                0,
                0,
                null,
                true);
    }
}
