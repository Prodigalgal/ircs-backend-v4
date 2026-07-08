package com.prodigalgal.ircs.common.lock;

import java.time.Duration;
import java.time.Instant;

public record TokenBucketReservation(
        String key,
        boolean allowed,
        boolean rejected,
        long remainingTokens,
        Duration retryAfter,
        Instant checkedAt) {

    public static TokenBucketReservation allowed(String key, long remainingTokens, Instant checkedAt) {
        return new TokenBucketReservation(key, true, false, remainingTokens, Duration.ZERO, checkedAt);
    }

    public static TokenBucketReservation rejected(
            String key,
            long remainingTokens,
            Duration retryAfter,
            Instant checkedAt) {
        return new TokenBucketReservation(key, false, true, remainingTokens, retryAfter, checkedAt);
    }

    public static TokenBucketReservation waiting(
            String key,
            long remainingTokens,
            Duration retryAfter,
            Instant checkedAt) {
        return new TokenBucketReservation(key, false, false, remainingTokens, retryAfter, checkedAt);
    }
}
