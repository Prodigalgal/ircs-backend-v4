package com.prodigalgal.ircs.search.portal.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.prodigalgal.ircs.common.audit.ServiceRequestAuditFilter;
import com.prodigalgal.ircs.common.security.IrcsRequestPrincipal;
import com.prodigalgal.ircs.search.portal.application.PortalSearchQueryService;
import jakarta.servlet.http.Cookie;
import java.sql.DriverManager;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class SearchServiceAuditFilterTest {

    private final PortalSearchQueryService portalSearchQueryService =
            org.mockito.Mockito.mock(PortalSearchQueryService.class);

    @Test
    void writesSearchServiceHandlerAuditRowWithoutSensitiveValues() throws Exception {
        String url = "jdbc:h2:mem:search_service_audit;MODE=PostgreSQL;DB_CLOSE_DELAY=-1";
        createAuditTable(url);
        JdbcTemplate jdbcTemplate = new JdbcTemplate(new DriverManagerDataSource(url));
        when(portalSearchQueryService.suggest("codex", IrcsRequestPrincipal.publicPrincipal()))
                .thenReturn(List.of("response-secret-suggestion"));
        MockMvc mockMvc = MockMvcBuilders
                .standaloneSetup(new PortalSearchController(portalSearchQueryService))
                .addFilters(new ServiceRequestAuditFilter(true, "ircs-search-service", jdbcTemplate))
                .build();

        mockMvc.perform(get("/api/portal/search/suggest?token=query-secret-token&keyword=codex&password=query-secret-password")
                        .header("X-Authenticated-User", "search-user@example.com")
                        .header("X-Forwarded-For", "203.0.113.124, 10.0.0.1")
                        .header("X-Trace-Id", "codex-trace-02812-search")
                        .header("Authorization", "Bearer header-secret-token")
                        .cookie(new Cookie("SESSION", "secret-session-cookie"))
                        .header("User-Agent", "search-service-audit-test"))
                .andExpect(status().isOk());

        var rows = jdbcTemplate.queryForList("""
                select request_source, username, method, path, query_string, status_code, success,
                       client_ip, user_agent, trace_id, error_message
                  from request_audit_logs
                """);
        assertThat(rows).hasSize(1);
        assertThat(rows.getFirst())
                .containsEntry("REQUEST_SOURCE", "ircs-search-service")
                .containsEntry("USERNAME", "search-user@example.com")
                .containsEntry("METHOD", "GET")
                .containsEntry("PATH", "/api/portal/search/suggest")
                .containsEntry("QUERY_STRING", "token=***&keyword=codex&password=***")
                .containsEntry("STATUS_CODE", 200)
                .containsEntry("SUCCESS", true)
                .containsEntry("CLIENT_IP", "203.0.113.124")
                .containsEntry("TRACE_ID", "codex-trace-02812-search");
        String auditText = rows.getFirst().values().toString();
        assertThat(auditText)
                .doesNotContain("query-secret-token")
                .doesNotContain("query-secret-password")
                .doesNotContain("header-secret-token")
                .doesNotContain("secret-session-cookie")
                .doesNotContain("response-secret-suggestion");
    }

    @Test
    void skipsActuatorNoise() throws Exception {
        String url = "jdbc:h2:mem:search_service_audit_skip;MODE=PostgreSQL;DB_CLOSE_DELAY=-1";
        createAuditTable(url);
        JdbcTemplate jdbcTemplate = new JdbcTemplate(new DriverManagerDataSource(url));
        ServiceRequestAuditFilter filter = new ServiceRequestAuditFilter(true, "ircs-search-service", jdbcTemplate);

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
