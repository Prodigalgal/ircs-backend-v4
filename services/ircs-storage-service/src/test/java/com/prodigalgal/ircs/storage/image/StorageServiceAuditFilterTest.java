package com.prodigalgal.ircs.storage.image;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.prodigalgal.ircs.common.audit.ServiceRequestAuditFilter;
import com.prodigalgal.ircs.storage.image.CoverImageDtos.CoverImageResponse;
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

class StorageServiceAuditFilterTest {

    private final CoverImageAdminService coverImageAdminService = org.mockito.Mockito.mock(CoverImageAdminService.class);

    @Test
    void writesStorageServiceHandlerAuditRowWithoutSensitiveValues() throws Exception {
        String url = "jdbc:h2:mem:storage_service_audit;MODE=PostgreSQL;DB_CLOSE_DELAY=-1";
        createAuditTable(url);
        JdbcTemplate jdbcTemplate = new JdbcTemplate(new DriverManagerDataSource(url));
        String bodySecretUrl = "https://example.invalid/body-secret-cover.jpg";
        UUID coverId = UUID.randomUUID();
        when(coverImageAdminService.createFromUrl(bodySecretUrl))
                .thenReturn(response(coverId));
        MockMvc mockMvc = MockMvcBuilders
                .standaloneSetup(new CoverImageController(coverImageAdminService))
                .addFilters(new ServiceRequestAuditFilter(true, "ircs-storage-service", jdbcTemplate))
                .build();

        mockMvc.perform(post("/api/v1/cover-images/fetch?token=query-secret-token&url=metadata&password=query-secret-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Authenticated-User", "storage-admin@example.com")
                        .header("X-Forwarded-For", "203.0.113.123, 10.0.0.1")
                        .header("X-Trace-Id", "codex-trace-02812-storage")
                        .header("Authorization", "Bearer header-secret-token")
                        .cookie(new Cookie("SESSION", "secret-session-cookie"))
                        .header("User-Agent", "storage-service-audit-test")
                        .content("""
                                {
                                  "url": "https://example.invalid/body-secret-cover.jpg"
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
                .containsEntry("REQUEST_SOURCE", "ircs-storage-service")
                .containsEntry("USERNAME", "storage-admin@example.com")
                .containsEntry("METHOD", "POST")
                .containsEntry("PATH", "/api/v1/cover-images/fetch")
                .containsEntry("QUERY_STRING", "token=***&url=metadata&password=***")
                .containsEntry("STATUS_CODE", 200)
                .containsEntry("SUCCESS", true)
                .containsEntry("CLIENT_IP", "203.0.113.123")
                .containsEntry("TRACE_ID", "codex-trace-02812-storage");
        String auditText = rows.getFirst().values().toString();
        assertThat(auditText)
                .doesNotContain("query-secret-token")
                .doesNotContain("query-secret-password")
                .doesNotContain("body-secret-cover")
                .doesNotContain("header-secret-token")
                .doesNotContain("secret-session-cookie")
                .doesNotContain("response-secret-storage-path");
    }

    @Test
    void skipsActuatorNoise() throws Exception {
        String url = "jdbc:h2:mem:storage_service_audit_skip;MODE=PostgreSQL;DB_CLOSE_DELAY=-1";
        createAuditTable(url);
        JdbcTemplate jdbcTemplate = new JdbcTemplate(new DriverManagerDataSource(url));
        ServiceRequestAuditFilter filter = new ServiceRequestAuditFilter(true, "ircs-storage-service", jdbcTemplate);

        assertThat(filter.shouldNotFilter(new MockHttpServletRequest("GET", "/actuator/health"))).isTrue();
    }

    private static CoverImageResponse response(UUID id) {
        Instant now = Instant.parse("2026-06-08T00:00:00Z");
        return new CoverImageResponse(
                id,
                CoverImageStorageType.LOCAL,
                CoverImageStatus.UNPROCESSED,
                "https://cdn.example.invalid/response-secret-storage-path.jpg",
                "https://cdn.example.invalid/response-secret-storage-path.jpg",
                "covers/response-secret-storage-path.jpg",
                null,
                null,
                null,
                "EXAMPLE",
                0,
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
