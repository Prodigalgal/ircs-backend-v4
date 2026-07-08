package com.prodigalgal.ircs.common.audit;

import com.prodigalgal.ircs.common.id.IrcsUuidGenerator;
import com.prodigalgal.ircs.common.id.IrcsUuidGenerators;
import com.prodigalgal.ircs.common.worker.WorkerInstanceIds;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Metrics;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class WorkerJobAuditWriter {

    private static final Logger log = LoggerFactory.getLogger(WorkerJobAuditWriter.class);
    private static final IrcsUuidGenerator ID_GENERATOR = IrcsUuidGenerators.defaultGenerator();
    private static final int SOURCE_LIMIT = 128;
    private static final int TYPE_LIMIT = 64;
    private static final int NAME_LIMIT = 128;
    private static final int CORRELATION_LIMIT = 128;
    private static final int STATUS_LIMIT = 32;
    private static final int ERROR_CLASS_LIMIT = 256;
    private static final int ERROR_MESSAGE_LIMIT = 2000;
    private static final Pattern EMAIL_PATTERN = Pattern.compile(
            "\\b[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern BEARER_PATTERN = Pattern.compile(
            "\\bBearer\\s+[A-Za-z0-9._~+/=-]+",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern TOKEN_VALUE_PATTERN = Pattern.compile(
            "\\b((?:access|refresh|id)[_-]?token|token)\\b\\s*([:=])\\s*([^\\s,;&]+)",
            Pattern.CASE_INSENSITIVE);
    private static final String INSERT_SQL = """
            insert into worker_job_audit_events (
                id, created_at, updated_at, version, audit_class, job_source, job_type, job_name,
                correlation_id, status, duration_ms, error_class, error_message
            ) values (?, now(), now(), 0, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;

    private final boolean enabled;
    private final String datasourceUrl;
    private final String username;
    private final String password;
    private final String jobSource;
    private final WorkerJobMetrics metrics;

    public WorkerJobAuditWriter(
            boolean enabled,
            String datasourceUrl,
            String username,
            String password,
            String jobSource) {
        this(enabled, datasourceUrl, username, password, jobSource, null, Metrics.globalRegistry);
    }

    public WorkerJobAuditWriter(
            boolean enabled,
            String datasourceUrl,
            String username,
            String password,
            String jobSource,
            String workerInstanceId) {
        this(enabled, datasourceUrl, username, password, jobSource, workerInstanceId, Metrics.globalRegistry);
    }

    public WorkerJobAuditWriter(
            boolean enabled,
            String datasourceUrl,
            String username,
            String password,
            String jobSource,
            String workerInstanceId,
            MeterRegistry meterRegistry) {
        this.enabled = enabled;
        this.datasourceUrl = normalize(datasourceUrl);
        this.username = normalize(username);
        this.password = password == null ? "" : password;
        this.jobSource = truncate(
                WorkerInstanceIds.resolve(defaultIfBlank(jobSource, "worker"), workerInstanceId),
                SOURCE_LIMIT);
        this.metrics = meterRegistry == null ? WorkerJobMetrics.noop() : new WorkerJobMetrics(meterRegistry, this.jobSource);
    }

    public static WorkerJobAuditWriter noop() {
        return new WorkerJobAuditWriter(false, null, null, null, "worker", null, null);
    }

    public void record(WorkerJobAuditEvent event) {
        if (event == null) {
            return;
        }
        metrics.record(event);
        if (!enabled || datasourceUrl == null) {
            return;
        }
        String jobType = truncate(normalize(event.jobType()), TYPE_LIMIT);
        String jobName = truncate(normalize(event.jobName()), NAME_LIMIT);
        String status = truncate(normalize(event.status()), STATUS_LIMIT);
        if (jobType == null || jobName == null || status == null) {
            log.warn("Worker job audit skipped for {} because required fields are missing", jobSource);
            return;
        }
        try (Connection connection = connection();
             PreparedStatement statement = connection.prepareStatement(INSERT_SQL)) {
            UUID id = ID_GENERATOR.nextId();
            statement.setObject(1, id);
            statement.setString(2, AuditClass.SYSTEM.name());
            statement.setString(3, jobSource);
            statement.setString(4, jobType);
            statement.setString(5, jobName);
            statement.setString(6, truncate(normalize(event.correlationId()), CORRELATION_LIMIT));
            statement.setString(7, status);
            statement.setLong(8, Math.max(0L, event.duration() == null ? 0L : event.duration().toMillis()));
            statement.setString(9, errorClass(event.error()));
            statement.setString(10, errorMessage(event.error()));
            statement.executeUpdate();
            AuditReplicationWorkDispatcher.enqueueWorkerJob(AuditClass.SYSTEM, id, log);
        } catch (SQLException | RuntimeException ex) {
            log.warn("Worker job audit write failed for {}: {}", jobSource, ex.getMessage());
        }
    }

    private Connection connection() throws SQLException {
        if (username == null) {
            return DriverManager.getConnection(datasourceUrl);
        }
        return DriverManager.getConnection(datasourceUrl, username, password);
    }

    private static String errorClass(Throwable error) {
        return error == null ? null : truncate(error.getClass().getName(), ERROR_CLASS_LIMIT);
    }

    private static String errorMessage(Throwable error) {
        if (error == null) {
            return null;
        }
        String message = normalize(error.getMessage());
        if (message == null) {
            return null;
        }
        String lower = message.toLowerCase(Locale.ROOT);
        if (lower.contains("authorization")
                || lower.contains("cookie")
                || lower.contains("credential")
                || lower.contains("password")
                || lower.contains("secret")
                || lower.contains("request body")) {
            return "[redacted-sensitive-error]";
        }
        String redactedBearer = BEARER_PATTERN.matcher(message).replaceAll("Bearer [redacted]");
        String redactedToken = TOKEN_VALUE_PATTERN.matcher(redactedBearer).replaceAll("$1$2[redacted-token]");
        String redactedEmail = EMAIL_PATTERN.matcher(redactedToken).replaceAll("[redacted-email]");
        return truncate(redactedEmail, ERROR_MESSAGE_LIMIT);
    }

    private static String defaultIfBlank(String value, String fallback) {
        String normalized = normalize(value);
        return normalized == null ? fallback : normalized;
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
