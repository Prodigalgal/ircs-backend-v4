package com.prodigalgal.ircs.ops.maintenance.application;

import com.prodigalgal.ircs.common.audit.WorkerJobAuditEvent;
import com.prodigalgal.ircs.common.audit.WorkerJobAuditWriter;
import com.prodigalgal.ircs.ops.config.OpsConfigValues;
import com.prodigalgal.ircs.ops.maintenance.domain.MaintenanceOwnerStep;
import com.prodigalgal.ircs.ops.maintenance.domain.MaintenanceRiskLevel;
import com.prodigalgal.ircs.ops.maintenance.domain.MaintenanceRunResult;
import com.prodigalgal.ircs.ops.maintenance.domain.MaintenanceRunnerExecution;
import com.prodigalgal.ircs.ops.maintenance.dto.MaintenanceRunnerMetadata;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class MaintenanceRunnerService {

    static final String JOB_TYPE_MAINTENANCE_RUNNER = "maintenance-runner";
    static final String TASK_SANITIZE = "sanitize";
    static final String TASK_UNIFIED_RECALCULATE = "unified-recalculate";
    static final String TASK_AGGREGATION_PENDING_BACKFILL = "aggregation-pending-backfill";
    static final String TASK_AGGREGATION_COVER_BACKFILL = "aggregation-cover-backfill";
    static final String TASK_AGGREGATION_ADULT_ASSESSMENT_BACKFILL = "aggregation-adult-assessment-backfill";
    static final String TASK_AGGREGATION_RESET = "aggregation-reset";
    static final String TASK_TREND_SYNC = "trend-sync";
    static final String DEV_REFUSAL_REASON = "refused: high-risk maintenance runner is not allowed in dev";
    static final String UNKNOWN_REFUSAL_REASON = "refused: maintenance runner is not registered";
    static final MaintenanceOwnerStep SEARCH_REINDEX_OWNER_STEP = new MaintenanceOwnerStep(
            "enqueue-unified-index",
            "ircs-search-service",
            "POST /internal/v1/search/sync-tasks/batch",
            "search:sync");
    static final MaintenanceOwnerStep RAW_SEARCH_REINDEX_OWNER_STEP = new MaintenanceOwnerStep(
            "enqueue-raw-index",
            "ircs-search-service",
            "POST /internal/v1/search/sync-tasks/batch",
            "search:sync");
    static final MaintenanceOwnerStep SEARCH_REINDEX_ALL_OWNER_STEP = new MaintenanceOwnerStep(
            "enqueue-all-unified-index",
            "ircs-search-service",
            "POST /internal/v1/search/sync-tasks/batch",
            "search:sync");
    static final MaintenanceOwnerStep RAW_SEARCH_REINDEX_ALL_OWNER_STEP = new MaintenanceOwnerStep(
            "enqueue-all-raw-index",
            "ircs-search-service",
            "POST /internal/v1/search/sync-tasks/batch",
            "search:sync");
    static final MaintenanceOwnerStep UNIFIED_RECALCULATE_OWNER_STEP = new MaintenanceOwnerStep(
            "recalculate-dirty-unified",
            "ircs-aggregation-worker",
            "POST /internal/v1/aggregation/unified-videos/recalculate-dirty",
            "aggregation:maintenance");
    static final MaintenanceOwnerStep AGGREGATION_PENDING_BACKFILL_OWNER_STEP = new MaintenanceOwnerStep(
            "enqueue-pending-raw-work",
            "ircs-aggregation-worker",
            "POST /internal/v1/aggregation/raw-videos/enqueue-pending",
            "aggregation:maintenance");
    static final MaintenanceOwnerStep AGGREGATION_COVER_BACKFILL_OWNER_STEP = new MaintenanceOwnerStep(
            "backfill-unified-covers",
            "ircs-aggregation-worker",
            "POST /internal/v1/aggregation/unified-videos/backfill-covers",
            "aggregation:maintenance");
    static final MaintenanceOwnerStep AGGREGATION_ADULT_ASSESSMENT_BACKFILL_OWNER_STEP = new MaintenanceOwnerStep(
            "backfill-unified-adult-assessments",
            "ircs-aggregation-worker",
            "POST /internal/v1/aggregation/unified-videos/backfill-adult-assessments",
            "aggregation:maintenance");
    static final MaintenanceOwnerStep AGGREGATION_RESET_PREPARE_OWNER_STEP = new MaintenanceOwnerStep(
            "prepare-aggregation-reset",
            "ircs-aggregation-worker",
            "POST /internal/v1/aggregation/reset/prepare",
            "aggregation:maintenance");
    static final MaintenanceOwnerStep AGGREGATION_RESET_SEARCH_OWNER_STEP = new MaintenanceOwnerStep(
            "hard-reset-unified-index",
            "ircs-search-service",
            "POST /internal/v1/search/index-maintenance/UNIFIED_VIDEO/hard-reset",
            "search:sync");
    static final MaintenanceOwnerStep AGGREGATION_RESET_MARK_RAW_OWNER_STEP = new MaintenanceOwnerStep(
            "mark-all-raw-aggregation-pending",
            "ircs-aggregation-worker",
            "POST /internal/v1/aggregation/reset/mark-raw-pending",
            "aggregation:maintenance");
    static final MaintenanceOwnerStep SANITIZE_OWNER_STEP = new MaintenanceOwnerStep(
            "reset-all-raw-normalization-pending",
            "ircs-normalization-worker",
            "POST /internal/v1/normalization/maintenance/raw-videos/reset-normalization",
            "normalization:maintenance");
    static final MaintenanceOwnerStep TREND_SYNC_OWNER_STEP = new MaintenanceOwnerStep(
            "sync-trends",
            "ircs-scraper-service",
            "POST /internal/v1/scraper/trends/sync",
            "scraper:maintenance");
    private static final Map<String, MaintenanceRunnerMetadata> REGISTRY = Map.ofEntries(
            metadata(
                    MaintenanceReindexCommand.TASK_UNIFIED_REINDEX,
                    MaintenanceRiskLevel.LOW,
                    true,
                    true,
                    MaintenanceReindexCommand.DEFAULT_DEV_LIMIT,
                    MaintenanceReindexCommand.MAX_DEV_LIMIT,
                    "",
                    List.of(SEARCH_REINDEX_OWNER_STEP)),
            metadata(
                    MaintenanceReindexCommand.TASK_RAW_REINDEX,
                    MaintenanceRiskLevel.LOW,
                    true,
                    true,
                    MaintenanceReindexCommand.DEFAULT_DEV_LIMIT,
                    MaintenanceReindexCommand.MAX_DEV_LIMIT,
                    "",
                    List.of(RAW_SEARCH_REINDEX_OWNER_STEP)),
            metadata(
                    MaintenanceReindexCommand.TASK_UNIFIED_REINDEX_ALL,
                    MaintenanceRiskLevel.HIGH,
                    true,
                    true,
                    MaintenanceReindexCommand.DEFAULT_BATCH_SIZE,
                    MaintenanceReindexCommand.MAX_BATCH_SIZE,
                    "",
                    List.of(SEARCH_REINDEX_ALL_OWNER_STEP)),
            metadata(
                    MaintenanceReindexCommand.TASK_RAW_REINDEX_ALL,
                    MaintenanceRiskLevel.HIGH,
                    true,
                    true,
                    MaintenanceReindexCommand.DEFAULT_BATCH_SIZE,
                    MaintenanceReindexCommand.MAX_BATCH_SIZE,
                    "",
                    List.of(RAW_SEARCH_REINDEX_ALL_OWNER_STEP)),
            metadata(
                    TASK_SANITIZE,
                    MaintenanceRiskLevel.HIGH,
                    true,
                    true,
                    OpsConfigValues.DEFAULT_NORMALIZATION_DEV_LIMIT,
                    OpsConfigValues.MAX_NORMALIZATION_DEV_LIMIT,
                    "",
                    List.of(SANITIZE_OWNER_STEP)),
            metadata(
                    TASK_AGGREGATION_RESET,
                    MaintenanceRiskLevel.HIGH,
                    true,
                    true,
                    OpsConfigValues.DEFAULT_AGGREGATION_DEV_LIMIT,
                    OpsConfigValues.MAX_AGGREGATION_DEV_LIMIT,
                    "",
                    List.of(
                            AGGREGATION_RESET_PREPARE_OWNER_STEP,
                            AGGREGATION_RESET_SEARCH_OWNER_STEP,
                            AGGREGATION_RESET_MARK_RAW_OWNER_STEP)),
            metadata(
                    TASK_UNIFIED_RECALCULATE,
                    MaintenanceRiskLevel.HIGH,
                    true,
                    true,
                    OpsConfigValues.DEFAULT_AGGREGATION_DEV_LIMIT,
                    OpsConfigValues.MAX_AGGREGATION_DEV_LIMIT,
                    "",
                    List.of(UNIFIED_RECALCULATE_OWNER_STEP)),
            metadata(
                    TASK_AGGREGATION_PENDING_BACKFILL,
                    MaintenanceRiskLevel.LOW,
                    true,
                    true,
                    OpsConfigValues.DEFAULT_AGGREGATION_DEV_LIMIT,
                    OpsConfigValues.MAX_AGGREGATION_DEV_LIMIT,
                    "",
                    List.of(AGGREGATION_PENDING_BACKFILL_OWNER_STEP)),
            metadata(
                    TASK_AGGREGATION_COVER_BACKFILL,
                    MaintenanceRiskLevel.LOW,
                    true,
                    true,
                    OpsConfigValues.DEFAULT_AGGREGATION_DEV_LIMIT,
                    OpsConfigValues.MAX_AGGREGATION_DEV_LIMIT,
                    "",
                    List.of(AGGREGATION_COVER_BACKFILL_OWNER_STEP)),
            metadata(
                    TASK_AGGREGATION_ADULT_ASSESSMENT_BACKFILL,
                    MaintenanceRiskLevel.LOW,
                    true,
                    true,
                    OpsConfigValues.DEFAULT_AGGREGATION_DEV_LIMIT,
                    OpsConfigValues.MAX_AGGREGATION_DEV_LIMIT,
                    "",
                    List.of(AGGREGATION_ADULT_ASSESSMENT_BACKFILL_OWNER_STEP)),
            metadata("area-clean", DEV_REFUSAL_REASON),
            metadata("language-clean", DEV_REFUSAL_REASON),
            metadata(
                    TASK_TREND_SYNC,
                    MaintenanceRiskLevel.HIGH,
                    true,
                    true,
                    0,
                    0,
                    "",
                    List.of(TREND_SYNC_OWNER_STEP)));

    private final MaintenanceReindexCommand reindexCommand;
    private final MaintenanceNormalizationClient normalizationClient;
    private final MaintenanceAggregationClient aggregationClient;
    private final MaintenanceTrendSyncClient trendSyncClient;
    private final WorkerJobAuditWriter auditWriter;

    public List<MaintenanceRunnerMetadata> registry() {
        return REGISTRY.values().stream()
                .sorted(Comparator.comparing(MaintenanceRunnerMetadata::taskName))
                .toList();
    }

    public Optional<MaintenanceRunnerMetadata> metadata(String taskName) {
        return Optional.ofNullable(REGISTRY.get(taskName));
    }

    public boolean supports(String taskName) {
        return metadata(taskName).map(MaintenanceRunnerMetadata::devAllowed).orElse(false);
    }

    public MaintenanceRunnerExecution run(String taskName) {
        return run(taskName, null);
    }

    public MaintenanceRunnerExecution run(String taskName, String correlationId) {
        MaintenanceRunnerMetadata metadata = metadata(taskName)
                .orElseGet(() -> unknownMetadata(taskName));
        Instant startedAt = Instant.now();
        if (!metadata.devAllowed()) {
            auditWriter.record(WorkerJobAuditEvent.failed(
                    JOB_TYPE_MAINTENANCE_RUNNER,
                    metadata.taskName(),
                    correlationId,
                    elapsedSince(startedAt),
                    new MaintenanceRunnerRefusedException(reason(metadata))));
            return MaintenanceRunnerExecution.refused(metadata, reason(metadata));
        }
        if (MaintenanceReindexCommand.TASK_UNIFIED_REINDEX.equals(metadata.taskName())) {
            return execute(metadata, correlationId, startedAt, () -> reindexCommand.enqueueUnifiedIndex(correlationId));
        }
        if (MaintenanceReindexCommand.TASK_RAW_REINDEX.equals(metadata.taskName())) {
            return execute(metadata, correlationId, startedAt, () -> reindexCommand.enqueueRawIndex(correlationId));
        }
        if (MaintenanceReindexCommand.TASK_UNIFIED_REINDEX_ALL.equals(metadata.taskName())) {
            return execute(metadata, correlationId, startedAt, () -> reindexCommand.enqueueAllUnifiedIndex(correlationId));
        }
        if (MaintenanceReindexCommand.TASK_RAW_REINDEX_ALL.equals(metadata.taskName())) {
            return execute(metadata, correlationId, startedAt, () -> reindexCommand.enqueueAllRawIndex(correlationId));
        }
        if (TASK_SANITIZE.equals(metadata.taskName())) {
            return execute(metadata, correlationId, startedAt, () -> normalizationClient.resetAllNormalization(correlationId));
        }
        if (TASK_UNIFIED_RECALCULATE.equals(metadata.taskName())) {
            return execute(metadata, correlationId, startedAt, () -> aggregationClient.recalculateDirtyUnified(correlationId));
        }
        if (TASK_AGGREGATION_PENDING_BACKFILL.equals(metadata.taskName())) {
            return execute(metadata, correlationId, startedAt, () -> aggregationClient.enqueuePendingRawWork(correlationId));
        }
        if (TASK_AGGREGATION_COVER_BACKFILL.equals(metadata.taskName())) {
            return execute(metadata, correlationId, startedAt, () -> aggregationClient.backfillUnifiedCovers(correlationId));
        }
        if (TASK_AGGREGATION_ADULT_ASSESSMENT_BACKFILL.equals(metadata.taskName())) {
            return execute(
                    metadata,
                    correlationId,
                    startedAt,
                    () -> aggregationClient.backfillUnifiedAdultAssessments(correlationId));
        }
        if (TASK_AGGREGATION_RESET.equals(metadata.taskName())) {
            return execute(metadata, correlationId, startedAt, () -> runAggregationReset(correlationId));
        }
        if (TASK_TREND_SYNC.equals(metadata.taskName())) {
            return execute(metadata, correlationId, startedAt, () -> trendSyncClient.syncTrends(correlationId));
        }
        auditWriter.record(WorkerJobAuditEvent.failed(
                JOB_TYPE_MAINTENANCE_RUNNER,
                metadata.taskName(),
                correlationId,
                elapsedSince(startedAt),
                new MaintenanceRunnerRefusedException(UNKNOWN_REFUSAL_REASON)));
        return MaintenanceRunnerExecution.refused(metadata, UNKNOWN_REFUSAL_REASON);
    }

    private MaintenanceRunResult runAggregationReset(String correlationId) {
        MaintenanceRunResult prepare = aggregationClient.prepareAggregationReset(correlationId);
        MaintenanceRunResult searchReset = reindexCommand.hardResetUnifiedIndex(correlationId);
        MaintenanceRunResult markPending = aggregationClient.markAggregationResetRawPending(correlationId);
        return new MaintenanceRunResult(
                TASK_AGGREGATION_RESET,
                Math.max(prepare.selectedCount(), markPending.selectedCount()),
                safeSum(prepare.publishedCount(), searchReset.publishedCount(), markPending.publishedCount()),
                prepare.entityIds().isEmpty() ? markPending.entityIds() : prepare.entityIds());
    }

    private MaintenanceRunnerExecution execute(
            MaintenanceRunnerMetadata metadata,
            String correlationId,
            Instant startedAt,
            Supplier<MaintenanceRunResult> command) {
        try {
            MaintenanceRunResult result = command.get();
            auditWriter.record(WorkerJobAuditEvent.succeeded(
                    JOB_TYPE_MAINTENANCE_RUNNER,
                    metadata.taskName(),
                    correlationId,
                    elapsedSince(startedAt)));
            return MaintenanceRunnerExecution.executed(metadata, result);
        } catch (RuntimeException ex) {
            auditWriter.record(WorkerJobAuditEvent.failed(
                    JOB_TYPE_MAINTENANCE_RUNNER,
                    metadata.taskName(),
                    correlationId,
                    elapsedSince(startedAt),
                    ex));
            throw ex;
        }
    }

    private static String reason(MaintenanceRunnerMetadata metadata) {
        return metadata.refusalReason().isBlank() ? DEV_REFUSAL_REASON : metadata.refusalReason();
    }

    private static Duration elapsedSince(Instant startedAt) {
        return Duration.between(startedAt, Instant.now());
    }

    private static int safeSum(int... values) {
        long total = 0;
        for (int value : values) {
            total += Math.max(0, value);
        }
        return total > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) total;
    }

    private static Map.Entry<String, MaintenanceRunnerMetadata> metadata(
            String taskName,
            MaintenanceRiskLevel riskLevel,
            boolean devAllowed,
            boolean supportsDryRun,
            int defaultLimit,
            int maxLimit,
            String refusalReason,
            List<MaintenanceOwnerStep> ownerSteps) {
        return Map.entry(
                taskName,
                new MaintenanceRunnerMetadata(
                        taskName,
                        riskLevel,
                        devAllowed,
                        supportsDryRun,
                        defaultLimit,
                        maxLimit,
                        refusalReason,
                        ownerSteps));
    }

    private static Map.Entry<String, MaintenanceRunnerMetadata> metadata(String taskName, String refusalReason) {
        return metadata(
                taskName,
                MaintenanceRiskLevel.HIGH,
                false,
                false,
                0,
                0,
                refusalReason,
                List.of());
    }

    private static MaintenanceRunnerMetadata unknownMetadata(String taskName) {
        String safeTaskName = taskName == null || taskName.isBlank() ? "unknown" : taskName;
        return new MaintenanceRunnerMetadata(
                safeTaskName,
                MaintenanceRiskLevel.HIGH,
                false,
                false,
                0,
                0,
                UNKNOWN_REFUSAL_REASON,
                List.of());
    }

    private static class MaintenanceRunnerRefusedException extends RuntimeException {
        MaintenanceRunnerRefusedException(String message) {
            super(message);
        }
    }
}
