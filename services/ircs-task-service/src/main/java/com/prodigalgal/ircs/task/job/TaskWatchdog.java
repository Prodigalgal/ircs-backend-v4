package com.prodigalgal.ircs.task.job;

import com.prodigalgal.ircs.common.audit.WorkerJobAuditEvent;
import com.prodigalgal.ircs.common.audit.WorkerJobAuditWriter;
import com.prodigalgal.ircs.common.scheduling.ScheduledTriggers;
import com.prodigalgal.ircs.task.infrastructure.JdbcCollectionTaskRepository;
import com.prodigalgal.ircs.task.infrastructure.TaskDistributedLockRunner;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.ExecutorService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(value = "app.task.watchdog.enabled", havingValue = "true")
@Slf4j
class TaskWatchdog {

    static final String JOB_TYPE_TASK_WATCHDOG = "task-watchdog";
    static final String JOB_NAME_FAIL_STALE_ACTIVE_TASKS = "task-watchdog.fail-stale-active-tasks";
    static final String CORRELATION_FAIL_STALE_ACTIVE_TASKS = "batch:fail-stale-active-tasks";

    private final JdbcCollectionTaskRepository taskRepository;
    private final WorkerJobAuditWriter auditWriter;
    private final TaskDistributedLockRunner lockRunner;
    private final ExecutorService triggerExecutor =
            ScheduledTriggers.virtualThreadExecutor("ircs-task-watchdog-trigger-vt-");

    TaskWatchdog(
            JdbcCollectionTaskRepository taskRepository,
            WorkerJobAuditWriter auditWriter,
            TaskDistributedLockRunner lockRunner) {
        this.taskRepository = taskRepository;
        this.auditWriter = auditWriter;
        this.lockRunner = lockRunner;
    }

    @Scheduled(
            fixedDelayString = "${app.task.runner.watchdog-delay-ms:300000}",
            initialDelayString = "${app.task.runner.watchdog-initial-delay-ms:60000}")
    void failStaleActiveTasks() {
        ScheduledTriggers.submit(triggerExecutor, this::failStaleActiveTasksOnce, log, "task.watchdog.fail-stale-active-tasks");
    }

    @PreDestroy
    void shutdownTriggerExecutor() {
        triggerExecutor.shutdownNow();
    }

    void failStaleActiveTasksOnce() {
        if (!lockRunner.runExclusive("task:watchdog:fail-stale-active-tasks", this::failStaleActiveTasksLocked)) {
            log.debug("Task watchdog skipped: distributed lock is held by another instance");
        }
    }

    private void failStaleActiveTasksLocked() {
        Instant startedAt = Instant.now();
        Instant threshold = Instant.now().minus(30, ChronoUnit.MINUTES);
        try {
            int failed = taskRepository.failStaleActiveTasks(
                    threshold,
                    "Watchdog: Task heartbeat timeout.");
            if (failed > 0) {
                log.warn("Watchdog failed {} stale collection tasks", failed);
                recordAudit(WorkerJobAuditEvent.succeeded(
                        JOB_TYPE_TASK_WATCHDOG,
                        JOB_NAME_FAIL_STALE_ACTIVE_TASKS,
                        CORRELATION_FAIL_STALE_ACTIVE_TASKS,
                        elapsedSince(startedAt)));
            }
        } catch (RuntimeException ex) {
            recordAudit(WorkerJobAuditEvent.failed(
                    JOB_TYPE_TASK_WATCHDOG,
                    JOB_NAME_FAIL_STALE_ACTIVE_TASKS,
                    CORRELATION_FAIL_STALE_ACTIVE_TASKS,
                    elapsedSince(startedAt),
                    new TaskWatchdogAuditException("watchdog failed stale active tasks batch")));
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
            log.warn("Task watchdog audit write failed: {}", ex.getMessage());
        }
    }

    private static class TaskWatchdogAuditException extends RuntimeException {
        TaskWatchdogAuditException(String message) {
            super(message);
        }
    }
}
