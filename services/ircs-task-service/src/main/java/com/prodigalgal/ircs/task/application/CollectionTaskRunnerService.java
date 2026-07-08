package com.prodigalgal.ircs.task.application;






import com.prodigalgal.ircs.task.infrastructure.ScraperTaskExecutionLog;
import com.prodigalgal.ircs.task.infrastructure.JdbcCollectionTaskRepository;
import com.prodigalgal.ircs.task.infrastructure.ScraperTaskExecutionResult;
import com.prodigalgal.ircs.task.domain.TaskExecutionPlan;
import com.prodigalgal.ircs.task.infrastructure.ScraperTaskExecutionClient;
import com.prodigalgal.ircs.common.audit.WorkerJobAuditEvent;
import com.prodigalgal.ircs.common.audit.WorkerJobAuditWriter;
import com.prodigalgal.ircs.task.dto.TaskItemLogResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskRejectedException;

@Slf4j
class CollectionTaskRunnerService {

    static final String JOB_TYPE_COLLECTION_TASK_RUNNER = "collection-task-runner";
    static final String JOB_NAME_COLLECTION_TASK_RUNNER = "collection-task.runner";

    private final JdbcCollectionTaskRepository taskRepository;
    private final TaskLogService taskLogService;
    private final ScraperTaskExecutionClient scraperClient;
    private final Executor executor;
    private final WorkerJobAuditWriter auditWriter;

    CollectionTaskRunnerService(
            JdbcCollectionTaskRepository taskRepository,
            TaskLogService taskLogService,
            ScraperTaskExecutionClient scraperClient,
            @Qualifier("collectionTaskRunnerExecutor") Executor executor,
            WorkerJobAuditWriter auditWriter) {
        this.taskRepository = taskRepository;
        this.taskLogService = taskLogService;
        this.scraperClient = scraperClient;
        this.executor = executor;
        this.auditWriter = auditWriter;
    }

    void submit(UUID taskId, boolean resume) {
        Instant submittedAt = Instant.now();
        try {
            executor.execute(() -> run(taskId, resume));
        } catch (RuntimeException ex) {
            if (!isRejectedExecution(ex)) {
                throw ex;
            }
            String reason = "Task runner rejected by executor: " + safeMessage(ex);
            taskRepository.fail(taskId, reason);
            append(taskId, "ERROR", "SYSTEM", reason);
            recordRefused(submittedAt, taskId, "refused: executor rejected task");
            log.warn("Collection task {} rejected by executor: {}", taskId, safeMessage(ex));
        }
    }

    private void run(UUID taskId, boolean resume) {
        Instant startedAt = Instant.now();
        TaskExecutionPlan plan = taskRepository.findExecutionPlan(taskId)
                .orElse(null);
        if (plan == null) {
            log.warn("Collection task {} skipped: execution plan missing", taskId);
            recordSkipped(startedAt, taskId, "skipped: execution plan missing");
            return;
        }
        if (!Boolean.TRUE.equals(plan.enabled())) {
            append(taskId, "WARN", "SYSTEM", "Task skipped: disabled");
            recordSkipped(startedAt, taskId, "skipped: task disabled");
            return;
        }
        if (!"QUEUED".equals(plan.status())) {
            append(taskId, "WARN", "SYSTEM", "Task skipped: current status is " + plan.status());
            recordSkipped(startedAt, taskId, "skipped: current status is " + safeStatus(plan.status()));
            return;
        }
        if (!taskRepository.markRunning(taskId)) {
            append(taskId, "WARN", "SYSTEM", "Task skipped: status changed before runner picked it");
            recordRefused(startedAt, taskId, "refused: status changed before runner picked task");
            return;
        }

        append(taskId, "INFO", "SYSTEM", "Task runner started");
        try {
            ScraperTaskExecutionResult result = scraperClient.execute(plan, resume);
            appendLogs(taskId, result == null ? List.of() : result.logs());
            String currentStatus = taskRepository.statusOf(taskId);
            if ("PAUSED".equals(currentStatus) || "STOPPING".equals(currentStatus)) {
                taskRepository.markPaused(taskId, "Task runner stopped after current batch.");
                append(taskId, "WARN", "SYSTEM", "Task runner stopped after current batch");
                recordSkipped(startedAt, taskId, "skipped: task stopped after current batch");
                return;
            }
            if (result != null && result.successful()) {
                taskRepository.complete(taskId, result.published() + result.failed(),
                        result.published(), result.failed());
                append(taskId, "INFO", "SYSTEM", "Task completed");
                recordSucceeded(startedAt, taskId);
            } else {
                int published = result == null ? 0 : result.published();
                int failed = result == null ? 1 : result.failed();
                taskRepository.fail(taskId, "Task execution failed: published=" + published + ", failed=" + failed);
                append(taskId, "ERROR", "SYSTEM", "Task failed");
                recordFailed(startedAt, taskId,
                        "runner result failed: published=" + published + ", failed=" + failed);
            }
        } catch (Exception ex) {
            taskRepository.fail(taskId, ex.getMessage());
            append(taskId, "ERROR", "SYSTEM", "Task runner failed: " + ex.getMessage());
            recordFailed(startedAt, taskId, "runner exception: " + ex.getClass().getName());
        }
    }

    private void appendLogs(UUID taskId, List<ScraperTaskExecutionLog> logs) {
        if (logs == null) {
            return;
        }
        for (ScraperTaskExecutionLog logEntry : logs) {
            taskLogService.appendLog(taskId, new TaskItemLogResponse(
                    logEntry.timestamp(),
                    logEntry.level(),
                    logEntry.sourceVid(),
                    logEntry.message()));
        }
    }

    private void append(UUID taskId, String level, String sourceVid, String message) {
        taskLogService.appendLog(taskId, new TaskItemLogResponse(
                Instant.now().toString(),
                level,
                sourceVid,
                message));
    }

    private void recordSucceeded(Instant startedAt, UUID taskId) {
        recordAudit(WorkerJobAuditEvent.succeeded(
                JOB_TYPE_COLLECTION_TASK_RUNNER,
                JOB_NAME_COLLECTION_TASK_RUNNER,
                correlationId(taskId),
                elapsedSince(startedAt)));
    }

    private void recordFailed(Instant startedAt, UUID taskId, String reason) {
        recordAudit(WorkerJobAuditEvent.failed(
                JOB_TYPE_COLLECTION_TASK_RUNNER,
                JOB_NAME_COLLECTION_TASK_RUNNER,
                correlationId(taskId),
                elapsedSince(startedAt),
                new CollectionTaskRunnerAuditException(reason)));
    }

    private void recordSkipped(Instant startedAt, UUID taskId, String reason) {
        recordAudit(new WorkerJobAuditEvent(
                JOB_TYPE_COLLECTION_TASK_RUNNER,
                JOB_NAME_COLLECTION_TASK_RUNNER,
                correlationId(taskId),
                "skipped",
                elapsedSince(startedAt),
                new CollectionTaskRunnerAuditException(reason)));
    }

    private void recordRefused(Instant startedAt, UUID taskId, String reason) {
        recordAudit(new WorkerJobAuditEvent(
                JOB_TYPE_COLLECTION_TASK_RUNNER,
                JOB_NAME_COLLECTION_TASK_RUNNER,
                correlationId(taskId),
                "refused",
                elapsedSince(startedAt),
                new CollectionTaskRunnerAuditException(reason)));
    }

    private void recordAudit(WorkerJobAuditEvent event) {
        try {
            auditWriter.record(event);
        } catch (RuntimeException ex) {
            log.warn("Collection task runner audit write failed: {}", ex.getMessage());
        }
    }

    private static Duration elapsedSince(Instant startedAt) {
        return Duration.between(startedAt, Instant.now());
    }

    private static String correlationId(UUID taskId) {
        return taskId == null ? null : taskId.toString();
    }

    private static String safeStatus(String status) {
        if (status == null || status.isBlank()) {
            return "unknown";
        }
        String trimmed = status.trim();
        return trimmed.matches("[A-Z_]{1,32}") ? trimmed : "unknown";
    }

    private static boolean isRejectedExecution(RuntimeException ex) {
        return ex instanceof RejectedExecutionException
                || ex instanceof TaskRejectedException
                || ex.getCause() instanceof RejectedExecutionException;
    }

    private static String safeMessage(Throwable ex) {
        if (ex == null || ex.getMessage() == null || ex.getMessage().isBlank()) {
            return ex == null ? "unknown" : ex.getClass().getSimpleName();
        }
        String message = ex.getMessage().replaceAll("\\s+", " ").trim();
        return message.length() <= 240 ? message : message.substring(0, 240);
    }

    private static class CollectionTaskRunnerAuditException extends RuntimeException {
        CollectionTaskRunnerAuditException(String message) {
            super(message);
        }
    }
}
