package com.prodigalgal.ircs.opsalert.application;

import com.prodigalgal.ircs.common.config.RuntimeConfigService;
import com.prodigalgal.ircs.contracts.notification.NotificationChannel;
import com.prodigalgal.ircs.contracts.notification.NotificationCommand;
import com.prodigalgal.ircs.contracts.queue.QueueTopic;
import com.prodigalgal.ircs.opsalert.domain.AlertEvent;
import com.prodigalgal.ircs.opsalert.domain.HealingAction;
import com.prodigalgal.ircs.opsalert.domain.Incident;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
@Slf4j
@RequiredArgsConstructor
public class OpsAlertNotificationPublisher {

    static final String MAIL_ENABLED_KEY = "app.ops-alert.notification.mail.enabled";
    static final String SYSTEM_MAIL_ENABLED_KEY = "app.mail.enabled";

    private final RabbitTemplate rabbitTemplate;
    private final RuntimeConfigService runtimeConfig;
    private final OpsAlertNotificationProperties properties;

    public void publishIncidentNotification(AlertEvent event, Incident incident, List<HealingAction> actions) {
        if (!shouldPublishMail()) {
            return;
        }
        List<String> recipients = recipients();
        if (recipients.isEmpty()) {
            log.debug("Skipped ops alert mail notification because recipients are not configured");
            return;
        }
        try {
            QueueTopic topic = QueueTopic.DISPATCH_NOTIFICATION;
            rabbitTemplate.convertAndSend(
                    topic.exchange(),
                    topic.routingKey(),
                    command(event, incident, actions, recipients));
        } catch (Exception ex) {
            log.warn("Failed to publish ops alert mail notification; incidentId={}", incident.id(), ex);
        }
    }

    private boolean shouldPublishMail() {
        return runtimeConfig.booleanValue(SYSTEM_MAIL_ENABLED_KEY, true)
                && runtimeConfig.booleanValue(MAIL_ENABLED_KEY, properties.enabled());
    }

    private List<String> recipients() {
        return properties.recipients().stream()
                .flatMap(value -> java.util.Arrays.stream(value.split("[,;\\s]+")))
                .map(String::trim)
                .filter(StringUtils::hasText)
                .distinct()
                .toList();
    }

    private NotificationCommand command(
            AlertEvent event,
            Incident incident,
            List<HealingAction> actions,
            List<String> recipients) {
        return NotificationCommand.builder()
                .commandId(UUID.randomUUID().toString())
                .correlationId(event.id().toString())
                .channel(NotificationChannel.MAIL)
                .recipients(recipients)
                .subject(subject(incident))
                .content(content(event, incident, actions))
                .html(false)
                .variables(Map.of(
                        "eventId", event.id().toString(),
                        "incidentId", incident.id().toString(),
                        "severity", incident.severity().name(),
                        "resourceType", incident.resourceType(),
                        "resourceName", incident.resourceName(),
                        "occurrenceCount", incident.occurrenceCount()))
                .build();
    }

    private String subject(Incident incident) {
        return "%s %s %s/%s".formatted(
                properties.subjectPrefix(),
                incident.severity().name(),
                incident.resourceType(),
                incident.resourceName());
    }

    private String content(AlertEvent event, Incident incident, List<HealingAction> actions) {
        StringBuilder builder = new StringBuilder();
        builder.append("告警事件：").append(event.summary()).append('\n');
        builder.append("严重级别：").append(event.severity()).append('\n');
        builder.append("来源：").append(event.source()).append('\n');
        builder.append("资源：").append(event.resourceType()).append('/').append(event.resourceName()).append('\n');
        builder.append("事件类型：").append(event.eventType()).append('\n');
        builder.append("事件次数：").append(incident.occurrenceCount()).append('\n');
        builder.append("事件 ID：").append(event.id()).append('\n');
        builder.append("事件聚合 ID：").append(incident.id()).append('\n');
        if (actions != null && !actions.isEmpty()) {
            builder.append("自愈动作：");
            builder.append(actions.stream()
                    .map(action -> action.playbookKey() + "(" + action.status() + ")")
                    .toList());
            builder.append('\n');
        }
        if (StringUtils.hasText(event.detailsJson())) {
            builder.append("详情：").append(event.detailsJson()).append('\n');
        }
        return builder.toString();
    }
}
