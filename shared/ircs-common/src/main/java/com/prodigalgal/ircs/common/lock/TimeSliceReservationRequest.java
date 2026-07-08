package com.prodigalgal.ircs.common.lock;

import java.time.Duration;
import org.springframework.util.StringUtils;

public record TimeSliceReservationRequest(
        String key,
        Duration gap,
        Duration maxWait,
        Duration ttl) {

    public TimeSliceReservationRequest {
        if (!StringUtils.hasText(key)) {
            throw new IllegalArgumentException("time-slice key is required");
        }
        if (gap == null || !gap.isPositive()) {
            throw new IllegalArgumentException("time-slice gap must be positive");
        }
        maxWait = maxWait == null || maxWait.isNegative() ? Duration.ZERO : maxWait;
        ttl = ttl == null || !ttl.isPositive() ? Duration.ofMinutes(10) : ttl;
        key = key.trim();
    }
}
