package com.prodigalgal.ircs.normalization;

import com.prodigalgal.ircs.common.audit.WorkerJobAuditEvent;
import com.prodigalgal.ircs.common.audit.WorkerJobAuditWriter;
import com.prodigalgal.ircs.common.concurrent.VirtualThreadExecutors;
import com.prodigalgal.ircs.common.pipeline.PipelineRuntimeWorkTypes;
import com.prodigalgal.ircs.common.scheduling.ScheduledTriggers;
import com.prodigalgal.ircs.common.work.RuntimeWorkItem;
import com.prodigalgal.ircs.common.work.RuntimeWorkQueue;
import com.prodigalgal.ircs.common.worker.WorkerInstanceIds;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@Slf4j
public class NormalizeVideoWorkQueueWorker {

    private final RuntimeWorkQueue workQueue;
    private final RawVideoNormalizationService normalizationService;
    private final NormalizationConfigValues configValues;
    private final WorkerJobAuditWriter auditWriter;
    private final String workerId;
    private final int batchSize;
    private final Duration visibilityTimeout;
    private final ExecutorService triggerExecutor = ScheduledTriggers.virtualThreadExecutor(
            "ircs-normalize-video-trigger-vt-");
    private final ExecutorService taskExecutor = VirtualThreadExecutors.newPerTaskExecutor("normalize-video-task-");
    private final AtomicBoolean running = new AtomicBoolean(false);

    NormalizeVideoWorkQueueWorker(
            RuntimeWorkQueue workQueue,
            RawVideoNormalizationService normalizationService,
            NormalizationConfigValues configValues,
            WorkerJobAuditWriter auditWriter,
            @Value("${spring.application.name:ircs-normalization-worker}") String applicationName,
            @Value("${app.normalization.pipeline.worker-id:${APP_NORMALIZATION_PIPELINE_WORKER_ID:}}")
                    String configuredWorkerId,
            @Value("${app.normalization.valkey-worker.batch-size:${APP_NORMALIZATION_VALKEY_WORKER_BATCH_SIZE:10}}")
                    int batchSize,
            @Value("${app.normalization.valkey-worker.visibility-timeout:PT10M}") Duration visibilityTimeout) {
        this.workQueue = workQueue;
        this.normalizationService = normalizationService;
        this.configValues = configValues;
        this.auditWriter = auditWriter == null ? WorkerJobAuditWriter.noop() : auditWriter;
        this.workerId = WorkerInstanceIds.resolve(applicationName, configuredWorkerId);
        this.batchSize = Math.max(1, batchSize);
        this.visibilityTimeout = positive(visibilityTimeout, Duration.ofMinutes(10));
    }

    @Scheduled(
            initialDelayString = "${app.normalization.valkey-worker.initial-delay-ms:10000}",
            fixedDelayString = "${app.normalization.valkey-worker.fixed-delay-ms:1000}")
    public void runScheduled() {
        ScheduledTriggers.submit(triggerExecutor, () -> runOnce(), log, "normalization.valkey-worker.run");
    }

    @Scheduled(
            initialDelayString = "${app.normalization.valkey-worker.heartbeat-initial-delay-ms:5000}",
            fixedDelayString = "${app.normalization.valkey-worker.heartbeat-fixed-delay-ms:15000}")
    public void heartbeatScheduled() {
        ScheduledTriggers.submit(triggerExecutor, this::heartbeat, log, "normalization.valkey-worker.heartbeat");
    }

    int runOnce() {
        if (!running.compareAndSet(false, true)) {
            return 0;
        }
        try {
            heartbeat();
            List<RuntimeWorkItem> tasks = workQueue.claim(
                    PipelineRuntimeWorkTypes.NORMALIZE_VIDEO,
                    workerId,
                    batchSize,
                    visibilityTimeout);
            processBatch(tasks);
            return tasks.size();
        } finally {
            running.set(false);
        }
    }

    private void processBatch(List<RuntimeWorkItem> tasks) {
        if (tasks.isEmpty()) {
            return;
        }
        List<CompletableFuture<Void>> futures = tasks.stream()
                .map(task -> CompletableFuture.runAsync(() -> process(task), taskExecutor))
                .toList();
        futures.forEach(this::joinTask);
    }

    private void joinTask(CompletableFuture<Void> future) {
        try {
            future.join();
        } catch (CompletionException ex) {
            log.warn("Normalization runtime work task future completed exceptionally: {}", ex.getMessage());
        }
    }

    private void heartbeat() {
        workQueue.heartbeatConsumer(
                PipelineRuntimeWorkTypes.NORMALIZE_VIDEO,
                workerId,
                visibilityTimeout.plusMinutes(1));
    }

    private void process(RuntimeWorkItem task) {
        Instant startedAt = Instant.now();
        UUID rawVideoId = rawVideoId(task);
        String pipelineVersion = task.version();
        try {
            log.info(
                    "Processing runtime normalization task: rawVideoId={}, pipelineVersion={}, attempt={}",
                    rawVideoId,
                    pipelineVersion,
                    task.attempt());
            RawVideoNormalizationService.NormalizationResult result =
                    normalizationService.normalize(rawVideoId, pipelineVersion);
            if ("MISSING".equals(result.status())) {
                workQueue.fail(task, false, Duration.ZERO, result.reason());
                recordFailed(startedAt, rawVideoId, new IllegalStateException(result.reason()));
                return;
            }
            if ("FAILED".equals(result.status())) {
                workQueue.fail(task, true, retryDelay(task.attempt()), result.reason());
                recordSucceeded(startedAt, rawVideoId);
                return;
            }
            workQueue.complete(task);
            recordSucceeded(startedAt, rawVideoId);
        } catch (RuntimeException ex) {
            workQueue.fail(task, true, retryDelay(task.attempt()), ex.getClass().getSimpleName() + ": " + ex.getMessage());
            recordFailed(startedAt, rawVideoId, ex);
        }
    }

    private Duration retryDelay(int attempt) {
        long multiplier = 1L << Math.min(10, Math.max(0, attempt - 1));
        return Duration.ofSeconds(Math.max(1L, configValues.backoffBaseSeconds()) * multiplier);
    }

    private void recordSucceeded(Instant startedAt, UUID rawVideoId) {
        recordAudit(WorkerJobAuditEvent.succeeded(
                "runtime-work-queue",
                "normalization.raw-video",
                correlationId(rawVideoId),
                elapsedSince(startedAt)));
    }

    private void recordFailed(Instant startedAt, UUID rawVideoId, RuntimeException error) {
        recordAudit(WorkerJobAuditEvent.failed(
                "runtime-work-queue",
                "normalization.raw-video",
                correlationId(rawVideoId),
                elapsedSince(startedAt),
                error));
    }

    private void recordAudit(WorkerJobAuditEvent event) {
        try {
            auditWriter.record(event);
        } catch (RuntimeException ex) {
            log.warn("Normalize runtime worker audit write failed: {}", ex.getMessage());
        }
    }

    private static UUID rawVideoId(RuntimeWorkItem task) {
        if (task == null || task.aggregateId() == null || task.aggregateId().isBlank()) {
            throw new IllegalArgumentException("Normalization runtime task requires aggregateId");
        }
        return UUID.fromString(task.aggregateId());
    }

    private static String correlationId(UUID rawVideoId) {
        return rawVideoId == null ? null : rawVideoId.toString();
    }

    private static Duration elapsedSince(Instant startedAt) {
        return Duration.between(startedAt, Instant.now());
    }

    private static Duration positive(Duration value, Duration fallback) {
        return value == null || value.isZero() || value.isNegative() ? fallback : value;
    }

    @PreDestroy
    void shutdown() {
        triggerExecutor.shutdownNow();
        taskExecutor.shutdownNow();
    }
}
