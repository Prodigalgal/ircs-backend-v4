package com.prodigalgal.ircs.search.sync;

import com.prodigalgal.ircs.common.audit.WorkerJobAuditEvent;
import com.prodigalgal.ircs.common.audit.WorkerJobAuditWriter;
import com.prodigalgal.ircs.common.scheduling.ScheduledTriggers;
import com.prodigalgal.ircs.common.search.SearchSyncWorkPublisher;
import com.prodigalgal.ircs.contracts.search.SearchEntityType;
import com.prodigalgal.ircs.contracts.search.SyncOperation;
import com.prodigalgal.ircs.search.index.SearchIndexService;
import com.prodigalgal.ircs.search.repository.SearchDocumentJdbcRepository;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@Slf4j
@ConditionalOnProperty(prefix = "app.search.reconciliation", name = "enabled", havingValue = "true")
class SearchReconciliationRunner {

    private static final String JOB_TYPE = "scheduler";
    private static final String JOB_NAME = "search.reconciliation";

    private final SearchDocumentJdbcRepository documentRepository;
    private final SearchIndexService searchIndexService;
    private final SearchSyncWorkPublisher workPublisher;
    private final WorkerJobAuditWriter auditWriter;
    private final SearchDistributedLockRunner lockRunner;
    private final ExecutorService triggerExecutor =
            ScheduledTriggers.virtualThreadExecutor("ircs-search-reconciliation-trigger-vt-");
    private final AtomicBoolean running = new AtomicBoolean(false);

    @Value("${app.search.reconciliation.batch-size:100}")
    private int batchSize;

    @Value("${app.search.reconciliation.max-batches:20}")
    private int maxBatches;

    @Value("${app.search.reconciliation.dry-run:true}")
    private boolean dryRun;

    @Value("${app.search.reconciliation.execute-gate:true}")
    private boolean executeGate;
    SearchReconciliationRunner(
            SearchDocumentJdbcRepository documentRepository,
            SearchIndexService searchIndexService,
            SearchSyncWorkPublisher workPublisher,
            WorkerJobAuditWriter auditWriter,
            SearchDistributedLockRunner lockRunner) {
        this.documentRepository = documentRepository;
        this.searchIndexService = searchIndexService;
        this.workPublisher = workPublisher;
        this.auditWriter = auditWriter;
        this.lockRunner = lockRunner;
    }

    @Scheduled(cron = "${app.search.reconciliation.cron:0 0 3 * * ?}")
    public void runScheduled() {
        ScheduledTriggers.submit(triggerExecutor, () -> runOnce(), log, "search.reconciliation.run");
    }

    @PreDestroy
    void shutdownTriggerExecutor() {
        triggerExecutor.shutdownNow();
    }

    ReconciliationResult runOnce() {
        if (!running.compareAndSet(false, true)) {
            return new ReconciliationResult(0, 0, 0, 0, true);
        }
        try {
            return lockRunner.callExclusive("search:reconciliation", this::runOnceLocked)
                    .orElse(new ReconciliationResult(0, 0, 0, 0, true));
        } finally {
            running.set(false);
        }
    }

    private ReconciliationResult runOnceLocked() {
        Instant startedAt = Instant.now();
        try {
            ReconciliationResult raw = reconcile(SearchEntityType.RAW_VIDEO);
            ReconciliationResult unified = reconcile(SearchEntityType.UNIFIED_VIDEO);
            ReconciliationResult result = new ReconciliationResult(
                    raw.scanned() + unified.scanned(),
                    raw.missing() + unified.missing(),
                    raw.enqueued() + unified.enqueued(),
                    raw.batches() + unified.batches(),
                    dryRun || !executeGate);
            recordAudit(WorkerJobAuditEvent.succeeded(
                    JOB_TYPE,
                    JOB_NAME,
                    "scanned=" + result.scanned()
                            + ",missing=" + result.missing()
                            + ",enqueued=" + result.enqueued()
                            + ",dryRun=" + result.dryRun(),
                    elapsedSince(startedAt)));
            return result;
        } catch (RuntimeException ex) {
            recordAudit(WorkerJobAuditEvent.failed(JOB_TYPE, JOB_NAME, null, elapsedSince(startedAt), ex));
            throw ex;
        }
    }

    private ReconciliationResult reconcile(SearchEntityType entityType) {
        UUID cursor = null;
        int scanned = 0;
        int missing = 0;
        int enqueued = 0;
        int batches = 0;
        int effectiveBatchSize = Math.max(1, batchSize);
        int effectiveMaxBatches = Math.max(1, maxBatches);
        while (batches < effectiveMaxBatches) {
            List<UUID> ids = entityType == SearchEntityType.RAW_VIDEO
                    ? documentRepository.findNextRawVideoIds(cursor, effectiveBatchSize)
                    : documentRepository.findNextUnifiedVideoIds(cursor, effectiveBatchSize);
            if (ids.isEmpty()) {
                break;
            }
            cursor = ids.getLast();
            batches++;
            scanned += ids.size();
            Set<UUID> existing = entityType == SearchEntityType.RAW_VIDEO
                    ? searchIndexService.existingRawIds(ids)
                    : searchIndexService.existingUnifiedIds(ids);
            List<UUID> missingIds = ids.stream()
                    .filter(id -> !existing.contains(id))
                    .toList();
            missing += missingIds.size();
            if (!missingIds.isEmpty() && !dryRun && executeGate) {
                enqueued += workPublisher.enqueueBatch(
                        missingIds,
                        entityType,
                        SyncOperation.INDEX,
                        "search-reconciliation",
                        null);
            }
            if (ids.size() < effectiveBatchSize) {
                break;
            }
        }
        if (missing > 0) {
            log.info("Search reconciliation entityType={} scanned={}, missing={}, enqueued={}, dryRun={}",
                    entityType, scanned, missing, enqueued, dryRun || !executeGate);
        }
        return new ReconciliationResult(scanned, missing, enqueued, batches, dryRun || !executeGate);
    }

    private void recordAudit(WorkerJobAuditEvent event) {
        try {
            auditWriter.record(event);
        } catch (RuntimeException ex) {
            log.warn("Search reconciliation audit write failed: {}", ex.getMessage());
        }
    }

    private static Duration elapsedSince(Instant startedAt) {
        return Duration.between(startedAt, Instant.now());
    }

    record ReconciliationResult(int scanned, int missing, int enqueued, int batches, boolean dryRun) {
    }
}
