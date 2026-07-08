package com.prodigalgal.ircs.common.audit;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.DriverManager;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.web.MockHttpServletRequest;

class ProxyRequestAuditWriterTest {

    @Test
    void writesAuditLogForProxyRequest() throws Exception {
        String url = "jdbc:h2:mem:audit;MODE=PostgreSQL;DB_CLOSE_DELAY=-1";
        createAuditTables(url);

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/portal/search/suggest");
        request.setQueryString("keyword=codex");
        request.addHeader("X-Authenticated-User", "member@example.com");
        request.addHeader("X-Forwarded-For", "203.0.113.10, 10.0.0.1");
        request.addHeader("User-Agent", "api-gateway-test");
        request.addHeader("traceparent", "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-00");

        ProxyRequestAuditWriter writer = new ProxyRequestAuditWriter(true, url, null, null, "ircs-api-gateway");
        writer.record(request, 200, Duration.ofMillis(42), null);

        try (var connection = DriverManager.getConnection(url);
             var result = connection.createStatement().executeQuery(
                     """
                             select audit_class, request_source, username, method, path, query_string, status_code,
                                    success, duration_ms, client_ip, user_agent, trace_id
                             from request_audit_logs
                             """)) {
            assertThat(result.next()).isTrue();
            assertThat(result.getString("audit_class")).isEqualTo("BEHAVIOR");
            assertThat(result.getString("request_source")).isEqualTo("ircs-api-gateway");
            assertThat(result.getString("username")).isEqualTo("member@example.com");
            assertThat(result.getString("method")).isEqualTo("GET");
            assertThat(result.getString("path")).isEqualTo("/api/portal/search/suggest");
            assertThat(result.getString("query_string")).isEqualTo("keyword=codex");
            assertThat(result.getInt("status_code")).isEqualTo(200);
            assertThat(result.getBoolean("success")).isTrue();
            assertThat(result.getLong("duration_ms")).isEqualTo(42L);
            assertThat(result.getString("client_ip")).isEqualTo("203.0.113.10");
            assertThat(result.getString("user_agent")).isEqualTo("api-gateway-test");
            assertThat(result.getString("trace_id")).isEqualTo("4bf92f3577b34da6a3ce929d0e0e4736");
            assertThat(result.next()).isFalse();
        }
    }

    @Test
    void excludesAuditQueryNoise() throws Exception {
        String url = "jdbc:h2:mem:audit_excluded;MODE=PostgreSQL;DB_CLOSE_DELAY=-1";
        createAuditTables(url);

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/ops/request-audit");
        ProxyRequestAuditWriter writer = new ProxyRequestAuditWriter(true, url, null, null);

        writer.record(request, 200, Duration.ofMillis(1), null);

        try (var connection = DriverManager.getConnection(url);
             var result = connection.createStatement().executeQuery("select count(*) from request_audit_logs")) {
            assertThat(result.next()).isTrue();
            assertThat(result.getLong(1)).isZero();
        }
    }

    @Test
    void writesAuditLogThroughInjectedInsertExecutor() throws Exception {
        String url = "jdbc:h2:mem:audit_jdbc_template;MODE=PostgreSQL;DB_CLOSE_DELAY=-1";
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource(url));
        createAuditTables(jdbcTemplate);

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/dashboard/metrics");
        request.addHeader("X-Authenticated-User", "admin");
        ProxyRequestAuditWriter writer = new ProxyRequestAuditWriter(true, jdbcTemplate::update, "ircs-api-gateway");

        writer.record(request, 200, Duration.ofMillis(17), null);

        Integer count = jdbcTemplate.queryForObject("select count(*) from request_audit_logs", Integer.class);
        String source = jdbcTemplate.queryForObject(
                "select request_source from request_audit_logs",
                String.class);
        Long duration = jdbcTemplate.queryForObject(
                "select duration_ms from request_audit_logs",
                Long.class);
        assertThat(count).isEqualTo(1);
        assertThat(source).isEqualTo("ircs-api-gateway");
        assertThat(duration).isEqualTo(17L);
    }

    @Test
    void redactsSensitiveQueryParametersForProxyRequest() throws Exception {
        String url = "jdbc:h2:mem:audit_redacted;MODE=PostgreSQL;DB_CLOSE_DELAY=-1";
        createAuditTables(url);

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/portal/profile");
        request.setQueryString("token=secret-token&keyword=codex&password=secret-password");
        ProxyRequestAuditWriter writer = new ProxyRequestAuditWriter(true, url, null, null, "ircs-api-gateway");

        writer.record(request, 200, Duration.ofMillis(1), null);

        try (var connection = DriverManager.getConnection(url);
             var result = connection.createStatement().executeQuery("select query_string from request_audit_logs")) {
            assertThat(result.next()).isTrue();
            assertThat(result.getString("query_string"))
                    .isEqualTo("token=***&keyword=codex&password=***")
                    .doesNotContain("secret-token")
                    .doesNotContain("secret-password");
        }
    }

    private static void createAuditTables(String url) throws Exception {
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

    private static void createAuditTables(JdbcTemplate jdbcTemplate) {
        jdbcTemplate.execute("""
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

    private static DriverManagerDataSource dataSource(String url) {
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setUrl(url);
        return dataSource;
    }
}
