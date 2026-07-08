package com.prodigalgal.ircs.common.lock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

class RedisDistributedLockManagerTest {

    private final StringRedisTemplate redisTemplate = org.mockito.Mockito.mock(StringRedisTemplate.class);
    private final Clock clock = Clock.fixed(Instant.parse("2026-06-11T12:00:00Z"), ZoneOffset.UTC);
    private final RedisDistributedLockManager manager = new RedisDistributedLockManager(redisTemplate, clock);

    @Test
    void profilesRouteBusinessTypesToExpectedLockTypes() {
        assertThat(manager.profileFor(DistributedLockBusinessType.SINGLETON_RUNNER).lockType())
                .isEqualTo(DistributedLockType.MUTEX_LEASE);
        assertThat(manager.profileFor(DistributedLockBusinessType.DATA_SOURCE_SCRAPE).lockType())
                .isEqualTo(DistributedLockType.TIME_SLICE_RESERVATION);
        assertThat(manager.profileFor(DistributedLockBusinessType.CREDENTIAL_API_CALL).lockType())
                .isEqualTo(DistributedLockType.TOKEN_BUCKET);
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void reserveTimeSliceReturnsReservedWaitAndNextAvailableTime() {
        when(redisTemplate.execute(
                any(RedisScript.class),
                eq(List.of("traffic:limit:Provider:Metadata:Ip:203.0.113.10:DOUBAN", TrafficLimitKeys.ACTIVE_INDEX_KEY)),
                any(),
                any(),
                any(),
                any()))
                .thenReturn((List) List.of(1L, 250L, Instant.parse("2026-06-11T12:00:02Z").toEpochMilli()));

        TimeSliceReservation reservation = manager.reserveTimeSlice(new TimeSliceReservationRequest(
                "traffic:limit:Provider:Metadata:Ip:203.0.113.10:DOUBAN",
                Duration.ofSeconds(2),
                Duration.ofMinutes(1),
                Duration.ofMinutes(10)));

        assertThat(reservation.reserved()).isTrue();
        assertThat(reservation.rejected()).isFalse();
        assertThat(reservation.waitTime()).isEqualTo(Duration.ofMillis(250));
        assertThat(reservation.nextAvailableAt()).isEqualTo(Instant.parse("2026-06-11T12:00:02Z"));
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void reserveTimeSliceRejectsWhenQueueWaitExceedsMaxWait() {
        when(redisTemplate.execute(
                any(RedisScript.class),
                eq(List.of("traffic:limit:DataSource:Scraper:Ip:203.0.113.10:source-1", TrafficLimitKeys.ACTIVE_INDEX_KEY)),
                any(),
                any(),
                any(),
                any()))
                .thenReturn((List) List.of(-1L, 120_001L, Instant.parse("2026-06-11T12:02:01Z").toEpochMilli()));

        TimeSliceReservation reservation = manager.reserveTimeSlice(new TimeSliceReservationRequest(
                "traffic:limit:DataSource:Scraper:Ip:203.0.113.10:source-1",
                Duration.ofSeconds(2),
                Duration.ofMinutes(2),
                Duration.ofMinutes(10)));

        assertThat(reservation.reserved()).isFalse();
        assertThat(reservation.rejected()).isTrue();
        assertThat(reservation.waitTime()).isEqualTo(Duration.ofMillis(120_001));
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void reserveTokenBucketAllowsWhenTokensAreAvailable() {
        when(redisTemplate.execute(
                any(RedisScript.class),
                eq(List.of("traffic:limit:cred:tmdb-1", TrafficLimitKeys.ACTIVE_INDEX_KEY)),
                any(),
                any(),
                any(),
                any(),
                any(),
                any()))
                .thenReturn((List) List.of(1L, 4L, 0L));

        TokenBucketReservation reservation = manager.reserveTokenBucket(new TokenBucketReservationRequest(
                "traffic:limit:cred:tmdb-1",
                5,
                1,
                Duration.ofSeconds(1),
                1,
                Duration.ofSeconds(5),
                Duration.ofMinutes(10)));

        assertThat(reservation.allowed()).isTrue();
        assertThat(reservation.rejected()).isFalse();
        assertThat(reservation.remainingTokens()).isEqualTo(4);
        assertThat(reservation.retryAfter()).isZero();
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void reserveTokenBucketRejectsWhenRetryAfterExceedsMaxWait() {
        when(redisTemplate.execute(
                any(RedisScript.class),
                eq(List.of("traffic:limit:cred:tmdb-1", TrafficLimitKeys.ACTIVE_INDEX_KEY)),
                any(),
                any(),
                any(),
                any(),
                any(),
                any()))
                .thenReturn((List) List.of(0L, 0L, 6_000L));

        TokenBucketReservation reservation = manager.reserveTokenBucket(new TokenBucketReservationRequest(
                "traffic:limit:cred:tmdb-1",
                5,
                1,
                Duration.ofSeconds(1),
                1,
                Duration.ofSeconds(5),
                Duration.ofMinutes(10)));

        assertThat(reservation.allowed()).isFalse();
        assertThat(reservation.rejected()).isTrue();
        assertThat(reservation.retryAfter()).isEqualTo(Duration.ofSeconds(6));
    }
}
