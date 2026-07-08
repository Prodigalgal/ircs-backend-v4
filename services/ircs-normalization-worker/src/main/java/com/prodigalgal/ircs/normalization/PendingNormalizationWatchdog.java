package com.prodigalgal.ircs.normalization;

import com.prodigalgal.ircs.common.audit.WorkerJobAuditEvent;
import com.prodigalgal.ircs.common.audit.WorkerJobAuditWriter;
import com.prodigalgal.ircs.common.lease.ClusterLease;
import com.prodigalgal.ircs.common.lease.ClusterLeaseService;
import com.prodigalgal.ircs.common.pipeline.PipelineRuntimeWorkTypes;
import com.prodigalgal.ircs.common.scheduling.ScheduledTriggers;
import com.prodigalgal.ircs.common.work.RuntimeWorkItemRequest;
import com.prodigalgal.ircs.common.work.RuntimeWorkQueue;
import com.prodigalgal.ircs.common.worker.WorkerInstanceIds;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@Slf4j
class PendingNormalizationWatchdog {

    static final String JOB_TYPE = "normalization-watchdog";
    static final String JOB_NAME = "pending-normalization-reconcile";
    static final String LEASE_NAME = "normalization:pending-watchdog";

    private final RawVideoNormalizationRepository repository;
    private final RuntimeWorkQueue workQueue;
    private final NormalizationConfigValues configValues;
    private final ObjectProvider<ClusterLeaseService> clusterLeaseServiceProvider;
    private final WorkerJobAuditWriter auditWriter;
    private final String workerId;
    private final ExecutorService triggerExecutor = ScheduledTriggers.virtualThreadExecutor(
            "ircs-pending-normalization-watchdog-vt-");
    private final AtomicBoolean running = new AtomicBoolean(false);

    PendingNormalizationWatchdog(
            RawVideoNormalizationRepository repository,
            RuntimeWorkQueue workQueue,
            NormalizationConfigValues configValues,
            ObjectProvider<ClusterLeaseService> clusterLeaseServiceProvider,
            WorkerJobAuditWriter auditWriter,
            @Value("${spring.application.name:ircs-normalization-worker}") String applicationName,
            @Value("${app.worker.id:${APP_WORKER_ID:}}") String configuredWorkerId) {
        this.repository = repository;
        this.workQueue = workQueue;
        this.configValues = configValues;
        this.clusterLeaseServiceProvider = clusterLeaseServiceProvider;
        this.auditWriter = auditWriter == null ? WorkerJobAuditWriter.noop() : auditWriter;
        this.workerId = WorkerInstanceIds.resolve(applicationName, configuredWorkerId);
    }

    @Scheduled(
            initialDelayString = "${app.normalization.pending-watchdog.initial-delay-ms:30000}",
            fixedDelayString = "${app.normalization.pending-watchdog.fixed-delay-ms:60000}")
    public void scheduledTick() {
        ScheduledTriggers.submit(triggerExecutor, () -> reconcilePending(), log, "normalization.pending-watchdog.run");
    }

    @PreDestroy
    void shutdownTriggerExecutor() {
        triggerExecutor.shutdownNow();
    }

    int reconcilePending() {
        if (!configValues.pendingWatchdogEnabled() || !running.compareAndSet(false, true)) {
            return 0;
        }
        try {
            Optional<ClusterLease> lease = acquireLease();
            if (lease.isEmpty()) {
                log.debug("Pending normalization watchdog skipped because cluster lease is held: workerId={}", workerId);
                return 0;
            }
            Instant startedAt = Instant.now();
            try {
                int submitted = submitMissingPendingTasks();
                recordSucceeded(startedAt, submitted);
                return submitted;
            } catch (RuntimeException ex) {
                recordFailed(startedAt, ex);
                throw ex;
            } finally {
                releaseLease(lease.get());
            }
        } finally {
            running.set(false);
        }
    }

    private int submitMissingPendingTasks() {
        Instant updatedBefore = Instant.now().minus(configValues.pendingWatchdogMinPendingAge());
        List<RawVideoNormalizationRepository.RawVideoQueueItem> items =
                repository.findPendingNormalizationQueueItems(updatedBefore, configValues.pendingWatchdogBatchSize());
        int submitted = 0;
        for (RawVideoNormalizationRepository.RawVideoQueueItem item : items) {
            RuntimeWorkItemRequest request = normalizeRequest(item);
            if (workQueue.hasOpenTask(request.taskType(), request.taskId())) {
                continue;
            }
            workQueue.submit(request);
            submitted++;
        }
        if (submitted > 0) {
            log.info(
                    "Submitted missing pending normalization tasks: submitted={}, scanned={}, workerId={}",
                    submitted,
                    items.size(),
                    workerId);
        }
        return submitted;
    }

    private static RuntimeWorkItemRequest normalizeRequest(RawVideoNormalizationRepository.RawVideoQueueItem item) {
        return new RuntimeWorkItemRequest(
                PipelineRuntimeWorkTypes.NORMALIZE_VIDEO,
                PipelineRuntimeWorkTypes.normalizeTaskId(item.id(), item.dataHash()),
                item.id().toString(),
                PipelineRuntimeWorkTypes.normalizeVersion(item.dataHash()),
                "");
    }

    private Optional<ClusterLease> acquireLease() {
        ClusterLeaseService leaseService = clusterLeaseServiceProvider == null
                ? null
                : clusterLeaseServiceProvider.getIfAvailable();
        if (leaseService == null) {
            return Optional.of(new ClusterLease(
                    LEASE_NAME,
                    workerId,
                    "local",
                    Instant.now(),
                    Instant.now().plus(configValues.pendingWatchdogLeaseTtl())));
        }
        try {
            return leaseService.tryAcquire(LEASE_NAME, workerId, configValues.pendingWatchdogLeaseTtl());
        } catch (RuntimeException ex) {
            log.warn("Pending normalization watchdog lease unavailable, falling back to local run: {}", ex.getMessage());
            return Optional.of(new ClusterLease(
                    LEASE_NAME,
                    workerId,
                    "local",
                    Instant.now(),
                    Instant.now().plus(configValues.pendingWatchdogLeaseTtl())));
        }
    }

    private void releaseLease(ClusterLease lease) {
        if ("local".equals(lease.token())) {
            return;
        }
        ClusterLeaseService leaseService = clusterLeaseServiceProvider == null
                ? null
                : clusterLeaseServiceProvider.getIfAvailable();
        if (leaseService == null) {
            return;
        }
        try {
            leaseService.release(lease);
        } catch (RuntimeException ex) {
            log.warn("Pending normalization watchdog lease release failed: {}", ex.getMessage());
        }
    }

    private void recordSucceeded(Instant startedAt, int submitted) {
        if (submitted <= 0) {
            return;
        }
        recordAudit(WorkerJobAuditEvent.succeeded(
                JOB_TYPE,
                JOB_NAME,
                "submitted=" + submitted,
                elapsedSince(startedAt)));
    }

    private void recordFailed(Instant startedAt, RuntimeException error) {
        recordAudit(WorkerJobAuditEvent.failed(
                JOB_TYPE,
                JOB_NAME,
                workerId,
                elapsedSince(startedAt),
                error));
    }

    private void recordAudit(WorkerJobAuditEvent event) {
        try {
            auditWriter.record(event);
        } catch (RuntimeException ex) {
            log.warn("Pending normalization watchdog audit write failed: {}", ex.getMessage());
        }
    }

    private static Duration elapsedSince(Instant startedAt) {
        return Duration.between(startedAt, Instant.now());
    }
}
