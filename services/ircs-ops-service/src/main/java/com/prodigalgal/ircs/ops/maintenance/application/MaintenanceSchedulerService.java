package com.prodigalgal.ircs.ops.maintenance.application;

import com.prodigalgal.ircs.common.audit.WorkerJobAuditEvent;
import com.prodigalgal.ircs.common.audit.WorkerJobAuditWriter;
import com.prodigalgal.ircs.common.id.IrcsUuidGenerators;
import com.prodigalgal.ircs.common.lease.ClusterLease;
import com.prodigalgal.ircs.common.lease.ClusterLeaseService;
import com.prodigalgal.ircs.common.worker.WorkerInstanceIds;
import com.prodigalgal.ircs.ops.config.OpsConfigValues;
import com.prodigalgal.ircs.ops.maintenance.domain.MaintenanceRunResult;
import com.prodigalgal.ircs.ops.maintenance.domain.MaintenanceRunnerExecution;
import com.prodigalgal.ircs.ops.maintenance.domain.MaintenanceRiskLevel;
import com.prodigalgal.ircs.ops.maintenance.dto.MaintenanceRunnerMetadata;
import com.prodigalgal.ircs.ops.maintenance.dto.MaintenanceSchedulerRunResult;
import com.prodigalgal.ircs.ops.maintenance.dto.MaintenanceSchedulerStatusResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
@Slf4j
public class MaintenanceSchedulerService {

    static final String JOB_TYPE_MAINTENANCE_SCHEDULER = "maintenance-scheduler";
    static final String DISABLED_REASON = "skipped: maintenance scheduler is disabled";
    static final String ALREADY_RUNNING_REASON = "skipped: maintenance scheduler is already running";
    static final String EXECUTE_GATE_REFUSAL_REASON = "refused: maintenance scheduler execute gate is disabled";
    static final String CLUSTER_LEASE_HELD_REASON =
            "skipped: maintenance scheduler cluster lease is held by another instance";
    static final String CLUSTER_LEASE_UNAVAILABLE_REASON =
            "skipped: maintenance scheduler cluster lease is unavailable";
    static final String CLUSTER_LEASE_NAME = "ops-service:maintenance-scheduler";

    private final OpsConfigValues configValues;
    private final MaintenanceRunnerService runnerService;
    private final WorkerJobAuditWriter auditWriter;
    private final ClusterLeaseService clusterLeaseService;
    private final MaintenanceNotificationPublisher notificationPublisher;
    private final String workerId;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicReference<Snapshot> lastSnapshot = new AtomicReference<>(
            new Snapshot(null, null, null, List.of()));
    public MaintenanceSchedulerService(
            OpsConfigValues configValues,
            MaintenanceRunnerService runnerService,
            WorkerJobAuditWriter auditWriter,
            ClusterLeaseService clusterLeaseService,
            MaintenanceNotificationPublisher notificationPublisher,
            @Value("${spring.application.name:ircs-ops-service}") String applicationName,
            @Value("${app.worker.id:${APP_WORKER_ID:}}") String configuredWorkerId) {
        this.configValues = configValues;
        this.runnerService = runnerService;
        this.auditWriter = auditWriter;
        this.clusterLeaseService = clusterLeaseService;
        this.notificationPublisher = notificationPublisher;
        this.workerId = WorkerInstanceIds.resolve(applicationName, configuredWorkerId);
    }

    public MaintenanceSchedulerStatusResponse status() {
        return status(lastSnapshot.get(), running.get());
    }

    public MaintenanceSchedulerStatusResponse runOnce() {
        if (!configValues.maintenanceSchedulerEnabled()) {
            return status(new Snapshot(null, null, null, List.of(
                    MaintenanceSchedulerRunResult.skipped("scheduler", DISABLED_REASON))), false);
        }
        if (!running.compareAndSet(false, true)) {
            return status(new Snapshot(null, null, null, List.of(
                    MaintenanceSchedulerRunResult.skipped("scheduler", ALREADY_RUNNING_REASON))), true);
        }

        String correlationId = IrcsUuidGenerators.nextId().toString();
        Instant startedAt = Instant.now();
        ClusterLease clusterLease = null;
        Snapshot snapshot;
        try {
            try {
                clusterLease = acquireClusterLease(correlationId, startedAt);
            } catch (ClusterLeaseHeldException ex) {
                snapshot = snapshot(correlationId, startedAt, ex.getMessage());
                lastSnapshot.set(snapshot);
                return status(snapshot, false);
            } catch (RuntimeException ex) {
                String reason = CLUSTER_LEASE_UNAVAILABLE_REASON + ": " + ex.getMessage();
                auditWriter.record(WorkerJobAuditEvent.failed(
                        JOB_TYPE_MAINTENANCE_SCHEDULER,
                        "scheduler",
                        correlationId,
                        elapsedSince(startedAt),
                        new MaintenanceSchedulerRefusedException(reason)));
                snapshot = snapshot(correlationId, startedAt, reason);
                lastSnapshot.set(snapshot);
                return status(snapshot, false);
            }
            List<MaintenanceSchedulerRunResult> results = new ArrayList<>();
            for (String taskName : configValues.maintenanceSchedulerTasks()) {
                results.add(runTask(taskName, correlationId));
            }
            notificationPublisher.publishSchedulerRun(correlationId, results);
            snapshot = new Snapshot(correlationId, startedAt, Instant.now(), results);
            lastSnapshot.set(snapshot);
        } finally {
            releaseClusterLease(clusterLease);
            running.set(false);
        }
        return status(snapshot, false);
    }

    public void scheduledTick() {
        if (configValues.maintenanceSchedulerEnabled()) {
            runOnce();
        }
    }

    private MaintenanceSchedulerRunResult runTask(String taskName, String correlationId) {
        Instant startedAt = Instant.now();
        MaintenanceRunnerMetadata metadata = metadata(taskName);
        boolean dryRun = configValues.maintenanceSchedulerDryRun();
        if (dryRun) {
            auditWriter.record(WorkerJobAuditEvent.succeeded(
                    JOB_TYPE_MAINTENANCE_SCHEDULER,
                    metadata.taskName(),
                    correlationId,
                    elapsedSince(startedAt)));
            return MaintenanceSchedulerRunResult.dryRun(metadata);
        }
        if (!configValues.maintenanceSchedulerExecuteEnabled()) {
            auditWriter.record(WorkerJobAuditEvent.failed(
                    JOB_TYPE_MAINTENANCE_SCHEDULER,
                    metadata.taskName(),
                    correlationId,
                    elapsedSince(startedAt),
                    new MaintenanceSchedulerRefusedException(EXECUTE_GATE_REFUSAL_REASON)));
            return MaintenanceSchedulerRunResult.refused(metadata, false, EXECUTE_GATE_REFUSAL_REASON);
        }

        try {
            MaintenanceRunnerExecution execution = runnerService.run(taskName, correlationId);
            if (execution.refused()) {
                auditWriter.record(WorkerJobAuditEvent.failed(
                        JOB_TYPE_MAINTENANCE_SCHEDULER,
                        execution.metadata().taskName(),
                        correlationId,
                        elapsedSince(startedAt),
                        new MaintenanceSchedulerRefusedException(execution.reason())));
                return MaintenanceSchedulerRunResult.refused(execution.metadata(), false, execution.reason());
            }
            auditWriter.record(WorkerJobAuditEvent.succeeded(
                    JOB_TYPE_MAINTENANCE_SCHEDULER,
                    execution.metadata().taskName(),
                    correlationId,
                    elapsedSince(startedAt)));
            return MaintenanceSchedulerRunResult.executed(execution);
        } catch (RuntimeException ex) {
            auditWriter.record(WorkerJobAuditEvent.failed(
                    JOB_TYPE_MAINTENANCE_SCHEDULER,
                    metadata.taskName(),
                    correlationId,
                    elapsedSince(startedAt),
                    ex));
            return MaintenanceSchedulerRunResult.refused(metadata, false, "failed: " + ex.getMessage());
        }
    }

    private MaintenanceRunnerMetadata metadata(String taskName) {
        Optional<MaintenanceRunnerMetadata> metadata = runnerService.metadata(taskName);
        return metadata.orElseGet(() -> new MaintenanceRunnerMetadata(
                taskName,
                MaintenanceRiskLevel.HIGH,
                false,
                false,
                0,
                0,
                MaintenanceRunnerService.UNKNOWN_REFUSAL_REASON,
                List.of()));
    }

    private ClusterLease acquireClusterLease(String correlationId, Instant startedAt) {
        if (!configValues.maintenanceSchedulerClusterLeaseEnabled()) {
            return null;
        }
        return clusterLeaseService.tryAcquire(
                        CLUSTER_LEASE_NAME,
                        workerId,
                        configValues.maintenanceSchedulerClusterLeaseTtl())
                .orElseThrow(() -> new ClusterLeaseHeldException(CLUSTER_LEASE_HELD_REASON
                        + " (workerId=" + workerId + ", correlationId=" + correlationId
                        + ", waitedMs=" + elapsedSince(startedAt).toMillis() + ")"));
    }

    private void releaseClusterLease(ClusterLease clusterLease) {
        if (clusterLease == null) {
            return;
        }
        try {
            if (!clusterLeaseService.release(clusterLease)) {
                log.warn("Maintenance scheduler cluster lease was not released because the token no longer matched");
            }
        } catch (RuntimeException ex) {
            log.warn("Maintenance scheduler cluster lease release failed: {}", ex.getMessage());
        }
    }

    private Snapshot snapshot(String correlationId, Instant startedAt, String reason) {
        return new Snapshot(correlationId, startedAt, Instant.now(), List.of(
                MaintenanceSchedulerRunResult.skipped("scheduler", reason)));
    }

    private MaintenanceSchedulerStatusResponse status(Snapshot snapshot, boolean runningNow) {
        return new MaintenanceSchedulerStatusResponse(
                configValues.maintenanceSchedulerEnabled(),
                configValues.maintenanceSchedulerDryRun(),
                configValues.maintenanceSchedulerExecuteEnabled(),
                runningNow,
                configValues.maintenanceSchedulerClusterLeaseEnabled(),
                workerId,
                configValues.maintenanceSchedulerTasks(),
                snapshot.lastCorrelationId(),
                snapshot.lastStartedAt(),
                snapshot.lastFinishedAt(),
                snapshot.lastResults());
    }

    private static Duration elapsedSince(Instant startedAt) {
        return Duration.between(startedAt, Instant.now());
    }

    private record Snapshot(
            String lastCorrelationId,
            Instant lastStartedAt,
            Instant lastFinishedAt,
            List<MaintenanceSchedulerRunResult> lastResults
    ) {
        private Snapshot {
            lastResults = lastResults == null ? List.of() : List.copyOf(lastResults);
        }
    }

    private static class MaintenanceSchedulerRefusedException extends RuntimeException {
        MaintenanceSchedulerRefusedException(String message) {
            super(message);
        }
    }

    private static class ClusterLeaseHeldException extends RuntimeException {
        ClusterLeaseHeldException(String message) {
            super(message);
        }
    }
}
