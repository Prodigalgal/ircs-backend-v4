package com.prodigalgal.ircs.notification.mail;

import com.prodigalgal.ircs.common.audit.WorkerJobAuditEvent;
import com.prodigalgal.ircs.common.audit.WorkerJobAuditWriter;
import com.prodigalgal.ircs.contracts.notification.MailMessageDTO;
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
public class MailConsumer {

    private static final String JOB_TYPE = "queue_consumer";
    private static final String JOB_NAME = "notification.mail";

    private final NotificationMailService mailService;
    private final WorkerJobAuditWriter auditWriter;

    @RabbitListener(
            queues = QueueTopic.Names.NOTIFICATION_Q_MAIL,
            autoStartup = "${app.notification.listener.enabled:false}")
    public void onMessage(
            MailMessageDTO message,
            @Header(name = AmqpHeaders.MESSAGE_ID, required = false) String messageId) {
        handleMessage(message, messageId);
    }

    void onMessage(MailMessageDTO message) {
        handleMessage(message, null);
    }

    private void handleMessage(MailMessageDTO message, String messageId) {
        log.info("Processing mail task for {}", message.getTo());
        Instant startedAt = Instant.now();
        String correlationId = normalizeCorrelationId(messageId);
        try {
            mailService.send(message, correlationId);
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
            log.error("Failed to send mail to {}. Retry scheduled by RabbitMQ.", message.getTo(), ex);
            throw ex;
        }
    }

    private static String normalizeCorrelationId(String messageId) {
        return StringUtils.hasText(messageId) ? messageId.trim() : null;
    }
}
