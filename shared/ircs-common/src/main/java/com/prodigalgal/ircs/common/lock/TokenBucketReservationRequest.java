package com.prodigalgal.ircs.common.lock;

import java.time.Duration;
import org.springframework.util.StringUtils;

public record TokenBucketReservationRequest(
        String key,
        long capacity,
        long refillTokens,
        Duration refillPeriod,
        long permits,
        Duration maxWait,
        Duration ttl) {

    public TokenBucketReservationRequest {
        if (!StringUtils.hasText(key)) {
            throw new IllegalArgumentException("token-bucket key is required");
        }
        if (capacity <= 0) {
            throw new IllegalArgumentException("token-bucket capacity must be positive");
        }
        if (refillTokens <= 0) {
            throw new IllegalArgumentException("token-bucket refillTokens must be positive");
        }
        if (refillPeriod == null || !refillPeriod.isPositive()) {
            throw new IllegalArgumentException("token-bucket refillPeriod must be positive");
        }
        if (permits <= 0 || permits > capacity) {
            throw new IllegalArgumentException("token-bucket permits must be between 1 and capacity");
        }
        maxWait = maxWait == null || maxWait.isNegative() ? Duration.ZERO : maxWait;
        ttl = ttl == null || !ttl.isPositive() ? Duration.ofMinutes(10) : ttl;
        key = key.trim();
    }
}
