package com.prodigalgal.ircs.search.audit;

import static org.assertj.core.api.Assertions.assertThat;

import com.prodigalgal.ircs.common.audit.AuditClass;
import com.prodigalgal.ircs.common.audit.AuditReplicationWorkPayload;
import com.prodigalgal.ircs.search.document.AuditEventSearchDocument;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class AuditEsReplicationRepositoryTest {

    private JdbcTemplate jdbcTemplate;
    private AuditEsReplicationRepository repository;

    @BeforeEach
    void setUp() {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:audit-es-" + UUID.randomUUID()
                + ";MODE=PostgreSQL;DATABASE_TO_UPPER=false;DB_CLOSE_DELAY=-1");
        this.jdbcTemplate = new JdbcTemplate(dataSource);
        this.repository = new AuditEsReplicationRepository(jdbcTemplate);
        createTables();
    }

    @Test
    void mapsRequestAuditRecord() {
        UUID id = UUID.randomUUID();
        Instant now = Instant.parse("2026-06-19T01:02:03Z");
        jdbcTemplate.update("""
                insert into request_audit_logs
                    (id, created_at, updated_at, audit_class, username, method, path, query_string,
                     status_code, success, duration_ms, client_ip, user_agent, trace_id, error_message)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                id, Timestamp.from(now), Timestamp.from(now), "BEHAVIOR", "admin", "GET", "/api/test", "a=1",
                200, true, 15L, "127.0.0.1", "JUnit", "trace-1", null);

        Optional<AuditEventSearchDocument> result = repository.findDocument(new AuditReplicationWorkPayload(
                AuditClass.SYSTEM,
                AuditEsReplicationRepository.REQUEST_AUDIT_LOGS,
                id,
                "UPSERT",
                null));

        assertThat(result).isPresent();
        AuditEventSearchDocument doc = result.orElseThrow();
        assertThat(doc.getId()).isEqualTo(AuditEsReplicationRepository.REQUEST_AUDIT_LOGS + ":" + id);
        assertThat(doc.getRecordType()).isEqualTo("REQUEST");
        assertThat(doc.getAuditClass()).isEqualTo("SYSTEM");
        assertThat(doc.getStatus()).isEqualTo("SUCCESS");
        assertThat(doc.getPath()).isEqualTo("/api/test");
        assertThat(doc.getIndexedAt()).isNotNull();
    }

    @Test
    void mapsWorkerAuditRecord() {
        UUID id = UUID.randomUUID();
        Instant now = Instant.parse("2026-06-19T02:03:04Z");
        jdbcTemplate.update("""
                insert into worker_job_audit_events
                    (id, created_at, updated_at, audit_class, job_source, job_type, job_name,
                     correlation_id, status, duration_ms, error_class, error_message)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                id, Timestamp.from(now), Timestamp.from(now), "SYSTEM", "search", "runtime", "sync",
                "corr-1", "FAILED", 300L, "IllegalStateException", "es down");

        Optional<AuditEventSearchDocument> result = repository.findDocument(new AuditReplicationWorkPayload(
                null,
                AuditEsReplicationRepository.WORKER_JOB_AUDIT_EVENTS,
                id,
                "UPSERT",
                null));

        assertThat(result).isPresent();
        AuditEventSearchDocument doc = result.orElseThrow();
        assertThat(doc.getRecordType()).isEqualTo("WORKER_JOB");
        assertThat(doc.getAuditClass()).isEqualTo("SYSTEM");
        assertThat(doc.getStatus()).isEqualTo("FAILED");
        assertThat(doc.getJobSource()).isEqualTo("search");
        assertThat(doc.getErrorClass()).isEqualTo("IllegalStateException");
    }

    @Test
    void mapsMailAuditRecordAndIgnoresUnknownSource() {
        UUID id = UUID.randomUUID();
        UUID credentialId = UUID.randomUUID();
        Instant now = Instant.parse("2026-06-19T03:04:05Z");
        jdbcTemplate.update("""
                insert into notification_mail_send_history
                    (id, created_at, updated_at, audit_class, correlation_id, recipient, subject,
                     template_code, delivery_mode, status, credential_id, failure_code, failure_message)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                id, Timestamp.from(now), Timestamp.from(now), "SYSTEM", "corr-2", "user@example.com", "Hello",
                "template", "SMTP", "SUCCESS", credentialId, null, null);

        Optional<AuditEventSearchDocument> result = repository.findDocument(new AuditReplicationWorkPayload(
                AuditClass.SYSTEM,
                AuditEsReplicationRepository.NOTIFICATION_MAIL_SEND_HISTORY,
                id,
                "UPSERT",
                null));

        assertThat(result).isPresent();
        AuditEventSearchDocument doc = result.orElseThrow();
        assertThat(doc.getRecordType()).isEqualTo("MAIL");
        assertThat(doc.getRecipient()).isEqualTo("user@example.com");
        assertThat(doc.getCredentialId()).isEqualTo(credentialId.toString());
        assertThat(repository.findDocument(new AuditReplicationWorkPayload(
                AuditClass.SYSTEM,
                "unknown_table",
                id,
                "UPSERT",
                null))).isEmpty();
    }

    private void createTables() {
        jdbcTemplate.execute("""
                create table request_audit_logs (
                    id uuid primary key,
                    created_at timestamp,
                    updated_at timestamp,
                    audit_class varchar(32),
                    username varchar(128),
                    method varchar(16),
                    path varchar(512),
                    query_string varchar(1024),
                    status_code integer,
                    success boolean,
                    duration_ms bigint,
                    client_ip varchar(64),
                    user_agent varchar(512),
                    trace_id varchar(128),
                    error_message varchar(1024)
                )
                """);
        jdbcTemplate.execute("""
                create table worker_job_audit_events (
                    id uuid primary key,
                    created_at timestamp,
                    updated_at timestamp,
                    audit_class varchar(32),
                    job_source varchar(128),
                    job_type varchar(128),
                    job_name varchar(256),
                    correlation_id varchar(128),
                    status varchar(64),
                    duration_ms bigint,
                    error_class varchar(256),
                    error_message varchar(1024)
                )
                """);
        jdbcTemplate.execute("""
                create table notification_mail_send_history (
                    id uuid primary key,
                    created_at timestamp,
                    updated_at timestamp,
                    audit_class varchar(32),
                    correlation_id varchar(128),
                    recipient varchar(256),
                    subject varchar(512),
                    template_code varchar(128),
                    delivery_mode varchar(64),
                    status varchar(64),
                    credential_id uuid,
                    failure_code varchar(128),
                    failure_message varchar(1024)
                )
                """);
    }
}
