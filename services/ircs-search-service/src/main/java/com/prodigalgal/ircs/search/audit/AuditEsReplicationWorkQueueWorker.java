package com.prodigalgal.ircs.search.audit;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.prodigalgal.ircs.common.audit.AuditReplicationWorkPayload;
import com.prodigalgal.ircs.common.audit.AuditReplicationWorkTypes;
import com.prodigalgal.ircs.common.scheduling.ScheduledTriggers;
import com.prodigalgal.ircs.common.work.RuntimeWorkItem;
import com.prodigalgal.ircs.common.work.RuntimeWorkQueue;
import com.prodigalgal.ircs.common.worker.WorkerInstanceIds;
import com.prodigalgal.ircs.search.config.SearchSchedulingConfiguration;
import com.prodigalgal.ircs.search.document.AuditEventSearchDocument;
import com.prodigalgal.ircs.search.index.SearchIndexService;
import com.prodigalgal.ircs.search.support.SystemConfigRepository;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@Slf4j
class AuditEsReplicationWorkQueueWorker {

    private static final String REPLICATION_ENABLED_KEY = "app.audit.es-replication.enabled";
    private static final String ENABLED_KEY = "app.search.audit-es-replication.worker.enabled";
    private static final long ENABLED_CACHE_TTL_NANOS = Duration.ofSeconds(30).toNanos();

    private final RuntimeWorkQueue workQueue;
    private final ObjectMapper objectMapper;
    private final AuditEsReplicationRepository repository;
    private final SearchIndexService searchIndexService;
    private final SystemConfigRepository systemConfigRepository;
    private final Executor workerExecutor;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile Boolean cachedEnabled;
    private volatile long cachedEnabledExpiresAtNanos;

    @Value("${app.audit.es-replication.enabled:true}")
    private boolean replicationEnabledByDeployment = true;

    @Value("${app.search.audit-es-replication.worker.enabled:true}")
    private boolean enabledByDeployment = true;

    @Value("${app.search.audit-es-replication.worker.batch-size:100}")
    private int batchSize;

    @Value("${app.search.audit-es-replication.worker.visibility-seconds:600}")
    private long visibilitySeconds;

    @Value("${app.search.audit-es-replication.worker.max-retries:8}")
    private int maxRetries;

    @Value("${app.search.audit-es-replication.worker.max-backoff-seconds:900}")
    private long maxBackoffSeconds;

    @Value("${app.search.audit-es-replication.worker.worker-id:${APP_SEARCH_AUDIT_ES_REPLICATION_WORKER_ID:}}")
    private String workerId;

    @Value("${spring.application.name:ircs-search-service}")
    private String applicationName;

    AuditEsReplicationWorkQueueWorker(
            RuntimeWorkQueue workQueue,
            ObjectMapper objectMapper,
            AuditEsReplicationRepository repository,
            SearchIndexService searchIndexService,
            ObjectProvider<SystemConfigRepository> systemConfigRepositoryProvider,
            @Qualifier(SearchSchedulingConfiguration.AUDIT_ES_REPLICATION_EXECUTOR) Executor workerExecutor) {
        this.workQueue = workQueue;
        this.objectMapper = objectMapper;
        this.repository = repository;
        this.searchIndexService = searchIndexService;
        this.systemConfigRepository = systemConfigRepositoryProvider == null
                ? null
                : systemConfigRepositoryProvider.getIfAvailable();
        this.workerExecutor = workerExecutor == null ? Runnable::run : workerExecutor;
    }

    @Scheduled(
            initialDelayString = "${app.search.audit-es-replication.worker.initial-delay-ms:10000}",
            fixedRateString = "${app.search.audit-es-replication.worker.fixed-rate-ms:1000}")
    void runScheduled() {
        ScheduledTriggers.submit(workerExecutor, () -> runOnce(), log, "search.audit-es-replication.run");
    }

    @Scheduled(
            initialDelayString = "${app.search.audit-es-replication.worker.heartbeat-initial-delay-ms:5000}",
            fixedRateString = "${app.search.audit-es-replication.worker.heartbeat-fixed-rate-ms:15000}")
    void heartbeatScheduled() {
        ScheduledTriggers.submit(workerExecutor, this::heartbeatIfEnabled, log, "search.audit-es-replication.heartbeat");
    }

    private void heartbeatIfEnabled() {
        if (workerEnabled()) {
            heartbeat(workerId(), visibility());
        }
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
        } catch (RuntimeException ex) {
            log.warn("Audit ES replication worker run failed: {}", ex.getMessage());
            return 0;
        } finally {
            running.set(false);
        }
    }

    private int runOnceLocked() {
        int effectiveBatchSize = Math.max(1, batchSize);
        Duration visibility = visibility();
        String owner = workerId();
        heartbeat(owner, visibility);
        List<RuntimeWorkItem> items = workQueue.claim(
                AuditReplicationWorkTypes.ES_REPLICATION,
                owner,
                effectiveBatchSize,
                visibility);
        int processed = 0;
        for (RuntimeWorkItem item : items) {
            heartbeat(owner, visibility);
            if (processItem(item)) {
                processed++;
            }
        }
        heartbeat(owner, visibility);
        return processed;
    }

    private Duration visibility() {
        return Duration.ofSeconds(Math.max(1, visibilitySeconds));
    }

    private boolean processItem(RuntimeWorkItem item) {
        try {
            AuditReplicationWorkPayload payload = objectMapper.readValue(item.payload(), AuditReplicationWorkPayload.class);
            Optional<AuditEventSearchDocument> document = repository.findDocument(payload);
            if (document.isPresent()) {
                searchIndexService.saveAudit(document.get());
            }
            workQueue.complete(item);
            return true;
        } catch (RuntimeException | JsonProcessingException ex) {
            boolean retryable = item.attempt() < Math.max(1, maxRetries);
            try {
                workQueue.fail(item, retryable, retryDelay(item.attempt()), ex.getClass().getSimpleName() + ": " + ex.getMessage());
            } catch (RuntimeException failEx) {
                log.warn("Audit ES replication fail write failed: {}", failEx.getMessage());
                throw failEx;
            }
            return false;
        }
    }

    private void heartbeat(String owner, Duration visibility) {
        workQueue.heartbeatConsumer(
                AuditReplicationWorkTypes.ES_REPLICATION,
                owner,
                visibility.plusMinutes(1));
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

    private boolean workerEnabled() {
        SystemConfigRepository configRepository = systemConfigRepository;
        if (configRepository == null) {
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
                configRepository.evict(REPLICATION_ENABLED_KEY);
                configRepository.evict(ENABLED_KEY);
                boolean replicationEnabled = configRepository.findValue(REPLICATION_ENABLED_KEY)
                        .map(raw -> parseBoolean(raw, replicationEnabledByDeployment))
                        .orElse(replicationEnabledByDeployment);
                boolean workerEnabled = configRepository.findValue(ENABLED_KEY)
                        .map(raw -> parseBoolean(raw, enabledByDeployment))
                        .orElse(enabledByDeployment);
                refreshed = replicationEnabled && workerEnabled;
            } catch (RuntimeException ex) {
                log.warn("Unable to read Audit ES replication worker enabled config: {}", ex.getMessage());
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
}
