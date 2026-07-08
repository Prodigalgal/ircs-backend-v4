package com.prodigalgal.ircs.notification.mail;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.StringUtils;

@Slf4j
class MailCredentialUsageRepository {

    private static final String COUNT_SENT_SQL = """
            select count(*)
              from notification_mail_send_history
             where credential_id = ?
               and status = 'sent'
               and created_at >= ?
            """;

    private final String datasourceUrl;
    private final String username;
    private final String password;

    MailCredentialUsageRepository(String datasourceUrl, String username, String password) {
        this.datasourceUrl = normalize(datasourceUrl);
        this.username = normalize(username);
        this.password = password == null ? "" : password;
    }

    long countSentSince(UUID credentialId, Instant since) {
        if (credentialId == null || since == null || datasourceUrl == null) {
            return 0L;
        }
        try (Connection connection = connection();
             PreparedStatement statement = connection.prepareStatement(COUNT_SENT_SQL)) {
            statement.setObject(1, credentialId);
            statement.setTimestamp(2, Timestamp.from(since));
            try (var result = statement.executeQuery()) {
                return result.next() ? result.getLong(1) : 0L;
            }
        } catch (SQLException | RuntimeException ex) {
            log.warn("Mail credential usage query failed: {}", ex.getMessage());
            return 0L;
        }
    }

    private Connection connection() throws SQLException {
        if (username == null) {
            return DriverManager.getConnection(datasourceUrl);
        }
        return DriverManager.getConnection(datasourceUrl, username, password);
    }

    private static String normalize(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }
}
