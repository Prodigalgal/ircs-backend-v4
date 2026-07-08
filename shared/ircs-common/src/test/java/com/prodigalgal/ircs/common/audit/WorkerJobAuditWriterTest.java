package com.prodigalgal.ircs.common.audit;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.prodigalgal.ircs.common.work.RuntimeWorkItem;
import com.prodigalgal.ircs.common.work.RuntimeWorkItemRequest;
import com.prodigalgal.ircs.common.work.RuntimeWorkQueue;
import com.prodigalgal.ircs.common.work.RuntimeWorkQueueCounts;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.sql.DriverManager;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class WorkerJobAuditWriterTest {

    @AfterEach
    void clearAuditReplicationPublisher() {
        AuditReplicationWorkDispatcher.clearForTests();
    }

    @Test
    void writesWorkerJobAuditEvent() throws Exception {
        String url = "jdbc:h2:mem:worker_job_audit;MODE=PostgreSQL;DB_CLOSE_DELAY=-1";
        createAuditTable(url);
        CapturingRuntimeWorkQueue queue = new CapturingRuntimeWorkQueue();
        AuditReplicationWorkDispatcher.register(new AuditReplicationWorkPublisher(queue, new ObjectMapper()));

        WorkerJobAuditWriter writer = new WorkerJobAuditWriter(
                true,
                url,
                null,
                null,
                "ircs-notification-worker",
                "notification-pod-1");

        writer.record(WorkerJobAuditEvent.succeeded(
                "queue_consumer",
                "notification.mail",
                "mail-task-1",
                Duration.ofMillis(42)));

        try (var connection = DriverManager.getConnection(url);
             var result = connection.createStatement().executeQuery("""
                     select audit_class, job_source, job_type, job_name, correlation_id, status, duration_ms,
                            error_class, error_message
                     from worker_job_audit_events
                     """)) {
            assertThat(result.next()).isTrue();
            assertThat(result.getString("audit_class")).isEqualTo("SYSTEM");
            assertThat(result.getString("job_source")).isEqualTo("notification-pod-1");
            assertThat(result.getString("job_type")).isEqualTo("queue_consumer");
            assertThat(result.getString("job_name")).isEqualTo("notification.mail");
            assertThat(result.getString("correlation_id")).isEqualTo("mail-task-1");
            assertThat(result.getString("status")).isEqualTo("succeeded");
            assertThat(result.getLong("duration_ms")).isEqualTo(42L);
            assertThat(result.getString("error_class")).isNull();
            assertThat(result.getString("error_message")).isNull();
            assertThat(result.next()).isFalse();
        }
        RuntimeWorkItemRequest request = queue.requests.getFirst();
        assertThat(request.taskType()).isEqualTo(AuditReplicationWorkTypes.ES_REPLICATION);
        assertThat(request.taskId()).startsWith("worker_job_audit_events:");
        assertThat(request.version()).isEqualTo("UPSERT");
        AuditReplicationWorkPayload payload = new ObjectMapper()
                .readValue(request.payload(), AuditReplicationWorkPayload.class);
        assertThat(payload.sourceTable()).isEqualTo("worker_job_audit_events");
        assertThat(payload.auditClass()).isEqualTo(AuditClass.SYSTEM);
    }

    @Test
    void defaultJobSourceContainsServiceNameAndInstanceIdentity() throws Exception {
        String url = "jdbc:h2:mem:worker_job_audit_instance_source;MODE=PostgreSQL;DB_CLOSE_DELAY=-1";
        createAuditTable(url);
        WorkerJobAuditWriter writer = new WorkerJobAuditWriter(
                true,
                url,
                null,
                null,
                "ircs-task-service");

        writer.record(WorkerJobAuditEvent.succeeded(
                "scheduler",
                "task.watchdog",
                null,
                Duration.ofMillis(1)));

        try (var connection = DriverManager.getConnection(url);
             var result = connection.createStatement()
                     .executeQuery("select job_source from worker_job_audit_events")) {
            assertThat(result.next()).isTrue();
            assertThat(result.getString("job_source"))
                    .startsWith("ircs-task-service@")
                    .contains("#")
                    .hasSizeLessThanOrEqualTo(128);
        }
    }

    @Test
    void redactsSensitiveErrorMessage() throws Exception {
        String url = "jdbc:h2:mem:worker_job_audit_redacted;MODE=PostgreSQL;DB_CLOSE_DELAY=-1";
        createAuditTable(url);
        WorkerJobAuditWriter writer = new WorkerJobAuditWriter(true, url, null, null, "worker");

        writer.record(WorkerJobAuditEvent.failed(
                "queue_consumer",
                "notification.mail",
                null,
                Duration.ofMillis(7),
                new RuntimeException("access_token=abc123 for codex@example.invalid")));

        try (var connection = DriverManager.getConnection(url);
             var result = connection.createStatement()
                     .executeQuery("select audit_class, error_class, error_message from worker_job_audit_events")) {
            assertThat(result.next()).isTrue();
            assertThat(result.getString("audit_class")).isEqualTo("SYSTEM");
            assertThat(result.getString("error_class")).isEqualTo("java.lang.RuntimeException");
            assertThat(result.getString("error_message"))
                    .isEqualTo("access_token=[redacted-token] for [redacted-email]");
        }
    }

    @Test
    void keepsNonSensitiveParserTokenDiagnostic() throws Exception {
        String url = "jdbc:h2:mem:worker_job_audit_parser_token;MODE=PostgreSQL;DB_CLOSE_DELAY=-1";
        createAuditTable(url);
        WorkerJobAuditWriter writer = new WorkerJobAuditWriter(true, url, null, null, "worker");

        writer.record(WorkerJobAuditEvent.failed(
                "queue_consumer",
                "scraper.task-page",
                "task-1",
                Duration.ofMillis(7),
                new RuntimeException("Unexpected character ('<' expected a valid value token 'null')")));

        try (var connection = DriverManager.getConnection(url);
             var result = connection.createStatement()
                     .executeQuery("select error_message from worker_job_audit_events")) {
            assertThat(result.next()).isTrue();
            assertThat(result.getString("error_message"))
                    .contains("Unexpected character")
                    .contains("token 'null'")
                    .doesNotContain("[redacted-sensitive-error]");
        }
    }

    @Test
    void skipsWhenDatasourceIsMissing() {
        WorkerJobAuditWriter writer = WorkerJobAuditWriter.noop();

        writer.record(WorkerJobAuditEvent.succeeded(
                "queue_consumer",
                "notification.mail",
                null,
                Duration.ofMillis(1)));
    }

    @Test
    void recordsMicrometerMetricsEvenWhenDbAuditIsDisabled() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        WorkerJobAuditWriter writer = new WorkerJobAuditWriter(
                false,
                null,
                null,
                null,
                "ircs-notification-worker",
                "notification-pod-1",
                registry);

        writer.record(WorkerJobAuditEvent.failed(
                "queue_consumer",
                "notification.mail",
                "mail-task-1",
                Duration.ofMillis(17),
                new RuntimeException("delivery failed")));

        assertThat(registry.get("ircs.worker.job.runs")
                .tag("worker_id", "notification-pod-1")
                .tag("job_type", "queue_consumer")
                .tag("job_name", "notification.mail")
                .tag("outcome", "failed")
                .counter()
                .count())
                .isEqualTo(1.0d);
        assertThat(registry.get("ircs.worker.job.duration")
                .tag("worker_id", "notification-pod-1")
                .tag("job_type", "queue_consumer")
                .tag("job_name", "notification.mail")
                .tag("outcome", "failed")
                .timer()
                .totalTime(TimeUnit.MILLISECONDS))
                .isEqualTo(17.0d);
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

    private static class CapturingRuntimeWorkQueue implements RuntimeWorkQueue {
        private final List<RuntimeWorkItemRequest> requests = new ArrayList<>();

        @Override
        public void submit(RuntimeWorkItemRequest request) {
            requests.add(request);
        }

        @Override
        public void submit(RuntimeWorkItemRequest request, Duration delay) {
            requests.add(request);
        }

        @Override
        public void submitAfterCommit(RuntimeWorkItemRequest request) {
            requests.add(request);
        }

        @Override
        public void submitAfterCommit(RuntimeWorkItemRequest request, Duration delay) {
            requests.add(request);
        }

        @Override
        public List<RuntimeWorkItem> claim(String taskType, String ownerId, int limit, Duration visibilityTimeout) {
            return List.of();
        }

        @Override
        public boolean complete(RuntimeWorkItem item) {
            return true;
        }

        @Override
        public boolean fail(RuntimeWorkItem item, boolean retryable, Duration retryDelay, String reason) {
            return true;
        }

        @Override
        public int requeueExpired(String taskType, int limit) {
            return 0;
        }

        @Override
        public RuntimeWorkQueueCounts counts(String taskType) {
            return new RuntimeWorkQueueCounts(0, 0, 0);
        }
    }
}
