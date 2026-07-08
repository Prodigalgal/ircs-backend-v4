package com.prodigalgal.ircs.common.config;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.StringUtils;

@Slf4j
public class SystemConfigValkeyCache {

    public static final String DEFAULT_KEY_PREFIX = "ircs:system-config:v1";
    public static final Duration DEFAULT_TTL = Duration.ofHours(12);
    public static final Duration DEFAULT_LOCAL_TTL = Duration.ofMinutes(5);

    private final ObjectProvider<StringRedisTemplate> redisTemplateProvider;
    private final String keyPrefix;
    private final Duration ttl;
    private final long localTtlNanos;
    private final ConcurrentMap<String, LocalEntry> localCache = new ConcurrentHashMap<>();

    public SystemConfigValkeyCache(
            ObjectProvider<StringRedisTemplate> redisTemplateProvider,
            String keyPrefix,
            Duration ttl) {
        this(redisTemplateProvider, keyPrefix, ttl, DEFAULT_LOCAL_TTL);
    }

    public SystemConfigValkeyCache(
            ObjectProvider<StringRedisTemplate> redisTemplateProvider,
            String keyPrefix,
            Duration ttl,
            Duration localTtl) {
        this.redisTemplateProvider = redisTemplateProvider;
        this.keyPrefix = StringUtils.hasText(keyPrefix) ? keyPrefix.trim() : DEFAULT_KEY_PREFIX;
        this.ttl = ttl == null || ttl.isZero() || ttl.isNegative() ? DEFAULT_TTL : ttl;
        Duration resolvedLocalTtl = localTtl == null || localTtl.isZero() || localTtl.isNegative()
                ? DEFAULT_LOCAL_TTL
                : localTtl;
        this.localTtlNanos = resolvedLocalTtl.toNanos();
    }

    public Optional<String> findValue(String key, Supplier<Optional<String>> loader) {
        // L1 local cache -> L2 Valkey -> L3 database loader.
        Optional<String> local = getLocal(key);
        if (local != null) {
            return local;
        }
        Optional<String> remote = getRemote(key);
        if (remote != null) {
            putLocal(key, remote);
            return remote;
        }
        Optional<String> loaded = loader.get();
        putLocal(key, loaded);
        loaded.ifPresent(value -> putRemote(key, value));
        return loaded;
    }

    public void putAfterCommit(String key, String value) {
        afterCommit(() -> put(key, value));
    }

    public void evictAfterCommit(String key) {
        afterCommit(() -> evict(key));
    }

    public void put(String key, String value) {
        if (key != null && value != null) {
            putLocal(key, Optional.of(value));
        }
        putRemote(key, value);
    }

    public void evict(String key) {
        if (key != null) {
            localCache.remove(key);
        }
        evictRemote(key);
    }

    public void evictLocal(String key) {
        if (key != null) {
            localCache.remove(key);
        }
    }

    private void putRemote(String key, String value) {
        StringRedisTemplate redisTemplate = redisTemplate();
        if (redisTemplate == null || key == null || value == null) {
            return;
        }
        try {
            redisTemplate.opsForValue().set(cacheKey(key), value, ttl);
        } catch (RuntimeException ex) {
            log.warn("Unable to write system config Valkey cache: key={}, error={}", key, ex.getMessage());
        }
    }

    private void evictRemote(String key) {
        StringRedisTemplate redisTemplate = redisTemplate();
        if (redisTemplate == null || key == null) {
            return;
        }
        try {
            redisTemplate.delete(cacheKey(key));
        } catch (RuntimeException ex) {
            log.warn("Unable to evict system config Valkey cache: key={}, error={}", key, ex.getMessage());
        }
    }

    private Optional<String> getRemote(String key) {
        StringRedisTemplate redisTemplate = redisTemplate();
        if (redisTemplate == null || key == null) {
            return null;
        }
        try {
            String value = redisTemplate.opsForValue().get(cacheKey(key));
            return value == null ? null : Optional.of(value);
        } catch (RuntimeException ex) {
            log.warn("Unable to read system config Valkey cache: key={}, error={}", key, ex.getMessage());
            return null;
        }
    }

    private Optional<String> getLocal(String key) {
        if (key == null) {
            return null;
        }
        LocalEntry entry = localCache.get(key);
        if (entry == null) {
            return null;
        }
        if (entry.expired(System.nanoTime(), localTtlNanos)) {
            localCache.remove(key, entry);
            return null;
        }
        return entry.value();
    }

    private void putLocal(String key, Optional<String> value) {
        if (key != null && value != null) {
            localCache.put(key, new LocalEntry(value, System.nanoTime()));
        }
    }

    private StringRedisTemplate redisTemplate() {
        return redisTemplateProvider == null ? null : redisTemplateProvider.getIfAvailable();
    }

    private String cacheKey(String key) {
        return keyPrefix + ":" + key;
    }

    private void afterCommit(Runnable action) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            action.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                action.run();
            }
        });
    }

    private record LocalEntry(Optional<String> value, long loadedAtNanos) {

        boolean expired(long nowNanos, long ttlNanos) {
            return nowNanos - loadedAtNanos >= ttlNanos;
        }
    }
}
