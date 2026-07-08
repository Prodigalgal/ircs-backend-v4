package com.prodigalgal.ircs.task.controller;

import com.prodigalgal.ircs.common.web.PageEnvelope;
import com.prodigalgal.ircs.task.application.TaskCommandService;
import com.prodigalgal.ircs.task.application.TaskLogService;
import com.prodigalgal.ircs.task.application.TaskQueryService;
import com.prodigalgal.ircs.task.dto.TaskRuntimeDetailResponse;
import com.prodigalgal.ircs.task.application.TaskRuntimeReadService;
import com.prodigalgal.ircs.task.dto.TaskCardSummary;
import com.prodigalgal.ircs.task.dto.TaskCreateRequest;
import com.prodigalgal.ircs.task.dto.TaskDetailSummary;
import com.prodigalgal.ircs.task.dto.TaskIdResponse;
import com.prodigalgal.ircs.task.dto.TaskItemLogResponse;
import com.prodigalgal.ircs.task.dto.TaskUpdateRequest;
import java.util.UUID;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/collection-tasks")
public class CollectionTaskController {

    private final TaskQueryService taskQueryService;
    private final TaskCommandService taskCommandService;
    private final TaskLogService taskLogService;
    private final TaskRuntimeReadService taskRuntimeReadService;

    @PostMapping
    public ResponseEntity<TaskIdResponse> createTask(@Valid @RequestBody TaskCreateRequest request) {
        UUID id = taskCommandService.create(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(new TaskIdResponse(id));
    }

    @PutMapping("/{id}")
    public ResponseEntity<Void> updateTask(
            @PathVariable UUID id,
            @Valid @RequestBody TaskUpdateRequest request
    ) {
        taskCommandService.update(id, request);
        return ResponseEntity.ok().build();
    }

    @GetMapping
    public ResponseEntity<PageEnvelope<TaskCardSummary>> listTasks(
            @PageableDefault(size = 20) Pageable pageable,
            @RequestParam(required = false) String name,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) UUID dataSourceId,
            @RequestParam(required = false) Boolean enabled
    ) {
        return ResponseEntity.ok(PageEnvelope.from(taskQueryService.findAll(pageable, name, status, dataSourceId, enabled)));
    }

    @GetMapping("/{id}")
    public ResponseEntity<TaskDetailSummary> getTask(@PathVariable UUID id) {
        return taskQueryService.findOne(id)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping("/{id}/runtime")
    public ResponseEntity<TaskRuntimeDetailResponse> getTaskRuntime(
            @PathVariable UUID id,
            @RequestParam(defaultValue = "0") int pageOffset,
            @RequestParam(defaultValue = "100") int pageLimit,
            @RequestParam(defaultValue = "20") int detailLimit
    ) {
        return taskRuntimeReadService.find(id, pageOffset, pageLimit, detailLimit)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteTask(@PathVariable UUID id) {
        taskCommandService.delete(id);
        taskLogService.clearLogs(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/start")
    public ResponseEntity<Void> startTask(@PathVariable UUID id) {
        taskCommandService.start(id, false);
        return ResponseEntity.accepted().build();
    }

    @PostMapping("/{id}/resume")
    public ResponseEntity<Void> resumeTask(@PathVariable UUID id) {
        taskCommandService.start(id, true);
        return ResponseEntity.accepted().build();
    }

    @PostMapping("/{id}/pause")
    public ResponseEntity<Void> pauseTask(@PathVariable UUID id) {
        taskCommandService.pause(id);
        return ResponseEntity.accepted().build();
    }

    @PostMapping("/{id}/stop")
    public ResponseEntity<Void> stopTask(@PathVariable UUID id) {
        taskCommandService.stop(id);
        return ResponseEntity.accepted().build();
    }

    @GetMapping("/{id}/logs")
    public ResponseEntity<java.util.List<TaskItemLogResponse>> getTaskLogs(
            @PathVariable UUID id,
            @RequestParam(defaultValue = "0") int offset,
            @RequestParam(defaultValue = "50") int limit
    ) {
        return ResponseEntity.ok(taskLogService.getLogs(id, offset, limit));
    }
}
