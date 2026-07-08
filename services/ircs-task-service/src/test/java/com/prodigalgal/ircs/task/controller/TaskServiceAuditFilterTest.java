package com.prodigalgal.ircs.task.controller;





import com.prodigalgal.ircs.task.application.TaskQueryService;
import com.prodigalgal.ircs.task.application.TaskRuntimeReadService;
import com.prodigalgal.ircs.task.application.TaskCommandService;
import com.prodigalgal.ircs.task.application.TaskLogService;
import com.prodigalgal.ircs.task.controller.CollectionTaskController;
import com.prodigalgal.ircs.task.dto.TaskCardSummary;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.prodigalgal.ircs.common.audit.ServiceRequestAuditFilter;
import jakarta.servlet.http.Cookie;
import java.sql.DriverManager;
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

class TaskServiceAuditFilterTest {

    private final TaskQueryService taskQueryService = org.mockito.Mockito.mock(TaskQueryService.class);
    private final TaskCommandService taskCommandService = org.mockito.Mockito.mock(TaskCommandService.class);
    private final TaskLogService taskLogService = org.mockito.Mockito.mock(TaskLogService.class);
    private final TaskRuntimeReadService taskRuntimeReadService = org.mockito.Mockito.mock(TaskRuntimeReadService.class);

    @Test
    void writesTaskServiceHandlerAuditRowWithoutSensitiveValues() throws Exception {
        String url = "jdbc:h2:mem:task_service_audit;MODE=PostgreSQL;DB_CLOSE_DELAY=-1";
        createAuditTable(url);
        JdbcTemplate jdbcTemplate = new JdbcTemplate(new DriverManagerDataSource(url));
        PageRequest pageable = PageRequest.of(0, 20);
        TaskCardSummary task = new TaskCardSummary(
                UUID.randomUUID(),
                "response-secret-task",
                "IDLE",
                true,
                "Codex source",
                null,
                "Asia/Shanghai",
                "recent",
                24,
                "codex",
                1,
                2,
                1,
                null,
                0L,
                0L,
                0L,
                0L,
                0L,
                0L,
                0L,
                null,
                null);
        when(taskQueryService.findAll(pageable, "Codex", "IDLE", null, true))
                .thenReturn(new PageImpl<>(List.of(task), pageable, 1));
        MockMvc mockMvc = MockMvcBuilders
                .standaloneSetup(new CollectionTaskController(
                        taskQueryService,
                        taskCommandService,
                        taskLogService,
                        taskRuntimeReadService))
                .setCustomArgumentResolvers(new PageableHandlerMethodArgumentResolver())
                .addFilters(new ServiceRequestAuditFilter(true, "ircs-task-service", jdbcTemplate))
                .build();

        mockMvc.perform(get("/api/v1/collection-tasks?token=query-secret-token&name=Codex&status=IDLE&enabled=true&password=query-secret-password")
                        .header("X-Authenticated-User", "task-user@example.com")
                        .header("X-Forwarded-For", "203.0.113.126, 10.0.0.1")
                        .header("X-Trace-Id", "codex-trace-02812-task")
                        .header("Authorization", "Bearer header-secret-token")
                        .cookie(new Cookie("SESSION", "secret-session-cookie"))
                        .header("User-Agent", "task-service-audit-test"))
                .andExpect(status().isOk());

        var rows = jdbcTemplate.queryForList("""
                select request_source, username, method, path, query_string, status_code, success,
                       client_ip, user_agent, trace_id, error_message
                  from request_audit_logs
                """);
        assertThat(rows).hasSize(1);
        assertThat(rows.getFirst())
                .containsEntry("REQUEST_SOURCE", "ircs-task-service")
                .containsEntry("USERNAME", "task-user@example.com")
                .containsEntry("METHOD", "GET")
                .containsEntry("PATH", "/api/v1/collection-tasks")
                .containsEntry("QUERY_STRING", "token=***&name=Codex&status=IDLE&enabled=true&password=***")
                .containsEntry("STATUS_CODE", 200)
                .containsEntry("SUCCESS", true)
                .containsEntry("CLIENT_IP", "203.0.113.126")
                .containsEntry("TRACE_ID", "codex-trace-02812-task");
        String auditText = rows.getFirst().values().toString();
        assertThat(auditText)
                .doesNotContain("query-secret-token")
                .doesNotContain("query-secret-password")
                .doesNotContain("header-secret-token")
                .doesNotContain("secret-session-cookie")
                .doesNotContain("response-secret-task");
    }

    @Test
    void skipsActuatorNoise() throws Exception {
        String url = "jdbc:h2:mem:task_service_audit_skip;MODE=PostgreSQL;DB_CLOSE_DELAY=-1";
        createAuditTable(url);
        JdbcTemplate jdbcTemplate = new JdbcTemplate(new DriverManagerDataSource(url));
        ServiceRequestAuditFilter filter = new ServiceRequestAuditFilter(true, "ircs-task-service", jdbcTemplate);

        assertThat(filter.shouldNotFilter(new MockHttpServletRequest("GET", "/actuator/health"))).isTrue();
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
