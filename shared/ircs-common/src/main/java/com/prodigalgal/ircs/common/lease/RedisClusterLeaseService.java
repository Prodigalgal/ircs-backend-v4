package com.prodigalgal.ircs.common.lease;

import com.prodigalgal.ircs.common.lock.DistributedLockService;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.util.StringUtils;

public class RedisClusterLeaseService implements DistributedLockService {

    public static final String DEFAULT_PREFIX = "ircs:cluster-lease:";

    private static final DefaultRedisScript<Long> RELEASE_SCRIPT = new DefaultRedisScript<>(
            """
            if redis.call('get', KEYS[1]) == ARGV[1] then
              return redis.call('del', KEYS[1])
            end
            return 0
            """,
            Long.class);
    private static final DefaultRedisScript<Long> RENEW_SCRIPT = new DefaultRedisScript<>(
            """
            if redis.call('get', KEYS[1]) == ARGV[1] then
              return redis.call('pexpire', KEYS[1], ARGV[2])
            end
            return 0
            """,
            Long.class);

    private final StringRedisTemplate redisTemplate;
    private final Clock clock;
    private final String keyPrefix;

    public RedisClusterLeaseService(StringRedisTemplate redisTemplate) {
        this(redisTemplate, Clock.systemUTC(), DEFAULT_PREFIX);
    }

    public RedisClusterLeaseService(StringRedisTemplate redisTemplate, Clock clock, String keyPrefix) {
        if (redisTemplate == null) {
            throw new IllegalArgumentException("redisTemplate is required");
        }
        this.redisTemplate = redisTemplate;
        this.clock = clock == null ? Clock.systemUTC() : clock;
        this.keyPrefix = StringUtils.hasText(keyPrefix) ? keyPrefix : DEFAULT_PREFIX;
    }

    @Override
    public Optional<ClusterLease> tryAcquire(String name, String ownerId, Duration ttl) {
        String normalizedName = normalize(name, "lease name");
        String normalizedOwner = normalize(ownerId, "lease ownerId");
        Duration normalizedTtl = normalizeTtl(ttl);
        Instant acquiredAt = Instant.now(clock);
        String token = normalizedOwner + ":" + UUID.randomUUID();
        Boolean acquired = redisTemplate.opsForValue()
                .setIfAbsent(redisKey(normalizedName), token, normalizedTtl);
        if (!Boolean.TRUE.equals(acquired)) {
            return Optional.empty();
        }
        return Optional.of(new ClusterLease(
                normalizedName,
                normalizedOwner,
                token,
                acquiredAt,
                acquiredAt.plus(normalizedTtl)));
    }

    @Override
    public boolean renew(ClusterLease lease, Duration ttl) {
        if (lease == null) {
            return false;
        }
        Duration normalizedTtl = normalizeTtl(ttl);
        Long renewed = redisTemplate.execute(
                RENEW_SCRIPT,
                List.of(redisKey(lease.name())),
                lease.token(),
                String.valueOf(normalizedTtl.toMillis()));
        return Long.valueOf(1L).equals(renewed);
    }

    @Override
    public boolean release(ClusterLease lease) {
        if (lease == null) {
            return false;
        }
        Long released = redisTemplate.execute(
                RELEASE_SCRIPT,
                List.of(redisKey(lease.name())),
                lease.token());
        return Long.valueOf(1L).equals(released);
    }

    private String redisKey(String name) {
        return keyPrefix + name;
    }

    private static String normalize(String value, String field) {
        if (!StringUtils.hasText(value)) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value.trim();
    }

    private static Duration normalizeTtl(Duration ttl) {
        if (ttl == null || ttl.isZero() || ttl.isNegative()) {
            throw new IllegalArgumentException("lease ttl must be positive");
        }
        return ttl;
    }
}
