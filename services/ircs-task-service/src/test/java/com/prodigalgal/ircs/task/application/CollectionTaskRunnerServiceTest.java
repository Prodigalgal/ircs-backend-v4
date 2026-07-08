package com.prodigalgal.ircs.task.application;







import com.prodigalgal.ircs.task.infrastructure.ScraperTaskExecutionLog;
import com.prodigalgal.ircs.task.infrastructure.JdbcCollectionTaskRepository;
import com.prodigalgal.ircs.task.infrastructure.ScraperTaskExecutionResult;
import com.prodigalgal.ircs.task.domain.TaskExecutionPlan;
import com.prodigalgal.ircs.task.infrastructure.ScraperTaskExecutionClient;
import com.prodigalgal.ircs.task.dto.TaskItemLogResponse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.prodigalgal.ircs.common.audit.WorkerJobAuditEvent;
import com.prodigalgal.ircs.common.audit.WorkerJobAuditWriter;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.RejectedExecutionException;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class CollectionTaskRunnerServiceTest {

    private final JdbcCollectionTaskRepository taskRepository = org.mockito.Mockito.mock(JdbcCollectionTaskRepository.class);
    private final TaskLogService taskLogService = org.mockito.Mockito.mock(TaskLogService.class);
    private final ScraperTaskExecutionClient scraperClient = org.mockito.Mockito.mock(ScraperTaskExecutionClient.class);
    private final WorkerJobAuditWriter auditWriter = org.mockito.Mockito.mock(WorkerJobAuditWriter.class);
    private final CollectionTaskRunnerService runnerService =
            new CollectionTaskRunnerService(taskRepository, taskLogService, scraperClient, Runnable::run, auditWriter);

    @Test
    void completesTaskWhenScraperExecutionSucceedsAndWritesSucceededAuditEvidence() {
        UUID taskId = UUID.randomUUID();
        TaskExecutionPlan plan = plan(taskId, "QUEUED", true);
        when(taskRepository.findExecutionPlan(taskId)).thenReturn(Optional.of(plan));
        when(taskRepository.markRunning(taskId)).thenReturn(true);
        when(taskRepository.statusOf(taskId)).thenReturn("RUNNING");
        when(scraperClient.execute(plan, false)).thenReturn(new ScraperTaskExecutionResult(
                "COMPLETED",
                1,
                0,
                List.of(new ScraperTaskExecutionLog("2026-06-06T00:00:00Z", "INFO", "codex-vid", "published"))));

        runnerService.submit(taskId, false);

        verify(taskRepository).markRunning(taskId);
        verify(taskRepository).complete(taskId, 1, 1, 0);
        verify(taskLogService).appendLog(taskId,
                new TaskItemLogResponse("2026-06-06T00:00:00Z", "INFO", "codex-vid", "published"));

        WorkerJobAuditEvent event = captureAuditEvent();
        assertEquals(CollectionTaskRunnerService.JOB_TYPE_COLLECTION_TASK_RUNNER, event.jobType());
        assertEquals(CollectionTaskRunnerService.JOB_NAME_COLLECTION_TASK_RUNNER, event.jobName());
        assertEquals(taskId.toString(), event.correlationId());
        assertEquals("succeeded", event.status());
        assertNotNull(event.duration());
        assertEquals(null, event.error());
    }

    @Test
    void failsTaskWhenScraperExecutionFailsAndWritesFailedAuditEvidence() {
        UUID taskId = UUID.randomUUID();
        TaskExecutionPlan plan = plan(taskId, "QUEUED", true);
        when(taskRepository.findExecutionPlan(taskId)).thenReturn(Optional.of(plan));
        when(taskRepository.markRunning(taskId)).thenReturn(true);
        when(taskRepository.statusOf(taskId)).thenReturn("RUNNING");
        when(scraperClient.execute(plan, false)).thenReturn(new ScraperTaskExecutionResult(
                "FAILED",
                0,
                1,
                List.of()));

        runnerService.submit(taskId, false);

        verify(taskRepository).fail(taskId, "Task execution failed: published=0, failed=1");

        WorkerJobAuditEvent event = captureAuditEvent();
        assertEquals(CollectionTaskRunnerService.JOB_TYPE_COLLECTION_TASK_RUNNER, event.jobType());
        assertEquals(CollectionTaskRunnerService.JOB_NAME_COLLECTION_TASK_RUNNER, event.jobName());
        assertEquals(taskId.toString(), event.correlationId());
        assertEquals("failed", event.status());
        assertNotNull(event.duration());
        assertNotNull(event.error());
        assertEquals("runner result failed: published=0, failed=1", event.error().getMessage());
    }

    @Test
    void skipsTaskWhenStatusIsNoLongerQueuedAndWritesStableAuditEvidenceWithoutScraperCall() {
        UUID taskId = UUID.randomUUID();
        when(taskRepository.findExecutionPlan(taskId)).thenReturn(Optional.of(plan(taskId, "PAUSED", true)));

        runnerService.submit(taskId, false);

        verifyNoInteractions(scraperClient);

        WorkerJobAuditEvent event = captureAuditEvent();
        assertEquals(CollectionTaskRunnerService.JOB_TYPE_COLLECTION_TASK_RUNNER, event.jobType());
        assertEquals(CollectionTaskRunnerService.JOB_NAME_COLLECTION_TASK_RUNNER, event.jobName());
        assertEquals(taskId.toString(), event.correlationId());
        assertEquals("skipped", event.status());
        assertNotNull(event.duration());
        assertNotNull(event.error());
        assertEquals("skipped: current status is PAUSED", event.error().getMessage());
    }

    @Test
    void refusesTaskWhenMarkRunningLosesRaceAndDoesNotCallScraper() {
        UUID taskId = UUID.randomUUID();
        TaskExecutionPlan plan = plan(taskId, "QUEUED", true);
        when(taskRepository.findExecutionPlan(taskId)).thenReturn(Optional.of(plan));
        when(taskRepository.markRunning(taskId)).thenReturn(false);

        runnerService.submit(taskId, false);

        verifyNoInteractions(scraperClient);

        WorkerJobAuditEvent event = captureAuditEvent();
        assertEquals(CollectionTaskRunnerService.JOB_TYPE_COLLECTION_TASK_RUNNER, event.jobType());
        assertEquals(CollectionTaskRunnerService.JOB_NAME_COLLECTION_TASK_RUNNER, event.jobName());
        assertEquals(taskId.toString(), event.correlationId());
        assertEquals("refused", event.status());
        assertNotNull(event.duration());
        assertNotNull(event.error());
        assertEquals("refused: status changed before runner picked task", event.error().getMessage());
        verifyNoMoreInteractions(auditWriter);
    }

    @Test
    void marksTaskFailedWhenExecutorIsUnavailableForSubmission() {
        UUID taskId = UUID.randomUUID();
        CollectionTaskRunnerService rejectingRunnerService = new CollectionTaskRunnerService(
                taskRepository,
                taskLogService,
                scraperClient,
                command -> {
                    throw new RejectedExecutionException("executor is shut down");
                },
                auditWriter);

        rejectingRunnerService.submit(taskId, false);

        verify(taskRepository).fail(taskId, "Task runner rejected by executor: executor is shut down");
        verify(taskLogService).appendLog(org.mockito.ArgumentMatchers.eq(taskId), org.mockito.ArgumentMatchers.argThat(log ->
                "ERROR".equals(log.level())
                        && "SYSTEM".equals(log.sourceVid())
                        && log.message().contains("executor is shut down")));
        verifyNoInteractions(scraperClient);

        WorkerJobAuditEvent event = captureAuditEvent();
        assertEquals(CollectionTaskRunnerService.JOB_TYPE_COLLECTION_TASK_RUNNER, event.jobType());
        assertEquals(CollectionTaskRunnerService.JOB_NAME_COLLECTION_TASK_RUNNER, event.jobName());
        assertEquals(taskId.toString(), event.correlationId());
        assertEquals("refused", event.status());
        assertNotNull(event.error());
        assertEquals("refused: executor rejected task", event.error().getMessage());
    }

    private TaskExecutionPlan plan(UUID taskId, String status, boolean enabled) {
        return new TaskExecutionPlan(
                taskId,
                "Codex task",
                UUID.randomUUID(),
                status,
                enabled,
                1,
                1,
                1,
                null,
                null,
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

    private WorkerJobAuditEvent captureAuditEvent() {
        ArgumentCaptor<WorkerJobAuditEvent> captor = ArgumentCaptor.forClass(WorkerJobAuditEvent.class);
        verify(auditWriter).record(captor.capture());
        return captor.getValue();
    }
}
