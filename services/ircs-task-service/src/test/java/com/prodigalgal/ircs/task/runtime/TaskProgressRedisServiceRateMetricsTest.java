package com.prodigalgal.ircs.task.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;

import com.prodigalgal.ircs.common.metrics.RateMetricKeys;
import com.prodigalgal.ircs.contracts.task.TaskDetailDoneMessage;
import com.prodigalgal.ircs.contracts.task.TaskPageDiscoveredMessage;
import com.prodigalgal.ircs.contracts.task.TaskPageFailedMessage;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

class TaskProgressRedisServiceRateMetricsTest {

    private static final Instant EVENT_AT = Instant.parse("2026-06-18T00:00:05Z");

    private final StringRedisTemplate redisTemplate = org.mockito.Mockito.mock(StringRedisTemplate.class);
    private final TaskProgressRedisService service = new TaskProgressRedisService(
            redisTemplate,
            50_000,
            "ircs:metrics:rate",
            Duration.ofSeconds(10),
            Duration.ofMinutes(30),
            Duration.ofMinutes(31));

    @Test
    void schedulePagePassesRateBucketAndMetricArgs() {
        service.trySchedulePage(UUID.randomUUID(), UUID.randomUUID(), 1, EVENT_AT);

        CapturedExecution execution = captureExecution();

        assertRateArgs(execution, 8, 9, RateMetricKeys.COLLECTION_PAGE_SCHEDULED);
    }

    @Test
    void pageDiscoveredPassesRateBucketAndMetricArgs() {
        service.recordPageDiscovered(new TaskPageDiscoveredMessage(
                UUID.randomUUID(),
                UUID.randomUUID(),
                1,
                3,
                10,
                30,
                "corr-1",
                EVENT_AT));

        CapturedExecution execution = captureExecution();

        assertRateArgs(
                execution,
                7,
                13,
                RateMetricKeys.COLLECTION_PAGE_DISCOVERED,
                RateMetricKeys.COLLECTION_PAGE_COMPLETED);
    }

    @Test
    void pageFailedPassesRateBucketAndMetricArgs() {
        service.recordPageFailed(new TaskPageFailedMessage(
                UUID.randomUUID(),
                UUID.randomUUID(),
                1,
                "boom",
                "corr-1",
                EVENT_AT));

        CapturedExecution execution = captureExecution();

        assertRateArgs(execution, 7, 10, RateMetricKeys.COLLECTION_PAGE_FAILED);
    }

    @Test
    void detailDonePassesRateBucketAndMetricArgs() {
        service.recordDetailDone(new TaskDetailDoneMessage(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                "VID-1",
                true,
                "",
                "corr-1",
                EVENT_AT));

        CapturedExecution execution = captureExecution();

        assertRateArgs(
                execution,
                9,
                15,
                RateMetricKeys.COLLECTION_DETAIL_COMPLETED,
                RateMetricKeys.COLLECTION_DETAIL_SUCCEEDED,
                RateMetricKeys.COLLECTION_DETAIL_FAILED,
                RateMetricKeys.COLLECTION_PAGE_COMPLETED);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private CapturedExecution captureExecution() {
        ArgumentCaptor<List> keysCaptor = ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<Object[]> argsCaptor = ArgumentCaptor.forClass(Object[].class);
        verify(redisTemplate).execute(any(DefaultRedisScript.class), keysCaptor.capture(), argsCaptor.capture());
        return new CapturedExecution(keysCaptor.getValue(), argsCaptor.getValue());
    }

    private void assertRateArgs(
            CapturedExecution execution,
            int expectedKeyCount,
            int expectedArgCount,
            String... expectedMetricKeys) {
        long bucketStart = RateMetricKeys.bucketStartMillis(EVENT_AT.toEpochMilli(), Duration.ofSeconds(10));
        assertEquals(expectedKeyCount, execution.keys().size());
        assertEquals(expectedArgCount, execution.args().length);
        assertTrue(execution.keys().contains(RateMetricKeys.bucketKey("ircs:metrics:rate", bucketStart)));
        assertTrue(execution.keys().contains(RateMetricKeys.bucketIndexKey("ircs:metrics:rate")));
        assertTrue(List.of(execution.args()).contains(Long.toString(bucketStart)));
        assertTrue(List.of(execution.args()).contains(Long.toString(Duration.ofMinutes(30).toSeconds())));
        assertTrue(List.of(execution.args()).contains(Long.toString(Duration.ofMinutes(31).toMillis())));
        for (String metricKey : expectedMetricKeys) {
            assertTrue(List.of(execution.args()).contains(metricKey));
        }
    }

    private record CapturedExecution(List<?> keys, Object[] args) {
    }
}
