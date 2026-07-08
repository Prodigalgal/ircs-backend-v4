package com.prodigalgal.ircs.common.lock;

import java.time.Duration;

public record DistributedLockProfile(
        DistributedLockBusinessType businessType,
        DistributedLockType lockType,
        Duration defaultTtl,
        Duration defaultMaxWait,
        String keyPrefix) {

    public DistributedLockProfile {
        if (businessType == null) {
            throw new IllegalArgumentException("businessType is required");
        }
        if (lockType == null) {
            throw new IllegalArgumentException("lockType is required");
        }
        defaultTtl = defaultTtl == null || !defaultTtl.isPositive() ? Duration.ofMinutes(5) : defaultTtl;
        defaultMaxWait = defaultMaxWait == null || defaultMaxWait.isNegative() ? Duration.ZERO : defaultMaxWait;
        keyPrefix = keyPrefix == null ? "" : keyPrefix;
    }
}
