package com.prodigalgal.ircs.ops.dashboard.domain;

import com.prodigalgal.ircs.ops.dashboard.dto.RateMetricResponse;
import java.util.List;

public record RateMetricSnapshot(
        RateMetricWindowCalculator calculator,
        List<RateMetricWindowCalculator.RateBucketPoint> buckets,
        long nowMillis) {

    public RateMetricResponse metric(String metricKey, String label, String... sourceMetricKeys) {
        return calculator.calculate(metricKey, label, buckets, nowMillis, sourceMetricKeys);
    }

    public RateMetricResponse empty(String metricKey, String label) {
        return calculator.empty(metricKey, label);
    }
}
