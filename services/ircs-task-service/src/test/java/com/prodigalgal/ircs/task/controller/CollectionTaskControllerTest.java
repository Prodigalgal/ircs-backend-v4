package com.prodigalgal.ircs.task.controller;






import com.prodigalgal.ircs.task.dto.TaskRuntimeDetailResponse;
import com.prodigalgal.ircs.task.dto.TaskMasterRuntimeSummary;
import com.prodigalgal.ircs.task.application.TaskQueryService;
import com.prodigalgal.ircs.task.application.TaskRuntimeReadService;
import com.prodigalgal.ircs.task.application.TaskCommandService;
import com.prodigalgal.ircs.task.application.TaskLogService;
import com.prodigalgal.ircs.task.controller.CollectionTaskController;
import com.prodigalgal.ircs.task.dto.TaskCardSummary;
import com.prodigalgal.ircs.task.dto.TaskCreateRequest;
import com.prodigalgal.ircs.task.dto.TaskDetailSummary;
import com.prodigalgal.ircs.task.dto.TaskItemLogResponse;
import com.prodigalgal.ircs.task.dto.TaskUpdateRequest;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;

class CollectionTaskControllerTest {

    private final TaskQueryService taskQueryService = org.mockito.Mockito.mock(TaskQueryService.class);
    private final TaskCommandService taskCommandService = org.mockito.Mockito.mock(TaskCommandService.class);
    private final TaskLogService taskLogService = org.mockito.Mockito.mock(TaskLogService.class);
    private final TaskRuntimeReadService taskRuntimeReadService = org.mockito.Mockito.mock(TaskRuntimeReadService.class);
    private final CollectionTaskController controller =
            new CollectionTaskController(taskQueryService, taskCommandService, taskLogService, taskRuntimeReadService);

    @Test
    void createsTask() {
        UUID dataSourceId = UUID.randomUUID();
        UUID taskId = UUID.randomUUID();
        TaskCreateRequest request = new TaskCreateRequest(
                "Codex task",
                dataSourceId,
                "BY_PAGE",
                true,
                null,
                "Asia/Shanghai",
                1,
                2,
                null,
                null,
                null,
                "RANDOM",
                500,
                1000,
                3000,
                10000,
                3,
                null,
                true,
                false,
                null,
                null,
                null,
                null,
                null,
                "{}");
        doReturn(taskId).when(taskCommandService).create(request);

        var response = controller.createTask(request);

        assertEquals(taskId, response.getBody().id());
        assertEquals(HttpStatus.CREATED, response.getStatusCode());
        verify(taskCommandService).create(request);
    }

    @Test
    void updatesTask() {
        UUID taskId = UUID.randomUUID();
        TaskUpdateRequest request = new TaskUpdateRequest(
                taskId,
                "Updated",
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null);

        assertEquals(HttpStatus.OK, controller.updateTask(taskId, request).getStatusCode());
        verify(taskCommandService).update(taskId, request);
    }

    @Test
    void returnsTaskPage() {
        PageRequest pageable = PageRequest.of(0, 20);
        UUID taskId = UUID.randomUUID();
        TaskCardSummary task = new TaskCardSummary(
                taskId,
                "Codex task",
                "IDLE",
                true,
                "Codex source",
                null,
                "Asia/Shanghai",
                "recent",
                24,
                "codex",
                1,
                2,
                1,
                null,
                0L,
                0L,
                0L,
                0L,
                0L,
                0L,
                0L,
                null,
                null);
        when(taskQueryService.findAll(pageable, "Codex", "IDLE", null, true))
                .thenReturn(new PageImpl<>(List.of(task), pageable, 1));

        var response = controller.listTasks(pageable, "Codex", "IDLE", null, true).getBody();

        assertEquals(List.of(task), response.content());
        assertEquals(1, response.page().totalElements());
        assertEquals(20, response.page().size());
        assertEquals(0, response.page().number());
        verify(taskQueryService).findAll(pageable, "Codex", "IDLE", null, true);
    }

    @Test
    void returnsSingleTask() {
        UUID taskId = UUID.randomUUID();
        UUID dataSourceId = UUID.randomUUID();
        TaskDetailSummary task = new TaskDetailSummary(
                taskId,
                "Codex task",
                "IDLE",
                true,
                null,
                "Asia/Shanghai",
                dataSourceId,
                "Codex source",
                "BY_PAGE",
                1,
                2,
                1,
                "recent",
                24,
                "codex",
                "FIXED",
                500,
                300,
                1500,
                10000,
                3,
                "Codex UA",
                false,
                true,
                "HTTP",
                "127.0.0.1",
                8080,
                "codex-user",
                null,
                "{}",
                null,
                Instant.parse("2026-06-03T00:00:00Z"),
                Instant.parse("2026-06-03T00:00:00Z"),
                null,
                0L,
                0L,
                0L,
                0L,
                0L,
                0L,
                0L,
                null,
                null);
        when(taskQueryService.findOne(taskId)).thenReturn(Optional.of(task));

        assertEquals(task, controller.getTask(taskId).getBody());
        verify(taskQueryService).findOne(taskId);
    }

    @Test
    void returnsNotFoundForMissingTask() {
        UUID taskId = UUID.randomUUID();
        when(taskQueryService.findOne(taskId)).thenReturn(Optional.empty());

        assertEquals(HttpStatus.NOT_FOUND, controller.getTask(taskId).getStatusCode());
    }

    @Test
    void returnsTaskRuntime() {
        UUID taskId = UUID.randomUUID();
        TaskRuntimeDetailResponse runtime = new TaskRuntimeDetailResponse(
                taskId,
                null,
                null,
                new TaskMasterRuntimeSummary(
                        "RUNNING",
                        null,
                        "Codex task",
                        false,
                        1,
                        2,
                        2,
                        1,
                        0,
                        0,
                        3,
                        1,
                        1,
                        0,
                        2,
                        3,
                        null,
                        taskId.toString(),
                        Instant.parse("2026-06-13T00:00:00Z"),
                        Instant.parse("2026-06-13T00:00:01Z"),
                        java.util.Map.of()),
                List.of(),
                0,
                100,
                0,
                0,
                false,
                true,
                Instant.parse("2026-06-13T00:00:02Z"));
        when(taskRuntimeReadService.find(taskId, 0, 100, 20)).thenReturn(Optional.of(runtime));

        assertEquals(runtime, controller.getTaskRuntime(taskId, 0, 100, 20).getBody());
        verify(taskRuntimeReadService).find(taskId, 0, 100, 20);
    }

    @Test
    void delegatesLifecycleCommands() {
        UUID taskId = UUID.randomUUID();

        assertEquals(HttpStatus.ACCEPTED, controller.startTask(taskId).getStatusCode());
        assertEquals(HttpStatus.ACCEPTED, controller.resumeTask(taskId).getStatusCode());
        assertEquals(HttpStatus.ACCEPTED, controller.pauseTask(taskId).getStatusCode());
        assertEquals(HttpStatus.ACCEPTED, controller.stopTask(taskId).getStatusCode());

        verify(taskCommandService).start(taskId, false);
        verify(taskCommandService).start(taskId, true);
        verify(taskCommandService).pause(taskId);
        verify(taskCommandService).stop(taskId);
    }

    @Test
    void deletesTaskAndLogs() {
        UUID taskId = UUID.randomUUID();

        assertEquals(HttpStatus.NO_CONTENT, controller.deleteTask(taskId).getStatusCode());
        verify(taskCommandService).delete(taskId);
        verify(taskLogService).clearLogs(taskId);
    }

    @Test
    void returnsTaskLogs() {
        UUID taskId = UUID.randomUUID();
        TaskItemLogResponse log = new TaskItemLogResponse("2026-06-05T00:00:00Z", "INFO", "codex", "ok");
        when(taskLogService.getLogs(taskId, 0, 20)).thenReturn(List.of(log));

        assertEquals(List.of(log), controller.getTaskLogs(taskId, 0, 20).getBody());
        verify(taskLogService).getLogs(taskId, 0, 20);
    }
}
