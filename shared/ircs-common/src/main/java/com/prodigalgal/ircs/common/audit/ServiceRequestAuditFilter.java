package com.prodigalgal.ircs.common.audit;

import com.prodigalgal.ircs.common.id.IrcsUuidGenerator;
import com.prodigalgal.ircs.common.id.IrcsUuidGenerators;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

public class ServiceRequestAuditFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(ServiceRequestAuditFilter.class);
    private static final IrcsUuidGenerator ID_GENERATOR = IrcsUuidGenerators.defaultGenerator();
    private static final int SOURCE_LIMIT = 128;
    private static final int USERNAME_LIMIT = 128;
    private static final int METHOD_LIMIT = 16;
    private static final int PATH_LIMIT = 1024;
    private static final int CLIENT_IP_LIMIT = 128;
    private static final int TRACE_ID_LIMIT = 128;
    private static final int ERROR_LIMIT = 2000;
    private static final String INSERT_SQL = """
            insert into request_audit_logs (
                id, created_at, updated_at, version, audit_class, request_source, username, method, path, query_string,
                status_code, success, duration_ms, client_ip, user_agent, trace_id, error_message
            ) values (?, now(), now(), 0, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;

    private final boolean enabled;
    private final String requestSource;
    private final JdbcTemplate jdbcTemplate;

    public ServiceRequestAuditFilter(boolean enabled, String requestSource, JdbcTemplate jdbcTemplate) {
        this.enabled = enabled;
        this.requestSource = truncate(normalize(requestSource), SOURCE_LIMIT);
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public boolean shouldNotFilter(HttpServletRequest request) {
        return !enabled || jdbcTemplate == null || request == null || excluded(request.getRequestURI());
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        Instant startedAt = Instant.now();
        String errorMessage = null;
        try {
            filterChain.doFilter(request, response);
        } catch (ServletException | IOException | RuntimeException ex) {
            errorMessage = ex.getMessage();
            throw ex;
        } finally {
            int status = response.getStatus();
            if (errorMessage != null && status < 400) {
                status = 500;
            }
            record(request, status, Duration.between(startedAt, Instant.now()), errorMessage);
        }
    }

    private void record(HttpServletRequest request, int statusCode, Duration duration, String errorMessage) {
        try {
            UUID id = ID_GENERATOR.nextId();
            AuditClass auditClass = AuditClassifiers.request(request.getRequestURI());
            jdbcTemplate.update(
                    INSERT_SQL,
                    id,
                    auditClass.name(),
                    requestSource,
                    truncate(resolveUsername(request), USERNAME_LIMIT),
                    truncate(request.getMethod(), METHOD_LIMIT),
                    truncate(request.getRequestURI(), PATH_LIMIT),
                    RequestAuditSanitizer.sanitizeQueryString(request.getQueryString()),
                    statusCode,
                    statusCode >= 200 && statusCode < 400,
                    Math.max(0L, duration == null ? 0L : duration.toMillis()),
                    truncate(resolveClientIp(request), CLIENT_IP_LIMIT),
                    request.getHeader("User-Agent"),
                    truncate(resolveTraceId(request), TRACE_ID_LIMIT),
                    truncate(errorMessage, ERROR_LIMIT));
            AuditReplicationWorkDispatcher.enqueueRequest(auditClass, id, log);
        } catch (RuntimeException ex) {
            log.warn("Service request audit write failed for {}: {}", requestSource, ex.getMessage());
        }
    }

    private static boolean excluded(String path) {
        if (!StringUtils.hasText(path)) {
            return true;
        }
        String normalized = path.toLowerCase(Locale.ROOT);
        return normalized.startsWith("/actuator/")
                || normalized.equals("/actuator")
                || normalized.equals("/favicon.ico")
                || normalized.startsWith("/assets/")
                || normalized.startsWith("/static/")
                || normalized.startsWith("/api/v1/ops/request-audit");
    }

    private static String resolveUsername(HttpServletRequest request) {
        return firstHeader(request, "X-Authenticated-User", "X-User", "X-Username", "X-User-Email");
    }

    private static String resolveClientIp(HttpServletRequest request) {
        String forwarded = normalize(request.getHeader("X-Forwarded-For"));
        if (forwarded != null) {
            int comma = forwarded.indexOf(',');
            return comma >= 0 ? forwarded.substring(0, comma).trim() : forwarded;
        }
        String realIp = normalize(request.getHeader("X-Real-IP"));
        return realIp == null ? normalize(request.getRemoteAddr()) : realIp;
    }

    private static String resolveTraceId(HttpServletRequest request) {
        String direct = firstHeader(request, "X-Trace-Id", "X-Request-Id", "X-B3-TraceId");
        if (direct != null) {
            return direct;
        }
        String traceparent = normalize(request.getHeader("traceparent"));
        if (traceparent == null) {
            return null;
        }
        String[] parts = traceparent.split("-");
        return parts.length >= 2 ? parts[1] : traceparent;
    }

    private static String firstHeader(HttpServletRequest request, String... names) {
        for (String name : names) {
            String value = normalize(request.getHeader(name));
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private static String normalize(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }
}
