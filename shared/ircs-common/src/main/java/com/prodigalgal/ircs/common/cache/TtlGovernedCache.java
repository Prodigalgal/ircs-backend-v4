package com.prodigalgal.ircs.common.cache;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;

public class TtlGovernedCache<K, V> implements GovernedCache {

    private final String name;
    private final Duration ttl;
    private final Clock clock;
    private final Function<K, String> externalKeyFormatter;
    private final ConcurrentMap<K, Entry<V>> entries = new ConcurrentHashMap<>();
    private final LongAdder hits = new LongAdder();
    private final LongAdder misses = new LongAdder();
    private final LongAdder evictions = new LongAdder();

    public TtlGovernedCache(String name, Duration ttl, Function<K, String> externalKeyFormatter) {
        this(name, ttl, Clock.systemUTC(), externalKeyFormatter);
    }

    public TtlGovernedCache(String name, Duration ttl, Clock clock, Function<K, String> externalKeyFormatter) {
        this.name = requireText(name, "name");
        this.ttl = normalizeTtl(ttl);
        this.clock = Objects.requireNonNull(clock, "clock is required");
        this.externalKeyFormatter = Objects.requireNonNull(externalKeyFormatter, "externalKeyFormatter is required");
    }

    @Override
    public String name() {
        return name;
    }

    public Duration ttl() {
        return ttl;
    }

    public V get(K key, Supplier<V> loader) {
        Objects.requireNonNull(key, "key is required");
        Objects.requireNonNull(loader, "loader is required");
        Instant now = clock.instant();
        Entry<V> existing = entries.get(key);
        if (existing != null && existing.expiresAt().isAfter(now)) {
            hits.increment();
            return existing.value();
        }
        if (existing != null && entries.remove(key, existing)) {
            evictions.increment();
        }
        misses.increment();
        V value = loader.get();
        entries.put(key, new Entry<>(value, now.plus(ttl)));
        return value;
    }

    public long evictIf(Predicate<K> predicate) {
        Objects.requireNonNull(predicate, "predicate is required");
        long count = 0;
        for (K key : entries.keySet()) {
            if (predicate.test(key) && entries.remove(key) != null) {
                count++;
            }
        }
        if (count > 0) {
            evictions.add(count);
        }
        return count;
    }

    @Override
    public long evictAll() {
        int count = entries.size();
        entries.clear();
        if (count > 0) {
            evictions.add(count);
        }
        return count;
    }

    @Override
    public long evictByExternalKey(String externalKey) {
        if (externalKey == null || externalKey.isBlank()) {
            return 0;
        }
        String expected = externalKey.trim();
        return evictIf(key -> expected.equals(externalKeyFormatter.apply(key)));
    }

    @Override
    public CacheSummary summary() {
        evictExpired();
        return new CacheSummary(
                name,
                entries.size(),
                ttl.toSeconds(),
                hits.sum(),
                misses.sum(),
                evictions.sum());
    }

    private void evictExpired() {
        Instant now = clock.instant();
        long count = 0;
        for (Map.Entry<K, Entry<V>> entry : entries.entrySet()) {
            if (!entry.getValue().expiresAt().isAfter(now) && entries.remove(entry.getKey(), entry.getValue())) {
                count++;
            }
        }
        if (count > 0) {
            evictions.add(count);
        }
    }

    private Duration normalizeTtl(Duration value) {
        if (value == null || value.isZero() || value.isNegative()) {
            return Duration.ofSeconds(30);
        }
        return value;
    }

    private String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value;
    }

    private record Entry<V>(V value, Instant expiresAt) {
    }
}
