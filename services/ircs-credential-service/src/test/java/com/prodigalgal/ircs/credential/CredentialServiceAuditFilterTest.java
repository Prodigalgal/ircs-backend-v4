package com.prodigalgal.ircs.credential;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.prodigalgal.ircs.common.audit.ServiceRequestAuditFilter;
import java.sql.DriverManager;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class CredentialServiceAuditFilterTest {

    private final CredentialService credentialService = org.mockito.Mockito.mock(CredentialService.class);

    @Test
    void writesCredentialServiceHandlerAuditRowWithoutSensitiveValues() throws Exception {
        String url = "jdbc:h2:mem:credential_service_audit;MODE=PostgreSQL;DB_CLOSE_DELAY=-1";
        createAuditTable(url);
        JdbcTemplate jdbcTemplate = new JdbcTemplate(new DriverManagerDataSource(url));
        UUID credentialId = UUID.randomUUID();
        when(credentialService.create(any(CredentialWriteRequest.class)))
                .thenReturn(summary(credentialId));
        MockMvc mockMvc = MockMvcBuilders
                .standaloneSetup(new CredentialController(credentialService))
                .addFilters(new ServiceRequestAuditFilter(true, "ircs-credential-service", jdbcTemplate))
                .build();

        mockMvc.perform(post("/api/v1/credentials")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Authenticated-User", "credential-admin@example.com")
                        .header("X-Forwarded-For", "203.0.113.67, 10.0.0.1")
                        .header("X-Trace-Id", "codex-trace-02812-credential")
                        .header("Authorization", "Bearer secret-authorization-token")
                        .header("Cookie", "SESSION=secret-cookie-value")
                        .header("X-IRCS-SERVICE-TOKEN", "secret-service-token")
                        .header("User-Agent", "credential-service-audit-test")
                        .content("""
                                {
                                  "provider": "TMDB",
                                  "name": "dev",
                                  "payload": {
                                    "api_key": "super-secret-api-key"
                                  },
                                  "enabled": true
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
                .containsEntry("REQUEST_SOURCE", "ircs-credential-service")
                .containsEntry("USERNAME", "credential-admin@example.com")
                .containsEntry("METHOD", "POST")
                .containsEntry("PATH", "/api/v1/credentials")
                .containsEntry("STATUS_CODE", 201)
                .containsEntry("SUCCESS", true)
                .containsEntry("CLIENT_IP", "203.0.113.67")
                .containsEntry("TRACE_ID", "codex-trace-02812-credential");
        String auditText = rows.getFirst().values().toString();
        assertThat(auditText)
                .doesNotContain("super-secret-api-key")
                .doesNotContain("secret-authorization-token")
                .doesNotContain("secret-cookie-value")
                .doesNotContain("secret-service-token");
    }

    @Test
    void writesInternalCredentialFailureAuditWithoutInternalToken() throws Exception {
        String url = "jdbc:h2:mem:credential_service_internal_audit;MODE=PostgreSQL;DB_CLOSE_DELAY=-1";
        createAuditTable(url);
        JdbcTemplate jdbcTemplate = new JdbcTemplate(new DriverManagerDataSource(url));
        CredentialInternalAccessProperties properties = new CredentialInternalAccessProperties();
        properties.setToken("configured-token");
        MockMvc mockMvc = MockMvcBuilders
                .standaloneSetup(new InternalCredentialController(credentialService, properties))
                .addFilters(new ServiceRequestAuditFilter(true, "ircs-credential-service", jdbcTemplate))
                .build();

        mockMvc.perform(get("/internal/credentials/providers/TMDB/leases?requiredPayloadKey=api_key&limit=1")
                        .header("X-Trace-Id", "codex-trace-02812-credential-internal")
                        .header("X-IRCS-SERVICE-ID", "metadata-worker")
                        .header("X-IRCS-SERVICE-TOKEN", "wrong-secret-service-token")
                        .header("X-IRCS-SERVICE-SCOPES", "credential:lease")
                        .header("Authorization", "Bearer wrong-secret-authorization-token"))
                .andExpect(status().isUnauthorized());

        var row = jdbcTemplate.queryForMap("""
                select request_source, method, path, query_string, status_code, success,
                       trace_id, error_message
                  from request_audit_logs
                """);
        assertThat(row)
                .containsEntry("REQUEST_SOURCE", "ircs-credential-service")
                .containsEntry("METHOD", "GET")
                .containsEntry("PATH", "/internal/credentials/providers/TMDB/leases")
                .containsEntry("QUERY_STRING", "requiredPayloadKey=api_key&limit=1")
                .containsEntry("STATUS_CODE", 401)
                .containsEntry("SUCCESS", false)
                .containsEntry("TRACE_ID", "codex-trace-02812-credential-internal");
        assertThat(row.values().toString())
                .doesNotContain("wrong-secret-service-token")
                .doesNotContain("wrong-secret-authorization-token");
    }

    @Test
    void skipsActuatorNoise() throws Exception {
        String url = "jdbc:h2:mem:credential_service_audit_skip;MODE=PostgreSQL;DB_CLOSE_DELAY=-1";
        createAuditTable(url);
        JdbcTemplate jdbcTemplate = new JdbcTemplate(new DriverManagerDataSource(url));
        ServiceRequestAuditFilter filter = new ServiceRequestAuditFilter(true, "ircs-credential-service", jdbcTemplate);

        assertThat(filter.shouldNotFilter(new MockHttpServletRequest("GET", "/actuator/health"))).isTrue();
    }

    private static CredentialSummary summary(UUID id) {
        Instant now = Instant.parse("2026-06-08T00:00:00Z");
        return new CredentialSummary(
                id,
                now,
                now,
                "TMDB",
                "dev",
                "abcdef12",
                Map.of("api_key", "super-secret-api-key"),
                true,
                1,
                30,
                "MINUTE",
                0L,
                0L,
                0L,
                0L,
                "remark",
                0L,
                0L,
                0L,
                0L,
                0.0,
                0L,
                List.of("api_key"));
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
