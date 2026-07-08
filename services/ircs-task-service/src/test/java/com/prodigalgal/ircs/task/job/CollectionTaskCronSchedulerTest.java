package com.prodigalgal.ircs.task.job;





import com.prodigalgal.ircs.task.domain.ScheduledTaskDefinition;
import com.prodigalgal.ircs.task.infrastructure.JdbcCollectionTaskRepository;
import com.prodigalgal.ircs.task.domain.TaskConfigurationChangedEvent;
import com.prodigalgal.ircs.task.application.TaskCommandService;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.ScheduledFuture;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.Trigger;

class CollectionTaskCronSchedulerTest {

    private final JdbcCollectionTaskRepository taskRepository = org.mockito.Mockito.mock(JdbcCollectionTaskRepository.class);
    private final TaskCommandService taskCommandService = org.mockito.Mockito.mock(TaskCommandService.class);
    private final TaskScheduler scheduler = org.mockito.Mockito.mock(TaskScheduler.class);
    private final CollectionTaskCronScheduler cronScheduler =
            new CollectionTaskCronScheduler(taskRepository, taskCommandService, scheduler, true);

    @Test
    void schedulesEnabledCronTasksAndCancelsDeletedTask() {
        UUID taskId = UUID.randomUUID();
        @SuppressWarnings("rawtypes")
        ScheduledFuture future = org.mockito.Mockito.mock(ScheduledFuture.class);
        when(taskRepository.findScheduledTasks()).thenReturn(List.of(new ScheduledTaskDefinition(
                taskId,
                "Codex cron",
                true,
                "0 0/5 * * * *",
                "UTC")));
        org.mockito.Mockito.doReturn(future)
                .when(scheduler)
                .schedule(any(Runnable.class), any(Trigger.class));

        cronScheduler.refreshAll();

        assertEquals(1, cronScheduler.scheduledCount());
        ArgumentCaptor<Runnable> runnableCaptor = ArgumentCaptor.forClass(Runnable.class);
        verify(scheduler).schedule(runnableCaptor.capture(), any(Trigger.class));
        runnableCaptor.getValue().run();
        verify(taskCommandService, timeout(1000)).startInternal(taskId, false);

        cronScheduler.onTaskConfigurationChanged(new TaskConfigurationChangedEvent(taskId, true));

        verify(future).cancel(false);
        assertEquals(0, cronScheduler.scheduledCount());
    }

    @Test
    void skipsSchedulingWhenSchedulerGateIsDisabled() {
        CollectionTaskCronScheduler disabledScheduler =
                new CollectionTaskCronScheduler(taskRepository, taskCommandService, scheduler, false);

        disabledScheduler.refreshAll();

        assertEquals(0, disabledScheduler.scheduledCount());
        org.mockito.Mockito.verifyNoInteractions(taskRepository, scheduler, taskCommandService);
    }
}
