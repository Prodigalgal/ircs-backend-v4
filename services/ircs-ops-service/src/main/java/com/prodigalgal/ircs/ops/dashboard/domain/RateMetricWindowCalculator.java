package com.prodigalgal.ircs.ops.dashboard.domain;

import com.prodigalgal.ircs.ops.dashboard.dto.RateMetricResponse;
import java.time.Instant;
import java.util.List;
import java.util.Map;

public final class RateMetricWindowCalculator {

    private final long bucketSizeMillis;
    private final long instantWindowMillis;
    private final long stableWindowMillis;
    private final double alpha;

    public RateMetricWindowCalculator(
            long bucketSizeMillis,
            long instantWindowMillis,
            long stableWindowMillis,
            double alpha) {
        this.bucketSizeMillis = Math.max(1_000L, bucketSizeMillis);
        this.instantWindowMillis = Math.max(this.bucketSizeMillis, instantWindowMillis);
        this.stableWindowMillis = Math.max(this.instantWindowMillis, stableWindowMillis);
        this.alpha = alpha <= 0.0d || alpha > 1.0d ? 0.25d : alpha;
    }

    public RateMetricResponse calculate(
            String metricKey,
            String label,
            List<RateBucketPoint> buckets,
            long nowMillis,
            String... sourceMetricKeys) {
        List<String> sources = sourceMetricKeys == null || sourceMetricKeys.length == 0
                ? List.of(metricKey)
                : List.of(sourceMetricKeys);
        long instantStart = nowMillis - instantWindowMillis;
        long stableStart = nowMillis - stableWindowMillis;
        long instantCount = 0L;
        long stableCount = 0L;
        Long lastEventAt = null;
        double ewma = 0.0d;

        for (RateBucketPoint bucket : buckets) {
            long bucketCount = sources.stream()
                    .mapToLong(source -> bucket.counts().getOrDefault(source, 0L))
                    .sum();
            if (bucket.startMillis() >= instantStart) {
                instantCount += bucketCount;
            }
            if (bucket.startMillis() >= stableStart) {
                stableCount += bucketCount;
                double bucketTpm = perMinute(bucketCount, bucketSizeMillis);
                ewma = alpha * bucketTpm + (1.0d - alpha) * ewma;
            }
            if (bucketCount > 0L) {
                lastEventAt = Math.min(nowMillis, bucket.startMillis() + bucketSizeMillis);
            }
        }

        boolean stale = lastEventAt == null || lastEventAt < instantStart;
        return new RateMetricResponse(
                metricKey,
                label,
                perMinute(instantCount, instantWindowMillis),
                Math.max(0L, Math.round(ewma)),
                instantWindowMillis / 1000L,
                stableWindowMillis / 1000L,
                instantCount,
                stableCount,
                lastEventAt == null ? null : Instant.ofEpochMilli(lastEventAt),
                stale);
    }

    public RateMetricResponse empty(String metricKey, String label) {
        return RateMetricResponse.empty(metricKey, label, instantWindowMillis / 1000L, stableWindowMillis / 1000L);
    }

    private long perMinute(long count, long windowMillis) {
        if (count <= 0L || windowMillis <= 0L) {
            return 0L;
        }
        return Math.max(1L, Math.round((count * 60_000.0d) / windowMillis));
    }

    public record RateBucketPoint(long startMillis, Map<String, Long> counts) {
    }
}
