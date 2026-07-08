package com.prodigalgal.ircs.notification.channel;

import com.prodigalgal.ircs.common.audit.WorkerJobAuditEvent;
import com.prodigalgal.ircs.common.audit.WorkerJobAuditWriter;
import com.prodigalgal.ircs.contracts.notification.NotificationCommand;
import com.prodigalgal.ircs.contracts.queue.QueueTopic;
import java.time.Duration;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
@Slf4j
@RequiredArgsConstructor
public class NotificationCommandConsumer {

    private static final String JOB_TYPE = "queue_consumer";
    private static final String JOB_NAME = "notification.command";

    private final NotificationCommandDispatcher dispatcher;
    private final WorkerJobAuditWriter auditWriter;

    @RabbitListener(
            queues = QueueTopic.Names.NOTIFICATION_Q_COMMAND,
            autoStartup = "${app.notification.listener.command-enabled:false}")
    public void onMessage(
            NotificationCommand command,
            @Header(name = AmqpHeaders.MESSAGE_ID, required = false) String messageId) {
        handleMessage(command, messageId);
    }

    void onMessage(NotificationCommand command) {
        handleMessage(command, null);
    }

    private void handleMessage(NotificationCommand command, String messageId) {
        Instant startedAt = Instant.now();
        String correlationId = normalizeCorrelationId(command, messageId);
        try {
            dispatcher.dispatch(command);
            auditWriter.record(WorkerJobAuditEvent.succeeded(
                    JOB_TYPE,
                    JOB_NAME,
                    correlationId,
                    Duration.between(startedAt, Instant.now())));
        } catch (Exception ex) {
            auditWriter.record(WorkerJobAuditEvent.failed(
                    JOB_TYPE,
                    JOB_NAME,
                    correlationId,
                    Duration.between(startedAt, Instant.now()),
                    ex));
            log.error("Failed to dispatch notification command {}. Retry scheduled by RabbitMQ.",
                    command == null ? null : command.getCommandId(), ex);
            throw ex;
        }
    }

    private static String normalizeCorrelationId(NotificationCommand command, String messageId) {
        if (command != null && StringUtils.hasText(command.getCorrelationId())) {
            return command.getCorrelationId().trim();
        }
        if (command != null && StringUtils.hasText(command.getCommandId())) {
            return command.getCommandId().trim();
        }
        return StringUtils.hasText(messageId) ? messageId.trim() : null;
    }
}
