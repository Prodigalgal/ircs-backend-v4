package com.prodigalgal.ircs.ops.dashboard.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

import com.prodigalgal.ircs.common.aggregation.AggregationWorkTypes;
import com.prodigalgal.ircs.common.config.RuntimeConfigService;
import com.prodigalgal.ircs.common.metrics.RateMetricKeys;
import com.prodigalgal.ircs.common.normalization.LlmCleaningWorkTypes;
import com.prodigalgal.ircs.common.search.SearchSyncWorkTypes;
import com.prodigalgal.ircs.ops.queue.domain.RuntimeWorkMetricRole;
import com.prodigalgal.ircs.ops.dashboard.dto.RateMetricResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class QueueBlockedReasonResolverTest {

    private final RuntimeConfigService runtimeConfig = org.mockito.Mockito.mock(RuntimeConfigService.class);
    private final QueueBlockedReasonResolver resolver = new QueueBlockedReasonResolver(runtimeConfig);

    @BeforeEach
    void setUp() {
        when(runtimeConfig.positiveDurationValue(
                "app.aggregation.work-queue.worker.processing-stall-threshold",
                Duration.ofMinutes(10))).thenReturn(Duration.ofMinutes(10));
    }

    @Test
    void runtimeWorkBacklogUsesDisabledConfigReasonBeforeNoActiveConsumer() {
        when(runtimeConfig.booleanValue("app.ai.llm.enabled", false)).thenReturn(true);
        when(runtimeConfig.booleanValue("app.normalization.llm-cleaning.work-queue.worker.enabled", false))
                .thenReturn(false);

        String reason = resolver.runtimeWorkBlockedReason(
                LlmCleaningWorkTypes.RAW_TERM,
                RuntimeWorkMetricRole.PENDING,
                11,
                0,
                staleRate(),
                Map.of());

        assertEquals("DISABLED_BY_CONFIG", reason);
    }

    @Test
    void runtimeWorkBacklogReportsNoActiveConsumerWhenConsumerImplExists() {
        String reason = resolver.runtimeWorkBlockedReason(
                AggregationWorkTypes.RAW_VIDEO,
                RuntimeWorkMetricRole.PENDING,
                9,
                0,
                staleRate(),
                Map.of());

        assertEquals("NO_ACTIVE_CONSUMER", reason);
    }

    @Test
    void runtimeWorkBacklogWaitsForMoreEvidenceWhenConsumerExistsButRateIsStale() {
        String reason = resolver.runtimeWorkBlockedReason(
                SearchSyncWorkTypes.RAW,
                RuntimeWorkMetricRole.PENDING,
                11,
                1,
                staleRate(),
                Map.of());

        assertNull(reason);
    }

    @Test
    void runtimeWorkBacklogIgnoresNoConsumerWhenRateRecentlyMoved() {
        String reason = resolver.runtimeWorkBlockedReason(
                SearchSyncWorkTypes.RAW,
                RuntimeWorkMetricRole.PENDING,
                11,
                0,
                activeRate(),
                Map.of());

        assertNull(reason);
        assertTrue(resolver.recentlyActive(activeRate()));
        assertFalse(resolver.recentlyActive(staleRate()));
    }

    @Test
    void aggregationInflightReportsProcessingBeforeStallThreshold() {
        String reason = resolver.runtimeWorkBlockedReason(
                AggregationWorkTypes.RAW_VIDEO,
                RuntimeWorkMetricRole.INFLIGHT,
                25,
                1,
                staleRate(),
                workerStats(30));

        assertEquals("PROCESSING", reason);
    }

    @Test
    void aggregationInflightReportsProcessingStuckAfterThreshold() {
        String reason = resolver.runtimeWorkBlockedReason(
                AggregationWorkTypes.RAW_VIDEO,
                RuntimeWorkMetricRole.INFLIGHT,
                25,
                1,
                staleRate(),
                workerStats(900));

        assertEquals("CONSUMER_PROCESSING_STUCK", reason);
    }

    private Map<String, Object> workerStats(long runningForSeconds) {
        return Map.of(
                "available", true,
                "worker", Map.of(
                        "running", true,
                        "currentStage", "AGGREGATE_BATCH",
                        "runningForSeconds", runningForSeconds));
    }

    private RateMetricResponse staleRate() {
        return RateMetricResponse.empty(
                RateMetricKeys.runtimeMetric(SearchSyncWorkTypes.RAW, RateMetricKeys.ACTION_CLAIMED),
                "领取速率",
                60,
                600);
    }

    private RateMetricResponse activeRate() {
        return new RateMetricResponse(
                RateMetricKeys.runtimeMetric(SearchSyncWorkTypes.RAW, RateMetricKeys.ACTION_CLAIMED),
                "领取速率",
                1,
                1,
                60,
                600,
                1,
                1,
                Instant.now(),
                true);
    }
}
