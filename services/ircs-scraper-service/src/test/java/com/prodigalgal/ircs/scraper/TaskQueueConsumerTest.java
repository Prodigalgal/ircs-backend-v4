package com.prodigalgal.ircs.scraper;

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
import com.prodigalgal.ircs.contracts.task.TaskDetailMessage;
import com.prodigalgal.ircs.contracts.task.TaskPageFailedMessage;
import com.prodigalgal.ircs.contracts.task.TaskPageMessage;
import com.prodigalgal.ircs.contracts.task.TaskScrapeOptions;
import com.prodigalgal.ircs.messaging.RabbitTaskFailureClassifier;
import com.prodigalgal.ircs.messaging.RabbitTaskHttpStatusException;
import com.prodigalgal.ircs.messaging.RabbitTaskRetryPublisher;
import com.prodigalgal.ircs.messaging.TaskSourceTerminalException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;

class TaskQueueConsumerTest {

    private static final long DELIVERY_TAG = 42L;

    private final ObjectMapper objectMapper = JsonMapper.builder().findAndAddModules().build();
    private final TaskQueueScraperService scraperService = org.mockito.Mockito.mock(TaskQueueScraperService.class);
    private final ScraperTaskQueuePublisher queuePublisher = org.mockito.Mockito.mock(ScraperTaskQueuePublisher.class);
    private final RabbitTaskRetryPublisher retryPublisher = org.mockito.Mockito.mock(RabbitTaskRetryPublisher.class);
    private final RabbitTaskFailureClassifier failureClassifier = new RabbitTaskFailureClassifier();
    private final WorkerJobAuditWriter auditWriter = org.mockito.Mockito.mock(WorkerJobAuditWriter.class);
    private final Channel channel = org.mockito.Mockito.mock(Channel.class);
    private final TaskQueueConsumer consumer =
            new TaskQueueConsumer(
                    objectMapper,
                    scraperService,
                    queuePublisher,
                    retryPublisher,
                    failureClassifier,
                    auditWriter,
                    3);

    @Test
    void pageFailureSchedulesRetryBelowRetryLimit() throws Exception {
        TaskPageMessage pageMessage = pageMessage();
        org.mockito.Mockito.doThrow(new IllegalStateException("source timeout"))
                .when(scraperService).processPage(pageMessage);
        when(retryPublisher.retryCount(any())).thenReturn(0);
        when(retryPublisher.publishRetry(eq(QueueTopic.TASK_PAGE), any(), any())).thenReturn(1);

        consumer.consumePage(message(objectMapper.writeValueAsString(pageMessage)), channel, DELIVERY_TAG);

        verify(retryPublisher).publishRetry(eq(QueueTopic.TASK_PAGE), any(), any());
        verify(channel).basicAck(DELIVERY_TAG, false);
        assertAudit("scraper.task-page", pageMessage.correlationId(), "retrying");
    }

    @Test
    void httpRateLimitSchedulesRetryWithoutTerminalProgress() throws Exception {
        TaskPageMessage pageMessage = pageMessage();
        org.mockito.Mockito.doThrow(new RabbitTaskHttpStatusException(
                        429,
                        "Fake",
                        "https://provider.example.test/api.php"))
                .when(scraperService).processPage(pageMessage);
        when(retryPublisher.retryCount(any())).thenReturn(0);
        when(retryPublisher.publishRetry(eq(QueueTopic.TASK_PAGE), any(), any())).thenReturn(1);

        consumer.consumePage(message(objectMapper.writeValueAsString(pageMessage)), channel, DELIVERY_TAG);

        verify(retryPublisher).publishRetry(eq(QueueTopic.TASK_PAGE), any(), any());
        verify(queuePublisher, never()).publishPageFailed(any());
        verify(channel).basicAck(DELIVERY_TAG, false);
        assertAudit("scraper.task-page", pageMessage.correlationId(), "retrying");
    }

    @Test
    void retryableSourceHttpFailurePublishesPageFailedWithoutDlqAfterRetryLimit() throws Exception {
        TaskPageMessage pageMessage = pageMessage();
        org.mockito.Mockito.doThrow(new RabbitTaskHttpStatusException(
                        502,
                        "Fake",
                        "https://provider.example.test/api.php"))
                .when(scraperService).processPage(pageMessage);
        when(retryPublisher.retryCount(any())).thenReturn(3);

        consumer.consumePage(message(objectMapper.writeValueAsString(pageMessage)), channel, DELIVERY_TAG);

        ArgumentCaptor<TaskPageFailedMessage> failedCaptor = ArgumentCaptor.forClass(TaskPageFailedMessage.class);
        verify(queuePublisher).publishPageFailed(failedCaptor.capture());
        assertThat(failedCaptor.getValue().masterTaskId()).isEqualTo(pageMessage.masterTaskId());
        assertThat(failedCaptor.getValue().pageTaskId()).isEqualTo(pageMessage.pageTaskId());
        assertThat(failedCaptor.getValue().pageNumber()).isEqualTo(pageMessage.pageNumber());
        verify(retryPublisher, never()).publishRetry(eq(QueueTopic.TASK_PAGE), any(), any());
        verify(retryPublisher, never()).publishDlq(eq(QueueTopic.TASK_PAGE), any(), any());
        verify(channel).basicAck(DELIVERY_TAG, false);
        assertAudit("scraper.task-page", pageMessage.correlationId(), "failed");
    }

    @Test
    void pageFailurePublishesPageFailedBeforeDlqAfterRetryLimit() throws Exception {
        TaskPageMessage pageMessage = pageMessage();
        org.mockito.Mockito.doThrow(new IllegalStateException("source timeout"))
                .when(scraperService).processPage(pageMessage);
        when(retryPublisher.retryCount(any())).thenReturn(3);

        consumer.consumePage(message(objectMapper.writeValueAsString(pageMessage)), channel, DELIVERY_TAG);

        ArgumentCaptor<TaskPageFailedMessage> failedCaptor = ArgumentCaptor.forClass(TaskPageFailedMessage.class);
        verify(queuePublisher).publishPageFailed(failedCaptor.capture());
        assertThat(failedCaptor.getValue().masterTaskId()).isEqualTo(pageMessage.masterTaskId());
        assertThat(failedCaptor.getValue().pageTaskId()).isEqualTo(pageMessage.pageTaskId());
        assertThat(failedCaptor.getValue().pageNumber()).isEqualTo(pageMessage.pageNumber());
        verify(retryPublisher).publishDlq(eq(QueueTopic.TASK_PAGE), any(), any());
        verify(channel).basicAck(DELIVERY_TAG, false);
        assertAudit("scraper.task-page", pageMessage.correlationId(), "dead-lettered");
    }

    @Test
    void terminalSourceHttpPageFailurePublishesPageFailedWithoutDlq() throws Exception {
        TaskPageMessage pageMessage = pageMessage();
        org.mockito.Mockito.doThrow(new RabbitTaskHttpStatusException(
                        404,
                        "Fake",
                        "https://provider.example.test/api.php"))
                .when(scraperService).processPage(pageMessage);

        consumer.consumePage(message(objectMapper.writeValueAsString(pageMessage)), channel, DELIVERY_TAG);

        ArgumentCaptor<TaskPageFailedMessage> failedCaptor = ArgumentCaptor.forClass(TaskPageFailedMessage.class);
        verify(queuePublisher).publishPageFailed(failedCaptor.capture());
        assertThat(failedCaptor.getValue().masterTaskId()).isEqualTo(pageMessage.masterTaskId());
        assertThat(failedCaptor.getValue().pageTaskId()).isEqualTo(pageMessage.pageTaskId());
        assertThat(failedCaptor.getValue().pageNumber()).isEqualTo(pageMessage.pageNumber());
        verify(retryPublisher, never()).publishRetry(eq(QueueTopic.TASK_PAGE), any(), any());
        verify(retryPublisher, never()).publishDlq(eq(QueueTopic.TASK_PAGE), any(), any());
        verify(channel).basicAck(DELIVERY_TAG, false);
        assertAudit("scraper.task-page", pageMessage.correlationId(), "failed");
    }

    @Test
    void terminalSourceHttpDetailFailurePublishesDetailDoneWithoutDlq() throws Exception {
        TaskDetailMessage detailMessage = detailMessage();
        org.mockito.Mockito.doThrow(new RabbitTaskHttpStatusException(
                        403,
                        "Fake",
                        "https://provider.example.test/api.php"))
                .when(scraperService).processDetail(detailMessage);

        consumer.consumeDetail(message(objectMapper.writeValueAsString(detailMessage)), channel, DELIVERY_TAG);

        ArgumentCaptor<TaskDetailDoneMessage> doneCaptor = ArgumentCaptor.forClass(TaskDetailDoneMessage.class);
        verify(queuePublisher).publishDetailDone(doneCaptor.capture());
        assertThat(doneCaptor.getValue().masterTaskId()).isEqualTo(detailMessage.masterTaskId());
        assertThat(doneCaptor.getValue().pageTaskId()).isEqualTo(detailMessage.pageTaskId());
        assertThat(doneCaptor.getValue().detailTaskId()).isEqualTo(detailMessage.detailTaskId());
        assertThat(doneCaptor.getValue().successful()).isFalse();
        verify(retryPublisher, never()).publishRetry(eq(QueueTopic.TASK_DETAIL), any(), any());
        verify(retryPublisher, never()).publishDlq(eq(QueueTopic.TASK_DETAIL), any(), any());
        verify(channel).basicAck(DELIVERY_TAG, false);
        assertAudit("scraper.task-detail", detailMessage.correlationId(), "failed");
    }

    @Test
    void terminalSourceParsePageFailurePublishesPageFailedWithoutDlq() throws Exception {
        TaskPageMessage pageMessage = pageMessage();
        org.mockito.Mockito.doThrow(new TaskSourceTerminalException(
                        "Fake",
                        "invalid JSON list response",
                        new RuntimeException("html")))
                .when(scraperService).processPage(pageMessage);

        consumer.consumePage(message(objectMapper.writeValueAsString(pageMessage)), channel, DELIVERY_TAG);

        ArgumentCaptor<TaskPageFailedMessage> failedCaptor = ArgumentCaptor.forClass(TaskPageFailedMessage.class);
        verify(queuePublisher).publishPageFailed(failedCaptor.capture());
        assertThat(failedCaptor.getValue().masterTaskId()).isEqualTo(pageMessage.masterTaskId());
        assertThat(failedCaptor.getValue().pageTaskId()).isEqualTo(pageMessage.pageTaskId());
        assertThat(failedCaptor.getValue().pageNumber()).isEqualTo(pageMessage.pageNumber());
        verify(retryPublisher, never()).publishRetry(eq(QueueTopic.TASK_PAGE), any(), any());
        verify(retryPublisher, never()).publishDlq(eq(QueueTopic.TASK_PAGE), any(), any());
        verify(channel).basicAck(DELIVERY_TAG, false);
        assertAudit("scraper.task-page", pageMessage.correlationId(), "failed");
    }

    @Test
    void detailFailureDeadLettersAfterRetryLimit() throws Exception {
        TaskDetailMessage detailMessage = detailMessage();
        org.mockito.Mockito.doThrow(new IllegalStateException("mapping failed"))
                .when(scraperService).processDetail(detailMessage);
        when(retryPublisher.retryCount(any())).thenReturn(3);

        consumer.consumeDetail(message(objectMapper.writeValueAsString(detailMessage)), channel, DELIVERY_TAG);

        ArgumentCaptor<TaskDetailDoneMessage> doneCaptor = ArgumentCaptor.forClass(TaskDetailDoneMessage.class);
        verify(queuePublisher).publishDetailDone(doneCaptor.capture());
        assertThat(doneCaptor.getValue().masterTaskId()).isEqualTo(detailMessage.masterTaskId());
        assertThat(doneCaptor.getValue().pageTaskId()).isEqualTo(detailMessage.pageTaskId());
        assertThat(doneCaptor.getValue().detailTaskId()).isEqualTo(detailMessage.detailTaskId());
        assertThat(doneCaptor.getValue().successful()).isFalse();
        verify(retryPublisher).publishDlq(eq(QueueTopic.TASK_DETAIL), any(), any());
        verify(channel).basicAck(DELIVERY_TAG, false);
        assertAudit("scraper.task-detail", detailMessage.correlationId(), "dead-lettered");
    }

    @Test
    void fatalPageFailurePublishesPageFailedAndGoesDirectlyToDlq() throws Exception {
        TaskPageMessage pageMessage = pageMessage();
        org.mockito.Mockito.doThrow(new IllegalArgumentException("unsupported source mapping"))
                .when(scraperService).processPage(pageMessage);

        consumer.consumePage(message(objectMapper.writeValueAsString(pageMessage)), channel, DELIVERY_TAG);

        ArgumentCaptor<TaskPageFailedMessage> failedCaptor = ArgumentCaptor.forClass(TaskPageFailedMessage.class);
        verify(queuePublisher).publishPageFailed(failedCaptor.capture());
        assertThat(failedCaptor.getValue().masterTaskId()).isEqualTo(pageMessage.masterTaskId());
        assertThat(failedCaptor.getValue().pageTaskId()).isEqualTo(pageMessage.pageTaskId());
        assertThat(failedCaptor.getValue().pageNumber()).isEqualTo(pageMessage.pageNumber());
        verify(retryPublisher, never()).publishRetry(eq(QueueTopic.TASK_PAGE), any(), any());
        verify(retryPublisher).publishDlq(eq(QueueTopic.TASK_PAGE), any(), any());
        verify(channel).basicAck(DELIVERY_TAG, false);
        assertAudit("scraper.task-page", pageMessage.correlationId(), "dead-lettered");
    }

    @Test
    void fatalDetailFailurePublishesDetailDoneAndGoesDirectlyToDlq() throws Exception {
        TaskDetailMessage detailMessage = detailMessage();
        org.mockito.Mockito.doThrow(new IllegalArgumentException("bad detail mapping"))
                .when(scraperService).processDetail(detailMessage);

        consumer.consumeDetail(message(objectMapper.writeValueAsString(detailMessage)), channel, DELIVERY_TAG);

        ArgumentCaptor<TaskDetailDoneMessage> doneCaptor = ArgumentCaptor.forClass(TaskDetailDoneMessage.class);
        verify(queuePublisher).publishDetailDone(doneCaptor.capture());
        assertThat(doneCaptor.getValue().masterTaskId()).isEqualTo(detailMessage.masterTaskId());
        assertThat(doneCaptor.getValue().pageTaskId()).isEqualTo(detailMessage.pageTaskId());
        assertThat(doneCaptor.getValue().detailTaskId()).isEqualTo(detailMessage.detailTaskId());
        assertThat(doneCaptor.getValue().successful()).isFalse();
        verify(retryPublisher, never()).publishRetry(eq(QueueTopic.TASK_DETAIL), any(), any());
        verify(retryPublisher).publishDlq(eq(QueueTopic.TASK_DETAIL), any(), any());
        verify(channel).basicAck(DELIVERY_TAG, false);
        assertAudit("scraper.task-detail", detailMessage.correlationId(), "dead-lettered");
    }

    @Test
    void invalidPayloadGoesDirectlyToDlq() throws Exception {
        consumer.consumeDetail(message("{broken"), channel, DELIVERY_TAG);

        verify(retryPublisher).publishDlq(eq(QueueTopic.TASK_DETAIL), any(), any());
        verify(channel).basicAck(DELIVERY_TAG, false);
        assertAudit("scraper.task-detail", null, "dead-lettered");
    }

    @Test
    void retryPublishFailureNacksOriginalMessageWithoutRequeue() throws Exception {
        TaskPageMessage pageMessage = pageMessage();
        org.mockito.Mockito.doThrow(new IllegalStateException("source timeout"))
                .when(scraperService).processPage(pageMessage);
        when(retryPublisher.retryCount(any())).thenReturn(0);
        when(retryPublisher.publishRetry(eq(QueueTopic.TASK_PAGE), any(), any()))
                .thenThrow(new IllegalStateException("rabbit unavailable"));

        assertThatThrownBy(() -> consumer.consumePage(message(objectMapper.writeValueAsString(pageMessage)), channel, DELIVERY_TAG))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("rabbit unavailable");

        verify(channel).basicNack(DELIVERY_TAG, false, false);
    }

    private TaskPageMessage pageMessage() {
        UUID masterTaskId = UUID.randomUUID();
        return new TaskPageMessage(
                masterTaskId,
                UUID.randomUUID(),
                UUID.randomUUID(),
                1,
                false,
                0,
                options(),
                masterTaskId.toString(),
                Instant.parse("2026-06-13T00:00:00Z"));
    }

    private TaskDetailMessage detailMessage() {
        UUID masterTaskId = UUID.randomUUID();
        return new TaskDetailMessage(
                masterTaskId,
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                "v-1",
                null,
                0,
                "idempotent",
                options(),
                masterTaskId.toString(),
                Instant.parse("2026-06-13T00:00:00Z"));
    }

    private TaskScrapeOptions options() {
        return new TaskScrapeOptions(
                "codex",
                null,
                null,
                null,
                true,
                false,
                null,
                null,
                null,
                null,
                null,
                "{}",
                0,
                false);
    }

    private Message message(String payload) {
        return new Message(payload.getBytes(StandardCharsets.UTF_8), new MessageProperties());
    }

    private void assertAudit(String jobName, String correlationId, String status) {
        ArgumentCaptor<WorkerJobAuditEvent> auditCaptor = ArgumentCaptor.forClass(WorkerJobAuditEvent.class);
        verify(auditWriter).record(auditCaptor.capture());
        assertThat(auditCaptor.getValue().jobType()).isEqualTo("queue-consumer");
        assertThat(auditCaptor.getValue().jobName()).isEqualTo(jobName);
        assertThat(auditCaptor.getValue().correlationId()).isEqualTo(correlationId);
        assertThat(auditCaptor.getValue().status()).isEqualTo(status);
    }
}
