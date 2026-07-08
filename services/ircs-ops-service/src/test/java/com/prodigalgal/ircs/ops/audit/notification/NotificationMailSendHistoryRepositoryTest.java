package com.prodigalgal.ircs.ops.audit.notification;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class NotificationMailSendHistoryRepositoryTest {

    @Test
    void filtersSendHistoryByStatusTypeAndTime() {
        Fixture fixture = fixture("filter");
        Instant base = Instant.parse("2026-06-08T10:00:00Z");
        insert(fixture.jdbcTemplate(), base, "mail-001", "codex@example.invalid", "mail/activation",
                "sink", "sent", null, null);
        insert(fixture.jdbcTemplate(), base.plusSeconds(30), "mail-002", "ops@example.invalid", "mail/reset",
                "fake", "failed", "java.lang.IllegalStateException", "redacted");
        insert(fixture.jdbcTemplate(), base.minusSeconds(7200), "mail-old", "codex@example.invalid", "mail/activation",
                "sink", "sent", null, null);

        Page<NotificationMailSendHistoryResponse> page = fixture.repository().findAll(
                PageRequest.of(0, 10, Sort.by(Sort.Direction.ASC, "createdAt")),
                "SENT",
                "SINK",
                "activation",
                "mail-",
                "codex",
                base.minusSeconds(60),
                base.plusSeconds(60));

        assertEquals(1, page.getTotalElements());
        NotificationMailSendHistoryResponse row = page.getContent().getFirst();
        assertEquals("mail-001", row.correlationId());
        assertEquals("codex@example.invalid", row.recipient());
        assertEquals("mail/activation", row.templateCode());
        assertEquals("SINK", row.deliveryMode());
        assertEquals("SENT", row.status());
    }

    @Test
    void summarizesSendHistoryWithStatusSemantics() {
        Fixture fixture = fixture("summary");
        Instant since = Instant.parse("2026-06-08T00:00:00Z");
        insert(fixture.jdbcTemplate(), since.plusSeconds(60), "mail-sent", "codex@example.invalid",
                "mail/activation", "sink", "sent", null, null);
        insert(fixture.jdbcTemplate(), since.plusSeconds(120), "mail-failed", "ops@example.invalid",
                "mail/reset", "fake", "failed", "java.lang.IllegalStateException", "redacted");
        insert(fixture.jdbcTemplate(), since.plusSeconds(180), "mail-skipped", "disabled@example.invalid",
                "mail/reset", "sink", "skipped", "mail_disabled", null);
        insert(fixture.jdbcTemplate(), since.minusSeconds(60), "mail-old", "old@example.invalid",
                "mail/reset", "sink", "failed", "java.lang.IllegalStateException", "redacted");

        NotificationMailSendHistorySummaryResponse summary = fixture.repository().summarize(since);

        assertEquals(3, summary.totalLast24h());
        assertEquals(1, summary.sentLast24h());
        assertEquals(1, summary.failedLast24h());
        assertEquals(1, summary.skippedLast24h());
        assertTrue(summary.sentSemantics().contains("不代表真实 SMTP 最终投递成功"));
    }

    @Test
    void estimatesUnfilteredListTotal() {
        Fixture fixture = fixture("estimated_total");
        Instant base = Instant.parse("2026-06-08T10:00:00Z");
        insert(fixture.jdbcTemplate(), base, "mail-001", "codex@example.invalid", "mail/activation",
                "sink", "sent", null, null);
        insert(fixture.jdbcTemplate(), base.plusSeconds(30), "mail-002", "ops@example.invalid", "mail/reset",
                "fake", "failed", "java.lang.IllegalStateException", "redacted");
        insert(fixture.jdbcTemplate(), base.plusSeconds(60), "mail-003", "admin@example.invalid", "mail/reset",
                "sink", "skipped", "mail_disabled", null);

        Page<NotificationMailSendHistoryResponse> page = fixture.repository().findAll(
                PageRequest.of(0, 2, Sort.by(Sort.Direction.DESC, "createdAt")),
                null,
                null,
                null,
                null,
                null,
                null,
                null);

        assertEquals(2, page.getContent().size());
        assertEquals(3, page.getTotalElements());
    }

    private record Fixture(NotificationMailSendHistoryRepository repository, JdbcTemplate jdbcTemplate) {
    }

    private static Fixture fixture(String name) {
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setUrl("jdbc:h2:mem:notification_mail_send_history_ops_" + name
                + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        dataSource.setUsername("sa");
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
        createTable(jdbcTemplate);
        return new Fixture(
                new NotificationMailSendHistoryRepository(new NamedParameterJdbcTemplate(dataSource)),
                jdbcTemplate);
    }

    private static void createTable(JdbcTemplate jdbcTemplate) {
        jdbcTemplate.execute("""
                create table notification_mail_send_history (
                    id uuid primary key,
                    created_at timestamp not null,
                    updated_at timestamp not null,
                    version bigint not null,
                    audit_class varchar(32) not null,
                    correlation_id varchar(128),
                    recipient varchar(320),
                    subject varchar(512),
                    template_code varchar(256),
                    delivery_mode varchar(32),
                    status varchar(32) not null,
                    credential_id uuid,
                    failure_code varchar(256),
                    failure_message text
                )
                """);
    }

    private static void insert(
            JdbcTemplate jdbcTemplate,
            Instant createdAt,
            String correlationId,
            String recipient,
            String templateCode,
            String deliveryMode,
            String status,
            String failureCode,
            String failureMessage) {
        jdbcTemplate.update("""
                        insert into notification_mail_send_history (
                            id, created_at, updated_at, version, audit_class, correlation_id, recipient, subject,
                            template_code, delivery_mode, status, credential_id, failure_code, failure_message
                        ) values (?, ?, ?, 0, 'SYSTEM', ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                UUID.randomUUID(),
                Timestamp.from(createdAt),
                Timestamp.from(createdAt),
                correlationId,
                recipient,
                "subject",
                templateCode,
                deliveryMode,
                status,
                null,
                failureCode,
                failureMessage);
    }
}
