package com.prodigalgal.ircs.scraper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.prodigalgal.ircs.common.audit.ServiceRequestAuditFilter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.Cookie;
import java.sql.DriverManager;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class ScraperServiceAuditFilterTest {

    private final ManualScraperService manualScraperService = org.mockito.Mockito.mock(ManualScraperService.class);

    @Test
    void writesScraperServiceHandlerAuditRowWithoutSensitiveValues() throws Exception {
        String url = "jdbc:h2:mem:scraper_service_audit;MODE=PostgreSQL;DB_CLOSE_DELAY=-1";
        createAuditTable(url);
        JdbcTemplate jdbcTemplate = new JdbcTemplate(new DriverManagerDataSource(url));
        UUID responseSessionId = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
        when(manualScraperService.initSession(any(ScraperDtos.ManualScrapeConfigRequest.class)))
                .thenReturn(responseSessionId);
        MockMvc mockMvc = MockMvcBuilders
                .standaloneSetup(new ManualScraperController(manualScraperService))
                .addFilters(new ServiceRequestAuditFilter(true, "ircs-scraper-service", jdbcTemplate))
                .build();

        mockMvc.perform(post("/api/v1/scraper/manual/init?token=query-secret-token&mode=manual&password=query-secret-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "keyword": "codex",
                                  "startPage": 1,
                                  "endPage": 1,
                                  "userAgent": "body-secret-token",
                                  "enableRandomUa": false,
                                  "useCustomProxy": true,
                                  "proxyType": "http",
                                  "proxyHost": "127.0.0.1",
                                  "proxyPort": 8080,
                                  "proxyUsername": "body-user",
                                  "proxyPassword": "body-secret-password",
                                  "headers": "Authorization=body-secret-header",
                                  "fixedDelayMs": 0,
                                  "forceIngest": false,
                                  "directItems": []
                                }
                                """)
                        .header("X-Authenticated-User", "scraper-user@example.com")
                        .header("X-Forwarded-For", "203.0.113.127, 10.0.0.1")
                        .header("X-Trace-Id", "codex-trace-02812-scraper")
                        .header("Authorization", "Bearer header-secret-token")
                        .cookie(new Cookie("SESSION", "secret-session-cookie"))
                        .header("User-Agent", "scraper-service-audit-test"))
                .andExpect(status().isOk());

        var rows = jdbcTemplate.queryForList("""
                select request_source, username, method, path, query_string, status_code, success,
                       client_ip, user_agent, trace_id, error_message
                  from request_audit_logs
                """);
        assertThat(rows).hasSize(1);
        assertThat(rows.getFirst())
                .containsEntry("REQUEST_SOURCE", "ircs-scraper-service")
                .containsEntry("USERNAME", "scraper-user@example.com")
                .containsEntry("METHOD", "POST")
                .containsEntry("PATH", "/api/v1/scraper/manual/init")
                .containsEntry("QUERY_STRING", "token=***&mode=manual&password=***")
                .containsEntry("STATUS_CODE", 200)
                .containsEntry("SUCCESS", true)
                .containsEntry("CLIENT_IP", "203.0.113.127")
                .containsEntry("TRACE_ID", "codex-trace-02812-scraper");
        String auditText = rows.getFirst().values().toString();
        assertThat(auditText)
                .doesNotContain("query-secret-token")
                .doesNotContain("query-secret-password")
                .doesNotContain("header-secret-token")
                .doesNotContain("secret-session-cookie")
                .doesNotContain("body-secret-token")
                .doesNotContain("body-secret-password")
                .doesNotContain("body-secret-header")
                .doesNotContain(responseSessionId.toString());
    }

    @Test
    void writesFailedAuditRowForInvalidStreamRequestWithoutSensitiveValues() throws Exception {
        String url = "jdbc:h2:mem:scraper_service_audit_failed_stream;MODE=PostgreSQL;DB_CLOSE_DELAY=-1";
        createAuditTable(url);
        JdbcTemplate jdbcTemplate = new JdbcTemplate(new DriverManagerDataSource(url));
        ServiceRequestAuditFilter filter = new ServiceRequestAuditFilter(true, "ircs-scraper-service", jdbcTemplate);
        String invalidSessionId = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb";
        MockHttpServletRequest request = new MockHttpServletRequest(
                "GET",
                "/api/v1/scraper/manual/stream/" + invalidSessionId);
        request.setQueryString("token=query-secret-token&password=query-secret-password");
        request.addHeader("X-Authenticated-User", "scraper-user@example.com");
        request.addHeader("X-Trace-Id", "codex-trace-02812-scraper-failure");
        request.addHeader("Authorization", "Bearer header-secret-token");
        request.addHeader("User-Agent", "scraper-service-audit-test");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = (servletRequest, servletResponse) -> {
            throw new IllegalArgumentException("Session expired or invalid");
        };

        assertThatThrownBy(() -> filter.doFilter(request, response, chain))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Session expired or invalid");

        var rows = jdbcTemplate.queryForList("""
                select request_source, username, method, path, query_string, status_code, success,
                       trace_id, error_message
                  from request_audit_logs
                """);
        assertThat(rows).hasSize(1);
        assertThat(rows.getFirst())
                .containsEntry("REQUEST_SOURCE", "ircs-scraper-service")
                .containsEntry("USERNAME", "scraper-user@example.com")
                .containsEntry("METHOD", "GET")
                .containsEntry("PATH", "/api/v1/scraper/manual/stream/" + invalidSessionId)
                .containsEntry("QUERY_STRING", "token=***&password=***")
                .containsEntry("STATUS_CODE", 500)
                .containsEntry("SUCCESS", false)
                .containsEntry("TRACE_ID", "codex-trace-02812-scraper-failure")
                .containsEntry("ERROR_MESSAGE", "Session expired or invalid");
        String auditText = rows.getFirst().values().toString();
        assertThat(auditText)
                .doesNotContain("query-secret-token")
                .doesNotContain("query-secret-password")
                .doesNotContain("header-secret-token");
    }

    @Test
    void skipsActuatorNoise() throws Exception {
        String url = "jdbc:h2:mem:scraper_service_audit_skip;MODE=PostgreSQL;DB_CLOSE_DELAY=-1";
        createAuditTable(url);
        JdbcTemplate jdbcTemplate = new JdbcTemplate(new DriverManagerDataSource(url));
        ServiceRequestAuditFilter filter = new ServiceRequestAuditFilter(true, "ircs-scraper-service", jdbcTemplate);

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
