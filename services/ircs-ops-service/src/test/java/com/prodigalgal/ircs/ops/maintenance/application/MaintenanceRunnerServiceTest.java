package com.prodigalgal.ircs.ops.maintenance.application;


import com.prodigalgal.ircs.ops.maintenance.domain.MaintenanceRiskLevel;
import com.prodigalgal.ircs.ops.maintenance.domain.MaintenanceRunnerExecution;
import com.prodigalgal.ircs.ops.maintenance.domain.MaintenanceRunResult;
import com.prodigalgal.ircs.ops.maintenance.dto.MaintenanceRunnerMetadata;
import com.prodigalgal.ircs.ops.config.OpsConfigValues;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.prodigalgal.ircs.common.audit.WorkerJobAuditEvent;
import com.prodigalgal.ircs.common.audit.WorkerJobAuditWriter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class MaintenanceRunnerServiceTest {

    private final MaintenanceReindexCommand reindexCommand =
            org.mockito.Mockito.mock(MaintenanceReindexCommand.class);
    private final MaintenanceNormalizationClient normalizationClient =
            org.mockito.Mockito.mock(MaintenanceNormalizationClient.class);
    private final MaintenanceAggregationClient aggregationClient =
            org.mockito.Mockito.mock(MaintenanceAggregationClient.class);
    private final MaintenanceTrendSyncClient trendSyncClient =
            org.mockito.Mockito.mock(MaintenanceTrendSyncClient.class);
    private final WorkerJobAuditWriter auditWriter = org.mockito.Mockito.mock(WorkerJobAuditWriter.class);
    private final MaintenanceRunnerService runnerService =
            new MaintenanceRunnerService(reindexCommand, normalizationClient, aggregationClient, trendSyncClient, auditWriter);

    @Test
    void registryExposesRiskDevDryRunAndLimitMetadata() {
        List<MaintenanceRunnerMetadata> registry = runnerService.registry();

        MaintenanceRunnerMetadata unified = find(registry, "search-reindex-unified");
        assertEquals(MaintenanceRiskLevel.LOW, unified.riskLevel());
        assertTrue(unified.devAllowed());
        assertTrue(unified.supportsDryRun());
        assertEquals(MaintenanceReindexCommand.DEFAULT_DEV_LIMIT, unified.defaultLimit());
        assertEquals(MaintenanceReindexCommand.MAX_DEV_LIMIT, unified.maxLimit());
        assertEquals(List.of(MaintenanceRunnerService.SEARCH_REINDEX_OWNER_STEP), unified.ownerSteps());

        MaintenanceRunnerMetadata raw = find(registry, "search-reindex-raw");
        assertEquals(MaintenanceRiskLevel.LOW, raw.riskLevel());
        assertTrue(raw.devAllowed());
        assertTrue(raw.supportsDryRun());
        assertEquals(MaintenanceReindexCommand.DEFAULT_DEV_LIMIT, raw.defaultLimit());
        assertEquals(MaintenanceReindexCommand.MAX_DEV_LIMIT, raw.maxLimit());
        assertEquals(List.of(MaintenanceRunnerService.RAW_SEARCH_REINDEX_OWNER_STEP), raw.ownerSteps());

        MaintenanceRunnerMetadata unifiedAll = find(registry, "search-reindex-unified-all");
        assertEquals(MaintenanceRiskLevel.HIGH, unifiedAll.riskLevel());
        assertTrue(unifiedAll.devAllowed());
        assertTrue(unifiedAll.supportsDryRun());
        assertEquals(MaintenanceReindexCommand.DEFAULT_BATCH_SIZE, unifiedAll.defaultLimit());
        assertEquals(MaintenanceReindexCommand.MAX_BATCH_SIZE, unifiedAll.maxLimit());
        assertEquals(List.of(MaintenanceRunnerService.SEARCH_REINDEX_ALL_OWNER_STEP), unifiedAll.ownerSteps());

        MaintenanceRunnerMetadata rawAll = find(registry, "search-reindex-raw-all");
        assertEquals(MaintenanceRiskLevel.HIGH, rawAll.riskLevel());
        assertTrue(rawAll.devAllowed());
        assertTrue(rawAll.supportsDryRun());
        assertEquals(MaintenanceReindexCommand.DEFAULT_BATCH_SIZE, rawAll.defaultLimit());
        assertEquals(MaintenanceReindexCommand.MAX_BATCH_SIZE, rawAll.maxLimit());
        assertEquals(List.of(MaintenanceRunnerService.RAW_SEARCH_REINDEX_ALL_OWNER_STEP), rawAll.ownerSteps());

        MaintenanceRunnerMetadata unifiedRecalculate = find(registry, "unified-recalculate");
        assertEquals(MaintenanceRiskLevel.HIGH, unifiedRecalculate.riskLevel());
        assertTrue(unifiedRecalculate.devAllowed());
        assertTrue(unifiedRecalculate.supportsDryRun());
        assertEquals(OpsConfigValues.DEFAULT_AGGREGATION_DEV_LIMIT, unifiedRecalculate.defaultLimit());
        assertEquals(OpsConfigValues.MAX_AGGREGATION_DEV_LIMIT, unifiedRecalculate.maxLimit());
        assertEquals(List.of(MaintenanceRunnerService.UNIFIED_RECALCULATE_OWNER_STEP),
                unifiedRecalculate.ownerSteps());

        MaintenanceRunnerMetadata pendingBackfill = find(registry, "aggregation-pending-backfill");
        assertEquals(MaintenanceRiskLevel.LOW, pendingBackfill.riskLevel());
        assertTrue(pendingBackfill.devAllowed());
        assertTrue(pendingBackfill.supportsDryRun());
        assertEquals(OpsConfigValues.DEFAULT_AGGREGATION_DEV_LIMIT, pendingBackfill.defaultLimit());
        assertEquals(OpsConfigValues.MAX_AGGREGATION_DEV_LIMIT, pendingBackfill.maxLimit());
        assertEquals(List.of(MaintenanceRunnerService.AGGREGATION_PENDING_BACKFILL_OWNER_STEP),
                pendingBackfill.ownerSteps());

        MaintenanceRunnerMetadata coverBackfill = find(registry, "aggregation-cover-backfill");
        assertEquals(MaintenanceRiskLevel.LOW, coverBackfill.riskLevel());
        assertTrue(coverBackfill.devAllowed());
        assertTrue(coverBackfill.supportsDryRun());
        assertEquals(OpsConfigValues.DEFAULT_AGGREGATION_DEV_LIMIT, coverBackfill.defaultLimit());
        assertEquals(OpsConfigValues.MAX_AGGREGATION_DEV_LIMIT, coverBackfill.maxLimit());
        assertEquals(List.of(MaintenanceRunnerService.AGGREGATION_COVER_BACKFILL_OWNER_STEP),
                coverBackfill.ownerSteps());

        MaintenanceRunnerMetadata adultAssessmentBackfill = find(registry, "aggregation-adult-assessment-backfill");
        assertEquals(MaintenanceRiskLevel.LOW, adultAssessmentBackfill.riskLevel());
        assertTrue(adultAssessmentBackfill.devAllowed());
        assertTrue(adultAssessmentBackfill.supportsDryRun());
        assertEquals(OpsConfigValues.DEFAULT_AGGREGATION_DEV_LIMIT, adultAssessmentBackfill.defaultLimit());
        assertEquals(OpsConfigValues.MAX_AGGREGATION_DEV_LIMIT, adultAssessmentBackfill.maxLimit());
        assertEquals(List.of(MaintenanceRunnerService.AGGREGATION_ADULT_ASSESSMENT_BACKFILL_OWNER_STEP),
                adultAssessmentBackfill.ownerSteps());

        MaintenanceRunnerMetadata aggregationReset = find(registry, "aggregation-reset");
        assertEquals(MaintenanceRiskLevel.HIGH, aggregationReset.riskLevel());
        assertTrue(aggregationReset.devAllowed());
        assertTrue(aggregationReset.supportsDryRun());
        assertEquals(OpsConfigValues.DEFAULT_AGGREGATION_DEV_LIMIT, aggregationReset.defaultLimit());
        assertEquals(OpsConfigValues.MAX_AGGREGATION_DEV_LIMIT, aggregationReset.maxLimit());
        assertEquals(List.of(
                        MaintenanceRunnerService.AGGREGATION_RESET_PREPARE_OWNER_STEP,
                        MaintenanceRunnerService.AGGREGATION_RESET_SEARCH_OWNER_STEP,
                        MaintenanceRunnerService.AGGREGATION_RESET_MARK_RAW_OWNER_STEP),
                aggregationReset.ownerSteps());

        MaintenanceRunnerMetadata sanitize = find(registry, "sanitize");
        assertEquals(MaintenanceRiskLevel.HIGH, sanitize.riskLevel());
        assertTrue(sanitize.devAllowed());
        assertTrue(sanitize.supportsDryRun());
        assertEquals(OpsConfigValues.DEFAULT_NORMALIZATION_DEV_LIMIT, sanitize.defaultLimit());
        assertEquals(OpsConfigValues.MAX_NORMALIZATION_DEV_LIMIT, sanitize.maxLimit());
        assertEquals(List.of(MaintenanceRunnerService.SANITIZE_OWNER_STEP), sanitize.ownerSteps());

        MaintenanceRunnerMetadata trendSync = find(registry, "trend-sync");
        assertEquals(MaintenanceRiskLevel.HIGH, trendSync.riskLevel());
        assertTrue(trendSync.devAllowed());
        assertTrue(trendSync.supportsDryRun());
        assertEquals(List.of(MaintenanceRunnerService.TREND_SYNC_OWNER_STEP), trendSync.ownerSteps());
    }

    @Test
    void searchReindexUnifiedRunsAndWritesSucceededAuditEvidence() {
        MaintenanceRunResult result = new MaintenanceRunResult(
                "search-reindex-unified",
                2,
                2,
                List.of(java.util.UUID.randomUUID(), java.util.UUID.randomUUID()));
        when(reindexCommand.enqueueUnifiedIndex(org.mockito.ArgumentMatchers.isNull())).thenReturn(result);

        MaintenanceRunnerExecution execution = runnerService.run("search-reindex-unified");

        assertFalse(execution.refused());
        assertEquals(result, execution.result());
        assertEquals("search-reindex-unified", execution.metadata().taskName());
        assertTrue(execution.metadata().devAllowed());

        WorkerJobAuditEvent event = captureAuditEvent();
        assertEquals(MaintenanceRunnerService.JOB_TYPE_MAINTENANCE_RUNNER, event.jobType());
        assertEquals("search-reindex-unified", event.jobName());
        assertEquals("succeeded", event.status());
        assertNotNull(event.duration());
    }

    @Test
    void searchReindexRawRunsAndWritesSucceededAuditEvidence() {
        MaintenanceRunResult result = new MaintenanceRunResult(
                "search-reindex-raw",
                2,
                2,
                List.of(java.util.UUID.randomUUID(), java.util.UUID.randomUUID()));
        when(reindexCommand.enqueueRawIndex("corr-raw")).thenReturn(result);

        MaintenanceRunnerExecution execution = runnerService.run("search-reindex-raw", "corr-raw");

        assertFalse(execution.refused());
        assertEquals(result, execution.result());
        assertEquals("search-reindex-raw", execution.metadata().taskName());
        assertTrue(execution.metadata().devAllowed());

        WorkerJobAuditEvent event = captureAuditEvent();
        assertEquals(MaintenanceRunnerService.JOB_TYPE_MAINTENANCE_RUNNER, event.jobType());
        assertEquals("search-reindex-raw", event.jobName());
        assertEquals("succeeded", event.status());
        assertEquals("corr-raw", event.correlationId());
        assertNotNull(event.duration());
    }

    @Test
    void searchReindexUnifiedAllRunsAndWritesSucceededAuditEvidence() {
        MaintenanceRunResult result = new MaintenanceRunResult(
                "search-reindex-unified-all",
                200,
                200,
                List.of(java.util.UUID.randomUUID()));
        when(reindexCommand.enqueueAllUnifiedIndex("corr-all")).thenReturn(result);

        MaintenanceRunnerExecution execution = runnerService.run("search-reindex-unified-all", "corr-all");

        assertFalse(execution.refused());
        assertEquals(result, execution.result());
        assertEquals("search-reindex-unified-all", execution.metadata().taskName());
        verify(reindexCommand).enqueueAllUnifiedIndex("corr-all");

        WorkerJobAuditEvent event = captureAuditEvent();
        assertEquals(MaintenanceRunnerService.JOB_TYPE_MAINTENANCE_RUNNER, event.jobType());
        assertEquals("search-reindex-unified-all", event.jobName());
        assertEquals("succeeded", event.status());
        assertEquals("corr-all", event.correlationId());
        assertNotNull(event.duration());
    }

    @Test
    void searchReindexUnifiedRecordsMicrometerMetrics() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        WorkerJobAuditWriter metricsWriter = new WorkerJobAuditWriter(
                false,
                null,
                null,
                null,
                "ircs-ops-service",
                "ops-pod-1",
                registry);
        MaintenanceRunnerService service = new MaintenanceRunnerService(
                reindexCommand,
                normalizationClient,
                aggregationClient,
                trendSyncClient,
                metricsWriter);
        MaintenanceRunResult result = new MaintenanceRunResult(
                "search-reindex-unified",
                2,
                2,
                List.of(java.util.UUID.randomUUID(), java.util.UUID.randomUUID()));
        when(reindexCommand.enqueueUnifiedIndex(org.mockito.ArgumentMatchers.isNull())).thenReturn(result);

        service.run("search-reindex-unified");

        assertEquals(1.0d, registry.get("ircs.worker.job.runs")
                .tag("worker_id", "ops-pod-1")
                .tag("job_type", MaintenanceRunnerService.JOB_TYPE_MAINTENANCE_RUNNER)
                .tag("job_name", "search-reindex-unified")
                .tag("outcome", "succeeded")
                .counter()
                .count());
        assertEquals(1L, registry.get("ircs.worker.job.duration")
                .tag("worker_id", "ops-pod-1")
                .tag("job_type", MaintenanceRunnerService.JOB_TYPE_MAINTENANCE_RUNNER)
                .tag("job_name", "search-reindex-unified")
                .tag("outcome", "succeeded")
                .timer()
                .count());
    }

    @Test
    void unifiedRecalculateRunnerCallsAggregationOwnerAndWritesSucceededAuditEvidence() {
        MaintenanceRunResult result = new MaintenanceRunResult(
                "unified-recalculate",
                2,
                5,
                List.of(java.util.UUID.randomUUID(), java.util.UUID.randomUUID()));
        when(aggregationClient.recalculateDirtyUnified("corr-agg")).thenReturn(result);

        MaintenanceRunnerExecution execution = runnerService.run("unified-recalculate", "corr-agg");

        assertFalse(execution.refused());
        assertEquals(result, execution.result());
        assertEquals("unified-recalculate", execution.metadata().taskName());
        verify(aggregationClient).recalculateDirtyUnified("corr-agg");

        WorkerJobAuditEvent event = captureAuditEvent();
        assertEquals(MaintenanceRunnerService.JOB_TYPE_MAINTENANCE_RUNNER, event.jobType());
        assertEquals("unified-recalculate", event.jobName());
        assertEquals("succeeded", event.status());
        assertEquals("corr-agg", event.correlationId());
        assertNotNull(event.duration());
    }

    @Test
    void aggregationPendingBackfillRunnerCallsAggregationOwnerAndWritesSucceededAuditEvidence() {
        MaintenanceRunResult result = new MaintenanceRunResult(
                "aggregation-pending-backfill",
                2,
                2,
                List.of(java.util.UUID.randomUUID(), java.util.UUID.randomUUID()));
        when(aggregationClient.enqueuePendingRawWork("corr-pending")).thenReturn(result);

        MaintenanceRunnerExecution execution = runnerService.run("aggregation-pending-backfill", "corr-pending");

        assertFalse(execution.refused());
        assertEquals(result, execution.result());
        assertEquals("aggregation-pending-backfill", execution.metadata().taskName());
        verify(aggregationClient).enqueuePendingRawWork("corr-pending");

        WorkerJobAuditEvent event = captureAuditEvent();
        assertEquals(MaintenanceRunnerService.JOB_TYPE_MAINTENANCE_RUNNER, event.jobType());
        assertEquals("aggregation-pending-backfill", event.jobName());
        assertEquals("succeeded", event.status());
        assertEquals("corr-pending", event.correlationId());
        assertNotNull(event.duration());
    }

    @Test
    void aggregationCoverBackfillRunnerCallsAggregationOwnerAndWritesSucceededAuditEvidence() {
        MaintenanceRunResult result = new MaintenanceRunResult(
                "aggregation-cover-backfill",
                1,
                1,
                List.of(java.util.UUID.randomUUID()));
        when(aggregationClient.backfillUnifiedCovers("corr-cover")).thenReturn(result);

        MaintenanceRunnerExecution execution = runnerService.run("aggregation-cover-backfill", "corr-cover");

        assertFalse(execution.refused());
        assertEquals(result, execution.result());
        assertEquals("aggregation-cover-backfill", execution.metadata().taskName());
        verify(aggregationClient).backfillUnifiedCovers("corr-cover");

        WorkerJobAuditEvent event = captureAuditEvent();
        assertEquals(MaintenanceRunnerService.JOB_TYPE_MAINTENANCE_RUNNER, event.jobType());
        assertEquals("aggregation-cover-backfill", event.jobName());
        assertEquals("succeeded", event.status());
        assertEquals("corr-cover", event.correlationId());
        assertNotNull(event.duration());
    }

    @Test
    void aggregationAdultAssessmentBackfillRunnerCallsAggregationOwnerAndWritesSucceededAuditEvidence() {
        MaintenanceRunResult result = new MaintenanceRunResult(
                "aggregation-adult-assessment-backfill",
                2,
                2,
                List.of(java.util.UUID.randomUUID(), java.util.UUID.randomUUID()));
        when(aggregationClient.backfillUnifiedAdultAssessments("corr-adult")).thenReturn(result);

        MaintenanceRunnerExecution execution =
                runnerService.run("aggregation-adult-assessment-backfill", "corr-adult");

        assertFalse(execution.refused());
        assertEquals(result, execution.result());
        assertEquals("aggregation-adult-assessment-backfill", execution.metadata().taskName());
        verify(aggregationClient).backfillUnifiedAdultAssessments("corr-adult");

        WorkerJobAuditEvent event = captureAuditEvent();
        assertEquals(MaintenanceRunnerService.JOB_TYPE_MAINTENANCE_RUNNER, event.jobType());
        assertEquals("aggregation-adult-assessment-backfill", event.jobName());
        assertEquals("succeeded", event.status());
        assertEquals("corr-adult", event.correlationId());
        assertNotNull(event.duration());
    }

    @Test
    void aggregationResetRunnerCallsOwnersInV1OrderAndWritesSucceededAuditEvidence() {
        java.util.UUID sampleRawId = java.util.UUID.randomUUID();
        MaintenanceRunResult prepare = new MaintenanceRunResult(
                "aggregation-reset.prepare",
                10,
                7,
                List.of(sampleRawId));
        MaintenanceRunResult searchReset = new MaintenanceRunResult(
                "aggregation-reset.search-hard-reset",
                1,
                3,
                List.of());
        MaintenanceRunResult markPending = new MaintenanceRunResult(
                "aggregation-reset.mark-raw-pending",
                10,
                10,
                List.of(sampleRawId));
        when(aggregationClient.prepareAggregationReset("corr-reset")).thenReturn(prepare);
        when(reindexCommand.hardResetUnifiedIndex("corr-reset")).thenReturn(searchReset);
        when(aggregationClient.markAggregationResetRawPending("corr-reset")).thenReturn(markPending);

        MaintenanceRunnerExecution execution = runnerService.run("aggregation-reset", "corr-reset");

        assertFalse(execution.refused());
        assertEquals("aggregation-reset", execution.result().taskName());
        assertEquals(10, execution.result().selectedCount());
        assertEquals(20, execution.result().publishedCount());
        assertEquals(List.of(sampleRawId), execution.result().entityIds());

        org.mockito.InOrder ordered = org.mockito.Mockito.inOrder(aggregationClient, reindexCommand);
        ordered.verify(aggregationClient).prepareAggregationReset("corr-reset");
        ordered.verify(reindexCommand).hardResetUnifiedIndex("corr-reset");
        ordered.verify(aggregationClient).markAggregationResetRawPending("corr-reset");

        WorkerJobAuditEvent event = captureAuditEvent();
        assertEquals(MaintenanceRunnerService.JOB_TYPE_MAINTENANCE_RUNNER, event.jobType());
        assertEquals("aggregation-reset", event.jobName());
        assertEquals("succeeded", event.status());
        assertEquals("corr-reset", event.correlationId());
        assertNotNull(event.duration());
    }

    @Test
    void sanitizeRunnerCallsNormalizationOwnerAndWritesSucceededAuditEvidence() {
        java.util.UUID sampleRawId = java.util.UUID.randomUUID();
        MaintenanceRunResult result = new MaintenanceRunResult(
                "sanitize",
                11,
                9,
                List.of(sampleRawId));
        when(normalizationClient.resetAllNormalization("corr-sanitize")).thenReturn(result);

        MaintenanceRunnerExecution execution = runnerService.run("sanitize", "corr-sanitize");

        assertFalse(execution.refused());
        assertEquals(result, execution.result());
        assertEquals("sanitize", execution.metadata().taskName());
        verify(normalizationClient).resetAllNormalization("corr-sanitize");

        WorkerJobAuditEvent event = captureAuditEvent();
        assertEquals(MaintenanceRunnerService.JOB_TYPE_MAINTENANCE_RUNNER, event.jobType());
        assertEquals("sanitize", event.jobName());
        assertEquals("succeeded", event.status());
        assertEquals("corr-sanitize", event.correlationId());
        assertNotNull(event.duration());
    }

    @Test
    void trendSyncRunnerCallsScraperOwnerAndWritesSucceededAuditEvidence() {
        java.util.UUID updatedId = java.util.UUID.randomUUID();
        MaintenanceRunResult result = new MaintenanceRunResult(
                "trend-sync",
                12,
                7,
                List.of(updatedId));
        when(trendSyncClient.syncTrends("corr-trend")).thenReturn(result);

        MaintenanceRunnerExecution execution = runnerService.run("trend-sync", "corr-trend");

        assertFalse(execution.refused());
        assertEquals(result, execution.result());
        assertEquals("trend-sync", execution.metadata().taskName());
        verify(trendSyncClient).syncTrends("corr-trend");

        WorkerJobAuditEvent event = captureAuditEvent();
        assertEquals(MaintenanceRunnerService.JOB_TYPE_MAINTENANCE_RUNNER, event.jobType());
        assertEquals("trend-sync", event.jobName());
        assertEquals("succeeded", event.status());
        assertEquals("corr-trend", event.correlationId());
        assertNotNull(event.duration());
    }

    @Test
    void highRiskRunnerIsRefusedInDevAndWritesFailedAuditEvidence() {
        MaintenanceRunnerExecution execution = runnerService.run("area-clean");

        assertTrue(execution.refused());
        assertEquals(MaintenanceRiskLevel.HIGH, execution.metadata().riskLevel());
        assertEquals(MaintenanceRunnerService.DEV_REFUSAL_REASON, execution.reason());
        verify(reindexCommand, never()).enqueueUnifiedIndex();
        verify(reindexCommand, never()).enqueueRawIndex();
        verify(reindexCommand, never()).hardResetUnifiedIndex(org.mockito.ArgumentMatchers.any());
        verify(normalizationClient, never()).resetAllNormalization(org.mockito.ArgumentMatchers.any());
        verify(aggregationClient, never()).recalculateDirtyUnified(org.mockito.ArgumentMatchers.any());
        verify(aggregationClient, never()).enqueuePendingRawWork(org.mockito.ArgumentMatchers.any());
        verify(aggregationClient, never()).backfillUnifiedCovers(org.mockito.ArgumentMatchers.any());
        verify(aggregationClient, never()).backfillUnifiedAdultAssessments(org.mockito.ArgumentMatchers.any());
        verify(aggregationClient, never()).prepareAggregationReset(org.mockito.ArgumentMatchers.any());
        verify(aggregationClient, never()).markAggregationResetRawPending(org.mockito.ArgumentMatchers.any());
        verify(trendSyncClient, never()).syncTrends(org.mockito.ArgumentMatchers.any());

        WorkerJobAuditEvent event = captureAuditEvent();
        assertEquals(MaintenanceRunnerService.JOB_TYPE_MAINTENANCE_RUNNER, event.jobType());
        assertEquals("area-clean", event.jobName());
        assertEquals("failed", event.status());
        assertNotNull(event.error());
        assertEquals(MaintenanceRunnerService.DEV_REFUSAL_REASON, event.error().getMessage());
    }

    @Test
    void unknownRunnerIsRefusedWithStableReason() {
        MaintenanceRunnerExecution execution = runnerService.run("not-registered");

        assertTrue(execution.refused());
        assertEquals("not-registered", execution.metadata().taskName());
        assertEquals(MaintenanceRunnerService.UNKNOWN_REFUSAL_REASON, execution.reason());
        verify(reindexCommand, never()).enqueueUnifiedIndex();
        verify(reindexCommand, never()).enqueueRawIndex();
        verify(reindexCommand, never()).hardResetUnifiedIndex(org.mockito.ArgumentMatchers.any());
        verify(normalizationClient, never()).resetAllNormalization(org.mockito.ArgumentMatchers.any());
        verify(aggregationClient, never()).recalculateDirtyUnified(org.mockito.ArgumentMatchers.any());
        verify(aggregationClient, never()).enqueuePendingRawWork(org.mockito.ArgumentMatchers.any());
        verify(aggregationClient, never()).backfillUnifiedCovers(org.mockito.ArgumentMatchers.any());
        verify(aggregationClient, never()).backfillUnifiedAdultAssessments(org.mockito.ArgumentMatchers.any());
        verify(aggregationClient, never()).prepareAggregationReset(org.mockito.ArgumentMatchers.any());
        verify(aggregationClient, never()).markAggregationResetRawPending(org.mockito.ArgumentMatchers.any());
        verify(trendSyncClient, never()).syncTrends(org.mockito.ArgumentMatchers.any());
    }

    private WorkerJobAuditEvent captureAuditEvent() {
        ArgumentCaptor<WorkerJobAuditEvent> captor = ArgumentCaptor.forClass(WorkerJobAuditEvent.class);
        verify(auditWriter).record(captor.capture());
        return captor.getValue();
    }

    private static MaintenanceRunnerMetadata find(List<MaintenanceRunnerMetadata> registry, String taskName) {
        return registry.stream()
                .filter(metadata -> taskName.equals(metadata.taskName()))
                .findFirst()
                .orElseThrow();
    }
}
