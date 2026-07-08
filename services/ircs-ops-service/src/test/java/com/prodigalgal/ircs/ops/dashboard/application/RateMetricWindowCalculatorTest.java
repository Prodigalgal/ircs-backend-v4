package com.prodigalgal.ircs.ops.dashboard.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.prodigalgal.ircs.ops.dashboard.domain.RateMetricWindowCalculator;
import com.prodigalgal.ircs.ops.dashboard.dto.RateMetricResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RateMetricWindowCalculatorTest {

    @Test
    void calculatesInstantRateAndSmoothStableRate() {
        RateMetricWindowCalculator calculator = new RateMetricWindowCalculator(
                10_000L,
                60_000L,
                300_000L,
                0.25d);
        List<RateMetricWindowCalculator.RateBucketPoint> buckets = buckets(
                0,
                300_000,
                Map.of(
                        250_000L, Map.of("collection.detail.completed", 10L),
                        290_000L, Map.of("collection.detail.completed", 5L)));

        RateMetricResponse response = calculator.calculate(
                "collection.detail.completed",
                "DT 采集速率",
                buckets,
                300_000L);

        assertEquals(15, response.instantCount());
        assertEquals(15, response.instantTpm());
        assertEquals(15, response.stableCount());
        assertTrue(response.stableTpm() > 0);
        assertTrue(response.stableTpm() < 15);
        assertFalse(response.stale());
    }

    @Test
    void emptyBucketsDecayStableRateToZeroAndMarkStale() {
        RateMetricWindowCalculator calculator = new RateMetricWindowCalculator(
                10_000L,
                60_000L,
                300_000L,
                0.25d);
        List<RateMetricWindowCalculator.RateBucketPoint> buckets = buckets(
                300_000,
                600_000,
                Map.of(300_000L, Map.of("pipeline.normalize.completed", 5L)));

        RateMetricResponse response = calculator.calculate(
                "pipeline.normalize.completed",
                "清洗速率",
                buckets,
                600_000L);

        assertEquals(0, response.instantCount());
        assertEquals(0, response.instantTpm());
        assertEquals(5, response.stableCount());
        assertEquals(0, response.stableTpm());
        assertTrue(response.stale());
    }

    @Test
    void combinesMultipleSourceMetrics() {
        RateMetricWindowCalculator calculator = new RateMetricWindowCalculator(
                10_000L,
                60_000L,
                300_000L,
                0.25d);
        List<RateMetricWindowCalculator.RateBucketPoint> buckets = buckets(
                0,
                60_000,
                Map.of(50_000L, Map.of(
                        "pipeline.metadata_dispatch.completed", 2L,
                        "pipeline.metadata_provider.completed", 3L)));

        RateMetricResponse response = calculator.calculate(
                "metadata.combined",
                "元数据速率",
                buckets,
                60_000L,
                "pipeline.metadata_dispatch.completed",
                "pipeline.metadata_provider.completed");

        assertEquals(5, response.instantCount());
        assertEquals(5, response.instantTpm());
        assertFalse(response.stale());
    }

    private List<RateMetricWindowCalculator.RateBucketPoint> buckets(
            long startInclusive,
            long endInclusive,
            Map<Long, Map<String, Long>> counts) {
        List<RateMetricWindowCalculator.RateBucketPoint> buckets = new ArrayList<>();
        for (long bucketStart = startInclusive; bucketStart <= endInclusive; bucketStart += 10_000L) {
            buckets.add(new RateMetricWindowCalculator.RateBucketPoint(
                    bucketStart,
                    counts.getOrDefault(bucketStart, Map.of())));
        }
        return buckets;
    }
}
