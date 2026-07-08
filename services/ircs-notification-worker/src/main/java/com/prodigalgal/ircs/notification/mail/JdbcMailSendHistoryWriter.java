package com.prodigalgal.ircs.notification.mail;

import com.prodigalgal.ircs.common.audit.AuditClass;
import com.prodigalgal.ircs.common.audit.AuditReplicationWorkDispatcher;
import com.prodigalgal.ircs.common.id.IrcsUuidGenerators;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;

@Slf4j
class JdbcMailSendHistoryWriter implements MailSendHistoryWriter {

    private static final int CORRELATION_LIMIT = 128;
    private static final int RECIPIENT_LIMIT = 320;
    private static final int SUBJECT_LIMIT = 512;
    private static final int TEMPLATE_LIMIT = 256;
    private static final int DELIVERY_MODE_LIMIT = 32;
    private static final int STATUS_LIMIT = 32;
    private static final int FAILURE_CODE_LIMIT = 256;
    private static final int FAILURE_MESSAGE_LIMIT = 2000;
    private static final Pattern EMAIL_PATTERN = Pattern.compile(
            "\\b[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern BEARER_PATTERN = Pattern.compile(
            "\\bBearer\\s+[A-Za-z0-9._~+/=-]+",
            Pattern.CASE_INSENSITIVE);
    private static final String INSERT_SQL = """
            insert into notification_mail_send_history (
                id, created_at, updated_at, version, audit_class, correlation_id, recipient, subject,
                template_code, delivery_mode, status, credential_id, failure_code,
                failure_message
            ) values (?, now(), now(), 0, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;

    private final boolean enabled;
    private final String datasourceUrl;
    private final String username;
    private final String password;

    JdbcMailSendHistoryWriter(
            boolean enabled,
            String datasourceUrl,
            String username,
            String password) {
        this.enabled = enabled;
        this.datasourceUrl = normalize(datasourceUrl);
        this.username = normalize(username);
        this.password = password == null ? "" : password;
    }

    @Override
    public void record(MailSendHistoryEvent event) {
        if (!enabled || datasourceUrl == null || event == null) {
            return;
        }
        try (Connection connection = connection();
             PreparedStatement statement = connection.prepareStatement(INSERT_SQL)) {
            UUID id = IrcsUuidGenerators.nextId();
            statement.setObject(1, id);
            statement.setString(2, AuditClass.SYSTEM.name());
            statement.setString(3, truncate(normalize(event.correlationId()), CORRELATION_LIMIT));
            statement.setString(4, truncate(normalize(event.recipient()), RECIPIENT_LIMIT));
            statement.setString(5, truncate(normalize(event.subject()), SUBJECT_LIMIT));
            statement.setString(6, truncate(normalize(event.templateCode()), TEMPLATE_LIMIT));
            statement.setString(7, truncate(deliveryMode(event), DELIVERY_MODE_LIMIT));
            statement.setString(8, truncate(status(event), STATUS_LIMIT));
            statement.setObject(9, event.credentialId());
            statement.setString(10, truncate(normalize(event.failureCode()), FAILURE_CODE_LIMIT));
            statement.setString(11, sanitizeFailureMessage(event.failureMessage()));
            statement.executeUpdate();
            AuditReplicationWorkDispatcher.enqueueMailSendHistory(AuditClass.SYSTEM, id, log);
        } catch (SQLException | RuntimeException ex) {
            log.warn("Notification mail send history write failed: {}", ex.getMessage());
        }
    }

    private Connection connection() throws SQLException {
        if (username == null) {
            return DriverManager.getConnection(datasourceUrl);
        }
        return DriverManager.getConnection(datasourceUrl, username, password);
    }

    private static String deliveryMode(MailSendHistoryEvent event) {
        return event.deliveryMode() == null ? null : event.deliveryMode().name().toLowerCase(Locale.ROOT);
    }

    private static String status(MailSendHistoryEvent event) {
        return event.status() == null ? null : event.status().name().toLowerCase(Locale.ROOT);
    }

    private static String sanitizeFailureMessage(String value) {
        String message = normalize(value);
        if (message == null) {
            return null;
        }
        String lower = message.toLowerCase(Locale.ROOT);
        if (lower.contains("authorization")
                || lower.contains("cookie")
                || lower.contains("credential")
                || lower.contains("token")
                || lower.contains("password")
                || lower.contains("secret")
                || lower.contains("request body")) {
            return "[redacted-sensitive-error]";
        }
        String redactedBearer = BEARER_PATTERN.matcher(message).replaceAll("Bearer [redacted]");
        String redactedEmail = EMAIL_PATTERN.matcher(redactedBearer).replaceAll("[redacted-email]");
        return truncate(redactedEmail, FAILURE_MESSAGE_LIMIT);
    }

    private static String normalize(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }
}
