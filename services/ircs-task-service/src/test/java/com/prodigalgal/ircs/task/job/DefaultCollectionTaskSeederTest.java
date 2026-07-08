package com.prodigalgal.ircs.task.job;





import com.prodigalgal.ircs.task.infrastructure.DataSourceSeedCandidate;
import com.prodigalgal.ircs.task.infrastructure.JdbcCollectionTaskRepository;
import com.prodigalgal.ircs.task.infrastructure.TaskDistributedLockRunner;
import com.prodigalgal.ircs.task.domain.ValidatedCreateTask;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class DefaultCollectionTaskSeederTest {

    private final JdbcCollectionTaskRepository taskRepository = org.mockito.Mockito.mock(JdbcCollectionTaskRepository.class);
    private final CollectionTaskCronScheduler cronScheduler = org.mockito.Mockito.mock(CollectionTaskCronScheduler.class);

    @Test
    void createsAutoAndFullTasksForEachDataSourceWithV1Defaults() {
        UUID sourceId = UUID.randomUUID();
        when(taskRepository.findDefaultTaskSeedDataSources())
                .thenReturn(List.of(new DataSourceSeedCandidate(sourceId, "Codex Source")));
        when(taskRepository.existsTaskByName("自动采集 - Codex Source")).thenReturn(false);
        when(taskRepository.existsTaskByName("全量采集 - Codex Source")).thenReturn(false);
        DefaultCollectionTaskSeeder seeder = new DefaultCollectionTaskSeeder(
                taskRepository,
                cronScheduler,
                TaskDistributedLockRunner.local(),
                true);

        seeder.seedDefaultTasks();

        ArgumentCaptor<ValidatedCreateTask> captor = ArgumentCaptor.forClass(ValidatedCreateTask.class);
        verify(taskRepository, org.mockito.Mockito.times(2)).create(captor.capture());
        List<ValidatedCreateTask> tasks = captor.getAllValues();
        ValidatedCreateTask autoTask = tasks.get(0);
        ValidatedCreateTask fullTask = tasks.get(1);

        assertTaskDefaults(autoTask, sourceId);
        assertEquals("自动采集 - Codex Source", autoTask.name());
        assertEquals("0 0 3 * * ?", autoTask.cronExpression());
        assertEquals(48, autoTask.filterHours());

        assertTaskDefaults(fullTask, sourceId);
        assertEquals("全量采集 - Codex Source", fullTask.name());
        assertNull(fullTask.cronExpression());
        assertNull(fullTask.filterHours());
        verify(cronScheduler).refreshAll();
    }

    @Test
    void skipsExistingNamedTasksWithoutOverwritingThem() {
        UUID sourceId = UUID.randomUUID();
        when(taskRepository.findDefaultTaskSeedDataSources())
                .thenReturn(List.of(new DataSourceSeedCandidate(sourceId, "Codex Source")));
        when(taskRepository.existsTaskByName("自动采集 - Codex Source")).thenReturn(true);
        when(taskRepository.existsTaskByName("全量采集 - Codex Source")).thenReturn(false);
        DefaultCollectionTaskSeeder seeder = new DefaultCollectionTaskSeeder(
                taskRepository,
                cronScheduler,
                TaskDistributedLockRunner.local(),
                true);

        seeder.seedDefaultTasks();

        ArgumentCaptor<ValidatedCreateTask> captor = ArgumentCaptor.forClass(ValidatedCreateTask.class);
        verify(taskRepository).create(captor.capture());
        assertEquals("全量采集 - Codex Source", captor.getValue().name());
        verify(taskRepository, never()).update(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
        verify(cronScheduler).refreshAll();
    }

    @Test
    void doesNothingWhenDisabled() {
        DefaultCollectionTaskSeeder seeder = new DefaultCollectionTaskSeeder(
                taskRepository,
                cronScheduler,
                TaskDistributedLockRunner.local(),
                false);

        seeder.seedDefaultTasks();

        verifyNoInteractions(taskRepository, cronScheduler);
    }

    @Test
    void doesNotRefreshSchedulerWhenNothingWasInserted() {
        UUID sourceId = UUID.randomUUID();
        when(taskRepository.findDefaultTaskSeedDataSources())
                .thenReturn(List.of(new DataSourceSeedCandidate(sourceId, "Codex Source")));
        when(taskRepository.existsTaskByName("自动采集 - Codex Source")).thenReturn(true);
        when(taskRepository.existsTaskByName("全量采集 - Codex Source")).thenReturn(true);
        DefaultCollectionTaskSeeder seeder = new DefaultCollectionTaskSeeder(
                taskRepository,
                cronScheduler,
                TaskDistributedLockRunner.local(),
                true);

        seeder.seedDefaultTasks();

        verify(taskRepository, never()).create(org.mockito.ArgumentMatchers.any());
        verifyNoInteractions(cronScheduler);
    }

    private void assertTaskDefaults(ValidatedCreateTask task, UUID sourceId) {
        assertEquals(sourceId, task.dataSourceId());
        assertEquals("BY_PAGE", task.taskType());
        assertEquals(true, task.enabled());
        assertEquals("Asia/Shanghai", task.timeZone());
        assertEquals(1, task.startPage());
        assertEquals(0, task.endPage());
        assertNull(task.filterType());
        assertNull(task.filterKeywords());
        assertEquals("RANDOM", task.requestDelayType());
        assertEquals(0, task.fixedDelayMs());
        assertEquals(3000, task.randomDelayMinMs());
        assertEquals(8000, task.randomDelayMaxMs());
        assertEquals(15000, task.timeoutMs());
        assertEquals(3, task.maxRetries());
        assertNull(task.userAgent());
        assertEquals(true, task.enableRandomUa());
        assertEquals(false, task.useCustomProxy());
        assertNull(task.headers());
    }
}
