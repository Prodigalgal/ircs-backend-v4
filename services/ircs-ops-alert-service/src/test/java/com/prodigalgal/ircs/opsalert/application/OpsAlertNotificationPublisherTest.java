package com.prodigalgal.ircs.opsalert.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.prodigalgal.ircs.common.config.RuntimeConfigService;
import com.prodigalgal.ircs.contracts.notification.NotificationChannel;
import com.prodigalgal.ircs.contracts.notification.NotificationCommand;
import com.prodigalgal.ircs.contracts.queue.QueueTopic;
import com.prodigalgal.ircs.opsalert.domain.AlertEvent;
import com.prodigalgal.ircs.opsalert.domain.AlertSeverity;
import com.prodigalgal.ircs.opsalert.domain.Incident;
import com.prodigalgal.ircs.opsalert.domain.IncidentStatus;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

class OpsAlertNotificationPublisherTest {

    private final RabbitTemplate rabbitTemplate = org.mockito.Mockito.mock(RabbitTemplate.class);
    private final RuntimeConfigService runtimeConfig = org.mockito.Mockito.mock(RuntimeConfigService.class);

    @Test
    void publishesMailNotificationWhenMailIsEnabledAndRecipientsConfigured() {
        when(runtimeConfig.booleanValue(OpsAlertNotificationPublisher.SYSTEM_MAIL_ENABLED_KEY, true)).thenReturn(true);
        when(runtimeConfig.booleanValue(OpsAlertNotificationPublisher.MAIL_ENABLED_KEY, true)).thenReturn(true);
        OpsAlertNotificationPublisher publisher = new OpsAlertNotificationPublisher(
                rabbitTemplate,
                runtimeConfig,
                new OpsAlertNotificationProperties(true, List.of("ops@example.invalid; admin@example.invalid"), "[IRCS]"));

        publisher.publishIncidentNotification(event(), incident(), List.of());

        ArgumentCaptor<NotificationCommand> captor = ArgumentCaptor.forClass(NotificationCommand.class);
        verify(rabbitTemplate).convertAndSend(
                eq(QueueTopic.Names.NOTIFICATION_X),
                eq(QueueTopic.DISPATCH_NOTIFICATION.routingKey()),
                captor.capture());
        NotificationCommand command = captor.getValue();
        assertThat(command.getChannel()).isEqualTo(NotificationChannel.MAIL);
        assertThat(command.getRecipients()).containsExactly("ops@example.invalid", "admin@example.invalid");
        assertThat(command.getSubject()).contains("[IRCS]", "ERROR", "queue/metadata-runtime");
        assertThat(command.getContent()).contains("metadata-runtime stalled", "事件聚合 ID");
    }

    @Test
    void skipsMailNotificationWhenSystemMailIsDisabled() {
        when(runtimeConfig.booleanValue(OpsAlertNotificationPublisher.SYSTEM_MAIL_ENABLED_KEY, true)).thenReturn(false);
        OpsAlertNotificationPublisher publisher = new OpsAlertNotificationPublisher(
                rabbitTemplate,
                runtimeConfig,
                new OpsAlertNotificationProperties(true, List.of("ops@example.invalid"), "[IRCS]"));

        publisher.publishIncidentNotification(event(), incident(), List.of());

        verifyNoInteractions(rabbitTemplate);
    }

    @Test
    void skipsMailNotificationWhenRecipientsAreEmpty() {
        when(runtimeConfig.booleanValue(OpsAlertNotificationPublisher.SYSTEM_MAIL_ENABLED_KEY, true)).thenReturn(true);
        when(runtimeConfig.booleanValue(OpsAlertNotificationPublisher.MAIL_ENABLED_KEY, true)).thenReturn(true);
        OpsAlertNotificationPublisher publisher = new OpsAlertNotificationPublisher(
                rabbitTemplate,
                runtimeConfig,
                new OpsAlertNotificationProperties(true, List.of(), "[IRCS]"));

        publisher.publishIncidentNotification(event(), incident(), List.of());

        verifyNoInteractions(rabbitTemplate);
    }

    private static AlertEvent event() {
        return new AlertEvent(
                UUID.randomUUID(),
                Instant.parse("2026-06-22T10:15:30Z"),
                Instant.parse("2026-06-22T10:15:00Z"),
                "ops-service",
                "runtime_queue_stale_inflight",
                AlertSeverity.ERROR,
                "queue",
                "metadata-runtime",
                "worker-runtime-stalled",
                "metadata-runtime stalled",
                "{\"queue\":\"metadata-runtime\"}");
    }

    private static Incident incident() {
        UUID eventId = UUID.randomUUID();
        return new Incident(
                UUID.randomUUID(),
                Instant.parse("2026-06-22T10:15:30Z"),
                Instant.parse("2026-06-22T10:15:30Z"),
                1,
                "worker-runtime-stalled",
                IncidentStatus.OPEN,
                AlertSeverity.ERROR,
                "metadata-runtime stalled",
                "ops-service",
                "queue",
                "metadata-runtime",
                Instant.parse("2026-06-22T10:15:00Z"),
                Instant.parse("2026-06-22T10:15:00Z"),
                null,
                1,
                "metadata-runtime stalled",
                eventId);
    }
}
