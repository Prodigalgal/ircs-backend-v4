package com.prodigalgal.ircs.storage.image;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.prodigalgal.ircs.common.audit.WorkerJobAuditEvent;
import com.prodigalgal.ircs.common.audit.WorkerJobAuditWriter;
import com.prodigalgal.ircs.common.scheduling.ScheduledTriggers;
import com.prodigalgal.ircs.common.storage.StorageWorkPayload;
import com.prodigalgal.ircs.common.storage.StorageWorkTypes;
import com.prodigalgal.ircs.common.work.RuntimeWorkItem;
import com.prodigalgal.ircs.common.work.RuntimeWorkQueue;
import com.prodigalgal.ircs.common.worker.WorkerInstanceIds;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@Slf4j
public class StorageR2WorkQueueWorker {

    private static final String ENABLED_KEY = "app.storage.r2.work-queue.worker.enabled";
    private static final long ENABLED_CACHE_TTL_NANOS = Duration.ofSeconds(30).toNanos();
    private static final String JOB_TYPE = "runtime-work-queue";
    private static final String JOB_AVATAR_SYNC = "storage.avatar-sync";
    private static final String JOB_COVER_R2_SYNC = "storage.cover-r2-sync";

    private final RuntimeWorkQueue workQueue;
    private final ObjectMapper objectMapper;
    private final AvatarSyncService avatarSyncService;
    private final CoverImageR2SyncService coverImageR2SyncService;
    private final R2ObjectStorage r2ObjectStorage;
    private final WorkerJobAuditWriter auditWriter;
    private final SystemConfigRepository systemConfigRepository;
    private final ExecutorService triggerExecutor =
            ScheduledTriggers.virtualThreadExecutor("ircs-storage-r2-trigger-vt-");
    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile Boolean cachedEnabled;
    private volatile long cachedEnabledExpiresAtNanos;

    @Value("${app.storage.r2.work-queue.worker.enabled:false}")
    private boolean enabledByDeployment = true;

    @Value("${app.storage.r2.work-queue.worker.batch-size:25}")
    private int batchSize;

    @Value("${app.storage.r2.work-queue.worker.visibility-seconds:900}")
    private long visibilitySeconds;

    @Value("${app.storage.r2.work-queue.worker.max-retries:8}")
    private int maxRetries;

    @Value("${app.storage.r2.work-queue.worker.max-backoff-seconds:1800}")
    private long maxBackoffSeconds;

    @Value("${app.storage.r2.work-queue.worker.worker-id:${APP_STORAGE_R2_WORK_QUEUE_WORKER_ID:}}")
    private String workerId;

    @Value("${spring.application.name:ircs-storage-service}")
    private String applicationName;

    StorageR2WorkQueueWorker(
            RuntimeWorkQueue workQueue,
            ObjectMapper objectMapper,
            AvatarSyncService avatarSyncService,
            CoverImageR2SyncService coverImageR2SyncService,
            R2ObjectStorage r2ObjectStorage,
            WorkerJobAuditWriter auditWriter,
            ObjectProvider<SystemConfigRepository> systemConfigRepositoryProvider) {
        this.workQueue = workQueue;
        this.objectMapper = objectMapper;
        this.avatarSyncService = avatarSyncService;
        this.coverImageR2SyncService = coverImageR2SyncService;
        this.r2ObjectStorage = r2ObjectStorage;
        this.auditWriter = auditWriter == null ? WorkerJobAuditWriter.noop() : auditWriter;
        this.systemConfigRepository = systemConfigRepositoryProvider == null
                ? null
                : systemConfigRepositoryProvider.getIfAvailable();
    }

    @Scheduled(
            initialDelayString = "${app.storage.r2.work-queue.worker.initial-delay-ms:30000}",
            fixedDelayString = "${app.storage.r2.work-queue.worker.fixed-delay-ms:5000}")
    public void runScheduled() {
        ScheduledTriggers.submit(triggerExecutor, () -> runOnce(), log, "storage.r2.run");
    }

    @Scheduled(
            initialDelayString = "${app.storage.r2.work-queue.worker.heartbeat-initial-delay-ms:5000}",
            fixedDelayString = "${app.storage.r2.work-queue.worker.heartbeat-fixed-delay-ms:15000}")
    public void heartbeatScheduled() {
        ScheduledTriggers.submit(triggerExecutor, this::heartbeatIfActive, log, "storage.r2.heartbeat");
    }

    @PreDestroy
    void shutdownTriggerExecutor() {
        triggerExecutor.shutdownNow();
    }

    int runOnce() {
        if (!workerEnabled()) {
            return 0;
        }
        if (!running.compareAndSet(false, true)) {
            return 0;
        }
        try {
            if (!r2ObjectStorage.isActive()) {
                log.debug("Storage R2 runtime worker skipped because R2 is inactive");
                return 0;
            }
            return runTask(
                    StorageWorkTypes.AVATAR_SYNC,
                    JOB_AVATAR_SYNC,
                    this::syncAvatar)
                    + runTask(
                            StorageWorkTypes.COVER_R2_SYNC,
                            JOB_COVER_R2_SYNC,
                            this::syncCover);
        } finally {
            running.set(false);
        }
    }

    private int runTask(String taskType, String jobName, Function<UUID, WorkResult> handler) {
        int effectiveBatchSize = Math.max(1, batchSize);
        heartbeat(taskType);
        List<RuntimeWorkItem> items = workQueue.claim(
                taskType,
                workerId(),
                effectiveBatchSize,
                Duration.ofSeconds(Math.max(1, visibilitySeconds)));
        for (RuntimeWorkItem item : items) {
            process(item, jobName, handler);
        }
        return items.size();
    }

    private void heartbeat(String taskType) {
        workQueue.heartbeatConsumer(
                taskType,
                workerId(),
                Duration.ofSeconds(Math.max(1, visibilitySeconds)).plusMinutes(1));
    }

    private void heartbeatIfActive() {
        if (workerEnabled() && r2ObjectStorage.isActive()) {
            heartbeat(StorageWorkTypes.AVATAR_SYNC);
            heartbeat(StorageWorkTypes.COVER_R2_SYNC);
        }
    }

    private void process(RuntimeWorkItem item, String jobName, Function<UUID, WorkResult> handler) {
        Instant startedAt = Instant.now();
        UUID entityId = null;
        try {
            entityId = payload(item).entityId();
            if (entityId == null) {
                fail(item, startedAt, jobName, null, false, "entity id is null");
                return;
            }
            WorkResult result = handler.apply(entityId);
            if (result.failed()) {
                fail(item, startedAt, jobName, entityId, retryable(item), result.reason());
                return;
            }
            workQueue.complete(item);
            if (result.skipped()) {
                recordSkipped(startedAt, jobName, entityId, result.reason());
            } else {
                recordSucceeded(startedAt, jobName, entityId);
            }
        } catch (RuntimeException | JsonProcessingException ex) {
            fail(item, startedAt, jobName, entityId, retryable(item), ex.getClass().getSimpleName() + ": " + ex.getMessage());
        }
    }

    private StorageWorkPayload payload(RuntimeWorkItem item) throws JsonProcessingException {
        return objectMapper.readValue(item.payload(), StorageWorkPayload.class);
    }

    private WorkResult syncAvatar(UUID memberId) {
        AvatarSyncService.AvatarSyncResult result = avatarSyncService.sync(memberId);
        return result.synced()
                ? WorkResult.succeeded()
                : WorkResult.skipped(result.reason());
    }

    private WorkResult syncCover(UUID imageId) {
        CoverImageR2SyncService.CoverImageR2SyncResult result = coverImageR2SyncService.sync(imageId);
        if (result.synced()) {
            return WorkResult.succeeded();
        }
        return result.failed()
                ? WorkResult.failed(result.reason())
                : WorkResult.skipped(result.reason());
    }

    private void fail(
            RuntimeWorkItem item,
            Instant startedAt,
            String jobName,
            UUID entityId,
            boolean retryable,
            String reason) {
        workQueue.fail(item, retryable, retryDelay(item.attempt()), reason);
        recordFailed(startedAt, jobName, entityId == null ? item.aggregateId() : entityId.toString(), reason);
    }

    private boolean retryable(RuntimeWorkItem item) {
        return item != null && item.attempt() < Math.max(1, maxRetries);
    }

    private Duration retryDelay(int attempt) {
        long delaySeconds = 1L << Math.min(Math.max(0, attempt), 10);
        long maxSeconds = Math.max(1, maxBackoffSeconds);
        return Duration.ofSeconds(Math.min(delaySeconds, maxSeconds));
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

    private void recordSucceeded(Instant startedAt, String jobName, UUID entityId) {
        recordAudit(WorkerJobAuditEvent.succeeded(
                JOB_TYPE,
                jobName,
                entityId == null ? null : entityId.toString(),
                elapsedSince(startedAt)));
    }

    private void recordSkipped(Instant startedAt, String jobName, UUID entityId, String reason) {
        recordAudit(new WorkerJobAuditEvent(
                JOB_TYPE,
                jobName,
                entityId == null ? null : entityId.toString(),
                "skipped",
                elapsedSince(startedAt),
                new StorageR2WorkQueueException(reason)));
    }

    private void recordFailed(Instant startedAt, String jobName, String correlationId, String reason) {
        recordAudit(WorkerJobAuditEvent.failed(
                JOB_TYPE,
                jobName,
                correlationId,
                elapsedSince(startedAt),
                new StorageR2WorkQueueException(reason)));
    }

    private void recordAudit(WorkerJobAuditEvent event) {
        try {
            auditWriter.record(event);
        } catch (RuntimeException ex) {
            log.warn("Storage R2 runtime worker audit write failed: {}", ex.getMessage());
        }
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
                log.warn("Unable to read storage R2 worker enabled config: {}", ex.getMessage());
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

    private record WorkResult(boolean failed, boolean skipped, String reason) {

        static WorkResult succeeded() {
            return new WorkResult(false, false, null);
        }

        static WorkResult skipped(String reason) {
            return new WorkResult(false, true, reason);
        }

        static WorkResult failed(String reason) {
            return new WorkResult(true, false, reason);
        }
    }

    private static class StorageR2WorkQueueException extends RuntimeException {
        StorageR2WorkQueueException(String message) {
            super(message);
        }
    }
}
