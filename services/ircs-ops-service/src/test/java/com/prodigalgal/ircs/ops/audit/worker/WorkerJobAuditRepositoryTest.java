package com.prodigalgal.ircs.ops.audit.worker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.prodigalgal.ircs.common.config.RuntimeConfigService;
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

class WorkerJobAuditRepositoryTest {

    @Test
    void filtersWorkerJobAuditBySourceStatusCorrelationAndTime() {
        Fixture fixture = fixture("filter");
        Instant base = Instant.parse("2026-06-08T10:00:00Z");
        insert(fixture.jdbcTemplate(), "ircs-notification-worker", "queue-consumer", "notification.mail",
                "mail-abc-001", "failed", 120L, "IllegalStateException", base);
        insert(fixture.jdbcTemplate(), "ircs-normalization-worker", "queue-consumer", "normalize.video",
                "normalize-002", "succeeded", 40L, null, base.plusSeconds(30));
        insert(fixture.jdbcTemplate(), "ircs-notification-worker", "queue-consumer", "notification.mail",
                "mail-old-003", "failed", 80L, "IllegalStateException", base.minusSeconds(7200));

        Page<WorkerJobAuditEventResponse> page = fixture.repository().findAll(
                PageRequest.of(0, 10, Sort.by(Sort.Direction.ASC, "durationMs")),
                "notification",
                "queue-consumer",
                "mail",
                "abc",
                "failed",
                "IllegalState",
                base.minusSeconds(60),
                base.plusSeconds(60));

        assertEquals(1, page.getTotalElements());
        WorkerJobAuditEventResponse row = page.getContent().getFirst();
        assertEquals("ircs-notification-worker", row.jobSource());
        assertEquals("queue-consumer", row.jobType());
        assertEquals("notification.mail", row.jobName());
        assertEquals("mail-abc-001", row.correlationId());
        assertEquals("failed", row.status());
        assertEquals(120L, row.durationMs());
        assertEquals("IllegalStateException", row.errorClass());
    }

    @Test
    void summarizesWorkerJobAuditSinceBoundary() {
        Fixture fixture = fixture("summary");
        Instant since = Instant.parse("2026-06-08T00:00:00Z");
        insert(fixture.jdbcTemplate(), "ircs-notification-worker", "queue-consumer", "notification.mail",
                "mail-1", "succeeded", 20L, null, since.plusSeconds(60));
        insert(fixture.jdbcTemplate(), "ircs-notification-worker", "queue-consumer", "notification.mail",
                "mail-2", "failed", 90L, "IllegalStateException", since.plusSeconds(120));
        insert(fixture.jdbcTemplate(), "ircs-notification-worker", "queue-consumer", "notification.mail",
                "mail-old", "failed", 300L, "IllegalStateException", since.minusSeconds(60));

        WorkerJobAuditSummaryResponse summary = fixture.repository().summarize(since);

        assertEquals(2, summary.totalLast24h());
        assertEquals(1, summary.failedLast24h());
        assertEquals(1, summary.succeededLast24h());
        assertEquals(90L, summary.maxDurationMsLast24h());
    }

    private record Fixture(WorkerJobAuditRepository repository, JdbcTemplate jdbcTemplate) {
    }

    private static Fixture fixture(String name) {
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setUrl("jdbc:h2:mem:worker_job_audit_" + name + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        dataSource.setUsername("sa");
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
        createTable(jdbcTemplate);
        RuntimeConfigService runtimeConfig = mock(RuntimeConfigService.class);
        when(runtimeConfig.boundedIntValue(
                "app.ops.worker-job-audit.summary-sample-limit",
                50_000,
                1,
                1_000_000))
                .thenReturn(50_000);
        return new Fixture(
                new WorkerJobAuditRepository(
                        new NamedParameterJdbcTemplate(dataSource),
                        runtimeConfig),
                jdbcTemplate);
    }

    private static void createTable(JdbcTemplate jdbcTemplate) {
        jdbcTemplate.execute("""
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
                    error_message varchar(1024)
                )
                """);
    }

    private static void insert(
            JdbcTemplate jdbcTemplate,
            String jobSource,
            String jobType,
            String jobName,
            String correlationId,
            String status,
            Long durationMs,
            String errorClass,
            Instant createdAt) {
        jdbcTemplate.update("""
                        insert into worker_job_audit_events (
                            id, created_at, updated_at, version, audit_class, job_source, job_type, job_name,
                            correlation_id, status, duration_ms, error_class, error_message
                        ) values (?, ?, ?, 0, 'SYSTEM', ?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                UUID.randomUUID(),
                Timestamp.from(createdAt),
                Timestamp.from(createdAt),
                jobSource,
                jobType,
                jobName,
                correlationId,
                status,
                durationMs,
                errorClass,
                errorClass == null ? null : "redacted");
    }
}
