package com.prodigalgal.ircs.task.job;

import com.prodigalgal.ircs.common.audit.WorkerJobAuditEvent;
import com.prodigalgal.ircs.common.audit.WorkerJobAuditWriter;
import com.prodigalgal.ircs.common.scheduling.ScheduledTriggers;
import com.prodigalgal.ircs.task.application.TaskRuntimeRepairResult;
import com.prodigalgal.ircs.task.application.TaskRuntimeRepairService;
import com.prodigalgal.ircs.task.application.TaskSnapshotFlushResult;
import com.prodigalgal.ircs.task.application.TaskSnapshotFlushService;
import com.prodigalgal.ircs.task.infrastructure.TaskDistributedLockRunner;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ExecutorService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(value = "app.task.snapshot.flush.enabled", havingValue = "true")
@Slf4j
class TaskSnapshotFlushScheduler {

    static final String JOB_TYPE_TASK_SNAPSHOT_FLUSH = "task-snapshot-flush";
    static final String JOB_NAME_FLUSH_DIRTY_MASTERS = "task-snapshot-flush.flush-dirty-masters";
    static final String CORRELATION_FLUSH_DIRTY_MASTERS = "batch:flush-dirty-masters";

    private final TaskSnapshotFlushService flushService;
    private final TaskRuntimeRepairService repairService;
    private final WorkerJobAuditWriter auditWriter;
    private final TaskDistributedLockRunner lockRunner;
    private final Duration minDirtyAge;
    private final int batchSize;
    private final int repairBatchSize;
    private final ExecutorService triggerExecutor =
            ScheduledTriggers.virtualThreadExecutor("ircs-task-snapshot-flush-trigger-vt-");

    TaskSnapshotFlushScheduler(
            TaskSnapshotFlushService flushService,
            TaskRuntimeRepairService repairService,
            WorkerJobAuditWriter auditWriter,
            TaskDistributedLockRunner lockRunner,
            @Value("${app.task.snapshot.flush.min-dirty-age:PT5S}") Duration minDirtyAge,
            @Value("${app.task.snapshot.flush.batch-size:100}") int batchSize,
            @Value("${app.task.runtime.repair.batch-size:100}") int repairBatchSize) {
        this.flushService = flushService;
        this.repairService = repairService;
        this.auditWriter = auditWriter;
        this.lockRunner = lockRunner;
        this.minDirtyAge = minDirtyAge == null || minDirtyAge.isNegative() ? Duration.ZERO : minDirtyAge;
        this.batchSize = Math.max(1, batchSize);
        this.repairBatchSize = Math.max(1, repairBatchSize);
    }

    @Scheduled(
            fixedDelayString = "${app.task.snapshot.flush.delay-ms:30000}",
            initialDelayString = "${app.task.snapshot.flush.initial-delay-ms:30000}")
    void flushDirtyMasters() {
        ScheduledTriggers.submit(triggerExecutor, this::flushDirtyMastersOnce, log, "task.snapshot-flush.flush-dirty-masters");
    }

    @PreDestroy
    void shutdownTriggerExecutor() {
        triggerExecutor.shutdownNow();
    }

    void flushDirtyMastersOnce() {
        if (!lockRunner.runExclusive("task:snapshot-flush:dirty-masters", this::flushDirtyMastersLocked)) {
            log.debug("Task snapshot flush skipped: distributed lock is held by another instance");
        }
    }

    private void flushDirtyMastersLocked() {
        Instant startedAt = Instant.now();
        try {
            TaskSnapshotFlushResult result = flushService.flushDirtyMasters(
                    Instant.now().minus(minDirtyAge),
                    batchSize);
            TaskRuntimeRepairResult repairResult = repairService.repairStuckActiveMasters(repairBatchSize);
            if (result.discovered() > 0 || result.failed() > 0 || repairResult.repaired() > 0
                    || repairResult.finalized() > 0 || repairResult.failed() > 0) {
                log.info("Task snapshot flush completed: discovered={}, flushed={}, failed={}, "
                                + "repairScanned={}, repaired={}, finalized={}, repairFailed={}",
                        result.discovered(), result.flushed(), result.failed(),
                        repairResult.scanned(), repairResult.repaired(), repairResult.finalized(), repairResult.failed());
                recordAudit(WorkerJobAuditEvent.succeeded(
                        JOB_TYPE_TASK_SNAPSHOT_FLUSH,
                        JOB_NAME_FLUSH_DIRTY_MASTERS,
                        CORRELATION_FLUSH_DIRTY_MASTERS,
                        elapsedSince(startedAt)));
            }
        } catch (RuntimeException ex) {
            recordAudit(WorkerJobAuditEvent.failed(
                    JOB_TYPE_TASK_SNAPSHOT_FLUSH,
                    JOB_NAME_FLUSH_DIRTY_MASTERS,
                    CORRELATION_FLUSH_DIRTY_MASTERS,
                    elapsedSince(startedAt),
                    new TaskSnapshotFlushAuditException("task snapshot flush failed dirty masters batch")));
            throw ex;
        }
    }

    private void recordAudit(WorkerJobAuditEvent event) {
        try {
            auditWriter.record(event);
        } catch (RuntimeException ex) {
            log.warn("Task snapshot flush audit write failed: {}", ex.getMessage());
        }
    }

    private static Duration elapsedSince(Instant startedAt) {
        return Duration.between(startedAt, Instant.now());
    }

    private static class TaskSnapshotFlushAuditException extends RuntimeException {
        TaskSnapshotFlushAuditException(String message) {
            super(message);
        }
    }
}
