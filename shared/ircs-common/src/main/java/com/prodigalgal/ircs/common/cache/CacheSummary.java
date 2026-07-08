package com.prodigalgal.ircs.common.cache;

public record CacheSummary(
        String name,
        long size,
        long ttlSeconds,
        long hits,
        long misses,
        long evictions
) {
}
