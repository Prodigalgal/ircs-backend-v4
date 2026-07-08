package com.prodigalgal.ircs.metadata.dispatch.worker;

import com.prodigalgal.ircs.common.pipeline.PipelineRuntimeWorkTypes;
import com.prodigalgal.ircs.common.scheduling.ScheduledTriggers;
import com.prodigalgal.ircs.common.work.RuntimeWorkItem;
import com.prodigalgal.ircs.common.work.RuntimeWorkQueue;
import com.prodigalgal.ircs.common.worker.WorkerInstanceIds;
import com.prodigalgal.ircs.metadata.dispatch.application.MetadataDispatchService;
import com.prodigalgal.ircs.metadata.dispatch.dto.MetadataDispatchResult;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@Slf4j
public class MetadataDispatchWorkQueueWorker {

    private final RuntimeWorkQueue workQueue;
    private final MetadataDispatchService dispatchService;
    private final String workerId;
    private final int batchSize;
    private final Duration visibilityTimeout;
    private final Duration retryDelay;
    private final ExecutorService triggerExecutor =
            ScheduledTriggers.virtualThreadExecutor("ircs-metadata-dispatch-trigger-vt-");
    private final AtomicBoolean running = new AtomicBoolean(false);

    MetadataDispatchWorkQueueWorker(
            RuntimeWorkQueue workQueue,
            MetadataDispatchService dispatchService,
            @Value("${spring.application.name:ircs-metadata-worker}") String applicationName,
            @Value("${app.metadata.pipeline.worker-id:${APP_METADATA_PIPELINE_WORKER_ID:}}")
                    String configuredWorkerId,
            @Value("${app.metadata.valkey-dispatcher.batch-size:${APP_METADATA_VALKEY_DISPATCHER_BATCH_SIZE:10}}")
                    int batchSize,
            @Value("${app.metadata.valkey-dispatcher.visibility-timeout:PT10M}") Duration visibilityTimeout,
            @Value("${app.metadata.valkey-dispatcher.retry-delay:PT5M}") Duration retryDelay) {
        this.workQueue = workQueue;
        this.dispatchService = dispatchService;
        this.workerId = WorkerInstanceIds.resolve(applicationName, configuredWorkerId);
        this.batchSize = Math.max(1, batchSize);
        this.visibilityTimeout = positive(visibilityTimeout, Duration.ofMinutes(10));
        this.retryDelay = positive(retryDelay, Duration.ofMinutes(5));
    }

    @Scheduled(
            initialDelayString = "${app.metadata.valkey-dispatcher.initial-delay-ms:10000}",
            fixedDelayString = "${app.metadata.valkey-dispatcher.fixed-delay-ms:1000}")
    public void runScheduled() {
        ScheduledTriggers.submit(triggerExecutor, () -> runOnce(), log, "metadata.dispatch.run");
    }

    @Scheduled(
            initialDelayString = "${app.metadata.valkey-dispatcher.heartbeat-initial-delay-ms:5000}",
            fixedDelayString = "${app.metadata.valkey-dispatcher.heartbeat-fixed-delay-ms:15000}")
    public void heartbeatScheduled() {
        ScheduledTriggers.submit(triggerExecutor, this::heartbeat, log, "metadata.dispatch.heartbeat");
    }

    @PreDestroy
    void shutdownTriggerExecutor() {
        triggerExecutor.shutdownNow();
    }

    int runOnce() {
        if (!running.compareAndSet(false, true)) {
            return 0;
        }
        try {
            heartbeat();
            List<RuntimeWorkItem> tasks = workQueue.claim(
                    PipelineRuntimeWorkTypes.ENRICH_METADATA,
                    workerId,
                    batchSize,
                    visibilityTimeout);
            tasks.forEach(this::process);
            return tasks.size();
        } finally {
            running.set(false);
        }
    }

    private void heartbeat() {
        workQueue.heartbeatConsumer(
                PipelineRuntimeWorkTypes.ENRICH_METADATA,
                workerId,
                visibilityTimeout.plusMinutes(1));
    }

    private void process(RuntimeWorkItem task) {
        try {
            MetadataDispatchResult result = dispatchService.dispatch(
                    rawVideoId(task),
                    task.version());
            if (!result.videoFound()) {
                workQueue.fail(task, false, Duration.ZERO, "RAW_VIDEO_NOT_FOUND");
                return;
            }
            if ("FAILED".equals(result.status())) {
                workQueue.fail(task, true, retryDelay, "ENRICHMENT_RETRYABLE_FAILURE");
                return;
            }
            workQueue.complete(task);
        } catch (RuntimeException ex) {
            workQueue.fail(task, true, retryDelay, ex.getClass().getSimpleName() + ": " + ex.getMessage());
            throw ex;
        }
    }

    private static java.util.UUID rawVideoId(RuntimeWorkItem task) {
        if (task == null || task.aggregateId() == null || task.aggregateId().isBlank()) {
            throw new IllegalArgumentException("Metadata dispatch runtime task requires aggregateId");
        }
        return java.util.UUID.fromString(task.aggregateId());
    }

    private static Duration positive(Duration value, Duration fallback) {
        return value == null || value.isZero() || value.isNegative() ? fallback : value;
    }
}
