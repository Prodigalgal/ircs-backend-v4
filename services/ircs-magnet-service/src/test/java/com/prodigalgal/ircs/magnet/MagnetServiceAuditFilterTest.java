package com.prodigalgal.ircs.magnet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.prodigalgal.ircs.common.audit.ServiceRequestAuditFilter;
import jakarta.servlet.http.Cookie;
import java.sql.DriverManager;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class MagnetServiceAuditFilterTest {

    private final MagnetQueryService magnetQueryService = org.mockito.Mockito.mock(MagnetQueryService.class);

    @Test
    void writesMagnetServiceHandlerAuditRowWithoutSensitiveValues() throws Exception {
        String url = "jdbc:h2:mem:magnet_service_audit;MODE=PostgreSQL;DB_CLOSE_DELAY=-1";
        createAuditTable(url);
        JdbcTemplate jdbcTemplate = new JdbcTemplate(new DriverManagerDataSource(url));
        UUID providerId = UUID.randomUUID();
        when(magnetQueryService.createProvider(any(MagnetProviderRequest.class)))
                .thenReturn(provider(providerId));
        MockMvc mockMvc = MockMvcBuilders
                .standaloneSetup(new MagnetController(magnetQueryService))
                .addFilters(new ServiceRequestAuditFilter(true, "ircs-magnet-service", jdbcTemplate))
                .build();

        mockMvc.perform(post("/api/v1/magnet-providers?token=query-secret-token&keyword=codex&password=query-secret-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Authenticated-User", "magnet-admin@example.com")
                        .header("X-Forwarded-For", "203.0.113.122, 10.0.0.1")
                        .header("X-Trace-Id", "codex-trace-02812-magnet")
                        .header("Authorization", "Bearer header-secret-token")
                        .cookie(new Cookie("SESSION", "secret-session-cookie"))
                        .header("User-Agent", "magnet-service-audit-test")
                        .content("""
                                {
                                  "code": "codex_provider",
                                  "name": "Codex Provider",
                                  "providerType": "CODEX_PROVIDER",
                                  "baseUrl": "https://example.invalid/api",
                                  "enabled": true,
                                  "priority": 10,
                                  "riskLevel": "HIGH",
                                  "supportedExternalIds": ["IMDB"],
                                  "minDelayMs": 1000,
                                  "maxDelayMs": 3000,
                                  "timeoutMs": 10000,
                                  "resultLimit": 20,
                                  "autoApproveAllowed": true,
                                  "contentPolicy": "body-secret-policy"
                                }
                                """))
                .andExpect(status().isCreated());

        var rows = jdbcTemplate.queryForList("""
                select request_source, username, method, path, query_string, status_code, success,
                       client_ip, user_agent, trace_id, error_message
                  from request_audit_logs
                """);
        assertThat(rows).hasSize(1);
        assertThat(rows.getFirst())
                .containsEntry("REQUEST_SOURCE", "ircs-magnet-service")
                .containsEntry("USERNAME", "magnet-admin@example.com")
                .containsEntry("METHOD", "POST")
                .containsEntry("PATH", "/api/v1/magnet-providers")
                .containsEntry("QUERY_STRING", "token=***&keyword=codex&password=***")
                .containsEntry("STATUS_CODE", 201)
                .containsEntry("SUCCESS", true)
                .containsEntry("CLIENT_IP", "203.0.113.122")
                .containsEntry("TRACE_ID", "codex-trace-02812-magnet");
        String auditText = rows.getFirst().values().toString();
        assertThat(auditText)
                .doesNotContain("query-secret-token")
                .doesNotContain("query-secret-password")
                .doesNotContain("body-secret-policy")
                .doesNotContain("header-secret-token")
                .doesNotContain("secret-session-cookie")
                .doesNotContain("response-secret-policy");
    }

    @Test
    void skipsActuatorNoise() throws Exception {
        String url = "jdbc:h2:mem:magnet_service_audit_skip;MODE=PostgreSQL;DB_CLOSE_DELAY=-1";
        createAuditTable(url);
        JdbcTemplate jdbcTemplate = new JdbcTemplate(new DriverManagerDataSource(url));
        ServiceRequestAuditFilter filter = new ServiceRequestAuditFilter(true, "ircs-magnet-service", jdbcTemplate);

        assertThat(filter.shouldNotFilter(new MockHttpServletRequest("GET", "/actuator/health"))).isTrue();
    }

    private static MagnetProviderSummary provider(UUID providerId) {
        Instant now = Instant.parse("2026-06-08T00:00:00Z");
        return new MagnetProviderSummary(
                providerId,
                "codex_provider",
                "Codex Provider",
                "CODEX_PROVIDER",
                "https://example.invalid/api",
                true,
                10,
                "HIGH",
                List.of("IMDB"),
                1000,
                3000,
                10000,
                20,
                true,
                "response-secret-policy",
                null,
                null,
                null,
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
