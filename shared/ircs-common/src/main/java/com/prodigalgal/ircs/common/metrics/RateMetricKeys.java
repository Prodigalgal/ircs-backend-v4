package com.prodigalgal.ircs.common.metrics;

import com.prodigalgal.ircs.common.aggregation.AggregationWorkTypes;
import com.prodigalgal.ircs.common.audit.AuditReplicationWorkTypes;
import com.prodigalgal.ircs.common.magnet.MagnetWorkTypes;
import com.prodigalgal.ircs.common.normalization.LlmCleaningWorkTypes;
import com.prodigalgal.ircs.common.pipeline.PipelineRuntimeWorkTypes;
import com.prodigalgal.ircs.common.search.SearchSyncWorkTypes;
import com.prodigalgal.ircs.common.storage.StorageWorkTypes;
import java.time.Duration;
import org.springframework.util.StringUtils;

public final class RateMetricKeys {

    public static final String DEFAULT_KEY_PREFIX = "ircs:metrics:rate";
    public static final Duration DEFAULT_BUCKET_SIZE = Duration.ofSeconds(10);
    public static final Duration DEFAULT_BUCKET_TTL = Duration.ofMinutes(30);
    public static final Duration DEFAULT_BUCKET_INDEX_RETENTION = Duration.ofMinutes(31);

    public static final String ACTION_SUBMITTED = "submitted";
    public static final String ACTION_CLAIMED = "claimed";
    public static final String ACTION_COMPLETED = "completed";
    public static final String ACTION_SUCCEEDED = "succeeded";
    public static final String ACTION_FAILED = "failed";

    public static final String COLLECTION_PAGE_SCHEDULED = "collection.page.scheduled";
    public static final String COLLECTION_PAGE_DISCOVERED = "collection.page.discovered";
    public static final String COLLECTION_PAGE_COMPLETED = "collection.page.completed";
    public static final String COLLECTION_PAGE_FAILED = "collection.page.failed";
    public static final String COLLECTION_DETAIL_COMPLETED = "collection.detail.completed";
    public static final String COLLECTION_DETAIL_SUCCEEDED = "collection.detail.succeeded";
    public static final String COLLECTION_DETAIL_FAILED = "collection.detail.failed";

    private RateMetricKeys() {
    }

    public static String bucketIndexKey(String keyPrefix) {
        return normalizeKeyPrefix(keyPrefix) + ":buckets";
    }

    public static String bucketKey(String keyPrefix, long bucketStartMillis) {
        return normalizeKeyPrefix(keyPrefix) + ":bucket:" + bucketStartMillis;
    }

    public static long bucketStartMillis(long eventMillis, Duration bucketSize) {
        long bucketMillis = bucketSizeMillis(bucketSize);
        return Math.floorDiv(eventMillis, bucketMillis) * bucketMillis;
    }

    public static long bucketSizeMillis(Duration bucketSize) {
        if (bucketSize == null || bucketSize.isZero() || bucketSize.isNegative()) {
            return DEFAULT_BUCKET_SIZE.toMillis();
        }
        return Math.max(1_000L, bucketSize.toMillis());
    }

    public static long ttlSeconds(Duration ttl, Duration fallback) {
        Duration effective = ttl == null || ttl.isZero() || ttl.isNegative() ? fallback : ttl;
        return Math.max(60L, effective.toSeconds());
    }

    public static long retentionMillis(Duration retention, Duration fallback) {
        Duration effective = retention == null || retention.isZero() || retention.isNegative() ? fallback : retention;
        return Math.max(60_000L, effective.toMillis());
    }

    public static String normalizeKeyPrefix(String keyPrefix) {
        if (!StringUtils.hasText(keyPrefix)) {
            return DEFAULT_KEY_PREFIX;
        }
        String normalized = keyPrefix.trim();
        while (normalized.endsWith(":")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    public static String runtimeMetric(String taskType, String action) {
        return runtimePrefix(taskType) + "." + action;
    }

    private static String runtimePrefix(String taskType) {
        return switch (taskType) {
            case SearchSyncWorkTypes.RAW -> "runtime.search_raw";
            case SearchSyncWorkTypes.UNIFIED -> "runtime.search_unified";
            case AggregationWorkTypes.RAW_VIDEO -> "runtime.aggregation";
            case PipelineRuntimeWorkTypes.NORMALIZE_VIDEO -> "runtime.pipeline_normalize";
            case PipelineRuntimeWorkTypes.ENRICH_METADATA -> "runtime.pipeline_metadata_dispatch";
            case PipelineRuntimeWorkTypes.METADATA_PROVIDER -> "runtime.pipeline_metadata_provider";
            case LlmCleaningWorkTypes.RAW_TERM -> "runtime.llm_cleaning";
            case StorageWorkTypes.AVATAR_SYNC -> "runtime.storage_avatar";
            case StorageWorkTypes.COVER_R2_SYNC -> "runtime.storage_cover";
            case AuditReplicationWorkTypes.ES_REPLICATION -> "runtime.audit_es";
            case MagnetWorkTypes.SEARCH -> "runtime.magnet_search";
            default -> "runtime." + normalizeMetricPart(taskType);
        };
    }

    private static String normalizeMetricPart(String value) {
        if (!StringUtils.hasText(value)) {
            return "unknown";
        }
        return value.trim()
                .toLowerCase()
                .replaceAll("[^a-z0-9]+", "_")
                .replaceAll("^_+|_+$", "");
    }
}
