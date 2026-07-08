package com.prodigalgal.ircs.ops.maintenance.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.prodigalgal.ircs.common.config.RuntimeConfigService;
import com.prodigalgal.ircs.contracts.notification.NotificationChannel;
import com.prodigalgal.ircs.contracts.notification.NotificationCommand;
import com.prodigalgal.ircs.contracts.queue.QueueTopic;
import com.prodigalgal.ircs.ops.maintenance.domain.MaintenanceOwnerStep;
import com.prodigalgal.ircs.ops.maintenance.domain.MaintenanceRiskLevel;
import com.prodigalgal.ircs.ops.maintenance.dto.MaintenanceRunnerMetadata;
import com.prodigalgal.ircs.ops.maintenance.dto.MaintenanceSchedulerRunResult;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

class MaintenanceNotificationPublisherTest {

    private final RabbitTemplate rabbitTemplate = org.mockito.Mockito.mock(RabbitTemplate.class);
    private final RuntimeConfigService runtimeConfig = org.mockito.Mockito.mock(RuntimeConfigService.class);
    private final MaintenanceNotificationPublisher publisher =
            new MaintenanceNotificationPublisher(rabbitTemplate, runtimeConfig);

    @Test
    void publishesSchedulerMailNotificationWhenEnabledAndRecipientsConfigured() {
        enableMail("ops@example.invalid; admin@example.invalid");

        publisher.publishSchedulerRun("corr-1", List.of(MaintenanceSchedulerRunResult.executed(
                com.prodigalgal.ircs.ops.maintenance.domain.MaintenanceRunnerExecution.executed(
                        metadata("search-reindex-unified"),
                        new com.prodigalgal.ircs.ops.maintenance.domain.MaintenanceRunResult(
                                "search-reindex-unified",
                                2,
                                2,
                                List.of())))));

        ArgumentCaptor<NotificationCommand> captor = ArgumentCaptor.forClass(NotificationCommand.class);
        verify(rabbitTemplate).convertAndSend(
                eq(QueueTopic.Names.NOTIFICATION_X),
                eq(QueueTopic.DISPATCH_NOTIFICATION.routingKey()),
                captor.capture());
        NotificationCommand command = captor.getValue();
        assertThat(command.getChannel()).isEqualTo(NotificationChannel.MAIL);
        assertThat(command.getRecipients()).containsExactly("ops@example.invalid", "admin@example.invalid");
        assertThat(command.getSubject()).contains("[OPS]", "scheduler", "executed=1");
        assertThat(command.getContent()).contains("search-reindex-unified", "selected=2", "published=2");
    }

    @Test
    void skipsWhenSystemMailIsDisabled() {
        when(runtimeConfig.booleanValue(MaintenanceNotificationPublisher.SYSTEM_MAIL_ENABLED_KEY, true))
                .thenReturn(false);

        publisher.publishSchedulerRun("corr-1", List.of(MaintenanceSchedulerRunResult.executed(
                com.prodigalgal.ircs.ops.maintenance.domain.MaintenanceRunnerExecution.executed(
                        metadata("search-reindex-unified"),
                        new com.prodigalgal.ircs.ops.maintenance.domain.MaintenanceRunResult(
                                "search-reindex-unified",
                                1,
                                1,
                                List.of())))));

        verifyNoInteractions(rabbitTemplate);
    }

    @Test
    void skipsWhenRecipientsAreEmpty() {
        enableMail("");

        publisher.publishSchedulerRun("corr-1", List.of(MaintenanceSchedulerRunResult.executed(
                com.prodigalgal.ircs.ops.maintenance.domain.MaintenanceRunnerExecution.executed(
                        metadata("search-reindex-unified"),
                        new com.prodigalgal.ircs.ops.maintenance.domain.MaintenanceRunResult(
                                "search-reindex-unified",
                                1,
                                1,
                                List.of())))));

        verifyNoInteractions(rabbitTemplate);
    }

    private void enableMail(String recipients) {
        when(runtimeConfig.booleanValue(MaintenanceNotificationPublisher.SYSTEM_MAIL_ENABLED_KEY, true))
                .thenReturn(true);
        when(runtimeConfig.booleanValue(
                eq(MaintenanceNotificationPublisher.MAIL_ENABLED_KEY),
                eq(true),
                eq("APP_OPS_MAINTENANCE_NOTIFICATION_MAIL_ENABLED")))
                .thenReturn(true);
        when(runtimeConfig.stringValue(
                eq(MaintenanceNotificationPublisher.MAIL_RECIPIENTS_KEY),
                eq(""),
                eq("APP_OPS_MAINTENANCE_NOTIFICATION_MAIL_RECIPIENTS")))
                .thenReturn(recipients);
        when(runtimeConfig.stringValue(
                eq(MaintenanceNotificationPublisher.MAIL_SUBJECT_PREFIX_KEY),
                eq("[IRCS 运维任务]"),
                eq("APP_OPS_MAINTENANCE_NOTIFICATION_MAIL_SUBJECT_PREFIX")))
                .thenReturn("[OPS]");
    }

    private static MaintenanceRunnerMetadata metadata(String taskName) {
        return new MaintenanceRunnerMetadata(
                taskName,
                MaintenanceRiskLevel.LOW,
                true,
                true,
                5,
                100,
                "",
                List.of(new MaintenanceOwnerStep(
                        "search-index",
                        "search-service",
                        "enqueue-index",
                        "search:sync")));
    }
}
