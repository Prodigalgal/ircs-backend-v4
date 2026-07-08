package com.prodigalgal.ircs.scraper;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.Channel;
import com.prodigalgal.ircs.common.audit.WorkerJobAuditEvent;
import com.prodigalgal.ircs.common.audit.WorkerJobAuditWriter;
import com.prodigalgal.ircs.contracts.queue.QueueTopic;
import com.prodigalgal.ircs.contracts.task.TaskDetailDoneMessage;
import com.prodigalgal.ircs.contracts.task.TaskDetailMessage;
import com.prodigalgal.ircs.contracts.task.TaskPageFailedMessage;
import com.prodigalgal.ircs.contracts.task.TaskPageMessage;
import com.prodigalgal.ircs.messaging.RabbitTaskFailureClassifier;
import com.prodigalgal.ircs.messaging.RabbitTaskHttpStatusException;
import com.prodigalgal.ircs.messaging.RabbitTaskRetryPublisher;
import com.prodigalgal.ircs.messaging.TaskSourceTerminalException;
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
class TaskQueueConsumer {

    private static final String JOB_TYPE_QUEUE_CONSUMER = "queue-consumer";
    private static final String JOB_NAME_PAGE = "scraper.task-page";
    private static final String JOB_NAME_DETAIL = "scraper.task-detail";

    private final ObjectMapper objectMapper;
    private final TaskQueueScraperService scraperService;
    private final ScraperTaskQueuePublisher queuePublisher;
    private final RabbitTaskRetryPublisher retryPublisher;
    private final RabbitTaskFailureClassifier failureClassifier;
    private final WorkerJobAuditWriter auditWriter;
    private final int maxRetries;

    TaskQueueConsumer(
            ObjectMapper objectMapper,
            TaskQueueScraperService scraperService,
            ScraperTaskQueuePublisher queuePublisher,
            RabbitTaskRetryPublisher retryPublisher,
            RabbitTaskFailureClassifier failureClassifier,
            WorkerJobAuditWriter auditWriter,
            @Value("${app.scraper.task-queue.retry.max-retries:3}") int maxRetries) {
        this.objectMapper = objectMapper;
        this.scraperService = scraperService;
        this.queuePublisher = queuePublisher;
        this.retryPublisher = retryPublisher;
        this.failureClassifier = failureClassifier;
        this.auditWriter = auditWriter;
        this.maxRetries = Math.max(0, maxRetries);
    }

    @RabbitListener(
            queues = QueueTopic.Names.TASK_Q_PAGE,
            autoStartup = "${app.scraper.task-queue.listener-enabled:false}",
            ackMode = "MANUAL")
    void consumePage(
            Message rawMessage,
            Channel channel,
            @Header(AmqpHeaders.DELIVERY_TAG) long deliveryTag) {
        withManualAck(channel, deliveryTag, () -> handlePage(rawMessage));
    }

    private void handlePage(Message rawMessage) {
        Instant startedAt = Instant.now();
        TaskPageMessage message;
        try {
            message = read(rawMessage, TaskPageMessage.class);
        } catch (RuntimeException ex) {
            deadLetter(QueueTopic.TASK_PAGE, rawMessage, JOB_NAME_PAGE, null, startedAt, ex);
            return;
        }
        try {
            scraperService.processPage(message);
        } catch (RuntimeException ex) {
            retryOrDeadLetter(
                    QueueTopic.TASK_PAGE,
                    rawMessage,
                    JOB_NAME_PAGE,
                    message.correlationId(),
                    startedAt,
                    ex,
                    () -> publishPageFailed(message, ex));
        }
    }

    @RabbitListener(
            queues = QueueTopic.Names.TASK_Q_DETAIL,
            autoStartup = "${app.scraper.task-queue.listener-enabled:false}",
            ackMode = "MANUAL")
    void consumeDetail(
            Message rawMessage,
            Channel channel,
            @Header(AmqpHeaders.DELIVERY_TAG) long deliveryTag) {
        withManualAck(channel, deliveryTag, () -> handleDetail(rawMessage));
    }

    private void handleDetail(Message rawMessage) {
        Instant startedAt = Instant.now();
        TaskDetailMessage message;
        try {
            message = read(rawMessage, TaskDetailMessage.class);
        } catch (RuntimeException ex) {
            deadLetter(QueueTopic.TASK_DETAIL, rawMessage, JOB_NAME_DETAIL, null, startedAt, ex);
            return;
        }
        try {
            scraperService.processDetail(message);
        } catch (RuntimeException ex) {
            retryOrDeadLetter(
                    QueueTopic.TASK_DETAIL,
                    rawMessage,
                    JOB_NAME_DETAIL,
                    message.correlationId(),
                    startedAt,
                    ex,
                    () -> publishDetailFailed(message, ex));
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
            throw new IllegalArgumentException("Invalid task queue payload: " + type.getSimpleName(), ex);
        }
    }

    private void retryOrDeadLetter(
            QueueTopic topic,
            Message rawMessage,
            String jobName,
            String correlationId,
            Instant startedAt,
            RuntimeException error) {
        retryOrDeadLetter(topic, rawMessage, jobName, correlationId, startedAt, error, null);
    }

    private void retryOrDeadLetter(
            QueueTopic topic,
            Message rawMessage,
            String jobName,
            String correlationId,
            Instant startedAt,
            RuntimeException error,
            Runnable beforeDeadLetter) {
        if (!failureClassifier.retryable(error)) {
            if (isTerminalSourceFailure(error)) {
                terminalFailure(jobName, correlationId, startedAt, error, beforeDeadLetter);
                return;
            }
            deadLetter(topic, rawMessage, jobName, correlationId, startedAt, error, beforeDeadLetter);
            return;
        }
        if (retryPublisher.retryCount(rawMessage) < maxRetries) {
            int retryCount = retryPublisher.publishRetry(topic, rawMessage, error);
            recordRetried(jobName, correlationId, retryCount, startedAt, error);
            return;
        }
        if (isTerminalSourceFailure(error)) {
            terminalFailure(jobName, correlationId, startedAt, error, beforeDeadLetter);
            return;
        }
        deadLetter(topic, rawMessage, jobName, correlationId, startedAt, error, beforeDeadLetter);
    }

    private void deadLetter(
            QueueTopic topic,
            Message rawMessage,
            String jobName,
            String correlationId,
            Instant startedAt,
            RuntimeException error) {
        deadLetter(topic, rawMessage, jobName, correlationId, startedAt, error, null);
    }

    private void deadLetter(
            QueueTopic topic,
            Message rawMessage,
            String jobName,
            String correlationId,
            Instant startedAt,
            RuntimeException error,
            Runnable beforeDeadLetter) {
        if (beforeDeadLetter != null) {
            beforeDeadLetter.run();
        }
        retryPublisher.publishDlq(topic, rawMessage, error);
        recordDeadLettered(jobName, correlationId, startedAt, error);
    }

    private void terminalFailure(
            String jobName,
            String correlationId,
            Instant startedAt,
            RuntimeException error,
            Runnable beforeTerminalFailure) {
        if (beforeTerminalFailure != null) {
            beforeTerminalFailure.run();
        }
        recordTerminalFailed(jobName, correlationId, startedAt, error);
    }

    private boolean isTerminalSourceFailure(RuntimeException error) {
        Throwable current = error;
        while (current != null) {
            if (current instanceof RabbitTaskHttpStatusException
                    || current instanceof TaskSourceTerminalException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private void publishPageFailed(TaskPageMessage message, RuntimeException error) {
        queuePublisher.publishPageFailed(new TaskPageFailedMessage(
                message.masterTaskId(),
                message.pageTaskId(),
                message.pageNumber(),
                safeError(error),
                message.correlationId(),
                Instant.now()));
    }

    private void publishDetailFailed(TaskDetailMessage message, RuntimeException error) {
        queuePublisher.publishDetailDone(new TaskDetailDoneMessage(
                message.masterTaskId(),
                message.pageTaskId(),
                message.detailTaskId(),
                message.sourceVid(),
                false,
                safeError(error),
                message.correlationId(),
                Instant.now()));
    }

    private void recordRetried(String jobName, String correlationId, int retryCount, Instant startedAt, RuntimeException error) {
        recordAudit(new WorkerJobAuditEvent(
                JOB_TYPE_QUEUE_CONSUMER,
                jobName,
                correlationId,
                "retrying",
                elapsedSince(startedAt),
                new TaskQueueConsumerException("retry scheduled " + retryCount + "/" + maxRetries, error)));
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

    private void recordTerminalFailed(String jobName, String correlationId, Instant startedAt, RuntimeException error) {
        recordAudit(new WorkerJobAuditEvent(
                JOB_TYPE_QUEUE_CONSUMER,
                jobName,
                correlationId,
                "failed",
                elapsedSince(startedAt),
                error));
    }

    private void recordAudit(WorkerJobAuditEvent event) {
        try {
            auditWriter.record(event);
        } catch (RuntimeException ex) {
            log.warn("Task queue consumer audit write failed: {}", ex.getMessage());
        }
    }

    private Duration elapsedSince(Instant startedAt) {
        return Duration.between(startedAt, Instant.now());
    }

    private String safeError(RuntimeException error) {
        if (error == null || error.getMessage() == null || error.getMessage().isBlank()) {
            return "task queue processing failed";
        }
        String normalized = error.getMessage().replaceAll("\\s+", " ").trim();
        return normalized.length() <= 500 ? normalized : normalized.substring(0, 500);
    }

    private static class TaskQueueConsumerException extends RuntimeException {
        TaskQueueConsumerException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
