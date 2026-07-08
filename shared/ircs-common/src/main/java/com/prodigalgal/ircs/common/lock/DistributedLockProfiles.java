package com.prodigalgal.ircs.common.lock;

import java.time.Duration;

public final class DistributedLockProfiles {

    private DistributedLockProfiles() {
    }

    public static DistributedLockProfile profileFor(DistributedLockBusinessType businessType) {
        return switch (businessType) {
            case SINGLETON_RUNNER, MAINTENANCE_RUNNER, CACHE_WARMUP -> new DistributedLockProfile(
                    businessType,
                    DistributedLockType.MUTEX_LEASE,
                    Duration.ofMinutes(10),
                    Duration.ZERO,
                    "lock:");
            case DATA_SOURCE_SCRAPE -> new DistributedLockProfile(
                    businessType,
                    DistributedLockType.TIME_SLICE_RESERVATION,
                    Duration.ofMinutes(10),
                    Duration.ofMinutes(2),
                    "traffic:limit:DataSource:");
            case DOMAIN_FETCH -> new DistributedLockProfile(
                    businessType,
                    DistributedLockType.TIME_SLICE_RESERVATION,
                    Duration.ofMinutes(10),
                    Duration.ofMinutes(2),
                    "traffic:limit:Domain:");
            case PROVIDER_FETCH -> new DistributedLockProfile(
                    businessType,
                    DistributedLockType.TIME_SLICE_RESERVATION,
                    Duration.ofMinutes(10),
                    Duration.ofMinutes(2),
                    "traffic:limit:Provider:");
            case AGGREGATION_MATCH -> new DistributedLockProfile(
                    businessType,
                    DistributedLockType.MUTEX_LEASE,
                    Duration.ofMinutes(10),
                    Duration.ZERO,
                    "lock:aggregation:match:");
            case CREDENTIAL_API_CALL -> new DistributedLockProfile(
                    businessType,
                    DistributedLockType.TOKEN_BUCKET,
                    Duration.ofMinutes(10),
                    Duration.ofSeconds(5),
                    "traffic:limit:cred:");
            case USER_ACTION_RATE_LIMIT -> new DistributedLockProfile(
                    businessType,
                    DistributedLockType.TOKEN_BUCKET,
                    Duration.ofMinutes(10),
                    Duration.ZERO,
                    "traffic:limit:user:");
        };
    }
}
