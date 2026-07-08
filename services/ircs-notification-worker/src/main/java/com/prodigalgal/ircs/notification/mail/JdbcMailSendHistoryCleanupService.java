package com.prodigalgal.ircs.notification.mail;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import com.prodigalgal.ircs.common.id.IrcsUuidGenerators;
import lombok.extern.slf4j.Slf4j;

@Slf4j
class JdbcMailSendHistoryCleanupService {

    static final int DEFAULT_RETENTION_DAYS = 90;
    static final int DEFAULT_BATCH_SIZE = 500;
    static final int DEFAULT_MAX_BATCHES = 20;
    static final int MAX_BATCH_SIZE = 5000;
    static final int MAX_RATE_LIMIT_DELAY_MS = 60_000;

    private static final String SELECT_WITHOUT_STATUS_SQL = """
            select id, created_at, correlation_id, recipient, subject, template_code,
                   delivery_mode, status, credential_id, failure_code, failure_message
              from notification_mail_send_history
             where created_at < ?
             order by created_at asc, id asc
             limit ?
            """;
    private static final String COUNT_WITHOUT_STATUS_SQL = """
            select count(*)
              from (
                    select id
                      from notification_mail_send_history
                     where created_at < ?
                     order by created_at asc, id asc
                     limit ?
              ) candidate
            """;
    private static final String COUNT_WITH_STATUS_SQL_TEMPLATE = """
            select count(*)
              from (
                    select id
                      from notification_mail_send_history
                     where created_at < ?
                       and lower(status) in (%s)
                     order by created_at asc, id asc
                     limit ?
              ) candidate
            """;
    private static final String SELECT_WITH_STATUS_SQL_TEMPLATE = """
            select id, created_at, correlation_id, recipient, subject, template_code,
                   delivery_mode, status, credential_id, failure_code, failure_message
              from notification_mail_send_history
             where created_at < ?
               and lower(status) in (%s)
             order by created_at asc, id asc
             limit ?
            """;
    private static final String INSERT_ARCHIVE_SQL = """
            insert into audit_archive_entries (
                id, created_at, updated_at, version, audit_class, archive_type, source_table,
                source_id, source_created_at, retention_days, reason, payload
            ) values (?, now(), now(), 0, 'SYSTEM', 'RETENTION_PURGE',
                      'notification_mail_send_history', ?, ?, ?, ?, ?)
            """;
    private static final String DELETE_BY_ID_SQL =
            "delete from notification_mail_send_history where id = ?";

    private final String datasourceUrl;
    private final String username;
    private final String password;
    private final Clock clock;

    JdbcMailSendHistoryCleanupService(
            String datasourceUrl,
            String username,
            String password,
            Clock clock) {
        this.datasourceUrl = normalize(datasourceUrl);
        this.username = normalize(username);
        this.password = password == null ? "" : password;
        this.clock = clock == null ? Clock.systemUTC() : clock;
    }

    MailSendHistoryCleanupResult cleanup(
            int retentionDays,
            Collection<String> statuses,
            int batchSize,
            int maxBatches) {
        return cleanup(retentionDays, statuses, batchSize, maxBatches, false, 0);
    }

    MailSendHistoryCleanupResult cleanup(
            int retentionDays,
            Collection<String> statuses,
            int batchSize,
            int maxBatches,
            boolean dryRun,
            int rateLimitDelayMs) {
        if (datasourceUrl == null) {
            return new MailSendHistoryCleanupResult(false, null, 0, 0, dryRun, 0, "datasource-url-missing");
        }
        if (retentionDays < 1) {
            throw new IllegalArgumentException("retentionDays must be >= 1");
        }
        if (batchSize < 1) {
            throw new IllegalArgumentException("batchSize must be >= 1");
        }
        if (maxBatches < 1) {
            throw new IllegalArgumentException("maxBatches must be >= 1");
        }

        int effectiveBatchSize = Math.min(batchSize, MAX_BATCH_SIZE);
        List<String> normalizedStatuses = normalizeStatuses(statuses);
        Instant cutoff = clock.instant().minus(Duration.ofDays(retentionDays));
        int deleted = 0;
        int batches = 0;
        int effectiveRateLimitDelayMs = Math.min(Math.max(rateLimitDelayMs, 0), MAX_RATE_LIMIT_DELAY_MS);

        try (Connection connection = connection()) {
            if (dryRun) {
                int candidateRows = countCandidates(
                        connection,
                        cutoff,
                        normalizedStatuses,
                        Math.multiplyExact(effectiveBatchSize, maxBatches));
                return new MailSendHistoryCleanupResult(
                        true, cutoff, 0, 0, true, candidateRows, "dry-run");
            }
            for (int i = 0; i < maxBatches; i++) {
                int current = archiveAndDeleteBatch(
                        connection,
                        cutoff,
                        normalizedStatuses,
                        effectiveBatchSize,
                        retentionDays);
                if (current == 0) {
                    break;
                }
                deleted += current;
                batches++;
                if (current < effectiveBatchSize) {
                    break;
                }
                if (i < maxBatches - 1) {
                    sleepBetweenBatches(effectiveRateLimitDelayMs);
                }
            }
            return new MailSendHistoryCleanupResult(true, cutoff, deleted, batches, false, 0, "completed");
        } catch (SQLException | RuntimeException ex) {
            log.warn("Notification mail send history cleanup failed: {}", ex.getMessage());
            return new MailSendHistoryCleanupResult(
                    false, cutoff, deleted, batches, dryRun, 0, ex.getClass().getSimpleName());
        }
    }

    private int countCandidates(
            Connection connection,
            Instant cutoff,
            List<String> statuses,
            int limit) throws SQLException {
        boolean filterByStatus = !statuses.isEmpty();
        String sql = filterByStatus
                ? COUNT_WITH_STATUS_SQL_TEMPLATE.formatted(placeholders(statuses.size()))
                : COUNT_WITHOUT_STATUS_SQL;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setTimestamp(1, Timestamp.from(cutoff));
            if (filterByStatus) {
                for (int i = 0; i < statuses.size(); i++) {
                    statement.setString(i + 2, statuses.get(i));
                }
                statement.setInt(statuses.size() + 2, limit);
            } else {
                statement.setInt(2, limit);
            }
            try (var result = statement.executeQuery()) {
                result.next();
                return result.getInt(1);
            }
        }
    }

    private int archiveAndDeleteBatch(
            Connection connection,
            Instant cutoff,
            List<String> statuses,
            int batchSize,
            int retentionDays) throws SQLException {
        List<ArchiveCandidate> candidates = selectCandidates(connection, cutoff, statuses, batchSize);
        int deleted = 0;
        for (ArchiveCandidate candidate : candidates) {
            archiveCandidate(connection, candidate, retentionDays);
            deleted += deleteCandidate(connection, candidate.id());
        }
        return deleted;
    }

    private List<ArchiveCandidate> selectCandidates(
            Connection connection,
            Instant cutoff,
            List<String> statuses,
            int batchSize) throws SQLException {
        boolean filterByStatus = !statuses.isEmpty();
        String sql = filterByStatus
                ? SELECT_WITH_STATUS_SQL_TEMPLATE.formatted(placeholders(statuses.size()))
                : SELECT_WITHOUT_STATUS_SQL;
        List<ArchiveCandidate> candidates = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setTimestamp(1, Timestamp.from(cutoff));
            if (filterByStatus) {
                for (int i = 0; i < statuses.size(); i++) {
                    statement.setString(i + 2, statuses.get(i));
                }
                statement.setInt(statuses.size() + 2, batchSize);
            } else {
                statement.setInt(2, batchSize);
            }
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    candidates.add(new ArchiveCandidate(
                            uuid(rs.getObject("id")),
                            toInstant(rs.getTimestamp("created_at")),
                            rs.getString("correlation_id"),
                            rs.getString("recipient"),
                            rs.getString("subject"),
                            rs.getString("template_code"),
                            rs.getString("delivery_mode"),
                            rs.getString("status"),
                            uuidOrNull(rs.getObject("credential_id")),
                            rs.getString("failure_code"),
                            rs.getString("failure_message")));
                }
            }
        }
        return candidates;
    }

    private void archiveCandidate(
            Connection connection,
            ArchiveCandidate candidate,
            int retentionDays) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(INSERT_ARCHIVE_SQL)) {
            statement.setObject(1, IrcsUuidGenerators.nextId());
            statement.setObject(2, candidate.id());
            statement.setTimestamp(3, candidate.createdAt() == null ? null : Timestamp.from(candidate.createdAt()));
            statement.setInt(4, retentionDays);
            statement.setString(5, "mail-send-history-retention");
            statement.setString(6, candidate.toPayload());
            statement.executeUpdate();
        }
    }

    private int deleteCandidate(Connection connection, UUID id) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(DELETE_BY_ID_SQL)) {
            statement.setObject(1, id);
            return statement.executeUpdate();
        }
    }

    private Connection connection() throws SQLException {
        if (username == null) {
            return DriverManager.getConnection(datasourceUrl);
        }
        return DriverManager.getConnection(datasourceUrl, username, password);
    }

    private static void sleepBetweenBatches(int rateLimitDelayMs) {
        if (rateLimitDelayMs <= 0) {
            return;
        }
        try {
            Thread.sleep(rateLimitDelayMs);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("cleanup interrupted during rate-limit delay", ex);
        }
    }

    private static List<String> normalizeStatuses(Collection<String> statuses) {
        if (statuses == null || statuses.isEmpty()) {
            return List.of();
        }
        List<String> normalized = new ArrayList<>();
        for (String status : statuses) {
            String value = normalize(status);
            if (value != null) {
                normalized.add(value.toLowerCase(Locale.ROOT));
            }
        }
        return normalized;
    }

    private static String placeholders(int count) {
        return String.join(",", java.util.Collections.nCopies(count, "?"));
    }

    private static String normalize(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static Instant toInstant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }

    private static UUID uuid(Object value) {
        UUID uuid = uuidOrNull(value);
        if (uuid == null) {
            throw new IllegalArgumentException("id is required");
        }
        return uuid;
    }

    private static UUID uuidOrNull(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof UUID uuid) {
            return uuid;
        }
        return UUID.fromString(value.toString());
    }

    private static String json(String value) {
        if (value == null) {
            return "null";
        }
        return "\"" + value
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\r", "\\r")
                .replace("\n", "\\n")
                + "\"";
    }

    private record ArchiveCandidate(
            UUID id,
            Instant createdAt,
            String correlationId,
            String recipient,
            String subject,
            String templateCode,
            String deliveryMode,
            String status,
            UUID credentialId,
            String failureCode,
            String failureMessage) {

        String toPayload() {
            return "{"
                    + "\"id\":" + json(id == null ? null : id.toString()) + ","
                    + "\"createdAt\":" + json(createdAt == null ? null : createdAt.toString()) + ","
                    + "\"correlationId\":" + json(correlationId) + ","
                    + "\"recipient\":" + json(recipient) + ","
                    + "\"subject\":" + json(subject) + ","
                    + "\"templateCode\":" + json(templateCode) + ","
                    + "\"deliveryMode\":" + json(deliveryMode) + ","
                    + "\"status\":" + json(status) + ","
                    + "\"credentialId\":" + json(credentialId == null ? null : credentialId.toString()) + ","
                    + "\"failureCode\":" + json(failureCode) + ","
                    + "\"failureMessage\":" + json(failureMessage)
                    + "}";
        }
    }
}
