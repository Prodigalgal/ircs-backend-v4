package com.prodigalgal.ircs.ops.dashboard.application;

import com.prodigalgal.ircs.ops.dashboard.dto.RateMetricResponse;
import com.prodigalgal.ircs.ops.dashboard.dto.RedisMetricResponse;
import com.prodigalgal.ircs.ops.dashboard.dto.SystemMetricsResponse;
import com.prodigalgal.ircs.ops.dashboard.dto.TaskRuntimeOverviewResponse;
import java.time.Instant;
import java.util.List;
import java.util.Map;

final class DashboardFallbacks {

    private DashboardFallbacks() {
    }

    static SystemMetricsResponse metrics(String reason) {
        String safeReason = reason == null || reason.isBlank() ? "UNAVAILABLE" : reason;
        return new SystemMetricsResponse(
                RateMetricResponse.empty("collection.page.discovered", "PT 发现速率", 60, 300),
                RateMetricResponse.empty("collection.detail.completed", "DT 采集速率", 60, 300),
                RateMetricResponse.empty("runtime.aggregation.completed", "清洗速率", 60, 300),
                RateMetricResponse.empty("pipeline.metadata_provider.completed", "元数据速率", 60, 300),
                0,
                0,
                0,
                0,
                List.of(),
                0,
                new RedisMetricResponse(0, 0, 0, "unknown"),
                0,
                0,
                false,
                0,
                Map.of("dashboardMetrics", safeReason));
    }

    static TaskRuntimeOverviewResponse taskRuntime(int taskRuntimeLimit) {
        Instant now = Instant.now();
        return new TaskRuntimeOverviewResponse(
                now,
                Math.max(1, taskRuntimeLimit),
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                Map.of(),
                Map.of(),
                List.of());
    }

    static Map<String, Object> unavailableOps(String reason) {
        String safeReason = reason == null || reason.isBlank() ? "UNAVAILABLE" : reason;
        return Map.of("available", false, "unavailableReason", safeReason);
    }
}
