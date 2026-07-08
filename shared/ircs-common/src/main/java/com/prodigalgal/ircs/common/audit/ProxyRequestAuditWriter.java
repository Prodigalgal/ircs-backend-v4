package com.prodigalgal.ircs.common.audit;

import jakarta.servlet.http.HttpServletRequest;
import com.prodigalgal.ircs.common.id.IrcsUuidGenerator;
import com.prodigalgal.ircs.common.id.IrcsUuidGenerators;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Locale;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ProxyRequestAuditWriter {

    private static final Logger log = LoggerFactory.getLogger(ProxyRequestAuditWriter.class);
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
    private final AuditInsertExecutor insertExecutor;
    private final String datasourceUrl;
    private final String username;
    private final String password;
    private final String requestSource;

    public ProxyRequestAuditWriter(boolean enabled, String datasourceUrl, String username, String password) {
        this(enabled, datasourceUrl, username, password, "api-gateway");
    }

    public ProxyRequestAuditWriter(
            boolean enabled,
            String datasourceUrl,
            String username,
            String password,
            String requestSource) {
        this(enabled, null, datasourceUrl, username, password, requestSource);
    }

    public ProxyRequestAuditWriter(boolean enabled, AuditInsertExecutor insertExecutor, String requestSource) {
        this(enabled, insertExecutor, null, null, null, requestSource);
    }

    private ProxyRequestAuditWriter(
            boolean enabled,
            AuditInsertExecutor insertExecutor,
            String datasourceUrl,
            String username,
            String password,
            String requestSource) {
        this.enabled = enabled;
        this.insertExecutor = insertExecutor;
        this.datasourceUrl = normalize(datasourceUrl);
        this.username = normalize(username);
        this.password = password == null ? "" : password;
        this.requestSource = normalize(requestSource);
    }

    public void record(HttpServletRequest request, int statusCode, Duration duration, String errorMessage) {
        if (!enabled || request == null || excluded(request.getRequestURI()) || (insertExecutor == null && datasourceUrl == null)) {
            return;
        }
        UUID id = ID_GENERATOR.nextId();
        AuditClass auditClass = AuditClassifiers.request(request.getRequestURI());
        Object[] args = auditArgs(request, statusCode, duration, errorMessage, id, auditClass);
        try {
            if (insertExecutor != null) {
                insertExecutor.insert(INSERT_SQL, args);
            } else {
                try (Connection connection = connection();
                     PreparedStatement statement = connection.prepareStatement(INSERT_SQL)) {
                    bind(statement, args);
                    statement.executeUpdate();
                }
            }
            AuditReplicationWorkDispatcher.enqueueRequest(auditClass, id, log);
        } catch (SQLException ex) {
            log.warn("Proxy request audit write failed: {}", ex.getMessage());
        } catch (RuntimeException ex) {
            log.warn("Proxy request audit write failed: {}", ex.getMessage());
        }
    }

    private Object[] auditArgs(
            HttpServletRequest request,
            int statusCode,
            Duration duration,
            String errorMessage,
            UUID id,
            AuditClass auditClass) {
        return new Object[] {
                id,
                auditClass.name(),
                truncate(requestSource, SOURCE_LIMIT),
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
                truncate(errorMessage, ERROR_LIMIT)
        };
    }

    private static void bind(PreparedStatement statement, Object[] args) throws SQLException {
        for (int i = 0; i < args.length; i++) {
            statement.setObject(i + 1, args[i]);
        }
    }

    private Connection connection() throws SQLException {
        if (username == null) {
            return DriverManager.getConnection(datasourceUrl);
        }
        return DriverManager.getConnection(datasourceUrl, username, password);
    }

    private static boolean excluded(String path) {
        if (path == null || path.isBlank()) {
            return true;
        }
        String normalized = path.toLowerCase(Locale.ROOT);
        return normalized.startsWith("/actuator/")
                || normalized.equals("/favicon.ico")
                || normalized.startsWith("/assets/")
                || normalized.startsWith("/static/")
                || normalized.startsWith("/api/v1/ops/request-audit");
    }

    private static String resolveUsername(HttpServletRequest request) {
        String value = firstHeader(request, "X-Authenticated-User", "X-User", "X-Username", "X-User-Email");
        return normalize(value);
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

    @FunctionalInterface
    public interface AuditInsertExecutor {

        void insert(String sql, Object[] args);
    }
}
