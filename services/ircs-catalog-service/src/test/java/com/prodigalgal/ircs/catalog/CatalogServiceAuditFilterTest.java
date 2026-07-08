package com.prodigalgal.ircs.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.prodigalgal.ircs.common.audit.ServiceRequestAuditFilter;
import java.sql.DriverManager;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class CatalogServiceAuditFilterTest {

    private final CatalogService catalogService = org.mockito.Mockito.mock(CatalogService.class);

    @Test
    void writesCatalogServiceHandlerAuditRow() throws Exception {
        String url = "jdbc:h2:mem:catalog_service_audit;MODE=PostgreSQL;DB_CLOSE_DELAY=-1";
        createAuditTable(url);
        JdbcTemplate jdbcTemplate = new JdbcTemplate(new DriverManagerDataSource(url));
        when(catalogService.listStandardCategories()).thenReturn(List.of(
                new StandardCategorySummary(UUID.randomUUID(), "Movie", "movie")));
        MockMvc mockMvc = MockMvcBuilders
                .standaloneSetup(new CatalogController(catalogService))
                .addFilters(new ServiceRequestAuditFilter(true, "ircs-catalog-service", jdbcTemplate))
                .build();

        mockMvc.perform(get("/api/v1/catalog/standard-categories")
                        .header("X-Authenticated-User", "catalog-admin@example.com")
                        .header("X-Forwarded-For", "203.0.113.9, 10.0.0.1")
                        .header("X-Trace-Id", "codex-trace-02812")
                        .header("User-Agent", "catalog-service-audit-test"))
                .andExpect(status().isOk());

        var rows = jdbcTemplate.queryForList("""
                select request_source, username, method, path, status_code, success, client_ip, trace_id
                  from request_audit_logs
                """);
        assertThat(rows).hasSize(1);
        assertThat(rows.getFirst())
                .containsEntry("REQUEST_SOURCE", "ircs-catalog-service")
                .containsEntry("USERNAME", "catalog-admin@example.com")
                .containsEntry("METHOD", "GET")
                .containsEntry("PATH", "/api/v1/catalog/standard-categories")
                .containsEntry("STATUS_CODE", 200)
                .containsEntry("SUCCESS", true)
                .containsEntry("CLIENT_IP", "203.0.113.9")
                .containsEntry("TRACE_ID", "codex-trace-02812");
    }

    @Test
    void skipsActuatorNoise() throws Exception {
        String url = "jdbc:h2:mem:catalog_service_audit_skip;MODE=PostgreSQL;DB_CLOSE_DELAY=-1";
        createAuditTable(url);
        JdbcTemplate jdbcTemplate = new JdbcTemplate(new DriverManagerDataSource(url));
        ServiceRequestAuditFilter filter = new ServiceRequestAuditFilter(true, "ircs-catalog-service", jdbcTemplate);

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
