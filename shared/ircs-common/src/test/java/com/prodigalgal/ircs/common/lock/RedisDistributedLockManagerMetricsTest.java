package com.prodigalgal.ircs.common.lock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;

class RedisDistributedLockManagerMetricsTest {

    private final StringRedisTemplate redisTemplate = Mockito.mock(StringRedisTemplate.class);
    private final Clock clock = Clock.fixed(Instant.parse("2026-06-11T12:00:00Z"), ZoneOffset.UTC);
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final RedisDistributedLockManager manager =
            new RedisDistributedLockManager(redisTemplate, clock, new DistributedLockMetrics(registry));

    @Test
    void recordsMutexAcquireBusyResultWithoutKeyCardinality() {
        ValueOperations<String, String> valueOperations = Mockito.mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(false);

        assertThat(manager.tryAcquire("lock:normalization", "worker-1", Duration.ofMinutes(1))).isEmpty();

        assertThat(registry.counter(
                        DistributedLockMetrics.OPERATIONS,
                        "lock_type", "mutex",
                        "operation", "acquire",
                        "result", "busy")
                .count()).isEqualTo(1.0);
        assertThat(registry.find(DistributedLockMetrics.OPERATIONS).tag("name", "lock:normalization").counter())
                .isNull();
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void recordsTimeSliceRejectedAndWaitDuration() {
        when(redisTemplate.execute(
                any(RedisScript.class),
                eq(List.of("traffic:limit:DataSource:Scraper:Ip:203.0.113.10:source-1", TrafficLimitKeys.ACTIVE_INDEX_KEY)),
                any(),
                any(),
                any(),
                any()))
                .thenReturn((List) List.of(-1L, 10_000L, Instant.parse("2026-06-11T12:00:10Z").toEpochMilli()));

        TimeSliceReservation reservation = manager.reserveTimeSlice(new TimeSliceReservationRequest(
                "traffic:limit:DataSource:Scraper:Ip:203.0.113.10:source-1",
                Duration.ofSeconds(2),
                Duration.ofSeconds(5),
                Duration.ofMinutes(10)));

        assertThat(reservation.rejected()).isTrue();
        assertThat(registry.counter(
                        DistributedLockMetrics.OPERATIONS,
                        "lock_type", "time_slice",
                        "operation", "reserve",
                        "result", "rejected")
                .count()).isEqualTo(1.0);
        assertThat(registry.timer(
                        DistributedLockMetrics.WAIT,
                        "lock_type", "time_slice",
                        "result", "rejected")
                .totalTime(java.util.concurrent.TimeUnit.MILLISECONDS)).isEqualTo(10_000.0);
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void recordsTokenBucketWaitingResult() {
        when(redisTemplate.execute(
                any(RedisScript.class),
                eq(List.of("traffic:limit:cred:tmdb-1", TrafficLimitKeys.ACTIVE_INDEX_KEY)),
                any(),
                any(),
                any(),
                any(),
                any(),
                any()))
                .thenReturn((List) List.of(0L, 0L, 2_000L));

        TokenBucketReservation reservation = manager.reserveTokenBucket(new TokenBucketReservationRequest(
                "traffic:limit:cred:tmdb-1",
                5,
                1,
                Duration.ofSeconds(1),
                1,
                Duration.ofSeconds(5),
                Duration.ofMinutes(10)));

        assertThat(reservation.allowed()).isFalse();
        assertThat(reservation.rejected()).isFalse();
        assertThat(registry.counter(
                        DistributedLockMetrics.OPERATIONS,
                        "lock_type", "token_bucket",
                        "operation", "reserve",
                        "result", "waiting")
                .count()).isEqualTo(1.0);
    }
}
