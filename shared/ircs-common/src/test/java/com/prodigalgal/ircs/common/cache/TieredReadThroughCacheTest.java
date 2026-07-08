package com.prodigalgal.ircs.common.cache;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class TieredReadThroughCacheTest {

    private final MutableClock clock = new MutableClock(Instant.parse("2026-06-11T00:00:00Z"));

    @Test
    void readsThroughLocalExternalAndLoaderInOrder() {
        FakeExternalStore external = new FakeExternalStore();
        external.values.put("ircs:cache:demo:a", "external-a");
        TieredReadThroughCache<String, String> cache = cache(external);
        AtomicInteger loads = new AtomicInteger();

        assertThat(cache.get("a", () -> "loader-" + loads.incrementAndGet())).isEqualTo("external-a");
        assertThat(cache.get("a", () -> "loader-" + loads.incrementAndGet())).isEqualTo("external-a");

        assertThat(loads).hasValue(0);
        assertThat(cache.summary().hits()).isEqualTo(1);
        assertThat(cache.summary().misses()).isEqualTo(1);
    }

    @Test
    void loaderWritesExternalCacheAndExpiresLocalLayer() {
        FakeExternalStore external = new FakeExternalStore();
        TieredReadThroughCache<String, String> cache = cache(external);
        AtomicInteger loads = new AtomicInteger();

        assertThat(cache.get("a", () -> "loader-" + loads.incrementAndGet())).isEqualTo("loader-1");
        assertThat(external.values).containsEntry("ircs:cache:demo:a", "loader-1");

        clock.advance(Duration.ofSeconds(6));

        assertThat(cache.get("a", () -> "loader-" + loads.incrementAndGet())).isEqualTo("loader-1");
        assertThat(loads).hasValue(1);
    }

    @Test
    void decodeFailureEvictsExternalPayloadAndFallsBackToLoader() {
        FakeExternalStore external = new FakeExternalStore();
        external.values.put("ircs:cache:demo:a", "bad");
        TieredReadThroughCache<String, String> cache = new TieredReadThroughCache<>(
                "demo",
                Duration.ofSeconds(5),
                clock,
                "ircs:cache:demo:",
                key -> key,
                value -> value,
                raw -> {
                    if ("bad".equals(raw)) {
                        throw new IllegalArgumentException("bad payload");
                    }
                    return raw;
                },
                external);

        assertThat(cache.get("a", () -> "loader")).isEqualTo("loader");

        assertThat(external.evicted).containsKey("ircs:cache:demo:a");
        assertThat(external.values).containsEntry("ircs:cache:demo:a", "loader");
    }

    @Test
    void evictByExternalKeyClearsLocalAndExternalLayers() {
        FakeExternalStore external = new FakeExternalStore();
        TieredReadThroughCache<String, String> cache = cache(external);
        cache.get("a", () -> "loader");

        CacheEvictResult result = new CacheRegistry() {{
            register(cache);
        }}.evictKey("demo", "a");

        assertThat(result.evicted()).isEqualTo(2);
        assertThat(external.evicted).containsKey("ircs:cache:demo:a");
    }

    private TieredReadThroughCache<String, String> cache(FakeExternalStore external) {
        return new TieredReadThroughCache<>(
                "demo",
                Duration.ofSeconds(5),
                clock,
                "ircs:cache:demo:",
                key -> key,
                value -> value,
                raw -> raw,
                external);
    }

    private static final class FakeExternalStore implements TieredStringCacheStore {

        private final Map<String, String> values = new LinkedHashMap<>();
        private final Map<String, Duration> ttlByKey = new LinkedHashMap<>();
        private final Map<String, Boolean> evicted = new LinkedHashMap<>();

        @Override
        public Optional<String> get(String key) {
            return Optional.ofNullable(values.get(key));
        }

        @Override
        public void set(String key, String value, Duration ttl) {
            values.put(key, value);
            ttlByKey.put(key, ttl);
        }

        @Override
        public long evict(String key) {
            evicted.put(key, true);
            return values.remove(key) == null ? 0 : 1;
        }

        @Override
        public long evictByPrefix(String keyPrefix) {
            long count = 0;
            for (String key : values.keySet().toArray(String[]::new)) {
                if (key.startsWith(keyPrefix)) {
                    count += evict(key);
                }
            }
            return count;
        }
    }

    private static final class MutableClock extends Clock {

        private Instant now;

        private MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
