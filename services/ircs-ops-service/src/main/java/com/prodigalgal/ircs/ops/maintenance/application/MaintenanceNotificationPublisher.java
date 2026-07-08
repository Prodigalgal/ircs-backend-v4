package com.prodigalgal.ircs.ops.maintenance.application;

import com.prodigalgal.ircs.common.config.RuntimeConfigService;
import com.prodigalgal.ircs.common.id.IrcsUuidGenerators;
import com.prodigalgal.ircs.contracts.notification.NotificationChannel;
import com.prodigalgal.ircs.contracts.notification.NotificationCommand;
import com.prodigalgal.ircs.contracts.queue.QueueTopic;
import com.prodigalgal.ircs.ops.maintenance.domain.MaintenanceRunResult;
import com.prodigalgal.ircs.ops.maintenance.domain.MaintenanceRunnerExecution;
import com.prodigalgal.ircs.ops.maintenance.dto.MaintenanceSchedulerRunResult;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
@RequiredArgsConstructor
@Slf4j
public class MaintenanceNotificationPublisher {

    static final String SYSTEM_MAIL_ENABLED_KEY = "app.mail.enabled";
    static final String MAIL_ENABLED_KEY = "app.ops.maintenance.notification.mail.enabled";
    static final String MAIL_RECIPIENTS_KEY = "app.ops.maintenance.notification.mail.recipients";
    static final String MAIL_SUBJECT_PREFIX_KEY = "app.ops.maintenance.notification.mail.subject-prefix";
    private static final Pattern RECIPIENT_SEPARATOR = Pattern.compile("[,;\\s]+");
    private static final String DEFAULT_SUBJECT_PREFIX = "[IRCS 运维任务]";

    private final RabbitTemplate rabbitTemplate;
    private final RuntimeConfigService runtimeConfig;

    public void publishSchedulerRun(String correlationId, List<MaintenanceSchedulerRunResult> results) {
        List<MaintenanceSchedulerRunResult> notifyableResults = results == null
                ? List.of()
                : results.stream()
                        .filter(result -> result != null && !result.skipped() && !result.dryRun())
                        .toList();
        if (notifyableResults.isEmpty()) {
            return;
        }
        publish(command(
                correlationId,
                "scheduler",
                "maintenance scheduler",
                summarizeScheduler(notifyableResults),
                schedulerContent(correlationId, notifyableResults),
                Map.of(
                        "eventType", "maintenance_scheduler",
                        "correlationId", nullToBlank(correlationId),
                        "taskCount", notifyableResults.size())));
    }

    public void publishManualRun(UUID sessionId, String taskName, MaintenanceRunnerExecution execution) {
        if (execution == null) {
            return;
        }
        String status = execution.refused() ? "refused" : "completed";
        MaintenanceRunResult result = execution.result();
        Map<String, Object> variables = new LinkedHashMap<>();
        variables.put("eventType", "maintenance_manual");
        variables.put("sessionId", sessionId == null ? "" : sessionId.toString());
        variables.put("taskName", taskName);
        variables.put("status", status);
        variables.put("selectedCount", result == null ? 0 : result.selectedCount());
        variables.put("publishedCount", result == null ? 0 : result.publishedCount());
        publish(command(
                sessionId == null ? null : sessionId.toString(),
                status,
                taskName,
                status + " " + taskName,
                manualContent(sessionId, taskName, execution),
                variables));
    }

    public void publishManualFailure(UUID sessionId, String taskName, Exception exception) {
        Map<String, Object> variables = new LinkedHashMap<>();
        variables.put("eventType", "maintenance_manual");
        variables.put("sessionId", sessionId == null ? "" : sessionId.toString());
        variables.put("taskName", taskName);
        variables.put("status", "failed");
        variables.put("error", exception == null ? "" : exception.getMessage());
        publish(command(
                sessionId == null ? null : sessionId.toString(),
                "failed",
                taskName,
                "failed " + taskName,
                failureContent(sessionId, taskName, exception),
                variables));
    }

    private void publish(NotificationCommand command) {
        if (!mailEnabled()) {
            return;
        }
        List<String> recipients = recipients();
        if (recipients.isEmpty()) {
            log.debug("Maintenance mail notification skipped because no recipients are configured");
            return;
        }
        command.setRecipients(recipients);
        QueueTopic topic = QueueTopic.DISPATCH_NOTIFICATION;
        try {
            rabbitTemplate.convertAndSend(topic.exchange(), topic.routingKey(), command);
        } catch (RuntimeException ex) {
            log.warn("Failed to publish maintenance mail notification: correlationId={}, error={}",
                    command.getCorrelationId(),
                    ex.getMessage());
        }
    }

    private NotificationCommand command(
            String correlationId,
            String status,
            String taskName,
            String summary,
            String content,
            Map<String, Object> variables) {
        return NotificationCommand.builder()
                .commandId(IrcsUuidGenerators.nextId().toString())
                .correlationId(correlationId)
                .channel(NotificationChannel.MAIL)
                .subject(subject(status, taskName, summary))
                .content(content)
                .html(false)
                .variables(variables)
                .build();
    }

    private boolean mailEnabled() {
        return runtimeConfig.booleanValue(SYSTEM_MAIL_ENABLED_KEY, true)
                && runtimeConfig.booleanValue(
                        MAIL_ENABLED_KEY,
                        true,
                        "APP_OPS_MAINTENANCE_NOTIFICATION_MAIL_ENABLED");
    }

    private List<String> recipients() {
        String raw = runtimeConfig.stringValue(
                MAIL_RECIPIENTS_KEY,
                "",
                "APP_OPS_MAINTENANCE_NOTIFICATION_MAIL_RECIPIENTS");
        if (!StringUtils.hasText(raw)) {
            return List.of();
        }
        return RECIPIENT_SEPARATOR.splitAsStream(raw)
                .map(String::trim)
                .filter(StringUtils::hasText)
                .distinct()
                .toList();
    }

    private String subject(String status, String taskName, String summary) {
        String prefix = runtimeConfig.stringValue(
                MAIL_SUBJECT_PREFIX_KEY,
                DEFAULT_SUBJECT_PREFIX,
                "APP_OPS_MAINTENANCE_NOTIFICATION_MAIL_SUBJECT_PREFIX");
        return prefix + " " + status + " " + nullToBlank(taskName) + " - " + nullToBlank(summary);
    }

    private static String summarizeScheduler(List<MaintenanceSchedulerRunResult> results) {
        long executed = results.stream().filter(MaintenanceSchedulerRunResult::executed).count();
        long refused = results.stream().filter(MaintenanceSchedulerRunResult::refused).count();
        return "executed=" + executed + ", refused=" + refused;
    }

    private static String schedulerContent(String correlationId, List<MaintenanceSchedulerRunResult> results) {
        StringBuilder builder = new StringBuilder()
                .append("运维调度任务已完成\n")
                .append("correlationId: ").append(nullToBlank(correlationId)).append('\n')
                .append("tasks:\n");
        for (MaintenanceSchedulerRunResult result : results) {
            builder.append("- ")
                    .append(result.taskName())
                    .append(" status=")
                    .append(result.executed() ? "executed" : "refused")
                    .append(" selected=")
                    .append(result.selectedCount())
                    .append(" published=")
                    .append(result.publishedCount());
            if (StringUtils.hasText(result.reason())) {
                builder.append(" reason=").append(result.reason());
            }
            builder.append('\n');
        }
        return builder.toString();
    }

    private static String manualContent(UUID sessionId, String taskName, MaintenanceRunnerExecution execution) {
        StringBuilder builder = new StringBuilder()
                .append("手工运维任务已结束\n")
                .append("sessionId: ").append(sessionId == null ? "" : sessionId).append('\n')
                .append("taskName: ").append(nullToBlank(taskName)).append('\n');
        if (execution.refused()) {
            return builder.append("status: refused\n")
                    .append("reason: ")
                    .append(nullToBlank(execution.reason()))
                    .append('\n')
                    .toString();
        }
        MaintenanceRunResult result = execution.result();
        return builder.append("status: completed\n")
                .append("selected: ").append(result == null ? 0 : result.selectedCount()).append('\n')
                .append("published: ").append(result == null ? 0 : result.publishedCount()).append('\n')
                .toString();
    }

    private static String failureContent(UUID sessionId, String taskName, Exception exception) {
        return new StringBuilder()
                .append("手工运维任务执行失败\n")
                .append("sessionId: ").append(sessionId == null ? "" : sessionId).append('\n')
                .append("taskName: ").append(nullToBlank(taskName)).append('\n')
                .append("error: ").append(exception == null ? "" : nullToBlank(exception.getMessage())).append('\n')
                .toString();
    }

    private static String nullToBlank(String value) {
        return value == null ? "" : value;
    }
}
