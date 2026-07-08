package com.prodigalgal.ircs.common.cache;

public interface GovernedCache {

    String name();

    CacheSummary summary();

    long evictAll();

    long evictByExternalKey(String externalKey);
}
