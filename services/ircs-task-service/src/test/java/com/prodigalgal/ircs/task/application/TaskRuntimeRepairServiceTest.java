package com.prodigalgal.ircs.task.application;





import com.prodigalgal.ircs.task.infrastructure.JdbcCollectionTaskRepository;
import com.prodigalgal.ircs.task.runtime.TaskProgressRedisService;
import com.prodigalgal.ircs.task.domain.TaskExecutionPlan;
import com.prodigalgal.ircs.task.runtime.MasterProgressState;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class TaskRuntimeRepairServiceTest {

    private final JdbcCollectionTaskRepository taskRepository = org.mockito.Mockito.mock(JdbcCollectionTaskRepository.class);
    private final TaskProgressRedisService progressService = org.mockito.Mockito.mock(TaskProgressRedisService.class);
    private final TaskQueueDispatchService dispatchService = org.mockito.Mockito.mock(TaskQueueDispatchService.class);
    private final TaskSnapshotFlushService snapshotFlushService = org.mockito.Mockito.mock(TaskSnapshotFlushService.class);
    private final TaskRuntimeRepairService service = new TaskRuntimeRepairService(
            taskRepository,
            progressService,
            dispatchService,
            snapshotFlushService);

    @Test
    void repairsCompletedRuntimeSliceWhenMorePagesExist() {
        UUID taskId = UUID.randomUUID();
        when(taskRepository.findActiveTaskIds(10)).thenReturn(List.of(taskId));
        when(taskRepository.findExecutionPlan(taskId)).thenReturn(Optional.of(plan(taskId, "RUNNING", 54)));
        when(progressService.masterProgress(taskId))
                .thenReturn(Optional.of(new MasterProgressState("COMPLETED", 1, 1, 4191, 54)));
        when(dispatchService.dispatchNextPageIfNeeded(taskId, 54, 4191, taskId.toString()))
                .thenReturn(DispatchNextPageResult.DISPATCHED);

        TaskRuntimeRepairResult result = service.repairStuckActiveMasters(10);

        assertThat(result.scanned()).isEqualTo(1);
        assertThat(result.repaired()).isEqualTo(1);
        verify(snapshotFlushService, never()).flushOne(taskId);
    }

    @Test
    void finalizesCompletedRuntimeSliceWhenNoMorePagesExist() {
        UUID taskId = UUID.randomUUID();
        when(taskRepository.findActiveTaskIds(10)).thenReturn(List.of(taskId));
        when(taskRepository.findExecutionPlan(taskId)).thenReturn(Optional.of(plan(taskId, "RUNNING", 54)));
        when(progressService.masterProgress(taskId))
                .thenReturn(Optional.of(new MasterProgressState("COMPLETED", 1, 1, 54, 54)));
        when(dispatchService.dispatchNextPageIfNeeded(taskId, 54, 54, taskId.toString()))
                .thenReturn(DispatchNextPageResult.NO_MORE_PAGES);

        TaskRuntimeRepairResult result = service.repairStuckActiveMasters(10);

        assertThat(result.finalized()).isEqualTo(1);
        verify(snapshotFlushService).flushOne(taskId);
    }

    @Test
    void skipsManualHoldTasks() {
        UUID taskId = UUID.randomUUID();
        when(taskRepository.findActiveTaskIds(10)).thenReturn(List.of(taskId));
        when(taskRepository.findExecutionPlan(taskId)).thenReturn(Optional.of(plan(taskId, "PAUSED", 54)));

        TaskRuntimeRepairResult result = service.repairStuckActiveMasters(10);

        assertThat(result.skipped()).isEqualTo(1);
        verify(dispatchService, never()).dispatchNextPageIfNeeded(
                org.mockito.Mockito.any(),
                org.mockito.Mockito.anyInt(),
                org.mockito.Mockito.any(),
                org.mockito.Mockito.any());
    }

    private TaskExecutionPlan plan(UUID taskId, String status, int currentPage) {
        return new TaskExecutionPlan(
                taskId,
                "Codex task",
                UUID.randomUUID(),
                status,
                true,
                1,
                0,
                currentPage,
                null,
                null,
                null,
                0,
                null,
                true,
                false,
                null,
                null,
                null,
                null,
                null,
                "{}");
    }
}
