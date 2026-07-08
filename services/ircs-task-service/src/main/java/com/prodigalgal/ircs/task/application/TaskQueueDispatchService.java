package com.prodigalgal.ircs.task.application;






import com.prodigalgal.ircs.task.messaging.TaskQueuePublisher;
import com.prodigalgal.ircs.task.infrastructure.JdbcCollectionTaskRepository;
import com.prodigalgal.ircs.task.runtime.TaskProgressRedisService;
import com.prodigalgal.ircs.task.domain.TaskExecutionPlan;
import com.prodigalgal.ircs.task.domain.TaskRuntimeStatus;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.prodigalgal.ircs.common.id.IrcsUuidGenerators;
import com.prodigalgal.ircs.contracts.task.TaskMasterSnapshot;
import com.prodigalgal.ircs.contracts.task.TaskPageMessage;
import com.prodigalgal.ircs.contracts.task.TaskScrapeOptions;
import com.prodigalgal.ircs.task.dto.TaskItemLogResponse;
import java.time.Instant;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
@Slf4j
public class TaskQueueDispatchService {

    private final JdbcCollectionTaskRepository taskRepository;
    private final TaskMasterSnapshotService snapshotService;
    private final TaskProgressRedisService progressService;
    private final TaskQueuePublisher queuePublisher;
    private final TaskLogService taskLogService;
    private final ObjectMapper objectMapper;
    private final boolean enabled;
    private final int maxPagesPerRun;

    public TaskQueueDispatchService(
            JdbcCollectionTaskRepository taskRepository,
            TaskMasterSnapshotService snapshotService,
            TaskProgressRedisService progressService,
            TaskQueuePublisher queuePublisher,
            TaskLogService taskLogService,
            ObjectMapper objectMapper,
            @Value("${app.task.queue.enabled:true}") boolean enabled,
            @Value("${app.task.queue.max-pages-per-run:0}") int maxPagesPerRun) {
        this.taskRepository = taskRepository;
        this.snapshotService = snapshotService;
        this.progressService = progressService;
        this.queuePublisher = queuePublisher;
        this.taskLogService = taskLogService;
        this.objectMapper = objectMapper;
        this.enabled = enabled;
        this.maxPagesPerRun = Math.max(0, maxPagesPerRun);
    }

    public void dispatchQueuedMaster(UUID taskId, boolean resume) {
        if (!enabled) {
            failMaster(taskId, "Task Rabbit queue is disabled; destructive V3 task mode has no local runner fallback.");
            return;
        }
        TaskExecutionPlan plan = taskRepository.findExecutionPlan(taskId).orElse(null);
        if (plan == null) {
            log.warn("Task master dispatch skipped: execution plan missing, masterTaskId={}", taskId);
            return;
        }
        if (!TaskRuntimeStatus.QUEUED.value().equalsIgnoreCase(plan.status())) {
            log.info("Task master dispatch skipped: masterTaskId={}, status={}", taskId, plan.status());
            return;
        }
        Instant now = Instant.now();
        TaskScrapeOptions options = scrapeOptions(plan);
        int firstPage = effectiveStartPage(plan.currentPage(), plan.startPage());
        TaskMasterSnapshot queuedSnapshot = snapshot(plan, resume, 0, TaskRuntimeStatus.QUEUED.value(), null, now);
        try {
            snapshotService.put(queuedSnapshot);
            dispatchPage(plan, firstPage, resume, options, correlationId(plan.id()), now);
        } catch (RuntimeException ex) {
            String reason = "Task queue dispatch failed: " + safeMessage(ex);
            failMaster(plan.id(), reason);
            snapshotService.put(snapshot(plan, resume, 0, TaskRuntimeStatus.FAILED.value(), reason, Instant.now()));
            throw ex;
        }
    }

    public DispatchNextPageResult dispatchNextPageIfNeeded(
            UUID masterTaskId,
            int completedPage,
            Integer observedTotalPages,
            String correlationId) {
        if (!enabled || masterTaskId == null || completedPage < 1) {
            return DispatchNextPageResult.BLOCKED;
        }
        TaskExecutionPlan plan = taskRepository.findExecutionPlan(masterTaskId).orElse(null);
        if (plan == null) {
            log.warn("Task page expansion skipped: execution plan missing, masterTaskId={}", masterTaskId);
            return DispatchNextPageResult.BLOCKED;
        }
        if (!allowsPageExpansion(plan.status())) {
            log.info("Task page expansion skipped: masterTaskId={}, status={}", masterTaskId, plan.status());
            return DispatchNextPageResult.BLOCKED;
        }
        int nextPage = completedPage + 1;
        Integer finalPage = finalPage(plan, observedTotalPages);
        if (finalPage == null || nextPage > finalPage) {
            return DispatchNextPageResult.NO_MORE_PAGES;
        }
        Instant now = Instant.now();
        boolean dispatched = dispatchPage(
                plan,
                nextPage,
                false,
                scrapeOptions(plan),
                correlationId(plan.id(), correlationId),
                now);
        return dispatched ? DispatchNextPageResult.DISPATCHED : DispatchNextPageResult.BLOCKED;
    }

    private boolean dispatchPage(
            TaskExecutionPlan plan,
            int pageNumber,
            boolean resume,
            TaskScrapeOptions options,
            String correlationId,
            Instant now) {
        Integer finalPage = finalPage(plan, null);
        if (finalPage != null && pageNumber > finalPage) {
            return false;
        }
        UUID pageTaskId = IrcsUuidGenerators.nextId();
        long scheduledPage = progressService.trySchedulePage(plan.id(), pageTaskId, pageNumber, now);
        if (scheduledPage <= 0) {
            return false;
        }
        try {
            queuePublisher.publishPage(new TaskPageMessage(
                    plan.id(),
                    pageTaskId,
                    plan.dataSourceId(),
                    (int) scheduledPage,
                    resume,
                    0,
                    options,
                    correlationId,
                    now));
        } catch (RuntimeException ex) {
            progressService.rollbackScheduledPage(plan.id(), pageTaskId, (int) scheduledPage, Instant.now());
            throw ex;
        }
        append(plan.id(), "INFO", "Queued page task " + scheduledPage + " into RabbitMQ");
        return true;
    }

    private boolean allowsPageExpansion(String status) {
        return TaskRuntimeStatus.allowsPageExpansion(status);
    }

    private Integer finalPage(TaskExecutionPlan plan, Integer observedTotalPages) {
        int start = effectiveStartPage(plan.currentPage(), plan.startPage());
        Integer configuredEnd = effectiveEndPage(plan.endPage());
        Integer observedEnd = observedTotalPages != null && observedTotalPages > 0 ? observedTotalPages : null;
        Integer finalPage = configuredEnd == null ? observedEnd : configuredEnd;
        if (finalPage == null) {
            return null;
        }
        finalPage = Math.max(start, finalPage);
        if (maxPagesPerRun > 0) {
            finalPage = Math.min(finalPage, start + maxPagesPerRun - 1);
        }
        return finalPage;
    }

    private TaskMasterSnapshot snapshot(
            TaskExecutionPlan plan,
            boolean resume,
            long pageScheduled,
            String status,
            String lastError,
            Instant now) {
        return new TaskMasterSnapshot(
                plan.id(),
                plan.dataSourceId(),
                plan.name(),
                status,
                resume,
                effectiveStartPage(plan.currentPage(), plan.startPage()),
                effectiveEndPage(plan.endPage()),
                pageScheduled,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                lastError,
                correlationId(plan.id()),
                now,
                now);
    }

    private TaskScrapeOptions scrapeOptions(TaskExecutionPlan plan) {
        return new TaskScrapeOptions(
                blankToNull(plan.filterKeywords()),
                blankToNull(plan.filterType()),
                plan.filterHours(),
                plan.userAgent(),
                Boolean.TRUE.equals(plan.enableRandomUa()),
                Boolean.TRUE.equals(plan.useCustomProxy()),
                plan.proxyType(),
                plan.proxyHost(),
                plan.proxyPort(),
                plan.proxyUsername(),
                plan.proxyPassword(),
                httpHeaders(plan.headers()),
                plan.fixedDelayMs(),
                false);
    }

    private String httpHeaders(String headers) {
        if (headers == null || headers.isBlank()) {
            return null;
        }
        try {
            JsonNode root = objectMapper.readTree(headers);
            JsonNode httpHeaders = root.path("httpHeaders");
            if (httpHeaders.isObject()) {
                return objectMapper.writeValueAsString(httpHeaders);
            }
        } catch (Exception ignored) {
            return headers;
        }
        return headers;
    }

    private void failMaster(UUID taskId, String reason) {
        taskRepository.fail(taskId, reason);
        append(taskId, "ERROR", reason);
    }

    private void append(UUID taskId, String level, String message) {
        taskLogService.appendLog(taskId, new TaskItemLogResponse(
                Instant.now().toString(),
                level,
                "SYSTEM",
                message));
    }

    private int effectiveStartPage(Integer currentPage, Integer startPage) {
        if (currentPage != null && currentPage > 0) {
            return currentPage;
        }
        return startPage != null && startPage > 0 ? startPage : 1;
    }

    private Integer effectiveEndPage(Integer endPage) {
        return endPage != null && endPage > 0 ? endPage : null;
    }

    private static String correlationId(UUID taskId) {
        return taskId == null ? null : taskId.toString();
    }

    private static String correlationId(UUID taskId, String existing) {
        return existing == null || existing.isBlank() ? correlationId(taskId) : existing;
    }

    private static String blankToNull(String keyword) {
        return keyword == null || keyword.isBlank() ? null : keyword;
    }

    private static String safeMessage(Throwable ex) {
        if (ex == null || ex.getMessage() == null || ex.getMessage().isBlank()) {
            return ex == null ? "unknown" : ex.getClass().getSimpleName();
        }
        String message = ex.getMessage().replaceAll("\\s+", " ").trim();
        return message.length() <= 240 ? message : message.substring(0, 240);
    }
}
