package com.prodigalgal.ircs.common.work;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.prodigalgal.ircs.common.aggregation.AggregationWorkTypes;
import com.prodigalgal.ircs.common.metrics.RateMetricKeys;
import com.prodigalgal.ircs.common.search.SearchSyncWorkTypes;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.data.redis.core.script.DefaultRedisScript;

class RedisRuntimeWorkQueueRateMetricsTest {

    private final StringRedisTemplate redisTemplate = org.mockito.Mockito.mock(StringRedisTemplate.class);
    private final Clock clock = Clock.fixed(Instant.parse("2026-06-18T00:00:05Z"), ZoneOffset.UTC);
    private final RedisRuntimeWorkQueue queue = new RedisRuntimeWorkQueue(
            redisTemplate,
            null,
            null,
            provider(clock),
            "ircs:work:v1",
            10_000,
            "ircs:metrics:rate",
            Duration.ofSeconds(10),
            Duration.ofMinutes(30),
            Duration.ofMinutes(31));

    @Test
    void submitPassesRateBucketAndMetricArgs() {
        queue.submit(new RuntimeWorkItemRequest(
                SearchSyncWorkTypes.RAW,
                "task-1",
                "raw-1",
                "v1",
                "{}"));

        CapturedExecution execution = captureExecution();

        assertRateArgs(
                execution,
                6,
                14,
                RateMetricKeys.runtimeMetric(SearchSyncWorkTypes.RAW, RateMetricKeys.ACTION_SUBMITTED));
    }

    @Test
    void submitSkipsRedisWhenSubmissionGateIsDisabled() {
        WorkSubmissionGate gate = org.mockito.Mockito.mock(WorkSubmissionGate.class);
        when(gate.canSubmitRuntime(SearchSyncWorkTypes.RAW)).thenReturn(false);
        RedisRuntimeWorkQueue gatedQueue = new RedisRuntimeWorkQueue(
                redisTemplate,
                gateProvider(gate),
                null,
                provider(clock),
                "ircs:work:v1",
                10_000,
                "ircs:metrics:rate",
                Duration.ofSeconds(10),
                Duration.ofMinutes(30),
                Duration.ofMinutes(31));

        gatedQueue.submit(new RuntimeWorkItemRequest(
                SearchSyncWorkTypes.RAW,
                "task-1",
                "raw-1",
                "v1",
                "{}"));

        verifyNoInteractions(redisTemplate);
    }

    @Test
    void claimPassesRateBucketAndMetricArgs() {
        queue.claim(AggregationWorkTypes.RAW_VIDEO, "worker-a", 5, Duration.ofMinutes(5));

        CapturedExecution execution = captureExecution();

        assertRateArgs(
                execution,
                5,
                11,
                RateMetricKeys.runtimeMetric(AggregationWorkTypes.RAW_VIDEO, RateMetricKeys.ACTION_CLAIMED));
    }

    @Test
    void completePassesRateBucketAndMetricArgs() {
        RuntimeWorkItem item = item(SearchSyncWorkTypes.UNIFIED, "task-2");

        queue.complete(item);

        CapturedExecution execution = captureExecution();

        assertRateArgs(
                execution,
                6,
                11,
                RateMetricKeys.runtimeMetric(SearchSyncWorkTypes.UNIFIED, RateMetricKeys.ACTION_COMPLETED));
    }

    @Test
    void failPassesRateBucketAndMetricArgs() {
        RuntimeWorkItem item = item(AggregationWorkTypes.RAW_VIDEO, "task-3");

        queue.fail(item, false, Duration.ZERO, "boom");

        CapturedExecution execution = captureExecution();

        assertRateArgs(
                execution,
                6,
                13,
                RateMetricKeys.runtimeMetric(AggregationWorkTypes.RAW_VIDEO, RateMetricKeys.ACTION_FAILED));
    }

    @Test
    void expiredInflightCountReadsExpiredVisibilityScores() {
        @SuppressWarnings("unchecked")
        ZSetOperations<String, String> zSets = org.mockito.Mockito.mock(ZSetOperations.class);
        when(redisTemplate.opsForZSet()).thenReturn(zSets);
        when(zSets.count(
                org.mockito.Mockito.eq("ircs:work:v1:" + SearchSyncWorkTypes.RAW + ":inflight"),
                org.mockito.Mockito.eq(Double.NEGATIVE_INFINITY),
                org.mockito.Mockito.anyDouble())).thenReturn(3L);

        assertEquals(3L, queue.expiredInflightCount(SearchSyncWorkTypes.RAW));
    }

    @Test
    void hasOpenTaskChecksPendingAndInflightSets() {
        @SuppressWarnings("unchecked")
        ZSetOperations<String, String> zSets = org.mockito.Mockito.mock(ZSetOperations.class);
        when(redisTemplate.opsForZSet()).thenReturn(zSets);
        when(zSets.score("ircs:work:v1:" + SearchSyncWorkTypes.RAW + ":pending", "task-pending"))
                .thenReturn(1.0d);
        when(zSets.score("ircs:work:v1:" + SearchSyncWorkTypes.RAW + ":pending", "task-inflight"))
                .thenReturn(null);
        when(zSets.score("ircs:work:v1:" + SearchSyncWorkTypes.RAW + ":inflight", "task-inflight"))
                .thenReturn(2.0d);

        assertTrue(queue.hasOpenTask(SearchSyncWorkTypes.RAW, "task-pending"));
        assertTrue(queue.hasOpenTask(SearchSyncWorkTypes.RAW, "task-inflight"));
    }

    @Test
    void requeueDlqPassesReplayLimitAndQueueKeys() {
        queue.requeueDlq(AggregationWorkTypes.RAW_VIDEO, 7, 3);

        CapturedExecution execution = captureExecution();

        assertEquals(4, execution.keys().size());
        assertTrue(execution.keys().contains("ircs:work:v1:" + AggregationWorkTypes.RAW_VIDEO + ":dlq"));
        assertTrue(execution.keys().contains("ircs:work:v1:" + AggregationWorkTypes.RAW_VIDEO + ":pending"));
        assertTrue(execution.keys().contains("ircs:work:v1:" + AggregationWorkTypes.RAW_VIDEO + ":inflight"));
        assertEquals("7", execution.args()[1]);
        assertEquals("3", execution.args()[4]);
        assertEquals(AggregationWorkTypes.RAW_VIDEO, execution.args()[6]);
        assertTrue(execution.script().contains("'status', 'DLQ_REPLAY_EXHAUSTED'"));
        assertTrue(execution.script().contains("'lastError', 'DLQ_REPLAY_EXHAUSTED'"));
        assertTrue(execution.script().contains("'event', 'dlq_replay_exhausted'"));
        assertTrue(execution.script().contains("redis.call('ZADD', KEYS[1], ARGV[1], id)"));
    }

    private RuntimeWorkItem item(String taskType, String taskId) {
        return new RuntimeWorkItem(
                taskType,
                taskId,
                "submission-1",
                "aggregate-1",
                "v1",
                "{}",
                "PROCESSING",
                1,
                clock.instant(),
                clock.instant(),
                clock.instant(),
                clock.instant(),
                "worker-a",
                "");
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private CapturedExecution captureExecution() {
        ArgumentCaptor<DefaultRedisScript> scriptCaptor = ArgumentCaptor.forClass(DefaultRedisScript.class);
        ArgumentCaptor<List> keysCaptor = ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<Object[]> argsCaptor = ArgumentCaptor.forClass(Object[].class);
        verify(redisTemplate).execute(scriptCaptor.capture(), keysCaptor.capture(), argsCaptor.capture());
        return new CapturedExecution(
                scriptCaptor.getValue().getScriptAsString(),
                keysCaptor.getValue(),
                argsCaptor.getValue());
    }

    private void assertRateArgs(
            CapturedExecution execution,
            int expectedKeyCount,
            int expectedArgCount,
            String expectedMetricKey) {
        long bucketStart = RateMetricKeys.bucketStartMillis(clock.millis(), Duration.ofSeconds(10));
        assertEquals(expectedKeyCount, execution.keys().size());
        assertEquals(expectedArgCount, execution.args().length);
        assertTrue(execution.keys().contains(RateMetricKeys.bucketKey("ircs:metrics:rate", bucketStart)));
        assertTrue(execution.keys().contains(RateMetricKeys.bucketIndexKey("ircs:metrics:rate")));
        assertEquals(expectedMetricKey, execution.args()[expectedArgCount - 1]);
        assertTrue(List.of(execution.args()).contains(Long.toString(bucketStart)));
        assertTrue(List.of(execution.args()).contains(Long.toString(Duration.ofMinutes(30).toSeconds())));
        assertTrue(List.of(execution.args()).contains(Long.toString(Duration.ofMinutes(31).toMillis())));
    }

    private record CapturedExecution(String script, List<?> keys, Object[] args) {
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<WorkSubmissionGate> gateProvider(WorkSubmissionGate gate) {
        ObjectProvider<WorkSubmissionGate> provider = org.mockito.Mockito.mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(gate);
        return provider;
    }

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> provider(T value) {
        ObjectProvider<T> provider = org.mockito.Mockito.mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(value);
        when(provider.getIfUnique()).thenReturn(value);
        return provider;
    }
}
