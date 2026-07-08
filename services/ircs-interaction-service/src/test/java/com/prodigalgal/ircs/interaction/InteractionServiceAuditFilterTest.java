package com.prodigalgal.ircs.interaction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.prodigalgal.ircs.common.audit.ServiceRequestAuditFilter;
import jakarta.servlet.http.Cookie;
import java.sql.DriverManager;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class InteractionServiceAuditFilterTest {

    private final InteractionQueryService queryService = org.mockito.Mockito.mock(InteractionQueryService.class);
    private final FeedbackCommandService commandService = org.mockito.Mockito.mock(FeedbackCommandService.class);
    private final MemberTokenService memberTokenService = org.mockito.Mockito.mock(MemberTokenService.class);

    @Test
    void writesInteractionServiceHandlerAuditRowWithoutSensitiveValues() throws Exception {
        String url = "jdbc:h2:mem:interaction_service_audit;MODE=PostgreSQL;DB_CLOSE_DELAY=-1";
        createAuditTable(url);
        JdbcTemplate jdbcTemplate = new JdbcTemplate(new DriverManagerDataSource(url));
        UUID memberId = UUID.randomUUID();
        when(memberTokenService.requireMemberId("Bearer header-secret-token"))
                .thenReturn(memberId);
        when(commandService.submit(eq(memberId), any(FeedbackSubmitRequest.class)))
                .thenReturn(message(memberId));
        MockMvc mockMvc = MockMvcBuilders
                .standaloneSetup(new FeedbackController(queryService, commandService, memberTokenService))
                .addFilters(new ServiceRequestAuditFilter(true, "ircs-interaction-service", jdbcTemplate))
                .build();

        mockMvc.perform(post("/api/portal/feedback?token=query-secret-token&keyword=codex&password=query-secret-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Authenticated-User", "member@example.com")
                        .header("X-Forwarded-For", "203.0.113.121, 10.0.0.1")
                        .header("X-Trace-Id", "codex-trace-02812-interaction")
                        .header("Authorization", "Bearer header-secret-token")
                        .cookie(new Cookie("SESSION", "secret-session-cookie"))
                        .header("User-Agent", "interaction-service-audit-test")
                        .content("""
                                {
                                  "content": "body-secret-feedback-content"
                                }
                                """))
                .andExpect(status().isOk());

        var rows = jdbcTemplate.queryForList("""
                select request_source, username, method, path, query_string, status_code, success,
                       client_ip, user_agent, trace_id, error_message
                  from request_audit_logs
                """);
        assertThat(rows).hasSize(1);
        assertThat(rows.getFirst())
                .containsEntry("REQUEST_SOURCE", "ircs-interaction-service")
                .containsEntry("USERNAME", "member@example.com")
                .containsEntry("METHOD", "POST")
                .containsEntry("PATH", "/api/portal/feedback")
                .containsEntry("QUERY_STRING", "token=***&keyword=codex&password=***")
                .containsEntry("STATUS_CODE", 200)
                .containsEntry("SUCCESS", true)
                .containsEntry("CLIENT_IP", "203.0.113.121")
                .containsEntry("TRACE_ID", "codex-trace-02812-interaction");
        String auditText = rows.getFirst().values().toString();
        assertThat(auditText)
                .doesNotContain("query-secret-token")
                .doesNotContain("query-secret-password")
                .doesNotContain("body-secret-feedback-content")
                .doesNotContain("header-secret-token")
                .doesNotContain("secret-session-cookie")
                .doesNotContain("response-secret-reply");
    }

    @Test
    void skipsActuatorNoise() throws Exception {
        String url = "jdbc:h2:mem:interaction_service_audit_skip;MODE=PostgreSQL;DB_CLOSE_DELAY=-1";
        createAuditTable(url);
        JdbcTemplate jdbcTemplate = new JdbcTemplate(new DriverManagerDataSource(url));
        ServiceRequestAuditFilter filter = new ServiceRequestAuditFilter(true, "ircs-interaction-service", jdbcTemplate);

        assertThat(filter.shouldNotFilter(new MockHttpServletRequest("GET", "/actuator/health"))).isTrue();
    }

    private static UserMessageResponse message(UUID memberId) {
        Instant now = Instant.parse("2026-06-08T00:00:00Z");
        return new UserMessageResponse(
                UUID.randomUUID(),
                memberId,
                "画外用户",
                "member@example.invalid",
                "https://example.invalid/avatar.png",
                "body-secret-feedback-content",
                "response-secret-reply",
                "PENDING",
                false,
                now,
                now);
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
