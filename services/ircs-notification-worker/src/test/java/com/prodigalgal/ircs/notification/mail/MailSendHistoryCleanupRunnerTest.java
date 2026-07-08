package com.prodigalgal.ircs.notification.mail;

import static org.assertj.core.api.Assertions.assertThat;

import com.prodigalgal.ircs.common.audit.WorkerJobAuditWriter;
import java.sql.DriverManager;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Collection;
import org.junit.jupiter.api.Test;

class MailSendHistoryCleanupRunnerTest {

    private static final Clock FIXED_CLOCK = Clock.fixed(
            Instant.parse("2026-06-09T12:00:00Z"),
            ZoneOffset.UTC);

    @Test
    void defaultOffDoesNotCallCleanupOrAudit() throws Exception {
        String auditUrl = "jdbc:h2:mem:cleanup_runner_default_off_audit;MODE=PostgreSQL;DB_CLOSE_DELAY=-1";
        createAuditTable(auditUrl);
        FakeCleanupService cleanupService = new FakeCleanupService();

        runner(false, true, false, cleanupService, auditWriter(auditUrl)).run(null);

        assertThat(cleanupService.calls).isZero();
        assertThat(auditCount(auditUrl)).isZero();
    }

    @Test
    void dryRunCallsCleanupAndRecordsSucceededAudit() throws Exception {
        String auditUrl = "jdbc:h2:mem:cleanup_runner_dry_run_audit;MODE=PostgreSQL;DB_CLOSE_DELAY=-1";
        createAuditTable(auditUrl);
        FakeCleanupService cleanupService = new FakeCleanupService();
        cleanupService.result = new MailSendHistoryCleanupResult(
                true,
                Instant.parse("2025-12-11T12:00:00Z"),
                0,
                0,
                true,
                2,
                "dry-run");

        runner(true, true, false, cleanupService, auditWriter(auditUrl)).run(null);

        assertThat(cleanupService.calls).isEqualTo(1);
        assertThat(cleanupService.dryRun).isTrue();
        assertThat(cleanupService.rateLimitDelayMs).isZero();
        assertAudit(auditUrl, "succeeded", "dry-run", null);
    }

    @Test
    void refusesRealExecuteWithoutExplicitExecuteGateAndAuditsFailure() throws Exception {
        String auditUrl = "jdbc:h2:mem:cleanup_runner_refusal_audit;MODE=PostgreSQL;DB_CLOSE_DELAY=-1";
        createAuditTable(auditUrl);
        FakeCleanupService cleanupService = new FakeCleanupService();

        runner(true, false, false, cleanupService, auditWriter(auditUrl)).run(null);

        assertThat(cleanupService.calls).isZero();
        assertAudit(
                auditUrl,
                "failed",
                "execute-gate-refused",
                "real cleanup refused: APP_MAIL_SEND_HISTORY_CLEANUP_EXECUTE_ENABLED must be true");
    }

    @Test
    void executesOnlyWhenDoubleGateIsExplicitAndRecordsAudit() throws Exception {
        String auditUrl = "jdbc:h2:mem:cleanup_runner_execute_audit;MODE=PostgreSQL;DB_CLOSE_DELAY=-1";
        createAuditTable(auditUrl);
        FakeCleanupService cleanupService = new FakeCleanupService();
        cleanupService.result = new MailSendHistoryCleanupResult(
                true,
                Instant.parse("2025-12-11T12:00:00Z"),
                1,
                1,
                false,
                0,
                "completed");

        runner(true, false, true, cleanupService, auditWriter(auditUrl)).run(null);

        assertThat(cleanupService.calls).isEqualTo(1);
        assertThat(cleanupService.dryRun).isFalse();
        assertAudit(auditUrl, "succeeded", "execute", null);
    }

    private static MailSendHistoryCleanupRunner runner(
            boolean enabled,
            boolean dryRun,
            boolean executeEnabled,
            FakeCleanupService cleanupService,
            WorkerJobAuditWriter auditWriter) {
        return new MailSendHistoryCleanupRunner(
                enabled,
                dryRun,
                executeEnabled,
                180,
                " failed ",
                50,
                5,
                0,
                false,
                cleanupService,
                auditWriter,
                null);
    }

    private static WorkerJobAuditWriter auditWriter(String url) {
        return new WorkerJobAuditWriter(true, url, null, null, "ircs-notification-worker");
    }

    private static long auditCount(String url) throws Exception {
        try (var connection = DriverManager.getConnection(url);
             var result = connection.createStatement()
                     .executeQuery("select count(*) from worker_job_audit_events")) {
            result.next();
            return result.getLong(1);
        }
    }

    private static void assertAudit(
            String url,
            String status,
            String correlationId,
            String errorMessage) throws Exception {
        try (var connection = DriverManager.getConnection(url);
             var result = connection.createStatement().executeQuery("""
                     select job_source, job_type, job_name, correlation_id, status, error_message
                       from worker_job_audit_events
            """)) {
            assertThat(result.next()).isTrue();
            assertThat(result.getString("job_source"))
                    .startsWith("ircs-notification-worker@")
                    .contains("#");
            assertThat(result.getString("job_type")).isEqualTo(MailSendHistoryCleanupRunner.JOB_TYPE);
            assertThat(result.getString("job_name")).isEqualTo(MailSendHistoryCleanupRunner.JOB_NAME);
            assertThat(result.getString("correlation_id")).isEqualTo(correlationId);
            assertThat(result.getString("status")).isEqualTo(status);
            assertThat(result.getString("error_message")).isEqualTo(errorMessage);
            assertThat(result.next()).isFalse();
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

    private static final class FakeCleanupService extends JdbcMailSendHistoryCleanupService {

        private int calls;
        private boolean dryRun;
        private int rateLimitDelayMs;
        private MailSendHistoryCleanupResult result = new MailSendHistoryCleanupResult(
                true,
                Instant.parse("2025-12-11T12:00:00Z"),
                0,
                0,
                true,
                0,
                "dry-run");

        private FakeCleanupService() {
            super(null, null, null, FIXED_CLOCK);
        }

        @Override
        MailSendHistoryCleanupResult cleanup(
                int retentionDays,
                Collection<String> statuses,
                int batchSize,
                int maxBatches,
                boolean dryRun,
                int rateLimitDelayMs) {
            this.calls++;
            this.dryRun = dryRun;
            this.rateLimitDelayMs = rateLimitDelayMs;
            return result;
        }
    }
}
