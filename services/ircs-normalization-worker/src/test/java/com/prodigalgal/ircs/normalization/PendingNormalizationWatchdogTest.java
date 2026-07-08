package com.prodigalgal.ircs.normalization;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.prodigalgal.ircs.common.audit.WorkerJobAuditWriter;
import com.prodigalgal.ircs.common.lease.ClusterLease;
import com.prodigalgal.ircs.common.lease.ClusterLeaseService;
import com.prodigalgal.ircs.common.pipeline.PipelineRuntimeWorkTypes;
import com.prodigalgal.ircs.common.work.RuntimeWorkItemRequest;
import com.prodigalgal.ircs.common.work.RuntimeWorkQueue;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;

@ExtendWith(MockitoExtension.class)
class PendingNormalizationWatchdogTest {

    @Mock
    private RawVideoNormalizationRepository repository;

    @Mock
    private RuntimeWorkQueue workQueue;

    @Mock
    private NormalizationConfigValues configValues;

    @Mock
    private ClusterLeaseService clusterLeaseService;

    @Mock
    private WorkerJobAuditWriter auditWriter;

    private ClusterLease lease;
    private PendingNormalizationWatchdog watchdog;

    @BeforeEach
    void setUp() {
        lease = new ClusterLease(
                PendingNormalizationWatchdog.LEASE_NAME,
                "worker-a",
                "lease-token",
                Instant.now(),
                Instant.now().plusSeconds(30));
        watchdog = new PendingNormalizationWatchdog(
                repository,
                workQueue,
                configValues,
                provider(clusterLeaseService),
                auditWriter,
                "ircs-normalization-worker",
                "worker-a");
    }

    @Test
    void submitsPendingRowsWhenNoOpenPipelineTaskExists() {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        when(configValues.pendingWatchdogEnabled()).thenReturn(true);
        when(configValues.pendingWatchdogLeaseTtl()).thenReturn(Duration.ofSeconds(30));
        when(configValues.pendingWatchdogMinPendingAge()).thenReturn(Duration.ofMinutes(5));
        when(configValues.pendingWatchdogBatchSize()).thenReturn(50);
        when(clusterLeaseService.tryAcquire(
                        PendingNormalizationWatchdog.LEASE_NAME,
                        "worker-a",
                        Duration.ofSeconds(30)))
                .thenReturn(Optional.of(lease));
        when(repository.findPendingNormalizationQueueItems(any(Instant.class), org.mockito.ArgumentMatchers.eq(50)))
                .thenReturn(List.of(
                        new RawVideoNormalizationRepository.RawVideoQueueItem(first, "hash-1"),
                        new RawVideoNormalizationRepository.RawVideoQueueItem(second, "hash-2")));
        when(workQueue.hasOpenTask(any(), any())).thenReturn(false);

        int submitted = watchdog.reconcilePending();

        assertEquals(2, submitted);
        ArgumentCaptor<RuntimeWorkItemRequest> requestCaptor = ArgumentCaptor.forClass(RuntimeWorkItemRequest.class);
        verify(workQueue, org.mockito.Mockito.times(2)).submit(requestCaptor.capture());
        assertEquals(List.of(first, second), requestCaptor.getAllValues().stream()
                .map(RuntimeWorkItemRequest::aggregateId)
                .map(UUID::fromString)
                .toList());
        assertEquals(List.of(PipelineRuntimeWorkTypes.NORMALIZE_VIDEO, PipelineRuntimeWorkTypes.NORMALIZE_VIDEO),
                requestCaptor.getAllValues().stream()
                        .map(RuntimeWorkItemRequest::taskType)
                        .toList());
        verify(clusterLeaseService).release(lease);
        verify(auditWriter).record(any());
    }

    @Test
    void skipsRowsThatAlreadyHavePendingOrInflightTask() {
        UUID rawVideoId = UUID.randomUUID();
        when(configValues.pendingWatchdogEnabled()).thenReturn(true);
        when(configValues.pendingWatchdogLeaseTtl()).thenReturn(Duration.ofSeconds(30));
        when(configValues.pendingWatchdogMinPendingAge()).thenReturn(Duration.ofMinutes(5));
        when(configValues.pendingWatchdogBatchSize()).thenReturn(50);
        when(clusterLeaseService.tryAcquire(
                        PendingNormalizationWatchdog.LEASE_NAME,
                        "worker-a",
                        Duration.ofSeconds(30)))
                .thenReturn(Optional.of(lease));
        when(repository.findPendingNormalizationQueueItems(any(Instant.class), org.mockito.ArgumentMatchers.eq(50)))
                .thenReturn(List.of(new RawVideoNormalizationRepository.RawVideoQueueItem(rawVideoId, "hash-1")));
        when(workQueue.hasOpenTask(any(), any())).thenReturn(true);

        int submitted = watchdog.reconcilePending();

        assertEquals(0, submitted);
        verify(workQueue, never()).submit(any(RuntimeWorkItemRequest.class));
        verify(auditWriter, never()).record(any());
    }

    @Test
    void disabledWatchdogDoesNotAcquireLeaseOrQueryDatabase() {
        when(configValues.pendingWatchdogEnabled()).thenReturn(false);

        int submitted = watchdog.reconcilePending();

        assertEquals(0, submitted);
        verify(clusterLeaseService, never()).tryAcquire(any(), any(), any());
        verify(repository, never()).findPendingNormalizationQueueItems(any(), org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    void heldLeaseSkipsThisRound() {
        when(configValues.pendingWatchdogEnabled()).thenReturn(true);
        when(configValues.pendingWatchdogLeaseTtl()).thenReturn(Duration.ofSeconds(30));
        when(clusterLeaseService.tryAcquire(
                        PendingNormalizationWatchdog.LEASE_NAME,
                        "worker-a",
                        Duration.ofSeconds(30)))
                .thenReturn(Optional.empty());

        int submitted = watchdog.reconcilePending();

        assertEquals(0, submitted);
        verify(repository, never()).findPendingNormalizationQueueItems(any(), org.mockito.ArgumentMatchers.anyInt());
    }

    private static ObjectProvider<ClusterLeaseService> provider(ClusterLeaseService leaseService) {
        return new ObjectProvider<>() {
            @Override
            public ClusterLeaseService getObject() {
                return leaseService;
            }
        };
    }
}
