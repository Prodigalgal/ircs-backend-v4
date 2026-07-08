package com.prodigalgal.ircs.task.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.prodigalgal.ircs.task.domain.TaskConfigurationChangedEvent;
import com.prodigalgal.ircs.task.domain.TaskRuntimeState;
import com.prodigalgal.ircs.task.domain.ValidatedCreateTask;
import com.prodigalgal.ircs.task.domain.ValidatedUpdateTask;
import com.prodigalgal.ircs.task.dto.TaskCreateRequest;
import com.prodigalgal.ircs.task.dto.TaskUpdateRequest;
import com.prodigalgal.ircs.task.infrastructure.JdbcCollectionTaskRepository;
import com.prodigalgal.ircs.task.runtime.TaskProgressRedisService;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Qualifier;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.server.ResponseStatusException;

@Service
@Slf4j
public class TaskCommandService {

    private final JdbcCollectionTaskRepository taskRepository;
    private final ObjectMapper objectMapper;
    private final ApplicationEventPublisher eventPublisher;
    private final TaskQueueDispatchService taskQueueDispatchService;
    private final TaskProgressRedisService progressService;
    private final Executor taskQueueDispatchExecutor;

    public TaskCommandService(
            JdbcCollectionTaskRepository taskRepository,
            ObjectMapper objectMapper,
            ApplicationEventPublisher eventPublisher,
            TaskQueueDispatchService taskQueueDispatchService,
            TaskProgressRedisService progressService,
            @Qualifier("taskQueueDispatchExecutor") Executor taskQueueDispatchExecutor) {
        this.taskRepository = taskRepository;
        this.objectMapper = objectMapper;
        this.eventPublisher = eventPublisher;
        this.taskQueueDispatchService = taskQueueDispatchService;
        this.progressService = progressService;
        this.taskQueueDispatchExecutor = taskQueueDispatchExecutor;
    }

    @Transactional
    public UUID create(TaskCreateRequest request) {
        ensureDataSourceExists(request.dataSourceId());
        ValidatedCreateTask task = validateCreate(request);
        try {
            UUID id = taskRepository.create(task);
            publishAfterCommit(new TaskConfigurationChangedEvent(id, false));
            return id;
        } catch (DuplicateKeyException ex) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Task name already exists", ex);
        } catch (DataIntegrityViolationException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid task payload", ex);
        }
    }

    @Transactional
    public void update(UUID id, TaskUpdateRequest request) {
        if (!Objects.equals(id, request.id())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Path ID mismatch");
        }
        if (request.dataSourceId() != null) {
            ensureDataSourceExists(request.dataSourceId());
        }
        ValidatedUpdateTask task = validateUpdate(request);
        try {
            boolean updated = taskRepository.update(id, task);
            if (!updated) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Task not found");
            }
            publishAfterCommit(new TaskConfigurationChangedEvent(id, false));
        } catch (DuplicateKeyException ex) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Task name already exists", ex);
        } catch (DataIntegrityViolationException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid task payload", ex);
        }
    }

    @Transactional
    public void delete(UUID id) {
        taskRepository.delete(id);
        publishAfterCommit(new TaskConfigurationChangedEvent(id, true));
    }

    @Transactional
    public void start(UUID id, boolean resume) {
        TaskRuntimeState task = taskRepository.findRuntimeState(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Task not found"));
        if (!Boolean.TRUE.equals(task.enabled())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Task is disabled");
        }
        if (isBusy(task.status())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Task is already active or stopping");
        }
        if (taskRepository.existsActiveTaskForDataSource(task.dataSourceId(), id)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "DataSource busy");
        }
        taskRepository.markQueued(task, resume);
        dispatchQueuedMasterAfterCommit(id, resume);
    }

    @Transactional
    public boolean startInternal(UUID id, boolean resume) {
        TaskRuntimeState task = taskRepository.findRuntimeState(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Task not found"));
        if (!Boolean.TRUE.equals(task.enabled()) || isBusy(task.status())) {
            return false;
        }
        taskRepository.markQueued(task, resume);
        dispatchQueuedMasterAfterCommit(id, resume);
        return true;
    }

    @Transactional
    public void pause(UUID id) {
        taskRepository.pause(id);
        runAfterCommit(() -> progressService.holdMaster(id, "PAUSED", "Task paused by operator.", java.time.Instant.now()));
    }

    @Transactional
    public void stop(UUID id) {
        taskRepository.stop(id);
        runAfterCommit(() -> progressService.holdMaster(id, "STOPPING", "Task stopped by operator.", java.time.Instant.now()));
    }

    private void ensureDataSourceExists(UUID dataSourceId) {
        if (!taskRepository.existsDataSource(dataSourceId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "DataSource not found");
        }
    }

    private boolean isBusy(String status) {
        return "RUNNING".equals(status) || "QUEUED".equals(status) || "STOPPING".equals(status);
    }

    private void publishAfterCommit(Object event) {
        runAfterCommit(() -> eventPublisher.publishEvent(event));
    }

    private void dispatchQueuedMasterAfterCommit(UUID id, boolean resume) {
        runAfterCommit(() -> {
            try {
                taskQueueDispatchExecutor.execute(() -> dispatchQueuedMasterSafely(id, resume));
            } catch (RuntimeException ex) {
                String reason = "Task queue dispatch submit failed: " + safeMessage(ex);
                taskRepository.fail(id, reason);
                log.error("Collection task dispatch submit failed, taskId={}, resume={}", id, resume, ex);
            }
        });
    }

    private void dispatchQueuedMasterSafely(UUID id, boolean resume) {
        try {
            taskQueueDispatchService.dispatchQueuedMaster(id, resume);
        } catch (RuntimeException ex) {
            log.error("Collection task post-commit dispatch failed, taskId={}, resume={}", id, resume, ex);
        }
    }

    private void runAfterCommit(Runnable action) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            action.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                action.run();
            }
        });
    }

    private ValidatedCreateTask validateCreate(TaskCreateRequest request) {
        String headers = validateHeaders(request.headers());
        return new ValidatedCreateTask(
                requireText(request.name(), "name"),
                request.dataSourceId(),
                normalizeTaskType(request.taskType()),
                defaultBoolean(request.enabled(), true),
                blankToNull(request.cronExpression()),
                defaultText(request.timeZone(), "UTC"),
                defaultInteger(request.startPage(), 1),
                defaultInteger(request.endPage(), 0),
                blankToNull(request.filterType()),
                request.filterHours(),
                blankToNull(request.filterKeywords()),
                normalizeDelayType(request.requestDelayType(), "RANDOM"),
                defaultInteger(request.fixedDelayMs(), 500),
                defaultInteger(request.randomDelayMinMs(), 1000),
                defaultInteger(request.randomDelayMaxMs(), 3000),
                defaultInteger(request.timeoutMs(), 10000),
                defaultInteger(request.maxRetries(), 3),
                blankToNull(request.userAgent()),
                defaultBoolean(request.enableRandomUa(), true),
                defaultBoolean(request.useCustomProxy(), false),
                blankToNull(request.proxyType()),
                blankToNull(request.proxyHost()),
                request.proxyPort(),
                blankToNull(request.proxyUsername()),
                blankToNull(request.proxyPassword()),
                headers);
    }

    private ValidatedUpdateTask validateUpdate(TaskUpdateRequest request) {
        return new ValidatedUpdateTask(
                optionalText(request.name(), "name"),
                request.enabled(),
                blankToNull(request.cronExpression()),
                optionalText(request.timeZone(), "timeZone"),
                request.dataSourceId(),
                optionalTaskType(request.taskType()),
                request.startPage(),
                request.endPage(),
                blankToNull(request.filterType()),
                request.filterHours(),
                blankToNull(request.filterKeywords()),
                optionalDelayType(request.requestDelayType()),
                request.fixedDelayMs(),
                request.randomDelayMinMs(),
                request.randomDelayMaxMs(),
                request.timeoutMs(),
                request.maxRetries(),
                blankToNull(request.userAgent()),
                request.enableRandomUa(),
                request.useCustomProxy(),
                blankToNull(request.proxyType()),
                blankToNull(request.proxyHost()),
                request.proxyPort(),
                blankToNull(request.proxyUsername()),
                blankToNull(request.proxyPassword()),
                validateHeaders(request.headers()));
    }

    private String validateHeaders(String headers) {
        String normalized = blankToNull(headers);
        if (normalized == null) {
            return null;
        }
        try {
            objectMapper.readTree(normalized);
            return normalized;
        } catch (Exception ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "headers must be valid JSON", ex);
        }
    }

    private String normalizeTaskType(String value) {
        String normalized = requireText(value, "taskType").toUpperCase();
        if (!"BY_PAGE".equals(normalized)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unsupported taskType");
        }
        return normalized;
    }

    private String optionalTaskType(String value) {
        return value == null ? null : normalizeTaskType(value);
    }

    private String normalizeDelayType(String value, String fallback) {
        String normalized = defaultText(value, fallback).toUpperCase();
        if (!"FIXED".equals(normalized) && !"RANDOM".equals(normalized)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unsupported requestDelayType");
        }
        return normalized;
    }

    private String optionalDelayType(String value) {
        return value == null ? null : normalizeDelayType(value, "RANDOM");
    }

    private String requireText(String value, String field) {
        String normalized = blankToNull(value);
        if (normalized == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, field + " is required");
        }
        return normalized;
    }

    private String optionalText(String value, String field) {
        if (value == null) {
            return null;
        }
        return requireText(value, field);
    }

    private String defaultText(String value, String fallback) {
        String normalized = blankToNull(value);
        return normalized == null ? fallback : normalized;
    }

    private String blankToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    private Boolean defaultBoolean(Boolean value, Boolean fallback) {
        return value == null ? fallback : value;
    }

    private Integer defaultInteger(Integer value, Integer fallback) {
        return value == null ? fallback : value;
    }

    private String safeMessage(Throwable ex) {
        if (ex == null || ex.getMessage() == null || ex.getMessage().isBlank()) {
            return ex == null ? "unknown" : ex.getClass().getSimpleName();
        }
        String message = ex.getMessage().replaceAll("\\s+", " ").trim();
        return message.length() <= 240 ? message : message.substring(0, 240);
    }
}
