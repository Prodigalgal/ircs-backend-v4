package com.prodigalgal.ircs.task.job;



import com.prodigalgal.ircs.task.infrastructure.JdbcCollectionTaskRepository;
import com.prodigalgal.ircs.task.infrastructure.TaskDistributedLockRunner;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.prodigalgal.ircs.common.audit.WorkerJobAuditEvent;
import com.prodigalgal.ircs.common.audit.WorkerJobAuditWriter;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class TaskWatchdogTest {

    private final JdbcCollectionTaskRepository taskRepository = org.mockito.Mockito.mock(JdbcCollectionTaskRepository.class);
    private final WorkerJobAuditWriter auditWriter = org.mockito.Mockito.mock(WorkerJobAuditWriter.class);
    private final TaskWatchdog watchdog = new TaskWatchdog(
            taskRepository,
            auditWriter,
            TaskDistributedLockRunner.local());

    @Test
    void writesBatchAuditEvidenceWhenStaleActiveTasksAreFailed() {
        when(taskRepository.failStaleActiveTasks(any(Instant.class), eq("Watchdog: Task heartbeat timeout.")))
                .thenReturn(2);

        watchdog.failStaleActiveTasksOnce();

        WorkerJobAuditEvent event = captureAuditEvent();
        assertEquals(TaskWatchdog.JOB_TYPE_TASK_WATCHDOG, event.jobType());
        assertEquals(TaskWatchdog.JOB_NAME_FAIL_STALE_ACTIVE_TASKS, event.jobName());
        assertEquals(TaskWatchdog.CORRELATION_FAIL_STALE_ACTIVE_TASKS, event.correlationId());
        assertEquals("succeeded", event.status());
        assertNotNull(event.duration());
    }

    @Test
    void skipsAuditWhenNoStaleActiveTasksAreFailed() {
        when(taskRepository.failStaleActiveTasks(any(Instant.class), eq("Watchdog: Task heartbeat timeout.")))
                .thenReturn(0);

        watchdog.failStaleActiveTasksOnce();

        verifyNoInteractions(auditWriter);
    }

    @Test
    void watchdogBeanIsNotCreatedWhenGateIsMissing() {
        watchdogContextRunner().run(context -> assertThat(context).doesNotHaveBean(TaskWatchdog.class));
    }

    @Test
    void watchdogBeanIsCreatedOnlyWhenGateIsEnabled() {
        watchdogContextRunner()
                .withPropertyValues("app.task.watchdog.enabled=true")
                .run(context -> assertThat(context).hasSingleBean(TaskWatchdog.class));
    }

    private WorkerJobAuditEvent captureAuditEvent() {
        ArgumentCaptor<WorkerJobAuditEvent> captor = ArgumentCaptor.forClass(WorkerJobAuditEvent.class);
        verify(auditWriter).record(captor.capture());
        return captor.getValue();
    }

    private ApplicationContextRunner watchdogContextRunner() {
        return new ApplicationContextRunner()
                .withBean(JdbcCollectionTaskRepository.class, () -> taskRepository)
                .withBean(WorkerJobAuditWriter.class, () -> auditWriter)
                .withBean(TaskDistributedLockRunner.class, TaskDistributedLockRunner::local)
                .withUserConfiguration(TaskWatchdog.class);
    }
}
