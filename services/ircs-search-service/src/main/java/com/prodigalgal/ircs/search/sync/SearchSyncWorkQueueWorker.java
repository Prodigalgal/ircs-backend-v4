package com.prodigalgal.ircs.search.sync;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.prodigalgal.ircs.common.audit.WorkerJobAuditEvent;
import com.prodigalgal.ircs.common.audit.WorkerJobAuditWriter;
import com.prodigalgal.ircs.common.scheduling.ScheduledTriggers;
import com.prodigalgal.ircs.common.search.SearchSyncWorkPayload;
import com.prodigalgal.ircs.common.search.SearchSyncWorkTypes;
import com.prodigalgal.ircs.common.work.RuntimeWorkItem;
import com.prodigalgal.ircs.common.work.RuntimeWorkQueue;
import com.prodigalgal.ircs.common.worker.WorkerInstanceIds;
import com.prodigalgal.ircs.search.config.SearchSchedulingConfiguration;
import com.prodigalgal.ircs.search.support.SystemConfigRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@Slf4j
class SearchSyncWorkQueueWorker {

    private static final String ENABLED_KEY = "app.search.work-queue.worker.enabled";
    private static final long ENABLED_CACHE_TTL_NANOS = Duration.ofSeconds(30).toNanos();
    private static final String JOB_TYPE = "runtime-work-queue";

    private final RuntimeWorkQueue workQueue;
    private final ObjectMapper objectMapper;
    private final SearchSyncProcessor syncProcessor;
    private final WorkerJobAuditWriter auditWriter;
    private final SearchSyncWorkQueueMetrics metrics;
    private final SystemConfigRepository systemConfigRepository;
    private final Executor workerExecutor;
    private final LaneRuntime rawLane = new LaneRuntime(SearchSyncWorkTypes.RAW);
    private final LaneRuntime unifiedLane = new LaneRuntime(SearchSyncWorkTypes.UNIFIED);
    private volatile Boolean cachedEnabled;
    private volatile long cachedEnabledExpiresAtNanos;

    @Value("${app.search.work-queue.worker.enabled:false}")
    private boolean enabledByDeployment = true;

    @Value("${app.search.work-queue.worker.batch-size:100}")
    private int batchSize;

    @Value("${app.search.work-queue.worker.max-retries:5}")
    private int maxRetries;

    @Value("${app.search.work-queue.worker.visibility-seconds:300}")
    private long visibilitySeconds;

    @Value("${app.search.work-queue.worker.max-backoff-seconds:300}")
    private long maxBackoffSeconds;

    @Value("${app.search.work-queue.worker.worker-id:${APP_SEARCH_WORK_QUEUE_WORKER_ID:}}")
    private String workerId;

    @Value("${spring.application.name:ircs-search-service}")
    private String applicationName;

    SearchSyncWorkQueueWorker(
            RuntimeWorkQueue workQueue,
            ObjectMapper objectMapper,
            SearchSyncProcessor syncProcessor,
            WorkerJobAuditWriter auditWriter,
            SearchSyncWorkQueueMetrics metrics,
            ObjectProvider<SystemConfigRepository> systemConfigRepositoryProvider,
            @Qualifier(SearchSchedulingConfiguration.SEARCH_SYNC_EXECUTOR) Executor workerExecutor) {
        this.workQueue = workQueue;
        this.objectMapper = objectMapper;
        this.syncProcessor = syncProcessor;
        this.auditWriter = auditWriter;
        this.metrics = metrics;
        this.systemConfigRepository = systemConfigRepositoryProvider == null
                ? null
                : systemConfigRepositoryProvider.getIfAvailable();
        this.workerExecutor = workerExecutor == null ? Runnable::run : workerExecutor;
    }

    @Scheduled(
            initialDelayString = "${app.search.work-queue.worker.initial-delay-ms:10000}",
            fixedRateString = "${app.search.work-queue.worker.fixed-rate-ms:1000}")
    public void runRawScheduled() {
        ScheduledTriggers.submit(
                workerExecutor,
                () -> runOnce(SearchSyncWorkTypes.RAW),
                log,
                "search.sync-work-queue." + SearchSyncWorkTypes.RAW);
    }

    @Scheduled(
            initialDelayString = "${app.search.work-queue.worker.initial-delay-ms:10000}",
            fixedRateString = "${app.search.work-queue.worker.fixed-rate-ms:1000}")
    public void runUnifiedScheduled() {
        ScheduledTriggers.submit(
                workerExecutor,
                () -> runOnce(SearchSyncWorkTypes.UNIFIED),
                log,
                "search.sync-work-queue." + SearchSyncWorkTypes.UNIFIED);
    }

    int runOnce() {
        return runOnce(SearchSyncWorkTypes.RAW) + runOnce(SearchSyncWorkTypes.UNIFIED);
    }

    int runOnce(String taskType) {
        LaneRuntime lane = lane(taskType);
        if (!workerEnabled()) {
            lane.lastRunState = "DISABLED";
            return 0;
        }
        if (!lane.running.compareAndSet(false, true)) {
            lane.lastRunState = "BUSY";
            return 0;
        }
        try {
            return runOnceLocked(lane);
        } finally {
            lane.running.set(false);
        }
    }

    SearchSyncWorkQueueState state() {
        Map<String, SearchSyncWorkLaneState> lanes = new LinkedHashMap<>();
        Instant now = Instant.now();
        lanes.put(SearchSyncWorkTypes.RAW, rawLane.snapshot(workerId(), now));
        lanes.put(SearchSyncWorkTypes.UNIFIED, unifiedLane.snapshot(workerId(), now));
        LaneRuntime latest = latestLane();
        return new SearchSyncWorkQueueState(
                rawLane.running.get() || unifiedLane.running.get(),
                workerId(),
                latest.lastRunAt,
                latest.lastSuccessAt,
                latest.lastFailureAt,
                latest.lastProgressAt,
                rawLane.lastProcessed + unifiedLane.lastProcessed,
                rawLane.lastFailed + unifiedLane.lastFailed,
                rawLane.lastRequeued + unifiedLane.lastRequeued,
                rawLane.consecutiveFailures.get() + unifiedLane.consecutiveFailures.get(),
                latest.lastRunState,
                latest.lastError,
                lanes);
    }

    private int runOnceLocked(LaneRuntime lane) {
        Instant startedAt = Instant.now();
        String resolvedWorkerId = workerId();
        lane.lastRunAt = startedAt;
        lane.markStage("RUN_START", null, null, startedAt);
        int processed = 0;
        int failed = 0;
        int requeued = 0;
        RuntimeException fatal = null;
        try {
            int effectiveBatchSize = Math.max(1, batchSize);
            Duration heartbeatTtl = consumerHeartbeatTtl();
            lane.markStage("HEARTBEAT", null, null, Instant.now());
            heartbeat(lane.taskType, resolvedWorkerId, heartbeatTtl);
            WorkRunResult result = processItemsSafely(lane, resolvedWorkerId, effectiveBatchSize, heartbeatTtl);
            processed = result.processed();
            failed = result.failed();
            if (failed > 0) {
                markFailure(lane, processed, failed, requeued, "search sync work item failures");
            } else {
                markSuccess(lane, processed, requeued);
            }
            return processed;
        } catch (RuntimeException ex) {
            fatal = ex;
            markFailure(lane, processed, failed, requeued, ex.getClass().getSimpleName() + ": " + ex.getMessage());
            log.warn("Search sync work queue run failed: taskType={}, reason={}", lane.taskType, ex.getMessage());
            return processed;
        } finally {
            if (!lane.running.get() || fatal == null) {
                lane.clearCurrent();
            }
            metrics.recordRun(
                    resolvedWorkerId,
                    elapsedSince(startedAt),
                    fatal == null && lane.lastFailed == 0 ? "success" : "failed",
                    processed,
                    lane.lastFailed,
                    requeued);
        }
    }

    private WorkRunResult processItemsSafely(
            LaneRuntime lane,
            String resolvedWorkerId,
            int limit,
            Duration heartbeatTtl) {
        try {
            return processItems(lane, resolvedWorkerId, limit, heartbeatTtl);
        } catch (RuntimeException ex) {
            log.warn("Search sync work queue task type failed: taskType={}, reason={}", lane.taskType, ex.getMessage());
            return new WorkRunResult(0, 1);
        }
    }

    private WorkRunResult processItems(
            LaneRuntime lane,
            String resolvedWorkerId,
            int limit,
            Duration heartbeatTtl) {
        lane.markStage("CLAIM_HEARTBEAT", null, null, Instant.now());
        heartbeat(lane.taskType, resolvedWorkerId, heartbeatTtl);
        lane.markStage("CLAIM", null, null, Instant.now());
        List<RuntimeWorkItem> items = workQueue.claim(
                lane.taskType,
                resolvedWorkerId,
                limit,
                Duration.ofSeconds(Math.max(1, visibilitySeconds)));
        if (!items.isEmpty()) {
            lane.lastProgressAt = Instant.now();
        }
        int processed = 0;
        int failed = 0;
        for (RuntimeWorkItem item : items) {
            lane.markStage("ITEM_HEARTBEAT", item, null, Instant.now());
            heartbeat(lane.taskType, resolvedWorkerId, heartbeatTtl);
            if (processItem(lane, item, resolvedWorkerId)) {
                processed++;
            } else {
                failed++;
            }
            lane.lastProgressAt = Instant.now();
        }
        lane.markStage("BATCH_DONE_HEARTBEAT", null, null, Instant.now());
        heartbeat(lane.taskType, resolvedWorkerId, heartbeatTtl);
        return new WorkRunResult(processed, failed);
    }

    private void heartbeat(String taskType, String resolvedWorkerId, Duration heartbeatTtl) {
        workQueue.heartbeatConsumer(taskType, resolvedWorkerId, heartbeatTtl);
    }

    private Duration consumerHeartbeatTtl() {
        return Duration.ofSeconds(Math.max(1, visibilitySeconds)).plusMinutes(1);
    }

    private boolean processItem(LaneRuntime lane, RuntimeWorkItem item, String resolvedWorkerId) {
        Instant startedAt = Instant.now();
        SearchSyncWorkPayload payload = null;
        try {
            lane.markStage("READ_PAYLOAD", item, null, startedAt);
            payload = objectMapper.readValue(item.payload(), SearchSyncWorkPayload.class);
            lane.markStage("PROCESS_DB_ES", item, payload, Instant.now());
            syncProcessor.process(payload.toMessage());
            lane.markStage("COMPLETE_WRITE", item, payload, Instant.now());
            workQueue.complete(item);
            metrics.recordItem(resolvedWorkerId, item, "processed", Instant.now());
            recordAudit(WorkerJobAuditEvent.succeeded(
                    JOB_TYPE,
                    item.taskType(),
                    correlationId(payload, item),
                    elapsedSince(startedAt)));
            lane.markStage("ITEM_DONE", item, payload, Instant.now());
            return true;
        } catch (RuntimeException | JsonProcessingException ex) {
            boolean retryable = item.attempt() < Math.max(1, maxRetries);
            try {
                lane.markStage("FAIL_WRITE", item, payload, Instant.now());
                workQueue.fail(
                        item,
                        retryable,
                        retryDelay(item.attempt()),
                        ex.getClass().getSimpleName() + ": " + ex.getMessage());
            } catch (RuntimeException failEx) {
                log.warn("Search sync work queue fail write failed: {}", failEx.getMessage());
                throw failEx;
            }
            metrics.recordItem(resolvedWorkerId, item, retryable ? "retry" : "dlq", Instant.now());
            recordAudit(WorkerJobAuditEvent.failed(
                    JOB_TYPE,
                    item.taskType(),
                    correlationId(payload, item),
                    elapsedSince(startedAt),
                    new SearchSyncWorkQueueException("search sync work failed")));
            return false;
        }
    }

    private void markSuccess(LaneRuntime lane, int processed, int requeued) {
        lane.lastSuccessAt = Instant.now();
        lane.lastProcessed = processed;
        lane.lastFailed = 0;
        lane.lastRequeued = requeued;
        lane.lastError = null;
        lane.lastRunState = processed > 0 || requeued > 0 ? "PROCESSED" : "IDLE";
        lane.clearCurrent();
        lane.consecutiveFailures.set(0);
    }

    private void markFailure(LaneRuntime lane, int processed, int failed, int requeued, String error) {
        lane.lastFailureAt = Instant.now();
        lane.lastProcessed = processed;
        lane.lastFailed = failed;
        lane.lastRequeued = requeued;
        lane.lastError = error;
        lane.lastRunState = "DEPENDENCY_FAILURE";
        lane.consecutiveFailures.incrementAndGet();
    }

    private LaneRuntime lane(String taskType) {
        if (SearchSyncWorkTypes.UNIFIED.equals(taskType)) {
            return unifiedLane;
        }
        return rawLane;
    }

    private LaneRuntime latestLane() {
        Instant rawLast = rawLane.lastRunAt == null ? Instant.EPOCH : rawLane.lastRunAt;
        Instant unifiedLast = unifiedLane.lastRunAt == null ? Instant.EPOCH : unifiedLane.lastRunAt;
        return rawLast.compareTo(unifiedLast) >= 0 ? rawLane : unifiedLane;
    }

    private String workerId() {
        String resolved = workerId;
        if (resolved == null || resolved.isBlank()) {
            synchronized (this) {
                if (workerId == null || workerId.isBlank()) {
                    workerId = WorkerInstanceIds.resolve(applicationName, null);
                }
                resolved = workerId;
            }
        } else {
            resolved = WorkerInstanceIds.resolve(applicationName, resolved);
            workerId = resolved;
        }
        return resolved.trim();
    }

    private Duration retryDelay(int attempt) {
        long delaySeconds = 1L << Math.min(Math.max(0, attempt), 10);
        long maxSeconds = Math.max(1, maxBackoffSeconds);
        return Duration.ofSeconds(Math.min(delaySeconds, maxSeconds));
    }

    private void recordAudit(WorkerJobAuditEvent event) {
        try {
            auditWriter.record(event);
        } catch (RuntimeException ex) {
            log.warn("Search sync work queue audit write failed: {}", ex.getMessage());
        }
    }

    private static String correlationId(SearchSyncWorkPayload payload, RuntimeWorkItem item) {
        if (payload != null && payload.correlationId() != null && !payload.correlationId().isBlank()) {
            return payload.correlationId();
        }
        if (payload != null && payload.entityId() != null) {
            return payload.entityId().toString();
        }
        return item == null ? null : item.aggregateId();
    }

    private static Duration elapsedSince(Instant startedAt) {
        return Duration.between(startedAt, Instant.now());
    }

    private boolean workerEnabled() {
        SystemConfigRepository repository = systemConfigRepository;
        if (repository == null) {
            return enabledByDeployment;
        }
        long now = System.nanoTime();
        Boolean value = cachedEnabled;
        if (value != null && now < cachedEnabledExpiresAtNanos) {
            return value;
        }
        synchronized (this) {
            value = cachedEnabled;
            if (value != null && now < cachedEnabledExpiresAtNanos) {
                return value;
            }
            boolean refreshed = enabledByDeployment;
            try {
                repository.evict(ENABLED_KEY);
                refreshed = repository.findValue(ENABLED_KEY)
                        .map(raw -> parseBoolean(raw, enabledByDeployment))
                        .orElse(enabledByDeployment);
            } catch (RuntimeException ex) {
                log.warn("Unable to read search worker enabled config: {}", ex.getMessage());
            }
            cachedEnabled = refreshed;
            cachedEnabledExpiresAtNanos = now + ENABLED_CACHE_TTL_NANOS;
            return refreshed;
        }
    }

    private static boolean parseBoolean(String raw, boolean defaultValue) {
        if (raw == null || raw.isBlank()) {
            return defaultValue;
        }
        return switch (raw.trim().toLowerCase(java.util.Locale.ROOT)) {
            case "true", "1", "yes", "y", "on", "enabled" -> true;
            case "false", "0", "no", "n", "off", "disabled" -> false;
            default -> defaultValue;
        };
    }

    private static class LaneRuntime {
        private final String taskType;
        private final AtomicBoolean running = new AtomicBoolean(false);
        private final AtomicLong consecutiveFailures = new AtomicLong(0);
        private volatile Instant lastRunAt;
        private volatile Instant lastSuccessAt;
        private volatile Instant lastFailureAt;
        private volatile Instant lastProgressAt;
        private volatile int lastProcessed;
        private volatile int lastFailed;
        private volatile int lastRequeued;
        private volatile String lastRunState = "NEVER_RUN";
        private volatile String lastError;
        private volatile String currentTaskId;
        private volatile String currentEntityId;
        private volatile String currentStage;
        private volatile Instant currentStageStartedAt;

        private LaneRuntime(String taskType) {
            this.taskType = taskType;
        }

        private void markStage(
                String stage,
                RuntimeWorkItem item,
                SearchSyncWorkPayload payload,
                Instant startedAt) {
            currentStage = stage;
            currentStageStartedAt = startedAt == null ? Instant.now() : startedAt;
            currentTaskId = item == null ? null : item.taskId();
            if (payload != null && payload.entityId() != null) {
                currentEntityId = payload.entityId().toString();
            } else {
                currentEntityId = item == null ? null : item.aggregateId();
            }
            if (running.get()) {
                lastRunState = "PROCESSING";
            }
        }

        private void clearCurrent() {
            currentTaskId = null;
            currentEntityId = null;
            currentStage = null;
            currentStageStartedAt = null;
        }

        private SearchSyncWorkLaneState snapshot(String workerId, Instant now) {
            Instant runningStartedAt = lastRunAt;
            return new SearchSyncWorkLaneState(
                    taskType,
                    running.get(),
                    workerId,
                    lastRunAt,
                    lastSuccessAt,
                    lastFailureAt,
                    lastProgressAt,
                    lastProcessed,
                    lastFailed,
                    lastRequeued,
                    consecutiveFailures.get(),
                    lastRunState,
                    lastError,
                    currentTaskId,
                    currentEntityId,
                    currentStage,
                    currentStageStartedAt,
                    running.get() && runningStartedAt != null
                            ? Math.max(0L, Duration.between(runningStartedAt, now).toSeconds())
                            : 0L);
        }
    }

    private static class SearchSyncWorkQueueException extends RuntimeException {
        SearchSyncWorkQueueException(String message) {
            super(message);
        }
    }

    private record WorkRunResult(int processed, int failed) {
    }
}
