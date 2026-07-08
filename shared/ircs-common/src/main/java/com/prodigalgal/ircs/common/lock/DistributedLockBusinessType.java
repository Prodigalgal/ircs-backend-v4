package com.prodigalgal.ircs.common.lock;

public enum DistributedLockBusinessType {
    SINGLETON_RUNNER,
    MAINTENANCE_RUNNER,
    CACHE_WARMUP,
    DATA_SOURCE_SCRAPE,
    DOMAIN_FETCH,
    PROVIDER_FETCH,
    AGGREGATION_MATCH,
    CREDENTIAL_API_CALL,
    USER_ACTION_RATE_LIMIT
}
