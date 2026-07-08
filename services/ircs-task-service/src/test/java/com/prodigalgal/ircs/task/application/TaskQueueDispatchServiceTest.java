package com.prodigalgal.ircs.task.application;





import com.prodigalgal.ircs.task.messaging.TaskQueuePublisher;
import com.prodigalgal.ircs.task.infrastructure.JdbcCollectionTaskRepository;
import com.prodigalgal.ircs.task.runtime.TaskProgressRedisService;
import com.prodigalgal.ircs.task.domain.TaskExecutionPlan;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.prodigalgal.ircs.contracts.task.TaskMasterSnapshot;
import com.prodigalgal.ircs.contracts.task.TaskPageMessage;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class TaskQueueDispatchServiceTest {

    private final JdbcCollectionTaskRepository taskRepository = org.mockito.Mockito.mock(JdbcCollectionTaskRepository.class);
    private final TaskMasterSnapshotService snapshotService = org.mockito.Mockito.mock(TaskMasterSnapshotService.class);
    private final TaskProgressRedisService progressService = org.mockito.Mockito.mock(TaskProgressRedisService.class);
    private final TaskQueuePublisher queuePublisher = org.mockito.Mockito.mock(TaskQueuePublisher.class);
    private final TaskLogService taskLogService = org.mockito.Mockito.mock(TaskLogService.class);
    private final ObjectMapper objectMapper = JsonMapper.builder().findAndAddModules().build();

    @Test
    void dispatchesQueuedMasterIntoPageTasksAndRedisSnapshot() {
        UUID taskId = UUID.randomUUID();
        TaskExecutionPlan plan = plan(taskId, 1, 3, 1);
        when(taskRepository.findExecutionPlan(taskId)).thenReturn(Optional.of(plan));
        when(progressService.trySchedulePage(
                org.mockito.Mockito.eq(taskId),
                org.mockito.Mockito.any(UUID.class),
                org.mockito.Mockito.eq(1),
                org.mockito.Mockito.any())).thenReturn(1L);
        TaskQueueDispatchService service = service(true);

        service.dispatchQueuedMaster(taskId, false);

        ArgumentCaptor<TaskMasterSnapshot> snapshotCaptor = ArgumentCaptor.forClass(TaskMasterSnapshot.class);
        verify(snapshotService).put(snapshotCaptor.capture());
        TaskMasterSnapshot snapshot = snapshotCaptor.getValue();
        assertThat(snapshot.masterTaskId()).isEqualTo(taskId);
        assertThat(snapshot.status()).isEqualTo("QUEUED");
        assertThat(snapshot.pageScheduled()).isZero();
        assertThat(snapshot.startPage()).isEqualTo(1);
        assertThat(snapshot.endPage()).isEqualTo(3);

        ArgumentCaptor<TaskPageMessage> pageCaptor = ArgumentCaptor.forClass(TaskPageMessage.class);
        verify(queuePublisher).publishPage(pageCaptor.capture());
        assertThat(pageCaptor.getAllValues())
                .extracting(TaskPageMessage::pageNumber)
                .containsExactly(1);
        assertThat(pageCaptor.getAllValues())
                .allSatisfy(message -> {
                    assertThat(message.masterTaskId()).isEqualTo(taskId);
                    assertThat(message.correlationId()).isEqualTo(taskId.toString());
                    assertThat(message.attempt()).isZero();
                    assertThat(message.options().keyword()).isEqualTo("codex");
                    assertThat(message.options().filterType()).isEqualTo("movie");
                    assertThat(message.options().filterHours()).isEqualTo(48);
                });
    }

    @Test
    void dispatchesNextPageAfterCompletedPage() {
        UUID taskId = UUID.randomUUID();
        when(taskRepository.findExecutionPlan(taskId)).thenReturn(Optional.of(plan(taskId, 5, 20, 5)));
        when(progressService.trySchedulePage(
                org.mockito.Mockito.eq(taskId),
                org.mockito.Mockito.any(UUID.class),
                org.mockito.Mockito.eq(6),
                org.mockito.Mockito.any())).thenReturn(6L);
        TaskQueueDispatchService service = service(true);

        service.dispatchNextPageIfNeeded(taskId, 5, null, "existing-correlation");

        ArgumentCaptor<TaskPageMessage> pageCaptor = ArgumentCaptor.forClass(TaskPageMessage.class);
        verify(queuePublisher).publishPage(pageCaptor.capture());
        assertThat(pageCaptor.getAllValues())
                .extracting(TaskPageMessage::pageNumber)
                .containsExactly(6);
        assertThat(pageCaptor.getValue().resume()).isFalse();
        assertThat(pageCaptor.getValue().correlationId()).isEqualTo("existing-correlation");
    }

    @Test
    void skipsNextPageWhenMasterIsPaused() {
        UUID taskId = UUID.randomUUID();
        when(taskRepository.findExecutionPlan(taskId)).thenReturn(Optional.of(plan(taskId, 5, 20, 5, "PAUSED")));
        TaskQueueDispatchService service = service(true);

        service.dispatchNextPageIfNeeded(taskId, 5, null, "existing-correlation");

        verify(queuePublisher, never()).publishPage(org.mockito.Mockito.any());
        verify(progressService, never()).trySchedulePage(
                org.mockito.Mockito.any(),
                org.mockito.Mockito.any(),
                org.mockito.Mockito.anyInt(),
                org.mockito.Mockito.any());
    }

    @Test
    void fullTaskDispatchesOnlyCurrentPageWhenEndPageIsOpenEnded() {
        UUID taskId = UUID.randomUUID();
        when(taskRepository.findExecutionPlan(taskId)).thenReturn(Optional.of(plan(taskId, 1, 0, 1)));
        when(progressService.trySchedulePage(
                org.mockito.Mockito.eq(taskId),
                org.mockito.Mockito.any(UUID.class),
                org.mockito.Mockito.eq(1),
                org.mockito.Mockito.any())).thenReturn(1L);
        TaskQueueDispatchService service = service(true);

        service.dispatchQueuedMaster(taskId, false);

        ArgumentCaptor<TaskMasterSnapshot> snapshotCaptor = ArgumentCaptor.forClass(TaskMasterSnapshot.class);
        verify(snapshotService).put(snapshotCaptor.capture());
        assertThat(snapshotCaptor.getValue().pageScheduled()).isZero();
        assertThat(snapshotCaptor.getValue().endPage()).isNull();

        ArgumentCaptor<TaskPageMessage> pageCaptor = ArgumentCaptor.forClass(TaskPageMessage.class);
        verify(queuePublisher).publishPage(pageCaptor.capture());
        assertThat(pageCaptor.getAllValues())
                .extracting(TaskPageMessage::pageNumber)
                .containsExactly(1);
    }

    @Test
    void rollsBackScheduledPageWhenRabbitPublishFails() {
        UUID taskId = UUID.randomUUID();
        when(taskRepository.findExecutionPlan(taskId)).thenReturn(Optional.of(plan(taskId, 1, 3, 1)));
        when(progressService.trySchedulePage(
                org.mockito.Mockito.eq(taskId),
                org.mockito.Mockito.any(UUID.class),
                org.mockito.Mockito.eq(1),
                org.mockito.Mockito.any())).thenReturn(1L);
        org.mockito.Mockito.doThrow(new IllegalStateException("rabbit down"))
                .when(queuePublisher)
                .publishPage(org.mockito.Mockito.any());
        TaskQueueDispatchService service = service(true);

        assertThatThrownBy(() -> service.dispatchQueuedMaster(taskId, false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("rabbit down");

        verify(progressService).rollbackScheduledPage(
                org.mockito.Mockito.eq(taskId),
                org.mockito.Mockito.any(UUID.class),
                org.mockito.Mockito.eq(1),
                org.mockito.Mockito.any());
        verify(taskRepository).fail(
                org.mockito.Mockito.eq(taskId),
                org.mockito.Mockito.contains("Task queue dispatch failed"));
    }

    @Test
    void disabledQueueFailsMasterWithoutLocalRunnerFallback() {
        UUID taskId = UUID.randomUUID();
        TaskQueueDispatchService service = service(false);

        service.dispatchQueuedMaster(taskId, false);

        verify(taskRepository).fail(
                org.mockito.Mockito.eq(taskId),
                org.mockito.Mockito.contains("no local runner fallback"));
        verify(queuePublisher, never()).publishPage(org.mockito.Mockito.any());
        verify(snapshotService, never()).put(org.mockito.Mockito.any());
    }

    private TaskQueueDispatchService service(boolean enabled) {
        when(progressService.allowsDispatch(org.mockito.Mockito.any())).thenReturn(true);
        return new TaskQueueDispatchService(
                taskRepository,
                snapshotService,
                progressService,
                queuePublisher,
                taskLogService,
                objectMapper,
                enabled,
                0);
    }

    private TaskExecutionPlan plan(UUID taskId, Integer startPage, Integer endPage, Integer currentPage) {
        return plan(taskId, startPage, endPage, currentPage, "QUEUED");
    }

    private TaskExecutionPlan plan(
            UUID taskId,
            Integer startPage,
            Integer endPage,
            Integer currentPage,
            String status) {
        return new TaskExecutionPlan(
                taskId,
                "Codex task",
                UUID.randomUUID(),
                status,
                true,
                startPage,
                endPage,
                currentPage,
                "movie",
                48,
                "codex",
                0,
                null,
                false,
                false,
                null,
                null,
                null,
                null,
                null,
                "{}");
    }
}
