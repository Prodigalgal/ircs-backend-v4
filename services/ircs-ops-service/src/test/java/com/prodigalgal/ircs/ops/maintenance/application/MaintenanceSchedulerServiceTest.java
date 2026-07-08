package com.prodigalgal.ircs.ops.maintenance.application;


import com.prodigalgal.ircs.ops.maintenance.domain.MaintenanceRiskLevel;
import com.prodigalgal.ircs.ops.maintenance.domain.MaintenanceRunnerExecution;
import com.prodigalgal.ircs.ops.maintenance.domain.MaintenanceRunResult;
import com.prodigalgal.ircs.ops.maintenance.dto.MaintenanceRunnerMetadata;
import com.prodigalgal.ircs.ops.maintenance.dto.MaintenanceSchedulerRunResult;
import com.prodigalgal.ircs.ops.maintenance.dto.MaintenanceSchedulerStatusResponse;
import com.prodigalgal.ircs.ops.config.OpsConfigValues;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.prodigalgal.ircs.common.audit.WorkerJobAuditEvent;
import com.prodigalgal.ircs.common.audit.WorkerJobAuditWriter;
import com.prodigalgal.ircs.common.lease.ClusterLease;
import com.prodigalgal.ircs.common.lease.ClusterLeaseService;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class MaintenanceSchedulerServiceTest {

    private final OpsConfigValues configValues = org.mockito.Mockito.mock(OpsConfigValues.class);
    private final MaintenanceRunnerService runnerService = org.mockito.Mockito.mock(MaintenanceRunnerService.class);
    private final WorkerJobAuditWriter auditWriter = org.mockito.Mockito.mock(WorkerJobAuditWriter.class);
    private final FakeClusterLeaseService clusterLeaseService = new FakeClusterLeaseService();
    private final MaintenanceNotificationPublisher notificationPublisher =
            org.mockito.Mockito.mock(MaintenanceNotificationPublisher.class);
    private final MaintenanceSchedulerService schedulerService =
            new MaintenanceSchedulerService(
                    configValues,
                    runnerService,
                    auditWriter,
                    clusterLeaseService,
                    notificationPublisher,
                    "ircs-ops-service",
                    "test-worker");

    @BeforeEach
    void setUp() {
        when(configValues.maintenanceSchedulerTasks()).thenReturn(List.of("search-reindex-unified"));
        when(configValues.maintenanceSchedulerDryRun()).thenReturn(true);
        when(configValues.maintenanceSchedulerExecuteEnabled()).thenReturn(true);
        when(configValues.maintenanceSchedulerClusterLeaseEnabled()).thenReturn(true);
        when(configValues.maintenanceSchedulerClusterLeaseTtl()).thenReturn(Duration.ofMinutes(10));
        when(runnerService.metadata("search-reindex-unified")).thenReturn(Optional.of(metadata()));
    }

    @Test
    void disabledSchedulerDoesNotAuditOrRunOwners() {
        when(configValues.maintenanceSchedulerEnabled()).thenReturn(false);

        MaintenanceSchedulerStatusResponse status = schedulerService.runOnce();

        assertThat(status.enabled()).isFalse();
        assertThat(status.lastResults()).hasSize(1);
        assertThat(status.lastResults().getFirst().skipped()).isTrue();
        assertThat(clusterLeaseService.acquireAttempts).isZero();
        verify(runnerService, never()).run(eq("search-reindex-unified"), anyString());
        verify(auditWriter, never()).record(org.mockito.Mockito.any());
    }

    @Test
    void dryRunWritesSchedulerAuditAndDoesNotCallRunner() {
        when(configValues.maintenanceSchedulerEnabled()).thenReturn(true);
        when(configValues.maintenanceSchedulerDryRun()).thenReturn(true);

        MaintenanceSchedulerStatusResponse status = schedulerService.runOnce();

        assertThat(status.enabled()).isTrue();
        assertThat(status.running()).isFalse();
        assertThat(status.clusterLeaseEnabled()).isTrue();
        assertThat(status.workerId()).isEqualTo("test-worker");
        assertThat(status.lastCorrelationId()).isNotBlank();
        MaintenanceSchedulerRunResult result = status.lastResults().getFirst();
        assertThat(result.dryRun()).isTrue();
        assertThat(result.executed()).isFalse();
        assertThat(result.ownerSteps()).containsExactly(MaintenanceRunnerService.SEARCH_REINDEX_OWNER_STEP);
        verify(runnerService, never()).run(eq("search-reindex-unified"), anyString());

        WorkerJobAuditEvent event = captureAuditEvent();
        assertThat(event.jobType()).isEqualTo(MaintenanceSchedulerService.JOB_TYPE_MAINTENANCE_SCHEDULER);
        assertThat(event.jobName()).isEqualTo("search-reindex-unified");
        assertThat(event.status()).isEqualTo("succeeded");
        assertThat(event.correlationId()).isEqualTo(status.lastCorrelationId());
        assertThat(clusterLeaseService.acquireAttempts).isEqualTo(1);
        assertThat(clusterLeaseService.releaseAttempts).isEqualTo(1);
        verify(notificationPublisher).publishSchedulerRun(eq(status.lastCorrelationId()), org.mockito.Mockito.anyList());
    }

    @Test
    void clusterLeaseHeldSkipsBeforeOwnerCallAndDoesNotAudit() {
        when(configValues.maintenanceSchedulerEnabled()).thenReturn(true);
        clusterLeaseService.acquire = false;

        MaintenanceSchedulerStatusResponse status = schedulerService.runOnce();

        MaintenanceSchedulerRunResult result = status.lastResults().getFirst();
        assertThat(result.skipped()).isTrue();
        assertThat(result.reason()).contains(MaintenanceSchedulerService.CLUSTER_LEASE_HELD_REASON);
        assertThat(status.running()).isFalse();
        verify(runnerService, never()).run(eq("search-reindex-unified"), anyString());
        verify(auditWriter, never()).record(org.mockito.Mockito.any());
        assertThat(clusterLeaseService.acquireAttempts).isEqualTo(1);
        assertThat(clusterLeaseService.releaseAttempts).isZero();
    }

    @Test
    void executeGateDisabledRefusesBeforeOwnerCall() {
        when(configValues.maintenanceSchedulerEnabled()).thenReturn(true);
        when(configValues.maintenanceSchedulerDryRun()).thenReturn(false);
        when(configValues.maintenanceSchedulerExecuteEnabled()).thenReturn(false);

        MaintenanceSchedulerStatusResponse status = schedulerService.runOnce();

        MaintenanceSchedulerRunResult result = status.lastResults().getFirst();
        assertThat(result.refused()).isTrue();
        assertThat(result.reason()).isEqualTo(MaintenanceSchedulerService.EXECUTE_GATE_REFUSAL_REASON);
        verify(runnerService, never()).run(eq("search-reindex-unified"), anyString());

        WorkerJobAuditEvent event = captureAuditEvent();
        assertThat(event.status()).isEqualTo("failed");
        assertThat(event.error()).hasMessage(MaintenanceSchedulerService.EXECUTE_GATE_REFUSAL_REASON);
    }

    @Test
    void executeModeDelegatesToRunnerAndRecordsSchedulerAudit() {
        when(configValues.maintenanceSchedulerEnabled()).thenReturn(true);
        when(configValues.maintenanceSchedulerDryRun()).thenReturn(false);
        when(configValues.maintenanceSchedulerExecuteEnabled()).thenReturn(true);
        MaintenanceRunResult runResult = new MaintenanceRunResult(
                "search-reindex-unified",
                2,
                2,
                List.of(UUID.randomUUID(), UUID.randomUUID()));
        when(runnerService.run(eq("search-reindex-unified"), anyString()))
                .thenReturn(MaintenanceRunnerExecution.executed(metadata(), runResult));

        MaintenanceSchedulerStatusResponse status = schedulerService.runOnce();

        MaintenanceSchedulerRunResult result = status.lastResults().getFirst();
        assertThat(result.executed()).isTrue();
        assertThat(result.selectedCount()).isEqualTo(2);
        assertThat(result.publishedCount()).isEqualTo(2);
        verify(runnerService).run(eq("search-reindex-unified"), eq(status.lastCorrelationId()));

        WorkerJobAuditEvent event = captureAuditEvent();
        assertThat(event.jobType()).isEqualTo(MaintenanceSchedulerService.JOB_TYPE_MAINTENANCE_SCHEDULER);
        assertThat(event.status()).isEqualTo("succeeded");
        assertThat(clusterLeaseService.releaseAttempts).isEqualTo(1);
    }

    private WorkerJobAuditEvent captureAuditEvent() {
        ArgumentCaptor<WorkerJobAuditEvent> captor = ArgumentCaptor.forClass(WorkerJobAuditEvent.class);
        verify(auditWriter).record(captor.capture());
        return captor.getValue();
    }

    private static MaintenanceRunnerMetadata metadata() {
        return new MaintenanceRunnerMetadata(
                "search-reindex-unified",
                MaintenanceRiskLevel.LOW,
                true,
                true,
                MaintenanceReindexCommand.DEFAULT_DEV_LIMIT,
                MaintenanceReindexCommand.MAX_DEV_LIMIT,
                "",
                List.of(MaintenanceRunnerService.SEARCH_REINDEX_OWNER_STEP));
    }

    private static class FakeClusterLeaseService implements ClusterLeaseService {

        boolean acquire = true;
        int acquireAttempts;
        int releaseAttempts;

        @Override
        public Optional<ClusterLease> tryAcquire(String name, String ownerId, Duration ttl) {
            acquireAttempts++;
            if (!acquire) {
                return Optional.empty();
            }
            Instant now = Instant.parse("2026-06-11T12:00:00Z");
            return Optional.of(new ClusterLease(name, ownerId, ownerId + ":token", now, now.plus(ttl)));
        }

        @Override
        public boolean renew(ClusterLease lease, Duration ttl) {
            return true;
        }

        @Override
        public boolean release(ClusterLease lease) {
            releaseAttempts++;
            return true;
        }
    }
}
