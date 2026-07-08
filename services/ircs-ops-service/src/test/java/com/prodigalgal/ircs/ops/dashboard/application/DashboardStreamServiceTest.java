package com.prodigalgal.ircs.ops.dashboard.application;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.prodigalgal.ircs.common.config.RuntimeConfigService;
import com.prodigalgal.ircs.ops.dashboard.dto.SystemMetricsResponse;
import com.prodigalgal.ircs.ops.dashboard.dto.TaskRuntimeOverviewResponse;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;

class DashboardStreamServiceTest {

    private final DashboardQueryService dashboardQueryService = org.mockito.Mockito.mock(DashboardQueryService.class);
    private final RuntimeConfigService runtimeConfig = org.mockito.Mockito.mock(RuntimeConfigService.class);
    private final DashboardStreamService service = new DashboardStreamService(dashboardQueryService, runtimeConfig);

    @Test
    void metricsTopicPayloadUsesMetricsQuery() {
        SystemMetricsResponse metrics = DashboardFallbacks.metrics("test");
        when(dashboardQueryService.getMetrics()).thenReturn(metrics);

        assertEquals(metrics, service.topicPayloadForTests("metrics", 50));
        verify(dashboardQueryService).getMetrics();
    }

    @Test
    void taskRuntimeTopicPayloadBoundsLimitAndFallsBackOnFailure() {
        when(dashboardQueryService.getTaskRuntimeOverview(1)).thenThrow(new IllegalStateException("redis unavailable"));

        TaskRuntimeOverviewResponse fallback =
                (TaskRuntimeOverviewResponse) service.topicPayloadForTests("task-runtime", 0);

        assertEquals(1, fallback.requestedLimit());
        assertEquals(0, fallback.activeMasterCount());
        verify(dashboardQueryService).getTaskRuntimeOverview(1);
    }

    @Test
    void opsTopicsDelegateToIndependentQueries() {
        Map<String, Object> search = Map.of("available", true, "worker", Map.of("state", "RUNNING"));
        Map<String, Object> aggregation = Map.of("available", true, "worker", Map.of("state", "IDLE"));
        when(dashboardQueryService.getSearchOpsStats()).thenReturn(search);
        when(dashboardQueryService.getAggregationOpsStats()).thenReturn(aggregation);

        assertEquals(search, service.topicPayloadForTests("search-ops", 50));
        assertEquals(aggregation, service.topicPayloadForTests("aggregation-ops", 50));
        verify(dashboardQueryService).getSearchOpsStats();
        verify(dashboardQueryService).getAggregationOpsStats();
    }

    @Test
    void rejectsUnknownTopic() {
        assertThatThrownBy(() -> service.stream("unknown", 50))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unsupported dashboard stream topic");
    }

    @Test
    void topicIntervalFallsBackToCommonStreamInterval() {
        when(runtimeConfig.positiveDurationValue("app.ops.dashboard.stream.interval", Duration.ofSeconds(3)))
                .thenReturn(Duration.ofSeconds(5));
        when(runtimeConfig.positiveDurationValue(
                "app.ops.dashboard.stream.metrics.interval",
                Duration.ofSeconds(5))).thenReturn(Duration.ofSeconds(2));

        assertEquals(Duration.ofSeconds(2), service.intervalForTests("metrics"));
    }
}
