package com.prodigalgal.ircs.config;


import com.prodigalgal.ircs.config.controller.CommonController;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.prodigalgal.ircs.common.audit.ServiceRequestAuditFilter;
import java.sql.DriverManager;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class ConfigServiceAuditFilterTest {

    @Test
    void writesConfigServiceHandlerAuditRow() throws Exception {
        String url = "jdbc:h2:mem:config_service_audit;MODE=PostgreSQL;DB_CLOSE_DELAY=-1";
        createAuditTable(url);
        JdbcTemplate jdbcTemplate = new JdbcTemplate(new DriverManagerDataSource(url));
        MockMvc mockMvc = MockMvcBuilders
                .standaloneSetup(new CommonController())
                .addFilters(new ServiceRequestAuditFilter(true, "ircs-config-service", jdbcTemplate))
                .build();

        mockMvc.perform(get("/api/v1/common/timezones")
                        .header("X-Authenticated-User", "admin@example.com")
                        .header("X-Forwarded-For", "198.51.100.7, 10.0.0.1")
                        .header("X-Trace-Id", "codex-trace-02811")
                        .header("User-Agent", "config-service-audit-test"))
                .andExpect(status().isOk());

        var rows = jdbcTemplate.queryForList("""
                select request_source, username, method, path, status_code, success, client_ip, trace_id
                  from request_audit_logs
                """);
        assertThat(rows).hasSize(1);
        assertThat(rows.getFirst())
                .containsEntry("REQUEST_SOURCE", "ircs-config-service")
                .containsEntry("USERNAME", "admin@example.com")
                .containsEntry("METHOD", "GET")
                .containsEntry("PATH", "/api/v1/common/timezones")
                .containsEntry("STATUS_CODE", 200)
                .containsEntry("SUCCESS", true)
                .containsEntry("CLIENT_IP", "198.51.100.7")
                .containsEntry("TRACE_ID", "codex-trace-02811");
    }

    @Test
    void skipsActuatorNoise() throws Exception {
        String url = "jdbc:h2:mem:config_service_audit_skip;MODE=PostgreSQL;DB_CLOSE_DELAY=-1";
        createAuditTable(url);
        JdbcTemplate jdbcTemplate = new JdbcTemplate(new DriverManagerDataSource(url));
        ServiceRequestAuditFilter filter = new ServiceRequestAuditFilter(true, "ircs-config-service", jdbcTemplate);

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
