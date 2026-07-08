package com.prodigalgal.ircs.task.application;




import com.prodigalgal.ircs.task.domain.TaskRuntimeState;
import com.prodigalgal.ircs.task.infrastructure.JdbcCollectionTaskRepository;
import com.prodigalgal.ircs.task.runtime.TaskProgressRedisService;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

class TaskCommandServiceTest {

    private final JdbcCollectionTaskRepository taskRepository = org.mockito.Mockito.mock(JdbcCollectionTaskRepository.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ApplicationEventPublisher eventPublisher = org.mockito.Mockito.mock(ApplicationEventPublisher.class);
    private final TaskQueueDispatchService taskQueueDispatchService = org.mockito.Mockito.mock(TaskQueueDispatchService.class);
    private final TaskProgressRedisService progressService = org.mockito.Mockito.mock(TaskProgressRedisService.class);
    private final RecordingExecutor taskQueueDispatchExecutor = new RecordingExecutor();
    private final TaskCommandService service =
            new TaskCommandService(taskRepository, objectMapper, eventPublisher, taskQueueDispatchService,
                    progressService, taskQueueDispatchExecutor);

    @Test
    void startSubmitsPostCommitDispatchAsynchronouslyAfterTaskIsQueued() {
        UUID taskId = UUID.randomUUID();
        UUID dataSourceId = UUID.randomUUID();
        TaskRuntimeState runtimeState = new TaskRuntimeState(taskId, dataSourceId, "IDLE", true, 1, 1);
        when(taskRepository.findRuntimeState(taskId)).thenReturn(Optional.of(runtimeState));
        when(taskRepository.existsActiveTaskForDataSource(dataSourceId, taskId)).thenReturn(false);

        assertThatCode(() -> service.start(taskId, false)).doesNotThrowAnyException();

        verify(taskRepository).markQueued(runtimeState, false);
        verify(taskQueueDispatchService, never()).dispatchQueuedMaster(taskId, false);
        assertThat(taskQueueDispatchExecutor.pending).isNotNull();

        taskQueueDispatchExecutor.runPending();

        verify(taskQueueDispatchService).dispatchQueuedMaster(taskId, false);
    }

    @Test
    void startDoesNotBubbleAsyncDispatchFailureAfterTaskIsQueued() {
        UUID taskId = UUID.randomUUID();
        UUID dataSourceId = UUID.randomUUID();
        TaskRuntimeState runtimeState = new TaskRuntimeState(taskId, dataSourceId, "IDLE", true, 1, 1);
        when(taskRepository.findRuntimeState(taskId)).thenReturn(Optional.of(runtimeState));
        when(taskRepository.existsActiveTaskForDataSource(dataSourceId, taskId)).thenReturn(false);
        doThrow(new IllegalStateException("rabbit unavailable"))
                .when(taskQueueDispatchService).dispatchQueuedMaster(taskId, false);

        service.start(taskId, false);

        assertThatCode(taskQueueDispatchExecutor::runPending).doesNotThrowAnyException();
        verify(taskRepository).markQueued(runtimeState, false);
        verify(taskQueueDispatchService).dispatchQueuedMaster(taskId, false);
    }

    @Test
    void pauseMarksRuntimeMasterAsHeldAfterDbStatusChange() {
        UUID taskId = UUID.randomUUID();

        service.pause(taskId);

        verify(taskRepository).pause(taskId);
        verify(progressService).holdMaster(
                org.mockito.Mockito.eq(taskId),
                org.mockito.Mockito.eq("PAUSED"),
                org.mockito.Mockito.anyString(),
                org.mockito.Mockito.any());
    }

    private static final class RecordingExecutor implements Executor {
        private Runnable pending;

        @Override
        public void execute(Runnable command) {
            this.pending = command;
        }

        void runPending() {
            assertThat(pending).isNotNull();
            pending.run();
        }
    }
}
