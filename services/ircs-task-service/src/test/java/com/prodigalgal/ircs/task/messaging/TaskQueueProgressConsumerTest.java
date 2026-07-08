package com.prodigalgal.ircs.task.messaging;






import com.prodigalgal.ircs.task.application.TaskSnapshotFlushService;
import com.prodigalgal.ircs.task.application.TaskQueueDispatchService;
import com.prodigalgal.ircs.task.runtime.PageProgressState;
import com.prodigalgal.ircs.task.runtime.TaskProgressRedisService;
import com.prodigalgal.ircs.task.application.DispatchNextPageResult;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.rabbitmq.client.Channel;
import com.prodigalgal.ircs.common.audit.WorkerJobAuditEvent;
import com.prodigalgal.ircs.common.audit.WorkerJobAuditWriter;
import com.prodigalgal.ircs.contracts.queue.QueueTopic;
import com.prodigalgal.ircs.contracts.task.TaskDetailDoneMessage;
import com.prodigalgal.ircs.contracts.task.TaskMasterDoneMessage;
import com.prodigalgal.ircs.contracts.task.TaskPageDiscoveredMessage;
import com.prodigalgal.ircs.contracts.task.TaskPageFailedMessage;
import com.prodigalgal.ircs.messaging.RabbitTaskFailureClassifier;
import com.prodigalgal.ircs.messaging.RabbitTaskRetryPublisher;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;

class TaskQueueProgressConsumerTest {

    private static final long DELIVERY_TAG = 42L;

    private final ObjectMapper objectMapper = JsonMapper.builder().findAndAddModules().build();
    private final TaskProgressRedisService progressService = org.mockito.Mockito.mock(TaskProgressRedisService.class);
    private final TaskQueueDispatchService dispatchService = org.mockito.Mockito.mock(TaskQueueDispatchService.class);
    private final TaskSnapshotFlushService snapshotFlushService = org.mockito.Mockito.mock(TaskSnapshotFlushService.class);
    private final RabbitTaskRetryPublisher retryPublisher = org.mockito.Mockito.mock(RabbitTaskRetryPublisher.class);
    private final RabbitTaskFailureClassifier failureClassifier = new RabbitTaskFailureClassifier();
    private final WorkerJobAuditWriter auditWriter = org.mockito.Mockito.mock(WorkerJobAuditWriter.class);
    private final Channel channel = org.mockito.Mockito.mock(Channel.class);
    private final TaskQueueProgressConsumer consumer =
            new TaskQueueProgressConsumer(
                    objectMapper,
                    progressService,
                    dispatchService,
                    snapshotFlushService,
                    retryPublisher,
                    failureClassifier,
                    auditWriter,
                    3);

    @Test
    void consumesPageDiscoveredAndWritesAudit() throws Exception {
        UUID masterTaskId = UUID.randomUUID();
        UUID pageTaskId = UUID.randomUUID();
        TaskPageDiscoveredMessage message = new TaskPageDiscoveredMessage(
                masterTaskId,
                pageTaskId,
                3,
                12,
                9,
                88,
                masterTaskId.toString(),
                Instant.parse("2026-06-13T00:00:00Z"));

        consumer.consumePageDiscovered(message(objectMapper.writeValueAsString(message)), channel, DELIVERY_TAG);

        verify(progressService).recordPageDiscovered(message);
        verify(channel).basicAck(DELIVERY_TAG, false);
        assertAudit("task.page-discovered", masterTaskId.toString(), "succeeded");
    }

    @Test
    void consumesDetailDoneAndWritesAudit() throws Exception {
        UUID masterTaskId = UUID.randomUUID();
        TaskDetailDoneMessage message = new TaskDetailDoneMessage(
                masterTaskId,
                UUID.randomUUID(),
                UUID.randomUUID(),
                "v-1",
                true,
                null,
                masterTaskId.toString(),
                Instant.parse("2026-06-13T00:00:00Z"));

        consumer.consumeDetailDone(message(objectMapper.writeValueAsString(message)), channel, DELIVERY_TAG);

        verify(progressService).recordDetailDone(message);
        verify(channel).basicAck(DELIVERY_TAG, false);
        assertAudit("task.detail-done", masterTaskId.toString(), "succeeded");
    }

    @Test
    void dispatchesNextPageWhenDetailCompletionFinishesCurrentPage() throws Exception {
        UUID masterTaskId = UUID.randomUUID();
        UUID pageTaskId = UUID.randomUUID();
        TaskDetailDoneMessage message = new TaskDetailDoneMessage(
                masterTaskId,
                pageTaskId,
                UUID.randomUUID(),
                "v-1",
                true,
                null,
                masterTaskId.toString(),
                Instant.parse("2026-06-13T00:00:00Z"));
        when(progressService.pageProgress(masterTaskId, pageTaskId))
                .thenReturn(Optional.of(new PageProgressState(54, 4191, "COMPLETED")));
        when(dispatchService.dispatchNextPageIfNeeded(masterTaskId, 54, 4191, masterTaskId.toString()))
                .thenReturn(DispatchNextPageResult.DISPATCHED);

        consumer.consumeDetailDone(message(objectMapper.writeValueAsString(message)), channel, DELIVERY_TAG);

        verify(dispatchService).dispatchNextPageIfNeeded(masterTaskId, 54, 4191, masterTaskId.toString());
        verify(snapshotFlushService, never()).flushOne(masterTaskId);
        verify(channel).basicAck(DELIVERY_TAG, false);
    }

    @Test
    void flushesMasterWhenCurrentPageIsFinalPage() throws Exception {
        UUID masterTaskId = UUID.randomUUID();
        UUID pageTaskId = UUID.randomUUID();
        TaskDetailDoneMessage message = new TaskDetailDoneMessage(
                masterTaskId,
                pageTaskId,
                UUID.randomUUID(),
                "v-1",
                true,
                null,
                masterTaskId.toString(),
                Instant.parse("2026-06-13T00:00:00Z"));
        when(progressService.pageProgress(masterTaskId, pageTaskId))
                .thenReturn(Optional.of(new PageProgressState(54, 54, "COMPLETED")));
        when(dispatchService.dispatchNextPageIfNeeded(masterTaskId, 54, 54, masterTaskId.toString()))
                .thenReturn(DispatchNextPageResult.NO_MORE_PAGES);

        consumer.consumeDetailDone(message(objectMapper.writeValueAsString(message)), channel, DELIVERY_TAG);

        verify(snapshotFlushService).flushOne(masterTaskId);
        verify(channel).basicAck(DELIVERY_TAG, false);
    }

    @Test
    void consumesPageFailedAndWritesAudit() throws Exception {
        UUID masterTaskId = UUID.randomUUID();
        TaskPageFailedMessage message = new TaskPageFailedMessage(
                masterTaskId,
                UUID.randomUUID(),
                3,
                "source timeout",
                masterTaskId.toString(),
                Instant.parse("2026-06-13T00:00:00Z"));

        consumer.consumePageFailed(message(objectMapper.writeValueAsString(message)), channel, DELIVERY_TAG);

        verify(progressService).recordPageFailed(message);
        verify(channel).basicAck(DELIVERY_TAG, false);
        assertAudit("task.page-failed", masterTaskId.toString(), "succeeded");
    }

    @Test
    void consumesMasterDoneAndTriggersFinalFlush() throws Exception {
        UUID masterTaskId = UUID.randomUUID();
        TaskMasterDoneMessage message = new TaskMasterDoneMessage(
                masterTaskId,
                "COMPLETED",
                2,
                2,
                2,
                0,
                4,
                4,
                4,
                0,
                null,
                masterTaskId.toString(),
                Instant.parse("2026-06-13T00:00:00Z"));

        consumer.consumeMasterDone(message(objectMapper.writeValueAsString(message)), channel, DELIVERY_TAG);

        verify(progressService).recordMasterDone(message);
        verify(snapshotFlushService).flushOne(masterTaskId);
        verify(channel).basicAck(DELIVERY_TAG, false);
        assertAudit("task.master-done", masterTaskId.toString(), "succeeded");
    }

    @Test
    void schedulesRetryWhenProgressUpdateFailsBelowRetryLimit() throws Exception {
        UUID masterTaskId = UUID.randomUUID();
        TaskDetailDoneMessage message = new TaskDetailDoneMessage(
                masterTaskId,
                UUID.randomUUID(),
                UUID.randomUUID(),
                "v-1",
                true,
                null,
                masterTaskId.toString(),
                Instant.parse("2026-06-13T00:00:00Z"));
        org.mockito.Mockito.doThrow(new IllegalStateException("redis unavailable"))
                .when(progressService).recordDetailDone(message);
        when(retryPublisher.retryCount(any())).thenReturn(1);
        when(retryPublisher.publishRetry(eq(QueueTopic.TASK_DETAIL_DONE), any(), any())).thenReturn(2);

        consumer.consumeDetailDone(message(objectMapper.writeValueAsString(message)), channel, DELIVERY_TAG);

        verify(retryPublisher).publishRetry(eq(QueueTopic.TASK_DETAIL_DONE), any(), any());
        verify(channel).basicAck(DELIVERY_TAG, false);
        assertAudit("task.detail-done", masterTaskId.toString(), "retrying");
    }

    @Test
    void deadLettersWhenRetryLimitReached() throws Exception {
        UUID masterTaskId = UUID.randomUUID();
        TaskMasterDoneMessage message = new TaskMasterDoneMessage(
                masterTaskId,
                "COMPLETED",
                2,
                2,
                2,
                0,
                4,
                4,
                4,
                0,
                null,
                masterTaskId.toString(),
                Instant.parse("2026-06-13T00:00:00Z"));
        org.mockito.Mockito.doThrow(new IllegalStateException("db unavailable"))
                .when(snapshotFlushService).flushOne(masterTaskId);
        when(retryPublisher.retryCount(any())).thenReturn(3);

        consumer.consumeMasterDone(message(objectMapper.writeValueAsString(message)), channel, DELIVERY_TAG);

        verify(retryPublisher).publishDlq(eq(QueueTopic.TASK_MASTER_DONE), any(), any());
        verify(channel).basicAck(DELIVERY_TAG, false);
        assertAudit("task.master-done", masterTaskId.toString(), "dead-lettered");
    }

    @Test
    void fatalProgressFailureGoesDirectlyToDlq() throws Exception {
        UUID masterTaskId = UUID.randomUUID();
        TaskPageFailedMessage message = new TaskPageFailedMessage(
                masterTaskId,
                UUID.randomUUID(),
                2,
                "bad mapping config",
                masterTaskId.toString(),
                Instant.parse("2026-06-13T00:00:00Z"));
        org.mockito.Mockito.doThrow(new IllegalArgumentException("bad mapping config"))
                .when(progressService).recordPageFailed(message);

        consumer.consumePageFailed(message(objectMapper.writeValueAsString(message)), channel, DELIVERY_TAG);

        verify(retryPublisher, never()).publishRetry(eq(QueueTopic.TASK_PAGE_FAILED), any(), any());
        verify(retryPublisher).publishDlq(eq(QueueTopic.TASK_PAGE_FAILED), any(), any());
        verify(channel).basicAck(DELIVERY_TAG, false);
        assertAudit("task.page-failed", masterTaskId.toString(), "dead-lettered");
    }

    @Test
    void invalidPayloadGoesDirectlyToDlq() throws Exception {
        consumer.consumePageDiscovered(message("{broken"), channel, DELIVERY_TAG);

        verify(retryPublisher).publishDlq(eq(QueueTopic.TASK_PAGE_DISCOVERED), any(), any());
        verify(channel).basicAck(DELIVERY_TAG, false);
        assertAudit("task.page-discovered", null, "dead-lettered");
    }

    @Test
    void retryPublishFailureNacksOriginalMessageWithoutRequeue() throws Exception {
        UUID masterTaskId = UUID.randomUUID();
        TaskDetailDoneMessage message = new TaskDetailDoneMessage(
                masterTaskId,
                UUID.randomUUID(),
                UUID.randomUUID(),
                "v-1",
                true,
                null,
                masterTaskId.toString(),
                Instant.parse("2026-06-13T00:00:00Z"));
        org.mockito.Mockito.doThrow(new IllegalStateException("redis unavailable"))
                .when(progressService).recordDetailDone(message);
        when(retryPublisher.retryCount(any())).thenReturn(0);
        when(retryPublisher.publishRetry(eq(QueueTopic.TASK_DETAIL_DONE), any(), any()))
                .thenThrow(new IllegalStateException("rabbit unavailable"));

        assertThatThrownBy(() -> consumer.consumeDetailDone(message(objectMapper.writeValueAsString(message)), channel, DELIVERY_TAG))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("rabbit unavailable");

        verify(channel).basicNack(DELIVERY_TAG, false, false);
    }

    private void assertAudit(String jobName, String correlationId, String status) {
        ArgumentCaptor<WorkerJobAuditEvent> auditCaptor = ArgumentCaptor.forClass(WorkerJobAuditEvent.class);
        verify(auditWriter).record(auditCaptor.capture());
        assertThat(auditCaptor.getValue().jobType()).isEqualTo("queue-consumer");
        assertThat(auditCaptor.getValue().jobName()).isEqualTo(jobName);
        assertThat(auditCaptor.getValue().correlationId()).isEqualTo(correlationId);
        assertThat(auditCaptor.getValue().status()).isEqualTo(status);
    }

    private Message message(String payload) {
        return new Message(payload.getBytes(StandardCharsets.UTF_8), new MessageProperties());
    }
}
