package com.prodigalgal.ircs.ops.dashboard.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import com.prodigalgal.ircs.common.config.RuntimeConfigService;
import com.prodigalgal.ircs.common.metrics.RateMetricKeys;
import com.prodigalgal.ircs.ops.dashboard.dto.RateMetricResponse;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;

class RateMetricSnapshotReaderTest {

    private final StringRedisTemplate redisTemplate = org.mockito.Mockito.mock(StringRedisTemplate.class);
    private final RuntimeConfigService runtimeConfig = org.mockito.Mockito.mock(RuntimeConfigService.class);
    private final RateMetricSnapshotReader reader = new RateMetricSnapshotReader(redisTemplate, runtimeConfig);

    @BeforeEach
    void setUp() {
        when(runtimeConfig.stringValue("app.rate-metrics.key-prefix", RateMetricKeys.DEFAULT_KEY_PREFIX))
                .thenReturn(RateMetricKeys.DEFAULT_KEY_PREFIX);
        when(runtimeConfig.positiveDurationValue("app.rate-metrics.bucket-size", RateMetricKeys.DEFAULT_BUCKET_SIZE))
                .thenReturn(RateMetricKeys.DEFAULT_BUCKET_SIZE);
        when(runtimeConfig.positiveDurationValue("app.ops.metrics.instant-window", Duration.ofSeconds(60)))
                .thenReturn(Duration.ofSeconds(60));
        when(runtimeConfig.positiveDurationValue("app.ops.metrics.stable-window", Duration.ofMinutes(5)))
                .thenReturn(Duration.ofMinutes(5));
        when(runtimeConfig.positiveDurationValue("app.ops.metrics.consumer-no-progress-grace", Duration.ofMinutes(10)))
                .thenReturn(Duration.ofMinutes(10));
        when(runtimeConfig.doubleValue("app.ops.metrics.ewma-alpha", 0.25d)).thenReturn(0.25d);
    }

    @Test
    void readsIndexedBucketsIntoRateSnapshot() {
        stubIndexedBucket(Map.of("demo.metric", "3"));

        RateMetricResponse response = reader.currentSnapshot().metric("demo.metric", "Demo");

        assertEquals(3, response.instantCount());
        assertEquals(3, response.stableCount());
        assertEquals(3, response.instantTpm());
        assertTrue(response.stableTpm() > 0);
    }

    @Test
    void fallsBackToEmptySnapshotWhenRedisReadFails() {
        @SuppressWarnings("unchecked")
        ZSetOperations<String, String> zSetOps = org.mockito.Mockito.mock(ZSetOperations.class);
        when(redisTemplate.opsForZSet()).thenReturn(zSetOps);
        when(zSetOps.rangeByScore(anyString(), anyDouble(), anyDouble()))
                .thenThrow(new IllegalStateException("redis unavailable"));

        RateMetricResponse response = reader.currentSnapshot().metric("demo.metric", "Demo");

        assertEquals(0, response.instantCount());
        assertEquals(0, response.stableCount());
        assertTrue(response.stale());
    }

    @SuppressWarnings("unchecked")
    private void stubIndexedBucket(Map<Object, Object> counts) {
        HashOperations<String, Object, Object> hashOps = org.mockito.Mockito.mock(HashOperations.class);
        ZSetOperations<String, String> zSetOps = org.mockito.Mockito.mock(ZSetOperations.class);
        long bucketMillis = RateMetricKeys.DEFAULT_BUCKET_SIZE.toMillis();
        long currentBucket = RateMetricKeys.bucketStartMillis(System.currentTimeMillis(), RateMetricKeys.DEFAULT_BUCKET_SIZE);
        Set<String> indexedBuckets = Set.of(
                Long.toString(currentBucket - bucketMillis),
                Long.toString(currentBucket),
                Long.toString(currentBucket + bucketMillis));
        AtomicBoolean emitted = new AtomicBoolean(false);

        when(redisTemplate.opsForZSet()).thenReturn(zSetOps);
        when(redisTemplate.opsForHash()).thenReturn(hashOps);
        when(zSetOps.rangeByScore(anyString(), anyDouble(), anyDouble()))
                .thenReturn(indexedBuckets);
        when(hashOps.entries(anyString())).thenAnswer(invocation -> emitted.compareAndSet(false, true)
                ? counts
                : Map.of());
    }
}
