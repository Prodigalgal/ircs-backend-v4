package com.prodigalgal.ircs.task.job;






import com.prodigalgal.ircs.task.application.TaskRuntimeRepairResult;
import com.prodigalgal.ircs.task.application.TaskSnapshotFlushService;
import com.prodigalgal.ircs.task.infrastructure.TaskDistributedLockRunner;
import com.prodigalgal.ircs.task.application.TaskSnapshotFlushResult;
import com.prodigalgal.ircs.task.application.TaskRuntimeRepairService;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.prodigalgal.ircs.common.audit.WorkerJobAuditEvent;
import com.prodigalgal.ircs.common.audit.WorkerJobAuditWriter;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.convert.ApplicationConversionService;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class TaskSnapshotFlushSchedulerTest {

    private final TaskSnapshotFlushService flushService = org.mockito.Mockito.mock(TaskSnapshotFlushService.class);
    private final TaskRuntimeRepairService repairService = org.mockito.Mockito.mock(TaskRuntimeRepairService.class);
    private final WorkerJobAuditWriter auditWriter = org.mockito.Mockito.mock(WorkerJobAuditWriter.class);
    private final TaskSnapshotFlushScheduler scheduler = new TaskSnapshotFlushScheduler(
            flushService,
            repairService,
            auditWriter,
            TaskDistributedLockRunner.local(),
            Duration.ZERO,
            10,
            10);

    @Test
    void writesAuditWhenDirtyMastersAreFlushed() {
        when(flushService.flushDirtyMasters(org.mockito.Mockito.any(), org.mockito.Mockito.eq(10)))
                .thenReturn(new TaskSnapshotFlushResult(2, 2, 0));
        when(repairService.repairStuckActiveMasters(10))
                .thenReturn(new TaskRuntimeRepairResult(0, 0, 0, 0, 0));

        scheduler.flushDirtyMastersOnce();

        WorkerJobAuditEvent event = captureAuditEvent();
        assertEquals(TaskSnapshotFlushScheduler.JOB_TYPE_TASK_SNAPSHOT_FLUSH, event.jobType());
        assertEquals(TaskSnapshotFlushScheduler.JOB_NAME_FLUSH_DIRTY_MASTERS, event.jobName());
        assertEquals(TaskSnapshotFlushScheduler.CORRELATION_FLUSH_DIRTY_MASTERS, event.correlationId());
        assertEquals("succeeded", event.status());
        assertNotNull(event.duration());
    }

    @Test
    void skipsAuditWhenNothingIsDirty() {
        when(flushService.flushDirtyMasters(org.mockito.Mockito.any(), org.mockito.Mockito.eq(10)))
                .thenReturn(new TaskSnapshotFlushResult(0, 0, 0));
        when(repairService.repairStuckActiveMasters(10))
                .thenReturn(new TaskRuntimeRepairResult(0, 0, 0, 0, 0));

        scheduler.flushDirtyMastersOnce();

        verifyNoInteractions(auditWriter);
    }

    @Test
    void schedulerBeanIsNotCreatedWhenGateIsMissing() {
        schedulerContextRunner().run(context -> assertThat(context).doesNotHaveBean(TaskSnapshotFlushScheduler.class));
    }

    @Test
    void schedulerBeanIsCreatedOnlyWhenGateIsEnabled() {
        schedulerContextRunner()
                .withPropertyValues("app.task.snapshot.flush.enabled=true")
                .run(context -> assertThat(context).hasSingleBean(TaskSnapshotFlushScheduler.class));
    }

    private WorkerJobAuditEvent captureAuditEvent() {
        ArgumentCaptor<WorkerJobAuditEvent> captor = ArgumentCaptor.forClass(WorkerJobAuditEvent.class);
        verify(auditWriter).record(captor.capture());
        return captor.getValue();
    }

    private ApplicationContextRunner schedulerContextRunner() {
        return new ApplicationContextRunner()
                .withInitializer(context -> context.getBeanFactory()
                        .setConversionService(ApplicationConversionService.getSharedInstance()))
                .withBean(TaskSnapshotFlushService.class, () -> flushService)
                .withBean(TaskRuntimeRepairService.class, () -> repairService)
                .withBean(WorkerJobAuditWriter.class, () -> auditWriter)
                .withBean(TaskDistributedLockRunner.class, TaskDistributedLockRunner::local)
                .withUserConfiguration(TaskSnapshotFlushScheduler.class);
    }
}
