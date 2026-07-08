package com.prodigalgal.ircs.metadata.provider.worker;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.prodigalgal.ircs.common.pipeline.PipelineRuntimeWorkTypes;
import com.prodigalgal.ircs.common.scheduling.ScheduledTriggers;
import com.prodigalgal.ircs.common.work.RuntimeWorkItem;
import com.prodigalgal.ircs.common.work.RuntimeWorkQueue;
import com.prodigalgal.ircs.common.worker.WorkerInstanceIds;
import com.prodigalgal.ircs.metadata.config.SystemConfigRepository;
import com.prodigalgal.ircs.metadata.dispatch.messaging.MetadataProviderTaskPublisher.MetadataProviderTaskPayload;
import com.prodigalgal.ircs.metadata.provider.application.MetadataProviderWorker;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@Slf4j
public class MetadataProviderWorkQueueWorker {

    private static final int MAX_DYNAMIC_BATCH_SIZE = 1000;
    private static final int MAX_DYNAMIC_PARALLELISM = 128;
    private static final String BATCH_SIZE_KEY = "app.metadata.valkey-provider-worker.batch-size";
    private static final String PARALLELISM_KEY = "app.metadata.valkey-provider-worker.parallelism";
    private static final long SETTINGS_CACHE_TTL_NANOS = Duration.ofSeconds(10).toNanos();
    private final RuntimeWorkQueue workQueue;
    private final MetadataProviderWorker providerWorker;
    private final ObjectMapper objectMapper;
    private final String workerId;
    private final int defaultBatchSize;
    private final int defaultParallelism;
    private final ExecutorService triggerExecutor =
            ScheduledTriggers.virtualThreadExecutor("ircs-metadata-provider-trigger-vt-");
    private final ThreadPoolExecutor executor;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final Duration visibilityTimeout;
    private final Duration retryDelay;
    private final SystemConfigRepository systemConfigRepository;
    private volatile WorkerSettings cachedSettings;
    private volatile long cachedSettingsExpiresAtNanos;

    MetadataProviderWorkQueueWorker(
            RuntimeWorkQueue workQueue,
            MetadataProviderWorker providerWorker,
            ObjectMapper objectMapper,
            ObjectProvider<SystemConfigRepository> systemConfigRepositoryProvider,
            @Value("${spring.application.name:ircs-metadata-worker}") String applicationName,
            @Value("${app.metadata.pipeline.worker-id:${APP_METADATA_PIPELINE_WORKER_ID:}}")
                    String configuredWorkerId,
            @Value("${app.metadata.valkey-provider-worker.batch-size:${APP_METADATA_VALKEY_PROVIDER_BATCH_SIZE:10}}")
                    int batchSize,
            @Value("${app.metadata.valkey-provider-worker.parallelism:${APP_METADATA_VALKEY_PROVIDER_PARALLELISM:1}}")
                    int parallelism,
            @Value("${app.metadata.valkey-provider-worker.visibility-timeout:PT10M}") Duration visibilityTimeout,
            @Value("${app.metadata.valkey-provider-worker.retry-delay:PT2M}") Duration retryDelay) {
        this.workQueue = workQueue;
        this.providerWorker = providerWorker;
        this.objectMapper = objectMapper;
        this.systemConfigRepository = systemConfigRepositoryProvider == null
                ? null
                : systemConfigRepositoryProvider.getIfAvailable();
        this.workerId = WorkerInstanceIds.resolve(applicationName, configuredWorkerId);
        this.defaultBatchSize = clamp(batchSize, 1, MAX_DYNAMIC_BATCH_SIZE);
        this.defaultParallelism = clamp(parallelism, 1, MAX_DYNAMIC_PARALLELISM);
        this.executor = new ThreadPoolExecutor(
                this.defaultParallelism,
                this.defaultParallelism,
                60,
                TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(),
                Thread.ofVirtual()
                        .name("metadata-provider-valkey-" + this.workerId + "-", 0)
                        .factory());
        this.visibilityTimeout = positive(visibilityTimeout, Duration.ofMinutes(10));
        this.retryDelay = positive(retryDelay, Duration.ofMinutes(2));
    }

    @Scheduled(
            initialDelayString = "${app.metadata.valkey-provider-worker.initial-delay-ms:10000}",
            fixedDelayString = "${app.metadata.valkey-provider-worker.fixed-delay-ms:1000}")
    public void runScheduled() {
        ScheduledTriggers.submit(triggerExecutor, () -> runOnce(), log, "metadata.provider.run");
    }

    @Scheduled(
            initialDelayString = "${app.metadata.valkey-provider-worker.heartbeat-initial-delay-ms:5000}",
            fixedDelayString = "${app.metadata.valkey-provider-worker.heartbeat-fixed-delay-ms:15000}")
    public void heartbeatScheduled() {
        ScheduledTriggers.submit(triggerExecutor, this::heartbeat, log, "metadata.provider.heartbeat");
    }

    int runOnce() {
        if (!running.compareAndSet(false, true)) {
            return 0;
        }
        try {
            WorkerSettings settings = workerSettings();
            int batchSize = settings.batchSize();
            int parallelism = applyParallelism(settings.parallelism());
            heartbeat();
            List<RuntimeWorkItem> tasks = workQueue.claim(
                    PipelineRuntimeWorkTypes.METADATA_PROVIDER,
                    workerId,
                    batchSize,
                    visibilityTimeout);
            processBatch(tasks, parallelism);
            return tasks.size();
        } finally {
            running.set(false);
        }
    }

    private void heartbeat() {
        workQueue.heartbeatConsumer(
                PipelineRuntimeWorkTypes.METADATA_PROVIDER,
                workerId,
                visibilityTimeout.plusMinutes(1));
    }

    private void processBatch(List<RuntimeWorkItem> tasks, int parallelism) {
        if (tasks.isEmpty()) {
            return;
        }
        if (parallelism <= 1 || tasks.size() == 1) {
            tasks.forEach(this::processSafely);
            return;
        }
        List<Future<?>> futures = new ArrayList<>(tasks.size());
        for (RuntimeWorkItem task : tasks) {
            futures.add(executor.submit(() -> processSafely(task)));
        }
        for (Future<?> future : futures) {
            try {
                future.get();
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception ex) {
                log.warn("Metadata provider runtime task future failed: {}", ex.getMessage());
            }
        }
    }

    private void processSafely(RuntimeWorkItem task) {
        try {
            process(task);
        } catch (RuntimeException ex) {
            log.warn("Metadata provider runtime task failed: taskId={}, reason={}", task.taskId(), ex.getMessage());
        }
    }

    private void process(RuntimeWorkItem task) {
        try {
            MetadataProviderTaskPayload payload =
                    objectMapper.reader()
                            .without(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                            .forType(MetadataProviderTaskPayload.class)
                            .readValue(task.payload());
            providerWorker.execute(payload.context(), payload.provider());
            workQueue.complete(task);
        } catch (RuntimeException ex) {
            workQueue.fail(task, true, retryDelay, ex.getClass().getSimpleName() + ": " + ex.getMessage());
            throw ex;
        } catch (Exception ex) {
            workQueue.fail(task, true, retryDelay, ex.getClass().getSimpleName() + ": " + ex.getMessage());
            throw new IllegalStateException("Unable to process metadata provider runtime task", ex);
        }
    }

    @PreDestroy
    void shutdown() {
        triggerExecutor.shutdownNow();
        executor.shutdownNow();
    }

    private static Duration positive(Duration value, Duration fallback) {
        return value == null || value.isZero() || value.isNegative() ? fallback : value;
    }

    private WorkerSettings workerSettings() {
        SystemConfigRepository repository = systemConfigRepository;
        if (repository == null) {
            return defaultSettings();
        }
        long now = System.nanoTime();
        WorkerSettings value = cachedSettings;
        if (value != null && now < cachedSettingsExpiresAtNanos) {
            return value;
        }
        synchronized (this) {
            value = cachedSettings;
            if (value != null && now < cachedSettingsExpiresAtNanos) {
                return value;
            }
            WorkerSettings refreshed = defaultSettings();
            try {
                refreshed = new WorkerSettings(
                        readConfig(repository, BATCH_SIZE_KEY)
                                .map(raw -> parsePositiveInt(raw, defaultBatchSize, MAX_DYNAMIC_BATCH_SIZE))
                                .orElse(defaultBatchSize),
                        readConfig(repository, PARALLELISM_KEY)
                                .map(raw -> parsePositiveInt(raw, defaultParallelism, MAX_DYNAMIC_PARALLELISM))
                                .orElse(defaultParallelism));
            } catch (RuntimeException ex) {
                log.warn("Unable to read metadata provider worker configs: {}", ex.getMessage());
            }
            cachedSettings = refreshed;
            cachedSettingsExpiresAtNanos = now + SETTINGS_CACHE_TTL_NANOS;
            return refreshed;
        }
    }

    private WorkerSettings defaultSettings() {
        return new WorkerSettings(defaultBatchSize, defaultParallelism);
    }

    private static java.util.Optional<String> readConfig(SystemConfigRepository repository, String key) {
        repository.evict(key);
        return repository.findValue(key);
    }

    private int applyParallelism(int value) {
        int resolved = clamp(value, 1, MAX_DYNAMIC_PARALLELISM);
        synchronized (executor) {
            if (resolved > executor.getMaximumPoolSize()) {
                executor.setMaximumPoolSize(resolved);
                executor.setCorePoolSize(resolved);
            } else {
                executor.setCorePoolSize(resolved);
                executor.setMaximumPoolSize(resolved);
            }
        }
        return resolved;
    }

    private static int parsePositiveInt(String raw, int defaultValue, int maxValue) {
        if (raw == null || raw.isBlank()) {
            return defaultValue;
        }
        try {
            int parsed = Integer.parseInt(raw.trim());
            return parsed > 0 ? clamp(parsed, 1, maxValue) : defaultValue;
        } catch (NumberFormatException ignored) {
            return defaultValue;
        }
    }

    private static int clamp(int value, int minValue, int maxValue) {
        return Math.min(Math.max(value, minValue), maxValue);
    }

    private record WorkerSettings(int batchSize, int parallelism) {
    }
}
