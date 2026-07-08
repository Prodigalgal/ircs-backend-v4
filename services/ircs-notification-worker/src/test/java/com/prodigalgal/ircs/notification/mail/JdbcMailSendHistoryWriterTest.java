package com.prodigalgal.ircs.notification.mail;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.sql.DriverManager;
import java.sql.Statement;
import java.util.Objects;
import java.util.Scanner;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class JdbcMailSendHistoryWriterTest {

    @Test
    void writesSentHistoryWithoutCredentialSecret() throws Exception {
        String url = "jdbc:h2:mem:notification_mail_send_history_sent;MODE=PostgreSQL;DB_CLOSE_DELAY=-1";
        createHistoryTable(url);
        UUID credentialId = UUID.fromString("de0a6fd9-f07d-4201-bf92-279b6c9f099d");
        JdbcMailSendHistoryWriter writer = new JdbcMailSendHistoryWriter(true, url, null, null);
        RenderedMail mail = new RenderedMail(
                "noreply@example.invalid",
                "codex@example.invalid",
                "subject",
                "content",
                true);

        writer.record(MailSendHistoryEvent.sent(
                "rabbit-message-02810",
                mail,
                "mail/activation",
                NotificationMailProperties.DeliveryMode.SINK,
                new MailCredential(credentialId, "mail@example.invalid", "secret-password")));

        try (var connection = DriverManager.getConnection(url);
             var result = connection.createStatement().executeQuery("""
                     select correlation_id, recipient, subject, template_code, delivery_mode,
                            status, credential_id, failure_code, failure_message
                     from notification_mail_send_history
                     """)) {
            org.assertj.core.api.Assertions.assertThat(result.next()).isTrue();
            assertEquals("rabbit-message-02810", result.getString("correlation_id"));
            assertEquals("codex@example.invalid", result.getString("recipient"));
            assertEquals("subject", result.getString("subject"));
            assertEquals("mail/activation", result.getString("template_code"));
            assertEquals("sink", result.getString("delivery_mode"));
            assertEquals("sent", result.getString("status"));
            assertEquals(credentialId, result.getObject("credential_id", UUID.class));
            assertNull(result.getString("failure_code"));
            assertNull(result.getString("failure_message"));
            org.assertj.core.api.Assertions.assertThat(result.next()).isFalse();
        }
    }

    @Test
    void writesFailedHistoryWithSensitiveFailureRedaction() throws Exception {
        String url = "jdbc:h2:mem:notification_mail_send_history_failed;MODE=PostgreSQL;DB_CLOSE_DELAY=-1";
        createHistoryTable(url);
        JdbcMailSendHistoryWriter writer = new JdbcMailSendHistoryWriter(true, url, null, null);
        RenderedMail mail = new RenderedMail(
                "",
                "codex@example.invalid",
                "subject",
                "content",
                false);

        writer.record(MailSendHistoryEvent.failed(
                "rabbit-message-02810",
                mail,
                null,
                NotificationMailProperties.DeliveryMode.FAKE,
                null,
                new RuntimeException("Fake mail delivery failed for codex@example.invalid")));

        try (var connection = DriverManager.getConnection(url);
             var result = connection.createStatement().executeQuery("""
                     select delivery_mode, status, credential_id, failure_code, failure_message
                     from notification_mail_send_history
                     """)) {
            org.assertj.core.api.Assertions.assertThat(result.next()).isTrue();
            assertEquals("fake", result.getString("delivery_mode"));
            assertEquals("failed", result.getString("status"));
            assertNull(result.getObject("credential_id"));
            assertEquals(RuntimeException.class.getName(), result.getString("failure_code"));
            assertEquals("Fake mail delivery failed for [redacted-email]", result.getString("failure_message"));
            org.assertj.core.api.Assertions.assertThat(result.next()).isFalse();
        }
    }

    @Test
    void missingHistoryTableDoesNotPropagateFailure() {
        JdbcMailSendHistoryWriter writer = new JdbcMailSendHistoryWriter(
                true,
                "jdbc:h2:mem:notification_mail_send_history_missing;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
                null,
                null);

        assertDoesNotThrow(() -> writer.record(MailSendHistoryEvent.skipped(
                "rabbit-message-02810",
                "codex@example.invalid",
                "subject",
                null,
                NotificationMailProperties.DeliveryMode.SINK,
                "mail_disabled")));
    }

    private static void createHistoryTable(String url) throws Exception {
        try (var connection = DriverManager.getConnection(url);
             var statement = connection.createStatement();
             var stream = Objects.requireNonNull(
                     JdbcMailSendHistoryWriterTest.class.getResourceAsStream(
                             "/db/schema/notification_mail_send_history.sql"))) {
            try (var scanner = new Scanner(stream).useDelimiter("\\A")) {
                String ddl = scanner.hasNext() ? scanner.next() : "";
                ddl = ddl.replace("TIMESTAMPTZ", "TIMESTAMP");
                try (var ddlScanner = new Scanner(ddl)) {
                    ddlScanner.useDelimiter(";");
                    while (ddlScanner.hasNext()) {
                        execute(statement, ddlScanner.next());
                    }
                }
            }
        }
    }

    private static void execute(Statement statement, String sql) throws Exception {
        String trimmed = sql.trim();
        if (!trimmed.isEmpty()) {
            statement.execute(trimmed);
        }
    }
}
