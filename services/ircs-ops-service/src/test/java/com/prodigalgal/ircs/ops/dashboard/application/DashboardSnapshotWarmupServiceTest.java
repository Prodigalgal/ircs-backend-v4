package com.prodigalgal.ircs.ops.dashboard.application;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.prodigalgal.ircs.common.config.RuntimeConfigService;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class DashboardSnapshotWarmupServiceTest {

    private final DashboardQueryService dashboardQueryService = org.mockito.Mockito.mock(DashboardQueryService.class);
    private final RuntimeConfigService runtimeConfig = org.mockito.Mockito.mock(RuntimeConfigService.class);
    private final DashboardSnapshotWarmupService service =
            new DashboardSnapshotWarmupService(dashboardQueryService, runtimeConfig);

    @Test
    void skipsWarmupWhenDisabled() {
        when(runtimeConfig.booleanValue("app.ops.dashboard.snapshot.enabled", true)).thenReturn(false);

        service.warmupIfDue();

        verify(dashboardQueryService, times(0)).warmAnalysisBlocks(eq(14), eq(50), any());
    }

    @Test
    void warmupUsesRuntimeConfigForBudgetAndLimit() {
        when(runtimeConfig.booleanValue("app.ops.dashboard.snapshot.enabled", true)).thenReturn(true);
        when(runtimeConfig.positiveDurationValue(
                        "app.ops.dashboard.snapshot.refresh-interval",
                        Duration.ofSeconds(30)))
                .thenReturn(Duration.ofSeconds(10));
        when(runtimeConfig.positiveDurationValue(
                        "app.ops.dashboard.snapshot.refresh-budget",
                        Duration.ofSeconds(3)))
                .thenReturn(Duration.ofSeconds(2));
        when(runtimeConfig.boundedIntValue(
                        "app.ops.dashboard.snapshot.default-task-runtime-limit",
                        50,
                        1,
                        500))
                .thenReturn(75);

        service.warmupNow();

        ArgumentCaptor<Instant> deadlineCaptor = ArgumentCaptor.forClass(Instant.class);
        verify(dashboardQueryService).warmAnalysisBlocks(eq(14), eq(75), deadlineCaptor.capture());
        Instant deadline = deadlineCaptor.getValue();
        Instant now = Instant.now();
        org.assertj.core.api.Assertions.assertThat(deadline).isAfter(now.minusSeconds(1));
        org.assertj.core.api.Assertions.assertThat(deadline).isBefore(now.plusSeconds(3));
    }

    @Test
    void scheduleRespectsNextWarmupTime() {
        when(runtimeConfig.booleanValue("app.ops.dashboard.snapshot.enabled", true)).thenReturn(true);
        when(runtimeConfig.positiveDurationValue(
                        "app.ops.dashboard.snapshot.refresh-interval",
                        Duration.ofSeconds(30)))
                .thenReturn(Duration.ofHours(1));
        when(runtimeConfig.positiveDurationValue(
                        "app.ops.dashboard.snapshot.refresh-budget",
                        Duration.ofSeconds(3)))
                .thenReturn(Duration.ofSeconds(1));
        when(runtimeConfig.boundedIntValue(
                        "app.ops.dashboard.snapshot.default-task-runtime-limit",
                        50,
                        1,
                        500))
                .thenReturn(50);

        service.warmupIfDue();
        service.warmupIfDue();

        verify(dashboardQueryService, times(1)).warmAnalysisBlocks(eq(14), eq(50), any());
    }
}
