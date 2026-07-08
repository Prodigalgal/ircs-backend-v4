package com.prodigalgal.ircs.normalization;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.prodigalgal.ircs.common.audit.WorkerJobAuditEvent;
import com.prodigalgal.ircs.common.audit.WorkerJobAuditWriter;
import com.prodigalgal.ircs.common.normalization.LlmCleaningWorkPayload;
import com.prodigalgal.ircs.common.normalization.LlmCleaningWorkTypes;
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
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@Slf4j
class LlmCleaningWorkQueueWorker {

    private static final String ENABLED_KEY = "app.normalization.llm-cleaning.work-queue.worker.enabled";
    private static final long ENABLED_CACHE_TTL_NANOS = Duration.ofSeconds(30).toNanos();
    private static final String JOB_TYPE = "runtime-work-queue";
    private static final String JOB_NAME = "normalization.llm-cleaning";

    private final RuntimeWorkQueue workQueue;
    private final ObjectMapper objectMapper;
    private final LlmCleaningService cleaningService;
    private final WorkerJobAuditWriter auditWriter;
    private final SystemConfigRepository systemConfigRepository;
    private final ExecutorService triggerExecutor = ScheduledTriggers.virtualThreadExecutor(
            "ircs-llm-cleaning-trigger-vt-");
    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile Boolean cachedEnabled;
    private volatile long cachedEnabledExpiresAtNanos;

    @Value("${app.normalization.llm-cleaning.work-queue.worker.enabled:false}")
    private boolean enabledByDeployment = true;

    @Value("${app.normalization.llm-cleaning.work-queue.worker.batch-size:100}")
    private int batchSize;

    @Value("${app.normalization.llm-cleaning.work-queue.worker.visibility-seconds:900}")
    private long visibilitySeconds;

    @Value("${app.normalization.llm-cleaning.work-queue.worker.max-retries:8}")
    private int maxRetries;

    @Value("${app.normalization.llm-cleaning.work-queue.worker.max-backoff-seconds:1800}")
    private long maxBackoffSeconds;

    @Value("${app.normalization.llm-cleaning.work-queue.worker.worker-id:${APP_NORMALIZATION_LLM_CLEANING_WORK_QUEUE_WORKER_ID:}}")
    private String workerId;

    @Value("${spring.application.name:ircs-normalization-worker}")
    private String applicationName;

    LlmCleaningWorkQueueWorker(
            RuntimeWorkQueue workQueue,
            ObjectMapper objectMapper,
            LlmCleaningService cleaningService,
            WorkerJobAuditWriter auditWriter,
            ObjectProvider<SystemConfigRepository> systemConfigRepositoryProvider) {
        this.workQueue = workQueue;
        this.objectMapper = objectMapper;
        this.cleaningService = cleaningService;
        this.auditWriter = auditWriter == null ? WorkerJobAuditWriter.noop() : auditWriter;
        this.systemConfigRepository = systemConfigRepositoryProvider == null
                ? null
                : systemConfigRepositoryProvider.getIfAvailable();
    }

    @Scheduled(
            initialDelayString = "${app.normalization.llm-cleaning.work-queue.worker.initial-delay-ms:30000}",
            fixedDelayString = "${app.normalization.llm-cleaning.work-queue.worker.fixed-delay-ms:5000}")
    void runScheduled() {
        ScheduledTriggers.submit(triggerExecutor, () -> runOnce(), log, "normalization.llm-cleaning.run");
    }

    @Scheduled(
            initialDelayString = "${app.normalization.llm-cleaning.work-queue.worker.heartbeat-initial-delay-ms:5000}",
            fixedDelayString = "${app.normalization.llm-cleaning.work-queue.worker.heartbeat-fixed-delay-ms:15000}")
    void heartbeatScheduled() {
        ScheduledTriggers.submit(triggerExecutor, this::heartbeatIfEnabled, log, "normalization.llm-cleaning.heartbeat");
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
            return runOnceLocked();
        } finally {
            running.set(false);
        }
    }

    private int runOnceLocked() {
        int effectiveBatchSize = Math.max(1, batchSize);
        heartbeat();
        List<RuntimeWorkItem> items = workQueue.claim(
                LlmCleaningWorkTypes.RAW_TERM,
                workerId(),
                effectiveBatchSize,
                Duration.ofSeconds(Math.max(1, visibilitySeconds)));
        if (items.isEmpty()) {
            return 0;
        }
        process(items);
        return items.size();
    }

    private void heartbeat() {
        workQueue.heartbeatConsumer(
                LlmCleaningWorkTypes.RAW_TERM,
                workerId(),
                Duration.ofSeconds(Math.max(1, visibilitySeconds)).plusMinutes(1));
    }

    private void heartbeatIfEnabled() {
        if (workerEnabled()) {
            heartbeat();
        }
    }

    private void process(List<RuntimeWorkItem> items) {
        Instant startedAt = Instant.now();
        Map<LlmCleaningKind, List<ClaimedWork>> byKind = new LinkedHashMap<>();
        for (RuntimeWorkItem item : items) {
            try {
                LlmCleaningWorkPayload payload = objectMapper.readValue(item.payload(), LlmCleaningWorkPayload.class);
                LlmCleaningKind kind = LlmCleaningKind.parse(payload.kind());
                byKind.computeIfAbsent(kind, ignored -> new ArrayList<>())
                        .add(new ClaimedWork(item, payload.rawId()));
            } catch (RuntimeException | JsonProcessingException ex) {
                failRetryable(item, startedAt, null, ex.getClass().getSimpleName() + ": " + ex.getMessage());
            }
        }
        for (Map.Entry<LlmCleaningKind, List<ClaimedWork>> entry : byKind.entrySet()) {
            LlmCleaningKind kind = entry.getKey();
            List<ClaimedWork> work = entry.getValue();
            try {
                LlmCleaningService.LlmCleaningResult result = cleaningService.cleanQueued(
                        kind,
                        work.stream().map(ClaimedWork::rawId).toList());
                finish(work, startedAt, kind, result);
            } catch (RuntimeException ex) {
                for (ClaimedWork claimed : work) {
                    failRetryable(claimed.item(), startedAt, claimed.rawId(),
                            ex.getClass().getSimpleName() + ": " + ex.getMessage());
                }
            }
        }
    }

    private void finish(
            List<ClaimedWork> work,
            Instant startedAt,
            LlmCleaningKind kind,
            LlmCleaningService.LlmCleaningResult result) {
        boolean retryableSkip = result != null
                && "SKIPPED".equals(result.status())
                && isRetryableSkipReason(result.reason());
        for (ClaimedWork claimed : work) {
            if (retryableSkip) {
                failRetryable(claimed.item(), startedAt, claimed.rawId(), result.reason());
            } else {
                workQueue.complete(claimed.item());
                recordAudit(WorkerJobAuditEvent.succeeded(
                        JOB_TYPE,
                        jobName(kind),
                        claimed.rawId().toString(),
                        elapsedSince(startedAt)));
            }
        }
    }

    private void failRetryable(RuntimeWorkItem item, Instant startedAt, UUID rawId, String reason) {
        boolean retryable = item.attempt() < Math.max(1, maxRetries);
        workQueue.fail(item, retryable, retryDelay(item.attempt()), reason);
        recordAudit(WorkerJobAuditEvent.failed(
                JOB_TYPE,
                JOB_NAME,
                rawId == null ? item.aggregateId() : rawId.toString(),
                elapsedSince(startedAt),
                new LlmCleaningWorkQueueException(reason)));
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

    private void recordAudit(WorkerJobAuditEvent event) {
        try {
            auditWriter.record(event);
        } catch (RuntimeException ex) {
            log.warn("LLM cleaning work queue audit write failed: {}", ex.getMessage());
        }
    }

    private static String jobName(LlmCleaningKind kind) {
        String name = kind == null ? "unknown" : kind.name().toLowerCase(Locale.ROOT);
        return JOB_NAME + "." + name;
    }

    private static boolean isRetryableSkipReason(String reason) {
        return switch (reason == null ? "" : reason) {
            case "CREDENTIAL_MISSING", "PROVIDER_TIMEOUT", "PROVIDER_ERROR" -> true;
            default -> false;
        };
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
                log.warn("Unable to read LLM cleaning worker enabled config: {}", ex.getMessage());
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

    private record ClaimedWork(RuntimeWorkItem item, UUID rawId) {
    }

    private static class LlmCleaningWorkQueueException extends RuntimeException {
        LlmCleaningWorkQueueException(String message) {
            super(message);
        }
    }
}
