package com.prodigalgal.ircs.common.cache;

public record CacheEvictResult(
        String name,
        String key,
        long evicted
) {
}
