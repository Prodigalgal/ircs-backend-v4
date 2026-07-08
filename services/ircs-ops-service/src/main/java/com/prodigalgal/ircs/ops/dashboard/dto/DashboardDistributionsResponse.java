package com.prodigalgal.ircs.ops.dashboard.dto;

import java.util.List;

public record DashboardDistributionsResponse(
        List<ChartDataPoint> categoryDistribution,
        List<ChartDataPoint> sourceDistribution,
        List<ChartDataPoint> enrichmentStatusDistribution
) {

    public DashboardDistributionsResponse {
        categoryDistribution = categoryDistribution == null ? List.of() : List.copyOf(categoryDistribution);
        sourceDistribution = sourceDistribution == null ? List.of() : List.copyOf(sourceDistribution);
        enrichmentStatusDistribution = enrichmentStatusDistribution == null
                ? List.of()
                : List.copyOf(enrichmentStatusDistribution);
    }
}
