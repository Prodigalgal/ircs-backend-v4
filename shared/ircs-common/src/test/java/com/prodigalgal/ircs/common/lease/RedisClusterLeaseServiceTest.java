package com.prodigalgal.ircs.common.lease;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;

class RedisClusterLeaseServiceTest {

    private final StringRedisTemplate redisTemplate = org.mockito.Mockito.mock(StringRedisTemplate.class);
    private final ValueOperations<String, String> valueOperations = org.mockito.Mockito.mock(ValueOperations.class);
    private final Clock clock = Clock.fixed(Instant.parse("2026-06-11T12:00:00Z"), ZoneOffset.UTC);
    private final RedisClusterLeaseService service =
            new RedisClusterLeaseService(redisTemplate, clock, "ircs:test-lease:");

    @Test
    void tryAcquireUsesSetNxWithTtlAndOwnerToken() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent(
                eq("ircs:test-lease:ops-maintenance-scheduler"),
                anyString(),
                eq(Duration.ofMinutes(10))))
                .thenReturn(true);

        Optional<ClusterLease> lease = service.tryAcquire(
                "ops-maintenance-scheduler",
                "ircs-ops-service@pod-1#42",
                Duration.ofMinutes(10));

        assertThat(lease).isPresent();
        assertThat(lease.orElseThrow().ownerId()).isEqualTo("ircs-ops-service@pod-1#42");
        assertThat(lease.orElseThrow().token()).startsWith("ircs-ops-service@pod-1#42:");
        assertThat(lease.orElseThrow().acquiredAt()).isEqualTo(Instant.parse("2026-06-11T12:00:00Z"));
        assertThat(lease.orElseThrow().expiresAt()).isEqualTo(Instant.parse("2026-06-11T12:10:00Z"));
    }

    @Test
    void tryLockAliasesTryAcquireForDistributedLockCallers() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent(
                eq("ircs:test-lease:provider-smoke"),
                anyString(),
                eq(Duration.ofSeconds(30))))
                .thenReturn(true);

        Optional<ClusterLease> lease = service.tryLock(
                "provider-smoke",
                "ircs-metadata-worker@pod-1#7",
                Duration.ofSeconds(30));

        assertThat(lease).isPresent();
        assertThat(lease.orElseThrow().name()).isEqualTo("provider-smoke");
        assertThat(lease.orElseThrow().ownerId()).isEqualTo("ircs-metadata-worker@pod-1#7");
    }

    @Test
    void tryAcquireReturnsEmptyWhenRedisSetNxFails() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(false);

        Optional<ClusterLease> lease = service.tryAcquire("ops-maintenance-scheduler", "owner", Duration.ofSeconds(30));

        assertThat(lease).isEmpty();
    }

    @Test
    void releaseDeletesOnlyMatchingToken() {
        ClusterLease lease = new ClusterLease(
                "ops-maintenance-scheduler",
                "owner",
                "owner:token",
                Instant.parse("2026-06-11T12:00:00Z"),
                Instant.parse("2026-06-11T12:10:00Z"));
        when(redisTemplate.execute(any(RedisScript.class), anyList(), any())).thenReturn(1L);

        assertThat(service.release(lease)).isTrue();

        ArgumentCaptor<RedisScript<Long>> scriptCaptor = ArgumentCaptor.forClass(RedisScript.class);
        verify(redisTemplate).execute(
                scriptCaptor.capture(),
                eq(java.util.List.of("ircs:test-lease:ops-maintenance-scheduler")),
                eq("owner:token"));
        assertThat(scriptCaptor.getValue().getScriptAsString()).contains("redis.call('get', KEYS[1]) == ARGV[1]");
    }

    @Test
    void renewExtendsOnlyMatchingToken() {
        ClusterLease lease = new ClusterLease(
                "ops-maintenance-scheduler",
                "owner",
                "owner:token",
                Instant.parse("2026-06-11T12:00:00Z"),
                Instant.parse("2026-06-11T12:10:00Z"));
        when(redisTemplate.execute(any(RedisScript.class), anyList(), any(), any())).thenReturn(1L);

        assertThat(service.renew(lease, Duration.ofSeconds(45))).isTrue();

        verify(redisTemplate).execute(
                any(RedisScript.class),
                eq(java.util.List.of("ircs:test-lease:ops-maintenance-scheduler")),
                eq("owner:token"),
                eq("45000"));
    }

    @Test
    void rejectsInvalidLeaseTtl() {
        assertThatThrownBy(() -> service.tryAcquire("ops-maintenance-scheduler", "owner", Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ttl");
    }
}
