package com.prodigalgal.ircs.task.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.prodigalgal.ircs.common.audit.WorkerJobAuditEvent;
import com.prodigalgal.ircs.common.audit.WorkerJobAuditWriter;
import java.sql.DriverManager;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class TaskServiceAuditConfigurationTest {

    @Test
    void workerJobAuditWriterUsesTaskServiceSource() throws Exception {
        String url = "jdbc:h2:mem:task_service_worker_job_audit_config;MODE=PostgreSQL;DB_CLOSE_DELAY=-1";
        createWorkerJobAuditTable(url);
        TaskServiceAuditConfiguration configuration = new TaskServiceAuditConfiguration();

        WorkerJobAuditWriter writer = configuration.workerJobAuditWriter(
                true,
                url,
                null,
                null,
                "ircs-task-service");

        writer.record(WorkerJobAuditEvent.succeeded(
                "collection-task-runner",
                "collection-task.runner",
                "codex-correlation",
                Duration.ofMillis(5)));

        try (var connection = DriverManager.getConnection(url);
             var result = connection.createStatement()
                     .executeQuery("select job_source from worker_job_audit_events")) {
            assertThat(result.next()).isTrue();
            assertThat(result.getString("job_source"))
                    .startsWith("ircs-task-service@")
                    .contains("#");
            assertThat(result.next()).isFalse();
        }
    }

    private static void createWorkerJobAuditTable(String url) throws Exception {
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
}
