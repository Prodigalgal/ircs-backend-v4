package com.prodigalgal.ircs.contracts.trend;

import java.util.List;

public record TrendDiscoveryScheduleResponse(
        String taskName,
        long requestedKeywords,
        long dataSourceCount,
        long createdTasks,
        long reusedTasks,
        long queuedTasks,
        List<TrendDiscoveryScheduledTask> tasks,
        List<String> errors) {

    public TrendDiscoveryScheduleResponse {
        tasks = tasks == null ? List.of() : List.copyOf(tasks);
        errors = errors == null ? List.of() : List.copyOf(errors);
    }

    public static TrendDiscoveryScheduleResponse empty(String taskName) {
        return new TrendDiscoveryScheduleResponse(taskName, 0, 0, 0, 0, 0, List.of(), List.of());
    }
}
