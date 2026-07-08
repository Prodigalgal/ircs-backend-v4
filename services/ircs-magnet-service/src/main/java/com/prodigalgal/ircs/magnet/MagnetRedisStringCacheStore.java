package com.prodigalgal.ircs.magnet;

import com.prodigalgal.ircs.common.cache.TieredStringCacheStore;
import java.time.Duration;
import java.util.Optional;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;

class MagnetRedisStringCacheStore implements TieredStringCacheStore {

    private final StringRedisTemplate redisTemplate;

    MagnetRedisStringCacheStore(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @Override
    public Optional<String> get(String key) {
        return Optional.ofNullable(redisTemplate.opsForValue().get(key));
    }

    @Override
    public void set(String key, String value, Duration ttl) {
        redisTemplate.opsForValue().set(key, value, ttl);
    }

    @Override
    public long evict(String key) {
        return Boolean.TRUE.equals(redisTemplate.delete(key)) ? 1 : 0;
    }

    @Override
    public long evictByPrefix(String keyPrefix) {
        long count = 0;
        try (var cursor = redisTemplate.scan(ScanOptions.scanOptions()
                .match(keyPrefix + "*")
                .count(100)
                .build())) {
            while (cursor.hasNext()) {
                count += evict(cursor.next());
            }
        }
        return count;
    }
}
