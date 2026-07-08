package com.prodigalgal.ircs.task.runtime;


import com.prodigalgal.ircs.task.domain.TaskRuntimeStatus;
import com.prodigalgal.ircs.common.metrics.RateMetricKeys;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import com.prodigalgal.ircs.contracts.task.TaskDetailDoneMessage;
import com.prodigalgal.ircs.contracts.task.TaskMasterDoneMessage;
import com.prodigalgal.ircs.contracts.task.TaskPageDiscoveredMessage;
import com.prodigalgal.ircs.contracts.task.TaskPageFailedMessage;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

@Service
public class TaskProgressRedisService {

    private final StringRedisTemplate redisTemplate;
    private final int eventStreamMaxLen;
    private final String rateMetricsKeyPrefix;
    private final Duration rateBucketSize;
    private final Duration rateBucketTtl;
    private final Duration rateBucketIndexRetention;

    public TaskProgressRedisService(
            StringRedisTemplate redisTemplate,
            @Value("${app.task.runtime.event-stream-maxlen:50000}") int eventStreamMaxLen,
            @Value("${app.rate-metrics.key-prefix:ircs:metrics:rate}") String rateMetricsKeyPrefix,
            @Value("${app.rate-metrics.bucket-size:PT10S}") Duration rateBucketSize,
            @Value("${app.rate-metrics.bucket-ttl:PT30M}") Duration rateBucketTtl,
            @Value("${app.rate-metrics.bucket-index-retention:PT31M}") Duration rateBucketIndexRetention) {
        this.redisTemplate = redisTemplate;
        this.eventStreamMaxLen = Math.max(1000, eventStreamMaxLen);
        this.rateMetricsKeyPrefix = RateMetricKeys.normalizeKeyPrefix(rateMetricsKeyPrefix);
        this.rateBucketSize = rateBucketSize == null ? RateMetricKeys.DEFAULT_BUCKET_SIZE : rateBucketSize;
        this.rateBucketTtl = rateBucketTtl == null ? RateMetricKeys.DEFAULT_BUCKET_TTL : rateBucketTtl;
        this.rateBucketIndexRetention = rateBucketIndexRetention == null
                ? RateMetricKeys.DEFAULT_BUCKET_INDEX_RETENTION
                : rateBucketIndexRetention;
    }

    public long trySchedulePage(UUID masterTaskId, UUID pageTaskId, int pageNumber, Instant scheduledAt) {
        if (masterTaskId == null || pageTaskId == null || pageNumber < 1) {
            return 0;
        }
        Instant effectiveAt = scheduledAt == null ? Instant.now() : scheduledAt;
        RateBucket bucket = rateBucket(effectiveAt);
        Long result = redisTemplate.execute(
                TaskProgressRedisScripts.SCHEDULE_PAGE,
                List.of(
                        TaskHotKeys.masterScheduledPages(masterTaskId),
                        TaskHotKeys.masterState(masterTaskId),
                        TaskHotKeys.dirtyMasters(),
                        TaskHotKeys.masterDiscoveredPages(masterTaskId),
                        TaskHotKeys.pageState(pageTaskId),
                        TaskHotKeys.eventStream(),
                        bucket.bucketKey(),
                        bucket.bucketIndexKey()),
                masterTaskId.toString(),
                pageTaskId.toString(),
                Integer.toString(pageNumber),
                Long.toString(effectiveAt.toEpochMilli()),
                Integer.toString(eventStreamMaxLen),
                bucket.bucketStartMillis(),
                bucket.bucketTtlSeconds(),
                bucket.bucketIndexRetentionMillis(),
                RateMetricKeys.COLLECTION_PAGE_SCHEDULED);
        return result == null ? 0 : result;
    }

    public long rollbackScheduledPage(UUID masterTaskId, UUID pageTaskId, int pageNumber, Instant rolledBackAt) {
        if (masterTaskId == null || pageTaskId == null || pageNumber < 1) {
            return 0;
        }
        Instant effectiveAt = rolledBackAt == null ? Instant.now() : rolledBackAt;
        Long result = redisTemplate.execute(
                TaskProgressRedisScripts.ROLLBACK_SCHEDULED_PAGE,
                List.of(
                        TaskHotKeys.masterScheduledPages(masterTaskId),
                        TaskHotKeys.masterState(masterTaskId),
                        TaskHotKeys.dirtyMasters(),
                        TaskHotKeys.masterDiscoveredPages(masterTaskId),
                        TaskHotKeys.pageState(pageTaskId)),
                masterTaskId.toString(),
                pageTaskId.toString(),
                Integer.toString(pageNumber),
                Long.toString(effectiveAt.toEpochMilli()));
        return result == null ? 0 : result;
    }

    public void holdMaster(UUID masterTaskId, String status, String reason, Instant heldAt) {
        if (masterTaskId == null) {
            return;
        }
        Instant effectiveAt = heldAt == null ? Instant.now() : heldAt;
        String normalizedStatus = normalizeHoldStatus(status);
        Map<String, String> values = new LinkedHashMap<>();
        values.put(TaskRuntimeFields.MASTER_TASK_ID, masterTaskId.toString());
        values.put(TaskRuntimeFields.STATUS, normalizedStatus);
        values.put(TaskRuntimeFields.UPDATED_AT, Long.toString(effectiveAt.toEpochMilli()));
        if (reason != null && !reason.isBlank()) {
            values.put(TaskRuntimeFields.CONTROL_REASON, safeError(reason));
        }
        redisTemplate.opsForHash().putAll(TaskHotKeys.masterState(masterTaskId), values);
        redisTemplate.opsForSet().add(TaskHotKeys.activeMasters(), masterTaskId.toString());
        redisTemplate.opsForZSet().add(TaskHotKeys.dirtyMasters(), masterTaskId.toString(), effectiveAt.toEpochMilli());
    }

    public boolean allowsDispatch(UUID masterTaskId) {
        if (masterTaskId == null) {
            return false;
        }
        Object status = redisTemplate.opsForHash().get(TaskHotKeys.masterState(masterTaskId), TaskRuntimeFields.STATUS);
        return !isBlockedStatus(status == null ? null : status.toString());
    }

    public Optional<PageProgressState> pageProgress(UUID masterTaskId, UUID pageTaskId) {
        Map<String, String> pageState = hash(TaskHotKeys.pageState(pageTaskId));
        if (pageState.isEmpty()) {
            return Optional.empty();
        }
        Integer pageNumber = intValue(pageState, TaskRuntimeFields.PAGE_NUMBER, null);
        if (pageNumber == null || pageNumber < 1) {
            return Optional.empty();
        }
        String status = pageState.get(TaskRuntimeFields.STATUS);
        long detailScheduled = longValue(pageState, TaskRuntimeFields.DETAIL_SCHEDULED, 0);
        long detailCompleted = longValue(pageState, TaskRuntimeFields.DETAIL_COMPLETED, 0);
        boolean terminal = TaskRuntimeStatus.isPageTerminal(status)
                || (detailScheduled > 0 && detailCompleted >= detailScheduled);
        if (!terminal) {
            return Optional.empty();
        }
        Map<String, String> masterState = hash(TaskHotKeys.masterState(masterTaskId));
        return Optional.of(new PageProgressState(
                pageNumber,
                intValue(masterState, TaskRuntimeFields.TOTAL_PAGES, null),
                status));
    }

    public Optional<MasterProgressState> masterProgress(UUID masterTaskId) {
        if (masterTaskId == null) {
            return Optional.empty();
        }
        Map<String, String> masterState = hash(TaskHotKeys.masterState(masterTaskId));
        if (masterState.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new MasterProgressState(
                masterState.get(TaskRuntimeFields.STATUS),
                longValue(masterState, TaskRuntimeFields.PAGE_SCHEDULED, 0),
                longValue(masterState, TaskRuntimeFields.PAGE_COMPLETED, 0),
                intValue(masterState, TaskRuntimeFields.TOTAL_PAGES, null),
                intValue(masterState, TaskRuntimeFields.START_PAGE, null)));
    }

    public long recordPageDiscovered(TaskPageDiscoveredMessage message) {
        RateBucket bucket = rateBucket(message.discoveredAt());
        Long result = redisTemplate.execute(
                TaskProgressRedisScripts.PAGE_DISCOVERED,
                List.of(
                        TaskHotKeys.masterDiscoveredPages(message.masterTaskId()),
                        TaskHotKeys.pageState(message.pageTaskId()),
                        TaskHotKeys.masterState(message.masterTaskId()),
                        TaskHotKeys.dirtyMasters(),
                        TaskHotKeys.eventStream(),
                        bucket.bucketKey(),
                        bucket.bucketIndexKey()),
                message.pageTaskId().toString(),
                Integer.toString(message.pageNumber()),
                Long.toString(message.detailScheduled()),
                optionalInt(message.totalPages()),
                optionalInt(message.totalItems()),
                Long.toString(message.discoveredAt().toEpochMilli()),
                message.masterTaskId().toString(),
                Integer.toString(eventStreamMaxLen),
                bucket.bucketStartMillis(),
                bucket.bucketTtlSeconds(),
                bucket.bucketIndexRetentionMillis(),
                RateMetricKeys.COLLECTION_PAGE_DISCOVERED,
                RateMetricKeys.COLLECTION_PAGE_COMPLETED);
        return result == null ? 0 : result;
    }

    public long recordPageFailed(TaskPageFailedMessage message) {
        Instant failedAt = message.failedAt() == null ? Instant.now() : message.failedAt();
        RateBucket bucket = rateBucket(failedAt);
        Long result = redisTemplate.execute(
                TaskProgressRedisScripts.PAGE_FAILED,
                List.of(
                        TaskHotKeys.masterDiscoveredPages(message.masterTaskId()),
                        TaskHotKeys.pageState(message.pageTaskId()),
                        TaskHotKeys.masterState(message.masterTaskId()),
                        TaskHotKeys.dirtyMasters(),
                        TaskHotKeys.eventStream(),
                        bucket.bucketKey(),
                        bucket.bucketIndexKey()),
                message.pageTaskId().toString(),
                Integer.toString(message.pageNumber()),
                safeError(message.errorMessage()),
                Long.toString(failedAt.toEpochMilli()),
                message.masterTaskId().toString(),
                Integer.toString(eventStreamMaxLen),
                bucket.bucketStartMillis(),
                bucket.bucketTtlSeconds(),
                bucket.bucketIndexRetentionMillis(),
                RateMetricKeys.COLLECTION_PAGE_FAILED);
        return result == null ? 0 : result;
    }

    public long recordDetailDone(TaskDetailDoneMessage message) {
        return recordDetailCompleted(new TaskDetailCompletion(
                message.masterTaskId(),
                message.pageTaskId(),
                message.detailTaskId(),
                message.sourceVid(),
                message.successful(),
                message.errorMessage(),
                message.completedAt()));
    }

    public long recordMasterDone(TaskMasterDoneMessage message) {
        Instant completedAt = message.completedAt() == null ? Instant.now() : message.completedAt();
        Long result = redisTemplate.execute(
                TaskProgressRedisScripts.MASTER_DONE,
                List.of(
                        TaskHotKeys.masterState(message.masterTaskId()),
                        TaskHotKeys.dirtyMasters(),
                        TaskHotKeys.eventStream()),
                message.masterTaskId().toString(),
                safeStatus(message.status()),
                Long.toString(message.pageScheduled()),
                Long.toString(message.pageCompleted()),
                Long.toString(message.pageSucceeded()),
                Long.toString(message.pageFailed()),
                Long.toString(message.detailScheduled()),
                Long.toString(message.detailCompleted()),
                Long.toString(message.detailSucceeded()),
                Long.toString(message.detailFailed()),
                safeError(message.lastError()),
                Long.toString(completedAt.toEpochMilli()),
                Integer.toString(eventStreamMaxLen));
        return result == null ? 0 : result;
    }

    public long recordDetailCompleted(TaskDetailCompletion completion) {
        RateBucket bucket = rateBucket(completion.completedAt());
        Long result = redisTemplate.execute(
                TaskProgressRedisScripts.DETAIL_COMPLETED,
                List.of(
                        TaskHotKeys.pageCompletedDetails(completion.pageTaskId()),
                        TaskHotKeys.pageState(completion.pageTaskId()),
                        TaskHotKeys.masterState(completion.masterTaskId()),
                        TaskHotKeys.dirtyMasters(),
                        TaskHotKeys.pageFailedDetails(completion.pageTaskId()),
                        TaskHotKeys.pageFailedDetailErrors(completion.pageTaskId()),
                        TaskHotKeys.eventStream(),
                        bucket.bucketKey(),
                        bucket.bucketIndexKey()),
                completion.detailTaskId().toString(),
                Boolean.toString(completion.successful()),
                Long.toString(completion.completedAt().toEpochMilli()),
                completion.masterTaskId().toString(),
                safeError(completion.errorMessage()),
                completion.pageTaskId().toString(),
                safeSourceVid(completion.sourceVid()),
                Integer.toString(eventStreamMaxLen),
                bucket.bucketStartMillis(),
                bucket.bucketTtlSeconds(),
                bucket.bucketIndexRetentionMillis(),
                RateMetricKeys.COLLECTION_DETAIL_COMPLETED,
                RateMetricKeys.COLLECTION_DETAIL_SUCCEEDED,
                RateMetricKeys.COLLECTION_DETAIL_FAILED,
                RateMetricKeys.COLLECTION_PAGE_COMPLETED);
        return result == null ? 0 : result;
    }

    private RateBucket rateBucket(Instant eventAt) {
        Instant effectiveAt = eventAt == null ? Instant.now() : eventAt;
        long bucketStart = RateMetricKeys.bucketStartMillis(effectiveAt.toEpochMilli(), rateBucketSize);
        return new RateBucket(
                RateMetricKeys.bucketKey(rateMetricsKeyPrefix, bucketStart),
                RateMetricKeys.bucketIndexKey(rateMetricsKeyPrefix),
                Long.toString(bucketStart),
                Long.toString(RateMetricKeys.ttlSeconds(rateBucketTtl, RateMetricKeys.DEFAULT_BUCKET_TTL)),
                Long.toString(RateMetricKeys.retentionMillis(
                        rateBucketIndexRetention,
                        RateMetricKeys.DEFAULT_BUCKET_INDEX_RETENTION)));
    }

    private Map<String, String> hash(String key) {
        Map<Object, Object> raw = redisTemplate.opsForHash().entries(key);
        if (raw == null || raw.isEmpty()) {
            return Map.of();
        }
        Map<String, String> result = new LinkedHashMap<>();
        raw.forEach((field, value) -> {
            if (field != null && value != null) {
                result.put(field.toString(), value.toString());
            }
        });
        return result;
    }

    private long longValue(Map<String, String> values, String key, long fallback) {
        String value = values.get(key);
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private Integer intValue(Map<String, String> values, String key, Integer fallback) {
        String value = values.get(key);
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private String optionalInt(Integer value) {
        return value == null ? "" : value.toString();
    }

    private String safeError(String errorMessage) {
        if (errorMessage == null || errorMessage.isBlank()) {
            return "";
        }
        String normalized = errorMessage.replaceAll("\\s+", " ").trim();
        return normalized.length() <= 500 ? normalized : normalized.substring(0, 500);
    }

    private String safeSourceVid(String sourceVid) {
        if (sourceVid == null || sourceVid.isBlank()) {
            return "";
        }
        String normalized = sourceVid.replaceAll("\\s+", " ").trim();
        return normalized.length() <= 120 ? normalized : normalized.substring(0, 120);
    }

    private String safeStatus(String status) {
        return TaskRuntimeStatus.normalizeValue(status, TaskRuntimeStatus.COMPLETED);
    }

    private String normalizeHoldStatus(String status) {
        return TaskRuntimeStatus.normalizeHoldStatus(status);
    }

    private boolean isBlockedStatus(String status) {
        return TaskRuntimeStatus.isBlockedForDispatch(status);
    }
}
