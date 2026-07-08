package com.prodigalgal.ircs.identity.controller;



import com.prodigalgal.ircs.identity.application.PoWService;
import com.prodigalgal.ircs.identity.application.AdminAuthService;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.prodigalgal.ircs.common.audit.ServiceRequestAuditFilter;
import com.prodigalgal.ircs.identity.dto.IdentityDtos.AdminLoginRequest;
import com.prodigalgal.ircs.identity.dto.IdentityDtos.AdminTokenResponse;
import jakarta.servlet.http.Cookie;
import java.sql.DriverManager;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class IdentityServiceAuditFilterTest {

    private final AdminAuthService adminAuthService = org.mockito.Mockito.mock(AdminAuthService.class);
    private final PoWService poWService = org.mockito.Mockito.mock(PoWService.class);

    @Test
    void writesIdentityServiceHandlerAuditRowWithoutSensitiveValues() throws Exception {
        String url = "jdbc:h2:mem:identity_service_audit;MODE=PostgreSQL;DB_CLOSE_DELAY=-1";
        createAuditTable(url);
        JdbcTemplate jdbcTemplate = new JdbcTemplate(new DriverManagerDataSource(url));
        when(adminAuthService.login(any(AdminLoginRequest.class)))
                .thenReturn(new AdminTokenResponse("response-secret-jwt-token"));
        MockMvc mockMvc = MockMvcBuilders
                .standaloneSetup(new AdminAuthController(adminAuthService, poWService))
                .addFilters(new ServiceRequestAuditFilter(true, "ircs-identity-service", jdbcTemplate))
                .build();

        mockMvc.perform(post("/api/v1/auth/login?token=query-secret-token&redirect=/admin&password=query-secret-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Authenticated-User", "admin@example.com")
                        .header("X-Forwarded-For", "203.0.113.81, 10.0.0.1")
                        .header("X-Trace-Id", "codex-trace-02812-identity")
                        .header("Authorization", "Bearer header-secret-jwt-token")
                        .cookie(new Cookie("SESSION", "secret-session-cookie"))
                        .header("User-Agent", "identity-service-audit-test")
                        .content("""
                                {
                                  "username": "admin",
                                  "password": "body-secret-password",
                                  "powVerification": {
                                    "id": "pow-id",
                                    "nonce": "pow-nonce"
                                  }
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
                .containsEntry("REQUEST_SOURCE", "ircs-identity-service")
                .containsEntry("USERNAME", "admin@example.com")
                .containsEntry("METHOD", "POST")
                .containsEntry("PATH", "/api/v1/auth/login")
                .containsEntry("QUERY_STRING", "token=***&redirect=/admin&password=***")
                .containsEntry("STATUS_CODE", 200)
                .containsEntry("SUCCESS", true)
                .containsEntry("CLIENT_IP", "203.0.113.81")
                .containsEntry("TRACE_ID", "codex-trace-02812-identity");
        String auditText = rows.getFirst().values().toString();
        assertThat(auditText)
                .doesNotContain("query-secret-token")
                .doesNotContain("query-secret-password")
                .doesNotContain("body-secret-password")
                .doesNotContain("header-secret-jwt-token")
                .doesNotContain("secret-session-cookie")
                .doesNotContain("response-secret-jwt-token");
    }

    @Test
    void skipsActuatorNoise() throws Exception {
        String url = "jdbc:h2:mem:identity_service_audit_skip;MODE=PostgreSQL;DB_CLOSE_DELAY=-1";
        createAuditTable(url);
        JdbcTemplate jdbcTemplate = new JdbcTemplate(new DriverManagerDataSource(url));
        ServiceRequestAuditFilter filter = new ServiceRequestAuditFilter(true, "ircs-identity-service", jdbcTemplate);

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
