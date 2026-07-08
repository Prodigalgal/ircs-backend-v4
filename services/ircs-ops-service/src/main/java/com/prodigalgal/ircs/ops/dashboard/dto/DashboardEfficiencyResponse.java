package com.prodigalgal.ircs.ops.dashboard.dto;

import java.util.List;

public record DashboardEfficiencyResponse(
        List<EfficiencyStatsResponse> sourceEfficiency,
        List<EfficiencyStatsResponse> categoryEfficiency
) {

    public DashboardEfficiencyResponse {
        sourceEfficiency = sourceEfficiency == null ? List.of() : List.copyOf(sourceEfficiency);
        categoryEfficiency = categoryEfficiency == null ? List.of() : List.copyOf(categoryEfficiency);
    }
}
