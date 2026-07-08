package com.prodigalgal.ircs.common.cache;

import java.time.Duration;
import java.util.Optional;

public interface TieredStringCacheStore {

    Optional<String> get(String key);

    void set(String key, String value, Duration ttl);

    long evict(String key);

    long evictByPrefix(String keyPrefix);

    static TieredStringCacheStore noop() {
        return Noop.INSTANCE;
    }

    enum Noop implements TieredStringCacheStore {
        INSTANCE;

        @Override
        public Optional<String> get(String key) {
            return Optional.empty();
        }

        @Override
        public void set(String key, String value, Duration ttl) {
        }

        @Override
        public long evict(String key) {
            return 0;
        }

        @Override
        public long evictByPrefix(String keyPrefix) {
            return 0;
        }
    }
}
