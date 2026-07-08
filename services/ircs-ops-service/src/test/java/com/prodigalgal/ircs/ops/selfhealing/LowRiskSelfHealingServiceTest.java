package com.prodigalgal.ircs.ops.selfhealing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.prodigalgal.ircs.common.config.RuntimeConfigService;
import com.prodigalgal.ircs.ops.dashboard.application.DashboardQueryService;
import com.prodigalgal.ircs.ops.queue.dlq.rabbit.RabbitDlqActionResponse;
import com.prodigalgal.ircs.ops.queue.dlq.rabbit.RabbitDlqQueueResponse;
import com.prodigalgal.ircs.ops.queue.dlq.rabbit.RabbitDlqService;
import com.prodigalgal.ircs.ops.queue.dlq.runtime.RuntimeWorkDlqService;
import com.prodigalgal.ircs.ops.queue.dlq.runtime.RuntimeWorkExpiredInflightReaper;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;

class LowRiskSelfHealingServiceTest {

    private final RuntimeWorkExpiredInflightReaper expiredInflightReaper = mock(RuntimeWorkExpiredInflightReaper.class);
    private final RuntimeWorkDlqService runtimeWorkDlqService = mock(RuntimeWorkDlqService.class);
    private final RabbitDlqService rabbitDlqService = mock(RabbitDlqService.class);
    private final DashboardQueryService dashboardQueryService = mock(DashboardQueryService.class);
    private final RuntimeConfigService runtimeConfig = mock(RuntimeConfigService.class);
    private final LowRiskSelfHealingService service = new LowRiskSelfHealingService(
            expiredInflightReaper,
            runtimeWorkDlqService,
            rabbitDlqService,
            dashboardQueryService,
            runtimeConfig,
            Clock.fixed(Instant.parse("2026-06-22T12:00:00Z"), ZoneOffset.UTC));

    @Test
    void defaultsToDryRunAndDoesNotExecuteRuntimeMutation() {
        LowRiskHealingResponse response = service.run(new LowRiskHealingRequest(
                LowRiskHealingPlaybook.RUNTIME_EXPIRED_INFLIGHT_REQUEUE,
                null,
                null,
                null,
                null,
                null,
                null));

        assertThat(response.dryRun()).isTrue();
        assertThat(response.executed()).isFalse();
        assertThat(response.status()).isEqualTo("DRY_RUN");
        verifyNoInteractions(expiredInflightReaper, runtimeWorkDlqService, rabbitDlqService, dashboardQueryService);
    }

    @Test
    void skipsExecutionWhenGlobalExecuteSwitchIsDisabled() {
        when(runtimeConfig.booleanValue(LowRiskSelfHealingService.DEFAULT_DRY_RUN_KEY, true)).thenReturn(false);
        when(runtimeConfig.booleanValue(LowRiskSelfHealingService.EXECUTE_ENABLED_KEY, false)).thenReturn(false);

        LowRiskHealingResponse response = service.run(new LowRiskHealingRequest(
                LowRiskHealingPlaybook.DASHBOARD_REFRESH,
                false,
                null,
                null,
                null,
                null,
                7));

        assertThat(response.dryRun()).isFalse();
        assertThat(response.executed()).isFalse();
        assertThat(response.status()).isEqualTo("SKIPPED");
        verifyNoInteractions(dashboardQueryService);
    }

    @Test
    void executesRuntimeDlqRequeueWithHardLimitOneWhenEnabled() {
        when(runtimeConfig.booleanValue(LowRiskSelfHealingService.DEFAULT_DRY_RUN_KEY, true)).thenReturn(false);
        when(runtimeConfig.booleanValue(LowRiskSelfHealingService.EXECUTE_ENABLED_KEY, false)).thenReturn(true);
        when(runtimeWorkDlqService.requeueOne("aggregation.raw-video", 5)).thenReturn(1);

        LowRiskHealingResponse response = service.run(new LowRiskHealingRequest(
                LowRiskHealingPlaybook.RUNTIME_DLQ_REQUEUE_ONE,
                false,
                "aggregation.raw-video",
                null,
                99,
                5,
                null));

        assertThat(response.executed()).isTrue();
        assertThat(response.affected()).isEqualTo(1);
        assertThat(response.evidence()).containsEntry("maxReplayAttempts", 5);
        verify(runtimeWorkDlqService).requeueOne("aggregation.raw-video", 5);
    }

    @Test
    void executesRabbitDlqRetryWithHardLimitOneWhenEnabled() {
        when(runtimeConfig.booleanValue(LowRiskSelfHealingService.DEFAULT_DRY_RUN_KEY, true)).thenReturn(false);
        when(runtimeConfig.booleanValue(LowRiskSelfHealingService.EXECUTE_ENABLED_KEY, false)).thenReturn(true);
        RabbitDlqActionResponse retryResponse = new RabbitDlqActionResponse(
                "q.notification.command.dlq",
                "retry",
                1,
                1,
                new RabbitDlqQueueResponse(
                        "DISPATCH_NOTIFICATION",
                        "Notification Command DLQ",
                        "q.notification.command.dlq",
                        "q.notification.command",
                        "x.notification",
                        "notification.command",
                        0,
                        0,
                        0,
                        0,
                        true,
                        List.of()),
                List.of());
        when(rabbitDlqService.retry("q.notification.command.dlq", 1)).thenReturn(retryResponse);

        LowRiskHealingResponse response = service.run(new LowRiskHealingRequest(
                LowRiskHealingPlaybook.RABBIT_DLQ_RETRY_ONE,
                false,
                null,
                "q.notification.command.dlq",
                99,
                null,
                null));

        assertThat(response.executed()).isTrue();
        assertThat(response.affected()).isEqualTo(1);
        verify(rabbitDlqService).retry("q.notification.command.dlq", 1);
    }

    @Test
    void refreshesDashboardWhenEnabled() {
        when(runtimeConfig.booleanValue(LowRiskSelfHealingService.DEFAULT_DRY_RUN_KEY, true)).thenReturn(false);
        when(runtimeConfig.booleanValue(LowRiskSelfHealingService.EXECUTE_ENABLED_KEY, false)).thenReturn(true);

        LowRiskHealingResponse response = service.run(new LowRiskHealingRequest(
                LowRiskHealingPlaybook.DASHBOARD_REFRESH,
                false,
                null,
                null,
                null,
                null,
                120));

        assertThat(response.executed()).isTrue();
        assertThat(response.evidence()).containsEntry("days", 90);
        verify(dashboardQueryService).refresh(90);
    }
}
