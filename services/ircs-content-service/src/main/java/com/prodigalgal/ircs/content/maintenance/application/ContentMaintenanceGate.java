package com.prodigalgal.ircs.content.maintenance.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.prodigalgal.ircs.common.maintenance.MaintenanceGateDecision;
import com.prodigalgal.ircs.common.maintenance.MaintenanceGateLockedException;
import com.prodigalgal.ircs.common.maintenance.MaintenanceWriteGateInspector;
import com.prodigalgal.ircs.contracts.maintenance.MaintenanceGateChangedEvent;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
@Slf4j
public class ContentMaintenanceGate {

    private static final String OWNER_SERVICE = "content-service";
    private static final String RESOURCE_VIDEO = "video";
    private static final String RESOURCE_RAW_VIDEO = "raw-video";
    private static final String RESOURCE_UNIFIED_VIDEO = "unified-video";
    private static final Duration DEFAULT_BLOCKED_CACHE_TTL = Duration.ofSeconds(5);
    private static final String REDIS_PREFIX = "ircs:maintenance:gate:block:" + OWNER_SERVICE + ":";

    private final MaintenanceWriteGateInspector inspector;
    private final ObjectMapper objectMapper;
    private final StringRedisTemplate redisTemplate;
    private final Duration blockedCacheTtl;
    private final ConcurrentMap<CacheKey, CachedBlockedDecision> blockedDecisionCache = new ConcurrentHashMap<>();
    private final AtomicLong lastInvalidationRevision = new AtomicLong(-1);

    public ContentMaintenanceGate(
            JdbcTemplate jdbcTemplate,
            ObjectMapper objectMapper,
            ObjectProvider<StringRedisTemplate> redisTemplate) {
        this.inspector = new MaintenanceWriteGateInspector(jdbcTemplate);
        this.objectMapper = objectMapper == null
                ? JsonMapper.builder().findAndAddModules().build()
                : objectMapper;
        this.redisTemplate = redisTemplate == null ? null : redisTemplate.getIfAvailable();
        this.blockedCacheTtl = DEFAULT_BLOCKED_CACHE_TTL;
    }

    public void assertRawVideoWrite(UUID id) {
        assertWrite(RESOURCE_VIDEO, scope(id));
        assertWrite(RESOURCE_RAW_VIDEO, scope(id));
    }

    public void assertUnifiedVideoWrite(UUID id) {
        assertWrite(RESOURCE_VIDEO, scope(id));
        assertWrite(RESOURCE_UNIFIED_VIDEO, scope(id));
    }

    private void assertWrite(String resourceType, String resourceScope) {
        MaintenanceGateDecision decision = checkWrite(resourceType, resourceScope);
        if (!decision.allowed()) {
            throw new MaintenanceGateLockedException(decision);
        }
    }

    MaintenanceGateDecision checkWrite(String resourceType, String resourceScope) {
        CacheKey key = new CacheKey(resourceType, resourceScope);
        Instant now = Instant.now();
        MaintenanceGateDecision localDecision = localBlocked(key, now);
        if (localDecision != null) {
            return localDecision;
        }
        MaintenanceGateDecision redisDecision = redisBlocked(key, now);
        if (redisDecision != null) {
            cacheLocal(key, redisDecision, now);
            return redisDecision;
        }
        MaintenanceGateDecision decision = inspector.checkWrite(OWNER_SERVICE, resourceType, resourceScope);
        if (!decision.allowed()) {
            cacheBlocked(key, decision, now);
        }
        return decision;
    }

    public void invalidate(MaintenanceGateChangedEvent event) {
        if (event == null || !appliesToContent(event.ownerService())) {
            return;
        }
        blockedDecisionCache.clear();
        evictRedis(event);
        lastInvalidationRevision.updateAndGet(current -> Math.max(current, event.revision()));
    }

    long lastInvalidationRevision() {
        return lastInvalidationRevision.get();
    }

    int blockedDecisionCacheSize() {
        return blockedDecisionCache.size();
    }

    private MaintenanceGateDecision localBlocked(CacheKey key, Instant now) {
        CachedBlockedDecision cached = blockedDecisionCache.get(key);
        if (cached == null) {
            return null;
        }
        if (cached.expiresAt().isAfter(now)) {
            return cached.decision();
        }
        blockedDecisionCache.remove(key, cached);
        return null;
    }

    private MaintenanceGateDecision redisBlocked(CacheKey key, Instant now) {
        if (redisTemplate == null) {
            return null;
        }
        try {
            String raw = redisTemplate.opsForValue().get(redisKey(key));
            if (raw == null || raw.isBlank()) {
                return null;
            }
            MaintenanceGateDecision decision = objectMapper.readValue(raw, MaintenanceGateDecision.class);
            if (decision.allowed() || decision.expiresAt() == null || !decision.expiresAt().isAfter(now)) {
                redisTemplate.delete(redisKey(key));
                return null;
            }
            return decision;
        } catch (Exception ex) {
            log.warn("Failed to read maintenance gate blocked decision from Redis; falling back to DB", ex);
            return null;
        }
    }

    private void cacheBlocked(CacheKey key, MaintenanceGateDecision decision, Instant now) {
        cacheLocal(key, decision, now);
        cacheRedis(key, decision, now);
    }

    private void cacheLocal(CacheKey key, MaintenanceGateDecision decision, Instant now) {
        Duration ttl = ttl(decision, now);
        if (!ttl.isPositive()) {
            return;
        }
        blockedDecisionCache.put(key, new CachedBlockedDecision(decision, now.plus(ttl)));
    }

    private void cacheRedis(CacheKey key, MaintenanceGateDecision decision, Instant now) {
        if (redisTemplate == null) {
            return;
        }
        Duration ttl = ttl(decision, now);
        if (!ttl.isPositive()) {
            return;
        }
        try {
            redisTemplate.opsForValue().set(redisKey(key), objectMapper.writeValueAsString(decision), ttl);
        } catch (Exception ex) {
            log.warn("Failed to write maintenance gate blocked decision to Redis; DB remains authoritative", ex);
        }
    }

    private Duration ttl(MaintenanceGateDecision decision, Instant now) {
        if (decision == null || decision.allowed() || decision.expiresAt() == null) {
            return Duration.ZERO;
        }
        Duration gateTtl = Duration.between(now, decision.expiresAt());
        if (!gateTtl.isPositive()) {
            return Duration.ZERO;
        }
        return gateTtl.compareTo(blockedCacheTtl) < 0 ? gateTtl : blockedCacheTtl;
    }

    private void evictRedis(MaintenanceGateChangedEvent event) {
        if (redisTemplate == null) {
            return;
        }
        try {
            if (MaintenanceGateChangedEvent.ALL_SCOPE.equals(event.resourceScope())) {
                evictRedisPrefix();
                return;
            }
            redisTemplate.delete(redisKey(new CacheKey(event.resourceType(), event.resourceScope())));
        } catch (Exception ex) {
            log.warn("Failed to evict maintenance gate blocked decision from Redis", ex);
        }
    }

    private void evictRedisPrefix() {
        try (var cursor = redisTemplate.scan(ScanOptions.scanOptions()
                .match(REDIS_PREFIX + "*")
                .count(100)
                .build())) {
            while (cursor.hasNext()) {
                redisTemplate.delete(cursor.next());
            }
        } catch (Exception ex) {
            log.warn("Failed to scan Redis maintenance gate blocked cache for eviction", ex);
        }
    }

    private static String scope(UUID id) {
        return id == null ? "*" : id.toString();
    }

    private static boolean appliesToContent(String ownerService) {
        if (ownerService == null) {
            return false;
        }
        String normalized = ownerService.trim().toLowerCase(Locale.ROOT);
        return OWNER_SERVICE.equals(normalized) || MaintenanceGateChangedEvent.ALL_OWNER_SERVICES.equals(normalized);
    }

    private static String redisKey(CacheKey key) {
        return REDIS_PREFIX + key.resourceType() + ":" + key.resourceScope();
    }

    private record CacheKey(String resourceType, String resourceScope) {
    }

    private record CachedBlockedDecision(MaintenanceGateDecision decision, Instant expiresAt) {
    }
}
