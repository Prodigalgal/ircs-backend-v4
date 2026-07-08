package com.prodigalgal.ircs.task.runtime;

record RateBucket(
        String bucketKey,
        String bucketIndexKey,
        String bucketStartMillis,
        String bucketTtlSeconds,
        String bucketIndexRetentionMillis
) {
}
