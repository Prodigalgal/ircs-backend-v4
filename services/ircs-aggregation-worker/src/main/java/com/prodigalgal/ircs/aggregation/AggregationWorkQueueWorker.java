package com.prodigalgal.ircs.aggregation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.prodigalgal.ircs.common.aggregation.AggregationWorkPayload;
import com.prodigalgal.ircs.common.aggregation.AggregationWorkTypes;
import com.prodigalgal.ircs.common.audit.WorkerJobAuditEvent;
import com.prodigalgal.ircs.common.audit.WorkerJobAuditWriter;
import com.prodigalgal.ircs.common.scheduling.ScheduledTriggers;
import com.prodigalgal.ircs.common.work.RuntimeWorkItem;
import com.prodigalgal.ircs.common.work.RuntimeWorkQueue;
import com.prodigalgal.ircs.common.worker.WorkerInstanceIds;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@Slf4j
class AggregationWorkQueueWorker {

    private static final String JOB_TYPE = "runtime-work-queue";
    private static final String JOB_NAME = "aggregation.raw-video";

    private final RuntimeWorkQueue workQueue;
    private final ObjectMapper objectMapper;
    private final AggregationService aggregationService;
    private final WorkerJobAuditWriter auditWriter;
    private final ExecutorService triggerExecutor =
            ScheduledTriggers.virtualThreadExecutor("ircs-aggregation-trigger-vt-");
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
    private volatile String currentStage;
    private volatile String currentRawVideoId;
    private volatile String currentTaskId;
    private volatile int currentBatchSize;
    private volatile Instant currentStageStartedAt;

    @Value("${app.aggregation.work-queue.worker.batch-size:100}")
    private int batchSize;

    @Value("${app.aggregation.work-queue.worker.processing-chunk-size:5}")
    private int processingChunkSize;

    @Value("${app.aggregation.work-queue.worker.visibility-seconds:600}")
    private long visibilitySeconds;

    @Value("${app.aggregation.work-queue.worker.max-retries:8}")
    private int maxRetries;

    @Value("${app.aggregation.work-queue.worker.max-backoff-seconds:900}")
    private long maxBackoffSeconds;

    @Value("${app.aggregation.work-queue.worker.worker-id:${APP_AGGREGATION_WORK_QUEUE_WORKER_ID:}}")
    private String workerId;

    @Value("${spring.application.name:ircs-aggregation-worker}")
    private String applicationName;

    AggregationWorkQueueWorker(
            RuntimeWorkQueue workQueue,
            ObjectMapper objectMapper,
            AggregationService aggregationService,
            WorkerJobAuditWriter auditWriter) {
        this.workQueue = workQueue;
        this.objectMapper = objectMapper;
        this.aggregationService = aggregationService;
        this.auditWriter = auditWriter == null ? WorkerJobAuditWriter.noop() : auditWriter;
    }

    @Scheduled(
            initialDelayString = "${app.aggregation.work-queue.worker.initial-delay-ms:10000}",
            fixedDelayString = "${app.aggregation.work-queue.worker.fixed-delay-ms:1000}")
    public void runScheduled() {
        ScheduledTriggers.submit(triggerExecutor, () -> runOnce(), log, "aggregation.work-queue.run");
    }

    @PreDestroy
    void shutdownTriggerExecutor() {
        triggerExecutor.shutdownNow();
    }

    int runOnce() {
        if (!running.compareAndSet(false, true)) {
            lastRunState = "BUSY";
            return 0;
        }
        try {
            return runOnceLocked();
        } finally {
            clearStage();
            running.set(false);
        }
    }

    AggregationWorkQueueState state() {
        Instant stageStartedAt = currentStageStartedAt;
        return new AggregationWorkQueueState(
                running.get(),
                workerId(),
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
                currentStage,
                currentRawVideoId,
                currentTaskId,
                currentBatchSize,
                stageStartedAt,
                runningForSeconds(stageStartedAt));
    }

    private int runOnceLocked() {
        Instant startedAt = Instant.now();
        lastRunAt = startedAt;
        int effectiveBatchSize = Math.max(1, batchSize);
        String resolvedWorkerId = workerId();
        int requeued = 0;
        ProcessResult result = ProcessResult.empty();
        try {
            heartbeat(resolvedWorkerId);
            setStage("CLAIM", null, null, effectiveBatchSize);
            List<RuntimeWorkItem> items = workQueue.claim(
                    AggregationWorkTypes.RAW_VIDEO,
                    resolvedWorkerId,
                    effectiveBatchSize,
                    Duration.ofSeconds(Math.max(1, visibilitySeconds)));
            if (!items.isEmpty()) {
                lastProgressAt = Instant.now();
            }
            result = process(items, resolvedWorkerId);
            if (result.failed() > 0) {
                markFailure(result.processed(), result.failed(), requeued, "aggregation work item failures");
            } else {
                markSuccess(result.processed(), requeued);
            }
            return items.size();
        } catch (RuntimeException ex) {
            markFailure(
                    result.processed(),
                    result.failed(),
                    requeued,
                    ex.getClass().getSimpleName() + ": " + ex.getMessage());
            log.warn("Aggregation work queue run failed: {}", ex.getMessage());
            return result.processed();
        }
    }

    private void heartbeat(String resolvedWorkerId) {
        workQueue.heartbeatConsumer(
                AggregationWorkTypes.RAW_VIDEO,
                resolvedWorkerId,
                Duration.ofSeconds(Math.max(1, visibilitySeconds)).plusMinutes(1));
    }

    private ProcessResult process(List<RuntimeWorkItem> items, String resolvedWorkerId) {
        if (items.isEmpty()) {
            return ProcessResult.empty();
        }
        Instant startedAt = Instant.now();
        Map<UUID, List<RuntimeWorkItem>> itemsByRawVideoId = new LinkedHashMap<>();
        int processed = 0;
        int failed = 0;
        for (RuntimeWorkItem item : items) {
            setStage("PARSE_PAYLOAD", null, item.taskId(), 1);
            heartbeat(resolvedWorkerId);
            UUID rawVideoId = null;
            try {
                AggregationWorkPayload payload = objectMapper.readValue(item.payload(), AggregationWorkPayload.class);
                rawVideoId = payload.rawVideoId();
                itemsByRawVideoId.computeIfAbsent(rawVideoId, ignored -> new ArrayList<>()).add(item);
            } catch (RuntimeException | JsonProcessingException ex) {
                failRetryable(item, startedAt, rawVideoId, ex.getClass().getSimpleName() + ": " + ex.getMessage());
                processed++;
                failed++;
                lastProgressAt = Instant.now();
            }
        }
        if (itemsByRawVideoId.isEmpty()) {
            return new ProcessResult(processed, failed);
        }
        List<Map.Entry<UUID, List<RuntimeWorkItem>>> entries = new ArrayList<>(itemsByRawVideoId.entrySet());
        int chunkSize = Math.max(1, processingChunkSize);
        for (int start = 0; start < entries.size(); start += chunkSize) {
            List<Map.Entry<UUID, List<RuntimeWorkItem>>> chunk =
                    entries.subList(start, Math.min(start + chunkSize, entries.size()));
            ProcessResult chunkResult = processChunk(chunk, startedAt, resolvedWorkerId);
            processed += chunkResult.processed();
            failed += chunkResult.failed();
        }
        return new ProcessResult(processed, failed);
    }

    private ProcessResult processChunk(
            List<Map.Entry<UUID, List<RuntimeWorkItem>>> chunk,
            Instant startedAt,
            String resolvedWorkerId) {
        if (chunk.isEmpty()) {
            return ProcessResult.empty();
        }
        int processed = 0;
        int failed = 0;
        List<UUID> rawVideoIds = chunk.stream()
                .map(Map.Entry::getKey)
                .toList();
        try {
            setStage("AGGREGATE_BATCH", rawVideoIds.getFirst(), firstTaskId(chunk), rawVideoIds.size());
            heartbeat(resolvedWorkerId);
            List<AggregationResult> results = aggregationService.aggregateRuntimeWorkBatch(rawVideoIds);
            heartbeat(resolvedWorkerId);
            Map<UUID, AggregationResult> resultByRawVideoId = new LinkedHashMap<>();
            for (AggregationResult result : results) {
                resultByRawVideoId.put(result.rawVideoId(), result);
            }
            for (Map.Entry<UUID, List<RuntimeWorkItem>> entry : chunk) {
                AggregationResult result = resultByRawVideoId.getOrDefault(
                        entry.getKey(),
                        AggregationResult.skipped(entry.getKey(), "MISSING_RAW_VIDEO"));
                for (RuntimeWorkItem item : entry.getValue()) {
                    setStage(
                            "BOUND".equals(result.status()) ? "FINISH_COMPLETE" : "FINISH_FAIL",
                            entry.getKey(),
                            item.taskId(),
                            1);
                    heartbeat(resolvedWorkerId);
                    if (!finish(item, startedAt, result)) {
                        failed++;
                    }
                    processed++;
                    lastProgressAt = Instant.now();
                }
            }
        } catch (RuntimeException ex) {
            for (Map.Entry<UUID, List<RuntimeWorkItem>> entry : chunk) {
                for (RuntimeWorkItem item : entry.getValue()) {
                    setStage("FINISH_FAIL", entry.getKey(), item.taskId(), 1);
                    heartbeat(resolvedWorkerId);
                    failRetryable(item, startedAt, entry.getKey(), ex.getClass().getSimpleName() + ": " + ex.getMessage());
                    processed++;
                    failed++;
                    lastProgressAt = Instant.now();
                }
            }
        }
        return new ProcessResult(processed, failed);
    }

    private boolean finish(RuntimeWorkItem item, Instant startedAt, AggregationResult result) {
        UUID rawVideoId = result.rawVideoId();
        if ("BOUND".equals(result.status())) {
            workQueue.complete(item);
            recordAudit(WorkerJobAuditEvent.succeeded(
                    JOB_TYPE,
                    JOB_NAME,
                    correlationId(rawVideoId, item),
                    elapsedSince(startedAt)));
            return true;
        }
        if ("MISSING_RAW_VIDEO".equals(result.status())) {
            workQueue.fail(item, false, Duration.ZERO, result.status());
            recordAudit(WorkerJobAuditEvent.failed(
                    JOB_TYPE,
                    JOB_NAME,
                    correlationId(rawVideoId, item),
                    elapsedSince(startedAt),
                    new AggregationWorkQueueException(result.status())));
            return false;
        }
        failRetryable(item, startedAt, rawVideoId, result.status());
        return false;
    }

    private void failRetryable(RuntimeWorkItem item, Instant startedAt, UUID rawVideoId, String reason) {
        boolean retryable = item.attempt() < Math.max(1, maxRetries);
        workQueue.fail(item, retryable, retryDelay(item.attempt()), reason);
        if (retryable) {
            log.info(
                    "Aggregation work item scheduled for retry: rawVideoId={}, taskId={}, attempt={}, reason={}",
                    rawVideoId,
                    item.taskId(),
                    item.attempt(),
                    reason);
        } else {
            log.warn(
                    "Aggregation work item moved to DLQ: rawVideoId={}, taskId={}, attempt={}, reason={}",
                    rawVideoId,
                    item.taskId(),
                    item.attempt(),
                    reason);
        }
        recordAudit(WorkerJobAuditEvent.failed(
                JOB_TYPE,
                JOB_NAME,
                correlationId(rawVideoId, item),
                elapsedSince(startedAt),
                new AggregationWorkQueueException(reason)));
    }

    private String workerId() {
        String resolved = workerId;
        if (resolved == null || resolved.isBlank()) {
            workerId = WorkerInstanceIds.resolve(applicationName, null);
        } else {
            workerId = WorkerInstanceIds.resolve(applicationName, resolved);
        }
        return workerId;
    }

    private Duration retryDelay(int attempt) {
        long delaySeconds = 1L << Math.min(Math.max(0, attempt), 10);
        long maxSeconds = Math.max(1, maxBackoffSeconds);
        return Duration.ofSeconds(Math.min(delaySeconds, maxSeconds));
    }

    private void markSuccess(int processed, int requeued) {
        lastSuccessAt = Instant.now();
        lastProcessed = processed;
        lastFailed = 0;
        lastRequeued = requeued;
        lastError = null;
        lastRunState = processed > 0 || requeued > 0 ? "PROCESSED" : "IDLE";
        consecutiveFailures.set(0);
    }

    private void markFailure(int processed, int failed, int requeued, String error) {
        lastFailureAt = Instant.now();
        lastProcessed = processed;
        lastFailed = failed;
        lastRequeued = requeued;
        lastError = error;
        lastRunState = "DEPENDENCY_FAILURE";
        consecutiveFailures.incrementAndGet();
    }

    private void recordAudit(WorkerJobAuditEvent event) {
        try {
            auditWriter.record(event);
        } catch (RuntimeException ex) {
            log.warn("Aggregation work queue audit write failed: {}", ex.getMessage());
        }
    }

    private static String correlationId(UUID rawVideoId, RuntimeWorkItem item) {
        if (rawVideoId != null) {
            return rawVideoId.toString();
        }
        return item == null ? null : item.aggregateId();
    }

    private static Duration elapsedSince(Instant startedAt) {
        return Duration.between(startedAt, Instant.now());
    }

    private void setStage(String stage, UUID rawVideoId, String taskId, int batchSize) {
        currentStage = stage;
        currentRawVideoId = rawVideoId == null ? null : rawVideoId.toString();
        currentTaskId = taskId;
        currentBatchSize = Math.max(0, batchSize);
        currentStageStartedAt = Instant.now();
    }

    private void clearStage() {
        currentStage = null;
        currentRawVideoId = null;
        currentTaskId = null;
        currentBatchSize = 0;
        currentStageStartedAt = null;
    }

    private long runningForSeconds(Instant stageStartedAt) {
        if (stageStartedAt == null) {
            return 0L;
        }
        return Math.max(0L, Duration.between(stageStartedAt, Instant.now()).toSeconds());
    }

    private String firstTaskId(List<Map.Entry<UUID, List<RuntimeWorkItem>>> chunk) {
        if (chunk.isEmpty() || chunk.getFirst().getValue().isEmpty()) {
            return null;
        }
        return chunk.getFirst().getValue().getFirst().taskId();
    }

    private static class AggregationWorkQueueException extends RuntimeException {
        AggregationWorkQueueException(String message) {
            super(message);
        }
    }

    private record ProcessResult(int processed, int failed) {
        static ProcessResult empty() {
            return new ProcessResult(0, 0);
        }
    }
}
