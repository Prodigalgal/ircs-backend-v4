package com.prodigalgal.ircs.ops.dashboard.dto;

public record EfficiencyStatsResponse(
        String name,
        long total,
        long successCount,
        double successRate
) {
}
