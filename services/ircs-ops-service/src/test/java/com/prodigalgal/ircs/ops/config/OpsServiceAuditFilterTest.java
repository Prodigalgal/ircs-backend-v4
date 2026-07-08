package com.prodigalgal.ircs.ops.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.prodigalgal.ircs.common.audit.ServiceRequestAuditFilter;
import com.prodigalgal.ircs.ops.audit.request.RequestAuditController;
import com.prodigalgal.ircs.ops.audit.request.RequestAuditQueryService;
import com.prodigalgal.ircs.ops.audit.request.RequestAuditSummaryResponse;
import com.prodigalgal.ircs.ops.audit.worker.WorkerJobAuditController;
import com.prodigalgal.ircs.ops.audit.worker.WorkerJobAuditEventResponse;
import com.prodigalgal.ircs.ops.audit.worker.WorkerJobAuditQueryService;
import jakarta.servlet.http.Cookie;
import java.sql.DriverManager;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.web.PageableHandlerMethodArgumentResolver;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class OpsServiceAuditFilterTest {

    private final WorkerJobAuditQueryService workerJobAuditQueryService =
            org.mockito.Mockito.mock(WorkerJobAuditQueryService.class);
    private final RequestAuditQueryService requestAuditQueryService =
            org.mockito.Mockito.mock(RequestAuditQueryService.class);

    @Test
    void writesOpsServiceHandlerAuditRowWithoutSensitiveValues() throws Exception {
        String url = "jdbc:h2:mem:ops_service_audit;MODE=PostgreSQL;DB_CLOSE_DELAY=-1";
        createAuditTable(url);
        JdbcTemplate jdbcTemplate = new JdbcTemplate(new DriverManagerDataSource(url));
        PageRequest pageable = PageRequest.of(0, 20);
        WorkerJobAuditEventResponse event = new WorkerJobAuditEventResponse(
                UUID.randomUUID(),
                Instant.parse("2026-06-08T10:00:00Z"),
                Instant.parse("2026-06-08T10:00:01Z"),
                0L,
                "ircs-response-secret-worker",
                "queue-consumer",
                "response-secret-job",
                "response-secret-correlation",
                "failed",
                987654321L,
                "ResponseSecretException",
                "response-secret-error");
        when(workerJobAuditQueryService.findAll(
                        pageable,
                        "ircs",
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null))
                .thenReturn(new PageImpl<>(List.of(event), pageable, 1));
        MockMvc mockMvc = MockMvcBuilders
                .standaloneSetup(new WorkerJobAuditController(workerJobAuditQueryService))
                .setCustomArgumentResolvers(new PageableHandlerMethodArgumentResolver())
                .addFilters(new ServiceRequestAuditFilter(true, "ircs-ops-service", jdbcTemplate))
                .build();

        mockMvc.perform(get("/api/v1/ops/worker-job-audit?token=query-secret-token&jobSource=ircs&password=query-secret-password")
                        .header("X-Authenticated-User", "ops-user@example.com")
                        .header("X-Forwarded-For", "203.0.113.127, 10.0.0.1")
                        .header("X-Trace-Id", "codex-trace-02812-ops")
                        .header("Authorization", "Bearer header-secret-token")
                        .cookie(new Cookie("SESSION", "secret-session-cookie"))
                        .header("User-Agent", "ops-service-audit-test"))
                .andExpect(status().isOk());

        var rows = jdbcTemplate.queryForList("""
                select request_source, username, method, path, query_string, status_code, success,
                       client_ip, user_agent, trace_id, error_message
                  from request_audit_logs
                """);
        assertThat(rows).hasSize(1);
        assertThat(rows.getFirst())
                .containsEntry("REQUEST_SOURCE", "ircs-ops-service")
                .containsEntry("USERNAME", "ops-user@example.com")
                .containsEntry("METHOD", "GET")
                .containsEntry("PATH", "/api/v1/ops/worker-job-audit")
                .containsEntry("QUERY_STRING", "token=***&jobSource=ircs&password=***")
                .containsEntry("STATUS_CODE", 200)
                .containsEntry("SUCCESS", true)
                .containsEntry("CLIENT_IP", "203.0.113.127")
                .containsEntry("TRACE_ID", "codex-trace-02812-ops");
        String auditText = rows.getFirst().values().toString();
        assertThat(auditText)
                .doesNotContain("query-secret-token")
                .doesNotContain("query-secret-password")
                .doesNotContain("header-secret-token")
                .doesNotContain("secret-session-cookie")
                .doesNotContain("response-secret")
                .doesNotContain("987654321")
                .doesNotContain("ResponseSecretException");
    }

    @Test
    void skipsActuatorNoise() throws Exception {
        String url = "jdbc:h2:mem:ops_service_audit_skip;MODE=PostgreSQL;DB_CLOSE_DELAY=-1";
        createAuditTable(url);
        JdbcTemplate jdbcTemplate = new JdbcTemplate(new DriverManagerDataSource(url));
        ServiceRequestAuditFilter filter = new ServiceRequestAuditFilter(true, "ircs-ops-service", jdbcTemplate);

        assertThat(filter.shouldNotFilter(new MockHttpServletRequest("GET", "/actuator/health"))).isTrue();
    }

    @Test
    void skipsRequestAuditEndpointToAvoidSelfAuditNoise() throws Exception {
        String url = "jdbc:h2:mem:ops_service_audit_self_skip;MODE=PostgreSQL;DB_CLOSE_DELAY=-1";
        createAuditTable(url);
        JdbcTemplate jdbcTemplate = new JdbcTemplate(new DriverManagerDataSource(url));
        RequestAuditSummaryResponse summary = new RequestAuditSummaryResponse(1, 0, 0, 12L);
        when(requestAuditQueryService.summarize()).thenReturn(summary);
        MockMvc mockMvc = MockMvcBuilders
                .standaloneSetup(new RequestAuditController(requestAuditQueryService))
                .addFilters(new ServiceRequestAuditFilter(true, "ircs-ops-service", jdbcTemplate))
                .build();

        mockMvc.perform(get("/api/v1/ops/request-audit/summary?token=query-secret-token")
                        .header("X-Trace-Id", "codex-trace-02812-ops-self"))
                .andExpect(status().isOk());

        verify(requestAuditQueryService).summarize();
        Integer rowCount = jdbcTemplate.queryForObject("select count(*) from request_audit_logs", Integer.class);
        assertThat(rowCount).isZero();
    }

    private static void createAuditTable(String url) throws Exception {
        try (var connection = DriverManager.getConnection(url);
             var statement = connection.createStatement()) {
            statement.execute("""
                    create table request_audit_logs (
                        id uuid primary key,
                        created_at timestamp not null,
                        updated_at timestamp not null,
                        version bigint,
                        audit_class varchar(32) not null,
                        request_source varchar(128),
                        username varchar(128),
                        method varchar(16) not null,
                        path varchar(1024) not null,
                        query_string text,
                        status_code integer not null,
                        success boolean not null,
                        duration_ms bigint not null,
                        client_ip varchar(128),
                        user_agent text,
                        trace_id varchar(128),
                        error_message text
                    )
                    """);
        }
    }
}
