package com.prodigalgal.ircs.task.job;



import com.prodigalgal.ircs.task.infrastructure.JdbcCollectionTaskRepository;
import com.prodigalgal.ircs.task.infrastructure.TaskDistributedLockRunner;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.prodigalgal.ircs.common.audit.WorkerJobAuditEvent;
import com.prodigalgal.ircs.common.audit.WorkerJobAuditWriter;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class TaskStartupRecoveryTest {

    private final JdbcCollectionTaskRepository taskRepository = org.mockito.Mockito.mock(JdbcCollectionTaskRepository.class);
    private final WorkerJobAuditWriter auditWriter = org.mockito.Mockito.mock(WorkerJobAuditWriter.class);
    private final TaskStartupRecovery recovery = new TaskStartupRecovery(
            taskRepository,
            auditWriter,
            TaskDistributedLockRunner.local());

    @Test
    void writesBatchAuditEvidenceWhenInterruptedTasksAreRecovered() {
        when(taskRepository.recoverInterruptedTasks(
                "Detected task-service restart; task was moved to PAUSED for manual review."))
                .thenReturn(3);

        recovery.run(null);

        WorkerJobAuditEvent event = captureAuditEvent();
        assertEquals(TaskStartupRecovery.JOB_TYPE_TASK_STARTUP_RECOVERY, event.jobType());
        assertEquals(TaskStartupRecovery.JOB_NAME_RECOVER_INTERRUPTED_TASKS, event.jobName());
        assertEquals(TaskStartupRecovery.CORRELATION_RECOVER_INTERRUPTED_TASKS, event.correlationId());
        assertEquals("succeeded", event.status());
        assertNotNull(event.duration());
    }

    @Test
    void skipsAuditWhenNoInterruptedTasksAreRecovered() {
        when(taskRepository.recoverInterruptedTasks(
                "Detected task-service restart; task was moved to PAUSED for manual review."))
                .thenReturn(0);

        recovery.run(null);

        verifyNoInteractions(auditWriter);
    }

    private WorkerJobAuditEvent captureAuditEvent() {
        ArgumentCaptor<WorkerJobAuditEvent> captor = ArgumentCaptor.forClass(WorkerJobAuditEvent.class);
        verify(auditWriter).record(captor.capture());
        return captor.getValue();
    }
}
