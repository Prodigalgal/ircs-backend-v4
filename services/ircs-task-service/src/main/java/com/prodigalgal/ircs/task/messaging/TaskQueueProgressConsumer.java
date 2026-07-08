package com.prodigalgal.ircs.task.messaging;





import com.prodigalgal.ircs.task.application.TaskSnapshotFlushService;
import com.prodigalgal.ircs.task.application.TaskQueueDispatchService;
import com.prodigalgal.ircs.task.runtime.TaskProgressRedisService;
import com.prodigalgal.ircs.task.application.DispatchNextPageResult;
import com.rabbitmq.client.Channel;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.prodigalgal.ircs.common.audit.WorkerJobAuditEvent;
import com.prodigalgal.ircs.common.audit.WorkerJobAuditWriter;
import com.prodigalgal.ircs.contracts.queue.QueueTopic;
import com.prodigalgal.ircs.contracts.task.TaskDetailDoneMessage;
import com.prodigalgal.ircs.contracts.task.TaskMasterDoneMessage;
import com.prodigalgal.ircs.contracts.task.TaskPageDiscoveredMessage;
import com.prodigalgal.ircs.contracts.task.TaskPageFailedMessage;
import com.prodigalgal.ircs.messaging.RabbitTaskFailureClassifier;
import com.prodigalgal.ircs.messaging.RabbitTaskRetryPublisher;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

@Component
@Slf4j
class TaskQueueProgressConsumer {

    private static final String JOB_TYPE_QUEUE_CONSUMER = "queue-consumer";
    private static final String JOB_NAME_PAGE_DISCOVERED = "task.page-discovered";
    private static final String JOB_NAME_PAGE_FAILED = "task.page-failed";
    private static final String JOB_NAME_DETAIL_DONE = "task.detail-done";
    private static final String JOB_NAME_MASTER_DONE = "task.master-done";

    private final ObjectMapper objectMapper;
    private final TaskProgressRedisService progressService;
    private final TaskQueueDispatchService dispatchService;
    private final TaskSnapshotFlushService snapshotFlushService;
    private final RabbitTaskRetryPublisher retryPublisher;
    private final RabbitTaskFailureClassifier failureClassifier;
    private final WorkerJobAuditWriter auditWriter;
    private final int maxRetries;

    TaskQueueProgressConsumer(
            ObjectMapper objectMapper,
            TaskProgressRedisService progressService,
            TaskQueueDispatchService dispatchService,
            TaskSnapshotFlushService snapshotFlushService,
            RabbitTaskRetryPublisher retryPublisher,
            RabbitTaskFailureClassifier failureClassifier,
            WorkerJobAuditWriter auditWriter,
            @Value("${app.task.queue.retry.max-retries:3}") int maxRetries) {
        this.objectMapper = objectMapper;
        this.progressService = progressService;
        this.dispatchService = dispatchService;
        this.snapshotFlushService = snapshotFlushService;
        this.retryPublisher = retryPublisher;
        this.failureClassifier = failureClassifier;
        this.auditWriter = auditWriter;
        this.maxRetries = Math.max(0, maxRetries);
    }

    @RabbitListener(
            queues = QueueTopic.Names.TASK_Q_PAGE_DISCOVERED,
            autoStartup = "${app.task.queue.listener-enabled:false}",
            ackMode = "MANUAL")
    void consumePageDiscovered(
            Message rawMessage,
            Channel channel,
            @Header(AmqpHeaders.DELIVERY_TAG) long deliveryTag) {
        withManualAck(channel, deliveryTag, () -> handlePageDiscovered(rawMessage));
    }

    private void handlePageDiscovered(Message rawMessage) {
        Instant startedAt = Instant.now();
        TaskPageDiscoveredMessage message;
        try {
            message = read(rawMessage, TaskPageDiscoveredMessage.class);
        } catch (RuntimeException ex) {
            deadLetter(QueueTopic.TASK_PAGE_DISCOVERED, rawMessage, JOB_NAME_PAGE_DISCOVERED, null, startedAt, ex);
            return;
        }
        try {
            progressService.recordPageDiscovered(message);
            if (message.detailScheduled() <= 0) {
                DispatchNextPageResult result = dispatchService.dispatchNextPageIfNeeded(
                        message.masterTaskId(),
                        message.pageNumber(),
                        message.totalPages(),
                        message.correlationId());
                if (result == DispatchNextPageResult.NO_MORE_PAGES) {
                    snapshotFlushService.flushOne(message.masterTaskId());
                }
            }
            recordSucceeded(JOB_NAME_PAGE_DISCOVERED, message.correlationId(), startedAt);
        } catch (RuntimeException ex) {
            retryOrDeadLetter(QueueTopic.TASK_PAGE_DISCOVERED, rawMessage, JOB_NAME_PAGE_DISCOVERED,
                    message.correlationId(), startedAt, ex);
        }
    }

    @RabbitListener(
            queues = QueueTopic.Names.TASK_Q_PAGE_FAILED,
            autoStartup = "${app.task.queue.listener-enabled:false}",
            ackMode = "MANUAL")
    void consumePageFailed(
            Message rawMessage,
            Channel channel,
            @Header(AmqpHeaders.DELIVERY_TAG) long deliveryTag) {
        withManualAck(channel, deliveryTag, () -> handlePageFailed(rawMessage));
    }

    private void handlePageFailed(Message rawMessage) {
        Instant startedAt = Instant.now();
        TaskPageFailedMessage message;
        try {
            message = read(rawMessage, TaskPageFailedMessage.class);
        } catch (RuntimeException ex) {
            deadLetter(QueueTopic.TASK_PAGE_FAILED, rawMessage, JOB_NAME_PAGE_FAILED, null, startedAt, ex);
            return;
        }
        try {
            progressService.recordPageFailed(message);
            recordSucceeded(JOB_NAME_PAGE_FAILED, message.correlationId(), startedAt);
        } catch (RuntimeException ex) {
            retryOrDeadLetter(QueueTopic.TASK_PAGE_FAILED, rawMessage, JOB_NAME_PAGE_FAILED,
                    message.correlationId(), startedAt, ex);
        }
    }

    @RabbitListener(
            queues = QueueTopic.Names.TASK_Q_DETAIL_DONE,
            autoStartup = "${app.task.queue.listener-enabled:false}",
            ackMode = "MANUAL")
    void consumeDetailDone(
            Message rawMessage,
            Channel channel,
            @Header(AmqpHeaders.DELIVERY_TAG) long deliveryTag) {
        withManualAck(channel, deliveryTag, () -> handleDetailDone(rawMessage));
    }

    private void handleDetailDone(Message rawMessage) {
        Instant startedAt = Instant.now();
        TaskDetailDoneMessage message;
        try {
            message = read(rawMessage, TaskDetailDoneMessage.class);
        } catch (RuntimeException ex) {
            deadLetter(QueueTopic.TASK_DETAIL_DONE, rawMessage, JOB_NAME_DETAIL_DONE, null, startedAt, ex);
            return;
        }
        try {
            progressService.recordDetailDone(message);
            progressService.pageProgress(message.masterTaskId(), message.pageTaskId())
                    .ifPresent(page -> {
                        DispatchNextPageResult result = dispatchService.dispatchNextPageIfNeeded(
                                message.masterTaskId(),
                                page.pageNumber(),
                                page.totalPages(),
                                message.correlationId());
                        if (result == DispatchNextPageResult.NO_MORE_PAGES) {
                            snapshotFlushService.flushOne(message.masterTaskId());
                        }
                    });
            recordSucceeded(JOB_NAME_DETAIL_DONE, message.correlationId(), startedAt);
        } catch (RuntimeException ex) {
            retryOrDeadLetter(QueueTopic.TASK_DETAIL_DONE, rawMessage, JOB_NAME_DETAIL_DONE,
                    message.correlationId(), startedAt, ex);
        }
    }

    @RabbitListener(
            queues = QueueTopic.Names.TASK_Q_MASTER_DONE,
            autoStartup = "${app.task.queue.listener-enabled:false}",
            ackMode = "MANUAL")
    void consumeMasterDone(
            Message rawMessage,
            Channel channel,
            @Header(AmqpHeaders.DELIVERY_TAG) long deliveryTag) {
        withManualAck(channel, deliveryTag, () -> handleMasterDone(rawMessage));
    }

    private void handleMasterDone(Message rawMessage) {
        Instant startedAt = Instant.now();
        TaskMasterDoneMessage message;
        try {
            message = read(rawMessage, TaskMasterDoneMessage.class);
        } catch (RuntimeException ex) {
            deadLetter(QueueTopic.TASK_MASTER_DONE, rawMessage, JOB_NAME_MASTER_DONE, null, startedAt, ex);
            return;
        }
        try {
            progressService.recordMasterDone(message);
            snapshotFlushService.flushOne(message.masterTaskId());
            recordSucceeded(JOB_NAME_MASTER_DONE, message.correlationId(), startedAt);
        } catch (RuntimeException ex) {
            retryOrDeadLetter(QueueTopic.TASK_MASTER_DONE, rawMessage, JOB_NAME_MASTER_DONE,
                    message.correlationId(), startedAt, ex);
        }
    }

    private void withManualAck(Channel channel, long deliveryTag, Runnable handler) {
        try {
            handler.run();
            acknowledge(channel, deliveryTag);
        } catch (RuntimeException ex) {
            reject(channel, deliveryTag, ex);
        }
    }

    private void acknowledge(Channel channel, long deliveryTag) {
        try {
            channel.basicAck(deliveryTag, false);
        } catch (IOException ex) {
            throw new IllegalStateException("Rabbit manual ack failed", ex);
        }
    }

    private void reject(Channel channel, long deliveryTag, RuntimeException error) {
        try {
            channel.basicNack(deliveryTag, false, false);
        } catch (IOException ex) {
            error.addSuppressed(ex);
        }
        throw error;
    }

    private <T> T read(Message rawMessage, Class<T> type) {
        try {
            return objectMapper.readValue(new String(rawMessage.getBody(), StandardCharsets.UTF_8), type);
        } catch (Exception ex) {
            throw new IllegalArgumentException("Invalid task progress payload: " + type.getSimpleName(), ex);
        }
    }

    private void retryOrDeadLetter(
            QueueTopic topic,
            Message rawMessage,
            String jobName,
            String correlationId,
            Instant startedAt,
            RuntimeException error) {
        if (!failureClassifier.retryable(error)) {
            deadLetter(topic, rawMessage, jobName, correlationId, startedAt, error);
            return;
        }
        if (retryPublisher.retryCount(rawMessage) < maxRetries) {
            int retryCount = retryPublisher.publishRetry(topic, rawMessage, error);
            recordRetried(jobName, correlationId, retryCount, startedAt, error);
            return;
        }
        deadLetter(topic, rawMessage, jobName, correlationId, startedAt, error);
    }

    private void deadLetter(
            QueueTopic topic,
            Message rawMessage,
            String jobName,
            String correlationId,
            Instant startedAt,
            RuntimeException error) {
        retryPublisher.publishDlq(topic, rawMessage, error);
        recordDeadLettered(jobName, correlationId, startedAt, error);
    }

    private void recordSucceeded(String jobName, String correlationId, Instant startedAt) {
        recordAudit(WorkerJobAuditEvent.succeeded(
                JOB_TYPE_QUEUE_CONSUMER,
                jobName,
                correlationId,
                elapsedSince(startedAt)));
    }

    private void recordRetried(String jobName, String correlationId, int retryCount, Instant startedAt, RuntimeException error) {
        recordAudit(new WorkerJobAuditEvent(
                JOB_TYPE_QUEUE_CONSUMER,
                jobName,
                correlationId,
                "retrying",
                elapsedSince(startedAt),
                new TaskQueueProgressException("retry scheduled " + retryCount + "/" + maxRetries, error)));
    }

    private void recordDeadLettered(String jobName, String correlationId, Instant startedAt, RuntimeException error) {
        recordAudit(new WorkerJobAuditEvent(
                JOB_TYPE_QUEUE_CONSUMER,
                jobName,
                correlationId,
                "dead-lettered",
                elapsedSince(startedAt),
                error));
    }

    private void recordAudit(WorkerJobAuditEvent event) {
        try {
            auditWriter.record(event);
        } catch (RuntimeException ex) {
            log.warn("Task queue progress audit write failed: {}", ex.getMessage());
        }
    }

    private Duration elapsedSince(Instant startedAt) {
        return Duration.between(startedAt, Instant.now());
    }

    private static class TaskQueueProgressException extends RuntimeException {
        TaskQueueProgressException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
