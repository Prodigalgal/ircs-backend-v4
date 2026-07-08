package com.prodigalgal.ircs.notification.mail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.sql.DriverManager;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import java.util.Scanner;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class JdbcMailSendHistoryCleanupServiceTest {

    private static final Clock FIXED_CLOCK = Clock.fixed(
            Instant.parse("2026-06-09T12:00:00Z"),
            ZoneOffset.UTC);

    @Test
    void deletesOnlyRowsOlderThanRetentionCutoff() throws Exception {
        String url = "jdbc:h2:mem:notification_mail_send_history_cleanup_cutoff;MODE=PostgreSQL;DB_CLOSE_DELAY=-1";
        createHistoryTable(url);
        insertHistory(url, "old-sent", "sent", Instant.parse("2025-01-01T00:00:00Z"));
        insertHistory(url, "fresh-sent", "sent", Instant.parse("2026-06-01T00:00:00Z"));

        JdbcMailSendHistoryCleanupService service = service(url);
        MailSendHistoryCleanupResult result = service.cleanup(180, List.of(), 500, 20);

        assertThat(result.success()).isTrue();
        assertThat(result.deletedRows()).isEqualTo(1);
        assertThat(countByCorrelation(url, "old-sent")).isZero();
        assertThat(countByCorrelation(url, "fresh-sent")).isEqualTo(1);
        assertThat(archiveCount(url, "notification_mail_send_history")).isEqualTo(1);
    }

    @Test
    void honorsStatusWhitelistWhenConfigured() throws Exception {
        String url = "jdbc:h2:mem:notification_mail_send_history_cleanup_status;MODE=PostgreSQL;DB_CLOSE_DELAY=-1";
        createHistoryTable(url);
        insertHistory(url, "old-skipped", "skipped", Instant.parse("2025-01-01T00:00:00Z"));
        insertHistory(url, "old-sent", "sent", Instant.parse("2025-01-01T00:00:00Z"));

        JdbcMailSendHistoryCleanupService service = service(url);
        MailSendHistoryCleanupResult result = service.cleanup(180, List.of(" SKIPPED "), 500, 20);

        assertThat(result.success()).isTrue();
        assertThat(result.deletedRows()).isEqualTo(1);
        assertThat(countByCorrelation(url, "old-skipped")).isZero();
        assertThat(countByCorrelation(url, "old-sent")).isEqualTo(1);
        assertThat(archiveCount(url, "notification_mail_send_history")).isEqualTo(1);
    }

    @Test
    void stopsAtConfiguredBatchLimit() throws Exception {
        String url = "jdbc:h2:mem:notification_mail_send_history_cleanup_batch;MODE=PostgreSQL;DB_CLOSE_DELAY=-1";
        createHistoryTable(url);
        insertHistory(url, "old-1", "failed", Instant.parse("2025-01-01T00:00:00Z"));
        insertHistory(url, "old-2", "failed", Instant.parse("2025-01-02T00:00:00Z"));
        insertHistory(url, "old-3", "failed", Instant.parse("2025-01-03T00:00:00Z"));

        JdbcMailSendHistoryCleanupService service = service(url);
        MailSendHistoryCleanupResult result = service.cleanup(180, List.of(), 2, 1);

        assertThat(result.success()).isTrue();
        assertThat(result.deletedRows()).isEqualTo(2);
        assertThat(result.batches()).isEqualTo(1);
        assertThat(totalCount(url)).isEqualTo(1);
        assertThat(archiveCount(url, "notification_mail_send_history")).isEqualTo(2);
    }

    @Test
    void dryRunCountsBoundedCandidatesWithoutDeletingRows() throws Exception {
        String url = "jdbc:h2:mem:notification_mail_send_history_cleanup_dryrun;MODE=PostgreSQL;DB_CLOSE_DELAY=-1";
        createHistoryTable(url);
        insertHistory(url, "old-1", "failed", Instant.parse("2025-01-01T00:00:00Z"));
        insertHistory(url, "old-2", "failed", Instant.parse("2025-01-02T00:00:00Z"));
        insertHistory(url, "old-3", "failed", Instant.parse("2025-01-03T00:00:00Z"));

        JdbcMailSendHistoryCleanupService service = service(url);
        MailSendHistoryCleanupResult result = service.cleanup(180, List.of(), 2, 1, true, 0);

        assertThat(result.success()).isTrue();
        assertThat(result.dryRun()).isTrue();
        assertThat(result.deletedRows()).isZero();
        assertThat(result.candidateRows()).isEqualTo(2);
        assertThat(totalCount(url)).isEqualTo(3);
        assertThat(archiveCount(url, "notification_mail_send_history")).isZero();
    }

    @Test
    void missingDatasourceReturnsNoopResult() {
        JdbcMailSendHistoryCleanupService service =
                new JdbcMailSendHistoryCleanupService("", null, null, FIXED_CLOCK);

        MailSendHistoryCleanupResult result = service.cleanup(180, List.of(), 500, 20);

        assertThat(result.success()).isFalse();
        assertThat(result.deletedRows()).isZero();
        assertThat(result.reason()).isEqualTo("datasource-url-missing");
    }

    @Test
    void refusesUnsafeRetentionAndBatchValues() {
        JdbcMailSendHistoryCleanupService service = service(
                "jdbc:h2:mem:notification_mail_send_history_cleanup_invalid;MODE=PostgreSQL;DB_CLOSE_DELAY=-1");

        assertThrows(IllegalArgumentException.class, () -> service.cleanup(0, List.of(), 500, 20));
        assertThrows(IllegalArgumentException.class, () -> service.cleanup(180, List.of(), 0, 20));
        assertThrows(IllegalArgumentException.class, () -> service.cleanup(180, List.of(), 500, 0));
    }

    private static JdbcMailSendHistoryCleanupService service(String url) {
        return new JdbcMailSendHistoryCleanupService(url, null, null, FIXED_CLOCK);
    }

    private static void createHistoryTable(String url) throws Exception {
        try (var connection = DriverManager.getConnection(url);
             var statement = connection.createStatement();
             var stream = Objects.requireNonNull(
                     JdbcMailSendHistoryCleanupServiceTest.class.getResourceAsStream(
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

    private static void insertHistory(String url, String correlationId, String status, Instant createdAt)
            throws Exception {
        try (var connection = DriverManager.getConnection(url);
             var statement = connection.prepareStatement("""
                     insert into notification_mail_send_history (
                         id, created_at, updated_at, version, correlation_id, recipient, subject,
                         template_code, delivery_mode, status
                     ) values (?, ?, ?, 0, ?, 'codex@example.invalid', 'subject', 'mail/test', 'sink', ?)
                     """)) {
            statement.setObject(1, UUID.randomUUID());
            statement.setTimestamp(2, Timestamp.from(createdAt));
            statement.setTimestamp(3, Timestamp.from(createdAt));
            statement.setString(4, correlationId);
            statement.setString(5, status);
            statement.executeUpdate();
        }
    }

    private static long countByCorrelation(String url, String correlationId) throws Exception {
        try (var connection = DriverManager.getConnection(url);
             var statement = connection.prepareStatement("""
                     select count(*) from notification_mail_send_history where correlation_id = ?
                     """)) {
            statement.setString(1, correlationId);
            try (var result = statement.executeQuery()) {
                result.next();
                return result.getLong(1);
            }
        }
    }

    private static long totalCount(String url) throws Exception {
        try (var connection = DriverManager.getConnection(url);
             var result = connection.createStatement().executeQuery(
                     "select count(*) from notification_mail_send_history")) {
            result.next();
            return result.getLong(1);
        }
    }

    private static long archiveCount(String url, String sourceTable) throws Exception {
        try (var connection = DriverManager.getConnection(url);
             var statement = connection.prepareStatement("""
                     select count(*) from audit_archive_entries where source_table = ?
                     """)) {
            statement.setString(1, sourceTable);
            try (var result = statement.executeQuery()) {
                result.next();
                return result.getLong(1);
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
