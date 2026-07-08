package com.prodigalgal.ircs.ops.dashboard.application;

import com.prodigalgal.ircs.common.config.RuntimeConfigService;
import com.prodigalgal.ircs.common.metrics.RateMetricKeys;
import com.prodigalgal.ircs.ops.dashboard.domain.RateMetricSnapshot;
import com.prodigalgal.ircs.ops.dashboard.domain.RateMetricWindowCalculator;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class RateMetricSnapshotReader {

    private final StringRedisTemplate redisTemplate;
    private final RuntimeConfigService runtimeConfig;

    RateMetricSnapshot currentSnapshot() {
        long now = System.currentTimeMillis();
        long bucketMillis = RateMetricKeys.bucketSizeMillis(rateBucketSize());
        long instantWindowMillis = durationMillis(instantRateWindow(), Duration.ofSeconds(60));
        long stableWindowMillis = Math.max(
                instantWindowMillis,
                Math.max(
                        durationMillis(stableRateWindow(), Duration.ofMinutes(5)),
                        durationMillis(consumerNoProgressGrace(), Duration.ofMinutes(10))));
        long start = Math.floorDiv(now - stableWindowMillis, bucketMillis) * bucketMillis;
        long end = Math.floorDiv(now, bucketMillis) * bucketMillis;
        Set<String> indexedBuckets = indexedRateBuckets(start, end);
        List<RateMetricWindowCalculator.RateBucketPoint> points = new ArrayList<>();

        for (long bucketStart = start; bucketStart <= end; bucketStart += bucketMillis) {
            String bucketStartValue = Long.toString(bucketStart);
            boolean shouldRead = indexedBuckets == null || indexedBuckets.contains(bucketStartValue);
            points.add(new RateMetricWindowCalculator.RateBucketPoint(
                    bucketStart,
                    shouldRead ? rateBucketCounts(bucketStart) : Map.of()));
        }

        RateMetricWindowCalculator calculator = new RateMetricWindowCalculator(
                bucketMillis,
                instantWindowMillis,
                stableWindowMillis,
                ewmaAlpha());
        return new RateMetricSnapshot(calculator, points, now);
    }

    private Set<String> indexedRateBuckets(long start, long end) {
        try {
            Set<String> buckets = redisTemplate.opsForZSet().rangeByScore(rateBucketIndexKey(), start, end);
            return buckets == null ? Set.of() : buckets;
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private Map<String, Long> rateBucketCounts(long bucketStart) {
        try {
            Map<Object, Object> raw = redisTemplate.opsForHash().entries(
                    RateMetricKeys.bucketKey(rateMetricsKeyPrefix(), bucketStart));
            if (raw == null || raw.isEmpty()) {
                return Map.of();
            }
            Map<String, Long> counts = new LinkedHashMap<>();
            raw.forEach((key, value) -> {
                long count = parseLong(String.valueOf(value));
                if (key != null && count > 0L) {
                    counts.put(String.valueOf(key), count);
                }
            });
            return counts;
        } catch (RuntimeException ignored) {
            return Map.of();
        }
    }

    private String rateBucketIndexKey() {
        return RateMetricKeys.bucketIndexKey(rateMetricsKeyPrefix());
    }

    private String rateMetricsKeyPrefix() {
        return runtimeConfig.stringValue("app.rate-metrics.key-prefix", RateMetricKeys.DEFAULT_KEY_PREFIX);
    }

    private Duration rateBucketSize() {
        return runtimeConfig.positiveDurationValue("app.rate-metrics.bucket-size", RateMetricKeys.DEFAULT_BUCKET_SIZE);
    }

    private Duration instantRateWindow() {
        return runtimeConfig.positiveDurationValue("app.ops.metrics.instant-window", Duration.ofSeconds(60));
    }

    private Duration stableRateWindow() {
        return runtimeConfig.positiveDurationValue("app.ops.metrics.stable-window", Duration.ofMinutes(5));
    }

    private Duration consumerNoProgressGrace() {
        return runtimeConfig.positiveDurationValue(
                "app.ops.metrics.consumer-no-progress-grace",
                Duration.ofMinutes(10));
    }

    private double ewmaAlpha() {
        double value = runtimeConfig.doubleValue("app.ops.metrics.ewma-alpha", 0.25d);
        return Math.max(0.0d, Math.min(1.0d, value));
    }

    private long durationMillis(Duration value, Duration fallback) {
        Duration effective = value == null || value.isZero() || value.isNegative() ? fallback : value;
        return Math.max(RateMetricKeys.DEFAULT_BUCKET_SIZE.toMillis(), effective.toMillis());
    }

    private long parseLong(String value) {
        if (value == null || value.isBlank()) {
            return 0L;
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException ignored) {
            return 0L;
        }
    }
}
