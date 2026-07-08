package com.prodigalgal.ircs.common.cache;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.springframework.stereotype.Component;

@Component
public class CacheRegistry {

    private final ConcurrentMap<String, GovernedCache> caches = new ConcurrentHashMap<>();

    public void register(GovernedCache cache) {
        caches.put(normalizeName(cache.name()), cache);
    }

    public List<CacheSummary> summaries() {
        return caches.values().stream()
                .map(GovernedCache::summary)
                .sorted(Comparator.comparing(CacheSummary::name))
                .toList();
    }

    public Optional<CacheSummary> summary(String name) {
        return find(name).map(GovernedCache::summary);
    }

    public CacheEvictResult evictAll(String name) {
        GovernedCache cache = required(name);
        return new CacheEvictResult(cache.name(), null, cache.evictAll());
    }

    public CacheEvictResult evictKey(String name, String externalKey) {
        GovernedCache cache = required(name);
        return new CacheEvictResult(cache.name(), externalKey, cache.evictByExternalKey(externalKey));
    }

    private Optional<GovernedCache> find(String name) {
        return Optional.ofNullable(caches.get(normalizeName(name)));
    }

    private GovernedCache required(String name) {
        return find(name).orElseThrow(() -> new IllegalArgumentException("Unknown cache: " + name));
    }

    private String normalizeName(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("cache name is required");
        }
        return name.trim().toLowerCase(Locale.ROOT);
    }
}
