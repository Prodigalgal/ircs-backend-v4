package com.prodigalgal.ircs.common.cache;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class TtlGovernedCacheTest {

    private final MutableClock clock = new MutableClock(Instant.parse("2026-06-10T00:00:00Z"));

    @Test
    void cachesValuesUntilTtlExpiresAndReportsSummary() {
        TtlGovernedCache<String, String> cache =
                new TtlGovernedCache<>("demo", Duration.ofSeconds(5), clock, key -> key);
        AtomicInteger loads = new AtomicInteger();

        assertThat(cache.get("a", () -> "v" + loads.incrementAndGet())).isEqualTo("v1");
        assertThat(cache.get("a", () -> "v" + loads.incrementAndGet())).isEqualTo("v1");
        clock.advance(Duration.ofSeconds(6));
        assertThat(cache.get("a", () -> "v" + loads.incrementAndGet())).isEqualTo("v2");

        CacheSummary summary = cache.summary();
        assertThat(summary.size()).isEqualTo(1);
        assertThat(summary.hits()).isEqualTo(1);
        assertThat(summary.misses()).isEqualTo(2);
        assertThat(summary.evictions()).isEqualTo(1);
    }

    @Test
    void registryCanEvictByExternalKey() {
        CacheRegistry registry = new CacheRegistry();
        TtlGovernedCache<String, String> cache =
                new TtlGovernedCache<>("demo", Duration.ofSeconds(30), clock, key -> key);
        registry.register(cache);
        cache.get("a", () -> "one");

        CacheEvictResult result = registry.evictKey("demo", "a");

        assertThat(result.evicted()).isEqualTo(1);
        assertThat(registry.summary("demo").orElseThrow().size()).isZero();
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
