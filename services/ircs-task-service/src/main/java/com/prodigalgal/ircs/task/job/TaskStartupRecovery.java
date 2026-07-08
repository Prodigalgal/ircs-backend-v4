package com.prodigalgal.ircs.task.job;



import com.prodigalgal.ircs.task.infrastructure.JdbcCollectionTaskRepository;
import com.prodigalgal.ircs.task.infrastructure.TaskDistributedLockRunner;
import com.prodigalgal.ircs.common.audit.WorkerJobAuditEvent;
import com.prodigalgal.ircs.common.audit.WorkerJobAuditWriter;
import java.time.Duration;
import java.time.Instant;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Component
@Order(1)
@Slf4j
class TaskStartupRecovery implements ApplicationRunner {

    static final String JOB_TYPE_TASK_STARTUP_RECOVERY = "task-startup-recovery";
    static final String JOB_NAME_RECOVER_INTERRUPTED_TASKS = "task-startup-recovery.recover-interrupted-tasks";
    static final String CORRELATION_RECOVER_INTERRUPTED_TASKS = "batch:recover-interrupted-tasks";

    private final JdbcCollectionTaskRepository taskRepository;
    private final WorkerJobAuditWriter auditWriter;
    private final TaskDistributedLockRunner lockRunner;
    TaskStartupRecovery(
            JdbcCollectionTaskRepository taskRepository,
            WorkerJobAuditWriter auditWriter,
            TaskDistributedLockRunner lockRunner) {
        this.taskRepository = taskRepository;
        this.auditWriter = auditWriter;
        this.lockRunner = lockRunner;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!lockRunner.runExclusive("task:startup-recovery", this::recoverInterruptedTasks)) {
            log.debug("Task startup recovery skipped: distributed lock is held by another instance");
        }
    }

    private void recoverInterruptedTasks() {
        Instant startedAt = Instant.now();
        try {
            int recovered = taskRepository.recoverInterruptedTasks(
                    "Detected task-service restart; task was moved to PAUSED for manual review.");
            if (recovered > 0) {
                log.info("Recovered {} interrupted collection tasks to PAUSED", recovered);
                recordAudit(WorkerJobAuditEvent.succeeded(
                        JOB_TYPE_TASK_STARTUP_RECOVERY,
                        JOB_NAME_RECOVER_INTERRUPTED_TASKS,
                        CORRELATION_RECOVER_INTERRUPTED_TASKS,
                        elapsedSince(startedAt)));
            }
        } catch (RuntimeException ex) {
            recordAudit(WorkerJobAuditEvent.failed(
                    JOB_TYPE_TASK_STARTUP_RECOVERY,
                    JOB_NAME_RECOVER_INTERRUPTED_TASKS,
                    CORRELATION_RECOVER_INTERRUPTED_TASKS,
                    elapsedSince(startedAt),
                    new TaskStartupRecoveryAuditException("startup recovery failed interrupted tasks batch")));
            throw ex;
        }
    }

    private static Duration elapsedSince(Instant startedAt) {
        return Duration.between(startedAt, Instant.now());
    }

    private void recordAudit(WorkerJobAuditEvent event) {
        try {
            auditWriter.record(event);
        } catch (RuntimeException ex) {
            log.warn("Task startup recovery audit write failed: {}", ex.getMessage());
        }
    }

    private static class TaskStartupRecoveryAuditException extends RuntimeException {
        TaskStartupRecoveryAuditException(String message) {
            super(message);
        }
    }
}
