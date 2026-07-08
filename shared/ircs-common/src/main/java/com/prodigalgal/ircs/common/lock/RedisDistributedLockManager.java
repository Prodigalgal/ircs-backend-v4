package com.prodigalgal.ircs.common.lock;

import com.prodigalgal.ircs.common.lease.ClusterLease;
import com.prodigalgal.ircs.common.lease.RedisClusterLeaseService;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

public class RedisDistributedLockManager implements DistributedLockManager, DistributedLockStatusProvider {

    private static final DefaultRedisScript<List> TIME_SLICE_SCRIPT = new DefaultRedisScript<>(
            """
            local key = KEYS[1]
            local index_key = KEYS[2]
            local now = tonumber(ARGV[1])
            local gap = tonumber(ARGV[2])
            local max_wait = tonumber(ARGV[3])
            local min_ttl = tonumber(ARGV[4])
            local last_scheduled = tonumber(redis.call('GET', key) or '0')
            local target_time = now
            if last_scheduled > now then
              target_time = last_scheduled
            end
            local wait_time = target_time - now
            if wait_time > max_wait then
              return {-1, wait_time, last_scheduled}
            end
            local next_scheduled = target_time + gap
            local ttl = math.max(min_ttl, next_scheduled - now + 60000)
            redis.call('SET', key, next_scheduled, 'PX', ttl)
            redis.call('SADD', index_key, key)
            return {1, wait_time, next_scheduled}
            """,
            List.class);

    private static final DefaultRedisScript<List> TOKEN_BUCKET_SCRIPT = new DefaultRedisScript<>(
            """
            local key = KEYS[1]
            local index_key = KEYS[2]
            local now = tonumber(ARGV[1])
            local capacity = tonumber(ARGV[2])
            local refill_tokens = tonumber(ARGV[3])
            local refill_period_ms = tonumber(ARGV[4])
            local permits = tonumber(ARGV[5])
            local ttl_ms = tonumber(ARGV[6])
            local values = redis.call('HMGET', key, 'tokens', 'updated_at')
            local tokens = tonumber(values[1])
            local updated_at = tonumber(values[2])
            if tokens == nil then
              tokens = capacity
              updated_at = now
            end
            if updated_at == nil then
              updated_at = now
            end
            local elapsed = math.max(0, now - updated_at)
            local intervals = math.floor(elapsed / refill_period_ms)
            if intervals > 0 then
              tokens = math.min(capacity, tokens + intervals * refill_tokens)
              updated_at = updated_at + intervals * refill_period_ms
            end
            local allowed = 0
            local retry_after = 0
            if tokens >= permits then
              tokens = tokens - permits
              allowed = 1
            else
              local missing = permits - tokens
              local needed_intervals = math.ceil(missing / refill_tokens)
              retry_after = math.max(1, needed_intervals * refill_period_ms - (now - updated_at))
            end
            redis.call('HMSET', key,
              'tokens', tokens,
              'updated_at', updated_at,
              'capacity', capacity,
              'refill_tokens', refill_tokens,
              'refill_period_ms', refill_period_ms,
              'permits', permits,
              'retry_after_ms', retry_after)
            redis.call('PEXPIRE', key, ttl_ms)
            redis.call('SADD', index_key, key)
            return {allowed, tokens, retry_after}
            """,
            List.class);

    private final StringRedisTemplate redisTemplate;
    private final RedisClusterLeaseService mutexLocks;
    private final Clock clock;
    private final DistributedLockMetrics metrics;

    public RedisDistributedLockManager(StringRedisTemplate redisTemplate) {
        this(redisTemplate, Clock.systemUTC(), DistributedLockMetrics.noop());
    }

    RedisDistributedLockManager(StringRedisTemplate redisTemplate, DistributedLockMetrics metrics) {
        this(redisTemplate, Clock.systemUTC(), metrics);
    }

    RedisDistributedLockManager(StringRedisTemplate redisTemplate, Clock clock) {
        this(redisTemplate, clock, DistributedLockMetrics.noop());
    }

    RedisDistributedLockManager(StringRedisTemplate redisTemplate, Clock clock, DistributedLockMetrics metrics) {
        if (redisTemplate == null) {
            throw new IllegalArgumentException("redisTemplate is required");
        }
        this.redisTemplate = redisTemplate;
        this.clock = clock == null ? Clock.systemUTC() : clock;
        this.metrics = metrics == null ? DistributedLockMetrics.noop() : metrics;
        this.mutexLocks = new RedisClusterLeaseService(redisTemplate, this.clock, RedisClusterLeaseService.DEFAULT_PREFIX);
    }

    @Override
    public Optional<ClusterLease> tryAcquire(String name, String ownerId, Duration ttl) {
        long started = System.nanoTime();
        try {
            Optional<ClusterLease> lease = mutexLocks.tryAcquire(name, ownerId, ttl);
            metrics.recordOperation("mutex", "acquire", lease.isPresent() ? "acquired" : "busy", elapsed(started));
            return lease;
        } catch (RuntimeException ex) {
            metrics.recordOperation("mutex", "acquire", "error", elapsed(started));
            throw ex;
        }
    }

    @Override
    public boolean renew(ClusterLease lease, Duration ttl) {
        long started = System.nanoTime();
        try {
            boolean renewed = mutexLocks.renew(lease, ttl);
            metrics.recordOperation("mutex", "renew", renewed ? "renewed" : "not_renewed", elapsed(started));
            return renewed;
        } catch (RuntimeException ex) {
            metrics.recordOperation("mutex", "renew", "error", elapsed(started));
            throw ex;
        }
    }

    @Override
    public boolean release(ClusterLease lease) {
        long started = System.nanoTime();
        try {
            boolean released = mutexLocks.release(lease);
            metrics.recordOperation("mutex", "release", released ? "released" : "not_released", elapsed(started));
            return released;
        } catch (RuntimeException ex) {
            metrics.recordOperation("mutex", "release", "error", elapsed(started));
            throw ex;
        }
    }

    @Override
    public DistributedLockProfile profileFor(DistributedLockBusinessType businessType) {
        return DistributedLockProfiles.profileFor(businessType);
    }

    @Override
    @SuppressWarnings("unchecked")
    public TimeSliceReservation reserveTimeSlice(TimeSliceReservationRequest request) {
        long started = System.nanoTime();
        Instant now = Instant.now(clock);
        try {
            List<Long> result = (List<Long>) redisTemplate.execute(
                    TIME_SLICE_SCRIPT,
                    List.of(request.key(), TrafficLimitKeys.ACTIVE_INDEX_KEY),
                    String.valueOf(now.toEpochMilli()),
                    String.valueOf(request.gap().toMillis()),
                    String.valueOf(request.maxWait().toMillis()),
                    String.valueOf(request.ttl().toMillis()));
            TimeSliceReservation reservation;
            String metricResult;
            if (result == null || result.size() < 3) {
                reservation = TimeSliceReservation.reserved(request.key(), Duration.ZERO, now, now.plus(request.gap()));
                metricResult = "fallback_reserved";
            } else {
                long status = valueAt(result, 0);
                long waitMs = Math.max(0L, valueAt(result, 1));
                long nextAvailableMs = Math.max(now.toEpochMilli(), valueAt(result, 2));
                Instant nextAvailableAt = Instant.ofEpochMilli(nextAvailableMs);
                if (status < 0) {
                    reservation = TimeSliceReservation.rejected(
                            request.key(), Duration.ofMillis(waitMs), now, nextAvailableAt);
                    metricResult = "rejected";
                } else {
                    reservation = TimeSliceReservation.reserved(
                            request.key(), Duration.ofMillis(waitMs), now, nextAvailableAt);
                    metricResult = "reserved";
                }
            }
            metrics.recordOperation("time_slice", "reserve", metricResult, elapsed(started));
            metrics.recordWait("time_slice", metricResult, reservation.waitTime());
            recordTrafficUsage(request.key(), "TIME_SLICE", metricResult, request.ttl(), Map.of(
                    "gapMs", String.valueOf(request.gap().toMillis()),
                    "maxWaitMs", String.valueOf(request.maxWait().toMillis()),
                    "waitMs", String.valueOf(reservation.waitTime().toMillis()),
                    "nextAvailableAt", reservation.nextAvailableAt().toString()));
            return reservation;
        } catch (RuntimeException ex) {
            metrics.recordOperation("time_slice", "reserve", "error", elapsed(started));
            recordTrafficUsage(request.key(), "TIME_SLICE", "error", request.ttl(), Map.of(
                    "gapMs", String.valueOf(request.gap().toMillis()),
                    "maxWaitMs", String.valueOf(request.maxWait().toMillis()),
                    "error", ex.getClass().getSimpleName()));
            throw ex;
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public TokenBucketReservation reserveTokenBucket(TokenBucketReservationRequest request) {
        long started = System.nanoTime();
        Instant now = Instant.now(clock);
        try {
            List<Long> result = (List<Long>) redisTemplate.execute(
                    TOKEN_BUCKET_SCRIPT,
                    List.of(request.key(), TrafficLimitKeys.ACTIVE_INDEX_KEY),
                    String.valueOf(now.toEpochMilli()),
                    String.valueOf(request.capacity()),
                    String.valueOf(request.refillTokens()),
                    String.valueOf(request.refillPeriod().toMillis()),
                    String.valueOf(request.permits()),
                    String.valueOf(request.ttl().toMillis()));
            TokenBucketReservation reservation;
            String metricResult;
            if (result == null || result.size() < 3) {
                reservation = TokenBucketReservation.allowed(
                        request.key(), Math.max(0, request.capacity() - request.permits()), now);
                metricResult = "fallback_allowed";
            } else {
                boolean allowed = valueAt(result, 0) == 1L;
                long remaining = Math.max(0L, valueAt(result, 1));
                Duration retryAfter = Duration.ofMillis(Math.max(0L, valueAt(result, 2)));
                if (allowed) {
                    reservation = TokenBucketReservation.allowed(request.key(), remaining, now);
                    metricResult = "allowed";
                } else if (retryAfter.compareTo(request.maxWait()) > 0) {
                    reservation = TokenBucketReservation.rejected(request.key(), remaining, retryAfter, now);
                    metricResult = "rejected";
                } else {
                    reservation = TokenBucketReservation.waiting(request.key(), remaining, retryAfter, now);
                    metricResult = "waiting";
                }
            }
            metrics.recordOperation("token_bucket", "reserve", metricResult, elapsed(started));
            metrics.recordWait("token_bucket", metricResult, reservation.retryAfter());
            recordTrafficUsage(request.key(), "TOKEN_BUCKET", metricResult, request.ttl(), Map.of(
                    "capacity", String.valueOf(request.capacity()),
                    "permits", String.valueOf(request.permits()),
                    "remainingPermits", String.valueOf(reservation.remainingTokens()),
                    "retryAfterMs", String.valueOf(reservation.retryAfter().toMillis())));
            return reservation;
        } catch (RuntimeException ex) {
            metrics.recordOperation("token_bucket", "reserve", "error", elapsed(started));
            recordTrafficUsage(request.key(), "TOKEN_BUCKET", "error", request.ttl(), Map.of(
                    "capacity", String.valueOf(request.capacity()),
                    "permits", String.valueOf(request.permits()),
                    "error", ex.getClass().getSimpleName()));
            throw ex;
        }
    }

    @Override
    public DistributedLockBackendStatus backendStatus() {
        RedisConnectionFactory connectionFactory = redisTemplate.getConnectionFactory();
        if (connectionFactory == null) {
            return DistributedLockBackendStatus.unavailable("redis", "Redis connection factory is unavailable");
        }
        RedisConnection connection = null;
        try {
            connection = connectionFactory.getConnection();
            String pong = connection.ping();
            if ("PONG".equalsIgnoreCase(pong)) {
                return DistributedLockBackendStatus.available("redis");
            }
            return DistributedLockBackendStatus.unavailable("redis", "Unexpected Redis PING response: " + pong);
        } catch (RuntimeException ex) {
            return DistributedLockBackendStatus.unavailable(
                    "redis",
                    ex.getClass().getSimpleName() + ": " + ex.getMessage());
        } finally {
            if (connection != null) {
                try {
                    connection.close();
                } catch (RuntimeException ignored) {
                    // Health must report the ping result; close failures are handled by the Redis client pool.
                }
            }
        }
    }

    private static long valueAt(List<Long> result, int index) {
        Long value = result.get(index);
        return value == null ? 0L : value;
    }

    private void recordTrafficUsage(String trafficKey, String limiterType, String result, Duration ttl, Map<String, String> fields) {
        try {
            if (!TrafficLimitKeys.isCurrentTrafficKey(trafficKey)) {
                return;
            }
            TrafficLimitKeys.TrafficKeyDescription description = TrafficLimitKeys.describe(trafficKey);
            Map<String, String> values = new HashMap<>();
            values.put("key", TrafficLimitKeys.stripPrefix(trafficKey));
            values.put("redisKey", trafficKey);
            values.put("limiterType", limiterType);
            values.put("business", description.business());
            values.put("scope", description.scope());
            values.put("target", nullToEmpty(description.target()));
            values.put("egressIdentity", nullToEmpty(description.egressIdentity()));
            values.put("displayName", description.displayName());
            values.put("lastResult", result);
            values.put("lastObservedAt", Instant.now(clock).toString());
            values.putAll(fields);

            String metaKey = TrafficLimitKeys.metaKey(trafficKey);
            redisTemplate.opsForHash().putAll(metaKey, values);
            redisTemplate.opsForHash().increment(metaKey, counterField(result), 1L);
            redisTemplate.opsForHash().increment(metaKey, "totalRequests", 1L);
            redisTemplate.expire(metaKey, metaTtl(ttl));
        } catch (RuntimeException ignored) {
            // Traffic metadata is best-effort; limiter correctness must not depend on observability writes.
        }
    }

    private static String counterField(String result) {
        return switch (result) {
            case "reserved", "fallback_reserved", "allowed", "fallback_allowed" -> "allowedCount";
            case "waiting" -> "waitingCount";
            case "rejected" -> "rejectedCount";
            case "error" -> "errorCount";
            default -> "otherCount";
        };
    }

    private static Duration metaTtl(Duration ttl) {
        Duration base = ttl == null || !ttl.isPositive() ? Duration.ofMinutes(10) : ttl;
        return base.plus(Duration.ofHours(1));
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static Duration elapsed(long startedNanos) {
        return Duration.ofNanos(Math.max(0L, System.nanoTime() - startedNanos));
    }
}
