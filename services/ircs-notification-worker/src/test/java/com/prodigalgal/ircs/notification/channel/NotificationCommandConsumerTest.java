package com.prodigalgal.ircs.notification.channel;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

import com.prodigalgal.ircs.common.audit.WorkerJobAuditWriter;
import com.prodigalgal.ircs.contracts.notification.NotificationChannel;
import com.prodigalgal.ircs.contracts.notification.NotificationCommand;
import java.lang.reflect.Method;
import java.sql.DriverManager;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.rabbit.annotation.RabbitListener;

@ExtendWith(MockitoExtension.class)
class NotificationCommandConsumerTest {

    @Mock
    private NotificationCommandDispatcher dispatcher;

    @Test
    void delegatesQueueMessageToDispatcher() {
        NotificationCommand command = command();

        new NotificationCommandConsumer(dispatcher, WorkerJobAuditWriter.noop()).onMessage(command);

        verify(dispatcher).dispatch(command);
    }

    @Test
    void rethrowsDispatcherFailureForRabbitRetry() {
        NotificationCommand command = command();
        RuntimeException failure = new RuntimeException("channel failed");
        doThrow(failure).when(dispatcher).dispatch(command);

        RuntimeException thrown = assertThrows(
                RuntimeException.class,
                () -> new NotificationCommandConsumer(dispatcher, WorkerJobAuditWriter.noop()).onMessage(command));

        assertSame(failure, thrown);
    }

    @Test
    void listenerUsesNotificationCommandAutoStartupSwitch() throws Exception {
        Method method = NotificationCommandConsumer.class.getDeclaredMethod(
                "onMessage", NotificationCommand.class, String.class);
        RabbitListener rabbitListener = method.getAnnotation(RabbitListener.class);

        org.junit.jupiter.api.Assertions.assertEquals(
                "${app.notification.listener.command-enabled:false}",
                rabbitListener.autoStartup());
    }

    @Test
    void writesCommandCorrelationIdAsWorkerJobAuditCorrelationId() throws Exception {
        String url = "jdbc:h2:mem:notification_command_audit_correlation;MODE=PostgreSQL;DB_CLOSE_DELAY=-1";
        createAuditTable(url);
        NotificationCommand command = command();

        new NotificationCommandConsumer(dispatcher, new WorkerJobAuditWriter(
                true,
                url,
                null,
                null,
                "ircs-notification-worker")).onMessage(command, "rabbit-message-1");

        verify(dispatcher).dispatch(command);
        try (var connection = DriverManager.getConnection(url);
             var result = connection.createStatement().executeQuery("""
                     select job_type, job_name, correlation_id, status
                     from worker_job_audit_events
                     """)) {
            org.assertj.core.api.Assertions.assertThat(result.next()).isTrue();
            org.assertj.core.api.Assertions.assertThat(result.getString("job_type")).isEqualTo("queue_consumer");
            org.assertj.core.api.Assertions.assertThat(result.getString("job_name")).isEqualTo("notification.command");
            org.assertj.core.api.Assertions.assertThat(result.getString("correlation_id")).isEqualTo("incident-1");
            org.assertj.core.api.Assertions.assertThat(result.getString("status")).isEqualTo("succeeded");
            org.assertj.core.api.Assertions.assertThat(result.next()).isFalse();
        }
    }

    private static NotificationCommand command() {
        return NotificationCommand.builder()
                .commandId("cmd-1")
                .correlationId("incident-1")
                .channel(NotificationChannel.MAIL)
                .recipients(List.of("ops@example.invalid"))
                .subject("IRCS alert")
                .content("incident opened")
                .build();
    }

    private static void createAuditTable(String url) throws Exception {
        try (var connection = DriverManager.getConnection(url);
             var statement = connection.createStatement()) {
            statement.execute("""
                    create table worker_job_audit_events (
                        id uuid primary key,
                        created_at timestamp not null,
                        updated_at timestamp not null,
                        version bigint not null,
                        audit_class varchar(32) not null,
                        job_source varchar(128) not null,
                        job_type varchar(64) not null,
                        job_name varchar(128) not null,
                        correlation_id varchar(128),
                        status varchar(32) not null,
                        duration_ms bigint not null,
                        error_class varchar(256),
                        error_message text
                    )
                    """);
        }
    }
}
