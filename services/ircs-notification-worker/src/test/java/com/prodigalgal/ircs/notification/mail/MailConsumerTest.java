package com.prodigalgal.ircs.notification.mail;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

import com.prodigalgal.ircs.common.audit.WorkerJobAuditWriter;
import com.prodigalgal.ircs.contracts.notification.MailMessageDTO;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.sql.DriverManager;
import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class MailConsumerTest {

    @Mock
    private NotificationMailService mailService;

    @Test
    void delegatesQueueMessageToMailService() {
        MailMessageDTO message = MailMessageDTO.builder()
                .to("codex@example.invalid")
                .subject("subject")
                .content("content")
                .build();

        new MailConsumer(mailService, WorkerJobAuditWriter.noop()).onMessage(message);

        verify(mailService).send(message, null);
    }

    @Test
    void rethrowsMailServiceFailureForRabbitRetry() {
        MailMessageDTO message = MailMessageDTO.builder()
                .to("codex@example.invalid")
                .subject("subject")
                .content("content")
                .build();
        RuntimeException failure = new RuntimeException("delivery failed");
        doThrow(failure).when(mailService).send(message, null);

        RuntimeException thrown = assertThrows(
                RuntimeException.class,
                () -> new MailConsumer(mailService, WorkerJobAuditWriter.noop()).onMessage(message));

        assertSame(failure, thrown);
    }

    @Test
    void rethrowsMissingMailCredentialFailureForRabbitRetryAndDlqPolicy() {
        MailMessageDTO message = MailMessageDTO.builder()
                .to("codex@example.invalid")
                .subject("subject")
                .content("content")
                .build();
        RuntimeException failure = new MailCredentialLeaseException("MAIL credential is not available");
        doThrow(failure).when(mailService).send(message, null);

        RuntimeException thrown = assertThrows(
                RuntimeException.class,
                () -> new MailConsumer(mailService, WorkerJobAuditWriter.noop()).onMessage(message));

        assertSame(failure, thrown);
    }

    @Test
    void rethrowsFakeMailFailureForRabbitRetryAndDlqPolicy() {
        MailMessageDTO message = MailMessageDTO.builder()
                .to("codex@example.invalid")
                .subject("subject")
                .content("content")
                .build();
        RuntimeException failure = new RuntimeException("Fake mail delivery failed");
        doThrow(failure).when(mailService).send(message, null);

        RuntimeException thrown = assertThrows(
                RuntimeException.class,
                () -> new MailConsumer(mailService, WorkerJobAuditWriter.noop()).onMessage(message));

        assertSame(failure, thrown);
    }

    @Test
    void listenerUsesNotificationAutoStartupSwitch() throws Exception {
        Method method = MailConsumer.class.getDeclaredMethod("onMessage", MailMessageDTO.class, String.class);
        RabbitListener rabbitListener = method.getAnnotation(RabbitListener.class);

        org.junit.jupiter.api.Assertions.assertEquals(
                "${app.notification.listener.enabled:false}",
                rabbitListener.autoStartup());
    }

    @Test
    void writesSucceededWorkerJobAuditEvent() throws Exception {
        String url = "jdbc:h2:mem:notification_mail_audit_success;MODE=PostgreSQL;DB_CLOSE_DELAY=-1";
        createAuditTable(url);
        MailMessageDTO message = MailMessageDTO.builder()
                .to("codex@example.invalid")
                .subject("subject")
                .content("content")
                .build();

        new MailConsumer(mailService, new WorkerJobAuditWriter(
                true,
                url,
                null,
                null,
                "ircs-notification-worker")).onMessage(message);

        verify(mailService).send(message, null);
        try (var connection = DriverManager.getConnection(url);
             var result = connection.createStatement().executeQuery("""
                     select job_source, job_type, job_name, correlation_id, status, duration_ms,
                            error_class, error_message
                     from worker_job_audit_events
                     """)) {
            org.assertj.core.api.Assertions.assertThat(result.next()).isTrue();
            org.assertj.core.api.Assertions.assertThat(result.getString("job_source"))
                    .startsWith("ircs-notification-worker@")
                    .contains("#");
            org.assertj.core.api.Assertions.assertThat(result.getString("job_type")).isEqualTo("queue_consumer");
            org.assertj.core.api.Assertions.assertThat(result.getString("job_name")).isEqualTo("notification.mail");
            org.assertj.core.api.Assertions.assertThat(result.getString("correlation_id")).isNull();
            org.assertj.core.api.Assertions.assertThat(result.getString("status")).isEqualTo("succeeded");
            org.assertj.core.api.Assertions.assertThat(result.getLong("duration_ms")).isGreaterThanOrEqualTo(0L);
            org.assertj.core.api.Assertions.assertThat(result.getString("error_class")).isNull();
            org.assertj.core.api.Assertions.assertThat(result.getString("error_message")).isNull();
            org.assertj.core.api.Assertions.assertThat(result.next()).isFalse();
        }
    }

    @Test
    void recordsQueueConsumerMicrometerMetrics() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        MailMessageDTO message = MailMessageDTO.builder()
                .to("codex@example.invalid")
                .subject("subject")
                .content("content")
                .build();

        new MailConsumer(mailService, new WorkerJobAuditWriter(
                false,
                null,
                null,
                null,
                "ircs-notification-worker",
                "notification-pod-1",
                registry)).onMessage(message, "mail-message-1");

        verify(mailService).send(message, "mail-message-1");
        org.assertj.core.api.Assertions.assertThat(registry.get("ircs.worker.job.runs")
                .tag("worker_id", "notification-pod-1")
                .tag("job_type", "queue_consumer")
                .tag("job_name", "notification.mail")
                .tag("outcome", "succeeded")
                .counter()
                .count())
                .isEqualTo(1.0d);
        org.assertj.core.api.Assertions.assertThat(registry.get("ircs.worker.job.duration")
                .tag("worker_id", "notification-pod-1")
                .tag("job_type", "queue_consumer")
                .tag("job_name", "notification.mail")
                .tag("outcome", "succeeded")
                .timer()
                .count())
                .isEqualTo(1L);
    }

    @Test
    void writesRabbitMessageIdAsWorkerJobAuditCorrelationId() throws Exception {
        String url = "jdbc:h2:mem:notification_mail_audit_correlation;MODE=PostgreSQL;DB_CLOSE_DELAY=-1";
        createAuditTable(url);
        MailMessageDTO message = MailMessageDTO.builder()
                .to("codex@example.invalid")
                .subject("subject")
                .content("content")
                .build();

        new MailConsumer(mailService, new WorkerJobAuditWriter(
                true,
                url,
                null,
                null,
                "ircs-notification-worker")).onMessage(message, "02812-notification-smoke");

        verify(mailService).send(message, "02812-notification-smoke");
        try (var connection = DriverManager.getConnection(url);
             var result = connection.createStatement().executeQuery("""
                     select correlation_id, status
                     from worker_job_audit_events
                     """)) {
            org.assertj.core.api.Assertions.assertThat(result.next()).isTrue();
            org.assertj.core.api.Assertions.assertThat(result.getString("correlation_id"))
                    .isEqualTo("02812-notification-smoke");
            org.assertj.core.api.Assertions.assertThat(result.getString("status")).isEqualTo("succeeded");
            org.assertj.core.api.Assertions.assertThat(result.next()).isFalse();
        }
    }

    @Test
    void writesFailedWorkerJobAuditEventAndRethrowsForRabbitRetry() throws Exception {
        String url = "jdbc:h2:mem:notification_mail_audit_failure;MODE=PostgreSQL;DB_CLOSE_DELAY=-1";
        createAuditTable(url);
        MailMessageDTO message = MailMessageDTO.builder()
                .to("codex@example.invalid")
                .subject("subject")
                .content("content")
                .build();
        RuntimeException failure = new RuntimeException("delivery failed");
        doThrow(failure).when(mailService).send(message, null);

        RuntimeException thrown = assertThrows(RuntimeException.class, () -> new MailConsumer(
                mailService,
                new WorkerJobAuditWriter(true, url, null, null, "ircs-notification-worker"))
                .onMessage(message));

        assertSame(failure, thrown);
        try (var connection = DriverManager.getConnection(url);
             var result = connection.createStatement().executeQuery("""
                     select job_source, job_type, job_name, correlation_id, status, duration_ms,
                            error_class, error_message
                     from worker_job_audit_events
                     """)) {
            org.assertj.core.api.Assertions.assertThat(result.next()).isTrue();
            org.assertj.core.api.Assertions.assertThat(result.getString("job_source"))
                    .startsWith("ircs-notification-worker@")
                    .contains("#");
            org.assertj.core.api.Assertions.assertThat(result.getString("job_type")).isEqualTo("queue_consumer");
            org.assertj.core.api.Assertions.assertThat(result.getString("job_name")).isEqualTo("notification.mail");
            org.assertj.core.api.Assertions.assertThat(result.getString("correlation_id")).isNull();
            org.assertj.core.api.Assertions.assertThat(result.getString("status")).isEqualTo("failed");
            org.assertj.core.api.Assertions.assertThat(result.getLong("duration_ms")).isGreaterThanOrEqualTo(0L);
            org.assertj.core.api.Assertions.assertThat(result.getString("error_class"))
                    .isEqualTo("java.lang.RuntimeException");
            org.assertj.core.api.Assertions.assertThat(result.getString("error_message")).isEqualTo("delivery failed");
            org.assertj.core.api.Assertions.assertThat(result.next()).isFalse();
        }
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
