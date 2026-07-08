package com.prodigalgal.ircs.common.lease;

import java.time.Duration;
import java.time.Instant;

public record ClusterLease(
        String name,
        String ownerId,
        String token,
        Instant acquiredAt,
        Instant expiresAt) {

    public ClusterLease {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("lease name is required");
        }
        if (ownerId == null || ownerId.isBlank()) {
            throw new IllegalArgumentException("lease ownerId is required");
        }
        if (token == null || token.isBlank()) {
            throw new IllegalArgumentException("lease token is required");
        }
        acquiredAt = acquiredAt == null ? Instant.now() : acquiredAt;
        expiresAt = expiresAt == null ? acquiredAt : expiresAt;
    }

    public Duration ttlRemaining(Instant now) {
        Instant reference = now == null ? Instant.now() : now;
        Duration ttl = Duration.between(reference, expiresAt);
        return ttl.isNegative() ? Duration.ZERO : ttl;
    }
}
